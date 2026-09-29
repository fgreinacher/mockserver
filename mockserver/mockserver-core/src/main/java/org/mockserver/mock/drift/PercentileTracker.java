package org.mockserver.mock.drift;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding window p50/p95 tracker for response times per expectation ID.
 * Uses a fixed-size circular buffer per expectation. Thread-safe via
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}.
 * <p>
 * An expectation's window is dropped when the expectation is removed or evicted
 * ({@link #remove(String)}); the number of tracked ids is additionally capped so a
 * record that races a removal cannot accumulate orphaned windows without bound.
 */
public class PercentileTracker {

    static final int MAX_TRACKED_EXPECTATIONS = 20_000;

    private static final PercentileTracker INSTANCE = new PercentileTracker(100);

    private static final class Window {
        private final long[] samples;
        // next write position, always in [0, samples.length)
        private int next;
        // samples held, saturating at samples.length — never overflows however many are recorded
        private int filled;

        private Window(int size) {
            this.samples = new long[size];
        }
    }

    private final int windowSize;
    private final int maxTrackedExpectations;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public PercentileTracker(int windowSize) {
        this(windowSize, MAX_TRACKED_EXPECTATIONS);
    }

    PercentileTracker(int windowSize, int maxTrackedExpectations) {
        this.windowSize = windowSize;
        this.maxTrackedExpectations = maxTrackedExpectations;
    }

    public static PercentileTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Record a response time observation for the given expectation ID.
     */
    public void record(String expectationId, long responseTimeMs) {
        if (expectationId == null) {
            return;
        }
        if (windows.size() >= maxTrackedExpectations && !windows.containsKey(expectationId)) {
            Iterator<String> ids = windows.keySet().iterator();
            while (windows.size() >= maxTrackedExpectations && ids.hasNext()) {
                ids.next();
                ids.remove();
            }
        }
        windows.compute(expectationId, (key, existing) -> {
            Window window = existing != null ? existing : new Window(windowSize);
            window.samples[window.next] = responseTimeMs;
            window.next = (window.next + 1) % windowSize;
            if (window.filled < windowSize) {
                window.filled++;
            }
            return window;
        });
    }

    /**
     * @return the p50 (median) response time for the given expectation, or 0 if no data.
     */
    public long p50(String expectationId) {
        return percentile(expectationId, 50);
    }

    /**
     * @return the p95 response time for the given expectation, or 0 if no data.
     */
    public long p95(String expectationId) {
        return percentile(expectationId, 95);
    }

    /**
     * @return the number of observations held for the given expectation (at most the window size).
     */
    public int count(String expectationId) {
        Window window = expectationId != null ? windows.get(expectationId) : null;
        return window != null ? window.filled : 0;
    }

    private long percentile(String expectationId, int pct) {
        if (expectationId == null) {
            return 0;
        }
        // copy under the same per-key lock record() writes under, so the snapshot is consistent
        long[][] holder = new long[1][];
        windows.computeIfPresent(expectationId, (key, current) -> {
            holder[0] = Arrays.copyOf(current.samples, current.filled);
            return current;
        });
        long[] copy = holder[0];
        if (copy == null || copy.length == 0) {
            return 0;
        }
        Arrays.sort(copy);
        int idx = (int) Math.ceil((pct / 100.0) * copy.length) - 1;
        return copy[Math.max(0, Math.min(idx, copy.length - 1))];
    }

    /**
     * Drops the window of a removed or evicted expectation.
     */
    public void remove(String expectationId) {
        if (expectationId != null) {
            windows.remove(expectationId);
        }
    }

    /**
     * @return the number of expectations currently tracked.
     */
    public int trackedExpectations() {
        return windows.size();
    }

    /**
     * Clears all tracked data.
     */
    public void clear() {
        windows.clear();
    }
}
