package org.mockserver.metrics;

import com.sun.net.httpserver.HttpServer;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.remotewrite.RemoteWriteEncoder;
import org.mockserver.metrics.remotewrite.RemoteWriteV1Encoder;
import org.mockserver.metrics.remotewrite.RemoteWriteV2Encoder;
import org.xerial.snappy.Snappy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertNull;

/**
 * Verifies {@link PrometheusRemoteWriteExporter}: a real POST is captured by a
 * local {@link HttpServer} with the right headers/body, the auth/header
 * resolution helpers behave, and the exporter is fail-soft (never throws;
 * {@code startIfEnabled} returns null when disabled or mis-configured).
 * <p>
 * This class mutates global {@link ConfigurationProperties} (in the
 * startIfEnabled cases) so it runs in the sequential Surefire phase.
 */
public class PrometheusRemoteWriteExporterTest {

    private boolean originalEnabled;
    private String originalUrl;

    @Before
    public void captureConfig() {
        originalEnabled = ConfigurationProperties.prometheusRemoteWriteEnabled();
        originalUrl = ConfigurationProperties.prometheusRemoteWriteUrl();
    }

    @After
    public void restoreConfig() {
        ConfigurationProperties.prometheusRemoteWriteEnabled(originalEnabled);
        ConfigurationProperties.prometheusRemoteWriteUrl(originalUrl);
    }

    // ------------------------------------------------------------------
    // pushOnce — real POST captured by a local HTTP server
    // ------------------------------------------------------------------

