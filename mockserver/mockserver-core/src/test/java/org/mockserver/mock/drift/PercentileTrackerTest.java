package org.mockserver.mock.drift;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class PercentileTrackerTest {

    @Test
    public void returnsZeroForUnknownExpectation() {
        PercentileTracker tracker = new PercentileTracker(100);
        assertThat(tracker.p50("nonexistent"), is(0L));
        assertThat(tracker.p95("nonexistent"), is(0L));
        assertThat(tracker.count("nonexistent"), is(0));
    }

    @Test
    public void recordsAndRetrievesP50AndP95() {
        PercentileTracker tracker = new PercentileTracker(100);
        // Record 100 values: 1, 2, 3, ..., 100
        for (int i = 1; i <= 100; i++) {
            tracker.record("exp1", i);
        }

        assertThat(tracker.count("exp1"), is(100));
        // p50 of 1..100 = 50th value = 50
        assertThat(tracker.p50("exp1"), is(50L));
        // p95 of 1..100 = 95th value = 95
        assertThat(tracker.p95("exp1"), is(95L));
    }

    @Test
    public void singleRecordReturnsSameForP50AndP95() {
        PercentileTracker tracker = new PercentileTracker(100);
        tracker.record("exp1", 42);

        assertThat(tracker.count("exp1"), is(1));
        assertThat(tracker.p50("exp1"), is(42L));
        assertThat(tracker.p95("exp1"), is(42L));
    }

    @Test
    public void slidingWindowEvictsOldValues() {
        PercentileTracker tracker = new PercentileTracker(5);
        // Fill window with 10, 20, 30, 40, 50
        tracker.record("exp1", 10);
        tracker.record("exp1", 20);
        tracker.record("exp1", 30);
        tracker.record("exp1", 40);
        tracker.record("exp1", 50);
        // Now overwrite first slot with 100 (circular: slot 0)
        tracker.record("exp1", 100);

        // Window should now contain: 100, 20, 30, 40, 50
        // Sorted: 20, 30, 40, 50, 100
        // Count is min(6, 5) = 5
        assertThat(tracker.count("exp1"), is(5));
        // p50 = ceil(0.5*5)-1 = 2 -> sorted[2] = 40
        assertThat(tracker.p50("exp1"), is(40L));
        // p95 = ceil(0.95*5)-1 = 4 -> sorted[4] = 100
        assertThat(tracker.p95("exp1"), is(100L));
    }

    @Test
    public void clearResetsState() {
        PercentileTracker tracker = new PercentileTracker(100);
        tracker.record("exp1", 42);
        tracker.record("exp2", 99);

        tracker.clear();

        assertThat(tracker.p50("exp1"), is(0L));
        assertThat(tracker.p95("exp2"), is(0L));
        assertThat(tracker.count("exp1"), is(0));
        assertThat(tracker.count("exp2"), is(0));
    }

    @Test
    public void tracksMultipleExpectationsIndependently() {
        PercentileTracker tracker = new PercentileTracker(100);
        for (int i = 1; i <= 10; i++) {
            tracker.record("fast", i);
        }
        for (int i = 91; i <= 100; i++) {
            tracker.record("slow", i);
        }

        assertThat(tracker.count("fast"), is(10));
        assertThat(tracker.count("slow"), is(10));
        // fast p50 should be <= 5, slow p50 should be >= 95
        assertThat(tracker.p50("fast"), is(5L));
        assertThat(tracker.p50("slow"), is(95L));
    }

    @Test
    public void removeDropsOnlyThatExpectationsWindow() {
        PercentileTracker tracker = new PercentileTracker(100);
        tracker.record("removed", 42);
        tracker.record("kept", 7);

        tracker.remove("removed");

        assertThat(tracker.count("removed"), is(0));
        assertThat(tracker.p95("removed"), is(0L));
        assertThat(tracker.count("kept"), is(1));
        assertThat(tracker.trackedExpectations(), is(1));
    }

    @Test
    public void countSaturatesAtTheWindowAndKeepsTheLatestSamplesAcrossManyWraps() {
        PercentileTracker tracker = new PercentileTracker(5);
        for (int i = 1; i <= 1_000_003; i++) {
            tracker.record("exp1", i);
        }

        // the window holds the 5 most recent samples: 999_999..1_000_003
        assertThat(tracker.count("exp1"), is(5));
        assertThat(tracker.p50("exp1"), is(1_000_001L));
        assertThat(tracker.p95("exp1"), is(1_000_003L));
    }

    @Test
    public void trackedExpectationsAreCappedSoOrphanedWindowsCannotGrowWithoutBound() {
        PercentileTracker tracker = new PercentileTracker(10, 3);
        for (int i = 0; i < 50; i++) {
            tracker.record("exp" + i, i);
        }

        assertThat(tracker.trackedExpectations(), is(3));
        // the newest id is always recorded
        assertThat(tracker.count("exp49"), is(1));
    }

    @Test
    public void nullExpectationIdIsIgnored() {
        PercentileTracker tracker = new PercentileTracker(10);
        tracker.record(null, 5);
        tracker.remove(null);

        assertThat(tracker.trackedExpectations(), is(0));
        assertThat(tracker.count(null), is(0));
    }
}
