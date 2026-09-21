package org.mockserver.benchmark;

import org.mockserver.model.MediaType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Hot-path micro-benchmark for {@link MediaType#parse(String)} — the per-request Content-Type parse
 * that runs on every request-with-body through {@code BodyDecoderEncoder}. The {@code -prof gc}
 * {@code gc.alloc.rate.norm} (bytes/op) column is the number that matters: each parse of an uncached
 * header allocates several substrings, a {@code ConcurrentHashMap} parameter map, a backing
 * {@code TreeMap}, and a {@code toString} {@code StringBuilder}. The realistic workload parses a
 * header drawn from a tiny fixed set ({@code application/json}, {@code text/plain}, ...) over and
 * over, so a bounded cache keyed on the verbatim header string should drive alloc/op to ~0 on the hit
 * path.
 *
 * <pre>./run.sh -prof gc MediaTypeParseBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MediaTypeParseBenchmark {

    /** Representative Content-Type headers — the common no-parameter case and a charset-parameter case. */
    @Param({"application/json", "application/json; charset=utf-8", "text/plain"})
    public String header;

    @Benchmark
    public MediaType parse() {
        return MediaType.parse(header);
    }
}