    @Test
    public void pushOnceSendsCompressedRemoteWriteWithHeadersAndBody() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/write", exchange -> {
            method.set(exchange.getRequestMethod());
            Map<String, String> captured = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> captured.put(k, String.join(",", v)));
            headers.set(captured);
            body.set(readAll(exchange.getRequestBody()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        PrometheusRemoteWriteExporter exporter = null;
        try {
            int port = server.getAddress().getPort();
            URI url = URI.create("http://127.0.0.1:" + port + "/api/v1/write");

            PrometheusRegistry registry = new PrometheusRegistry();
            Counter.builder().name("test_push").help("h").register(registry).inc(3);
            Supplier<MetricSnapshots> source = registry::scrape;

            Map<String, String> customHeaders = Collections.singletonMap("X-Scope-OrgID", "tenant-a");
            exporter = new PrometheusRemoteWriteExporter(
                url, HttpClient.newHttpClient(), new RemoteWriteV1Encoder(), customHeaders, source);

            // when
            exporter.pushOnce();

            // then — a POST arrived with the version-specific + custom headers
            assertThat(method.get(), is("POST"));
            Map<String, String> h = headers.get();
            assertThat(header(h, "Content-Encoding"), is("snappy"));
            assertThat(header(h, "Content-Type"), is("application/x-protobuf"));
            assertThat(header(h, "X-Prometheus-Remote-Write-Version"), is("0.1.0"));
            assertThat(header(h, "X-Scope-OrgID"), is("tenant-a"));

            // body is snappy-compressed protobuf carrying the known series name
            byte[] decompressed = Snappy.uncompress(body.get());
            String asText = new String(decompressed, StandardCharsets.UTF_8);
            assertThat(asText, containsString("test_push_total"));
        } finally {
            if (exporter != null) {
                exporter.stop();
            }
            server.stop(0);
        }
    }

    @Test
    public void pushOnceWithV2EncoderSendsV2ContentTypeAndVersionHeaders() throws Exception {
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/write", exchange -> {
            Map<String, String> captured = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> captured.put(k, String.join(",", v)));
            headers.set(captured);
            readAll(exchange.getRequestBody());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        PrometheusRemoteWriteExporter exporter = null;
        try {
            int port = server.getAddress().getPort();
            URI url = URI.create("http://127.0.0.1:" + port + "/api/v1/write");

            PrometheusRegistry registry = new PrometheusRegistry();
            Counter.builder().name("test_push_v2").help("h").register(registry).inc(2);

            exporter = new PrometheusRemoteWriteExporter(
                url, HttpClient.newHttpClient(), new RemoteWriteV2Encoder(),
                Collections.emptyMap(), registry::scrape);

            exporter.pushOnce();

            Map<String, String> h = headers.get();
            assertThat(header(h, "Content-Encoding"), is("snappy"));
            assertThat(header(h, "Content-Type"), is("application/x-protobuf;proto=io.prometheus.write.v2.Request"));
            assertThat(header(h, "X-Prometheus-Remote-Write-Version"), is("2.0.0"));
        } finally {
            if (exporter != null) {
                exporter.stop();
            }
            server.stop(0);
        }
    }

    @Test
    public void v2EncoderReportsV2ContentTypeAndVersion() {
        RemoteWriteV2Encoder encoder = new RemoteWriteV2Encoder();
        assertThat(encoder.contentType(), is("application/x-protobuf;proto=io.prometheus.write.v2.Request"));
        assertThat(encoder.protocolVersionHeader(), is("2.0.0"));
    }

    @Test
    public void selectEncoderChoosesV2OnlyForV2AndFailsSafeToV1Otherwise() {
        assertThat(PrometheusRemoteWriteExporter.selectEncoder("v2") instanceof RemoteWriteV2Encoder, is(true));
        assertThat(PrometheusRemoteWriteExporter.selectEncoder(" V2 ") instanceof RemoteWriteV2Encoder, is(true));
        for (String version : new String[]{"v1", "V1", "", "  ", "nonsense", null}) {
            RemoteWriteEncoder encoder = PrometheusRemoteWriteExporter.selectEncoder(version);
            assertThat("version=" + version, encoder instanceof RemoteWriteV1Encoder, is(true));
        }
    }

    @Test
    public void pushOnceIsFailSoftWhenEndpointUnreachable() {
        // an unreachable port — the POST fails, but pushOnce must not throw
        URI url = URI.create("http://127.0.0.1:1/api/v1/write");
        PrometheusRegistry registry = new PrometheusRegistry();
        Counter.builder().name("test_soft").help("h").register(registry).inc(1);

        PrometheusRemoteWriteExporter exporter = new PrometheusRemoteWriteExporter(
            url, HttpClient.newHttpClient(), new RemoteWriteV1Encoder(),
            Collections.emptyMap(), registry::scrape);
        try {
            exporter.pushOnce(); // must return normally, logging a WARN internally
        } finally {
            exporter.stop();
        }
    }

    // ------------------------------------------------------------------
    // header resolution + parseHeaders (pure helpers)
    // ------------------------------------------------------------------

    @Test
    public void bearerTokenTakesPrecedenceOverBasicAuth() {
        Map<String, String> resolved = PrometheusRemoteWriteExporter.resolveHeaders(
            "my-token", "user", "pass", "");
        assertThat(resolved.get("Authorization"), is("Bearer my-token"));
    }

    @Test
    public void basicAuthUsedWhenNoBearerToken() {
        Map<String, String> resolved = PrometheusRemoteWriteExporter.resolveHeaders(
            "", "user", "pass", "");
        String expected = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
        assertThat(resolved.get("Authorization"), is(expected));
    }

    @Test
    public void customHeaderOverridesDerivedAuthHeader() {
        Map<String, String> resolved = PrometheusRemoteWriteExporter.resolveHeaders(
            "my-token", "", "", "Authorization=Custom xyz,X-Extra=1");
        // custom headers applied LAST, so the user-supplied Authorization wins
        assertThat(resolved.get("Authorization"), is("Custom xyz"));
        assertThat(resolved.get("X-Extra"), is("1"));
    }

    @Test
    public void noAuthWhenNeitherBearerNorBasicConfigured() {
        Map<String, String> resolved = PrometheusRemoteWriteExporter.resolveHeaders("", "", "", "");
        assertThat(resolved.containsKey("Authorization"), is(false));
    }

    @Test
    public void parseHeadersTrimsAndSkipsMalformedEntries() {
        Map<String, String> parsed = PrometheusRemoteWriteExporter.parseHeaders(
            " A = 1 , B=2, ,noEquals, =emptyKey, C=has=equals ");
        assertThat(parsed.get("A"), is("1"));
        assertThat(parsed.get("B"), is("2"));
        assertThat(parsed.get("C"), is("has=equals")); // only the first '=' splits
        assertThat(parsed.containsKey("noEquals"), is(false));
        assertThat(parsed.containsKey(""), is(false));
        assertThat(parsed.size(), is(3));
    }

    @Test
    public void parseHeadersOnBlankReturnsEmptyMap() {
        assertThat(PrometheusRemoteWriteExporter.parseHeaders(null).isEmpty(), is(true));
        assertThat(PrometheusRemoteWriteExporter.parseHeaders("   ").isEmpty(), is(true));
    }

    // ------------------------------------------------------------------
    // startIfEnabled fail-soft gating
    // ------------------------------------------------------------------

    @Test
    public void startIfEnabledReturnsNullWhenDisabled() {
        ConfigurationProperties.prometheusRemoteWriteEnabled(false);
        assertNull(PrometheusRemoteWriteExporter.startIfEnabled());
    }

    @Test
    public void startIfEnabledReturnsNullWhenUrlBlank() {
        ConfigurationProperties.prometheusRemoteWriteEnabled(true);
        ConfigurationProperties.prometheusRemoteWriteUrl("");
        PrometheusRemoteWriteExporter exporter = PrometheusRemoteWriteExporter.startIfEnabled();
        try {
            assertThat(exporter, is(nullValue()));
        } finally {
            if (exporter != null) {
                exporter.stop();
            }
        }
    }

    @Test
    public void startIfEnabledStartsWhenEnabledWithUrl() {
        ConfigurationProperties.prometheusRemoteWriteEnabled(true);
        // an unreachable endpoint is fine — the scheduled push fails soft and never runs here anyway
        ConfigurationProperties.prometheusRemoteWriteUrl("http://127.0.0.1:1/api/v1/write");
        PrometheusRemoteWriteExporter exporter = PrometheusRemoteWriteExporter.startIfEnabled();
        try {
            assertThat(exporter, is(notNullValue()));
        } finally {
            if (exporter != null) {
                exporter.stop();
            }
        }
    }

    // ------------------------------------------------------------------

    private static String header(Map<String, String> headers, String name) {
        // com.sun HttpExchange normalises header names to Title-Case; look up case-insensitively
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
