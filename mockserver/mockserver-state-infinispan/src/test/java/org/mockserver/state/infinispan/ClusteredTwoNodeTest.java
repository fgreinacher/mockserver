package org.mockserver.state.infinispan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.ScenarioManager;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.*;

import org.mockserver.matchers.Times;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Phase 2e acceptance test: starts TWO {@link InfinispanStateBackend}
 * instances clustered via in-JVM JGroups (loopback, REPL_SYNC), then
 * asserts cross-node visibility for expectations, scenario states, and
 * CRUD entities.
 * <p>
 * <b>Design:</b>
 * <ul>
 *   <li>Both nodes use the same clusterName and the built-in
 *       {@code jgroups-loopback.xml} stack (TCP/MPING on loopback,
 *       TTL=0, bind_port=0 for automatic port assignment).</li>
 *   <li>Cluster formation is awaited deterministically by polling
 *       the JGroups view size with a timeout — no fixed sleep.</li>
 *   <li>Propagation of writes is synchronous (REPL_SYNC), so once
 *       a put returns on node A, the value is visible on node B
 *       without additional waiting.</li>
 * </ul>
 * <p>
 * <b>No Docker, no external service.</b> This test runs in a single
 * JVM and can execute in CI as a regular unit test.
 */
@Timeout(60) // safety net: kill the test if cluster formation hangs
class ClusteredTwoNodeTest {

    private static final int MAX_EXPECTATIONS = 100;
    private static final Duration CLUSTER_FORMATION_TIMEOUT = Duration.ofSeconds(30);

    // Unique per test method: a static name reused across @BeforeEach/@AfterEach cycles
    // races JGroups SHARED_LOOPBACK channel teardown, making sibling tests flaky.
    private String clusterName;
    private InfinispanStateBackend nodeA;
    private InfinispanStateBackend nodeB;

    @BeforeEach
    void setUp() throws Exception {
        clusterName = "mockserver-test-cluster-" + System.nanoTime();
        // Start node A
        Configuration configA = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS)
            .stateBackend("infinispan")
            .clusterEnabled(true)
            .clusterName(clusterName);
        nodeA = new InfinispanStateBackend(configA);

