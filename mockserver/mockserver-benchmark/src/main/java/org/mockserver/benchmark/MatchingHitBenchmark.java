package org.mockserver.benchmark;

import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;
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
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Matched-path companion to {@link MatchingBenchmark}: {@link RequestMatchers#firstMatchingExpectation}
 * for a request that DOES match, so per-request work added to the scan (not only to the no-match
 * reconciliation) shows up in {@code gc.alloc.rate.norm}.
 *
 * <p>Every expectation shares {@code GET /headers/scan}, so they all land in one candidate-index bucket
 * (the index engages from 64 expectations by default) and differ only by an {@code X-Tenant} header.
 * {@code hitPosition=FIRST} matches the first candidate in global order (no misses before it);
 * {@code LAST} matches the last, so every other candidate is evaluated and, at INFO, logged first.
 *
 * <p>A separate class, deliberately: {@code MatchingBenchmark} is read by the daily micro-bench and
 * the per-merge allocation gate, which include every method of that class and check their row counts.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatchingHitBenchmark {

    @Param({"64", "100", "1000"})
    public int expectationCount;

    @Param({"FIRST", "LAST"})
    public String hitPosition;

    @Param({"INFO", "WARN"})
    public String logLevel;

    @Param({"false", "true"})
    public boolean detailedMatchFailures;

    private RequestMatchers requestMatchers;
    private HttpRequest matchingRequest;

    @Setup(Level.Trial)
    public void setup() {
        // Configuration.configuration() snapshots these statics, so set them first (as MatchingBenchmark does)
        ConfigurationProperties.logLevel(logLevel);
        ConfigurationProperties.detailedMatchFailures(detailedMatchFailures);
        Configuration configuration = Configuration.configuration();
        requestMatchers = new RequestMatchers(
            configuration,
            new MockServerLogger(),
            mock(Scheduler.class),
            mock(WebSocketClientRegistry.class)
        );
        for (int i = 0; i < expectationCount; i++) {
            requestMatchers.add(
                new Expectation(request().withMethod("GET").withPath("/headers/scan").withHeader(new Header("X-Tenant", "tenant-" + i)))
                    .thenRespond(response().withBody("h" + i)),
                API
            );
        }
        int hit = "FIRST".equals(hitPosition) ? 0 : expectationCount - 1;
        matchingRequest = request()
            .withMethod("GET")
            .withPath("/headers/scan")
            .withHeader(new Header("X-Tenant", "tenant-" + hit))
            .withHeader(new Header("Accept", "application/json"))
            .withHeader(new Header("User-Agent", "benchmark-client/1.0"));
        Expectation matched = requestMatchers.firstMatchingExpectation(matchingRequest);
        if (matched == null || !("h" + hit).equals(matched.getHttpResponse().getBodyAsString())) {
            throw new IllegalStateException("expected the request to match expectation " + hit + " but matched " + matched);
        }
    }

    @Benchmark
    public Expectation firstMatchingExpectation_match() {
        return requestMatchers.firstMatchingExpectation(matchingRequest);
    }
}
