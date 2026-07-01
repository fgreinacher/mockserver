package org.mockserver.metrics;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.remotewrite.RemoteWriteEncoder;
import org.mockserver.metrics.remotewrite.RemoteWriteV1Encoder;
import org.mockserver.metrics.remotewrite.SnappyBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Optional push exporter that, on a fixed interval, snapshots the same
 * Prometheus metrics served at {@code /mockserver/metrics}, encodes them as a
 * Prometheus Remote-Write v1 {@code WriteRequest} protobuf, snappy-compresses
 * it (raw block format), and HTTP POSTs it to a configured remote-write
 * endpoint (Prometheus {@code --web.enable-remote-write-receiver}, Grafana
 * Mimir, New Relic, VictoriaMetrics, Thanos Receive).
 * <p>
 * Off unless {@code mockserver.prometheusRemoteWriteEnabled} is set. Mirrors the
 * {@link OtelMetricsExporter} lifecycle and is fail-soft throughout: telemetry
 * must never throw into the server or prevent it from running. Remote write is
 * inherently cumulative (the Prometheus model) — there is no delta here.
 */
public class PrometheusRemoteWriteExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrometheusRemoteWriteExporter.class);

    private final URI url;
    private final HttpClient client;
    private final RemoteWriteEncoder encoder;
    private final Map<String, String> headers;
    private final Supplier<MetricSnapshots> snapshotSource;
    private final ScheduledExecutorService scheduler;

    /**
     * Construct with explicit collaborators. Package-private so tests can inject
     * a stub HTTP client / encoder / snapshot supplier with zero global state.
     */
    PrometheusRemoteWriteExporter(URI url, HttpClient client, RemoteWriteEncoder encoder,
                                  Map<String, String> headers, Supplier<MetricSnapshots> snapshotSource) {
        this.url = url;
        this.client = client;
        this.encoder = encoder;
        this.headers = headers;
        this.snapshotSource = snapshotSource;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "mockserver-prometheus-remote-write");
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Start the exporter if enabled in configuration, returning the running
     * instance, or {@code null} if disabled, mis-configured, or startup failed
     * (fail-soft — telemetry must never prevent the server from running).
     */
    public static PrometheusRemoteWriteExporter startIfEnabled() {
        if (!ConfigurationProperties.prometheusRemoteWriteEnabled()) {
            return null;
        }
        try {
            String rawUrl = ConfigurationProperties.prometheusRemoteWriteUrl();
            if (rawUrl == null || rawUrl.trim().isEmpty()) {
                LOGGER.warn("Prometheus remote-write is enabled but no URL is configured (mockserver.prometheusRemoteWriteUrl); skipping");
                return null;
            }
            long intervalSeconds = ConfigurationProperties.prometheusRemoteWriteIntervalSeconds();
            Map<String, String> resolvedHeaders = resolveHeaders(
                ConfigurationProperties.prometheusRemoteWriteBearerToken(),
                ConfigurationProperties.prometheusRemoteWriteBasicAuthUsername(),
                ConfigurationProperties.prometheusRemoteWriteBasicAuthPassword(),
                ConfigurationProperties.prometheusRemoteWriteHeaders());
            boolean authConfigured = resolvedHeaders.containsKey("Authorization");
            HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
            PrometheusRemoteWriteExporter exporter = new PrometheusRemoteWriteExporter(
                URI.create(rawUrl.trim()), httpClient, new RemoteWriteV1Encoder(), resolvedHeaders,
                () -> PrometheusRegistry.defaultRegistry.scrape());
            exporter.scheduler.scheduleWithFixedDelay(exporter::pushOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            LOGGER.info("Prometheus remote-write export enabled (url {}, interval {}s, version {}, auth {})",
                rawUrl.trim(), intervalSeconds, exporter.encoder.protocolVersionHeader(),
                authConfigured ? "configured" : "none");
            return exporter;
        } catch (Exception e) {
            LOGGER.warn("failed to start Prometheus remote-write export ({}); continuing without it", e.getMessage());
            return null;
        }
    }

    /**
     * Snapshot, encode, compress, and POST once. Never throws — any failure is
     * logged at WARN and swallowed so the scheduler keeps running and the
     * server is never affected. Visible for testing.
     */
    void pushOnce() {
        try {
            MetricSnapshots snapshots = snapshotSource.get();
            byte[] encoded = encoder.encode(snapshots, System.currentTimeMillis());
            byte[] compressed = SnappyBlock.compress(encoded);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(url)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", encoder.contentType())
                .header("Content-Encoding", "snappy")
                .header("X-Prometheus-Remote-Write-Version", encoder.protocolVersionHeader())
                .POST(HttpRequest.BodyPublishers.ofByteArray(compressed));
            headers.forEach(builder::header);
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                LOGGER.warn("Prometheus remote-write POST to {} returned HTTP {}{}", url, status, bodyPrefix(response.body()));
            }
        } catch (Exception e) {
            LOGGER.warn("Prometheus remote-write POST to {} failed: {}", url, e.getMessage());
        }
    }

    private static String bodyPrefix(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, 0, Math.min(body.length, 256), StandardCharsets.UTF_8);
        return " - " + text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Resolve the auth + custom headers for each POST. Bearer token wins over
     * basic auth; the parsed custom headers are applied LAST so a user-supplied
     * header (including {@code Authorization}) may override the derived one.
     * Never logs or returns secret values in the log. Package-private for tests.
     */
    static Map<String, String> resolveHeaders(String bearerToken, String basicUsername, String basicPassword, String rawCustomHeaders) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (isNotBlank(bearerToken)) {
            resolved.put("Authorization", "Bearer " + bearerToken.trim());
        } else if (isNotBlank(basicUsername)) {
            String credentials = basicUsername.trim() + ":" + (basicPassword == null ? "" : basicPassword);
            resolved.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        resolved.putAll(parseHeaders(rawCustomHeaders));
        return resolved;
    }

    /**
     * Parse a {@code k=v,k2=v2} header string into a map, trimming keys/values
     * and skipping blank or malformed (no {@code =}, or empty key) entries.
     * Package-private for tests.
     */
    static Map<String, String> parseHeaders(String raw) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return parsed;
        }
        for (String entry : raw.split(",")) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                // no '=' or empty key — skip malformed entry
                continue;
            }
            String key = entry.substring(0, equals).trim();
            String value = entry.substring(equals + 1).trim();
            if (!key.isEmpty()) {
                parsed.put(key, value);
            }
        }
        return parsed;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Stop pushing and release the scheduler. Safe to call once.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
