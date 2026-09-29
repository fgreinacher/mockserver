package org.mockserver.mock;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.RequestDefinition;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The request definitions of REMOVED expectations (Times used up, TTL expired, or cleared), kept so
 * an expectation id stays resolvable for {@code verify}, {@code retrieve} and {@code clear} after the
 * expectation itself is gone — e.g. verifying a {@code Times.exactly(2)} expectation by id once it
 * has served its two responses. Live expectations are never stored here: their definition is read
 * from the expectation store.
 * <p>
 * Bounded by entry count ({@code maxExpectations}) AND by estimated bytes, evicting the
 * longest-retired entry first, so a workload that churns through expectations with large request
 * bodies cannot fill the heap with definitions nothing references any more. Cleared by reset.
 * <p>
 * Thread-safe; every method holds only this object's monitor and calls nothing outside it.
 */
class RetiredRequestDefinitions {

    // Default byte budget as a fraction of the heap-ceiling budget, with a fixed fallback when the
    // JVM reports no heap ceiling (heapAvailableInKB() == 0, e.g. a native image).
    static final long HEAP_FRACTION_DIVISOR = 16;
    static final long UNDEFINED_HEAP_FALLBACK_BYTES = 16L * 1024 * 1024;

    private static final class Retired {
        private final RequestDefinition definition;
        private final long bytes;

        private Retired(RequestDefinition definition, long bytes) {
            this.definition = definition;
            this.bytes = bytes;
        }
    }

    private final LinkedHashMap<String, Retired> retired = new LinkedHashMap<>();
    private int maxEntries;
    private long maxBytes;
    private long retainedBytes;

    RetiredRequestDefinitions(int maxEntries) {
        this(maxEntries, defaultMaxBytes(ConfigurationProperties.heapAvailableInKB()));
    }

    RetiredRequestDefinitions(int maxEntries, long maxBytes) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    static long defaultMaxBytes(long heapAvailableInKB) {
        return heapAvailableInKB > 0 ? heapAvailableInKB * 1024 / HEAP_FRACTION_DIVISOR : UNDEFINED_HEAP_FALLBACK_BYTES;
    }

    synchronized void retire(String id, RequestDefinition definition) {
        if (id == null || definition == null) {
            return;
        }
        forget(id);
        long bytes = Expectation.estimatedRequestDefinitionHeapSize(definition);
        retired.put(id, new Retired(definition, bytes));
        retainedBytes += bytes;
        trim();
    }

    synchronized RequestDefinition get(String id) {
        Retired entry = id != null ? retired.get(id) : null;
        return entry != null ? entry.definition : null;
    }

    synchronized void forget(String id) {
        Retired removed = id != null ? retired.remove(id) : null;
        if (removed != null) {
            retainedBytes -= removed.bytes;
        }
    }

    synchronized void clear() {
        retired.clear();
        retainedBytes = 0;
    }

    synchronized void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
        trim();
    }

    synchronized void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
        trim();
    }

    synchronized int size() {
        return retired.size();
    }

    synchronized long retainedBytes() {
        return retainedBytes;
    }

    // The newest entry is kept even when it alone exceeds the byte budget, so the expectation removed
    // most recently stays verifiable by id; only the count bound can drop it.
    private void trim() {
        Iterator<Map.Entry<String, Retired>> eldestFirst = retired.entrySet().iterator();
        while (eldestFirst.hasNext() && (retired.size() > maxEntries || (retired.size() > 1 && retainedBytes > maxBytes))) {
            retainedBytes -= eldestFirst.next().getValue().bytes;
            eldestFirst.remove();
        }
    }
}
