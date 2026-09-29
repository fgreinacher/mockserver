package org.mockserver.configuration;

import org.junit.Test;
import org.slf4j.event.Level;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Unit tests for the heap-based sizing of {@code maxLogEntries} / {@code maxExpectations} defaults.
 * <p>
 * The store-sizing budget is derived from the JVM heap <em>ceiling</em> ({@code -Xmx}) — a value fixed
 * for the JVM's lifetime — NOT from the momentary free heap. Deriving from free heap ({@code max - used})
 * made the default depend on whatever was in use at the moment of the first read; because the resolved
 * default is cached JVM-wide, an unrelated heavy fixture running before the first store was constructed
 * silently shrank the capacity of every store for the rest of the JVM (silent ring-buffer eviction, and a
 * later {@code verify} that stops finding what it should). Sizing off the fixed ceiling makes the default
 * deterministic and independent of allocation history. These tests lock that contract, plus the robustness
 * to an undefined JMX heap max (-1), e.g. in a GraalVM native image, and to heap pools that overlap
 * (generational ZGC), which must never be summed into the ceiling.
 * <p>
 * These tests exercise the pure package-private seams
 * ({@link ConfigurationProperties#computeHeapAvailableInKB(long, long)} and
 * {@link ConfigurationProperties#heapBasedDefaultOrFloor(long, long, int, int)}) so they do NOT mutate
 * global {@code ConfigurationProperties} state and can run in the parallel Surefire phase.
 */
// @ParallelStateGuardSuppress: only calls the pure static functions computeHeapAvailableInKB(...) and
// heapBasedDefaultOrFloor(...) (the guard's setter pattern false-positives on them), plus
// heapAvailableInKB(), which reads only the JVM; no global ConfigurationProperties state is read or mutated.
public class HeapAvailableSizingTest {

    private static final long BASE_KB = ConfigurationProperties.BASE_MEMORY_IN_KB; // 20 MB reservation

    // ----- computeHeapAvailableInKB: derives from the heap ceiling, not free heap -----

    @Test
    public void shouldComputeBudgetFromHeapCeilingWhenMaxDefined() {
        // given a normal HotSpot JVM: JMX and Runtime agree on a 512 MB heap ceiling
        long maxBytes = 512L * 1024 * 1024;

        // when
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(maxBytes, maxBytes);

        // then 512 MB / 1024 - 20 MB reservation (no dependence on used heap)
        long expected = (maxBytes / 1024L) - BASE_KB;
        assertThat(availableKB, is(expected));
        assertThat(availableKB, is(greaterThan(0L)));
    }

    @Test
    public void shouldNotDoubleTheCeilingWhenJmxReportsTheSumOfOverlappingGenerationalZgcPools() {
        // Generational ZGC (JDK 21 +ZGenerational, JDK 23+ +UseZGC) reports the full -Xmx as the max of
        // BOTH its young and old pools, so summing the heap pools gives 2x the real ceiling.
        long xmx = 1024L * 1024 * 1024;
        long summedZgcPools = xmx + xmx;

        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(summedZgcPools, xmx);

        assertThat(availableKB, is((xmx / 1024L) - BASE_KB));
        assertThat(availableKB, is(1_028_096L));
        assertThat(ConfigurationProperties.defaultMaxEventLogSizeInBytes(availableKB, Level.WARN), is(150_394_880L));
        assertThat(ConfigurationProperties.defaultMaxEventLogSizeInBytes(availableKB, Level.INFO), is(87_730_176L));
        assertThat(ConfigurationProperties.heapBasedDefaultOrFloor(availableKB, 8, 250000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES), is(128_512));
        // symmetric: whichever source over-reports, the smaller defined ceiling wins
        assertThat(ConfigurationProperties.computeHeapAvailableInKB(xmx, summedZgcPools), is(availableKB));
    }

    @Test
    public void shouldUseJmxCeilingWhenRuntimeMaxIsUndefined() {
        long jmxMax = 256L * 1024 * 1024;

        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(jmxMax, -1L);

        assertThat(availableKB, is((jmxMax / 1024L) - BASE_KB));
    }

    @Test
    public void shouldNeverExceedThisJvmsOwnHeapCeiling() {
        // Live check against the running JVM: whatever the collector, the budget must be derived from
        // (at most) Runtime.maxMemory(), never from a sum of heap pools that can exceed it. The JVM running
        // this test decides which collector is exercised; run with generational ZGC to cover that case.
        long runtimeCeilingKB = (Runtime.getRuntime().maxMemory() / 1024L) - BASE_KB;
        long toleranceKB = 1024L;

        long availableKB = ConfigurationProperties.heapAvailableInKB();

        assertThat(availableKB, is(greaterThan(0L)));
        assertThat(availableKB, is(lessThanOrEqualTo(runtimeCeilingKB + toleranceKB)));
        assertThat(availableKB, is(greaterThanOrEqualTo(runtimeCeilingKB - toleranceKB)));
    }

    @Test
    public void shouldBeIndependentOfAllocationHistory() {
        // The budget is a pure function of the ceiling. Whatever heap has been consumed before the call
        // (the old free-heap "used" term) can no longer change it: the same ceiling always yields the same
        // budget. This is the property that fixes the "an early fixture shrank every store" freeze — on the
        // old (max - used) formula these two calls modelled "clean start" vs "300 MB already consumed" and
        // returned different budgets; now they are identical.
        long ceiling = 1024L * 1024 * 1024; // 1 GB (-Xmx1g), the size the defect was reproduced at

        long budgetAtCleanStart = ConfigurationProperties.computeHeapAvailableInKB(ceiling, ceiling);
        long budgetAfterHeavyFixture = ConfigurationProperties.computeHeapAvailableInKB(ceiling, ceiling);

        assertThat(budgetAfterHeavyFixture, is(budgetAtCleanStart));
        assertThat(budgetAtCleanStart, is((ceiling / 1024L) - BASE_KB));

        // and the maxLogEntries default it feeds is identical regardless of history. At a 1 GB heap the
        // heap-derived value (budget / 8) sits below the 250,000 cap, so the default is heapKB / 8, and
        // the point of the test is that it does not vary with prior allocation.
        int cleanDefault = ConfigurationProperties.heapBasedDefaultOrFloor(budgetAtCleanStart, 8, 250000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);
        int fixtureDefault = ConfigurationProperties.heapBasedDefaultOrFloor(budgetAfterHeavyFixture, 8, 250000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);
        assertThat(fixtureDefault, is(cleanDefault));
        assertThat(cleanDefault, is((int) (budgetAtCleanStart / 8)));
    }

    @Test
    public void shouldFallBackToRuntimeCeilingWhenJmxMaxIsUndefinedMinusOne() {
        // given JMX reports max = -1 (undefined, as on a GraalVM native image)
        long runtimeMax = 256L * 1024 * 1024;

        // when
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(-1L, runtimeMax);

        // then it falls back to the Runtime ceiling and stays non-negative
        long expected = (runtimeMax / 1024L) - BASE_KB;
        assertThat(availableKB, is(expected));
        assertThat(availableKB, is(greaterThan(0L)));
    }

    @Test
    public void shouldFallBackToRuntimeCeilingWhenJmxMaxIsZero() {
        // given JMX reports max = 0 (also treated as undefined)
        long runtimeMax = 128L * 1024 * 1024;

        // when
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(0L, runtimeMax);

        // then
        long expected = (runtimeMax / 1024L) - BASE_KB;
        assertThat(availableKB, is(expected));
        assertThat(availableKB, is(greaterThan(0L)));
    }

    @Test
    public void shouldReturnZeroWhenBothJmxAndRuntimeMaxUndefined() {
        // when neither JMX nor Runtime provide a usable ceiling
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(-1L, -1L);

        // then the result is floored at zero rather than going negative
        assertThat(availableKB, is(0L));
    }

    @Test
    public void shouldNeverReturnNegativeWhenCeilingIsWithinBaseReservation() {
        // given a heap ceiling smaller than the 20 MB reservation, so the subtraction would go negative
        long maxBytes = 10L * 1024 * 1024;

        // when
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(maxBytes, maxBytes);

        // then floored at zero (10 MB ceiling - 20 MB reservation would be negative)
        assertThat(availableKB, is(0L));
    }

    @Test
    public void shouldStaySaneWhenRuntimeMaxIsUnbounded() {
        // given Runtime.maxMemory() == Long.MAX_VALUE (unbounded heap) via the JMX-undefined fallback path
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(-1L, Long.MAX_VALUE);

        // then it is a large positive value (clamped by callers' Math.min), not an overflow to negative
        assertThat(availableKB, is(greaterThan(0L)));
    }

    // ----- heapBasedDefaultOrFloor (unchanged behaviour: floors a <= 0 heap-derived value) -----

    @Test
    public void shouldFloorMaxExpectationsAtDevDefaultWhenHeapAvailableIsZero() {
        // given heapAvailableInKB computed to 0 (JMX max undefined)
        int value = ConfigurationProperties.heapBasedDefaultOrFloor(0L, 10, 15000, ConfigurationProperties.DEV_MODE_MAX_EXPECTATIONS);

        // then the store stays functional at the dev-mode default rather than collapsing to <= 0
        assertThat(value, is(ConfigurationProperties.DEV_MODE_MAX_EXPECTATIONS));
        assertThat(value, is(greaterThan(0)));
    }

    @Test
    public void shouldFloorMaxLogEntriesAtDevDefaultWhenHeapAvailableIsZero() {
        int value = ConfigurationProperties.heapBasedDefaultOrFloor(0L, 8, 250000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);

        assertThat(value, is(ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES));
        assertThat(value, is(greaterThan(0)));
    }

    @Test
    public void shouldComputeHeapBasedExpectationsDefaultWhenHeapAvailable() {
        // 100,000 KB / 10 = 10,000, below the 15,000 cap
        int value = ConfigurationProperties.heapBasedDefaultOrFloor(100000L, 10, 15000, ConfigurationProperties.DEV_MODE_MAX_EXPECTATIONS);

        assertThat(value, is(10000));
    }

    @Test
    public void shouldCapHeapBasedExpectationsDefault() {
        // huge heap -> capped at 15,000
        int value = ConfigurationProperties.heapBasedDefaultOrFloor(10_000_000L, 10, 15000, ConfigurationProperties.DEV_MODE_MAX_EXPECTATIONS);

        assertThat(value, is(15000));
    }

    @Test
    public void shouldCapHeapBasedLogEntriesDefault() {
        // huge heap -> capped at 250,000
        int value = ConfigurationProperties.heapBasedDefaultOrFloor(100_000_000L, 8, 250000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);

        assertThat(value, is(250000));
    }

    @Test
    public void shouldCrossOverToTheLogEntriesCapJustAboveTwoMillionKb() {
        // The 250,000 cap is reached where heapKB / 8 = 250,000, i.e. a 2,000,000 KB budget. Just below the
        // crossover the default is the heap-derived value; just above it is pinned at the cap.
        int justBelow = ConfigurationProperties.heapBasedDefaultOrFloor(1_999_992L, 8, 250000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);
        int justAbove = ConfigurationProperties.heapBasedDefaultOrFloor(2_000_008L, 8, 250000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);

        assertThat(justBelow, is(249999));
        assertThat(justAbove, is(250000));
    }

    // ----- defaultMaxEventLogSizeInBytes: byte budget is a log-level-aware fraction of the ceiling -----

    @Test
    public void shouldDeriveDefaultEventLogByteBudgetAsASeventhOfTheCeilingAtNonRenderingLevel() {
        // 200,000 KB available -> a seventh is 28,571 KB -> 29,256,704 counted bytes.
        long value = ConfigurationProperties.defaultMaxEventLogSizeInBytes(200000L, Level.WARN);

        assertThat(value, is((200000L / 7) * 1024L));
        assertThat(value, is(29_256_704L));
    }

    @Test
    public void shouldTightenDefaultEventLogByteBudgetToATwelfthAtRenderingLevel() {
        // INFO renders every entry and memoises the formatted message on it, which the weigher does not
        // count, so real heap per counted byte is larger at INFO. 200,000 KB -> a twelfth is 16,666 KB.
        long info = ConfigurationProperties.defaultMaxEventLogSizeInBytes(200000L, Level.INFO);
        long warn = ConfigurationProperties.defaultMaxEventLogSizeInBytes(200000L, Level.WARN);

        assertThat(info, is((200000L / 12) * 1024L));
        assertThat(info, is(17_065_984L));
        assertThat(info, is(lessThan(warn)));
        assertThat((double) warn / info, is(closeTo(12d / 7d, 0.01d)));
    }

    @Test
    public void shouldKeepWorstMeasuredWholeLogAtAboutAQuarterOfTheCeilingAtEveryLevel() {
        // The same budget bounds the retained deque AND the in-flight ring backlog, and both fill at once
        // while the consumer lags, so the whole log holds (k_deque + k_ring) x budget. Composite bound: the
        // largest measured k_deque plus the largest k_ring at each level, from different runs
        // (docs/code/memory-management.md, Validation at the current divisors).
        double worstWarnWholeLogMultiple = 0.974d + 0.704d;
        double worstInfoWholeLogMultiple = 2.314d + 0.724d;
        for (long heapAvailableInKB : new long[]{45_056L, 241_664L, 1_028_096L, 4_173_824L}) {
            double ceilingBytes = heapAvailableInKB * 1024d;
            for (Level level : new Level[]{Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, null}) {
                double multiple = ConfigurationProperties.rendersEveryLogEntry(level) ? worstInfoWholeLogMultiple : worstWarnWholeLogMultiple;
                double wholeLogShare = multiple * ConfigurationProperties.defaultMaxEventLogSizeInBytes(heapAvailableInKB, level) / ceilingBytes;
                assertThat("heap " + heapAvailableInKB + " KB at " + level, wholeLogShare, is(lessThanOrEqualTo(0.255d)));
                assertThat("heap " + heapAvailableInKB + " KB at " + level, wholeLogShare, is(greaterThan(0.20d)));
            }
        }
    }

    @Test
    public void shouldTreatDebugAndTraceAsRenderingLevelsAndErrorAndOffAsNonRendering() {
        assertThat(ConfigurationProperties.rendersEveryLogEntry(Level.TRACE), is(true));
        assertThat(ConfigurationProperties.rendersEveryLogEntry(Level.DEBUG), is(true));
        assertThat(ConfigurationProperties.rendersEveryLogEntry(Level.INFO), is(true));
        assertThat(ConfigurationProperties.rendersEveryLogEntry(Level.WARN), is(false));
        assertThat(ConfigurationProperties.rendersEveryLogEntry(Level.ERROR), is(false));
        // logLevel() returns null when the level is OFF — treated as non-rendering (nothing is rendered)
        assertThat(ConfigurationProperties.rendersEveryLogEntry(null), is(false));

        assertThat(ConfigurationProperties.defaultMaxEventLogSizeInBytes(200000L, Level.DEBUG), is((200000L / 12) * 1024L));
        assertThat(ConfigurationProperties.defaultMaxEventLogSizeInBytes(200000L, Level.ERROR), is((200000L / 7) * 1024L));
        assertThat(ConfigurationProperties.defaultMaxEventLogSizeInBytes(200000L, null), is((200000L / 7) * 1024L));
    }

    @Test
    public void shouldDisableDefaultEventLogByteBudgetWhenHeapCeilingUndefined() {
        // heapAvailableInKB == 0 (JMX + Runtime max undefined, e.g. a GraalVM native image) -> byte
        // budget disabled (0) at every level, falling back to the maxLogEntries count cap rather than
        // an arbitrary size
        assertThat(ConfigurationProperties.defaultMaxEventLogSizeInBytes(0L, Level.INFO), is(0L));
        assertThat(ConfigurationProperties.defaultMaxEventLogSizeInBytes(0L, Level.WARN), is(0L));
    }

    @Test
    public void shouldScaleDefaultEventLogByteBudgetWithTheHeapCeiling() {
        // the byte budget is deterministic in the ceiling and monotonic — a larger ceiling never
        // yields a smaller default (checked at both a rendering and a non-rendering level)
        long smallHeapInfo = ConfigurationProperties.defaultMaxEventLogSizeInBytes(500_000L, Level.INFO);
        long largeHeapInfo = ConfigurationProperties.defaultMaxEventLogSizeInBytes(4_000_000L, Level.INFO);
        long smallHeapWarn = ConfigurationProperties.defaultMaxEventLogSizeInBytes(500_000L, Level.WARN);
        long largeHeapWarn = ConfigurationProperties.defaultMaxEventLogSizeInBytes(4_000_000L, Level.WARN);

        assertThat(smallHeapInfo, is((500_000L / 12) * 1024L));
        assertThat(largeHeapInfo, is((4_000_000L / 12) * 1024L));
        assertThat(largeHeapInfo > smallHeapInfo, is(true));
        assertThat(smallHeapWarn, is((500_000L / 7) * 1024L));
        assertThat(largeHeapWarn, is((4_000_000L / 7) * 1024L));
        assertThat(largeHeapWarn > smallHeapWarn, is(true));
    }
}
