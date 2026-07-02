package org.mockserver.wasm;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * ABI-guard test for the <strong>richer WASM ABI</strong> ({@code match_request}) that
 * exposes method, path and headers in addition to the body.
 * <p>
 * The module {@code match-request.wasm} is the prebuilt example shipped in
 * {@code examples/wasm/rust-request/} (built on {@code mockserver-wasm-sdk}). It matches
 * when method is {@code POST}, path is {@code /orders} and header {@code X-Tenant} equals
 * {@code acme}. If the envelope shape or the {@code match_request} export ever changes,
 * this test fails — stopping the documented SDK/example and the runtime from drifting apart.
 */
public class WasmRuntimeRequestAbiTest {

    private static byte[] matchRequestModule() throws IOException {
        try (InputStream in = WasmRuntimeRequestAbiTest.class.getResourceAsStream("match-request.wasm")) {
            assertThat("test resource match-request.wasm must be on the classpath", in, notNullValue());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static WasmRequest request(String method, String path, String tenant) {
        WasmRequest req = new WasmRequest(method, path, null, "{}");
        if (tenant != null) {
            req.withHeader("X-Tenant", tenant);
        }
        return req;
    }

    @Test
    public void shouldMatchWhenMethodPathAndHeaderAllMatch() throws IOException {
        WasmRuntime runtime = new WasmRuntime(matchRequestModule());
        assertThat(runtime.callMatch(request("POST", "/orders", "acme")), is(true));
    }

    @Test
    public void shouldMatchWithCaseInsensitiveHeaderName() throws IOException {
        WasmRuntime runtime = new WasmRuntime(matchRequestModule());
        WasmRequest req = new WasmRequest("POST", "/orders", null, "{}").withHeader("x-tenant", "acme");
        assertThat(runtime.callMatch(req), is(true));
    }

    @Test
    public void shouldNotMatchWhenMethodDiffers() throws IOException {
        WasmRuntime runtime = new WasmRuntime(matchRequestModule());
        assertThat(runtime.callMatch(request("GET", "/orders", "acme")), is(false));
    }

    @Test
    public void shouldNotMatchWhenPathDiffers() throws IOException {
        WasmRuntime runtime = new WasmRuntime(matchRequestModule());
        assertThat(runtime.callMatch(request("POST", "/customers", "acme")), is(false));
    }

    @Test
    public void shouldNotMatchWhenHeaderMissing() throws IOException {
        WasmRuntime runtime = new WasmRuntime(matchRequestModule());
        assertThat(runtime.callMatch(request("POST", "/orders", null)), is(false));
    }

    @Test
    public void shouldNotMatchWhenHeaderValueDiffers() throws IOException {
        WasmRuntime runtime = new WasmRuntime(matchRequestModule());
        assertThat(runtime.callMatch(request("POST", "/orders", "other")), is(false));
    }

    @Test
    public void legacyBodyOnlyCallMatchDelegatesToEnvelopeRuntime() throws IOException {
        // a match_request module ignores a body-only call (no method/path/header) -> no match
        WasmRuntime runtime = new WasmRuntime(matchRequestModule());
        assertThat(runtime.callMatch("{}"), is(false));
    }

    @Test
    public void shouldBuildEnvelopeWithMethodPathHeadersAndBody() {
        WasmRequest req = new WasmRequest("POST", "/orders", null, "hi")
            .withHeader("X-Tenant", "acme")
            .withHeader("Accept", "application/json");
        String envelope = WasmRuntime.buildEnvelope(req);
        assertThat(envelope, containsString("\"method\":\"POST\""));
        assertThat(envelope, containsString("\"path\":\"/orders\""));
        assertThat(envelope, containsString("\"X-Tenant\":[\"acme\"]"));
        assertThat(envelope, containsString("\"body\":\"hi\""));
    }

    @Test
    public void shouldBuildEnvelopeWithNullBody() {
        String envelope = WasmRuntime.buildEnvelope(WasmRequest.ofBody(null));
        assertThat(envelope, containsString("\"body\":null"));
    }

    @Test
    public void shouldBuildEnvelopeWithMultiValuedHeader() {
        WasmRequest req = new WasmRequest("GET", "/", null, null);
        req.getHeaders().put("X-Multi", Arrays.asList("a", "b"));
        String envelope = WasmRuntime.buildEnvelope(req);
        assertThat(envelope, containsString("\"X-Multi\":[\"a\",\"b\"]"));
    }

    @Test
    public void shouldDeclareEnvelopeVersionTwo() {
        String envelope = WasmRuntime.buildEnvelope(WasmRequest.ofBody("hi"));
        assertThat(envelope, containsString("\"version\":2"));
    }

    @Test
    public void shouldBuildEnvelopeWithQueryStringParameters() {
        WasmRequest req = new WasmRequest("GET", "/orders", null, null, null, null)
            .withQueryStringParameter("tenant", "acme")
            .withQueryStringParameter("id", "1")
            .withQueryStringParameter("id", "2");
        String envelope = WasmRuntime.buildEnvelope(req);
        assertThat(envelope, containsString("\"queryStringParameters\":{"));
        assertThat(envelope, containsString("\"tenant\":[\"acme\"]"));
        assertThat(envelope, containsString("\"id\":[\"1\",\"2\"]"));
    }

    @Test
    public void shouldBuildEnvelopeWithCookies() {
        WasmRequest req = new WasmRequest("GET", "/", null, null, null, null)
            .withCookie("session", "abc123")
            .withCookie("empty", null);
        String envelope = WasmRuntime.buildEnvelope(req);
        assertThat(envelope, containsString("\"cookies\":{"));
        assertThat(envelope, containsString("\"session\":\"abc123\""));
        assertThat(envelope, containsString("\"empty\":null"));
    }

    @Test
    public void shouldBuildEnvelopeWithEmptyQueryAndCookiesByDefault() {
        // back-compat construction (4-arg / ofBody) still yields the v2 shape with empty objects
        String envelope = WasmRuntime.buildEnvelope(WasmRequest.ofBody("hi"));
        assertThat(envelope, containsString("\"queryStringParameters\":{}"));
        assertThat(envelope, containsString("\"cookies\":{}"));
    }
}
