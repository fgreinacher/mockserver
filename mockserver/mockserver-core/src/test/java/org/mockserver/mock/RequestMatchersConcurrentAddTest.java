package org.mockserver.mock;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.BlobStore;
import org.mockserver.state.ClusterInfo;
import org.mockserver.state.ExpectationEntry;
import org.mockserver.state.InMemoryStateBackend;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.mockserver.state.Versioned;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Regression tests for issue #2579 — concurrent {@code add(...)} silently
 * dropping expectations.
 * <p>
 * Root cause: {@link RequestMatchers#add} was a non-atomic
 * local-put → backend-put → eviction-trim sequence. Under concurrent adds on
 * the {@code nioEventLoopThreadCount} Netty worker threads, one thread's
 * eviction trim ({@code reconcileFromBackend → trimEvictedFromBackend}) could
 * observe another thread's just-inserted node-local matcher BEFORE that
 * thread's backend put landed, mis-classify it as backend-evicted, and delete
 * it from the node-local cache. The victim's own later trim then saw
 * {@code local <= backend} and never rebuilt it, so the expectation returned
 * 201 but was permanently absent from {@link RequestMatchers#retrieveActiveExpectations}.
 * <p>
 * The fix serialises every node-local cache mutator on the same monitor
 * {@code reconcileFromBackend()} uses, restoring a genuine single-writer
 * contract. These tests fail (a dropped expectation) against the pre-fix code
 * and pass against the fixed code.
 * <ul>
 *   <li>{@link #deterministicInterleavingNeverDropsExpectation()} forces the
 *       exact interleaving via a test-only backend that parks a "victim" add
 *       between its local put and its backend put while another add runs its
 *       trim — deterministically red before the fix, and (crucially) does NOT
 *       hang under the fix (the parked add holds the monitor, the other add
 *       blocks on it, and the orchestrator releases the victim after a bounded
 *       wait).</li>
 *   <li>{@link #concurrentAddsStoreEveryExpectation()} is a hammer test: 8
 *       threads × 64 distinct expectations, released from a common barrier,
 *       repeated over several rounds.</li>
 * </ul>
 */
public class RequestMatchersConcurrentAddTest {

    private static final int LARGE_MAX = 1_000_000;

    private RequestMatchers newMatchers(StateBackend backend) {
        Configuration configuration = configuration().maxExpectations(LARGE_MAX);
        RequestMatchers matchers = new RequestMatchers(
            configuration, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        matchers.setStateBackend(backend);
        return matchers;
    }

    private static Expectation expectation(String id) {
        return new Expectation(request().withPath("/" + id)).withId(id)
            .thenRespond(response().withBody(id));
    }

    // ---------------------------------------------------------------------
    // Deterministic interleaving reproduction
    // ---------------------------------------------------------------------

    @Test(timeout = 30_000)
    public void deterministicInterleavingNeverDropsExpectation() throws Exception {
        // The victim add ("v") parks inside its backend put — i.e. after its
        // node-local matcher has been inserted but before the backend knows about
        // it — until released. The aggressor add ("a") runs concurrently and, in
        // the pre-fix code, would run its eviction trim in that window and delete
        // the victim's node-local matcher.
        ParkingStateBackend backend = new ParkingStateBackend(new InMemoryStateBackend(LARGE_MAX), "v");
        RequestMatchers matchers = newMatchers(backend);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Victim thread: will park inside expectationBackend.put("v", ...)
            pool.submit(() -> matchers.add(expectation("v"), API));

            // Wait until the victim has done its node-local insert and is parked in
            // the backend put (pre-fix: NOT holding any monitor; post-fix: holding
            // the RequestMatchers monitor).
            assertThat("victim reached backend put",
                backend.victimReachedPut.await(10, TimeUnit.SECONDS), is(true));

            // Aggressor thread: adds "a". Pre-fix this completes (and runs the racy
            // trim) while the victim is parked; post-fix it blocks on the monitor
            // the parked victim still holds.
            pool.submit(() -> matchers.add(expectation("a"), API));

            // Give the aggressor time to either (pre-fix) complete its add + trim,
            // or (post-fix) block on the monitor. A generous, timing-insensitive
            // window: it only needs a thread to run a handful of statements.
            Thread.sleep(500);

            // Release the victim's backend put. Pre-fix: the victim has already been
            // trimmed from the node-local cache and is never rebuilt. Post-fix: the
            // victim completes and releases the monitor, letting the aggressor finish.
            backend.victimMayProceed.countDown();

            pool.shutdown();
            assertThat("both adds completed",
                pool.awaitTermination(10, TimeUnit.SECONDS), is(true));
        } finally {
            pool.shutdownNow();
        }

        List<Expectation> active = matchers.retrieveActiveExpectations(request());
        Set<String> activeIds = active.stream().map(Expectation::getId).collect(Collectors.toSet());
        assertThat("aggressor 'a' present", activeIds, hasItem("a"));
        assertThat("victim 'v' present (was silently dropped before the fix)", activeIds, hasItem("v"));
        assertThat("exactly two active expectations", active.size(), is(2));
    }

    // ---------------------------------------------------------------------
    // Hammer test
    // ---------------------------------------------------------------------

    @Test(timeout = 120_000)
    public void concurrentAddsStoreEveryExpectation() throws Exception {
        final int threads = 8;
        final int perThread = 64;
        final int rounds = 6;
        final int expectedTotal = threads * perThread;

        for (int round = 0; round < rounds; round++) {
            RequestMatchers matchers = newMatchers(new InMemoryStateBackend(LARGE_MAX));
            CyclicBarrier startBarrier = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            Set<String> addedIds = ConcurrentHashMap.newKeySet();
            try {
                for (int t = 0; t < threads; t++) {
                    final int threadIndex = t;
                    final int roundIndex = round;
                    pool.submit(() -> {
                        startBarrier.await();
                        for (int i = 0; i < perThread; i++) {
                            String id = "r" + roundIndex + "-t" + threadIndex + "-e" + i;
                            Expectation stored = matchers.add(expectation(id), API);
                            // add() returns the stored expectation; its id is the
                            // 201-acknowledged id the caller was handed.
                            addedIds.add(stored.getId());
                        }
                        return null;
                    });
                }
                pool.shutdown();
                assertThat("round " + round + " workers finished",
                    pool.awaitTermination(60, TimeUnit.SECONDS), is(true));
            } finally {
                pool.shutdownNow();
            }

            List<Expectation> active = matchers.retrieveActiveExpectations(request());
            Set<String> activeIds = active.stream().map(Expectation::getId).collect(Collectors.toSet());

            // Every id acknowledged by add() (returned to the caller with a 201) must be
            // retrievable, and nothing extra must appear — i.e. the retrievable set is
            // exactly the acknowledged set. Before the fix, a concurrent add's trim drops
            // victims so activeIds is a strict subset of addedIds.
            assertThat("round " + round + ": retrievable ids == acknowledged ids",
                activeIds, is(addedIds));
            assertThat("round " + round + ": no expectations dropped",
                active.size(), is(expectedTotal));
            assertThat("round " + round + ": all " + expectedTotal + " ids acknowledged",
                addedIds.size(), is(expectedTotal));
        }
    }

    // ---------------------------------------------------------------------
    // Test-only backend that parks the victim add between its local put and
    // its backend put. Delegates everything else to a real InMemoryStateBackend.
    // ---------------------------------------------------------------------

    private static final class ParkingStateBackend implements StateBackend {
        private final InMemoryStateBackend delegate;
        private final KeyValueStore<ExpectationEntry> parkingExpectations;
        final CountDownLatch victimReachedPut = new CountDownLatch(1);
        final CountDownLatch victimMayProceed = new CountDownLatch(1);

        ParkingStateBackend(InMemoryStateBackend delegate, String victimId) {
            this.delegate = delegate;
            this.parkingExpectations = new ParkingKeyValueStore(delegate.expectations(), victimId);
        }

        @Override
        public KeyValueStore<ExpectationEntry> expectations() {
            return parkingExpectations;
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
            return false;
        }

        @Override
        public ClusterInfo clusterInfo() {
            return delegate.clusterInfo();
        }

        @Override
        public void close() {
            delegate.close();
        }

        private final class ParkingKeyValueStore implements KeyValueStore<ExpectationEntry> {
            private final KeyValueStore<ExpectationEntry> delegateStore;
            private final String victimId;
            private final AtomicBoolean parked = new AtomicBoolean(false);

            ParkingKeyValueStore(KeyValueStore<ExpectationEntry> delegateStore, String victimId) {
                this.delegateStore = delegateStore;
                this.victimId = victimId;
            }

            @Override
            public long put(String key, ExpectationEntry value) {
                if (victimId.equals(key) && parked.compareAndSet(false, true)) {
                    // Signal that the victim's node-local insert is done and it is
                    // about to (but has not yet) written to the backend, then park.
                    victimReachedPut.countDown();
                    try {
                        victimMayProceed.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return delegateStore.put(key, value);
            }

            @Override
            public Optional<Versioned<ExpectationEntry>> get(String key) {
                return delegateStore.get(key);
            }

            @Override
            public Optional<Versioned<ExpectationEntry>> putIfAbsent(String key, ExpectationEntry value) {
                return delegateStore.putIfAbsent(key, value);
            }

            @Override
            public boolean compareAndSet(String key, long expectedVersion, ExpectationEntry value) {
                return delegateStore.compareAndSet(key, expectedVersion, value);
            }

            @Override
            public boolean compareAndRemove(String key, long expectedVersion) {
                return delegateStore.compareAndRemove(key, expectedVersion);
            }

            @Override
            public boolean remove(String key) {
                return delegateStore.remove(key);
            }

            @Override
            public Stream<Entry<ExpectationEntry>> entries() {
                return delegateStore.entries();
            }

            @Override
            public int size() {
                return delegateStore.size();
            }

            @Override
            public void clear() {
                delegateStore.clear();
            }

            @Override
            public void setMaxSize(int maxSize) {
                delegateStore.setMaxSize(maxSize);
            }

            @Override
            public void addInvalidationListener(InvalidationListener listener) {
                delegateStore.addInvalidationListener(listener);
            }
        }
    }
}
