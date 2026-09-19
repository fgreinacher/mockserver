package org.mockserver.benchmark;

import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.ExpectationId;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * G1 benchmark: the cost of {@link RequestMatchers#firstMatchingExpectation} when the
 * expectation store is being MUTATED (churned), versus a STATIC store.
 *
 * <p><b>The finding under test (G1).</b> {@code CircularPriorityQueue.toSortedList()} caches
 * the sorted matcher snapshot and NULLS it on every structural mutation; in parallel the
 * {@link RequestMatchers} modification counter bumps on every mutation and
 * {@code CandidateIndex} rebuilds whenever its generation is stale. So a single mutation
 * forces the NEXT request to rebuild both the full sorted list and (when engaged) the
 * candidate index. Because the serving path itself schedules lazy removal of inactive
 * ({@code once()} / limited-{@code Times}) matchers, a workload of consumable expectations
 * mutates the store continuously — every request then pays a rebuild.
 *
 * <p><b>What this benchmark measures.</b> The per-request cost and per-request allocation of
 * {@code firstMatchingExpectation} for a HIT, in four arms crossed with size {@code n}:
 * <ul>
 *   <li>{@code mode=STATIC} — the store is built once and never mutated: every request reuses
 *       the cached sorted list (and, in INDEX mode, the built buckets). This is the baseline.</li>
 *   <li>{@code mode=CHURN} — a SINGLE background writer thread continuously removes and re-adds a
 *       dedicated churn expectation through the real {@code clear(id)} / {@code add} control-plane
 *       API. Each such mutation nulls {@code sortedCache} and bumps the modification counter
 *       exactly as the serving path's lazy removal does — so reader requests observe a stale
 *       cache and rebuild. A single writer honours the {@code CircularPriorityQueue}
 *       single-writer contract; the reader threads use only the (concurrency-safe) read path.</li>
 *   <li>{@code indexMode=SCAN} — candidate index disabled (threshold above n): the reader
 *       rebuilds only the full sorted list under churn.</li>
 *   <li>{@code indexMode=INDEX} — candidate index engaged (threshold 2): the reader rebuilds the
 *       full sorted list AND the candidate index under churn.</li>
 * </ul>
 *
 * <p><b>Threads.</b> The reader thread count is set on the command line ({@code -t 1}, {@code -t 4},
 * {@code -t 8}). The finding claims the cost WORSENS with more cores because
 * {@code toSortedList()}'s cache is a benign race: each concurrent reader that observes the
 * nulled cache rebuilds the whole list independently. Only a multi-thread run can show that.
 *
 * <p><b>What it does NOT measure.</b> It fixes {@code outcome=HIT} (the rebuild happens before
 * the scan, so a MISS tells the same rebuild story with a longer SCAN tail — omitted to bound
 * the matrix). It does not model a partially-warm cache (the writer churns full-tilt, i.e. the
 * WORST-CASE continuous churn a high-throughput {@code once()} workload produces); a
 * rate-limited writer would interpolate between STATIC and this worst case. Absolute
 * magnitudes are from a contended laptop — read the STATIC-vs-CHURN and 1-vs-N-thread RATIOS,
 * not the raw microseconds.
 *
 * <p><b>Proof the churn arm actually rebuilds</b> (not assumed) lives in
 * {@code org.mockserver.mock.CandidateIndexChurnRebuildProof} — a package-private verifier that
 * shows (a) the writer's mutation bumps the modification counter and (b) it makes
 * {@code toSortedList()} return a fresh instance, while a static store returns the same
 * instance. The {@code [churn]} line printed at trial teardown reports how many writer
 * mutations landed during the run.
 *
 * <pre>./run.sh CandidateIndexChurnBenchmark -prof gc -f 1 -wi 3 -i 5 -t 1</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CandidateIndexChurnBenchmark {

    private static final String CHURN_ID = "g1-churn-expectation";

    @Param({"100", "1000", "15000"})
    public int n;

    @Param({"STATIC", "CHURN"})
    public String mode;

    @Param({"SCAN", "INDEX"})
    public String indexMode;

    private RequestMatchers requestMatchers;
    private HttpRequest probe;

    private volatile boolean churnRunning;
    private Thread churnThread;
    private final AtomicLong writerMutations = new AtomicLong();

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
        // SCAN: threshold above the largest n so the index never engages (the reader rebuilds
        // only the sorted list under churn). INDEX: threshold 2 so the index engages for every n
        // (the reader rebuilds the sorted list AND the index). Set on the instance, not a JVM
        // property, so it is deterministic across forks (see CandidateIndexBenchmark).
        requestMatchers.withCandidateIndexThreshold("SCAN".equals(indexMode) ? Integer.MAX_VALUE : 2);

        for (int i = 0; i < n; i++) {
            requestMatchers.add(
                new Expectation(request().withMethod("GET").withPath("/exact/path-" + i))
                    .thenRespond(response().withBody("e" + i)),
                API
            );
        }
        // A HIT in the middle of the insertion/priority order — representative of a real match,
        // not the full-miss worst case. The rebuild (the subject of G1) happens BEFORE the scan,
        // so the HIT/MISS choice does not change whether a rebuild occurs.
        probe = request().withMethod("GET").withPath("/exact/path-" + (n / 2));
        if (requestMatchers.firstMatchingExpectation(probe) == null) {
            throw new IllegalStateException("probe did not match — benchmark misconfigured");
        }

        if ("CHURN".equals(mode)) {
            // The churn expectation is a bucketable literal on its OWN (method,path), so in INDEX
            // mode it sits in a bucket the probe never reads — the reader never evaluates it, and
            // remove+add (never in-place update) means a reader can never observe it mid-mutation.
            requestMatchers.add(newChurnExpectation(), API);
            churnRunning = true;
            churnThread = new Thread(() -> {
                while (churnRunning) {
                    // clear(id) removes the matcher (nulls sortedCache, bumps the modification
                    // counter); add re-inserts it (same again). One writer only, so the CPQ
                    // single-writer contract holds; readers race only on the read/rebuild path.
                    requestMatchers.clear(ExpectationId.expectationId(CHURN_ID), "g1-churn");
                    requestMatchers.add(newChurnExpectation(), API);
                    writerMutations.addAndGet(2);
                }
            }, "g1-churn-writer");
            churnThread.setDaemon(true);
            churnThread.start();
        }
    }

    private static Expectation newChurnExpectation() {
        return new Expectation(request().withMethod("GET").withPath("/churn/only"))
            .thenRespond(response().withBody("c"))
            .withId(CHURN_ID);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        churnRunning = false;
        if (churnThread != null) {
            churnThread.join(2000);
        }
        // Reported so the write-up can state how many invalidations landed during the trial —
        // a CHURN trial with zero writer mutations would be a silent false negative.
        System.out.println("[churn] mode=" + mode + " indexMode=" + indexMode + " n=" + n
            + " writerMutations=" + writerMutations.get());
    }

    @Benchmark
    public Expectation match() {
        return requestMatchers.firstMatchingExpectation(probe);
    }
}
