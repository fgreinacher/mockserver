package org.mockserver.configuration;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

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
 * to an undefined JMX heap-pool max (-1), e.g. in a GraalVM native image.
 * <p>
 * These tests exercise the pure package-private seams
 * ({@link ConfigurationProperties#computeHeapAvailableInKB(long, long)} and
 * {@link ConfigurationProperties#heapBasedDefaultOrFloor(long, long, int, int)}) so they do NOT mutate
 * global {@code ConfigurationProperties} state and can run in the parallel Surefire phase.
 */
// @ParallelStateGuardSuppress: only calls the pure static functions computeHeapAvailableInKB(...) and
// heapBasedDefaultOrFloor(...) (the guard's setter pattern false-positives on them); no global
// ConfigurationProperties state is read or mutated.
public class HeapAvailableSizingTest {

    private static final long BASE_KB = ConfigurationProperties.BASE_MEMORY_IN_KB; // 20 MB reservation

    // ----- computeHeapAvailableInKB: derives from the heap ceiling, not free heap -----

    @Test
    public void shouldComputeBudgetFromJmxCeilingWhenMaxDefined() {
        // given a normal JVM: 512 MB heap ceiling (the Runtime fallback must be ignored when JMX is usable)
        long maxBytes = 512L * 1024 * 1024;

        // when
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(maxBytes, 1L);

        // then it uses the JMX ceiling: 512 MB / 1024 - 20 MB reservation (no dependence on used heap)
        long expected = (maxBytes / 1024L) - BASE_KB;
        assertThat(availableKB, is(expected));
        assertThat(availableKB, is(greaterThan(0L)));
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

        // and the maxLogEntries default it feeds is pinned at the cap for a 1 GB heap regardless of history
        int cleanDefault = ConfigurationProperties.heapBasedDefaultOrFloor(budgetAtCleanStart, 8, 100000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);
        int fixtureDefault = ConfigurationProperties.heapBasedDefaultOrFloor(budgetAfterHeavyFixture, 8, 100000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);
        assertThat(fixtureDefault, is(cleanDefault));
        assertThat(cleanDefault, is(100000));
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
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(maxBytes, 1L);

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
        int value = ConfigurationProperties.heapBasedDefaultOrFloor(0L, 8, 100000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);

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
        // huge heap -> capped at 100,000
        int value = ConfigurationProperties.heapBasedDefaultOrFloor(100_000_000L, 8, 100000, ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES);

        assertThat(value, is(100000));
    }
}
