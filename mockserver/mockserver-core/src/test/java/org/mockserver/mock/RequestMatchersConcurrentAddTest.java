package org.mockserver.mock;

import org.junit.After;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.BlobStore;
import org.mockserver.state.ExpectationEntry;
import org.mockserver.state.InMemoryStateBackend;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.mockserver.state.Versioned;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * Concurrency regression tests for {@link RequestMatchers#add} against the state backend
 * (issue #2579): concurrently created expectations must not be silently dropped, and — the
 * lesson of the reverted first fix ({@code 98ab5d8de}) — the fix must never hold a lock
 * across a backend call, so two concurrent adds whose backend {@code put}s interleave must
 * never deadlock.
 * <p>
 * Each test uses a {@link LatchingStateBackend} whose expectation-store {@code put} can be
 * parked for a chosen id, which lets the exact racy interleaving be forced deterministically
 * rather than relying on timing.
 */
public class RequestMatchersConcurrentAddTest {

    private final List<ExecutorService> pools = new CopyOnWriteArrayList<>();

    @After
    public void tearDown() {
        pools.forEach(ExecutorService::shutdownNow);
    }

    private RequestMatchers newMatchers(Configuration configuration, StateBackend backend) {
        RequestMatchers matchers = new RequestMatchers(
            configuration, new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        matchers.setStateBackend(backend);
        return matchers;
    }

    private ExecutorService pool(int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        pools.add(pool);
        return pool;
    }

    private static Expectation expectation(String id) {
        return new Expectation(request().withPath("/" + id)).withId(id)
            .thenRespond(response().withBody(id));
    }

    private static List<String> activeIds(RequestMatchers matchers) {
        return matchers.retrieveActiveExpectations(null).stream()
            .map(Expectation::getId).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------
    // Test 1 — DETERMINISTIC drop reproduction.
    // Park add(A) between its node-local insert and its backend put, then run
    // add(B) fully (including its eviction trim) while A is parked. On the
    // pre-fix code B's trim mis-classifies A's just-inserted local matcher as
    // backend-evicted (A is in the local cache but not yet in the backend) and
    // deletes it, so A is permanently absent. With the fix A is protected as an
    // in-flight add and survives.
    // ---------------------------------------------------------------------
    @Test(timeout = 30_000)
    public void concurrentAddDoesNotDropExpectationWhenTrimRacesAnInFlightPut() throws Exception {
        // maxExpectations high enough that NO genuine eviction can occur — any drop is the bug.
        Configuration configuration = configuration().maxExpectations(100);
        LatchingStateBackend backend = new LatchingStateBackend(100);
        RequestMatchers matchers = newMatchers(configuration, backend);

        // Park the backend put for "A" so add(A) is suspended AFTER its local insert.
        backend.parkPutFor("A");

        ExecutorService pool = pool(2);
        AtomicReference<Throwable> addAError = new AtomicReference<>();
        Thread aThread = new Thread(() -> {
            try {
                matchers.add(expectation("A"), API);
            } catch (Throwable t) {
                addAError.set(t);
            }
        }, "add-A");
        aThread.start();

        // Wait until add(A) has done its local insert and is blocked inside put("A").
        assertThat("add(A) should reach its parked backend put",
            backend.awaitParked("A", 10, TimeUnit.SECONDS), is(true));

        // Now run add(B) to completion on another thread — its trim runs while A is parked.
        pool.submit(() -> matchers.add(expectation("B"), API)).get(10, TimeUnit.SECONDS);

        // Release A's put so add(A) can finish.
        backend.releasePut("A");
        aThread.join(TimeUnit.SECONDS.toMillis(10));
        assertThat("add(A) thread should terminate", aThread.isAlive(), is(false));
        assertThat("add(A) must not throw", addAError.get(), is((Throwable) null));

        // BOTH acknowledged expectations must be retrievable — the pre-fix code drops "A".
        List<String> ids = activeIds(matchers);
        assertThat("expectation A must survive the concurrent trim", ids, hasItem("A"));
        assertThat("expectation B must survive", ids, hasItem("B"));
        assertThat("backend must hold both", backend.expectations().size(), is(2));
    }

    // ---------------------------------------------------------------------
    // Test 2 — HAMMER. N threads x M distinct expectations across several
    // rounds; every acknowledged id must be retrievable afterwards.
    // ---------------------------------------------------------------------
    @Test(timeout = 120_000)
    public void concurrentAddsNeverLoseAnAcknowledgedExpectation() throws Exception {
        final int threads = 8;
        final int perThread = 64;
        final int rounds = 6;
        Configuration configuration = configuration().maxExpectations(100_000);

        for (int round = 0; round < rounds; round++) {
            LatchingStateBackend backend = new LatchingStateBackend(100_000);
            RequestMatchers matchers = newMatchers(configuration, backend);
            ExecutorService pool = pool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            List<String> expectedIds = new CopyOnWriteArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadIndex = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String id = "r" + threadIndex + "-" + i;
                        expectedIds.add(id);
                        matchers.add(expectation(id), API);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            List<String> ids = activeIds(matchers);
            assertThat("round " + round + ": expected " + (threads * perThread) + " expectations",
                ids.size(), is(threads * perThread));
            for (String expectedId : expectedIds) {
                assertThat("round " + round + ": missing acknowledged id " + expectedId, ids, hasItem(expectedId));
            }
        }
    }

    // ---------------------------------------------------------------------
    // Test 3 — DEADLOCK GUARD (the test that would have caught the revert).
    // The backend put for "A" blocks until a SECOND concurrent add("B") on
    // another thread has completed. If any implementation holds a lock across
    // the backend put (as the reverted 98ab5d8de did), add("B") cannot acquire
    // that same lock, so it never completes, so A's put never unblocks — a
    // deadlock. The correct fix releases the monitor before the backend put, so
    // both complete. Bounded by the @Test timeout and explicit join assertions.
    // ---------------------------------------------------------------------
    @Test(timeout = 30_000)
    public void twoConcurrentAddsWhosePutsInterleaveDoNotDeadlock() throws Exception {
        Configuration configuration = configuration().maxExpectations(100);
        CountDownLatch secondAddComplete = new CountDownLatch(1);
        LatchingStateBackend backend = new LatchingStateBackend(100);
        // put("A") blocks until add("B") has fully returned.
        backend.gatePutFor("A", secondAddComplete);
        RequestMatchers matchers = newMatchers(configuration, backend);

        AtomicReference<Throwable> errors = new AtomicReference<>();
        Thread aThread = new Thread(() -> {
            try {
                matchers.add(expectation("A"), API);
            } catch (Throwable t) {
                errors.compareAndSet(null, t);
            }
        }, "add-A");
        Thread bThread = new Thread(() -> {
            try {
                matchers.add(expectation("B"), API);
            } catch (Throwable t) {
                errors.compareAndSet(null, t);
            } finally {
                secondAddComplete.countDown();
            }
        }, "add-B");

        aThread.start();
        // Ensure add(A) is inside its (gated) backend put before add(B) starts, so the
        // interleaving under test — A mid-put while B needs to mutate — is the one exercised.
        assertThat("add(A) should reach its gated backend put",
            backend.awaitParked("A", 10, TimeUnit.SECONDS), is(true));
        bThread.start();

        bThread.join(TimeUnit.SECONDS.toMillis(15));
        aThread.join(TimeUnit.SECONDS.toMillis(15));

        boolean deadlocked = aThread.isAlive() || bThread.isAlive();
        // On failure, unwedge the threads so the suite is not left with leaked live threads.
        if (deadlocked) {
            aThread.interrupt();
            bThread.interrupt();
        }
        assertThat("add(A)/add(B) deadlocked — a lock is held across the backend put", deadlocked, is(false));
        assertThat("neither add should throw", errors.get(), is((Throwable) null));

        List<String> ids = activeIds(matchers);
        assertThat(ids, hasItem("A"));
        assertThat(ids, hasItem("B"));
    }

    // ---------------------------------------------------------------------
    // Test 4 — CLUSTERED-PATH regression (reconcileClusteredScan ordering).
    // A reconcile driven by a (simulated) remote invalidation is suspended
    // while holding a backend snapshot taken BEFORE a concurrent local add
    // persists. On the buggy ordering the reconcile read the LIVE cache last,
    // saw the just-added id, found it absent from its stale backend snapshot
    // and from the (now empty) protected set, and evicted it — reintroducing
    // the #2579 drop on the clustered path. With cachedIds snapshotted FIRST
    // and the backend LAST, the reconcile's eviction set is empty and the
    // concurrently-added expectation survives.
    // ---------------------------------------------------------------------
    @Test(timeout = 30_000)
    public void clusteredReconcileDoesNotDropAConcurrentlyAddedExpectation() throws Exception {
        Configuration configuration = configuration().maxExpectations(100);
        LatchingStateBackend backend = new LatchingStateBackend(100, /* clustered */ true);
        RequestMatchers matchers = newMatchers(configuration, backend);

        // Arm: the first entries() call (this reconcile's backend snapshot) parks holding an
        // empty snapshot until released.
        backend.parkFirstEntries();

        AtomicReference<Throwable> errors = new AtomicReference<>();
        Thread reconcileThread = new Thread(() -> {
            try {
                matchers.reconcileFromBackend();
            } catch (Throwable t) {
                errors.compareAndSet(null, t);
            }
        }, "clustered-reconcile");
        reconcileThread.start();

        // Wait until the reconcile is parked holding its (empty) backend snapshot.
        assertThat("reconcile should reach its parked backend snapshot",
            backend.awaitEntriesParked(10, TimeUnit.SECONDS), is(true));

        // A complete add(X) lands while the reconcile is parked (its own entries() call passes
        // through, since only the first is armed).
        pool(1).submit(() -> matchers.add(expectation("X"), API)).get(10, TimeUnit.SECONDS);

        // Release the parked reconcile; it now applies its diff against the stale snapshot.
        backend.releaseEntries();
        reconcileThread.join(TimeUnit.SECONDS.toMillis(10));
        assertThat("reconcile thread should terminate", reconcileThread.isAlive(), is(false));
        assertThat("reconcile must not throw", errors.get(), is((Throwable) null));

        // X, added concurrently, must NOT have been evicted by the stale reconcile.
        List<String> ids = activeIds(matchers);
        assertThat("concurrently-added X must survive the clustered reconcile", ids, hasItem("X"));
        assertThat("backend must still hold X", backend.expectations().size(), is(1));
    }

    // =====================================================================
    // Test seam: a StateBackend that delegates to InMemoryStateBackend but
    // wraps the expectation store so a chosen id's put can be parked/gated.
    // =====================================================================
    private static final class LatchingStateBackend implements StateBackend {
        private final InMemoryStateBackend delegate;
        private final LatchingExpectationStore expectations;
        private final boolean clustered;

        LatchingStateBackend(int maxExpectations) {
            this(maxExpectations, false);
        }

        LatchingStateBackend(int maxExpectations, boolean clustered) {
            this.delegate = new InMemoryStateBackend(maxExpectations);
            this.expectations = new LatchingExpectationStore(delegate.expectations());
            this.clustered = clustered;
        }

        void parkFirstEntries() {
            expectations.parkFirstEntries();
        }

        boolean awaitEntriesParked(long timeout, TimeUnit unit) throws InterruptedException {
            return expectations.awaitEntriesParked(timeout, unit);
        }

        void releaseEntries() {
            expectations.releaseEntries();
        }

        void parkPutFor(String id) {
            expectations.parkPutFor(id);
        }

        void gatePutFor(String id, CountDownLatch releaseWhenCounted) {
            expectations.gatePutFor(id, releaseWhenCounted);
        }

        boolean awaitParked(String id, long timeout, TimeUnit unit) throws InterruptedException {
            return expectations.awaitParked(id, timeout, unit);
        }

        void releasePut(String id) {
            expectations.releasePut(id);
        }

        @Override
        public KeyValueStore<ExpectationEntry> expectations() {
            return expectations;
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
            return clustered;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Delegating expectation {@link KeyValueStore} whose {@link #put} can be parked (blocks
     * until explicitly released) or gated (blocks until a supplied latch reaches zero) for a
     * chosen key, so a concurrent trim / second add can be interleaved deterministically.
     */
    private static final class LatchingExpectationStore implements KeyValueStore<ExpectationEntry> {
        private final KeyValueStore<ExpectationEntry> delegate;
        private final ConcurrentHashMap<String, CountDownLatch> release = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CountDownLatch> entered = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CountDownLatch> gate = new ConcurrentHashMap<>();
        // Parks the FIRST entries() call: on that call the store captures a snapshot of the
        // current backend, signals enteredEntries, awaits releaseEntries, then returns the
        // STALE captured snapshot. Subsequent entries() calls pass straight through. This lets a
        // reconcile be suspended holding a backend snapshot taken before a concurrent add lands.
        private final java.util.concurrent.atomic.AtomicBoolean armEntries = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final CountDownLatch enteredEntries = new CountDownLatch(1);
        private final CountDownLatch releaseEntries = new CountDownLatch(1);

        LatchingExpectationStore(KeyValueStore<ExpectationEntry> delegate) {
            this.delegate = delegate;
        }

        void parkFirstEntries() {
            armEntries.set(true);
        }

        boolean awaitEntriesParked(long timeout, TimeUnit unit) throws InterruptedException {
            return enteredEntries.await(timeout, unit);
        }

        void releaseEntries() {
            releaseEntries.countDown();
        }

        void parkPutFor(String id) {
            entered.put(id, new CountDownLatch(1));
            release.put(id, new CountDownLatch(1));
        }

        void gatePutFor(String id, CountDownLatch gateLatch) {
            entered.put(id, new CountDownLatch(1));
            gate.put(id, gateLatch);
        }

        boolean awaitParked(String id, long timeout, TimeUnit unit) throws InterruptedException {
            CountDownLatch enteredLatch = entered.get(id);
            return enteredLatch != null && enteredLatch.await(timeout, unit);
        }

        void releasePut(String id) {
            CountDownLatch releaseLatch = release.get(id);
            if (releaseLatch != null) {
                releaseLatch.countDown();
            }
        }

        @Override
        public long put(String key, ExpectationEntry value) {
            CountDownLatch enteredLatch = entered.get(key);
            if (enteredLatch != null) {
                enteredLatch.countDown();
                try {
                    CountDownLatch releaseLatch = release.get(key);
                    if (releaseLatch != null) {
                        releaseLatch.await();
                    }
                    CountDownLatch gateLatch = gate.get(key);
                    if (gateLatch != null) {
                        gateLatch.await();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.put(key, value);
        }

        @Override
        public Optional<Versioned<ExpectationEntry>> get(String key) {
            return delegate.get(key);
        }

        @Override
        public Optional<Versioned<ExpectationEntry>> putIfAbsent(String key, ExpectationEntry value) {
            return delegate.putIfAbsent(key, value);
        }

        @Override
        public boolean compareAndSet(String key, long expectedVersion, ExpectationEntry value) {
            return delegate.compareAndSet(key, expectedVersion, value);
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
        public Stream<Entry<ExpectationEntry>> entries() {
            // Only the FIRST caller after parkFirstEntries() parks; it captures the snapshot NOW
            // (before the park) and returns that stale snapshot on release, reproducing a reconcile
            // that read the backend before a concurrent add persisted.
            if (armEntries.compareAndSet(true, false)) {
                List<Entry<ExpectationEntry>> snapshot = delegate.entries().collect(Collectors.toList());
                enteredEntries.countDown();
                try {
                    releaseEntries.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return snapshot.stream();
            }
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
}
