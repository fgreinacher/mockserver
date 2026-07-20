package org.mockserver.test;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;

/**
 * Proves the wrapper converts a THROWING probe into {@code false} rather than propagating.
 * <p>
 * The throwing cases are the point of this class. A probe that merely returns {@code false}
 * would pass with or without the wrapper, so only the throwing cases discriminate — they are
 * the ones that previously turned "no usable Docker" into a hard ERROR in every Docker-gated
 * suite. The exception types below mirror what Testcontainers actually throws: a plain
 * {@code RuntimeException} (docker-java's {@code BadRequestException} is one) and an
 * {@link Error} (a broken test classpath surfaces as {@link NoClassDefFoundError}).
 */
public class DockerAvailabilityTest {

    @Before
    public void resetCache() {
        DockerAvailability.resetCacheForTest();
    }

    @Test
    public void returnsTrueWhenProbeReportsAvailable() {
        assertThat(DockerAvailability.isAvailable(() -> true), is(true));
    }

    @Test
    public void returnsFalseWhenProbeReportsUnavailable() {
        assertThat(DockerAvailability.isAvailable(() -> false), is(false));
    }

    @Test
    public void returnsFalseWhenProbeThrowsRuntimeException() {
        // Stands in for docker-java BadRequestException ("privileged mode is incompatible with
        // user namespaces"), which is a DockerException and so a plain RuntimeException.
        assertThat(
            DockerAvailability.isAvailable(() -> {
                throw new IllegalArgumentException("privileged mode is incompatible with user namespaces");
            }),
            is(false)
        );
    }

    @Test
    public void returnsFalseWhenProbeThrowsError() {
        // Stands in for a broken/incomplete test classpath, which surfaces as an Error and would
        // otherwise escape any catch(Exception)-based guard.
        assertThat(
            DockerAvailability.isAvailable(() -> {
                throw new NoClassDefFoundError("org/junit/rules/TestRule");
            }),
            is(false)
        );
    }

    @Test
    public void rethrowsJvmFatalErrorsRatherThanReportingUnavailable() {
        // A VirtualMachineError must NOT be disguised as "Docker unavailable" — that would hide a
        // real failure behind a skip.
        try {
            DockerAvailability.isAvailable(() -> {
                throw new OutOfMemoryError("test");
            });
            fail("expected OutOfMemoryError to propagate");
        } catch (OutOfMemoryError expected) {
            assertThat(expected.getMessage(), is("test"));
        }
    }

    @Test
    public void cachesTheFirstResultSoAFailingProbeIsPaidOnce() {
        int[] calls = {0};
        BooleanSupplierCounter probe = new BooleanSupplierCounter(calls);

        assertThat(DockerAvailability.isAvailable(probe), is(false));
        assertThat(DockerAvailability.isAvailable(probe), is(false));

        assertThat("probe should be invoked once and the result cached", calls[0], is(1));
    }

    /** A probe that counts invocations and always throws. */
    private static final class BooleanSupplierCounter implements java.util.function.BooleanSupplier {
        private final int[] calls;

        private BooleanSupplierCounter(int[] calls) {
            this.calls = calls;
        }

        @Override
        public boolean getAsBoolean() {
            calls[0]++;
            throw new IllegalStateException("no docker");
        }
    }
}
