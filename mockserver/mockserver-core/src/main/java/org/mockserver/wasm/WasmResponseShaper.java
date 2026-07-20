package org.mockserver.wasm;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.slf4j.event.Level.WARN;

/**
 * Applies a WASM module's optional {@code shape_response} export to the response a matched expectation
 * would return, enabling WASM-computed dynamic responses (ABI v3).
 * <p>
 * <strong>Fail-safe.</strong> Shaping is best-effort and never fails a request: any trap, oversized or
 * out-of-bounds return, or invalid JSON from the module leaves the materialised response untouched and
 * logs a single WARN <em>per module</em> (subsequent failures of the same module are silent, so a broken
 * module cannot flood the log). A module that does not export {@code shape_response}, or that returns
 * {@code 0} (opt out), is a no-op with no log.
 * <p>
 * <strong>Apply semantics.</strong> The module returns a possibly-partial response
 * {@code {statusCode?, headers?, body?}}: a present {@code statusCode} replaces the status; each entry
 * in a present {@code headers} object is merged in (overwriting a same-named header, adding new ones,
 * leaving unmentioned headers intact); a present, non-null {@code body} replaces the body. Absent fields
 * leave the corresponding response part unchanged.
 */
public class WasmResponseShaper {

    /**
     * Modules (keyed by name) already warned about, so each broken module logs at most once. Static so the
     * dedup holds across the short-lived {@code WasmResponseShaper} instances created per response.
     */
    private static final Set<String> WARNED_MODULES = ConcurrentHashMap.newKeySet();

    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;

    public WasmResponseShaper(MockServerLogger mockServerLogger) {
        this(mockServerLogger, null);
    }

    /**
     * @param configuration the live configuration supplying the WASM limits; {@code null} falls back to
     *                      the static property store
     */
    public WasmResponseShaper(MockServerLogger mockServerLogger, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
    }

    /**
     * Shape {@code response} in place using the module's {@code shape_response} export, if any. A no-op
     * (leaving {@code response} unchanged) when the module does not shape, opts out, or fails.
     *
     * @param response   the materialised response to (possibly) rewrite — mutated in place
     * @param request    the matched request (exposed to the module under the envelope's {@code request})
     * @param moduleName the WASM module name (used for the once-per-module warning key)
     * @param wasmBytes  the module bytes
     */
    public void shape(HttpResponse response, HttpRequest request, String moduleName, byte[] wasmBytes) {
        try {
            WasmRequest wasmRequest = request != null
                ? WasmRequest.fromHttpRequest(request, request.getBodyAsString())
                : WasmRequest.ofBody(null);
            WasmResponse shaped = new WasmRuntime(wasmBytes, configuration).callShape(wasmRequest, currentResponse(response));
            if (shaped != null) {
                applyShaped(response, shaped);
            }
        } catch (Exception e) {
            warnOnce(moduleName, e);
        }
    }

    /**
     * Snapshot the response the expectation would return (status, headers, body) into the neutral
     * {@link WasmResponse} view serialised into the shape envelope.
     */
    private static WasmResponse currentResponse(HttpResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Header header : response.getHeaderList()) {
            List<String> values = new ArrayList<>();
            if (header.getValues() != null) {
                for (NottableString value : header.getValues()) {
                    values.add(value == null ? null : value.getValue());
                }
            }
            headers.put(header.getName().getValue(), values);
        }
        return new WasmResponse(response.getStatusCode(), headers, response.getBodyAsString());
    }

    /**
     * Merge the module's parsed output into the response: replace status/body when present, and
     * set/overwrite each returned header by name (leaving unmentioned headers intact).
     */
    @VisibleForTesting
    static void applyShaped(HttpResponse response, WasmResponse shaped) {
        if (shaped.getStatusCode() != null) {
            response.withStatusCode(shaped.getStatusCode());
        }
        if (shaped.getHeaders() != null) {
            for (Map.Entry<String, List<String>> entry : shaped.getHeaders().entrySet()) {
                List<String> values = entry.getValue() == null ? List.of() : entry.getValue();
                if (values.isEmpty()) {
                    // empty/null values mean "remove this header" — replaceHeader with empty varargs
                    // would substitute the ".*" matcher wildcard as a literal wire value
                    response.removeHeader(entry.getKey());
                } else {
                    response.replaceHeader(entry.getKey(), values.toArray(new String[0]));
                }
            }
        }
        if (shaped.getBody() != null) {
            response.withBody(shaped.getBody());
        }
    }

    private void warnOnce(String moduleName, Exception e) {
        String key = moduleName == null ? "" : moduleName;
        if (WARNED_MODULES.add(key)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(WARN)
                    .setMessageFormat("WASM module \"{}\" shape_response failed, falling back to the unshaped response (this is logged once per module) because:{}")
                    .setArguments(moduleName, e.getMessage())
                    .setThrowable(e)
            );
        }
    }

    /**
     * Clear the once-per-module warning dedup. Intended for tests that assert warn-once behaviour across
     * separate cases; not used on the request path.
     */
    public static void resetWarnings() {
        WARNED_MODULES.clear();
    }
}
