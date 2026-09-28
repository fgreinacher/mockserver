package org.mockserver.benchmark;

import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.ResponseMode;
import org.mockserver.model.Action;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Six threads sharing one {@link Expectation}: {@link #resolveSingleAction()} measures {@code getAction()}
 * on a single-action expectation, and the two {@code recordMatch*} arms measure {@code consumeMatch()} for
 * single- versus multi-response (the control) expectations.
 *
 * <pre>./run.sh -prof gc ExpectationActionResolutionBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(6)
public class ExpectationActionResolutionBenchmark {

    private Expectation singleResponseExpectation;
    private Expectation multiResponseExpectation;

    @Setup(Level.Trial)
    public void setup() {
        singleResponseExpectation = new Expectation(request().withPath("/single"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(response().withStatusCode(200))
            .withId("single-id");
        multiResponseExpectation = new Expectation(request().withPath("/multi"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(500),
                response().withStatusCode(418)))
            .withResponseMode(ResponseMode.SEQUENTIAL)
            .withId("multi-id");
        // pre-stamp so the id-write is already settled (steady state: the first request stamped it)
        singleResponseExpectation.getAction();
        multiResponseExpectation.consumeMatch();
        multiResponseExpectation.getAction();
    }

    @Benchmark
    public Action resolveSingleAction() {
        return singleResponseExpectation.getAction();
    }

    @Benchmark
    public boolean recordMatchSingleResponse() {
        return singleResponseExpectation.consumeMatch();
    }

    @Benchmark
    public boolean recordMatchMultiResponse() {
        return multiResponseExpectation.consumeMatch();
    }
}