        // Start node B
        Configuration configB = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS)
            .stateBackend("infinispan")
            .clusterEnabled(true)
            .clusterName(clusterName);
        nodeB = new InfinispanStateBackend(configB);

        // Wait for the 2-node cluster to form
        awaitClusterSize(nodeA, 2, CLUSTER_FORMATION_TIMEOUT);
        awaitClusterSize(nodeB, 2, CLUSTER_FORMATION_TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    // --- Cross-node visibility: Expectations ---

    @Test
    void expectationWrittenOnNodeAShouldBeVisibleOnNodeB() {
        // Given: an expectation put on node A
        Expectation expectation = Expectation.when(
            HttpRequest.request("/test-path")
        ).thenRespond(HttpResponse.response("OK"));

        nodeA.expectations().put(expectation.getId(), new ExpectationEntry(expectation));

        // Then: visible on node B (REPL_SYNC = synchronous replication)
        Optional<Versioned<ExpectationEntry>> result = nodeB.expectations().get(expectation.getId());
        assertTrue(result.isPresent(), "expectation should be visible on node B");
        assertThat(result.get().getValue().getId(), is(expectation.getId()));
        assertThat(result.get().getValue().getExpectation().getHttpRequest(),
            is(expectation.getHttpRequest()));
    }

    @Test
    void expectationRemovedOnNodeBShouldDisappearFromNodeA() {
        // Given: an expectation on node A
        Expectation expectation = Expectation.when(
            HttpRequest.request("/to-remove")
        ).thenRespond(HttpResponse.response("gone"));

        nodeA.expectations().put(expectation.getId(), new ExpectationEntry(expectation));
        assertTrue(nodeB.expectations().get(expectation.getId()).isPresent());

        // When: removed on node B
        nodeB.expectations().remove(expectation.getId());

        // Then: gone from node A
        assertFalse(nodeA.expectations().get(expectation.getId()).isPresent(),
            "removed expectation should disappear from node A");
    }

    @Test
    void expectationUpdatedOnNodeAShouldReflectOnNodeB() {
        Expectation original = Expectation.when(
            HttpRequest.request("/update-test")
        ).thenRespond(HttpResponse.response("v1"));

        long v1 = nodeA.expectations().put(original.getId(), new ExpectationEntry(original));

        // Update the expectation on node A
        Expectation updated = Expectation.when(
            HttpRequest.request("/update-test")
        ).thenRespond(HttpResponse.response("v2"));
        // Use same ID for an update
        updated.withId(original.getId());
        updated.withCreated(original.getCreated());

        long v2 = nodeA.expectations().put(updated.getId(), new ExpectationEntry(updated));
        assertThat(v2, greaterThan(v1));

        // Should see updated version on node B
        Optional<Versioned<ExpectationEntry>> result = nodeB.expectations().get(updated.getId());
        assertTrue(result.isPresent());
        assertThat(result.get().getVersion(), is(v2));
    }

    // --- Cross-node visibility: Scenario States ---

    @Test
    void scenarioStateWrittenOnNodeAShouldBeVisibleOnNodeB() {
        nodeA.scenarioStates().put("scenario1", "Started");

        Optional<Versioned<String>> result = nodeB.scenarioStates().get("scenario1");
        assertTrue(result.isPresent(), "scenario state should be visible on node B");
        assertThat(result.get().getValue(), is("Started"));
    }

    @Test
    void scenarioStateCASOnNodeAShouldBeVisibleOnNodeB() {
        long v1 = nodeA.scenarioStates().put("cas-scenario", "Started");

        boolean success = nodeA.scenarioStates().compareAndSet("cas-scenario", v1, "Step1");
        assertTrue(success);

        Optional<Versioned<String>> result = nodeB.scenarioStates().get("cas-scenario");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("Step1"));
    }

    // --- Cross-node visibility: CRUD Entities ---

    @Test
    void crudEntityWrittenOnNodeAShouldBeVisibleOnNodeB() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode entity = mapper.createObjectNode().put("name", "test-entity").put("value", 42);

        nodeA.crudEntities("testNs").put("entity1", entity);

        Optional<Versioned<ObjectNode>> result = nodeB.crudEntities("testNs").get("entity1");
        assertTrue(result.isPresent(), "CRUD entity should be visible on node B");
        assertThat(result.get().getValue().get("name").asText(), is("test-entity"));
        assertThat(result.get().getValue().get("value").asInt(), is(42));
    }

    // --- Cross-node invalidation listener ---

    @Test
    void invalidationListenerOnNodeBShouldFireWhenNodeAPutsExpectation() {
        AtomicInteger invalidationCount = new AtomicInteger(0);
        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                invalidationCount.incrementAndGet();
            }

            @Override
            public void onCleared() {
            }
        });

        // Write on node A
        Expectation expectation = Expectation.when(
            HttpRequest.request("/invalidation-test")
        ).thenRespond(HttpResponse.response("OK"));
        nodeA.expectations().put(expectation.getId(), new ExpectationEntry(expectation));

        // The clustered listener (clustered=true) fires for remote writes.
        // REPL_SYNC ensures the put has completed on both nodes, but the
        // listener may fire asynchronously. Poll briefly.
        awaitCondition(() -> invalidationCount.get() > 0, Duration.ofSeconds(5),
            "invalidation listener on node B should have fired");
    }

    // --- Cluster properties ---

    @Test
    void bothNodesShouldBeClustered() {
        assertTrue(nodeA.isClustered());
        assertTrue(nodeB.isClustered());
    }

    @Test
    void nodesShouldHaveDifferentIds() {
        assertNotEquals(nodeA.nodeId(), nodeB.nodeId());
    }

    @Test
    void clusterInfoShouldReportBothMembersWithSingleCoordinator() {
        // clusterInfo() reads real JGroups membership from the cache manager
        ClusterInfo infoA = nodeA.clusterInfo();
        ClusterInfo infoB = nodeB.clusterInfo();

        assertTrue(infoA.clustered(), "node A reports clustered");
        assertTrue(infoB.clustered(), "node B reports clustered");

        // both nodes see the full 2-member fleet
        assertThat(infoA.members(), hasSize(2));
        assertThat(infoB.members(), hasSize(2));

        // exactly one coordinator across the fleet, agreed by both nodes
        assertThat(infoA.members().stream().filter(ClusterInfo.Member::coordinator).count(), is(1L));
        assertThat(infoB.members().stream().filter(ClusterInfo.Member::coordinator).count(), is(1L));
        assertThat("both nodes agree on the coordinator", infoA.coordinator(), is(infoB.coordinator()));

        // each node flags exactly one member (itself) as local, and the two
        // nodes' local members are distinct
        assertThat(infoA.members().stream().filter(ClusterInfo.Member::local).count(), is(1L));
        assertThat(infoB.members().stream().filter(ClusterInfo.Member::local).count(), is(1L));
        String localOnA = infoA.members().stream().filter(ClusterInfo.Member::local).findFirst().get().id();
        String localOnB = infoB.members().stream().filter(ClusterInfo.Member::local).findFirst().get().id();
        assertNotEquals(localOnA, localOnB, "each node's local member is its own address");
        assertThat(infoA.nodeId(), is(localOnA));
        assertThat(infoB.nodeId(), is(localOnB));

        // the cluster name is reported
        assertThat(infoA.clusterName(), is(clusterName));
    }

    @Test
    void clearOnNodeAShouldClearNodeB() {
        nodeA.scenarioStates().put("s1", "v1");
        nodeA.scenarioStates().put("s2", "v2");
        assertTrue(nodeB.scenarioStates().get("s1").isPresent());
        assertTrue(nodeB.scenarioStates().get("s2").isPresent());

        nodeA.scenarioStates().clear();

        assertFalse(nodeB.scenarioStates().get("s1").isPresent());
        assertFalse(nodeB.scenarioStates().get("s2").isPresent());
        assertThat(nodeB.scenarioStates().size(), is(0));
    }

    @Test
    void entriesStreamOnNodeBShouldIncludeNodeAWrites() {
        nodeA.scenarioStates().put("a", "1");
        nodeA.scenarioStates().put("b", "2");
        nodeA.scenarioStates().put("c", "3");

        long countOnB = nodeB.scenarioStates().entries().count();
        assertThat(countOnB, is(3L));
    }

    // --- Cross-node MATCHABILITY (end-to-end through RequestMatchers) ---

    /**
     * End-to-end test: creates an expectation on node A, wires a
     * {@link RequestMatchers} to node B's backend with the
     * InvalidationListener triggering reconcileFromBackend, then asserts
     * that node B's RequestMatchers.peekFirstMatchingExpectation returns
     * the match. This proves cross-node MATCHABILITY, not just KV presence.
     */
    @Test
    void expectationCreatedOnNodeAShouldBeMatchableOnNodeBViaRequestMatchers() {
        // Wire a RequestMatchers to node B's backend, mirroring HttpState's wiring
        Configuration configB = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS);
        RequestMatchers nodeBMatchers = new RequestMatchers(
            configB, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        nodeBMatchers.setStateBackend(nodeB);

        // Wire the invalidation listener (same as HttpState does)
        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                nodeBMatchers.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                nodeBMatchers.reconcileFromBackend();
            }
        });

        // Create expectation on node A
        Expectation expectation = Expectation.when(
            HttpRequest.request("/cross-node-match")
        ).thenRespond(HttpResponse.response("cross-node-ok"));
        nodeA.expectations().put(expectation.getId(), new ExpectationEntry(expectation));

        // The clustered listener fires asynchronously for remote writes.
        // REPL_SYNC ensures the put has completed on both nodes, but the
        // listener callback may be slightly delayed. Poll briefly.
        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/cross-node-match")) != null,
            Duration.ofSeconds(5),
            "node B's RequestMatchers should match the expectation created on node A"
        );

        // Verify the matched expectation is correct
        Expectation matched = nodeBMatchers.peekFirstMatchingExpectation(
            HttpRequest.request("/cross-node-match"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("cross-node-ok"));
    }

    // --- Clustered shared-Times CAS (G10 phase 2c) ---

    /**
     * G10 core guarantee: a {@code Times.once()} expectation, when both
     * nodes attempt to consume it concurrently, is served EXACTLY once
     * across the fleet. This test wires two {@link RequestMatchers}
     * instances (one per backend node) and fires concurrent match
     * attempts from two threads via a {@link CyclicBarrier}.
     */
    @Test
    void timesOnceShouldBeServedExactlyOnceAcrossTwoNodes() throws Exception {
        // Wire RequestMatchers to each node's backend
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA);
        RequestMatchers nodeBMatchers = createNodeMatchers(nodeB);

        // Wire invalidation listeners so reconcile fires
        wireReconciliationListener(nodeA, nodeAMatchers);
        wireReconciliationListener(nodeB, nodeBMatchers);

        // Create a Times.once() expectation on node A
        Expectation expectation = Expectation.when(
            HttpRequest.request("/times-once"),
            Times.once(),
            org.mockserver.matchers.TimeToLive.unlimited()
        ).thenRespond(HttpResponse.response("once-only"));
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);

        // Wait for node B to see it via replication + invalidation
        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/times-once")) != null,
            Duration.ofSeconds(5),
            "node B should see the expectation"
        );

        // Concurrent consume from both nodes
        HttpRequest request = HttpRequest.request("/times-once");
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Expectation> resultA = new AtomicReference<>();
        AtomicReference<Expectation> resultB = new AtomicReference<>();

        Thread threadA = new Thread(() -> {
            try {
                barrier.await();
                Expectation matched = nodeAMatchers.firstMatchingExpectation(request);
                resultA.set(matched);
                // Always postProcess to clear responseInProgress
                nodeAMatchers.postProcess(matched);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread threadB = new Thread(() -> {
            try {
                barrier.await();
                Expectation matched = nodeBMatchers.firstMatchingExpectation(request);
                resultB.set(matched);
                nodeBMatchers.postProcess(matched);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        threadA.start();
        threadB.start();
        threadA.join(10_000);
        threadB.join(10_000);

        // Exactly one should have matched, the other should be null
        int totalMatches = (resultA.get() != null ? 1 : 0) + (resultB.get() != null ? 1 : 0);
        assertThat("Times.once() should produce exactly 1 match across 2 nodes, got " + totalMatches,
            totalMatches, is(1));
    }

    /**
     * G10: a {@code Times.exactly(3)} expectation, when both nodes
     * alternate consuming, should produce exactly 3 matches total.
     */
    @Test
    void timesExactlyNShouldBeServedExactlyNTimesAcrossTwoNodes() throws Exception {
        final int N = 3;
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA);
        RequestMatchers nodeBMatchers = createNodeMatchers(nodeB);
        wireReconciliationListener(nodeA, nodeAMatchers);
        wireReconciliationListener(nodeB, nodeBMatchers);

        Expectation expectation = Expectation.when(
            HttpRequest.request("/times-n"),
            Times.exactly(N),
            org.mockserver.matchers.TimeToLive.unlimited()
        ).thenRespond(HttpResponse.response("n-times"));
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);

        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/times-n")) != null,
            Duration.ofSeconds(5),
            "node B should see the expectation"
        );

        HttpRequest request = HttpRequest.request("/times-n");
        AtomicInteger totalMatches = new AtomicInteger(0);

        // Alternate between nodes, attempting more than N matches
        for (int i = 0; i < N + 4; i++) {
            RequestMatchers matchers = (i % 2 == 0) ? nodeAMatchers : nodeBMatchers;
            Expectation matched = matchers.firstMatchingExpectation(request);
            matchers.postProcess(matched);
            if (matched != null) {
                totalMatches.incrementAndGet();
            }
        }

        assertThat("Times.exactly(" + N + ") should produce exactly " + N
                + " matches total across 2 nodes, got " + totalMatches.get(),
            totalMatches.get(), is(N));
    }

    /**
     * G10: a {@code Times.exactly(5)} expectation under high concurrency
     * (multiple threads per node racing) still produces exactly 5 matches.
     */
    @Test
    void timesExactlyUnderHighConcurrencyShouldNotDoubleServe() throws Exception {
        final int N = 5;
        final int THREADS_PER_NODE = 4;
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA);
        RequestMatchers nodeBMatchers = createNodeMatchers(nodeB);
        wireReconciliationListener(nodeA, nodeAMatchers);
        wireReconciliationListener(nodeB, nodeBMatchers);

        Expectation expectation = Expectation.when(
            HttpRequest.request("/times-concurrent"),
            Times.exactly(N),
            org.mockserver.matchers.TimeToLive.unlimited()
        ).thenRespond(HttpResponse.response("concurrent"));
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);

        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/times-concurrent")) != null,
            Duration.ofSeconds(5),
            "node B should see the expectation"
        );

        HttpRequest request = HttpRequest.request("/times-concurrent");
        CyclicBarrier barrier = new CyclicBarrier(THREADS_PER_NODE * 2);
        AtomicInteger totalMatches = new AtomicInteger(0);
        Thread[] threads = new Thread[THREADS_PER_NODE * 2];

        for (int i = 0; i < threads.length; i++) {
            RequestMatchers matchers = (i < THREADS_PER_NODE) ? nodeAMatchers : nodeBMatchers;
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    // Each thread attempts multiple matches
                    for (int attempt = 0; attempt < N; attempt++) {
                        Expectation matched = matchers.firstMatchingExpectation(request);
                        matchers.postProcess(matched);
                        if (matched != null) {
                            totalMatches.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join(30_000);
        }

        assertThat("Times.exactly(" + N + ") should produce exactly " + N
                + " total matches under high concurrency, got " + totalMatches.get(),
            totalMatches.get(), is(N));
    }

    /**
     * NON-NEGOTIABLE over-match guard for the dedicated shared-times counter
     * path. Many threads on BOTH nodes race to consume a {@code Times.exactly(N)}
     * expectation far more than N times; the test fails if the fleet-wide total
     * served EVER exceeds N (the correctness bug a user would see directly). It
     * also proves the NEW mechanism is what enforces the bound: the dedicated
     * shared-times counter — not the ExpectationEntry — is what reaches exactly
     * 0, and the ExpectationEntry's own remainingTimes is left at the seed.
     */
    @Test
    void sharedTimesCounterEnforcesExactlyNAcrossTwoNodesUnderContention() throws Exception {
        final int N = 7;
        final int THREADS_PER_NODE = 6;
        final int ATTEMPTS_PER_THREAD = 5; // 2*6*5 = 60 attempts for 7 slots
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA);
        RequestMatchers nodeBMatchers = createNodeMatchers(nodeB);
        wireReconciliationListener(nodeA, nodeAMatchers);
        wireReconciliationListener(nodeB, nodeBMatchers);

        Expectation expectation = Expectation.when(
            HttpRequest.request("/times-overmatch-guard"),
            Times.exactly(N),
            org.mockserver.matchers.TimeToLive.unlimited()
        ).thenRespond(HttpResponse.response("guard"));
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);

        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/times-overmatch-guard")) != null,
            Duration.ofSeconds(5),
            "node B should see the expectation"
        );

        HttpRequest request = HttpRequest.request("/times-overmatch-guard");
        CyclicBarrier barrier = new CyclicBarrier(THREADS_PER_NODE * 2);
        AtomicInteger totalMatches = new AtomicInteger(0);
        Thread[] threads = new Thread[THREADS_PER_NODE * 2];
        for (int i = 0; i < threads.length; i++) {
            RequestMatchers matchers = (i < THREADS_PER_NODE) ? nodeAMatchers : nodeBMatchers;
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    for (int attempt = 0; attempt < ATTEMPTS_PER_THREAD; attempt++) {
                        Expectation matched = matchers.firstMatchingExpectation(request);
                        matchers.postProcess(matched);
                        if (matched != null) {
                            totalMatches.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join(30_000);

        assertThat("Times.exactly(" + N + ") must NEVER be served more than N times "
                + "across two nodes, got " + totalMatches.get(),
            totalMatches.get(), is(N));

        // The dedicated counter is the mechanism that reached exactly 0...
        Optional<Versioned<Integer>> counter = nodeA.sharedTimesCounters().get(expectation.getId());
        assertTrue(counter.isPresent(), "shared-times counter should exist");
        assertThat("shared-times counter drained to exactly 0 (never negative)",
            counter.get().getValue(), is(0));
        // ...and the ExpectationEntry seed was never rewritten by a decrement.
        assertThat("ExpectationEntry remainingTimes stays at the immutable seed",
            nodeA.expectations().get(expectation.getId()).get().getValue().getRemainingTimes(),
            is(N));
    }

    /**
     * REGRESSION GUARD for the counter-eviction fail-open (review CRITICAL).
     * Infinispan memory eviction is node-local and NOT replicated. If an absent
     * counter were re-seeded from the expectation's original N, a node could
     * resurrect the full allotment over the live decremented value and
     * {@code Times.exactly(N)} would serve MORE than N fleet-wide.
     * <p>
     * <b>Determinism.</b> The buggy re-seed is {@code putIfAbsent(id, N)}, which
     * only resurrects when the evicting node's consume reaches a PRIMARY OWNER
     * that has also lost the entry. JGroups node names and the expectation UUID
     * are random per run, so evicting on ONE node makes the regression fire only
     * when that node happens to be the key's primary owner — a coin flip. This
     * test therefore evicts the counter on <b>both</b> nodes, so the key is
     * absent at its primary owner regardless of which node that is, and the
     * buggy {@code putIfAbsent} deterministically creates (resurrects) N every
     * run. Verified: RED on all 5 lazy-re-seed degrade runs (total = 6);
     * GREEN with the fix (fail-closed: both nodes read absent, serve nothing
     * more, total = 1 &le; N).
     * <p>
     * The load-bearing assertion is {@code total <= N}. (A pre-eviction serve
     * count &gt; 0 is asserted separately so a wholesale non-match cannot make
     * this pass vacuously.)
     */
    @Test
    void sharedTimesCounterEvictedOnAllNodesMustNotOverServe() throws Exception {
        final int N = 5;
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA);
        RequestMatchers nodeBMatchers = createNodeMatchers(nodeB);
        wireReconciliationListener(nodeA, nodeAMatchers);
        wireReconciliationListener(nodeB, nodeBMatchers);

        Expectation expectation = Expectation.when(
            HttpRequest.request("/times-evict-all"),
            Times.exactly(N),
            org.mockserver.matchers.TimeToLive.unlimited()
        ).thenRespond(HttpResponse.response("evict"));
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);
        String id = expectation.getId();

        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/times-evict-all")) != null,
            Duration.ofSeconds(5),
            "node B should see the expectation"
        );

        HttpRequest request = HttpRequest.request("/times-evict-all");
        int totalServed = 0;

        // Serve ONCE so the counter is live (remaining = N-1) when evicted.
        Expectation first = nodeAMatchers.firstMatchingExpectation(request);
        nodeAMatchers.postProcess(first);
        if (first != null) {
            totalServed++;
        }
        assertThat("counter is live before eviction",
            nodeA.sharedTimesCounters().get(id).get().getValue(), is(N - 1));

        // Evict the LIVE counter on BOTH nodes: the key is now absent at its
        // primary owner whichever node that is, so the buggy putIfAbsent(N) is
        // deterministically a create (resurrect), not an ownership coin flip.
        nodeA.getCacheManager().getCache(InfinispanStateBackend.SHARED_TIMES_CACHE).evict(id);
        nodeB.getCacheManager().getCache(InfinispanStateBackend.SHARED_TIMES_CACHE).evict(id);
        assertThat("node A counter replica evicted",
            nodeA.sharedTimesCounters().get(id).isPresent(), is(false));
        assertThat("node B counter replica evicted",
            nodeB.sharedTimesCounters().get(id).isPresent(), is(false));

        // Hammer both nodes far more than the remaining allotment. Sequential so
        // the count is deterministic under REPL_SYNC.
        for (int i = 0; i < N * 2; i++) {
            RequestMatchers matchers = (i % 2 == 0) ? nodeAMatchers : nodeBMatchers;
            Expectation matched = matchers.firstMatchingExpectation(request);
            matchers.postProcess(matched);
            if (matched != null) {
                totalServed++;
            }
        }

        assertThat("Times.exactly(" + N + ") must NEVER serve more than N fleet-wide, "
                + "even after eviction of a live counter, got " + totalServed,
            totalServed, lessThanOrEqualTo(N));
        // Non-vacuity: the pre-eviction serve must have counted (weak guard —
        // the load-bearing assertion is total <= N above).
        assertThat("at least the pre-eviction match served", totalServed, greaterThanOrEqualTo(1));
    }

    /**
     * Pins the node-local semantics of Infinispan {@code Cache.evict()} that the
     * fix relies on: evicting on ONE node does NOT remove the entry from peers.
     * After node A evicts its counter replica, node B STILL HOLDS the true live
     * value (N-1) — this is the deterministic, ownership-independent observation
     * (a {@code get} is a local read; node B never evicted). Hammering both nodes
     * then stays &le; N (the fix never over-serves).
     * <p>
     * NOTE: the exact number node B serves after A's eviction is NOT asserted —
     * it is ownership-dependent. Node B's CAS is a {@code cache.replace} routed
     * to the key's primary owner; if that owner is the evicted node A, the
     * replace sees absent and fails, so B serves 0, whereas if B is primary it
     * serves its remaining N-1. Both outcomes satisfy the safety bound; only the
     * bound is asserted. This is also why the over-serve guard above evicts on
     * both nodes rather than relying on this single-node path.
     */
    @Test
    void nodeLocalCounterEvictionIsNotReplicatedToPeer() throws Exception {
        final int N = 5;
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA);
        RequestMatchers nodeBMatchers = createNodeMatchers(nodeB);
        wireReconciliationListener(nodeA, nodeAMatchers);
        wireReconciliationListener(nodeB, nodeBMatchers);

        Expectation expectation = Expectation.when(
            HttpRequest.request("/times-evict-one"),
            Times.exactly(N),
            org.mockserver.matchers.TimeToLive.unlimited()
        ).thenRespond(HttpResponse.response("evict-one"));
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);
        String id = expectation.getId();

        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/times-evict-one")) != null,
            Duration.ofSeconds(5),
            "node B should see the expectation"
        );

        HttpRequest request = HttpRequest.request("/times-evict-one");
        int totalServed = 0;

        Expectation first = nodeAMatchers.firstMatchingExpectation(request);
        nodeAMatchers.postProcess(first);
        if (first != null) {
            totalServed++;
        }
        assertThat("counter live before eviction", nodeA.sharedTimesCounters().get(id).get().getValue(), is(N - 1));

        // Evict on node A ONLY.
        nodeA.getCacheManager().getCache(InfinispanStateBackend.SHARED_TIMES_CACHE).evict(id);

        // Deterministic node-local semantics: A lost its replica, B still holds
        // the true live value. (evict() is documented as node-local, not cluster-wide.)
        assertThat("node A lost its replica", nodeA.sharedTimesCounters().get(id).isPresent(), is(false));
        assertThat("node B (peer) retains the live value — evict is node-local",
            nodeB.sharedTimesCounters().get(id).get().getValue(), is(N - 1));

        // Hammer both nodes; the fix never over-serves regardless of primary ownership.
        for (int i = 0; i < N * 2; i++) {
            RequestMatchers matchers = (i % 2 == 0) ? nodeAMatchers : nodeBMatchers;
            Expectation matched = matchers.firstMatchingExpectation(request);
            matchers.postProcess(matched);
            if (matched != null) {
                totalServed++;
            }
        }
        assertThat("Times.exactly(" + N + ") must NEVER serve more than N fleet-wide, got " + totalServed,
            totalServed, lessThanOrEqualTo(N));
    }

    /**
     * Opt-out: with {@code clusterSharedTimesEnabled=false}, limited-Times
     * matching takes the NODE-LOCAL fast path even on a clustered backend —
     * the synchronous shared-counter CAS (a replicated write on the request
     * worker thread) is bypassed entirely. We prove the bypass by asserting
     * the shared backend {@code remainingTimes} counter is NEVER decremented
     * by matching: node A serves the match via its node-local {@code Times},
     * while the replicated backend entry still reads its seeded value.
     * <p>
     * (The default shared path DOES decrement the backend counter — asserted
     * by {@link #backendRemainingTimesShouldReachZeroWhenExhausted()} and by
     * the fleet-wide exactly-N guarantee in
     * {@link #timesExactlyNShouldBeServedExactlyNTimesAcrossTwoNodes()}.)
     * <p>
     * The trade-off — that node-local Times makes fleet-wide N approximate —
     * is documented in docs/code/clustered-state.md; we deliberately assert
     * the verifiable "no shared CAS" invariant rather than a brittle total
     * match count (the first node to exhaust removes the shared entry, which
     * replicates, so the exact cross-node total is order-dependent).
     */
    @Test
    void sharedTimesCasBypassedWhenSharedTimesDisabled() throws Exception {
        final int N = 3;
        // Node A opts OUT of cluster-wide shared-Times CAS.
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA, false);

        Expectation expectation = Expectation.when(
            HttpRequest.request("/times-no-cas"),
            Times.exactly(N),
            org.mockserver.matchers.TimeToLive.unlimited()
        ).thenRespond(HttpResponse.response("no-cas"));
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);

        // Backend entry seeded with the full allotment, replicated to node B.
        awaitCondition(
            () -> nodeB.expectations().get(expectation.getId())
                .map(v -> v.getValue().getRemainingTimes() == N).orElse(false),
            Duration.ofSeconds(5),
            "node B should see remainingTimes=" + N
        );

        HttpRequest request = HttpRequest.request("/times-no-cas");

        // Serve a match on node A — node-local Times decrements, NOT the
        // shared backend counter (no CAS round-trip).
        Expectation matched = nodeAMatchers.firstMatchingExpectation(request);
        nodeAMatchers.postProcess(matched);
        assertThat("node A should serve via node-local Times", matched, is(notNullValue()));
        assertThat("node A node-local Times decremented (3 -> 2)",
            matched.getTimes().getRemainingTimes(), is(N - 1));

        // The shared backend counter is UNTOUCHED on both nodes — the
        // replicated CAS write on the worker thread was bypassed.
        assertThat("shared backend counter not decremented on node A",
            nodeA.expectations().get(expectation.getId()).get().getValue().getRemainingTimes(),
            is(N));
        assertThat("shared backend counter not decremented on node B (replica)",
            nodeB.expectations().get(expectation.getId()).get().getValue().getRemainingTimes(),
            is(N));
    }

    /**
     * G10: unlimited-Times expectations with a clustered backend should
     * still work without CAS overhead. Both nodes should be able to match
     * indefinitely.
     */
    @Test
    void unlimitedTimesShouldNotBeLimitedUnderClustering() throws Exception {
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA);
        RequestMatchers nodeBMatchers = createNodeMatchers(nodeB);
        wireReconciliationListener(nodeA, nodeAMatchers);
        wireReconciliationListener(nodeB, nodeBMatchers);

        Expectation expectation = Expectation.when(
            HttpRequest.request("/unlimited")
        ).thenRespond(HttpResponse.response("unlimited"));
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);

        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/unlimited")) != null,
            Duration.ofSeconds(5),
            "node B should see the unlimited expectation"
        );

        HttpRequest request = HttpRequest.request("/unlimited");
        // Match 20 times across both nodes — all should succeed
        int matches = 0;
        for (int i = 0; i < 20; i++) {
            RequestMatchers matchers = (i % 2 == 0) ? nodeAMatchers : nodeBMatchers;
            Expectation matched = matchers.firstMatchingExpectation(request);
            matchers.postProcess(matched);
            if (matched != null) {
                matches++;
            }
        }
        assertThat("unlimited Times should produce matches on every attempt",
            matches, is(20));
    }

    /**
     * G10 backend CAS: after Times are exhausted, the dedicated shared-times
     * COUNTER reaches 0. The counter is decremented across the wire as a bare
     * Integer, not by rewriting the ExpectationEntry — so the entry's own
     * remainingTimes stays at the immutable seed (N) while the counter drops
     * to 0. (Uses a mock Scheduler, so the exhaustion-triggered lazy removal
     * never runs and both the entry and the counter remain observable.)
     */
    @Test
    void backendRemainingTimesShouldReachZeroWhenExhausted() throws Exception {
        final int N = 2;
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA);
        wireReconciliationListener(nodeA, nodeAMatchers);

        Expectation expectation = Expectation.when(
            HttpRequest.request("/backend-counter"),
            Times.exactly(N),
            org.mockserver.matchers.TimeToLive.unlimited()
        ).thenRespond(HttpResponse.response("counter"));
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);

        HttpRequest request = HttpRequest.request("/backend-counter");
        for (int i = 0; i < N + 2; i++) {
            Expectation matched = nodeAMatchers.firstMatchingExpectation(request);
            nodeAMatchers.postProcess(matched);
        }

        // The dedicated shared-times counter reaches 0 after exhaustion...
        Optional<Versioned<Integer>> counter = nodeA.sharedTimesCounters().get(expectation.getId());
        assertTrue(counter.isPresent(), "shared-times counter should still exist");
        assertThat("shared-times counter should be 0 after exhaustion",
            counter.get().getValue(), is(0));

        // ...while the ExpectationEntry seed is left untouched by the decrement.
        Optional<Versioned<ExpectationEntry>> entry = nodeA.expectations().get(expectation.getId());
        assertTrue(entry.isPresent(), "backend entry should still exist");
        assertThat("ExpectationEntry remainingTimes stays at the immutable seed",
            entry.get().getValue().getRemainingTimes(), is(N));
    }

    // --- Cross-node ScenarioManager transitions (G10 follow-up) ---

    /**
     * G10 scenario clustering: a ScenarioManager wired to node A's
     * replicated scenarioStates() KV store transitions a scenario from
     * "Started" to "Step1". A ScenarioManager on node B, wired to node
     * B's view of the same replicated store, must see the new state.
     * This proves cross-node scenario visibility through the refactored
     * ScenarioManager (read-through-KV, no node-local cache).
     */
    @Test
    void scenarioTransitionOnNodeAShouldBeVisibleOnNodeB() {
        ScenarioManager managerA = new ScenarioManager(nodeA.scenarioStates());
        ScenarioManager managerB = new ScenarioManager(nodeB.scenarioStates());

        // Initial state on both nodes
        assertThat(managerA.getState("myScenario"), is(ScenarioManager.STARTED));
        assertThat(managerB.getState("myScenario"), is(ScenarioManager.STARTED));

        // Transition on node A
        boolean matched = managerA.matchesAndTransition("myScenario", "Started", "Step1");
        assertTrue(matched, "transition from Started to Step1 should succeed on node A");

        // Visible on node B (REPL_SYNC = synchronous replication)
        assertThat(managerB.getState("myScenario"), is("Step1"));
    }

    /**
     * G10 scenario clustering: a multi-step scenario sequence
     * (Started -> Step1 -> Step2 -> Done) driven alternately by two
     * nodes must produce the correct final state visible on both nodes.
     */
    @Test
    void multiStepScenarioTransitionAcrossNodesShouldWork() {
        ScenarioManager managerA = new ScenarioManager(nodeA.scenarioStates());
        ScenarioManager managerB = new ScenarioManager(nodeB.scenarioStates());

        // Node A: Started -> Step1
        assertTrue(managerA.matchesAndTransition("multiStep", "Started", "Step1"));
        assertThat(managerB.getState("multiStep"), is("Step1"));

        // Node B: Step1 -> Step2
        assertTrue(managerB.matchesAndTransition("multiStep", "Step1", "Step2"));
        assertThat(managerA.getState("multiStep"), is("Step2"));

        // Node A: Step2 -> Done
        assertTrue(managerA.matchesAndTransition("multiStep", "Step2", "Done"));
        assertThat(managerB.getState("multiStep"), is("Done"));
    }

    /**
     * G10 core guarantee: two nodes racing to transition the same
     * scenario from "Started" to "Step1" — exactly ONE should succeed
     * (the CAS-based matchesAndTransition ensures atomicity across nodes).
     * This prevents double-transition of a once-style scenario.
     */
    @Test
    void twoNodesCannotDoubleTransitionSameScenario() throws Exception {
        ScenarioManager managerA = new ScenarioManager(nodeA.scenarioStates());
        ScenarioManager managerB = new ScenarioManager(nodeB.scenarioStates());

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread threadA = new Thread(() -> {
            try {
                barrier.await();
                if (managerA.matchesAndTransition("onceScenario", "Started", "Step1")) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                error.set(e);
            }
        });
        Thread threadB = new Thread(() -> {
            try {
                barrier.await();
                if (managerB.matchesAndTransition("onceScenario", "Started", "Step1")) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                error.set(e);
            }
        });

        threadA.start();
        threadB.start();
        threadA.join(10_000);
        threadB.join(10_000);

        assertNull(error.get(), "no exceptions expected");
        assertThat("exactly one node should win the Started->Step1 transition",
            successCount.get(), is(1));

        // Both nodes should see the same final state
        assertThat(managerA.getState("onceScenario"), is("Step1"));
        assertThat(managerB.getState("onceScenario"), is("Step1"));
    }

    /**
     * Cross-node serving guard (review-final): two nodes racing the SAME
     * Started-&gt;Step1 scenario step through the production serving path
     * ({@link RequestMatchers#firstMatchingExpectation}) must serve the
     * response from EXACTLY ONE node. The loser's commit-point CAS
     * (matchesAndTransition) fails and it falls through without serving.
     * <p>
     * This closes the gap that {@link #twoNodesCannotDoubleTransitionSameScenario}
     * left open: that test exercises {@link ScenarioManager} directly, not the
     * production serving path where an unconditional put would have allowed
     * both nodes to serve the same step.
     */
    @Test
    void scenarioStepShouldBeServedExactlyOnceAcrossTwoNodes() throws Exception {
        RequestMatchers nodeAMatchers = createNodeMatchers(nodeA);
        RequestMatchers nodeBMatchers = createNodeMatchers(nodeB);
        wireReconciliationListener(nodeA, nodeAMatchers);
        wireReconciliationListener(nodeB, nodeBMatchers);

        // setStateBackend (in createNodeMatchers) already wired each node's
        // internal ScenarioManager to that node's view of the SAME replicated
        // scenarioStates() store, so the commit-point CAS races cross-node
        // exactly as in production.
        ScenarioManager managerA = nodeAMatchers.getScenarioManager();
        ScenarioManager managerB = nodeBMatchers.getScenarioManager();

        // A single Started->Step1 scenario expectation (unlimited Times) so
        // the ONLY thing gating the second serve is the scenario CAS.
        Expectation expectation = Expectation.when(
            HttpRequest.request("/scenario-step")
        ).thenRespond(HttpResponse.response("step1-served"))
            .withScenarioName("raceScenario")
            .withScenarioState(ScenarioManager.STARTED)
            .withNewScenarioState("Step1");
        nodeAMatchers.add(expectation, RequestMatchers.Cause.API);

        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/scenario-step")) != null,
            Duration.ofSeconds(5),
            "node B should see the scenario expectation"
        );

        HttpRequest request = HttpRequest.request("/scenario-step");
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Expectation> resultA = new AtomicReference<>();
        AtomicReference<Expectation> resultB = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread threadA = new Thread(() -> {
            try {
                barrier.await();
                Expectation matched = nodeAMatchers.firstMatchingExpectation(request);
                resultA.set(matched);
                nodeAMatchers.postProcess(matched);
            } catch (Exception e) {
                error.set(e);
            }
        });
        Thread threadB = new Thread(() -> {
            try {
                barrier.await();
                Expectation matched = nodeBMatchers.firstMatchingExpectation(request);
                resultB.set(matched);
                nodeBMatchers.postProcess(matched);
            } catch (Exception e) {
                error.set(e);
            }
        });

        threadA.start();
        threadB.start();
        threadA.join(10_000);
        threadB.join(10_000);

        assertNull(error.get(), "no exceptions expected");

        // Exactly one node should have served the step; the loser's
        // commit-point CAS failed and it returned null.
        int totalServed = (resultA.get() != null ? 1 : 0) + (resultB.get() != null ? 1 : 0);
        assertThat("Started->Step1 scenario step should be served by exactly 1 node, got " + totalServed,
            totalServed, is(1));

        // The scenario advanced exactly once, visible on both nodes.
        assertThat(managerA.getState("raceScenario"), is("Step1"));
        assertThat(managerB.getState("raceScenario"), is("Step1"));
    }

    /**
     * G10: scenario clear on node A should be visible on node B.
     */
    @Test
    void scenarioClearOnNodeAShouldBeVisibleOnNodeB() {
        ScenarioManager managerA = new ScenarioManager(nodeA.scenarioStates());
        ScenarioManager managerB = new ScenarioManager(nodeB.scenarioStates());

        managerA.setState("clearMe", "Step1");
        assertThat(managerB.getState("clearMe"), is("Step1"));

        managerA.clear("clearMe");

        // After clear, the state reverts to the implicit STARTED default
        assertThat(managerB.getState("clearMe"), is(ScenarioManager.STARTED));
    }

    /**
     * G10: scenario reset on node A should clear all states on node B.
     */
    @Test
    void scenarioResetOnNodeAShouldClearAllStatesOnNodeB() {
        ScenarioManager managerA = new ScenarioManager(nodeA.scenarioStates());
        ScenarioManager managerB = new ScenarioManager(nodeB.scenarioStates());

        managerA.setState("s1", "Step1");
        managerA.setState("s2", "Step2");
        assertThat(managerB.getState("s1"), is("Step1"));
        assertThat(managerB.getState("s2"), is("Step2"));

        managerA.reset();

        assertThat(managerB.getState("s1"), is(ScenarioManager.STARTED));
        assertThat(managerB.getState("s2"), is(ScenarioManager.STARTED));
    }

    /**
     * G10: composite-key (isolated) scenario transitions should also
     * replicate across nodes.
     */
    @Test
    void isolatedScenarioTransitionShouldReplicateAcrossNodes() {
        ScenarioManager managerA = new ScenarioManager(nodeA.scenarioStates());
        ScenarioManager managerB = new ScenarioManager(nodeB.scenarioStates());

        // Node A transitions isolation "session-1"
        assertTrue(managerA.matchesAndTransition("conv", "session-1", "Started", "turn_1"));
        // Node B sees the transition for "session-1"
        assertThat(managerB.getState("conv", "session-1"), is("turn_1"));

        // Node B transitions isolation "session-2" independently
        assertTrue(managerB.matchesAndTransition("conv", "session-2", "Started", "turn_1"));
        // Node A sees it
        assertThat(managerA.getState("conv", "session-2"), is("turn_1"));

        // Original isolation unchanged
        assertThat(managerA.getState("conv", "session-1"), is("turn_1"));
    }

    // --- Helpers for clustered Times tests ---

    /**
     * Creates a {@link RequestMatchers} wired to the given backend node,
     * with cluster-wide shared-Times CAS enforcement enabled (the default).
     */
    private RequestMatchers createNodeMatchers(InfinispanStateBackend backend) {
        return createNodeMatchers(backend, true);
    }

    /**
     * Creates a {@link RequestMatchers} wired to the given backend node,
     * with shared-Times CAS enforcement explicitly enabled or disabled.
     *
     * @param sharedTimesEnabled {@code true} for fleet-wide exactly-N via
     *                           backend CAS; {@code false} for node-local
     *                           Times fallback (no backend round-trip)
     */
    private RequestMatchers createNodeMatchers(InfinispanStateBackend backend, boolean sharedTimesEnabled) {
        Configuration config = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS)
            .clusterSharedTimesEnabled(sharedTimesEnabled);
        RequestMatchers matchers = new RequestMatchers(
            config, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        matchers.setStateBackend(backend);
        return matchers;
    }

    /**
     * Wires the backend's invalidation listener to trigger reconciliation
     * on the given RequestMatchers, mirroring HttpState's wiring.
     */
    private void wireReconciliationListener(InfinispanStateBackend backend, RequestMatchers matchers) {
        backend.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                matchers.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                matchers.reconcileFromBackend();
            }
        });
    }

    // --- Helpers ---

    /**
     * Polls the JGroups cluster view size until it reaches the expected
     * count, or throws if the timeout is exceeded. Deterministic — no
     * fixed sleep, only a brief poll interval.
     */
    private static void awaitClusterSize(InfinispanStateBackend backend, int expectedSize, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            int currentSize = backend.getCacheManager()
                .getTransport()
                .getMembers()
                .size();
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
        int finalSize = backend.getCacheManager().getTransport().getMembers().size();
        fail("cluster did not reach size " + expectedSize + " within " + timeout
            + "; current size=" + finalSize);
    }

    /**
     * Polls a condition with a short interval until it becomes true or
     * the timeout expires. For awaiting asynchronous invalidation events.
     */
    private static void awaitCondition(java.util.function.BooleanSupplier condition,
                                       Duration timeout, String message) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted: " + message, e);
            }
        }
        fail(message + " (timed out after " + timeout + ")");
    }
}
