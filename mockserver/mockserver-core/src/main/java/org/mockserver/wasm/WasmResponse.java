package org.mockserver.wasm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of the parts of an HTTP response that a WASM {@code shape_response} module can
 * read and rewrite: the {@code statusCode}, {@code headers}, and {@code body}.
 * <p>
 * This is the response half of the <strong>WASM host ABI v3</strong> (response shaping). It is used
 * in two directions:
 * <ul>
 *   <li>as <strong>input</strong> — the response the matched expectation <em>would</em> return,
 *       serialised into the shape envelope so the module can inspect it; and</li>
 *   <li>as <strong>output</strong> — the (possibly partial) response the module returns, parsed
 *       back out of the module's JSON.</li>
 * </ul>
 * <p>
 * <strong>Null semantics for the parsed output.</strong> A {@code null} field means "absent — leave
 * the materialised response unchanged": a {@code null} {@link #getStatusCode() statusCode} leaves the
 * status untouched, a {@code null}/empty {@link #getHeaders() headers} map adds/overwrites nothing,
 * and a {@code null} {@link #getBody() body} leaves the body unchanged. Present values replace (status
 * and body) or merge (headers) into the response. See {@link WasmResponseShaper} for how the parsed
 * output is applied.
 */
public class WasmResponse {

    private final Integer statusCode;
    private final Map<String, List<String>> headers;
    private final String body;

    /**
     * @param statusCode the HTTP status code, or {@code null} when absent/unchanged
     * @param headers    header name to list of values, or {@code null} when absent (never mutated here)
     * @param body       the response body string, or {@code null} when absent/unchanged
     */
    public WasmResponse(Integer statusCode, Map<String, List<String>> headers, String body) {
        this.statusCode = statusCode;
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
        this.body = body;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }
}
