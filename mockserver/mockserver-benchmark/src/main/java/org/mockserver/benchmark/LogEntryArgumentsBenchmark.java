package org.mockserver.benchmark;

import org.mockserver.log.model.LogEntry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Allocation per call of {@link LogEntry#setArguments} and {@link LogEntry#clone()}, each passed an
 * already-materialised {@code Object[]} as clone/translateTo do, so no varargs wrapper is counted.
 *
 * <pre>./run.sh -prof gc LogEntryArgumentsBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LogEntryArgumentsBenchmark {

    @Param({"1", "2"})
    private int argumentCount;

    private Object[] rawArguments;
    private LogEntry source;

    @Setup
    public void setup() {
        HttpRequest httpRequest = request().withMethod("POST").withPath("/api/orders");
        HttpResponse httpResponse = response().withStatusCode(200).withBody("{\"id\":42}");
        rawArguments = argumentCount == 1
            ? new Object[]{httpRequest}
            : new Object[]{httpRequest, httpResponse};
        source = new LogEntry()
            .setHttpRequest(httpRequest)
            .setMessageFormat("received request:{}and responded:{}")
            .setArguments(rawArguments);
    }

    /**
     * The call-site pattern: a fresh entry whose arguments are set once. The entry is returned so the
     * normalised array escapes and cannot be eliminated by escape analysis (a target reused across ops
     * lets the JIT scalar-replace the store and hides the allocation this measures).
     */
    @Benchmark
    public LogEntry setArguments() {
        return new LogEntry().setMessageFormat("received request:{}").setArguments(rawArguments);
    }

    @Benchmark
    public LogEntry clone(Blackhole bh) {
        LogEntry clone = source.clone();
        bh.consume(clone);
        return clone;
    }
}
