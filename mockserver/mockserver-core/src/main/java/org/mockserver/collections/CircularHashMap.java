package org.mockserver.collections;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author jamesdbloom
 */
public class CircularHashMap<K, V> extends LinkedHashMap<K, V> {

    private static final long serialVersionUID = 1L;

    private volatile int maxSize;
    private final Consumer<V> evictionListener;

    public CircularHashMap(int maxSize) {
        this(maxSize, null);
    }

    public CircularHashMap(int maxSize, Consumer<V> evictionListener) {
        this.maxSize = maxSize;
        this.evictionListener = evictionListener;
    }

    /**
     * Resize the bound. A SHRINK takes effect immediately: the eldest entries are removed (firing
     * the eviction listener, exactly as an overflow eviction would) until the map fits the new
     * bound, rather than waiting for the next {@code put}. This lets a live {@code maxExpectations}
     * change via {@code PUT /mockserver/configuration} resize this map alongside the expectation
     * queue it shadows.
     * <p>
     * Not internally synchronized — callers that share the map across threads must serialize this
     * with their other mutations (MockServer's {@code RequestMatchers} does; the
     * {@code LocalCallbackRegistry} maps are wrapped in {@code Collections.synchronizedMap}).
     */
    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        Iterator<Map.Entry<K, V>> iterator = entrySet().iterator();
        while (size() > maxSize && iterator.hasNext()) {
            V evicted = iterator.next().getValue();
            iterator.remove();
            if (evictionListener != null) {
                evictionListener.accept(evicted);
            }
        }
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        boolean shouldRemove = size() > maxSize;
        if (shouldRemove && evictionListener != null) {
            evictionListener.accept(eldest.getValue());
        }
        return shouldRemove;
    }

    public K findKey(V value) {
        for (Map.Entry<K, V> entry : entrySet()) {
            V entryValue = entry.getValue();
            if (entryValue == value || (value != null && value.equals(entryValue))) {
                return entry.getKey();
            }
        }
        return null;
    }
}
