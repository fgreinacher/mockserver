package org.mockserver.slo;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Set;

/**
 * Process-wide store of recent request samples used to evaluate
 * {@link SloCriteria} into an {@link SloVerdict}. Mirrors the singleton +
 * {@code reset()} pattern of {@code ServiceChaosRegistry} /
 * {@code ChaosAutoHaltMonitor}.
 *
 * <p>Each sample is one upstream round-trip (v1 records only {@link Scope#FORWARD}
 * traffic): its wall-clock end time, latency, whether it was an error
 * (status {@code null} or {@code >= 500}), the {@link Scope}, and the upstream
 * host. Samples are bounded two ways:
 *
 * <ul>
 *   <li><b>By count</b> — at most {@code sloWindowMaxSamples} (default 50 000);
 *       the oldest is evicted when full.</li>
 *   <li><b>By age</b> — samples older than {@code sloWindowRetentionMillis}
 *       (default 600 000 ms) relative to the newest recorded sample are evicted
 *       lazily on record and on query.</li>
 * </ul>
 *
 * <p>When {@code sloTrackingEnabled} is {@code false} (the default),
 * {@link #record} is a no-op so the forward hot path pays nothing. Queries
 * always work against whatever samples exist.
 *
 * <p>Latency percentiles use the same algorithm as
 * {@code org.mockserver.mock.drift.PercentileTracker}: sort the in-window
 * latencies ascending and take the {@code ceil(p/100 * n) - 1} index (clamped).
 */
public class SloSampleStore {

    private static final SloSampleStore INSTANCE = new SloSampleStore();

    /**
     * One recorded sample. Packed into parallel arrays inside the ring would be
     * cheaper, but a small immutable holder keeps the query code readable and
     * the volumes (≤ 50 000) modest.
     */
    static final class Sample {
        final long epochMillis;
        final long latencyMillis;
        final boolean error;
        final Scope scope;
        final String host;

        Sample(long epochMillis, long latencyMillis, boolean error, Scope scope, String host) {
            this.epochMillis = epochMillis;
            this.latencyMillis = latencyMillis;
            this.error = error;
            this.scope = scope;
            this.host = host;
        }
    }

    /** Result of an error-rate query over a window: error and total sample counts. */
    public static final class ErrorCounts {
        private final long errors;
        private final long total;

        public ErrorCounts(long errors, long total) {
            this.errors = errors;
            this.total = total;
        }

        public long getErrors() {
            return errors;
        }

        public long getTotal() {
            return total;
        }
    }

    /**
     * Newest-at-tail FIFO of samples. All access is synchronized on the deque
     * itself; the forward path appends one entry per upstream round-trip and the
     * (rare) control-plane query reads a window, so contention is negligible.
     */
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public static SloSampleStore getInstance() {
        return INSTANCE;
    }

    /**
     * The effective SLO settings: the {@link Configuration} instance value when one is supplied,
     * otherwise the static {@link ConfigurationProperties} store. The instance is preferred so a
     * value set programmatically or via {@code PUT /mockserver/configuration} actually takes effect;
     * the static fallback keeps system-property/env/file users unaffected.
     */
    private static boolean trackingEnabled(Configuration configuration) {
        return configuration != null
            ? configuration.sloTrackingEnabled()
            : ConfigurationProperties.sloTrackingEnabled();
    }

    private static int windowMaxSamples(Configuration configuration) {
        return configuration != null
            ? configuration.sloWindowMaxSamples()
            : ConfigurationProperties.sloWindowMaxSamples();
    }

    private static long windowRetentionMillis(Configuration configuration) {
        return configuration != null
            ? configuration.sloWindowRetentionMillis()
            : ConfigurationProperties.sloWindowRetentionMillis();
    }

    /**
     * Record one sample, reading the SLO settings from the static
     * {@link ConfigurationProperties} store. Prefer
     * {@link #record(Configuration, long, long, boolean, Scope, String)} from any call site that
     * has a {@link Configuration} in scope, so an instance-set value takes effect.
     */
    public void record(long epochMillis, long latencyMillis, boolean isError, Scope scope, String host) {
        record(null, epochMillis, latencyMillis, isError, scope, host);
    }

