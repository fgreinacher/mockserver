package org.mockserver.collections;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Priority-ordered queue with insertion-order eviction past {@code maxSize}.
 * <p>
 * <b>Concurrency contract:</b> all mutating methods ({@link #add(Object)},
 * {@link #remove(Object)}, {@link #replaceValue(Object, Object)},
 * {@link #addPriorityKey(Object)}, {@link #removePriorityKey(Object)},
 * {@link #setMaxSize(int)} and {@link #setEvictionListener(java.util.function.Consumer)})
 * are <b>single-writer</b> — callers serialize them on the control plane (the
 * Netty event loop / per-store synchronization). Read methods
 * ({@link #stream()}, {@link #toSortedList()}, {@link #getByKey(Object)},
 * {@link #size()}, {@link #keyMap()}) may run concurrently with the single
 * writer and are eventually consistent: a read concurrent with an in-flight
 * mutation may not yet reflect it, but never returns nulls (the
 * {@code filter(nonNull)} guard) or corrupt state.
 * <p>
 * <b>Precondition:</b> {@link #add(Object)} must not be called for a key that
 * already exists in the queue — use {@link #replaceValue(Object, Object)} for
 * in-place updates. Adding a duplicate key would push the key twice into the
 * insertion-order queue and corrupt eviction accounting.
 *
 * @author jamesdbloom
 */
public class CircularPriorityQueue<K, V, SLK extends Keyed<K>> {
    private int maxSize;
    private final Function<V, SLK> skipListKeyFunction;
    private final Function<V, K> mapKeyFunction;
    private final ConcurrentSkipListSet<SLK> sortOrderSkipList;
    // Insertion-order queue holds KEYS, not values. Insertion order only
    // matters for eviction (poll the eldest on overflow) and a key never
    // changes on an in-place update, so storing keys lets replaceValue run
    // in O(log n) (byKey.put + skip-list swap) instead of rebuilding the
    // whole queue. The live value for a key is always resolved via byKey.
    // Tradeoff: an element's eviction slot is fixed by its first insertion;
    // an in-place replaceValue leaves that slot untouched (which is the
    // desired semantics — eviction order follows original insertion time).
    private final ConcurrentLinkedQueue<K> insertionOrderQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<K, V> byKey = new ConcurrentHashMap<>();
    // Cached snapshot of the sorted list; nulled on every mutation so toSortedList()
    // rebuilds lazily. volatile ensures the null write is visible to all threads
    // immediately (no stale-cache reads after an add/remove).
    private volatile List<V> sortedCache = null;
    // Invoked once for every element evicted by overflow past maxSize, AFTER the
    // element has been removed from all three backing structures. Default is a
    // no-op so existing users/tests are unaffected. Used to clean up satellite
    // state keyed by the evicted element (e.g. a versions map) that would
    // otherwise leak for overflow-evicted keys.
    private volatile Consumer<V> evictionListener = element -> {
    };

    public CircularPriorityQueue(int maxSize, Comparator<? super SLK> skipListComparator, Function<V, SLK> skipListKeyFunction, Function<V, K> mapKeyFunction) {
        sortOrderSkipList = new ConcurrentSkipListSet<>(skipListComparator);
        this.maxSize = maxSize;
        this.skipListKeyFunction = skipListKeyFunction;
        this.mapKeyFunction = mapKeyFunction;
    }

    /**
     * Registers a listener invoked once for every element evicted because the
     * queue grew past {@code maxSize}. The listener is called AFTER the element
     * has been removed from the insertion queue, sort skip-list and byKey map,
     * so satellite state can be cleaned up safely. It is NOT invoked on explicit
     * {@link #remove(Object)} or {@link #replaceValue(Object, Object)}.
     *
     * @param evictionListener the listener, or {@code null} to restore the no-op default
     */
    public void setEvictionListener(Consumer<V> evictionListener) {
        this.evictionListener = evictionListener != null ? evictionListener : element -> {
        };
    }

    /**
     * Resize the bound. A SHRINK takes effect immediately: the eldest elements are evicted (firing
     * the eviction listener, exactly as an overflow eviction would) until the queue fits the new
     * bound, rather than waiting for the next {@link #add(Object)}. This is what makes a live
     * {@code maxExpectations} change via {@code PUT /mockserver/configuration} take effect at once.
     * Subject to the single-writer contract in the class javadoc.
     */
    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        if (maxSize > 0) {
            evictExcess();
        }
        sortedCache = null;
    }

    /**
     * Evict the eldest elements until the queue fits {@code maxSize}. Shared by {@link #add(Object)}
     * and {@link #setMaxSize(int)} so both paths keep the byKey map, sort skip-list and insertion
     * queue consistent and fire the eviction listener identically.
     */
    private void evictExcess() {
        while (insertionOrderQueue.size() > maxSize) {
            K keyToRemove = insertionOrderQueue.poll();
            // Resolve the live value via byKey, remove it, then update the
            // skip-list and fire the eviction listener. Under the single-
            // writer contract byKey.remove is non-null here (the key was
            // just polled from the insertion queue and no concurrent writer
            // exists); the guard defends against a precondition violation
            // (duplicate-key add) so eviction never NPEs or double-strips
            // the skip-list / fires the listener for a missing element.
            V elementToRemove = byKey.remove(keyToRemove);
            if (elementToRemove != null) {
                sortOrderSkipList.remove(skipListKeyFunction.apply(elementToRemove));
                evictionListener.accept(elementToRemove);
            }
        }
    }

    public void removePriorityKey(V element) {
        sortOrderSkipList.remove(skipListKeyFunction.apply(element));
        sortedCache = null;
    }

    public void addPriorityKey(V element) {
        sortOrderSkipList.add(skipListKeyFunction.apply(element));
        sortedCache = null;
    }

    public void add(V element) {
        if (maxSize > 0 && element != null) {
            K key = mapKeyFunction.apply(element);
            // Publish to byKey BEFORE the insertion-order queue so a concurrent
            // reader never sees a key in the queue (or its eviction accounting)
            // whose value is not yet resolvable via byKey.
            byKey.put(key, element);
            sortOrderSkipList.add(skipListKeyFunction.apply(element));
            insertionOrderQueue.offer(key);
            evictExcess();
            sortedCache = null;
        }
    }

    /**
     * Replaces the value associated with the given key in place, preserving
     * the element's position in {@code insertionOrderQueue} (and therefore
     * its eviction order). Because the insertion queue holds keys and the key
     * is invariant on update, the queue is left untouched — the element keeps
     * its exact eviction slot. Only the byKey map and the priority sort keys
     * (old removed, new added) are updated. O(log n).
     *
     * @param key      the key that identifies the existing element
     * @param newValue the replacement value
     * @return {@code true} if the key was found and the value replaced
     */
    public boolean replaceValue(K key, V newValue) {
        V existing = byKey.get(key);
        if (existing == null) {
            return false;
        }
        // Update byKey
        byKey.put(key, newValue);
        // Update priority sort: remove old, add new
        sortOrderSkipList.remove(skipListKeyFunction.apply(existing));
        sortOrderSkipList.add(skipListKeyFunction.apply(newValue));
        sortedCache = null;
        return true;
    }

    public boolean remove(V element) {
        if (element != null) {
            K key = mapKeyFunction.apply(element);
            insertionOrderQueue.remove(key);
            byKey.remove(key);
            boolean removed = sortOrderSkipList.remove(skipListKeyFunction.apply(element));
            sortedCache = null;
            return removed;
        } else {
            return false;
        }
    }

    public int size() {
        return insertionOrderQueue.size();
    }

    public Stream<V> stream() {
        return sortOrderSkipList.stream().map(item -> byKey.get(item.getKey())).filter(Objects::nonNull);
    }

    public Optional<V> getByKey(K key) {
        if (key != null && !"".equals(key)) {
            return Optional.ofNullable(byKey.get(key));
        } else {
            return Optional.empty();
        }
    }

    public Map<K, V> keyMap() {
        return new HashMap<>(byKey);
    }

    public boolean isEmpty() {
        return insertionOrderQueue.isEmpty();
    }

    /**
     * Returns a cached, unmodifiable sorted snapshot of this queue's elements.
     * The snapshot is rebuilt lazily when any mutation nulls the cache.
     * <p>
     * <b>Eventually-consistent under concurrent mutation:</b> a call to
     * this method concurrent with a control-plane mutation (add/remove/
     * reconcileFromBackend) may return a snapshot that does not yet reflect
     * the in-flight mutation. This is the existing control-plane / data-plane
     * concurrency contract — no lock is held on the matching hot path.
     */
    public List<V> toSortedList() {
        List<V> cached = sortedCache;
        if (cached == null) {
            cached = Collections.unmodifiableList(stream().collect(Collectors.toList()));
            sortedCache = cached;
        }
        return cached;
    }
}
