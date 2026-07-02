package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.wasm.WasmResponseShaper;
import org.mockserver.wasm.WasmStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.WasmBody.wasmBody;

/**
 * End-to-end test that response shaping (ABI v3) is wired into the response-action materialisation path:
 * an expectation that matched on a {@link org.mockserver.model.WasmBody} whose module exports
 * {@code shape_response} has its materialised response rewritten by the module, driven through
 * {@link HttpResponseActionHandler#handle}. Also covers the {@code wasmEnabled} gate and the
 * module-not-loaded fall-through.
 */
public class HttpResponseActionHandlerWasmShapeTest {

    private final HttpResponseActionHandler handler = new HttpResponseActionHandler(new MockServerLogger(), new Configuration());
    private boolean originalWasmEnabled;

    @Before
    public void setUp() {
        originalWasmEnabled = ConfigurationProperties.wasmEnabled();
        WasmResponseShaper.resetWarnings();
    }

    @After
    public void tearDown() {
        WasmStore.getInstance().reset();
        ConfigurationProperties.wasmEnabled(originalWasmEnabled);
    }

    private static byte[] shapeModule() throws IOException {
        try (InputStream in = HttpResponseActionHandlerWasmShapeTest.class.getResourceAsStream("/org/mockserver/wasm/shape-response.wasm")) {
            assertThat("shape-response.wasm must be on the classpath", in, notNullValue());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Test
    public void shouldShapeResponseWhenMatchedExpectationCarriesAShapingWasmModule() throws IOException {
        // given - WASM enabled and a module (that matches AND shapes) loaded under "shaper"
        ConfigurationProperties.wasmEnabled(true);
        WasmStore.getInstance().put("shaper", shapeModule());

        // the matched expectation matched on a WASM body referencing that module, with a static response
        HttpResponse responseAction = response().withStatusCode(201).withBody("{\"name\":\"acme\"}");

        // when - the response is materialised against the incoming POST /shape request
        HttpResponse actual = handler.handle(
            responseAction,
            request().withMethod("POST").withPath("/shape"),
            request().withBody(wasmBody("shaper")));

        // then - the module rewrote the response
        assertThat(actual.getStatusCode(), is(200));
        assertThat(actual.getBodyAsString(), is("{\"greeting\":\"Hello, acme!\",\"shaped\":true}"));
        assertThat(actual.getFirstHeader("X-Shaped"), is("true"));
    }

    @Test
    public void shouldNotShapeWhenWasmDisabled() throws IOException {
        // given - module loaded but WASM disabled
        ConfigurationProperties.wasmEnabled(false);
        WasmStore.getInstance().put("shaper", shapeModule());

        HttpResponse actual = handler.handle(
            response().withStatusCode(201).withBody("{\"name\":\"acme\"}"),
            request().withMethod("POST").withPath("/shape"),
            request().withBody(wasmBody("shaper")));

        // then - response untouched
        assertThat(actual.getStatusCode(), is(201));
        assertThat(actual.getBodyAsString(), is("{\"name\":\"acme\"}"));
        assertThat(actual.getFirstHeader("X-Shaped"), is(""));
    }

    @Test
    public void shouldNotShapeWhenModuleNotLoaded() {
        // given - WASM enabled but the referenced module is not in the store
        ConfigurationProperties.wasmEnabled(true);

        HttpResponse actual = handler.handle(
            response().withStatusCode(201).withBody("{\"name\":\"acme\"}"),
            request().withMethod("POST").withPath("/shape"),
            request().withBody(wasmBody("missing")));

        assertThat(actual.getStatusCode(), is(201));
        assertThat(actual.getBodyAsString(), is("{\"name\":\"acme\"}"));
    }

    @Test
    public void shouldNotShapeWhenMatchedRequestHasNoWasmBody() throws IOException {
        // given - a non-WASM matched request must never invoke shaping even with a module loaded
        ConfigurationProperties.wasmEnabled(true);
        WasmStore.getInstance().put("shaper", shapeModule());

        HttpResponse actual = handler.handle(
            response().withStatusCode(201).withBody("{\"name\":\"acme\"}"),
            request().withMethod("POST").withPath("/shape"),
            request().withPath("/shape"));

        assertThat(actual.getStatusCode(), is(201));
        assertThat(actual.getBodyAsString(), is("{\"name\":\"acme\"}"));
    }
}
