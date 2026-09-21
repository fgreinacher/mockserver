package org.mockserver.benchmark;

import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
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
 * Benchmark for {@link RequestMatchers#firstMatchingEarlyExpectation}, the once-per-connection
 * early (respondBeforeBody) match check that {@code EarlyMatchingHandler} runs on every HTTP/1.1
 * pipeline.
 *
 * <p>The BEFORE/AFTER of the empty-set fast-path change is NOT a JMH param — it is which
 * {@code mockserver-core} jar is installed in {@code ~/.m2} when this (benchmark-module) class is
 * run: the BEFORE build has {@code firstMatchingEarlyExpectation} walk the whole
 * {@code toSortedList()} regardless; the AFTER build returns null immediately when no expectation
 * carries respondBeforeBody. Run it once against each build and compare.
 *
 * <p>{@code early} selects the store shape that is the whole point of the change:
 * <ul>
 *   <li>{@code NONE} — no respondBeforeBody expectation (the overwhelmingly common deployment).
 *       BEFORE scans all {@code n}; AFTER skips via the empty-set gate. This is where the win is.</li>
 *   <li>{@code ONE} — exactly one respondBeforeBody expectation among {@code n}. The set is
 *       non-empty so AFTER runs the SAME scan as BEFORE — this arm proves the change adds no cost
 *       (and no benefit) once the niche feature is actually in use.</li>
 * </ul>
 *
 * <p>{@code n} sweeps 100 / 1000 / 15000 (15000 is the production {@code maxExpectations} ceiling).
 *
 * <p><b>What this does NOT capture:</b> it measures the cost of ONE call to
 * {@code firstMatchingEarlyExpectation}, not the per-connection amortisation. In production
 * {@code EarlyMatchingHandler} detaches after the first non-match, so the scan is paid once per
 * connection (amortised away under keep-alive), not once per request. This is a micro-measurement
 * of the call, not a whole-server throughput figure.
 *
 * <pre>./run.sh EarlyExpectationBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EarlyExpectationBenchmark {

    @Param({"100", "1000", "15000"})
    public int n;

    @Param({"NONE", "ONE"})
    public String early;

    private RequestMatchers requestMatchers;
    private HttpRequest probe;

    @Setup(Level.Trial)
    public void setup() {
        ConfigurationProperties.logLevel("WARN");
        ConfigurationProperties.detailedMatchFailures(false);

        Configuration configuration = Configuration.configuration();
        requestMatchers = new RequestMatchers(
            configuration,
            new MockServerLogger(),
            mock(Scheduler.class),
            mock(WebSocketClientRegistry.class)
        );
        // n-1 ordinary expectations; the nth is either ordinary (NONE) or a single
        // respondBeforeBody expectation (ONE), added LAST so the scan reaches it late.
        int ordinary = "ONE".equals(early) ? n - 1 : n;
        for (int i = 0; i < ordinary; i++) {
            requestMatchers.add(
                new Expectation(request().withMethod("GET").withPath("/data/path-" + i))
                    .thenRespond(response().withBody("e" + i)),
                API
            );
        }
        if ("ONE".equals(early)) {
            requestMatchers.add(
                new Expectation(request().withMethod("POST").withPath("/data/early-upload").withRespondBeforeBody(true))
                    .thenRespond(response().withStatusCode(403)),
                API
            );
        }
        // A data-plane request that matches NONE of the registered expectations, so the method
        // runs to completion (full scan in BEFORE / non-empty ONE) and returns null — the worst
        // case for the un-gated scan. Never starts with the control-plane PATH_PREFIX.
        probe = request().withMethod("GET").withPath("/data/no-such-path-zzzzzz");
    }

    @Benchmark
    public Expectation firstMatchingEarlyExpectation() {
        return requestMatchers.firstMatchingEarlyExpectation(probe);
    }
}
