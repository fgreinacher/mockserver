package org.mockserver.wasm;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

/**
 * ABI-guard and fail-safe tests for the <strong>WASM host ABI v3</strong> response-shaping hook
 * ({@code shape_response}).
 * <p>
 * The end-to-end round-trip is driven through the prebuilt example module
 * {@code shape-response.wasm} (from {@code examples/wasm/rust-shape-response/}, built on
 * {@code mockserver-wasm-sdk}), which exports <strong>both</strong> {@code match_request} and
 * {@code shape_response}. The size-cap, out-of-bounds and garbage-JSON fail-safe branches are exercised
 * directly against a real chicory {@link Memory} via {@link WasmRuntime#readShapedResult}.
 */
public class WasmRuntimeShapeAbiTest {

    private static byte[] module(String resource) throws IOException {
        try (InputStream in = WasmRuntimeShapeAbiTest.class.getResourceAsStream(resource)) {
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

    private static WasmRequest shapeRequest() {
        return new WasmRequest("POST", "/shape", null, null, null, "{}");
    }

    private static WasmResponse originalResponse() {
        // the response the expectation would return, carrying a name field to be rewritten
        return new WasmResponse(201, null, "{\"name\":\"acme\"}");
    }

    @Test
    public void shouldShapeResponseSettingHeaderAndRewritingBody() throws IOException {
        WasmRuntime runtime = new WasmRuntime(module("shape-response.wasm"));

        WasmResponse shaped = runtime.callShape(shapeRequest(), originalResponse());

        assertThat(shaped, notNullValue());
        assertThat(shaped.getStatusCode(), is(200));
        assertThat(shaped.getHeaders().get("X-Shaped"), contains("true"));
        assertThat(shaped.getBody(), is("{\"greeting\":\"Hello, acme!\",\"shaped\":true}"));
    }

    @Test
    public void shouldMatchFirstThenShapeWithTheSameModule() throws IOException {
        // both-exports flow: the module matches the request AND shapes the response
        WasmRuntime runtime = new WasmRuntime(module("shape-response.wasm"));
        assertThat(runtime.callMatch(shapeRequest()), is(true));
        assertThat(runtime.callMatch(new WasmRequest("GET", "/shape", null, null, null, "{}")), is(false));
        assertThat(runtime.callShape(shapeRequest(), originalResponse()), notNullValue());
    }

    @Test
    public void shouldFallBackToDefaultNameWhenOriginalBodyHasNoNameField() throws IOException {
        WasmRuntime runtime = new WasmRuntime(module("shape-response.wasm"));
        WasmResponse shaped = runtime.callShape(shapeRequest(), new WasmResponse(200, null, "{}"));
        assertThat(shaped.getBody(), is("{\"greeting\":\"Hello, world!\",\"shaped\":true}"));
    }

    @Test
    public void shouldReturnNullWhenModuleHasNoShapeExport() throws IOException {
        // a pure predicate module (match_request only) does not shape — not an error
        WasmRuntime runtime = new WasmRuntime(module("match-request.wasm"));
        assertThat(runtime.callShape(shapeRequest(), originalResponse()), is(nullValue()));
    }

    @Test
    public void shouldThrowWhenModuleBytesAreInvalid() {
        WasmRuntime runtime = new WasmRuntime(new byte[]{0x00, 0x01, 0x02, 0x03});
        assertThrows(WasmShapeException.class, () -> runtime.callShape(shapeRequest(), originalResponse()));
    }

    // --- readShapedResult: packed decode + fail-safe branches, against a real chicory Memory ---

    private static Memory onePage() {
        return new ByteArrayMemory(new MemoryLimits(1, 1)); // 1 * 64 KiB
    }

    private static long pack(int ptr, int len) {
        return (((long) ptr) << 32) | (len & 0xFFFFFFFFL);
    }

    @Test
    public void readShapedResultShouldReturnNullOnZero() {
        assertThat(WasmRuntime.readShapedResult(onePage(), 0L), is(nullValue()));
    }

    @Test
    public void readShapedResultShouldParseStatusHeadersAndBody() {
        Memory memory = onePage();
        byte[] json = "{\"statusCode\":204,\"headers\":{\"X-A\":[\"1\"]},\"body\":\"hi\"}".getBytes(StandardCharsets.UTF_8);
        memory.write(0, json);

        WasmResponse response = WasmRuntime.readShapedResult(memory, pack(0, json.length));

        assertThat(response.getStatusCode(), is(204));
        assertThat(response.getHeaders().get("X-A"), contains("1"));
        assertThat(response.getBody(), is("hi"));
    }

    @Test
    public void readShapedResultShouldAllowPartialReturns() {
        Memory memory = onePage();
        byte[] json = "{\"body\":\"only-body\"}".getBytes(StandardCharsets.UTF_8);
        memory.write(0, json);

        WasmResponse response = WasmRuntime.readShapedResult(memory, pack(0, json.length));

        assertThat("absent statusCode leaves it unchanged", response.getStatusCode(), is(nullValue()));
        assertThat(response.getBody(), is("only-body"));
    }

    @Test
    public void readShapedResultShouldRejectOversizedReturn() {
        // len beyond the 1 MiB cap must fail-safe (throw), never attempt an unbounded read
        WasmShapeException e = assertThrows(WasmShapeException.class,
            () -> WasmRuntime.readShapedResult(onePage(), pack(0, WasmRuntime.SHAPE_MAX_RETURN_BYTES + 1)));
        assertThat(e.getMessage(), notNullValue());
    }

    @Test
    public void readShapedResultShouldRejectOutOfBoundsRegion() {
        // ptr/len beyond the single 64 KiB page must fail-safe
        assertThrows(WasmShapeException.class,
            () -> WasmRuntime.readShapedResult(onePage(), pack(60000, 20000)));
    }

    @Test
    public void readShapedResultShouldRejectGarbageJson() {
        Memory memory = onePage();
        byte[] garbage = "not json at all <<<".getBytes(StandardCharsets.UTF_8);
        memory.write(0, garbage);
        assertThrows(WasmShapeException.class, () -> WasmRuntime.readShapedResult(memory, pack(0, garbage.length)));
    }

    @Test
    public void readShapedResultShouldRejectNonObjectJson() {
        Memory memory = onePage();
        byte[] arr = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
        memory.write(0, arr);
        assertThrows(WasmShapeException.class, () -> WasmRuntime.readShapedResult(memory, pack(0, arr.length)));
    }

    @Test
    public void buildShapeEnvelopeShouldNestRequestAndResponse() {
        String envelope = WasmRuntime.buildShapeEnvelope(
            new WasmRequest("POST", "/shape", null, null, null, "{}").withHeader("X-Tenant", "acme"),
            new WasmResponse(201, java.util.Map.of("Content-Type", List.of("application/json")), "{\"name\":\"acme\"}"));
        // version 3, request nested (with its own v2 version), response with statusCode/headers/body
        assertThat(envelope, is(notNullValue()));
        assertThat(envelope.contains("\"version\":3"), is(true));
        assertThat(envelope.contains("\"request\":{\"version\":2"), is(true));
        assertThat(envelope.contains("\"statusCode\":201"), is(true));
    }
}
