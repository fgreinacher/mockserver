package org.mockserver.load;

import org.mockserver.model.ObjectWithJsonToString;
import org.mockserver.slo.SloObjective;

/**
 * One in-run pass/fail threshold for a {@link LoadScenario}: a load metric compared against a
 * threshold value. All a scenario's thresholds must hold for the run verdict to be {@code PASS}
 * (logical AND); any breach makes the verdict {@code FAIL}.
 *
 * <p>Thresholds are evaluated on the orchestrator's control tick from <em>per-run</em> data (this
 * run's HDR latency histogram and its request counters) — never from the global
 * {@link org.mockserver.slo.SloSampleStore}, which aggregates all forward traffic rather than this
 * one run. The comparison reuses {@link SloObjective.Comparator} (and its
 * {@link SloObjective#satisfiedBy(double)} semantics) so the two features share one comparator
 * vocabulary, but a load threshold is otherwise independent of the SLO verify feature.
 *
 * @see LoadScenario#getThresholds()
 */
public class LoadThreshold extends ObjectWithJsonToString {

    /**
     * The per-run metric a threshold is evaluated against.
     *
     * <ul>
     *   <li>{@link #LATENCY_P50}/{@link #LATENCY_P95}/{@link #LATENCY_P99}/{@link #LATENCY_P999} —
     *       coordinated-omission-corrected latency percentiles in <b>milliseconds</b>, read from the
     *       run's HDR histogram.</li>
     *   <li>{@link #ERROR_RATE} — failed requests as a <b>fraction</b> of <em>completed</em> requests
     *       (succeeded + failed), in (0..1). In-flight (dispatched-but-not-yet-completed) requests are
     *       excluded so the rate is never diluted by requests whose outcome is not yet known.</li>
     *   <li>{@link #THROUGHPUT_RPS} — requests sent per second over the run's elapsed time.</li>
     *   <li>{@link #CHECK_FAILURE_RATE} — failed per-step {@link LoadCheck}s as a <b>fraction</b> of all
     *       evaluated checks, in (0..1); {@code 0} when no checks ran. Lets a CI run fail when too many
     *       response assertions break.</li>
     * </ul>
     */
    public enum Metric {
        LATENCY_P50,
        LATENCY_P95,
        LATENCY_P99,
        LATENCY_P999,
        ERROR_RATE,
        THROUGHPUT_RPS,
        CHECK_FAILURE_RATE
    }

    private Metric metric;
    private SloObjective.Comparator comparator;
    private double threshold;

    public static LoadThreshold loadThreshold() {
        return new LoadThreshold();
    }

    public Metric getMetric() {
        return metric;
    }

    public LoadThreshold withMetric(Metric metric) {
        this.metric = metric;
        return this;
    }

    public SloObjective.Comparator getComparator() {
        return comparator;
    }

    public LoadThreshold withComparator(SloObjective.Comparator comparator) {
        this.comparator = comparator;
        return this;
    }

    public double getThreshold() {
        return threshold;
    }

    public LoadThreshold withThreshold(double threshold) {
        this.threshold = threshold;
        return this;
    }

    /**
     * @return true when {@code observed} satisfies this threshold's {@link #comparator} against its
     * {@link #threshold} value, using the same comparison semantics as
     * {@link SloObjective#satisfiedBy(double)}.
     */
    public boolean satisfiedBy(double observed) {
        if (comparator == null) {
            return true;
        }
        return comparator.test(observed, threshold);
    }
}
