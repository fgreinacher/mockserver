package org.mockserver.wasm;

import org.mockserver.model.Cookie;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableString;
import org.mockserver.model.Parameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of the parts of an HTTP request that a WASM matcher module can
 * inspect: the {@code method}, {@code path}, {@code queryStringParameters},
 * {@code headers}, {@code cookies}, and {@code body}.
 * <p>
 * This is the input to the <strong>richer WASM ABI</strong>. Modules that export
 * {@code match_request(i32 ptr, i32 len)} receive a JSON envelope built from this
 * object; modules that export only the legacy {@code match(i32 ptr, i32 len)}
 * receive just the {@link #getBody() body} bytes (back-compat).
 * <p>
 * <strong>Envelope version 2</strong> added {@code queryStringParameters} and
 * {@code cookies} to the envelope. Because both are additive JSON fields, modules
 * built against version 1 (which only read method/path/headers/body) keep working
 * unchanged — see {@link WasmRuntime#ENVELOPE_VERSION}.
 */
public class WasmRequest {

    private final String method;
    private final String path;
    private final Map<String, List<String>> queryStringParameters;
    private final Map<String, List<String>> headers;
    private final Map<String, String> cookies;
    private final String body;

    /**
     * Full constructor exposing every request part a module can inspect. Any {@code null}
     * map argument is replaced with an empty, mutable map so the fluent {@code withX}
     * builders can always add to it.
     */
    public WasmRequest(String method,
                       String path,
                       Map<String, List<String>> queryStringParameters,
                       Map<String, List<String>> headers,
                       Map<String, String> cookies,
                       String body) {
        this.method = method == null ? "" : method;
        this.path = path == null ? "" : path;
        this.queryStringParameters = queryStringParameters == null ? new LinkedHashMap<>() : queryStringParameters;
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
        this.cookies = cookies == null ? new LinkedHashMap<>() : cookies;
        this.body = body;
    }

    /**
     * Back-compat constructor without query parameters or cookies. Retained so existing
     * callers keep compiling; equivalent to the full constructor with empty query/cookie maps.
     */
    public WasmRequest(String method, String path, Map<String, List<String>> headers, String body) {
        this(method, path, new LinkedHashMap<>(), headers, new LinkedHashMap<>(), body);
    }

    /**
     * Convenience factory for a body-only request (legacy behaviour). The method
     * and path are empty and there are no query parameters, headers or cookies.
     */
    public static WasmRequest ofBody(String body) {
        return new WasmRequest("", "", new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), body);
    }

    /**
     * Build a {@link WasmRequest} from an {@link HttpRequest}, copying its method, path,
     * query-string parameters, headers and cookies, and using the supplied {@code body}. Shared by the
     * WASM body matcher and the response shaper so both expose an identical request envelope to a module.
     *
     * @param request the matched HTTP request (must not be null)
     * @param body    the body string to expose (the matcher passes the matched body string)
     */
    public static WasmRequest fromHttpRequest(HttpRequest request, String body) {
        WasmRequest wasmRequest = new WasmRequest(
            request.getMethod() == null ? "" : request.getMethod().getValue(),
            request.getPath() == null ? "" : request.getPath().getValue(),
            null,
            null,
            null,
            body
        );
        for (Header header : request.getHeaderList()) {
            if (header.getValues() != null) {
                for (NottableString value : header.getValues()) {
                    wasmRequest.withHeader(header.getName().getValue(), value == null ? null : value.getValue());
                }
            }
        }
        if (request.getQueryStringParameters() != null) {
            for (Parameter parameter : request.getQueryStringParameters().getEntries()) {
                if (parameter.getValues() != null) {
                    for (NottableString value : parameter.getValues()) {
                        wasmRequest.withQueryStringParameter(parameter.getName().getValue(), value == null ? null : value.getValue());
                    }
                }
            }
        }
        for (Cookie cookie : request.getCookieList()) {
            wasmRequest.withCookie(
                cookie.getName().getValue(),
                cookie.getValue() == null ? null : cookie.getValue().getValue()
            );
        }
        return wasmRequest;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, List<String>> getQueryStringParameters() {
        return queryStringParameters;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public Map<String, String> getCookies() {
        return cookies;
    }

    /**
     * First value of the named header (case-insensitive), or {@code null} if absent.
     */
    public String getFirstHeader(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                List<String> values = entry.getValue();
                return values == null || values.isEmpty() ? null : values.get(0);
            }
        }
        return null;
    }

    public String getBody() {
        return body;
    }

    /**
     * Adds a header value, preserving insertion order and allowing multiple values
     * per name. Returns {@code this} for chaining.
     */
    public WasmRequest withHeader(String name, String value) {
        if (name != null) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return this;
    }

    /**
     * Adds a query-string parameter value, preserving insertion order and allowing
     * multiple values per name (e.g. {@code ?id=1&id=2}). Returns {@code this} for chaining.
     */
    public WasmRequest withQueryStringParameter(String name, String value) {
        if (name != null) {
            queryStringParameters.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return this;
    }

    /**
     * Adds a cookie (name to single value). A repeated name overwrites the prior value,
     * matching HTTP cookie semantics. Returns {@code this} for chaining.
     */
    public WasmRequest withCookie(String name, String value) {
        if (name != null) {
            cookies.put(name, value);
        }
        return this;
    }
}
