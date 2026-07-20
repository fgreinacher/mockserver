package org.mockserver.grpc;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Parsing of the gRPC {@code grpc-timeout} request header.
 */
public class GrpcTimeoutTest {

    @Test
    public void shouldParseEveryUnit() {
        assertThat(GrpcTimeout.parseNanos("1H"), is(TimeUnit.HOURS.toNanos(1)));
        assertThat(GrpcTimeout.parseNanos("2M"), is(TimeUnit.MINUTES.toNanos(2)));
        assertThat(GrpcTimeout.parseNanos("3S"), is(TimeUnit.SECONDS.toNanos(3)));
        assertThat(GrpcTimeout.parseNanos("100m"), is(TimeUnit.MILLISECONDS.toNanos(100)));
        assertThat(GrpcTimeout.parseNanos("250u"), is(TimeUnit.MICROSECONDS.toNanos(250)));
        assertThat(GrpcTimeout.parseNanos("500n"), is(500L));
    }

    /**
     * The units are case-sensitive and {@code M} vs {@code m} differ by a factor of 60,000 —
     * a case-insensitive parse would turn a 100ms deadline into a 100-minute one.
     */
    @Test
    public void shouldTreatUnitsAsCaseSensitive() {
        assertThat(GrpcTimeout.parseNanos("100M"), is(TimeUnit.MINUTES.toNanos(100)));
        assertThat(GrpcTimeout.parseNanos("100m"), is(TimeUnit.MILLISECONDS.toNanos(100)));
    }

    /**
     * A malformed value means "no deadline" rather than an error — refusing the call would be
     * harsher than a real server, and the header stays visible for matching.
     */
    @Test
    public void shouldTreatMalformedValuesAsAbsent() {
        for (String value : new String[]{null, "", "S", "abc", "100", "100x", "-5S", "1.5S", "999999999S"}) {
            assertThat("expected no deadline for: " + value, GrpcTimeout.parseNanos(value), is(nullValue()));
        }
    }

    @Test
    public void shouldRejectMoreThanEightDigitsPerSpec() {
        assertThat(GrpcTimeout.parseNanos("12345678S"), is(TimeUnit.SECONDS.toNanos(12345678L)));
        assertThat(GrpcTimeout.parseNanos("123456789S"), is(nullValue()));
    }
}