    /**
     * Record one sample. No-op when {@code sloTrackingEnabled} is false so the
     * forward hot path pays nothing when the feature is off.
     *
     * @param configuration the server configuration whose instance values take precedence over the
     *                      static {@link ConfigurationProperties} store; may be null
     * @param epochMillis   the sample's wall-clock end time (epoch millis)
     * @param latencyMillis upstream round-trip latency in milliseconds
     * @param isError       true when the response was an error (status null or {@code >= 500})
     * @param scope         which traffic the sample belongs to (v1 records {@link Scope#FORWARD})
     * @param host          the upstream host label, may be null
     */
    public void record(Configuration configuration, long epochMillis, long latencyMillis, boolean isError, Scope scope, String host) {
        if (!trackingEnabled(configuration)) {
            return;
        }
        int maxSamples = windowMaxSamples(configuration);
        long retentionMillis = windowRetentionMillis(configuration);
        synchronized (samples) {
            samples.addLast(new Sample(epochMillis, latencyMillis, isError, scope, host));
            evictExpired(epochMillis, retentionMillis);
            // Evict oldest beyond the count bound.
            while (maxSamples > 0 && samples.size() > maxSamples) {
                samples.pollFirst();
            }
        }
    }

    /**
     * @return the in-window, in-scope, in-host latencies (ascending order ready
     * for percentile selection), or an empty array when none match.
     */
    public long[] latenciesInWindow(long fromEpochMillis, long toEpochMillis, Scope scope, Set<String> hosts) {
        long[] buffer;
        int count;
        synchronized (samples) {
            buffer = new long[samples.size()];
            count = 0;
            for (Sample sample : samples) {
                if (matches(sample, fromEpochMillis, toEpochMillis, scope, hosts)) {
                    buffer[count++] = sample.latencyMillis;
                }
            }
        }
        long[] result = Arrays.copyOf(buffer, count);
        Arrays.sort(result);
        return result;
    }

    /**
     * @return error and total sample counts for the in-window, in-scope, in-host
     * samples.
     */
    public ErrorCounts errorCountsInWindow(long fromEpochMillis, long toEpochMillis, Scope scope, Set<String> hosts) {
        long errors = 0;
        long total = 0;
        synchronized (samples) {
            for (Sample sample : samples) {
                if (matches(sample, fromEpochMillis, toEpochMillis, scope, hosts)) {
                    total++;
                    if (sample.error) {
                        errors++;
                    }
                }
            }
        }
        return new ErrorCounts(errors, total);
    }

    /**
     * The latency percentile (0-100) over the supplied ascending latencies, using
     * the same algorithm as {@code PercentileTracker.percentile}. Returns
     * {@code null} when there are no samples (so the caller can mark the objective
     * INCONCLUSIVE rather than reporting a misleading zero).
     */
    public static Double percentile(long[] ascendingLatencies, int pct) {
        if (ascendingLatencies == null || ascendingLatencies.length == 0) {
            return null;
        }
        int filled = ascendingLatencies.length;
        int idx = (int) Math.ceil((pct / 100.0) * filled) - 1;
        return (double) ascendingLatencies[Math.max(0, Math.min(idx, filled - 1))];
    }

    /** The total number of samples currently held (across all scopes/hosts). */
    public int size() {
        synchronized (samples) {
            return samples.size();
        }
    }

    /** Clears all samples. Called on server reset and for test isolation. */
    public void reset() {
        synchronized (samples) {
            samples.clear();
        }
    }

    private static boolean matches(Sample sample, long fromEpochMillis, long toEpochMillis, Scope scope, Set<String> hosts) {
        if (sample.epochMillis < fromEpochMillis || sample.epochMillis > toEpochMillis) {
            return false;
        }
        if (scope != null && sample.scope != scope) {
            return false;
        }
        return hosts == null || hosts.isEmpty() || hosts.contains(sample.host);
    }

    /**
     * Evict samples older than the retention window relative to {@code newestEpochMillis}.
     * Samples are appended in arrival order, which for the forward path is
     * effectively time order; the head is therefore the oldest. Must be called
     * while holding the {@code samples} monitor.
     */
    private void evictExpired(long newestEpochMillis, long retentionMillis) {
        if (retentionMillis <= 0) {
            return;
        }
        long cutoff = newestEpochMillis - retentionMillis;
        Sample head;
        while ((head = samples.peekFirst()) != null && head.epochMillis < cutoff) {
            samples.pollFirst();
        }
    }
}
