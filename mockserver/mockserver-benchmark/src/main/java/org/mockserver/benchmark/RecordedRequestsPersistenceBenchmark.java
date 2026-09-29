package org.mockserver.benchmark;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.persistence.RecordedRequestsFileSystemPersistence;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

/**
 * Cost of disk capture ({@code persistRecordedRequestsToDisk}) per recorded exchange: the work
 * {@link RecordedRequestsFileSystemPersistence} does on the single event-log consumer thread for each
 * FORWARDED_REQUEST / EXPECTATION_RESPONSE entry. That thread also retains every other log entry, so
 * time spent here is time the ring is not drained — the cause of dropped log entries under proxy load.
 *
 * <p>The exchange is proxy-shaped: realistic request and response headers and a JSON body of
 * {@code bodySize} bytes on both sides (nested objects, whitespace inside strings, some non-ASCII).
 * Lines are written to {@code /dev/null}, so each op pays the write syscall but the file does not grow
 * by gigabytes during a run.
 * <ul>
 *   <li>{@code appendThenFlush} — one entry then a flush: a Disruptor batch of one, the worst case.</li>
 *   <li>{@code append} — one entry with no flush: a mid-batch entry, whose flush is amortised.</li>
 * </ul>
 * Headline columns: ops/s and {@code gc.alloc.rate.norm} (bytes/op).
 *
 * <pre>./run.sh RecordedRequestsPersistenceBenchmark -prof gc</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RecordedRequestsPersistenceBenchmark {

    @Param({"2048", "65536"})
    public int bodySize;

    private RecordedRequestsFileSystemPersistence persistence;
    private LogEntry logEntry;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        ConfigurationProperties.logLevel("WARN");
        logEntry = proxiedExchange(bodySize);
        assertOneParseableLinePerEntry();
        persistence = new RecordedRequestsFileSystemPersistence(capturingTo("/dev/null"), new MockServerLogger(RecordedRequestsPersistenceBenchmark.class));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        persistence.stop();
    }

    @Benchmark
    public void appendThenFlush() {
        persistence.append(logEntry);
        persistence.flush();
    }

    @Benchmark
    public void append() {
        persistence.append(logEntry);
    }

    /**
     * Fails the trial unless the entry really produces one line that parses back to the same exchange,
     * so a benchmark that silently wrote nothing (a swallowed serialisation error) cannot read as fast.
     */
    private void assertOneParseableLinePerEntry() throws Exception {
        File file = File.createTempFile("recorded-requests-benchmark", ".ndjson");
        try {
            RecordedRequestsFileSystemPersistence probe = new RecordedRequestsFileSystemPersistence(capturingTo(file.getAbsolutePath()), new MockServerLogger(RecordedRequestsPersistenceBenchmark.class));
            probe.append(logEntry);
            probe.append(logEntry);
            probe.stop();
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.size() != 2) {
                throw new IllegalStateException("expected 2 NDJSON lines but found " + lines.size());
            }
            HttpRequestAndHttpResponse parsed = new HttpRequestAndHttpResponseSerializer(new MockServerLogger()).deserialize(lines.get(0));
            if (parsed.getHttpResponse().getBodyAsString().length() < bodySize / 2) {
                throw new IllegalStateException("persisted response body is smaller than the " + bodySize + " byte payload");
            }
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static Configuration capturingTo(String path) {
        return configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(path);
    }

    static LogEntry proxiedExchange(int bodySize) {
        return new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/v1/orders/search")
                .withMethod("POST")
                .withQueryStringParameter("page", "3")
                .withQueryStringParameter("include", "lines", "customer")
                .withHeader("Host", "orders.internal.example.com")
                .withHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .withHeader("Accept", "application/json")
                .withHeader("Accept-Encoding", "gzip, deflate, br")
                .withHeader("Content-Type", "application/json")
                .withHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl")
                .withHeader("X-Request-Id", "5f0c2b4e-8a1d-4d6e-9b3f-2c7e1a9d4b60")
                .withHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .withBody(json(jsonPayload(bodySize, "req"), MediaType.APPLICATION_JSON)))
            .setHttpResponse(response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withHeader("Cache-Control", "no-store")
                .withHeader("Date", "Mon, 28 Sep 2026 10:15:30 GMT")
                .withHeader("X-Upstream-Latency", "42")
                .withBody(json(jsonPayload(bodySize, "resp"), MediaType.APPLICATION_JSON)));
    }

    static String jsonPayload(int targetBytes, String prefix) {
        StringBuilder json = new StringBuilder(targetBytes + 256).append("{\"items\":[");
        int i = 0;
        while (json.length() < targetBytes) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(i)
                .append(",\"sku\":\"").append(prefix).append("-SKU-").append(1000 + i).append('"')
                .append(",\"name\":\"Café crème item ").append(i).append('"')
                .append(",\"description\":\"A long free-text description with    several   spaces and a tab\\there\"")
                .append(",\"price\":").append(i * 3).append(".99")
                .append(",\"inStock\":").append(i % 3 != 0)
                .append(",\"tags\":[\"alpha\",\"beta\",\"gamma\"]")
                .append(",\"dimensions\":{\"w\":10,\"h\":20,\"d\":").append(i % 7).append("}}");
            i++;
        }
        return json.append("],\"total\":").append(i).append('}').toString();
    }
}
