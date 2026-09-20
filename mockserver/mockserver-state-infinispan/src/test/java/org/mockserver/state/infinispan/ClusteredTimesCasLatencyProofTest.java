package org.mockserver.state.infinispan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.Times;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.BlobStore;
import org.mockserver.state.ClusterInfo;
import org.mockserver.state.ExpectationEntry;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.mockserver.state.Versioned;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * G3 measurement — <b>research proof, not a gate</b>. Measures the per-match
 * cost of the synchronous distributed compare-and-set (CAS) that a clustered
 * limited-{@code Times} match performs <b>on the request thread</b> inside
 * {@link RequestMatchers#firstMatchingExpectation}, against the node-local fast
 * path (unlimited {@code Times}) as the control.
 * <p>
 * <b>The finding under test.</b> When a clustered {@link StateBackend} is active
 * and an expectation has a bounded {@code Times}, each match calls
 * {@code consumeTimesViaBackendCas} synchronously — a REPL_SYNC
 * {@code compareAndSet} that awaits replication acks from every cluster member.
 * Unlimited {@code Times} takes a node-local path with no backend round-trip.
 * So under clustering, serving latency for a bounded-{@code Times} expectation
 * becomes a function of grid RTT rather than local matching.
 * <p>
 * <b>Mechanism proof (the assertions).</b> Before trusting any latency number
 * this test proves the two arms take DIFFERENT routes, by wrapping the backend
 * in a {@link CountingStateBackend} that counts every {@code get}/{@code
 * compareAndSet} on both the expectation store and the shared-times counter
 * store:
 * <ul>
 *   <li><b>control</b> (unlimited {@code Times}): after N measured matches the
 *       shared-times CAS count is <b>0</b> — the CAS is genuinely OFF the path;</li>
 *   <li><b>treatment</b> (bounded {@code Times}): after N single-threaded
 *       uncontended matches the shared-times CAS count is exactly <b>N</b> — one
 *       replicated write per match, genuinely ON the path;</li>
 *   <li>on BOTH arms the expectation store sees <b>0</b> reads/CASes during
 *       matching — the only backend I/O on the serving path is the shared-times
 *       CAS.</li>
 * </ul>
 * Latency magnitudes are reported to stdout (they are load-dependent on a shared
 * machine); the assertions gate the mechanism, not the timing.
 * <p>
 * <b>Topology.</b> Uses the same in-JVM JGroups {@code SHARED_LOOPBACK}
 * REPL_SYNC fixture as {@link ClusteredTwoNodeTest} — N real
 * {@link InfinispanStateBackend} members in one JVM, no Docker. (Docker is
 * available in this environment but a multi-container cluster adds no fidelity
 * over loopback REPL_SYNC for measuring the request-thread CAS, and is far more
 * flaky.) The serving node is always member 0; peers exist only to make each
 * CAS await real cross-member replication acks.
 */
class ClusteredTimesCasLatencyProofTest {

    private static final int MAX_EXPECTATIONS = 1000;
    private static final Duration CLUSTER_FORMATION_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpRequest CONTROL_REQUEST = HttpRequest.request("/g3/control-unlimited");
    private static final HttpRequest LIMITED_REQUEST = HttpRequest.request("/g3/limited-times");

    // ---------------------------------------------------------------------
    // (1) Per-match CAS cost vs. the node-local fast path, WITH the mechanism proof.
    // ---------------------------------------------------------------------

    @Test
    void casIsOnTheLimitedTimesPathAndOffTheUnlimitedPath() {
        int warmup = 200;
        int measured = 1000;

        Cluster cluster = Cluster.form(2);
        try {
            CountingStateBackend counting = new CountingStateBackend(cluster.serving());
            RequestMatchers matchers = matchersFor(counting, true);

            addUnlimited(matchers, CONTROL_REQUEST);
            addLimited(matchers, LIMITED_REQUEST, 5_000_000);

            // Warm up both arms (JIT, cache priming, JGroups steady state).
            drive(matchers, CONTROL_REQUEST, warmup);
            drive(matchers, LIMITED_REQUEST, warmup);

            // ---- CONTROL: unlimited Times -> node-local fast path ----
            counting.reset();
            long[] controlNs = timeEach(matchers, CONTROL_REQUEST, measured);
            long controlSharedCas = counting.sharedTimesCas();
            long controlSharedGet = counting.sharedTimesGet();
            long controlExpStoreOps = counting.expectationStoreOps();

            // ---- TREATMENT: bounded Times -> synchronous distributed CAS ----
            counting.reset();
            long[] treatmentNs = timeEach(matchers, LIMITED_REQUEST, measured);
            long treatmentSharedCas = counting.sharedTimesCas();
            long treatmentSharedGet = counting.sharedTimesGet();
            long treatmentExpStoreOps = counting.expectationStoreOps();

            Stats control = Stats.of("control  (unlimited Times, node-local)", controlNs);
            Stats treatment = Stats.of("treatment(limited Times, clustered CAS)", treatmentNs);

            System.out.println();
            System.out.println("=== G3 (1) per-match CAS cost vs node-local fast path  [2-node loopback REPL_SYNC] ===");
            System.out.println("measured matches per arm: " + measured);
            System.out.println(control);
            System.out.println(treatment);
            System.out.printf("ratio treatment/control : p50 x%.1f   mean x%.1f%n",
                treatment.p50 / (double) control.p50, treatment.mean / (double) control.mean);
            System.out.println("backend I/O during CONTROL matching  : sharedTimesCAS=" + controlSharedCas
                + " sharedTimesGet=" + controlSharedGet + " expectationStoreOps=" + controlExpStoreOps);
            System.out.println("backend I/O during TREATMENT matching: sharedTimesCAS=" + treatmentSharedCas
                + " sharedTimesGet=" + treatmentSharedGet + " expectationStoreOps=" + treatmentExpStoreOps);

            // ---- Mechanism assertions: prove the two arms took different routes ----
            assertThat("control (unlimited Times) must NOT perform a shared-times CAS — "
                    + "it must take the node-local fast path",
                controlSharedCas, is(0L));
            assertThat("control (unlimited Times) must NOT even read the shared-times counter",
                controlSharedGet, is(0L));
            assertThat("treatment (limited Times) must perform exactly one shared-times CAS per match "
                    + "(single-threaded, uncontended)",
                treatmentSharedCas, is((long) measured));
            assertThat("treatment (limited Times) reads the shared-times counter once per match",
                treatmentSharedGet, is((long) measured));
            assertThat("neither arm touches the expectation store on the match path",
                controlExpStoreOps + treatmentExpStoreOps, is(0L));
        } finally {
            cluster.close();
        }
    }

    // ---------------------------------------------------------------------
    // (2) How the per-match CAS cost scales with cluster size (1 vs 2 vs 3 nodes).
    // ---------------------------------------------------------------------

    @Test
    void casCostScalesWithClusterSize() {
        int warmup = 200;
        int measured = 800;
        int[] sizes = {1, 2, 3};

        System.out.println();
        System.out.println("=== G3 (2) per-match CAS latency vs cluster size  [loopback REPL_SYNC, serving node = member 0] ===");
        System.out.println("measured matches per topology: " + measured);

        List<Stats> perSize = new ArrayList<>();
        Stats baseline = null;
        for (int size : sizes) {
            Cluster cluster = Cluster.form(size);
            try {
                CountingStateBackend counting = new CountingStateBackend(cluster.serving());
                RequestMatchers matchers = matchersFor(counting, true);
                addLimited(matchers, LIMITED_REQUEST, 5_000_000);

                drive(matchers, LIMITED_REQUEST, warmup);
                counting.reset();
                long[] ns = timeEach(matchers, LIMITED_REQUEST, measured);
                long cas = counting.sharedTimesCas();

                Stats s = Stats.of(size + "-node", ns);
                perSize.add(s);
                if (size == 1) {
                    baseline = s;
                }
                String rel = baseline != null
                    ? String.format("   (p50 x%.2f vs 1-node)", s.p50 / (double) baseline.p50)
                    : "";
                System.out.println(s + "   sharedTimesCAS=" + cas + rel);

                // Mechanism: exactly one CAS per match at every topology.
                assertThat(size + "-node: one shared-times CAS per match",
                    cas, is((long) measured));
            } finally {
                cluster.close();
            }
        }
        System.out.println("NOTE: absolute magnitudes are load-dependent on a shared machine; "
            + "read the cross-size RATIO, and expect wide error bars.");
        // No latency-magnitude assertion: a 1-node REPL_SYNC write need not touch
        // the network, and a contended laptop makes ordering unreliable. The
        // scaling claim is reported, not gated.
        assertThat(perSize, hasSize(sizes.length));
    }

    // ---------------------------------------------------------------------
    // (3) CAS retry rate when concurrent requests contend on the SAME key.
    // ---------------------------------------------------------------------

    @Test
    void casRetryRateUnderContentionOnOneKey() throws Exception {
        int threads = 8;
        int matchesPerThread = 400;
        int total = threads * matchesPerThread;

        Cluster cluster = Cluster.form(2);
        try {
            CountingStateBackend counting = new CountingStateBackend(cluster.serving());
            RequestMatchers matchers = matchersFor(counting, true);
            // Bounded Times high enough that no thread ever exhausts it — we are
            // measuring CAS *contention* on one hot key, not exhaustion.
            addLimited(matchers, LIMITED_REQUEST, 50_000_000);

            drive(matchers, LIMITED_REQUEST, 500); // warm up + reach steady state
            counting.reset();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CyclicBarrier start = new CyclicBarrier(threads);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger served = new AtomicInteger();
            try {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < matchesPerThread; i++) {
                                Expectation matched = matchers.firstMatchingExpectation(LIMITED_REQUEST);
                                if (matched != null) {
                                    served.incrementAndGet();
                                }
                                matchers.postProcess(matched);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                if (!done.await(60, TimeUnit.SECONDS)) {
                    fail("contention run did not finish within 60s");
                }
            } finally {
                pool.shutdownNow();
            }

            long totalCas = counting.sharedTimesCas();
            long totalGet = counting.sharedTimesGet();
            int servedCount = served.get();
            int drops = total - servedCount;
            // Total CAS attempts beyond one winning CAS per served match: this
            // conflates retries on served matches with the (up to MAX_CAS_RETRIES)
            // wasted attempts on each dropped request.
            long extraCasAttempts = totalCas - servedCount;
            double casPerServed = servedCount == 0 ? 0 : (totalCas / (double) servedCount);
            double dropRate = total == 0 ? 0 : (drops / (double) total);

            System.out.println();
            System.out.println("=== G3 (3) CAS contention on ONE limited-Times key  [2-node, " + threads + " threads, budget not exhausted] ===");
            System.out.println("attempts            : " + total);
            System.out.println("served matches      : " + servedCount);
            System.out.println("dropped (lost CAS >" + " MAX_CAS_RETRIES): " + drops);
            System.out.printf("drop rate           : %.1f%% of matches refused DESPITE a non-exhausted Times budget%n",
                100.0 * dropRate);
            System.out.println("total CAS attempts  : " + totalCas + "   total counter reads: " + totalGet);
            System.out.printf("amortised CAS/served: %.2f CAS attempts per served match (incl. wasted attempts on drops)%n",
                casPerServed);

            // Mechanism assertions (not magnitude — those vary with machine load):
            //  - no match is served without a backend CAS on the request thread
            //    (the whole point of the finding): total CAS >= served;
            //  - the counter is read at least once per CAS attempt;
            //  - contention is genuinely exercised: with 8 threads racing one key
            //    the request thread performs strictly MORE CAS attempts than the
            //    served count (retries and/or wasted drop attempts), proving the
            //    optimistic loop actually contends rather than sailing through.
            assertThat("every served match performed at least one CAS on the request thread",
                totalCas, greaterThanOrEqualTo((long) servedCount));
            assertThat("counter is read at least once per CAS attempt",
                totalGet, greaterThanOrEqualTo(totalCas));
            assertThat("same-key contention must produce extra CAS attempts beyond one-per-served",
                extraCasAttempts, greaterThan(0L));
            assertThat("some matches are served", servedCount, greaterThan(0));
            assertThat("served count can never exceed attempts", servedCount, lessThanOrEqualTo(total));

            // Regression guard for the randomised CAS backoff (see
            // RequestMatchers.backoffBeforeCasRetry). This is the ONE magnitude
            // assertion here, and it is deliberately loose: the retry loop used to
            // spin with no backoff, so same-key losers re-collided in lock-step and
            // this scenario refused 32.1% of matches despite a Times budget nowhere
            // near exhausted. With backoff it measures ~0.1-2.5% on this harness
            // (five runs across two machines' load states). A 10% ceiling therefore
            // sits ~4x above the observed post-fix noise and ~3x
            // below the defect, so it cannot flake on a loaded machine yet cannot
            // miss a removal of the backoff either. It is a floor on correctness,
            // not a performance threshold: every drop here is a match the budget
            // allowed but contention refused.
            assertThat("contention alone must not refuse matches a non-exhausted Times budget allows"
                    + " (served=" + servedCount + " dropped=" + drops + " of " + total + ")",
                dropRate, lessThan(0.10));
        } finally {
            cluster.close();
        }
    }

    // =====================================================================
    // Fixtures & helpers
    // =====================================================================

    /** An in-JVM loopback REPL_SYNC cluster of {@code size} Infinispan members. */
    private static final class Cluster implements AutoCloseable {
        private final List<InfinispanStateBackend> members;

        private Cluster(List<InfinispanStateBackend> members) {
            this.members = members;
        }

        static Cluster form(int size) {
            String clusterName = "g3-cas-latency-" + System.nanoTime();
            List<InfinispanStateBackend> members = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Configuration cfg = Configuration.configuration()
                    .maxExpectations(MAX_EXPECTATIONS)
                    .stateBackend("infinispan")
                    .clusterEnabled(true)
                    .clusterName(clusterName);
                members.add(new InfinispanStateBackend(cfg));
            }
            for (InfinispanStateBackend m : members) {
                awaitClusterSize(m, size, CLUSTER_FORMATION_TIMEOUT);
            }
            return new Cluster(members);
        }

        /** The node that serves requests (and performs the request-thread CAS). */
        InfinispanStateBackend serving() {
            return members.get(0);
        }

        @Override
        public void close() {
            // Close peers first, serving node last (mirrors ClusteredTwoNodeTest teardown order).
            for (int i = members.size() - 1; i >= 0; i--) {
                try {
                    members.get(i).close();
                } catch (RuntimeException ignored) {
                    // best-effort teardown
                }
            }
        }
    }

    private RequestMatchers matchersFor(StateBackend backend, boolean sharedTimesEnabled) {
        Configuration config = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS)
            .clusterSharedTimesEnabled(sharedTimesEnabled);
        RequestMatchers matchers = new RequestMatchers(
            config, new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        matchers.setStateBackend(backend);
        return matchers;
    }

    private void addUnlimited(RequestMatchers matchers, HttpRequest request) {
        Expectation expectation = Expectation.when(request, Times.unlimited(), TimeToLive.unlimited())
            .thenRespond(HttpResponse.response("control"));
        matchers.add(expectation, RequestMatchers.Cause.API);
    }

    private void addLimited(RequestMatchers matchers, HttpRequest request, int times) {
        Expectation expectation = Expectation.when(request, Times.exactly(times), TimeToLive.unlimited())
            .thenRespond(HttpResponse.response("limited"));
        matchers.add(expectation, RequestMatchers.Cause.API);
    }

    /** Drives {@code count} matches, discarding timing (warmup / priming). */
    private void drive(RequestMatchers matchers, HttpRequest request, int count) {
        for (int i = 0; i < count; i++) {
            Expectation matched = matchers.firstMatchingExpectation(request);
            if (matched == null) {
                fail("expected a match on " + request.getPath() + " during warmup at iteration " + i);
            }
            matchers.postProcess(matched);
        }
    }

    /** Times each of {@code count} matches individually, returning per-match nanos. */
    private long[] timeEach(RequestMatchers matchers, HttpRequest request, int count) {
        long[] ns = new long[count];
        for (int i = 0; i < count; i++) {
            long t0 = System.nanoTime();
            Expectation matched = matchers.firstMatchingExpectation(request);
            long t1 = System.nanoTime();
            if (matched == null) {
                fail("expected a match on " + request.getPath() + " at iteration " + i);
            }
            matchers.postProcess(matched);
            ns[i] = t1 - t0;
        }
        return ns;
    }

    private static final class Stats {
        final String label;
        final long p50;
        final long p90;
        final long p99;
        final long mean;
        final long min;

        private Stats(String label, long p50, long p90, long p99, long mean, long min) {
            this.label = label;
            this.p50 = p50;
            this.p90 = p90;
            this.p99 = p99;
            this.mean = mean;
            this.min = min;
        }

        static Stats of(String label, long[] ns) {
            long[] sorted = ns.clone();
            Arrays.sort(sorted);
            long sum = 0;
            for (long v : ns) {
                sum += v;
            }
            return new Stats(label,
                sorted[(int) (sorted.length * 0.50)],
                sorted[(int) (sorted.length * 0.90)],
                sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))],
                sum / ns.length,
                sorted[0]);
        }

        @Override
        public String toString() {
            return String.format("%-42s min=%6.1fus  p50=%6.1fus  p90=%6.1fus  p99=%7.1fus  mean=%6.1fus",
                label, min / 1000.0, p50 / 1000.0, p90 / 1000.0, p99 / 1000.0, mean / 1000.0);
        }
    }

    // ---------------------------------------------------------------------
    // Instrumentation: a StateBackend that counts backend I/O on the two stores
    // the match path could touch, so we can PROVE where the CAS is (and is not).
    // ---------------------------------------------------------------------

    private static final class CountingStateBackend implements StateBackend {
        private final StateBackend delegate;
        private final CountingKeyValueStore<ExpectationEntry> expectations;
        private final CountingKeyValueStore<Integer> sharedTimes;

        CountingStateBackend(StateBackend delegate) {
            this.delegate = delegate;
            this.expectations = new CountingKeyValueStore<>(delegate.expectations());
            KeyValueStore<Integer> realSharedTimes = delegate.sharedTimesCounters();
            this.sharedTimes = realSharedTimes == null ? null : new CountingKeyValueStore<>(realSharedTimes);
        }

        void reset() {
            expectations.reset();
            if (sharedTimes != null) {
                sharedTimes.reset();
            }
        }

        long sharedTimesCas() {
            return sharedTimes == null ? 0 : sharedTimes.casCount.get();
        }

        long sharedTimesGet() {
            return sharedTimes == null ? 0 : sharedTimes.getCount.get();
        }

        long expectationStoreOps() {
            return expectations.getCount.get() + expectations.casCount.get();
        }

        @Override
        public KeyValueStore<ExpectationEntry> expectations() {
            return expectations;
        }

        @Override
        public KeyValueStore<Integer> sharedTimesCounters() {
            return sharedTimes;
        }

        @Override
        public KeyValueStore<String> scenarioStates() {
            return delegate.scenarioStates();
        }

        @Override
        public KeyValueStore<ObjectNode> crudEntities(String namespace) {
            return delegate.crudEntities(namespace);
        }

        @Override
        public BlobStore blobs() {
            return delegate.blobs();
        }

        @Override
        public void addInvalidationListener(InvalidationListener listener) {
            delegate.addInvalidationListener(listener);
        }

        @Override
        public String nodeId() {
            return delegate.nodeId();
        }

        @Override
        public boolean isClustered() {
            return delegate.isClustered();
        }

        @Override
        public ClusterInfo clusterInfo() {
            return delegate.clusterInfo();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Delegating {@link KeyValueStore} that counts {@code get} and
     * {@code compareAndSet} calls — the two operations on the shared-times
     * consume path ({@code get} = node-local replica read, {@code compareAndSet}
     * = replicated write / round-trip).
     */
    private static final class CountingKeyValueStore<V> implements KeyValueStore<V> {
        private final KeyValueStore<V> delegate;
        final AtomicLong getCount = new AtomicLong();
        final AtomicLong casCount = new AtomicLong();

        CountingKeyValueStore(KeyValueStore<V> delegate) {
            this.delegate = delegate;
        }

        void reset() {
            getCount.set(0);
            casCount.set(0);
        }

        @Override
        public Optional<Versioned<V>> get(String key) {
            getCount.incrementAndGet();
            return delegate.get(key);
        }

        @Override
        public boolean compareAndSet(String key, long expectedVersion, V value) {
            casCount.incrementAndGet();
            return delegate.compareAndSet(key, expectedVersion, value);
        }

        @Override
        public long put(String key, V value) {
            return delegate.put(key, value);
        }

        @Override
        public Optional<Versioned<V>> putIfAbsent(String key, V value) {
            return delegate.putIfAbsent(key, value);
        }

        @Override
        public boolean compareAndRemove(String key, long expectedVersion) {
            return delegate.compareAndRemove(key, expectedVersion);
        }

        @Override
        public boolean remove(String key) {
            return delegate.remove(key);
        }

        @Override
        public Stream<Entry<V>> entries() {
            return delegate.entries();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public void setMaxSize(int maxSize) {
            delegate.setMaxSize(maxSize);
        }

        @Override
        public void addInvalidationListener(InvalidationListener listener) {
            delegate.addInvalidationListener(listener);
        }
    }

    private static void awaitClusterSize(InfinispanStateBackend backend, int expectedSize, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            int currentSize = backend.getCacheManager().getTransport() == null
                ? 1
                : backend.getCacheManager().getTransport().getMembers().size();
            if (currentSize >= expectedSize) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for cluster formation", e);
            }
        }
        fail("cluster did not reach size " + expectedSize + " within " + timeout);
    }
}
