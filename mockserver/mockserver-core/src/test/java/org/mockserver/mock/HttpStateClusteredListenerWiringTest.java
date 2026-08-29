package org.mockserver.mock;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.ServiceChaosRegistry;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.InMemoryStateBackend;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.mockserver.state.StateBackendFactory;
import org.mockserver.state.Versioned;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Verifies that the {@link HttpState} constructor wires the clustered-only invalidation
 * listeners that keep node-local <b>chaos-registry</b> and <b>cross-protocol event bus</b>
 * state converged with the shared backend on a remote write — and, symmetrically, that a
 * non-clustered backend registers <b>only</b> the unconditional expectations reconcile listener.
 * <p>
 * <b>Why this class exists (issue #2579 blind-spot family).</b> {@code HttpState} contains two
 * {@code if (stateBackend.isClustered()) stateBackend.addInvalidationListener(...)} blocks — one
 * that reconciles the chaos registries, one that reconciles the {@link CrossProtocolEventBus}.
 * Before this class, <b>no test constructed {@code HttpState} with a clustered backend</b>: the
 * {@code mockserver-state-infinispan} {@code ClusteredTwoNode*} suites hand-wire their own
 * {@link InvalidationListener}s directly against a backend and never build an {@code HttpState},
 * so deleting either {@code addInvalidationListener} call from the constructor left every suite
 * green while, in production, a clustered node would silently stop rebuilding its chaos /
 * cross-protocol-bus state on remote writes — exactly the "clustered branch nothing exercises,
 * failing silently" failure family.
 * <p>
 * <b>Approach.</b> A spy {@link StateBackend} (subclass of {@link InMemoryStateBackend}) whose
 * {@code isClustered()} is parameterised, that records every {@link InvalidationListener}
 * registered on it and wraps each CRUD-entity store so a read is observable. The test asserts:
 * <ul>
 *   <li><b>registration count</b> — a non-clustered backend gets exactly the one unconditional
 *       expectations listener; a clustered backend gets three (adds the chaos + bus listeners);</li>
 *   <li><b>behavioural wiring</b> — firing the registered listeners drives a reconcile read of the
 *       chaos-registry store <i>and</i> the cross-protocol-bus store when clustered, and of neither
 *       when not clustered.</li>
 * </ul>
 * The spy backend is injected through the production {@link StateBackendFactory#register(StateBackendFactory.Factory)}
 * seam (the same seam the Infinispan module uses), so the real {@code HttpState} constructor path
 * is exercised end to end.
 * <p>
 * <b>Global-state note.</b> Constructing a real {@code HttpState} mutates the process-wide
 * {@link ServiceChaosRegistry}/{@link TcpChaosRegistry}/{@link GrpcChaosRegistry} and
 * {@link CrossProtocolEventBus} singletons, {@code Metrics} suppliers, and the
 * {@link StateBackendFactory} registry, so this class runs in the {@code mockserver-core}
 * <b>sequential</b> Surefire phase (parallel-excludes + sequential-includes in the pom) — mirroring
 * {@code HttpStateReadinessTest}, which constructs {@code HttpState} for the same reason.
 */
@RunWith(Parameterized.class)
public class HttpStateClusteredListenerWiringTest {

    private static final String CHAOS_SERVICE_NAMESPACE = "chaos-service";
    private static final String CROSS_PROTOCOL_BUS_NAMESPACE = "cross-protocol-bus";

    @Parameterized.Parameters(name = "clustered={0}")
    public static Object[] data() {
        return new Object[]{Boolean.FALSE, Boolean.TRUE};
    }

    private final boolean clustered;

    public HttpStateClusteredListenerWiringTest(boolean clustered) {
        this.clustered = clustered;
    }

    @After
    public void tearDown() {
        // Constructing HttpState wires our spy backend into these process-wide singletons; reset them
        // (and the factory) so no later sequential test observes this test's spy store.
        StateBackendFactory.resetToDefault();
        ServiceChaosRegistry.getInstance().reset();
        TcpChaosRegistry.getInstance().reset();
        GrpcChaosRegistry.getInstance().reset();
        CrossProtocolEventBus.getInstance().reset();
    }

    private RecordingStateBackend constructHttpStateWithSpyBackend() {
        RecordingStateBackend backend = new RecordingStateBackend(clustered);
        StateBackendFactory.register(configuration -> backend);
        Configuration configuration = configuration();
        MockServerLogger mockServerLogger = new MockServerLogger(configuration, HttpStateClusteredListenerWiringTest.class);
        // Construct the real HttpState — this is the code under test (its constructor registers the listeners).
        new HttpState(configuration, mockServerLogger, mock(Scheduler.class));
        return backend;
    }

    // -------------------------------------------------------
    // Registration: the clustered-only listeners are added only when clustered
    // -------------------------------------------------------

    @Test
    public void registersChaosAndBusListenersOnlyWhenClustered() {
        RecordingStateBackend backend = constructHttpStateWithSpyBackend();

        // Unconditional: the expectations reconcile listener is always registered (1).
        // Clustered adds two more: the chaos-registry reconcile listener and the cross-protocol-bus
        // reconcile listener (3). Deleting EITHER clustered addInvalidationListener block drops the
        // clustered count to 2 and fails this assertion.
        int expected = clustered ? 3 : 1;
        assertThat("HttpState registered the wrong number of invalidation listeners for clustered=" + clustered,
            backend.registeredListeners.size(), is(expected));
    }

    // -------------------------------------------------------
    // Behaviour: firing the registered listeners reconciles chaos + bus only when clustered
    // -------------------------------------------------------

    @Test
    public void firedListenersReconcileChaosAndBusOnlyWhenClustered() {
        RecordingStateBackend backend = constructHttpStateWithSpyBackend();

        // Fire every registered listener (both notification branches) and observe which backend
        // CRUD-entity stores get read as a result of the reconcile calls they drive.
        for (InvalidationListener listener : backend.registeredListeners) {
            listener.onChanged("some-key");
            listener.onCleared();
        }

        if (clustered) {
            assertThat("clustered: firing the registered listeners must reconcile the chaos registry "
                    + "(chaos-service store read) — proves the chaos addInvalidationListener block is wired",
                backend.readNamespaces, hasItem(CHAOS_SERVICE_NAMESPACE));
            assertThat("clustered: firing the registered listeners must reconcile the cross-protocol bus "
                    + "(cross-protocol-bus store read) — proves the bus addInvalidationListener block is wired",
                backend.readNamespaces, hasItem(CROSS_PROTOCOL_BUS_NAMESPACE));
        } else {
            assertThat("non-clustered: no chaos reconcile listener is registered, so no chaos-service read",
                backend.readNamespaces, not(hasItem(CHAOS_SERVICE_NAMESPACE)));
            assertThat("non-clustered: no cross-protocol-bus reconcile listener is registered, so no bus read",
                backend.readNamespaces, not(hasItem(CROSS_PROTOCOL_BUS_NAMESPACE)));
        }
    }

    // -------------------------------------------------------
    // Spy backend
    // -------------------------------------------------------

    /**
     * In-memory backend that (a) reports a parameterised {@code isClustered()},
     * (b) records every {@link InvalidationListener} registered on it, and
     * (c) wraps each CRUD-entity store so a reconcile read of it is observable
     * via {@link #readNamespaces}.
     */
    private static final class RecordingStateBackend extends InMemoryStateBackend {

        private final boolean clustered;
        private final List<InvalidationListener> registeredListeners = new CopyOnWriteArrayList<>();
        private final Set<String> readNamespaces = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<String, KeyValueStore<ObjectNode>> spyStores = new ConcurrentHashMap<>();

        private RecordingStateBackend(boolean clustered) {
            super(1000);
            this.clustered = clustered;
        }

        @Override
        public boolean isClustered() {
            return clustered;
        }

        @Override
        public void addInvalidationListener(InvalidationListener listener) {
            registeredListeners.add(listener);
            super.addInvalidationListener(listener);
        }

        @Override
        public KeyValueStore<ObjectNode> crudEntities(String namespace) {
            // Memoise the wrapper so the store instance captured by a registry at setStateBackend()
            // time is the same instance whose entries() we observe when a listener later fires.
            return spyStores.computeIfAbsent(namespace,
                ns -> new ReadRecordingStore(ns, super.crudEntities(ns), readNamespaces));
        }
    }

    /**
     * Delegating {@link KeyValueStore} that records the namespace into a shared set whenever
     * {@link #entries()} is read — the read performed by every {@code reconcileFromBackend()}.
     */
    private static final class ReadRecordingStore implements KeyValueStore<ObjectNode> {

        private final String namespace;
        private final KeyValueStore<ObjectNode> delegate;
        private final Set<String> readNamespaces;

        private ReadRecordingStore(String namespace, KeyValueStore<ObjectNode> delegate, Set<String> readNamespaces) {
            this.namespace = namespace;
            this.delegate = delegate;
            this.readNamespaces = readNamespaces;
        }

        @Override
        public Stream<Entry<ObjectNode>> entries() {
            readNamespaces.add(namespace);
            return delegate.entries();
        }

        @Override
        public Optional<Versioned<ObjectNode>> get(String key) {
            return delegate.get(key);
        }

        @Override
        public long put(String key, ObjectNode value) {
            return delegate.put(key, value);
        }

        @Override
        public Optional<Versioned<ObjectNode>> putIfAbsent(String key, ObjectNode value) {
            return delegate.putIfAbsent(key, value);
        }

        @Override
        public boolean compareAndSet(String key, long expectedVersion, ObjectNode value) {
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
