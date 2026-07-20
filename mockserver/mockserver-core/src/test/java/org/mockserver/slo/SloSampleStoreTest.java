package org.mockserver.slo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Behavioural tests for {@link SloSampleStore}: recording is gated by
 * {@code sloTrackingEnabled}, samples are bounded by count and age, window/scope/host
 * filters select the right subset, the error-rate counts are correct, and the
 * percentile selection matches a brute-force oracle.
 *
 * <p>State-mutating: it flips the static {@code sloTrackingEnabled} /
 * {@code sloWindowMaxSamples} / {@code sloWindowRetentionMillis} properties and the
 * process-wide {@link SloSampleStore} singleton, so it must run in the sequential
 * Surefire phase.
 */
public class SloSampleStoreTest {

    private final SloSampleStore store = SloSampleStore.getInstance();

    @Before
    public void setUp() {
        store.reset();
        ConfigurationProperties.sloTrackingEnabled(true);
        ConfigurationProperties.sloWindowMaxSamples(50_000);
        ConfigurationProperties.sloWindowRetentionMillis(600_000L);
    }

    @After
    public void tearDown() {
        store.reset();
        ConfigurationProperties.sloTrackingEnabled(false);
        ConfigurationProperties.sloWindowMaxSamples(50_000);
        ConfigurationProperties.sloWindowRetentionMillis(600_000L);
    }

    @Test
    public void shouldNotRecordWhenTrackingDisabled() {
        ConfigurationProperties.sloTrackingEnabled(false);
        store.record(1000L, 10L, false, Scope.FORWARD, "a.svc");
        assertThat(store.size(), is(0));
    }

    @Test
    public void shouldRecordWhenTrackingEnabled() {
        store.record(1000L, 10L, false, Scope.FORWARD, "a.svc");
        assertThat(store.size(), is(1));
    }

    @Test
    public void shouldReturnLatenciesAscendingWithinWindow() {
        store.record(1000L, 30L, false, Scope.FORWARD, "a.svc");
        store.record(1100L, 10L, false, Scope.FORWARD, "a.svc");
        store.record(1200L, 20L, false, Scope.FORWARD, "a.svc");
        long[] latencies = store.latenciesInWindow(0L, 2000L, Scope.FORWARD, null);
        assertThat(latencies, is(new long[]{10L, 20L, 30L}));
    }

    @Test
    public void shouldExcludeSamplesOutsideWindow() {
        store.record(500L, 5L, false, Scope.FORWARD, "a.svc");
        store.record(1500L, 15L, false, Scope.FORWARD, "a.svc");
        store.record(2500L, 25L, false, Scope.FORWARD, "a.svc");
        long[] latencies = store.latenciesInWindow(1000L, 2000L, Scope.FORWARD, null);
        assertThat(latencies, is(new long[]{15L}));
    }

    @Test
    public void shouldFilterByScope() {
        store.record(1000L, 10L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 20L, false, Scope.INBOUND, "a.svc");
        assertThat(store.latenciesInWindow(0L, 2000L, Scope.FORWARD, null), is(new long[]{10L}));
        assertThat(store.latenciesInWindow(0L, 2000L, Scope.INBOUND, null), is(new long[]{20L}));
    }

    @Test
    public void shouldFilterByHost() {
        store.record(1000L, 10L, false, Scope.FORWARD, "a.svc");
        store.record(1000L, 20L, false, Scope.FORWARD, "b.svc");
        long[] aOnly = store.latenciesInWindow(0L, 2000L, Scope.FORWARD, Collections.singleton("a.svc"));
        assertThat(aOnly, is(new long[]{10L}));
        long[] both = store.latenciesInWindow(0L, 2000L, Scope.FORWARD, null);
        assertThat(both, is(new long[]{10L, 20L}));
    }

