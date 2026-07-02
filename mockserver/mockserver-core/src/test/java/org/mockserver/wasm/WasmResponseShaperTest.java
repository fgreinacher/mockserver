package org.mockserver.wasm;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests {@link WasmResponseShaper}: applying a module's {@code shape_response} output to a real
 * {@link HttpResponse} (status replace, header merge, body replace) and the fail-safe behaviour when a
 * module is absent, does not shape, or fails.
 */
public class WasmResponseShaperTest {

    private final WasmResponseShaper shaper = new WasmResponseShaper(new MockServerLogger());

    @Before
    public void resetWarnings() {
        WasmResponseShaper.resetWarnings();
    }

    private static byte[] module(String resource) throws IOException {
        try (InputStream in = WasmResponseShaperTest.class.getResourceAsStream(resource)) {
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

    private static HttpRequest shapeRequest() {
        return request().withMethod("POST").withPath("/shape");
    }

    @Test
    public void shouldMergeHeaderReplaceStatusAndRewriteBody() throws IOException {
        // given a response the expectation would return, with a pre-existing header
        HttpResponse response = response()
            .withStatusCode(201)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"name\":\"acme\"}");

        // when
        shaper.shape(response, shapeRequest(), "shaper", module("shape-response.wasm"));

        // then - status replaced, body rewritten, X-Shaped added, Content-Type preserved (merge)
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("{\"greeting\":\"Hello, acme!\",\"shaped\":true}"));
        assertThat(response.getFirstHeader("X-Shaped"), is("true"));
        assertThat(response.getFirstHeader("Content-Type"), is("application/json"));
    }

    @Test
    public void shouldRemoveHeaderWhenShapedValuesEmpty() {
        // an entry with an empty (or null) values list removes the header instead of
        // writing the ".*" matcher wildcard that replaceHeader substitutes for empty varargs
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("X-Remove-Me", "present")
            .withHeader("X-Keep", "kept");
        java.util.Map<String, java.util.List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put("X-Remove-Me", java.util.Collections.emptyList());
        headers.put("X-Null-Values", null);
        WasmResponse shaped = new WasmResponse(null, headers, null);
        WasmResponseShaper.applyShaped(response, shaped);
        assertThat(response.getFirstHeader("X-Remove-Me"), is(""));
        assertThat(response.containsHeader("X-Remove-Me"), is(false));
        assertThat(response.containsHeader("X-Null-Values"), is(false));
        assertThat(response.getFirstHeader("X-Keep"), is("kept"));
    }

    @Test
    public void shouldLeaveResponseUnchangedWhenModuleDoesNotShape() throws IOException {
        // match-request.wasm exports only match_request (no shape_response)
        HttpResponse response = response().withStatusCode(201).withBody("{\"name\":\"acme\"}");
        shaper.shape(response, shapeRequest(), "matcher", module("match-request.wasm"));
        assertThat(response.getStatusCode(), is(201));
        assertThat(response.getBodyAsString(), is("{\"name\":\"acme\"}"));
    }

    @Test
    public void shouldFailSafeAndLeaveResponseUnchangedWhenModuleBytesAreInvalid() {
        // invalid WASM bytes must never fail the request — the response is returned unshaped
        HttpResponse response = response().withStatusCode(201).withBody("{\"name\":\"acme\"}");
        shaper.shape(response, shapeRequest(), "broken", new byte[]{0x00, 0x01, 0x02, 0x03});
        assertThat(response.getStatusCode(), is(201));
        assertThat(response.getBodyAsString(), is("{\"name\":\"acme\"}"));
    }

    @Test
    public void shouldFailSafeRepeatedlyWithoutThrowing() {
        // repeated failures (warn-once dedup path) must remain a safe no-op
        HttpResponse response = response().withStatusCode(201).withBody("{\"name\":\"acme\"}");
        for (int i = 0; i < 3; i++) {
            shaper.shape(response, shapeRequest(), "broken", new byte[]{0x00, 0x01});
        }
        assertThat(response.getStatusCode(), is(201));
    }
}
