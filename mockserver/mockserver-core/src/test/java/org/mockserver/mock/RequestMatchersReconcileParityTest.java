package org.mockserver.mock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ExpectationId;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.InMemoryStateBackend;
import org.mockserver.state.KeyValueStore;

import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Backend-mode parity tests for {@link RequestMatchers}'s post-mutation
 * reconcile, run against BOTH the non-clustered and the clustered state
 * backend so the two eviction-reconcile siblings must agree.
 * <p>
 * <b>Why this class exists (issue #2579 blind-spot follow-up).</b>
 * {@link RequestMatchers#reconcileFromBackend()} dispatches to one of two
 * implementations depending on {@link org.mockserver.state.StateBackend#isClustered()}:
 * <ul>
 *   <li>the non-clustered fast path, {@code trimEvictedFromBackend()}; and</li>
 *   <li>the clustered scan, {@code reconcileClusteredScan()}.</li>
 * </ul>
 * The behavioural guarantees these paths share — a local {@code add}/{@code update}/
 * {@code remove} keeps the backend KV in sync with the node-local sorted view, and a
 * {@code maxExpectations} overflow evicts the oldest entry by insertion order (with an
 * update NOT moving an entry to the tail) — were, before this class, asserted ONLY
 * against the non-clustered path (in {@code RequestMatchersStateBackendTest}). The
 * clustered sibling had these behaviours exercised nowhere, which is precisely how the
 * #2579 defect — wrong snapshot ordering evicting a live expectation — reached review
 * twice on the clustered branch while the non-clustered branch stayed green.
 * <p>
 * Parameterising ONE set of behavioural assertions over both backends (rather than
 * writing a parallel clustered-only test that can drift out of sync with its
 * non-clustered twin) is the deliberate anti-drift guard: any future change that makes
 * {@code reconcileClusteredScan} diverge from {@code trimEvictedFromBackend} on these
 * shared guarantees fails the {@code clustered=true} row of this class while the
 * {@code clustered=false} row keeps passing.
 * <p>
 * The clustered backend here is an in-memory {@link InMemoryStateBackend} whose
 * {@code isClustered()} is overridden to {@code true} (the same seam the existing
 * clustered unit tests use). That is enough to route mutations through
 * {@code reconcileClusteredScan}; the full cross-node replication behaviour is covered
 * separately by the {@code mockserver-state-infinispan} {@code ClusteredTwoNode*} suites.
 */
@RunWith(Parameterized.class)
public class RequestMatchersReconcileParityTest {

    private static final int MAX_EXPECTATIONS = 2;

    @Parameterized.Parameters(name = "clustered={0}")
    public static Object[] data() {
        return new Object[]{Boolean.FALSE, Boolean.TRUE};
    }

    private final boolean clustered;
    private final Configuration configuration;
    private final InMemoryStateBackend stateBackend;
    private final RequestMatchers matchers;

    public RequestMatchersReconcileParityTest(boolean clustered) {
        this.clustered = clustered;
        this.configuration = configuration().maxExpectations(MAX_EXPECTATIONS);
        this.stateBackend = newBackend(MAX_EXPECTATIONS, clustered);
        this.matchers = new RequestMatchers(
            configuration, new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        this.matchers.setStateBackend(stateBackend);
    }

    private static InMemoryStateBackend newBackend(int maxExpectations, boolean clustered) {
        if (!clustered) {
            return new InMemoryStateBackend(maxExpectations);
        }
        // isClustered()==true routes reconcileFromBackend() through reconcileClusteredScan().
        return new InMemoryStateBackend(maxExpectations) {
            @Override
            public boolean isClustered() {
                return true;
            }
        };
    }

    private List<String> activeIds() {
        return matchers.retrieveActiveExpectations(null).stream()
            .map(Expectation::getId).collect(Collectors.toList());
    }

    private List<String> backendIds() {
        return stateBackend.expectations().entries()
            .map(KeyValueStore.Entry::getKey).collect(Collectors.toList());
    }

    // -------------------------------------------------------
    // add/update/remove keep the backend KV in sync — both paths
    // -------------------------------------------------------

    @Test
    public void addKeepsBackendInSync() {
        matchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withStatusCode(200)), API);

        assertThat("node-local size", matchers.size(), is(1));
        assertThat("matches locally", matchers.firstMatchingExpectation(request().withPath("/a")), is(notNullValue()));
        assertThat("backend size", stateBackend.expectations().size(), is(1));
        assertThat("backend holds a", stateBackend.expectations().get("a").isPresent(), is(true));
    }

    @Test
    public void updateKeepsBackendInSync() {
        matchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("v1")), API);
        matchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("v2")), API);

        assertThat("update does not add a second matcher", matchers.size(), is(1));
        assertThat("update does not add a second backend entry", stateBackend.expectations().size(), is(1));
        Expectation matched = matchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matched, is(notNullValue()));
        assertThat("matching serves the updated body", matched.getHttpResponse().getBodyAsString(), is("v2"));
    }

    @Test
    public void removeKeepsBackendInSync() {
        matchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withStatusCode(200)), API);
        matchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withStatusCode(201)), API);

        matchers.clear(ExpectationId.expectationId("a"), "test-correlation");

        assertThat("node-local size after remove", matchers.size(), is(1));
        assertThat("backend size after remove", stateBackend.expectations().size(), is(1));
        assertThat("a removed from backend", stateBackend.expectations().get("a").isPresent(), is(false));
        assertThat("b retained in backend", stateBackend.expectations().get("b").isPresent(), is(true));
    }

    @Test
    public void sortedViewMatchesInsertionOrder() {
        matchers.add(new Expectation(request().withPath("/first")).withId("first")
            .thenRespond(response().withBody("1")), API);
        matchers.add(new Expectation(request().withPath("/second")).withId("second")
            .thenRespond(response().withBody("2")), API);

        assertThat(activeIds(), contains("first", "second"));
    }

    // -------------------------------------------------------
    // maxExpectations overflow eviction — both paths
    // -------------------------------------------------------

    @Test
    public void evictionRemovesOldestByInsertionOrder() {
        // maxExpectations=2: add A, add B, add C -> A (oldest) is evicted.
        matchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        matchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);
        matchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        assertThat("node-local trimmed to max", matchers.size(), is(2));
        assertThat("backend trimmed to max", stateBackend.expectations().size(), is(2));
        assertThat("oldest evicted from backend", stateBackend.expectations().get("a").isPresent(), is(false));
        assertThat("survivors are b and c", activeIds(), containsInAnyOrder("b", "c"));
        assertThat("node-local no longer holds the evicted id", activeIds(), not(hasItem("a")));
        assertThat("backend no longer holds the evicted id", backendIds(), not(hasItem("a")));
    }

    @Test
    public void updateDoesNotChangeEvictionOrder() {
        // add A (pos 0), add B (pos 1), update A (must stay at pos 0), add C -> A is evicted, NOT B.
        matchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v1")), API);
        matchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);
        matchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v2")), API);
        matchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        assertThat("node-local trimmed to max", matchers.size(), is(2));
        assertThat("backend trimmed to max", stateBackend.expectations().size(), is(2));
        assertThat("update did not move A to the tail — A is still the eviction victim",
            activeIds(), containsInAnyOrder("b", "c"));
        assertThat("A evicted", activeIds(), not(hasItem("a")));
        assertThat("B retained (was NOT the oldest)", stateBackend.expectations().get("b").isPresent(), is(true));
        assertThat("A evicted from backend", stateBackend.expectations().get("a").isPresent(), is(false));
    }

    @Test
    public void evictionCleansUpExpectationRequestDefinitions() {
        matchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        matchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        assertThat("A tracked before eviction",
            matchers.expectationRequestDefinitions.containsKey("a"), is(true));

        matchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        assertThat("evicted A's request-definition entry is cleaned up",
            matchers.expectationRequestDefinitions.containsKey("a"), is(false));
        assertThat("B still tracked", matchers.expectationRequestDefinitions.containsKey("b"), is(true));
        assertThat("C tracked", matchers.expectationRequestDefinitions.containsKey("c"), is(true));
    }
}
