package org.mockserver.wasm;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * ABI-guard test for <strong>envelope version 2</strong> of the richer WASM ABI
 * ({@code match_request}), which adds {@code queryStringParameters} and {@code cookies}.
 * <p>
 * The module {@code match-request-v2.wasm} is the prebuilt example shipped in
 * {@code examples/wasm/rust-request-v2/} (built on {@code mockserver-wasm-sdk}). It matches
 * when method is {@code POST}, path is {@code /orders}, the query parameter {@code tenant}
 * equals {@code acme}, and the cookie {@code session} equals {@code abc123}. If the envelope
 * shape or the version-2 fields ever change, this test fails — keeping the runtime, the SDK
 * and the documented example from drifting apart.
 * <p>
 * It also proves <strong>backward compatibility</strong>: the version-1 example module
 * {@code match-request.wasm} (which reads only method/path/header) still matches when driven
 * with a v2 envelope, because the new fields are additive and ignored by v1 modules.
 */
public class WasmRuntimeRequestV2AbiTest {

    private static byte[] module(String resource) throws IOException {
        try (InputStream in = WasmRuntimeRequestV2AbiTest.class.getResourceAsStream(resource)) {
            assertThat("test resource " + resource + " must be on the classpath", in, notNullValue());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static WasmRequest v2Request(String method, String path, String tenant, String session) {
        WasmRequest req = new WasmRequest(method, path, null, null, null, "{}");
        if (tenant != null) {
            req.withQueryStringParameter("tenant", tenant);
        }
        if (session != null) {
            req.withCookie("session", session);
        }
        return req;
    }

    @Test
    public void shouldMatchWhenQueryParamAndCookieMatch() throws IOException {
        WasmRuntime runtime = new WasmRuntime(module("match-request-v2.wasm"));
        assertThat(runtime.callMatch(v2Request("POST", "/orders", "acme", "abc123")), is(true));
    }

    @Test
    public void shouldNotMatchWhenQueryParamMissing() throws IOException {
        WasmRuntime runtime = new WasmRuntime(module("match-request-v2.wasm"));
        assertThat(runtime.callMatch(v2Request("POST", "/orders", null, "abc123")), is(false));
    }

    @Test
    public void shouldNotMatchWhenQueryParamValueDiffers() throws IOException {
        WasmRuntime runtime = new WasmRuntime(module("match-request-v2.wasm"));
        assertThat(runtime.callMatch(v2Request("POST", "/orders", "other", "abc123")), is(false));
    }

    @Test
    public void shouldNotMatchWhenQueryParamCaseDiffers() throws IOException {
        // query-parameter names are case-sensitive
        WasmRuntime runtime = new WasmRuntime(module("match-request-v2.wasm"));
        WasmRequest req = new WasmRequest("POST", "/orders", null, null, null, "{}")
            .withQueryStringParameter("Tenant", "acme")
            .withCookie("session", "abc123");
        assertThat(runtime.callMatch(req), is(false));
    }

    @Test
    public void shouldNotMatchWhenCookieMissing() throws IOException {
        WasmRuntime runtime = new WasmRuntime(module("match-request-v2.wasm"));
        assertThat(runtime.callMatch(v2Request("POST", "/orders", "acme", null)), is(false));
    }

    @Test
    public void shouldNotMatchWhenCookieValueDiffers() throws IOException {
        WasmRuntime runtime = new WasmRuntime(module("match-request-v2.wasm"));
        assertThat(runtime.callMatch(v2Request("POST", "/orders", "acme", "nope")), is(false));
    }

    @Test
    public void v1ModuleStillMatchesUnderV2Envelope() throws IOException {
        // back-compat: a version-1 module (method/path/header only) is unaffected by the
        // additive queryStringParameters/cookies fields in the v2 envelope.
        WasmRuntime runtime = new WasmRuntime(module("match-request.wasm"));
        WasmRequest req = new WasmRequest("POST", "/orders", null, null, null, "{}")
            .withHeader("X-Tenant", "acme")
            // extra v2-only fields the v1 module ignores
            .withQueryStringParameter("tenant", "acme")
            .withCookie("session", "abc123");
        assertThat(runtime.callMatch(req), is(true));
    }
}