    @Test
    public void shouldCountErrorsAndTotalInWindow() {
        store.record(1000L, 10L, false, Scope.FORWARD, "a.svc");
        store.record(1100L, 10L, true, Scope.FORWARD, "a.svc");
        store.record(1200L, 10L, true, Scope.FORWARD, "a.svc");
        store.record(5000L, 10L, true, Scope.FORWARD, "a.svc"); // outside window
        SloSampleStore.ErrorCounts counts = store.errorCountsInWindow(0L, 2000L, Scope.FORWARD, null);
        assertThat(counts.getErrors(), is(2L));
        assertThat(counts.getTotal(), is(3L));
    }

    @Test
    public void shouldEvictOldestBeyondCountBound() {
        ConfigurationProperties.sloWindowMaxSamples(3);
        store.record(1000L, 1L, false, Scope.FORWARD, "a.svc");
        store.record(1001L, 2L, false, Scope.FORWARD, "a.svc");
        store.record(1002L, 3L, false, Scope.FORWARD, "a.svc");
        store.record(1003L, 4L, false, Scope.FORWARD, "a.svc"); // evicts latency 1
        assertThat(store.size(), is(3));
        assertThat(store.latenciesInWindow(0L, 2000L, Scope.FORWARD, null), is(new long[]{2L, 3L, 4L}));
    }

    @Test
    public void shouldEvictSamplesOlderThanRetentionRelativeToNewest() {
        ConfigurationProperties.sloWindowRetentionMillis(100L);
        store.record(1000L, 1L, false, Scope.FORWARD, "a.svc");
        store.record(1050L, 2L, false, Scope.FORWARD, "a.svc");
        // newest at 1200; cutoff = 1200 - 100 = 1100; both prior samples are older
        store.record(1200L, 3L, false, Scope.FORWARD, "a.svc");
        assertThat(store.size(), is(1));
        assertThat(store.latenciesInWindow(0L, 2000L, Scope.FORWARD, null), is(new long[]{3L}));
    }

    @Test
    public void shouldReturnNullPercentileForEmptyLatencies() {
        assertThat(SloSampleStore.percentile(new long[0], 95), is(nullValue()));
        assertThat(SloSampleStore.percentile(null, 95), is(nullValue()));
    }

    @Test
    public void shouldComputePercentileToHandCalculatedNearestRankValues() {
        // Independent oracle: hand-computed nearest-rank percentiles, pinning the
        // DEFINITION (not the implementation formula). Nearest-rank picks the value
        // at ceil(p/100 * n) (1-based), i.e. index ceil(p/100 * n) - 1 (0-based).
        long[] four = {10L, 20L, 30L, 40L};      // n = 4
        assertThat("p1",   SloSampleStore.percentile(four, 1),   is(10.0)); // rank ceil(.04)=1 -> 10
        assertThat("p25",  SloSampleStore.percentile(four, 25),  is(10.0)); // rank ceil(1.0)=1 -> 10
        assertThat("p50",  SloSampleStore.percentile(four, 50),  is(20.0)); // rank ceil(2.0)=2 -> 20
        assertThat("p75",  SloSampleStore.percentile(four, 75),  is(30.0)); // rank ceil(3.0)=3 -> 30
        assertThat("p95",  SloSampleStore.percentile(four, 95),  is(40.0)); // rank ceil(3.8)=4 -> 40
        assertThat("p99",  SloSampleStore.percentile(four, 99),  is(40.0)); // rank ceil(3.96)=4 -> 40
        assertThat("p100", SloSampleStore.percentile(four, 100), is(40.0)); // rank 4 -> 40

        long[] ten = {10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L}; // n = 10
        assertThat("p25/10",  SloSampleStore.percentile(ten, 25),  is(30.0));  // rank ceil(2.5)=3 -> 30
        assertThat("p50/10",  SloSampleStore.percentile(ten, 50),  is(50.0));  // rank ceil(5.0)=5 -> 50
        assertThat("p90/10",  SloSampleStore.percentile(ten, 90),  is(90.0));  // rank ceil(9.0)=9 -> 90
        assertThat("p95/10",  SloSampleStore.percentile(ten, 95),  is(100.0)); // rank ceil(9.5)=10 -> 100
        assertThat("p99/10",  SloSampleStore.percentile(ten, 99),  is(100.0)); // rank ceil(9.9)=10 -> 100

        // Single sample: every percentile is that sample.
        assertThat("p50/1", SloSampleStore.percentile(new long[]{7L}, 50), is(7.0));
        assertThat("p99/1", SloSampleStore.percentile(new long[]{7L}, 99), is(7.0));
    }

