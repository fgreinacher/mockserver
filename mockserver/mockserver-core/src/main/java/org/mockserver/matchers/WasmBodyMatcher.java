package org.mockserver.matchers;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
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

    // "configuration" is an injected collaborator, not part of the matcher's identity — two matchers for
    // the same module must compare EQUAL even when handed different Configuration instances. Same
    // convention as the mockServerLogger exclusion on the sibling matchers.
    private static final String[] EXCLUDED_FIELDS = {"configuration"};
    private final String moduleName;
    private final Configuration configuration;

    public WasmBodyMatcher(String moduleName) {
        this(moduleName, null);
    }

    /**
     * @param configuration the live configuration supplying the WASM limits; {@code null} falls back to
     *                      the static property store
     */
    public WasmBodyMatcher(String moduleName, Configuration configuration) {
        this.moduleName = moduleName;
        this.configuration = configuration;
    }

    @Override
    public boolean matches(MatchDifference context, String actual) {
        // read through the live configuration where one was supplied, so wasmEnabled honours a value set
        // on a Configuration instance or via PUT /mockserver/configuration rather than only the static store
        if (!(configuration == null ? ConfigurationProperties.wasmEnabled() : configuration.wasmEnabled())) {
            return false;
        }
        byte[] wasmBytes = WasmStore.getInstance().get(moduleName);
        if (wasmBytes == null) {
            return false;
        }
        boolean result = new WasmRuntime(wasmBytes, configuration).callMatch(buildWasmRequest(context, actual));
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
            return WasmRequest.fromHttpRequest((HttpRequest) requestDefinition, body);
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
