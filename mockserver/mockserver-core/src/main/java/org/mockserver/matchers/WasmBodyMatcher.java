package org.mockserver.matchers;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.wasm.WasmRequest;
import org.mockserver.wasm.WasmRuntime;
import org.mockserver.wasm.WasmStore;

/**
 * Body matcher that delegates matching to a WASM module loaded in the {@link WasmStore}.
 * <p>
 * When WASM support is disabled ({@code wasmEnabled=false}), the matcher always
 * returns {@code false} (no match) — consistent with the fail-closed design.
 * <p>
 * In addition to the request body, the matcher exposes the request method, path and
 * headers to the module via the richer WASM ABI (see {@link WasmRuntime}). Modules
 * that only export the legacy body-only {@code match} function continue to work
 * unchanged.
 * <p>
 * Fails closed: returns {@code false} if the module is not loaded or throws.
 */
public class WasmBodyMatcher extends BodyMatcher<String> {

    private static final String[] EXCLUDED_FIELDS = new String[0];
    private final String moduleName;

    public WasmBodyMatcher(String moduleName) {
        this.moduleName = moduleName;
    }

    @Override
    public boolean matches(MatchDifference context, String actual) {
        if (!ConfigurationProperties.wasmEnabled()) {
            return false;
        }
        byte[] wasmBytes = WasmStore.getInstance().get(moduleName);
        if (wasmBytes == null) {
            return false;
        }
        boolean result = new WasmRuntime(wasmBytes).callMatch(buildWasmRequest(context, actual));
        return not != result;
    }

    /**
     * Build the {@link WasmRequest} envelope from the matched body plus, when available,
     * the method/path/query-parameters/headers/cookies carried on the {@link MatchDifference}
     * context. Falls back to a body-only request when no request context is present (keeps the
     * matcher usable outside the request-matching path).
     */
    private WasmRequest buildWasmRequest(MatchDifference context, String body) {
        RequestDefinition requestDefinition = context == null ? null : context.getHttpRequest();
        if (requestDefinition instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) requestDefinition;
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
                    for (org.mockserver.model.NottableString value : header.getValues()) {
                        wasmRequest.withHeader(header.getName().getValue(), value == null ? null : value.getValue());
                    }
                }
            }
            if (request.getQueryStringParameters() != null) {
                for (org.mockserver.model.Parameter parameter : request.getQueryStringParameters().getEntries()) {
                    if (parameter.getValues() != null) {
                        for (org.mockserver.model.NottableString value : parameter.getValues()) {
                            wasmRequest.withQueryStringParameter(parameter.getName().getValue(), value == null ? null : value.getValue());
                        }
                    }
                }
            }
            for (org.mockserver.model.Cookie cookie : request.getCookieList()) {
                wasmRequest.withCookie(
                    cookie.getName().getValue(),
                    cookie.getValue() == null ? null : cookie.getValue().getValue()
                );
            }
            return wasmRequest;
        }
        return WasmRequest.ofBody(body);
    }

    @Override
    public boolean isBlank() {
        return moduleName == null || moduleName.isEmpty();
    }

    @Override
    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }
}