    @Test
    public void shouldReturnEmptyForNoMatchingSamples() {
        store.record(1000L, 10L, false, Scope.FORWARD, "a.svc");
        assertThat(store.latenciesInWindow(5000L, 6000L, Scope.FORWARD, null).length, is(0));
        SloSampleStore.ErrorCounts counts = store.errorCountsInWindow(5000L, 6000L, Scope.FORWARD, null);
        assertThat(counts.getTotal(), is(0L));
    }

    // ---------------------------------------------------------------------
    // Configuration-instance precedence: a value set on a Configuration instance
    // (as PUT /mockserver/configuration does) must change observable behaviour,
    // not be silently ignored in favour of the static ConfigurationProperties store.
    // ---------------------------------------------------------------------

    @Test
    public void shouldNotRecordWhenTrackingDisabledOnConfigurationInstance() {
        // given - the STATIC store says tracking is on
        ConfigurationProperties.sloTrackingEnabled(true);
        // but the instance says off, as a PUT /mockserver/configuration would
        Configuration configuration = Configuration.configuration().sloTrackingEnabled(false);

        store.record(configuration, 1000L, 10L, false, Scope.FORWARD, "a.svc");

        assertThat("instance value must win over the static store", store.size(), is(0));
    }

    @Test
    public void shouldRecordWhenTrackingEnabledOnConfigurationInstance() {
        // given - the STATIC store says tracking is off
        ConfigurationProperties.sloTrackingEnabled(false);
        // but the instance says on
        Configuration configuration = Configuration.configuration().sloTrackingEnabled(true);

        store.record(configuration, 1000L, 10L, false, Scope.FORWARD, "a.svc");

        assertThat("instance value must win over the static store", store.size(), is(1));
    }

    @Test
    public void shouldBoundSamplesByMaxSamplesFromConfigurationInstance() {
        // given - a generous static bound but a tight instance bound
        ConfigurationProperties.sloWindowMaxSamples(50_000);
        Configuration configuration = Configuration.configuration()
            .sloTrackingEnabled(true)
            .sloWindowMaxSamples(2);

        for (int i = 0; i < 5; i++) {
            store.record(configuration, 1000L + i, 10L, false, Scope.FORWARD, "a.svc");
        }

        assertThat("instance max-samples bound must be applied", store.size(), is(2));
    }

    @Test
    public void shouldEvictByRetentionFromConfigurationInstance() {
        // given - a generous static retention but a tight instance retention
        ConfigurationProperties.sloWindowRetentionMillis(600_000L);
        Configuration configuration = Configuration.configuration()
            .sloTrackingEnabled(true)
            .sloWindowRetentionMillis(100L);

        store.record(configuration, 1_000L, 10L, false, Scope.FORWARD, "a.svc");
        // 500ms later - beyond the 100ms instance retention, well inside the 600s static one
        store.record(configuration, 1_500L, 10L, false, Scope.FORWARD, "a.svc");

        assertThat("instance retention must evict the older sample", store.size(), is(1));
    }

    @Test
    public void shouldFallBackToStaticStoreWhenConfigurationIsNull() {
        // given - no instance supplied, so system-property/env/file users are unaffected
        ConfigurationProperties.sloTrackingEnabled(false);
        store.record(null, 1000L, 10L, false, Scope.FORWARD, "a.svc");
        assertThat(store.size(), is(0));

        ConfigurationProperties.sloTrackingEnabled(true);
        store.record(null, 1000L, 10L, false, Scope.FORWARD, "a.svc");
        assertThat(store.size(), is(1));
    }
}
