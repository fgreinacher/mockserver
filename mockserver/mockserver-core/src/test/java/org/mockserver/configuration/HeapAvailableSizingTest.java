package org.mockserver.configuration;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Unit tests for the heap-based sizing of {@code maxLogEntries} / {@code maxExpectations} defaults,
 * focused on robustness when the JMX heap-pool max is undefined (-1), e.g. in a GraalVM native image.
 * <p>
 * These tests exercise the pure package-private seams
 * ({@link ConfigurationProperties#computeHeapAvailableInKB(long, long, long, long)} and
 * {@link ConfigurationProperties#heapBasedDefaultOrFloor(long, long, int, int)}) so they do NOT mutate
 * global {@code ConfigurationProperties} state and can run in the parallel Surefire phase.
 */
// @ParallelStateGuardSuppress: only calls the pure static functions computeHeapAvailableInKB(...) and
// heapBasedDefaultOrFloor(...) (the guard's setter pattern false-positives on them); no global
// ConfigurationProperties state is read or mutated.
public class HeapAvailableSizingTest {

    private static final long BASE_KB = ConfigurationProperties.BASE_MEMORY_IN_KB; // 20 MB reservation

    // ----- computeHeapAvailableInKB -----

    @Test
    public void shouldComputeHeapAvailableFromJmxWhenMaxDefined() {
        // given a normal JVM: 512 MB max, 100 MB used (Runtime values must be ignored)
        long maxBytes = 512L * 1024 * 1024;
        long usedBytes = 100L * 1024 * 1024;

        // when
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(maxBytes, usedBytes, 1L, 1L);

        // then it uses the JMX figures: (512 MB - 100 MB) / 1024 - 20 MB reservation
        long expected = ((maxBytes - usedBytes) / 1024L) - BASE_KB;
        assertThat(availableKB, is(expected));
        assertThat(availableKB, is(greaterThan(0L)));
    }

    @Test
    public void shouldFallBackToRuntimeWhenJmxMaxIsUndefinedMinusOne() {
        // given JMX reports max = -1 (undefined, as on a GraalVM native image); used is still a
        // real positive value in that case (the JMX spec only allows -1 for max)
        long jmxUsed = 200L * 1024 * 1024;
        long runtimeMax = 256L * 1024 * 1024;
        long runtimeUsed = 40L * 1024 * 1024;

        // when
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(-1L, jmxUsed, runtimeMax, runtimeUsed);

        // then it falls back to the Runtime figures and stays non-negative
        long expected = ((runtimeMax - runtimeUsed) / 1024L) - BASE_KB;
        assertThat(availableKB, is(expected));
        assertThat(availableKB, is(greaterThan(0L)));
    }

    @Test
    public void shouldFallBackToRuntimeWhenJmxMaxIsZero() {
        // given JMX reports max = 0 (also treated as undefined)
        long runtimeMax = 128L * 1024 * 1024;
        long runtimeUsed = 10L * 1024 * 1024;

        // when
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(0L, 0L, runtimeMax, runtimeUsed);

        // then
        long expected = ((runtimeMax - runtimeUsed) / 1024L) - BASE_KB;
        assertThat(availableKB, is(expected));
        assertThat(availableKB, is(greaterThan(0L)));
    }

    @Test
    public void shouldReturnZeroWhenBothJmxAndRuntimeMaxUndefined() {
        // when neither JMX nor Runtime provide a usable ceiling
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(-1L, 0L, -1L, 0L);

        // then the result is floored at zero rather than going negative
        assertThat(availableKB, is(0L));
    }

    @Test
    public void shouldNeverReturnNegativeWhenUsedExceedsMax() {
        // given used > max (e.g. tiny reported max, larger used) — subtraction would go negative
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(10L * 1024 * 1024, 500L * 1024 * 1024, 1L, 1L);

        // then floored at zero
        assertThat(availableKB, is(0L));
    }

    @Test
    public void shouldNeverReturnNegativeWhenMaxIsWithinBaseReservation() {
        // given a max only slightly above used, so the 20 MB reservation would push the result negative
        long maxBytes = 21L * 1024 * 1024;
        long usedBytes = 20L * 1024 * 1024;

        // when
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(maxBytes, usedBytes, 1L, 1L);

        // then floored at zero (1 MB free - 20 MB reservation would be negative)
        assertThat(availableKB, is(0L));
    }

    @Test
    public void shouldStaySaneWhenRuntimeMaxIsUnbounded() {
        // given Runtime.maxMemory() == Long.MAX_VALUE (unbounded heap) via the JMX-undefined fallback path
        long availableKB = ConfigurationProperties.computeHeapAvailableInKB(-1L, -1L, Long.MAX_VALUE, 0L);

        // then it is a large positive value (clamped by callers' Math.min), not an overflow to negative
        assertThat(availableKB, is(greaterThan(0L)));
    }

    // ----- heapBasedDefaultOrFloor -----

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
