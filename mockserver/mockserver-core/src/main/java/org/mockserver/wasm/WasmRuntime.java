package org.mockserver.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around a compiled chicory WASM instance.
 * <p>
 * Thread-safety: chicory {@link Instance} is NOT thread-safe, so a fresh
 * {@link Instance} is created for each invocation. The parsed {@link WasmModule},
 * by contrast, is immutable and freely reusable across threads, so it is
 * <strong>cached</strong> (see {@link #MODULE_CACHE}) keyed by a content hash of
 * the module bytes — parsing/validating the binary is chicory's most expensive
 * step and is pure given the bytes, so it is done at most once per distinct module.
 * <p>
 * <strong>ABI.</strong> Two export shapes are supported, both returning non-zero
 * for a match:
 * <ul>
 *   <li><strong>Legacy body-only</strong> — {@code match(i32 ptr, i32 len) -> i32}.
 *       The request body is written into linear memory at offset 0 and the function
 *       is called with {@code (0, bodyLength)}.</li>
 *   <li><strong>Richer request envelope</strong> — {@code match_request(i32 ptr, i32 len) -> i32}.
 *       A JSON envelope {@code {"version","method","path","queryStringParameters","headers","cookies","body"}}
 *       is written into linear memory at offset 0 and the function is called with {@code (0, jsonLength)}.
 *       This lets a module read the method, path, query parameters, headers and cookies in addition to
 *       the body. See {@link #ENVELOPE_VERSION} for how the envelope stays backward compatible.</li>
 * </ul>
 * If the module exports {@code match_request} it is preferred; otherwise the runtime
 * falls back to {@code match} with the body only, so existing body-only modules keep
 * working unchanged.
 * <p>
 * This class <strong>fails closed</strong>: any error returns {@code false}.
 */
public class WasmRuntime {

    /** Legacy body-only export name. */
    static final String MATCH = "match";
    /** Richer request-envelope export name. */
    static final String MATCH_REQUEST = "match_request";
    /** Optional response-shaping export name (ABI v3). */
    static final String SHAPE_RESPONSE = "shape_response";

    /**
     * Version of the JSON envelope passed to {@link #SHAPE_RESPONSE} (the <strong>ABI v3</strong>
     * response-shaping envelope). Distinct from {@link #ENVELOPE_VERSION} (the match envelope's version):
     * the shape envelope is {@code {"version":3,"request":{...v2 request...},"response":{...}}}, nesting the
     * whole match envelope under {@code request} and adding the response the expectation would return.
     */
    static final int SHAPE_ENVELOPE_VERSION = 3;

    /**
     * Maximum number of bytes {@link #SHAPE_RESPONSE} may return. The module returns a packed
     * {@code (ptr &lt;&lt; 32) | len} pointer into its linear memory; a {@code len} larger than this cap is
     * rejected (the runtime falls back to the unshaped response) so a misbehaving or hostile module cannot
     * force MockServer to read an unbounded region. 1 MiB comfortably covers realistic mock bodies while
     * staying well under the default {@code wasmMaxMemoryPages} (16 MiB) linear-memory ceiling.
     */
    static final int SHAPE_MAX_RETURN_BYTES = 1024 * 1024;

    /**
     * Version of the JSON envelope passed to {@link #MATCH_REQUEST}. Declared as the
     * envelope's {@code version} field so modules can feature-detect newer fields.
     * <ul>
     *   <li><strong>1</strong> — {@code method}, {@code path}, {@code headers}, {@code body}.</li>
     *   <li><strong>2</strong> — additionally {@code queryStringParameters} and {@code cookies}.</li>
     * </ul>
     * Every version is a strict superset of the previous one: new fields are additive, so a
     * module written against an older version (which simply ignores unknown fields) keeps working.
     */
    static final int ENVELOPE_VERSION = 2;

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    /**
     * Maximum number of distinct parsed modules retained in {@link #MODULE_CACHE}. Distinct
     * modules are bounded in practice by how many WASM modules a user loads, but this cap keeps
     * memory bounded even if a client uploads an unbounded stream of distinct modules. Eviction is
     * least-recently-inserted (access-ordered) and never affects correctness — entries are keyed by
     * a content hash, so the worst case of eviction is a re-parse, not a wrong result.
     */
    static final int MODULE_CACHE_MAX = 256;

    /**
     * Cache of parsed {@link WasmModule}s keyed by a hex SHA-256 of the module bytes. The parsed
     * module is immutable and reusable, so the same bytes are parsed/validated at most once. A
     * content hash (rather than the user-chosen module name) is the key so that re-uploading
     * identical bytes — or two names pointing at the same module — share a single parsed entry, and
     * so a stale entry can never be wrong (the same hash always means the same bytes). Wrapped in a
     * synchronized access-ordered LRU bounded at {@link #MODULE_CACHE_MAX}.
     * <p>
     * The cache is keyed by content, so correctness does not depend on invalidation; it is cleared
     * via {@link #invalidate(byte[])}/{@link #invalidateAll()} from {@link WasmStore} remove/reset
     * purely to release memory promptly when modules are unloaded.
     */
    static final Map<String, WasmModule> MODULE_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<String, WasmModule>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, WasmModule> eldest) {
                return size() > MODULE_CACHE_MAX;
            }
        });

    private final byte[] wasmBytes;
    private final int maxMemoryPages;

    /**
     * Create a runtime with the default memory page limit from
     * {@link org.mockserver.configuration.ConfigurationProperties#wasmMaxMemoryPages()}.
     */
    public WasmRuntime(byte[] wasmBytes) {
        this(wasmBytes, org.mockserver.configuration.ConfigurationProperties.wasmMaxMemoryPages());
    }

    /**
     * Create a runtime with an explicit memory page limit.
     *
     * @param wasmBytes      the compiled WASM binary
     * @param maxMemoryPages maximum number of WASM linear memory pages (each page is 64 KiB)
     */
    public WasmRuntime(byte[] wasmBytes, int maxMemoryPages) {
        this.wasmBytes = wasmBytes;
        this.maxMemoryPages = maxMemoryPages;
    }

    /**
     * Call the WASM module with just the request body (legacy body-only ABI).
     * <p>
     * Retained for back-compat; equivalent to {@code callMatch(WasmRequest.ofBody(requestBody))}.
     *
     * @param requestBody the HTTP request body (may be null)
     * @return {@code true} if the module reports a match
     */
    public boolean callMatch(String requestBody) {
        return callMatch(WasmRequest.ofBody(requestBody));
    }

    /**
     * Call the WASM module with the full request envelope (method, path, headers, body).
     * <p>
     * If the module exports {@link #MATCH_REQUEST} the JSON envelope is passed and that
     * function is invoked; otherwise the runtime falls back to the legacy {@link #MATCH}
     * export with only the body, preserving back-compat for body-only modules.
     *
     * @param request the request parts to expose to the module (must not be null)
     * @return {@code true} if the module reports a match
     */
    public boolean callMatch(WasmRequest request) {
        try {
            Instance instance = buildInstance(parseModule(wasmBytes));

            byte[] input;
            ExportFunction matchFn = tryExport(instance, MATCH_REQUEST);
            if (matchFn != null) {
                // richer ABI: pass the full request envelope as JSON
                input = buildEnvelope(request).getBytes(StandardCharsets.UTF_8);
            } else {
                // legacy ABI: pass only the body
                matchFn = instance.export(MATCH);
                input = request.getBody() != null
                    ? request.getBody().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            }

            // Write input into the WASM module's linear memory at offset 0
            instance.memory().write(0, input);

            long[] result = matchFn.apply(0L, input.length);
            return result.length > 0 && result[0] != 0;
        } catch (Exception e) {
            // fail closed
            return false;
        }
    }

    /**
     * Call the module's optional {@link #SHAPE_RESPONSE} export to (possibly) rewrite the response the
     * matched expectation would return. This is the <strong>ABI v3</strong> response-shaping hook.
     * <p>
     * The runtime writes the {@link #buildShapeEnvelope shape envelope} into linear memory at offset 0 and
     * calls {@code shape_response(0, len) -> i64}. The module writes its response JSON somewhere in its own
     * linear memory and returns a packed {@code (ptr &lt;&lt; 32) | len}; the runtime reads {@code len} bytes at
     * {@code ptr} and parses them into a {@link WasmResponse}. A return of {@code 0} means "no change".
     *
     * @param request  the matched request parts (serialised under the envelope's {@code request} field)
     * @param response the response the expectation would return (serialised under {@code response})
     * @return the parsed shaped response, or {@code null} when the module does not export
     *         {@link #SHAPE_RESPONSE} or explicitly opts out (returns {@code 0})
     * @throws WasmShapeException if the module traps, returns an out-of-bounds or oversized region, or
     *         returns bytes that are not a valid response JSON object — the caller falls back to the
     *         unshaped response and logs once (see {@link WasmResponseShaper})
     */
    public WasmResponse callShape(WasmRequest request, WasmResponse response) {
        Instance instance;
        ExportFunction shapeFn;
        try {
            instance = buildInstance(parseModule(wasmBytes));
            shapeFn = tryExport(instance, SHAPE_RESPONSE);
        } catch (Exception e) {
            throw new WasmShapeException("failed to instantiate WASM module for response shaping", e);
        }
        if (shapeFn == null) {
            // module is a pure predicate — it does not shape responses; not an error
            return null;
        }
        long packed;
        try {
            byte[] input = buildShapeEnvelope(request, response).getBytes(StandardCharsets.UTF_8);
            instance.memory().write(0, input);
            long[] result = shapeFn.apply(0L, input.length);
            if (result.length == 0) {
                throw new WasmShapeException("shape_response returned no value");
            }
            packed = result[0];
        } catch (WasmShapeException e) {
            throw e;
        } catch (Exception e) {
            throw new WasmShapeException("WASM shape_response trapped", e);
        }
        return readShapedResult(instance.memory(), packed);
    }

    /**
     * Decode the module's packed {@code shape_response} return value and read/parse the response JSON it
     * points at. {@code packed == 0} means "no change" (returns {@code null}). Otherwise the high 32 bits
     * are a pointer and the low 32 bits a length into {@code memory}; the length is capped at
     * {@link #SHAPE_MAX_RETURN_BYTES} and an out-of-bounds region or unparseable JSON raises a
     * {@link WasmShapeException}. Package-private so the size-cap/out-of-bounds/parse fail-safe branches
     * are directly testable against a real chicory {@link Memory}.
     */
    static WasmResponse readShapedResult(Memory memory, long packed) {
        if (packed == 0L) {
            // explicit opt-out: leave the response unchanged
            return null;
        }
        int ptr = (int) (packed >>> 32);
        int len = (int) (packed & 0xFFFFFFFFL);
        if (ptr < 0 || len < 0) {
            throw new WasmShapeException("shape_response returned an invalid pointer/length");
        }
        if (len > SHAPE_MAX_RETURN_BYTES) {
            throw new WasmShapeException("shape_response returned " + len + " bytes, exceeding the "
                + SHAPE_MAX_RETURN_BYTES + "-byte cap");
        }
        byte[] output;
        try {
            output = memory.readBytes(ptr, len);
        } catch (Exception e) {
            throw new WasmShapeException("shape_response returned an out-of-bounds memory region", e);
        }
        return parseShapedResponse(new String(output, StandardCharsets.UTF_8));
    }

    /**
     * Build a chicory {@link Instance} for the parsed module, capping its linear memory at
     * {@link #maxMemoryPages} while preserving the module's declared initial pages (needed for data
     * segment initialization). Shared by {@link #callMatch(WasmRequest)} and {@link #callShape}.
     */
    private Instance buildInstance(WasmModule module) {
        Instance.Builder builder = Instance.builder(module);
        if (module.memorySection().isPresent()
            && module.memorySection().get().memoryCount() > 0) {
            MemoryLimits declared = module.memorySection().get().getMemory(0).limits();
            int effectiveMax = Math.min(declared.maximumPages(), maxMemoryPages);
            int effectiveInit = Math.min(declared.initialPages(), effectiveMax);
            builder.withMemoryLimits(new MemoryLimits(effectiveInit, effectiveMax));
        }
        return builder.build();
    }

    /**
     * Resolve an exported function by name, returning {@code null} if the module does
     * not export it (chicory throws rather than returning null for a missing export).
     */
    private static ExportFunction tryExport(Instance instance, String name) {
        try {
            return instance.export(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Serialise the request parts into the JSON envelope passed to {@code match_request}.
     * Shape (envelope {@link #ENVELOPE_VERSION version} 2):
     * {@code {"version":2,"method":string,"path":string,"queryStringParameters":{name:[values]},
     * "headers":{name:[values]},"cookies":{name:value},"body":string|null}}.
     * <p>
     * The {@code version}, {@code queryStringParameters} and {@code cookies} fields are additive
     * over version 1, so modules that only read method/path/headers/body are unaffected.
     */
    static String buildEnvelope(WasmRequest request) {
        return requestNode(request).toString();
    }

    /**
     * Build the request envelope {@link ObjectNode} (shared by the match envelope and, nested under
     * {@code request}, by the {@link #buildShapeEnvelope shape envelope}).
     */
    private static ObjectNode requestNode(WasmRequest request) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("version", ENVELOPE_VERSION);
        root.put("method", request.getMethod());
        root.put("path", request.getPath());
        putMultiValued(root.putObject("queryStringParameters"), request.getQueryStringParameters());
        putMultiValued(root.putObject("headers"), request.getHeaders());
        ObjectNode cookies = root.putObject("cookies");
        for (Map.Entry<String, String> entry : request.getCookies().entrySet()) {
            if (entry.getValue() == null) {
                cookies.putNull(entry.getKey());
            } else {
                cookies.put(entry.getKey(), entry.getValue());
            }
        }
        if (request.getBody() == null) {
            root.putNull("body");
        } else {
            root.put("body", request.getBody());
        }
        return root;
    }

    /**
     * Serialise the {@link #SHAPE_RESPONSE} input envelope (ABI v3):
     * {@code {"version":3,"request":{...v2 request...},"response":{"statusCode":int|null,
     * "headers":{name:[values]},"body":string|null}}}. The {@code request} field is the same envelope
     * {@link #buildEnvelope match modules} receive, so a module can route the shape on any request part.
     */
    static String buildShapeEnvelope(WasmRequest request, WasmResponse response) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("version", SHAPE_ENVELOPE_VERSION);
        root.set("request", requestNode(request));
        ObjectNode resp = root.putObject("response");
        if (response.getStatusCode() == null) {
            resp.putNull("statusCode");
        } else {
            resp.put("statusCode", response.getStatusCode());
        }
        putMultiValued(resp.putObject("headers"), response.getHeaders());
        if (response.getBody() == null) {
            resp.putNull("body");
        } else {
            resp.put("body", response.getBody());
        }
        return root.toString();
    }

    /**
     * Parse the response JSON a {@link #SHAPE_RESPONSE} module returned into a {@link WasmResponse}.
     * Recognises {@code statusCode} (integer), {@code headers} (name to array-of-values or scalar) and
     * {@code body} (string); absent fields become {@code null} (unchanged). A body-only or header-only
     * return is valid — the omitted fields simply leave the corresponding response part untouched.
     *
     * @throws WasmShapeException if the bytes are not a JSON object
     */
    private static WasmResponse parseShapedResponse(String json) {
        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new WasmShapeException("shape_response returned invalid JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new WasmShapeException("shape_response must return a JSON object");
        }
        Integer statusCode = null;
        JsonNode statusNode = node.get("statusCode");
        if (statusNode != null && statusNode.isNumber()) {
            statusCode = statusNode.intValue();
        }
        Map<String, List<String>> headers = null;
        JsonNode headersNode = node.get("headers");
        if (headersNode != null && headersNode.isObject()) {
            headers = new LinkedHashMap<>();
            java.util.Iterator<String> names = headersNode.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode valueNode = headersNode.get(name);
                List<String> values = new ArrayList<>();
                if (valueNode != null && valueNode.isArray()) {
                    for (JsonNode value : valueNode) {
                        if (!value.isNull()) {
                            values.add(value.asText());
                        }
                    }
                } else if (valueNode != null && !valueNode.isNull()) {
                    values.add(valueNode.asText());
                }
                headers.put(name, values);
            }
        }
        String body = null;
        JsonNode bodyNode = node.get("body");
        if (bodyNode != null && !bodyNode.isNull()) {
            body = bodyNode.asText();
        }
        return new WasmResponse(statusCode, headers, body);
    }

    /**
     * Write a {@code name -> [values]} multi-valued map (headers or query parameters) into the
     * given JSON object, preserving multiple values and insertion order.
     */
    private static void putMultiValued(ObjectNode target, Map<String, List<String>> source) {
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            ArrayNode values = target.putArray(entry.getKey());
            if (entry.getValue() != null) {
                for (String value : entry.getValue()) {
                    values.add(value);
                }
            }
        }
    }

    /**
     * Return the parsed {@link WasmModule} for the given bytes, parsing/validating at most once per
     * distinct module. Parsing is pure given the bytes and is chicory's most expensive step, so the
     * result is cached in {@link #MODULE_CACHE} keyed by a content hash. The returned module is
     * immutable and is shared across calls/threads; each call still builds its own {@link Instance}.
     */
    private static WasmModule parseModule(byte[] wasmBytes) {
        String key = contentKey(wasmBytes);
        if (key == null) {
            // hashing unavailable (should not happen for SHA-256) — fall back to parsing every call
            return Parser.parse(wasmBytes);
        }
        // computeIfAbsent on a synchronizedMap holds the map lock for the whole parse; modules are
        // few and parsed at most once each, so the brief contention is acceptable and far cheaper
        // than re-parsing on every request.
        return MODULE_CACHE.computeIfAbsent(key, k -> Parser.parse(wasmBytes));
    }

    /**
     * Compute the cache key for a module: a lowercase hex SHA-256 of its bytes, or {@code null} if
     * SHA-256 is unavailable (in which case the caller parses without caching).
     */
    private static String contentKey(byte[] wasmBytes) {
        if (wasmBytes == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(wasmBytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * Drop the cached parsed module for the given bytes, if present. Called when a module is unloaded
     * so its parsed form is released promptly. A no-op when the bytes were never cached. Correctness
     * never depends on this (the cache is content-keyed); it only bounds memory.
     */
    public static void invalidate(byte[] wasmBytes) {
        String key = contentKey(wasmBytes);
        if (key != null) {
            MODULE_CACHE.remove(key);
        }
    }

    /**
     * Clear all cached parsed modules. Called on a full WASM store reset.
     */
    public static void invalidateAll() {
        MODULE_CACHE.clear();
    }
}
