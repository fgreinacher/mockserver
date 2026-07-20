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
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * <strong>Execution budget.</strong> A WASM module is user-supplied code with unrestricted control
 * flow, so nothing in the module itself bounds how long an invocation runs — a module containing an
 * unbounded loop would otherwise pin the calling thread forever. Because {@link #callMatch(WasmRequest)}
 * runs <em>during request matching</em>, that would wedge matcher threads and starve the event loop.
 * Every invocation is therefore run on a {@link #EXECUTOR shared daemon worker} under a wall-clock
 * budget ({@code mockserver.wasmExecutionTimeoutMillis}, default 5000ms; {@code 0} disables the budget
 * and runs inline on the calling thread). On expiry the worker is interrupted — chicory's interpreter
 * polls {@link Thread#isInterrupted()} and unwinds with a {@code ChicoryInterruptedException} — and the
 * call fails closed <em>without waiting</em> for the worker to die, so a module that somehow ignores
 * interruption still cannot delay the caller.
 * <p>
 * This class <strong>fails closed</strong>: any error returns {@code false} ({@link #callMatch}) or
 * leaves the response unshaped ({@link #callShape}). The boundary catches {@link Throwable}, not merely
 * {@link Exception}, because the guest is untrusted code whose failure modes are not all
 * {@link Exception}s. Chicory happens to convert a guest {@link StackOverflowError} into a
 * {@code ChicoryException} at its call boundary, but that is chicory's implementation detail and covers
 * only stack exhaustion <em>inside a guest call</em> — an {@link OutOfMemoryError} from an allocating
 * module, or stack exhaustion in the surrounding host code (envelope serialisation, module parsing), is
 * not wrapped. Letting any of those escape into the matcher would break the fail-closed guarantee
 * precisely when a hostile module is exercising it. Errors caught here are logged at WARN rather than
 * swallowed silently, so a genuine JVM-level problem stays diagnosable.
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

    private static final Logger LOGGER = LoggerFactory.getLogger(WasmRuntime.class);

    /**
     * Cap on live WASM worker threads. A budgeted invocation holds its worker for up to the whole
     * timeout (5s by default — far longer than a regex evaluation), so the pool is capped well below
     * {@link org.mockserver.matchers.MatchingTimeoutExecutor}'s: a hostile module burst must not be able
     * to spawn unbounded threads and turn the DoS protection into a DoS amplifier.
     */
    static final int EXECUTOR_MAX_POOL_SIZE = Math.max(32, Runtime.getRuntime().availableProcessors() * 8);

    /**
     * Shared executor running guest WASM code off the calling (matcher / event-loop) thread so an
     * unbounded module can be abandoned on timeout instead of pinning the caller.
     * <p>
     * Deliberately <strong>not</strong> {@link org.mockserver.matchers.MatchingTimeoutExecutor}, despite
     * solving the same shape of problem, for two reasons. First, that pool's saturation policy runs the
     * task <em>inline on the calling thread without a timeout</em> — a correct trade for a regex (never
     * lose a real match result) but the exact failure this class exists to prevent: an inline unbounded
     * module pins the matcher thread, and under a WASM-driven burst every subsequent call would go inline,
     * cascading into the wedge. Here a rejected submission fails <em>closed</em> instead. Second, WASM
     * invocations occupy a worker for orders of magnitude longer than regex evaluations, so sharing one
     * pool would let a slow module starve regex/XPath matching.
     * <p>
     * Core size 0 with a 60s keep-alive so idle workers reap; a {@link SynchronousQueue} means there is
     * no backlog — a task either gets a worker immediately (creating one up to {@link #EXECUTOR_MAX_POOL_SIZE})
     * or is rejected — both because queueing time would silently eat into the caller's timeout budget and
     * because an unbounded queue is itself a memory-exhaustion vector. Threads are daemons so a stuck
     * module can never block JVM shutdown.
     */
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
        0,
        EXECUTOR_MAX_POOL_SIZE,
        60L,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        new ThreadFactoryBuilder()
            .setNameFormat("mockserver-wasm-exec-%d")
            .setDaemon(true)
            .build(),
        new ThreadPoolExecutor.AbortPolicy()
    );

    private final byte[] wasmBytes;
    private final int maxMemoryPages;
    private final long executionTimeoutMillis;

    /**
     * Create a runtime with the default memory page and execution budget limits from
     * {@link org.mockserver.configuration.ConfigurationProperties#wasmMaxMemoryPages()} and
     * {@link org.mockserver.configuration.ConfigurationProperties#wasmExecutionTimeoutMillis()}.
     */
    public WasmRuntime(byte[] wasmBytes) {
        this(wasmBytes, org.mockserver.configuration.ConfigurationProperties.wasmMaxMemoryPages());
    }

    /**
     * Create a runtime whose limits come from the LIVE {@link org.mockserver.configuration.Configuration}
     * rather than the static property store.
     *
     * <p>This is the constructor production call sites must use. Reading the static store instead means a
     * limit set through a {@code Configuration} instance or {@code PUT /mockserver/configuration} is
     * accepted — the endpoint returns 200 and echoes the new value back — while the enforcement point
     * keeps using the old one. A {@code null} configuration falls back to the static store, which is the
     * correct source when no instance is in scope.
     *
     * @param wasmBytes     the compiled WASM binary
     * @param configuration the live configuration supplying the memory-page and execution-budget limits
     */
    public WasmRuntime(byte[] wasmBytes, org.mockserver.configuration.Configuration configuration) {
        this(
            wasmBytes,
            configuration == null
                ? org.mockserver.configuration.ConfigurationProperties.wasmMaxMemoryPages()
                : configuration.wasmMaxMemoryPages(),
            configuration == null
                ? org.mockserver.configuration.ConfigurationProperties.wasmExecutionTimeoutMillis()
                : configuration.wasmExecutionTimeoutMillis()
        );
    }

    /**
     * Create a runtime with an explicit memory page limit and the default execution budget from
     * {@link org.mockserver.configuration.ConfigurationProperties#wasmExecutionTimeoutMillis()}.
     *
     * @param wasmBytes      the compiled WASM binary
     * @param maxMemoryPages maximum number of WASM linear memory pages (each page is 64 KiB)
     */
    public WasmRuntime(byte[] wasmBytes, int maxMemoryPages) {
        this(wasmBytes, maxMemoryPages, org.mockserver.configuration.ConfigurationProperties.wasmExecutionTimeoutMillis());
    }

    /**
     * Create a runtime with explicit memory page and execution budget limits.
     *
     * @param wasmBytes              the compiled WASM binary
     * @param maxMemoryPages         maximum number of WASM linear memory pages (each page is 64 KiB)
     * @param executionTimeoutMillis maximum wall-clock milliseconds a single invocation may run before it
     *                               is interrupted and fails closed; {@code 0} or negative disables the
     *                               budget and runs the invocation inline on the calling thread
     */
    public WasmRuntime(byte[] wasmBytes, int maxMemoryPages, long executionTimeoutMillis) {
        this.wasmBytes = wasmBytes;
        this.maxMemoryPages = maxMemoryPages;
        this.executionTimeoutMillis = executionTimeoutMillis;
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
            return callWithinBudget(MATCH, () -> doMatch(request));
        } catch (Throwable t) {
            // fail closed — see the class javadoc for why this catches Throwable and not just Exception
            logFailedClosed(MATCH, t);
            return false;
        }
    }

    /**
     * The actual guest invocation behind {@link #callMatch(WasmRequest)}, run under the execution budget.
     */
    private boolean doMatch(WasmRequest request) {
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
        try {
            return callWithinBudget(SHAPE_RESPONSE, () -> doShape(request, response));
        } catch (WasmShapeException e) {
            // already the caller's expected failure type — propagate unchanged
            throw e;
        } catch (Throwable t) {
            // fail closed: the shaper falls back to the unshaped response and warns once per module
            logFailedClosed(SHAPE_RESPONSE, t);
            throw new WasmShapeException("WASM shape_response failed: " + t.getMessage(), t);
        }
    }

    /**
     * The actual guest invocation behind {@link #callShape}, run under the execution budget.
     */
    private WasmResponse doShape(WasmRequest request, WasmResponse response) {
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
     * Run a guest invocation under the configured wall-clock execution budget.
     * <p>
     * A non-positive budget disables the limit and runs {@code invocation} inline on the calling thread,
     * which both preserves the pre-budget behaviour exactly for users who opt out and keeps the opt-out
     * path free of any thread hand-off cost.
     * <p>
     * Otherwise the invocation runs on a {@link #EXECUTOR} worker. On expiry the worker is interrupted via
     * {@link Future#cancel(boolean)} — which returns immediately, so a module that ignores interruption
     * delays no one — and a {@link WasmExecutionBudgetException} is thrown for the caller to fail closed on.
     * <p>
     * The budget spans the <em>whole</em> invocation — module parse, instantiation and the guest call —
     * not just guest execution, since a pathological module can be slow to parse too. A first call on an
     * uncached module therefore consumes more of the budget than subsequent ones (parsing is cached by
     * {@link #MODULE_CACHE}), which is why the budget is measured in seconds rather than milliseconds:
     * budgets near the cost of parse plus thread hand-off would fail legitimate modules closed.
     *
     * @throws WasmExecutionBudgetException if the budget expires, the pool is saturated, or the calling
     *                                      thread is interrupted while waiting
     */
    private <T> T callWithinBudget(String operation, Callable<T> invocation) throws Exception {
        if (executionTimeoutMillis <= 0) {
            return invocation.call();
        }
        // Clear any interrupt flag left on a recycled worker by a previous timed-out invocation before
        // this one starts — otherwise chicory's interrupt polling would abort this (innocent) module
        // immediately, turning a prior module's timeout into a spurious non-match for an unrelated one.
        Callable<T> task = () -> {
            Thread.interrupted();
            return invocation.call();
        };
        final Future<T> future;
        try {
            future = EXECUTOR.submit(task);
        } catch (RejectedExecutionException e) {
            // Saturation means EXECUTOR_MAX_POOL_SIZE modules are already in flight. Unlike regex
            // matching we do NOT fall back to running inline: an inline unbounded module pins the calling
            // matcher thread, which is precisely the wedge this budget exists to prevent, and under a
            // WASM-driven burst every subsequent call would take that path. Fail closed instead.
            LOGGER.warn("WASM execution pool saturated ({} workers in use) — failing {} closed for module {}; reduce concurrent WASM matching load or lower mockserver.wasmExecutionTimeoutMillis so stuck modules are reclaimed sooner", EXECUTOR_MAX_POOL_SIZE, operation, moduleIdentity());
            throw new WasmExecutionBudgetException("WASM execution pool saturated", e);
        }
        try {
            return future.get(executionTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // interrupt the worker but do NOT wait for it to die — chicory's interpreter polls the
            // interrupt flag and unwinds, but the caller must be released regardless of whether it does
            future.cancel(true);
            LOGGER.warn("WASM module {} exceeded the {}ms {} execution budget — interrupted and failed closed (raise mockserver.wasmExecutionTimeoutMillis, or set it to 0 to disable the budget, if the module legitimately needs longer)", moduleIdentity(), executionTimeoutMillis, operation);
            throw new WasmExecutionBudgetException("WASM " + operation + " exceeded the " + executionTimeoutMillis + "ms execution budget", e);
        } catch (InterruptedException e) {
            // the CALLING thread was interrupted (e.g. cooperative shutdown), not the guest: abandon the
            // worker, restore the flag so the interrupt is not lost, and fail closed
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new WasmExecutionBudgetException("interrupted while awaiting WASM " + operation, e);
        } catch (ExecutionException e) {
            // unwrap so the caller sees the guest's real failure (e.g. WasmShapeException, or a
            // StackOverflowError from a recursive module) rather than an ExecutionException wrapper
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    /**
     * Log a fail-closed invocation. Budget failures already logged their own WARN with full context, so
     * they are not logged twice. An {@link Error} is logged at WARN because silently swallowing (say) an
     * {@link OutOfMemoryError} would make heap exhaustion look like a plain non-match; ordinary
     * exceptions (invalid module bytes, a trap, a missing export) are expected on this path and would be
     * per-request log noise at anything above DEBUG.
     */
    private void logFailedClosed(String operation, Throwable t) {
        if (t instanceof WasmExecutionBudgetException) {
            return;
        }
        if (t instanceof Error) {
            LOGGER.warn("WASM module {} {} failed with {} — failing closed", moduleIdentity(), operation, t.getClass().getName(), t);
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("WASM module {} {} failed — failing closed", moduleIdentity(), operation, t);
        }
    }

    /**
     * Identify the module in a log line. {@link WasmRuntime} is constructed from bytes alone and never
     * sees the user-facing module name, so the content hash (already the cache key) plus the byte length
     * is the identity available here — and it correlates directly with {@link #MODULE_CACHE} entries.
     */
    private String moduleIdentity() {
        String key = contentKey(wasmBytes);
        String hash = key == null ? "unknown" : key.substring(0, 12);
        return hash + " (" + (wasmBytes == null ? 0 : wasmBytes.length) + " bytes)";
    }

    /**
     * Raised when an invocation could not be completed within its execution budget (timed out, was
     * rejected by a saturated pool, or the calling thread was interrupted). Internal to the fail-closed
     * plumbing: {@link #callMatch(WasmRequest)} turns it into {@code false} and {@link #callShape} into a
     * {@link WasmShapeException}, so it never escapes this class.
     */
    private static final class WasmExecutionBudgetException extends RuntimeException {
        WasmExecutionBudgetException(String message, Throwable cause) {
            super(message, cause);
        }
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
