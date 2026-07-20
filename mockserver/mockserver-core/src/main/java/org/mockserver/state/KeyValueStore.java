package org.mockserver.state;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Versioned key-value store abstraction for shared MockServer state.
 * <p>
 * The in-memory implementation wraps the existing concurrent data structures
 * (e.g. {@code CircularPriorityQueue} for expectations, {@code ConcurrentHashMap}
 * for scenario state). A clustered implementation can back this with a
 * distributed cache while preserving identical semantics.
 *
 * @param <V> the value type
 */
public interface KeyValueStore<V> {

    /**
     * Retrieves the versioned value for the given key.
     *
     * @param key the key
     * @return the versioned value, or empty if not present
     */
    Optional<Versioned<V>> get(String key);

    /**
     * Unconditionally puts a value, creating or replacing any existing entry.
     * Returns the new version.
     * <p>
     * Semantics are <b>last-writer-wins</b>: concurrent {@code put} calls for the same key
     * race and the final value is whichever write lands last (mirroring {@code ConcurrentHashMap}).
     * Callers that need to detect/avoid lost updates must use {@link #compareAndSet} with the
     * version from a prior {@link #get}.
     *
     * @param key   the key
     * @param value the value
     * @return the version assigned to this write
     */
    long put(String key, V value);

    /**
     * Atomically inserts the value only if no entry for the key exists yet.
     * If the key is already present, the store is not modified and the
     * existing versioned value is returned. If insertion succeeds, an empty
     * {@code Optional} is returned.
     * <p>
     * This is the create-only counterpart of {@link #put}: it never
     * overwrites an existing entry. Callers that need last-writer-wins
     * semantics should use {@link #put}; callers that need to detect
     * and defer to a concurrent creator should use this method.
     *
     * @param key   the key
     * @param value the value to insert
     * @return empty if the entry was created by this call; otherwise the
     *         existing versioned value that was already present
     */
    Optional<Versioned<V>> putIfAbsent(String key, V value);

    /**
     * Atomically replaces the value only if the current version matches
     * {@code expectedVersion}. Returns {@code true} on success.
     *
     * @param key             the key
     * @param expectedVersion the version the caller last read
     * @param value           the new value
     * @return true if the swap succeeded
     */
    boolean compareAndSet(String key, long expectedVersion, V value);

    /**
     * Atomically removes the entry only if the current version matches
     * {@code expectedVersion}. Returns {@code true} on success.
     *
     * @param key             the key
     * @param expectedVersion the version the caller last read
     * @return true if the removal succeeded
     */
    boolean compareAndRemove(String key, long expectedVersion);

    /**
     * Unconditionally removes the entry for the given key.
     *
     * @param key the key
     * @return true if the key was present
     */
    boolean remove(String key);

    /**
     * Returns a stream of all entries. The iteration order is
     * implementation-defined (unordered for a generic KV; sorted for
     * the expectation store).
     *
     * @return stream of key-versioned-value triples
     */
    Stream<Entry<V>> entries();

    /**
     * Returns the number of entries.
     */
    int size();

    /**
     * Removes all entries.
     */
    void clear();

    /**
     * Resize a bounded store's capacity, evicting the eldest entries immediately if the new
     * capacity is smaller than the current entry count. Called when {@code maxExpectations} changes
     * via {@code PUT /mockserver/configuration} so the change takes effect on the running store.
     * <p>
     * Default is a no-op for implementations whose capacity is not managed here (e.g. a clustered
     * cache whose eviction policy is configured on the cache itself).
     *
     * @param maxSize the new capacity
     */
    default void setMaxSize(int maxSize) {
        // no-op — unbounded, or bounded by the underlying implementation's own configuration
    }

    /**
     * Adds an invalidation listener that is notified on mutations.
     *
     * @param listener the listener
     */
    void addInvalidationListener(InvalidationListener listener);

    /**
     * A key-value entry with version metadata.
     *
     * @param <V> the value type
     */
    final class Entry<V> {
        private final String key;
        private final Versioned<V> versioned;

        public Entry(String key, Versioned<V> versioned) {
            this.key = key;
            this.versioned = versioned;
        }

        public String getKey() {
            return key;
        }

        public Versioned<V> getVersioned() {
            return versioned;
        }

        public V getValue() {
            return versioned.getValue();
        }

        public long getVersion() {
            return versioned.getVersion();
        }
    }
}
