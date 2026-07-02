package org.mockserver.matchers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpRequest;
import org.mockserver.wasm.WasmStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link WasmBodyMatcher}.
 * <p>
 * Real WASM module execution is not tested here — chicory integration
 * tests would require building a valid WASM binary. Instead, these tests
 * verify fail-closed behaviour, wasmEnabled gating, and basic wiring.
 */
public class WasmBodyMatcherTest {

    private boolean originalWasmEnabled;

    @Before
    public void saveConfig() {
        originalWasmEnabled = ConfigurationProperties.wasmEnabled();
    }

    @After
    public void resetStore() {
        WasmStore.getInstance().reset();
        ConfigurationProperties.wasmEnabled(originalWasmEnabled);
    }

    @Test
    public void shouldReturnFalseWhenWasmDisabled() {
        ConfigurationProperties.wasmEnabled(false);
        WasmStore.getInstance().put("someModule", new byte[]{0x00, 0x01, 0x02, 0x03});
        WasmBodyMatcher matcher = new WasmBodyMatcher("someModule");
        assertThat(matcher.matches(null, "some body"), is(false));
    }

    @Test
    public void shouldReturnFalseWhenModuleNotLoaded() {
        ConfigurationProperties.wasmEnabled(true);
        WasmBodyMatcher matcher = new WasmBodyMatcher("nonexistent");
        assertThat(matcher.matches(null, "some body"), is(false));
    }

    @Test
    public void shouldReturnFalseWhenModuleBytesAreInvalidWasm() {
        ConfigurationProperties.wasmEnabled(true);
        // Store invalid WASM bytes — callMatch should fail closed
        WasmStore.getInstance().put("invalid", new byte[]{0x00, 0x01, 0x02, 0x03});
        WasmBodyMatcher matcher = new WasmBodyMatcher("invalid");
        assertThat(matcher.matches(null, "hello"), is(false));
    }

    @Test
    public void shouldReturnFalseWhenModuleNameIsNull() {
        ConfigurationProperties.wasmEnabled(true);
        WasmBodyMatcher matcher = new WasmBodyMatcher(null);
        assertThat(matcher.matches(null, "body"), is(false));
    }

    @Test
    public void shouldReportBlankWhenModuleNameIsNull() {
        WasmBodyMatcher matcher = new WasmBodyMatcher(null);
        assertThat(matcher.isBlank(), is(true));
    }

    @Test
    public void shouldReportBlankWhenModuleNameIsEmpty() {
        WasmBodyMatcher matcher = new WasmBodyMatcher("");
        assertThat(matcher.isBlank(), is(true));
    }

    @Test
    public void shouldReportNotBlankWhenModuleNameIsSet() {
        WasmBodyMatcher matcher = new WasmBodyMatcher("myModule");
        assertThat(matcher.isBlank(), is(false));
    }

    @Test
    public void shouldExposeMethodPathAndHeadersFromContextToRicherAbiModule() throws IOException {
        ConfigurationProperties.wasmEnabled(true);
        WasmStore.getInstance().put("orders", matchRequestModule());
        WasmBodyMatcher matcher = new WasmBodyMatcher("orders");

        // module matches POST /orders with X-Tenant: acme
        HttpRequest matching = HttpRequest.request()
            .withMethod("POST")
            .withPath("/orders")
            .withHeader("X-Tenant", "acme");
        assertThat(matcher.matches(new MatchDifference(false, matching), "{}"), is(true));
    }

    @Test
    public void shouldNotMatchRicherAbiModuleWhenHeaderMissingInContext() throws IOException {
        ConfigurationProperties.wasmEnabled(true);
        WasmStore.getInstance().put("orders", matchRequestModule());
        WasmBodyMatcher matcher = new WasmBodyMatcher("orders");

        HttpRequest noTenant = HttpRequest.request()
            .withMethod("POST")
            .withPath("/orders");
        assertThat(matcher.matches(new MatchDifference(false, noTenant), "{}"), is(false));
    }

    @Test
    public void shouldNotMatchRicherAbiModuleWhenMethodDiffersInContext() throws IOException {
        ConfigurationProperties.wasmEnabled(true);
        WasmStore.getInstance().put("orders", matchRequestModule());
        WasmBodyMatcher matcher = new WasmBodyMatcher("orders");

        HttpRequest wrongMethod = HttpRequest.request()
            .withMethod("GET")
            .withPath("/orders")
            .withHeader("X-Tenant", "acme");
        assertThat(matcher.matches(new MatchDifference(false, wrongMethod), "{}"), is(false));
    }

    @Test
    public void shouldExposeQueryParametersAndCookiesFromContextToV2Module() throws IOException {
        ConfigurationProperties.wasmEnabled(true);
        WasmStore.getInstance().put("ordersV2", matchModule("/org/mockserver/wasm/match-request-v2.wasm"));
        WasmBodyMatcher matcher = new WasmBodyMatcher("ordersV2");

        // module matches POST /orders with query tenant=acme and cookie session=abc123
        HttpRequest matching = HttpRequest.request()
            .withMethod("POST")
            .withPath("/orders")
            .withQueryStringParameter("tenant", "acme")
            .withCookie("session", "abc123");
        assertThat(matcher.matches(new MatchDifference(false, matching), "{}"), is(true));
    }

    @Test
    public void shouldNotMatchV2ModuleWhenCookieMissingInContext() throws IOException {
        ConfigurationProperties.wasmEnabled(true);
        WasmStore.getInstance().put("ordersV2", matchModule("/org/mockserver/wasm/match-request-v2.wasm"));
        WasmBodyMatcher matcher = new WasmBodyMatcher("ordersV2");

        HttpRequest noCookie = HttpRequest.request()
            .withMethod("POST")
            .withPath("/orders")
            .withQueryStringParameter("tenant", "acme");
        assertThat(matcher.matches(new MatchDifference(false, noCookie), "{}"), is(false));
    }

    @Test
    public void shouldNotMatchV2ModuleWhenQueryParameterMissingInContext() throws IOException {
        ConfigurationProperties.wasmEnabled(true);
        WasmStore.getInstance().put("ordersV2", matchModule("/org/mockserver/wasm/match-request-v2.wasm"));
        WasmBodyMatcher matcher = new WasmBodyMatcher("ordersV2");

        HttpRequest noQuery = HttpRequest.request()
            .withMethod("POST")
            .withPath("/orders")
            .withCookie("session", "abc123");
        assertThat(matcher.matches(new MatchDifference(false, noQuery), "{}"), is(false));
    }

    private static byte[] matchRequestModule() throws IOException {
        return matchModule("/org/mockserver/wasm/match-request.wasm");
    }

    private static byte[] matchModule(String resource) throws IOException {
        try (InputStream in = WasmBodyMatcherTest.class.getResourceAsStream(resource)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
