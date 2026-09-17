package org.mockserver.state.infinispan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.mockserver.configuration.Configuration;
import org.mockserver.state.*;
import org.mockserver.uuid.UUIDService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Infinispan-backed {@link StateBackend} supporting both LOCAL (non-clustered)
 * and CLUSTERED (JGroups transport, REPL_SYNC) modes.
 * <p>
 * <b>LOCAL mode</b> (default, {@code clusterEnabled=false}): single-node,
 * no JGroups network transport, identical behaviour to Phase 2b. The Java
 * serialization allow-list remains permissive ({@code ".*"}) because caches
 * are heap-only with no network marshalling exposure.
 * <p>
 * <b>CLUSTERED mode</b> ({@code clusterEnabled=true}): starts a JGroups
 * transport for multi-node state replication. Caches use REPL_SYNC so all
 * writes are synchronously replicated to all cluster members. The Java
 * serialization allow-list is <b>tightened</b> to an explicit set of
 * packages covering exactly the types that cross the wire:
 * <ul>
 *   <li>{@code org.mockserver.state.infinispan.*} (VersionedWrapper)</li>
 *   <li>{@code org.mockserver.state.*} (ExpectationEntry)</li>
 *   <li>{@code org.mockserver.mock.*} (Expectation, domain model)</li>
 *   <li>{@code org.mockserver.model.*} (HttpRequest, HttpResponse, etc.)</li>
 *   <li>{@code org.mockserver.matchers.*} (TimeToLive, Times)</li>
 *   <li>{@code com.fasterxml.jackson.*} (ObjectNode for CRUD entities)</li>
 *   <li>{@code java.lang.*}, {@code java.util.*}, {@code java.time.*}
 *       (primitives/wrappers, collections, time types)</li>
 * </ul>
 * This explicit allow-list resolves the P0 security gate from Phase 2b —
 * deserialization gadget chains from untrusted packages are blocked.
 * <p>
 * Cluster invalidation: in CLUSTERED mode, Infinispan {@code @Listener}
 * annotated listeners are attached to each cache. When a remote write
 * arrives (replication from another node), the listener fires
 * {@link InvalidationListener#onChanged(String)} or
 * {@link InvalidationListener#onCleared()}, which triggers the node-local
 * view rebuild in {@code RequestMatchers.reconcileFromBackend()}.
 */
public class InfinispanStateBackend implements StateBackend {

    private static final Logger LOG = LoggerFactory.getLogger(InfinispanStateBackend.class);

    static final String EXPECTATIONS_CACHE = "expectations";
    static final String SCENARIO_STATES_CACHE = "scenarioStates";
    static final String SHARED_TIMES_CACHE = "sharedTimesCounters";
    static final String BLOBS_CACHE = "blobs";
    static final String CRUD_CACHE_PREFIX = "crud-";

    /**
     * P0 SECURITY GATE: the explicit Java-deserialization allow-list applied to the clustered
     * marshaller — exactly the types Infinispan needs to serialize for clustered replication.
     * NO wildcard. Each pattern covers a specific MockServer package or a JDK/library type used
     * as a field within the serialized objects. Exposed as a constant so the security test
     * ({@code DeserializationAllowListTest}) asserts the SAME list the production marshaller uses
     * and cannot silently drift from it.
     */
    static final List<String> CLUSTERED_ALLOW_LIST_PATTERNS = List.of(
        "org\\.mockserver\\.state\\.infinispan\\..*",   // VersionedWrapper
        "org\\.mockserver\\.state\\..*",                // ExpectationEntry, Blob
        "org\\.mockserver\\.mock\\..*",                 // Expectation
        "org\\.mockserver\\.model\\..*",                // HttpRequest, HttpResponse, etc.
        "org\\.mockserver\\.matchers\\..*",             // TimeToLive, Times
        "com\\.fasterxml\\.jackson\\..*",               // ObjectNode (CRUD entities)
        "java\\.lang\\..*",                             // String, Long, etc.
        "java\\.util\\..*",                             // Collections, Map entries
        "java\\.time\\..*",                             // Instant, Duration, etc.
        "\\[B"                                          // byte[] for Blob data
    );

    private final EmbeddedCacheManager cacheManager;
    private final InfinispanKeyValueStore<ExpectationEntry> expectations;
    private final InfinispanKeyValueStore<String> scenarioStates;
    private final InfinispanKeyValueStore<Integer> sharedTimesCounters;
    private final ConcurrentHashMap<String, KeyValueStore<ObjectNode>> crudStores;
    private final InfinispanBlobStore blobStore;
    private final String nodeId;
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();
    private final boolean clustered;
    // Set of cache names for which a clustered Infinispan @Listener has
    // already been registered. Prevents N^2 listener firing when
    // addInvalidationListener is called N times.
    private final java.util.Set<String> registeredClusterListenerCaches = ConcurrentHashMap.newKeySet();

    /**
     * Creates an Infinispan state backend in the appropriate mode based
     * on configuration. When {@code configuration.clusterEnabled()} is
     * {@code true}, a JGroups-transported clustered cache manager is
     * created; otherwise a LOCAL (non-clustered) manager is used.
     *
     * @param configuration the MockServer configuration
     */
    public InfinispanStateBackend(Configuration configuration) {
        this.nodeId = UUIDService.getUUID();
        this.clustered = configuration.clusterEnabled();
        this.cacheManager = clustered
            ? createClusteredCacheManager(configuration)
            : createLocalCacheManager(configuration.maxExpectations());

        this.expectations = createKeyValueStore(EXPECTATIONS_CACHE);
        this.scenarioStates = createKeyValueStore(SCENARIO_STATES_CACHE);
        this.sharedTimesCounters = createKeyValueStore(SHARED_TIMES_CACHE);
        this.blobStore = new InfinispanBlobStore(cacheManager.getCache(BLOBS_CACHE));
        this.crudStores = new ConcurrentHashMap<>();

        LOG.info("InfinispanStateBackend started (mode={}, nodeId={}, clusterName={})",
            clustered ? "CLUSTERED" : "LOCAL",
            nodeId,
            clustered ? configuration.clusterName() : "n/a");
    }

    /**
     * Backward-compatible constructor for tests and single-node usage.
     * Creates a LOCAL (non-clustered) backend.
     *
     * @param maxExpectations the maximum number of expectations
     */
    public InfinispanStateBackend(int maxExpectations) {
        this.nodeId = UUIDService.getUUID();
        this.clustered = false;
        this.cacheManager = createLocalCacheManager(maxExpectations);
        this.expectations = createKeyValueStore(EXPECTATIONS_CACHE);
        this.scenarioStates = createKeyValueStore(SCENARIO_STATES_CACHE);
        this.sharedTimesCounters = createKeyValueStore(SHARED_TIMES_CACHE);
        this.blobStore = new InfinispanBlobStore(cacheManager.getCache(BLOBS_CACHE));
        this.crudStores = new ConcurrentHashMap<>();

        LOG.info("InfinispanStateBackend started (LOCAL mode, nodeId={})", nodeId);
    }

    // --- Cache Manager creation ---

    /**
     * Creates a LOCAL (non-clustered) cache manager — Phase 2b behaviour,
     * byte-for-byte identical.
     */
    private static EmbeddedCacheManager createLocalCacheManager(int maxExpectations) {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        global.nonClusteredDefault();
        // LOCAL mode: heap-only, no network marshalling. Allow-list is
        // permissive because no untrusted bytes are ever deserialized.
        global.serialization().allowList().addRegexp(".*");

        ConfigurationBuilder expectationsConfig = new ConfigurationBuilder();
        expectationsConfig
            .memory()
                .storage(StorageType.HEAP)
                .maxCount(maxExpectations)
                .whenFull(EvictionStrategy.REMOVE);

        ConfigurationBuilder unboundedConfig = new ConfigurationBuilder();
        unboundedConfig.memory().storage(StorageType.HEAP);

        // Shared-times counter cache: bounded like the expectation cache so a
        // counter for an evicted/removed expectation cannot leak unboundedly.
        ConfigurationBuilder sharedTimesConfig = new ConfigurationBuilder();
        sharedTimesConfig
            .memory()
                .storage(StorageType.HEAP)
                .maxCount(maxExpectations)
                .whenFull(EvictionStrategy.REMOVE);

        DefaultCacheManager manager = new DefaultCacheManager(global.build());
        manager.defineConfiguration(EXPECTATIONS_CACHE, expectationsConfig.build());
        manager.defineConfiguration(SCENARIO_STATES_CACHE, unboundedConfig.build());
        manager.defineConfiguration(SHARED_TIMES_CACHE, sharedTimesConfig.build());
        manager.defineConfiguration(BLOBS_CACHE, unboundedConfig.build());

        return manager;
    }

    /**
     * Creates a CLUSTERED cache manager with JGroups transport and REPL_SYNC
     * caches. The serialization allow-list is restricted to the exact
     * packages that cross the wire (P0 security gate resolved).
     */
    private static EmbeddedCacheManager createClusteredCacheManager(Configuration configuration) {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();

        // JGroups transport
        String transportConfig = configuration.clusterTransportConfig();
        if (transportConfig != null && !transportConfig.isEmpty()) {
            global.transport().defaultTransport()
                .clusterName(configuration.clusterName())
                .addProperty("configurationFile", transportConfig);
        } else {
            // Use the built-in loopback JGroups stack for embedded testing
            global.transport().defaultTransport()
                .clusterName(configuration.clusterName())
                .addProperty("configurationFile", "jgroups-loopback.xml");
        }

        // Use Java serialization for clustered wire format (not ProtoStream)
        // so that VersionedWrapper<V> and its payloads (ExpectationEntry,
        // String, ObjectNode, Blob) can be marshalled without per-type
        // proto schema definitions. The domain model (Expectation) is
        // serialized as JSON inside ExpectationEntry's custom writeObject.
        global.serialization().marshaller(new JavaSerializationMarshaller());

        // P0 SECURITY GATE: explicit allow-list (see CLUSTERED_ALLOW_LIST_PATTERNS) covering
        // exactly the types that Infinispan needs to serialize for clustered replication. NO
        // wildcard. Applied from the shared constant so the security test cannot drift from it.
        var allowList = global.serialization().allowList();
        CLUSTERED_ALLOW_LIST_PATTERNS.forEach(allowList::addRegexp);

        // Expectations cache: REPL_SYNC, bounded
        ConfigurationBuilder expectationsConfig = new ConfigurationBuilder();
        expectationsConfig
            .clustering().cacheMode(CacheMode.REPL_SYNC)
            .memory()
                .storage(StorageType.HEAP)
                .maxCount(configuration.maxExpectations())
                .whenFull(EvictionStrategy.REMOVE);

        // Scenario states cache: REPL_SYNC, unbounded
        ConfigurationBuilder scenarioConfig = new ConfigurationBuilder();
        scenarioConfig
            .clustering().cacheMode(CacheMode.REPL_SYNC)
            .memory().storage(StorageType.HEAP);

        // Blobs cache: REPL_SYNC, unbounded
        ConfigurationBuilder blobsConfig = new ConfigurationBuilder();
        blobsConfig
            .clustering().cacheMode(CacheMode.REPL_SYNC)
            .memory().storage(StorageType.HEAP);

        // Shared-times counter cache: REPL_SYNC, bounded like the expectation
        // cache. Each value is a single Integer, so a clustered Times consume
        // replicates ~a handful of bytes rather than re-serialising the whole
        // ExpectationEntry (which marshals the entire expectation as JSON).
        ConfigurationBuilder sharedTimesConfig = new ConfigurationBuilder();
        sharedTimesConfig
            .clustering().cacheMode(CacheMode.REPL_SYNC)
            .memory()
                .storage(StorageType.HEAP)
                .maxCount(configuration.maxExpectations())
                .whenFull(EvictionStrategy.REMOVE);

        DefaultCacheManager manager = new DefaultCacheManager(global.build());
        manager.defineConfiguration(EXPECTATIONS_CACHE, expectationsConfig.build());
        manager.defineConfiguration(SCENARIO_STATES_CACHE, scenarioConfig.build());
        manager.defineConfiguration(SHARED_TIMES_CACHE, sharedTimesConfig.build());
        manager.defineConfiguration(BLOBS_CACHE, blobsConfig.build());

        return manager;
    }

    @SuppressWarnings("unchecked")
    private <V> InfinispanKeyValueStore<V> createKeyValueStore(String cacheName) {
        Cache<String, VersionedWrapper<V>> cache =
            (Cache<String, VersionedWrapper<V>>) (Cache<?, ?>) cacheManager.getCache(cacheName);
        return new InfinispanKeyValueStore<>(cache);
    }

    // --- StateBackend implementation ---

    @Override
    public KeyValueStore<ExpectationEntry> expectations() {
        return expectations;
    }

    @Override
    public KeyValueStore<String> scenarioStates() {
        return scenarioStates;
    }

    /**
     * Returns the shared-times counter store. A clustered Times consume CASes
     * this store, so only the small counter value crosses the wire — the
     * counter cache is deliberately NOT wired to the cross-node invalidation
     * listener (a decrement must not trigger a node-local matcher reconcile).
     */
    @Override
    public KeyValueStore<Integer> sharedTimesCounters() {
        return sharedTimesCounters;
    }

    @Override
    public KeyValueStore<ObjectNode> crudEntities(String namespace) {
        return crudStores.computeIfAbsent(namespace, ns -> {
            String cacheName = CRUD_CACHE_PREFIX + ns;
            ConfigurationBuilder crudConfig = new ConfigurationBuilder();
            if (clustered) {
                crudConfig.clustering().cacheMode(CacheMode.REPL_SYNC);
            }
            crudConfig.memory().storage(StorageType.HEAP);
            cacheManager.defineConfiguration(cacheName, crudConfig.build());
            InfinispanKeyValueStore<ObjectNode> store = createKeyValueStore(cacheName);
            // G11: register a clustered cache listener on newly created CRUD
            // caches so that remote writes (e.g. chaos profile replication)
            // trigger InvalidationListener callbacks on this node. Without
            // this, only expectations and scenarioStates caches fire cluster
            // events — CRUD entity caches would be invisible to invalidation.
            if (clustered) {
                registerClusterListenerOnce(cacheName);
                // Also wire local invalidation for any already-registered listeners
                for (InvalidationListener listener : listeners) {
                    store.addInvalidationListener(listener);
                }
            }
            return store;
        });
    }

    @Override
    public BlobStore blobs() {
        return blobStore;
    }

    @Override
    public void addInvalidationListener(InvalidationListener listener) {
        listeners.add(listener);
        // Local invalidation (fires for local puts in InfinispanKeyValueStore)
        expectations.addInvalidationListener(listener);
        scenarioStates.addInvalidationListener(listener);
        // Clustered invalidation (fires for REMOTE writes only, via
        // Infinispan's @Listener(clustered=true)). Register the cluster
        // listener ONCE per cache — it bridges to the shared `listeners`
        // list, so subsequently added InvalidationListeners are picked up
        // automatically. In LOCAL mode these listeners never fire because
        // there are no remote events.
        if (clustered) {
            registerClusterListenerOnce(EXPECTATIONS_CACHE);
            registerClusterListenerOnce(SCENARIO_STATES_CACHE);
        }
    }

    /**
     * Registers an Infinispan clustered cache listener on the named cache
     * exactly ONCE. The listener bridges remote cache events to the shared
     * {@code listeners} list, so all current and future InvalidationListeners
     * are notified. Subsequent calls for the same cache name are no-ops.
     */
    private void registerClusterListenerOnce(String cacheName) {
        if (registeredClusterListenerCaches.add(cacheName)) {
            Cache<?, ?> cache = cacheManager.getCache(cacheName);
            InfinispanCacheListener cacheListener = new InfinispanCacheListener(listeners);
            cache.addListener(cacheListener);
        }
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    /**
     * Returns whether this backend is in clustered mode. Overrides the
     * {@link StateBackend} default ({@code false}) to return the actual
     * clustering state from the configuration. When {@code true},
     * {@code RequestMatchers} will use backend CAS for Times consumption.
     */
    @Override
    public boolean isClustered() {
        return clustered;
    }

    /**
     * Returns the underlying cache manager (for tests and diagnostics).
     */
    EmbeddedCacheManager getCacheManager() {
        return cacheManager;
    }

    /**
     * Reports real cluster membership and health from the Infinispan cache
     * manager's JGroups transport.
     * <p>
     * In LOCAL mode (no transport) {@link EmbeddedCacheManager#getMembers()}
     * and {@link EmbeddedCacheManager#getAddress()} are {@code null}, so this
     * falls back to the degenerate single-node snapshot. In CLUSTERED mode it
     * lists every JGroups member, flags the coordinator, and marks this node's
     * own address as {@code local}. Fail-soft: any unexpected error degrades to
     * the single-node snapshot rather than failing the operability endpoint.
     */
    @Override
    public ClusterInfo clusterInfo() {
        try {
            java.util.List<org.infinispan.remoting.transport.Address> members = cacheManager.getMembers();
            org.infinispan.remoting.transport.Address localAddress = cacheManager.getAddress();
            if (!clustered || members == null || members.isEmpty() || localAddress == null) {
                return ClusterInfo.singleNode(nodeId);
            }
            org.infinispan.remoting.transport.Address coordinatorAddress = cacheManager.getCoordinator();
            String coordinator = coordinatorAddress != null ? coordinatorAddress.toString() : null;
            java.util.List<ClusterInfo.Member> memberList = new java.util.ArrayList<>(members.size());
            for (org.infinispan.remoting.transport.Address address : members) {
                memberList.add(new ClusterInfo.Member(
                    address.toString(),
                    address.equals(coordinatorAddress),
                    address.equals(localAddress)
                ));
            }
            return new ClusterInfo(
                true,
                localAddress.toString(),
                coordinator != null ? coordinator : localAddress.toString(),
                cacheManager.getClusterName(),
                memberList
            );
        } catch (Exception e) {
            LOG.warn("failed to read cluster membership, reporting single-node snapshot", e);
            return ClusterInfo.singleNode(nodeId);
        }
    }

    @Override
    public void close() {
        LOG.info("stopping InfinispanStateBackend (mode={}, nodeId={})",
            clustered ? "CLUSTERED" : "LOCAL", nodeId);
        if (cacheManager != null) {
            cacheManager.stop();
        }
    }
}
