package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.LlmChaosProfile.llmChaosProfile;

public class LlmChaosProfileTest {

    // --- errorProbability validation ---

    @Test
    public void withErrorProbabilityAcceptsNull() {
        llmChaosProfile().withErrorProbability(null);
    }

    @Test
    public void withErrorProbabilityAcceptsValidRange() {
        llmChaosProfile().withErrorProbability(0.0);
        llmChaosProfile().withErrorProbability(0.5);
        llmChaosProfile().withErrorProbability(1.0);
    }

    @Test
    public void withErrorProbabilityRejectsBelowZero() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withErrorProbability(-0.1));
        assertThat(exception.getMessage(), is("errorProbability must be between 0.0 and 1.0, got -0.1"));
    }

    @Test
    public void withErrorProbabilityRejectsAboveOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withErrorProbability(1.1));
        assertThat(exception.getMessage(), is("errorProbability must be between 0.0 and 1.0, got 1.1"));
    }

    @Test
    public void withErrorProbabilityRejectsNaN() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withErrorProbability(Double.NaN));
        assertThat(exception.getMessage(), is("errorProbability must be between 0.0 and 1.0, got NaN"));
    }

    // --- errorStatus validation ---

    @Test
    public void withErrorStatusAcceptsNull() {
        llmChaosProfile().withErrorStatus(null);
    }

    @Test
    public void withErrorStatusAcceptsValidRange() {
        llmChaosProfile().withErrorStatus(100);
        llmChaosProfile().withErrorStatus(429);
        llmChaosProfile().withErrorStatus(599);
    }

    @Test
    public void withErrorStatusRejectsBelowOneHundred() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withErrorStatus(99));
        assertThat(exception.getMessage(), is("errorStatus must be between 100 and 599, got 99"));
    }

    @Test
    public void withErrorStatusRejectsAboveFiveNineNine() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withErrorStatus(600));
        assertThat(exception.getMessage(), is("errorStatus must be between 100 and 599, got 600"));
    }

    // --- truncateAtFraction validation ---

    @Test
    public void withTruncateAtFractionAcceptsNullAndRange() {
        llmChaosProfile().withTruncateAtFraction(null);
        llmChaosProfile().withTruncateAtFraction(0.0);
        llmChaosProfile().withTruncateAtFraction(0.5);
        llmChaosProfile().withTruncateAtFraction(1.0);
    }

    @Test
    public void withTruncateAtFractionRejectsBelowZero() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withTruncateAtFraction(-0.1));
        assertThat(exception.getMessage(), is("truncateAtFraction must be between 0.0 and 1.0, got -0.1"));
    }

    @Test
    public void withTruncateAtFractionRejectsAboveOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withTruncateAtFraction(1.1));
        assertThat(exception.getMessage(), is("truncateAtFraction must be between 0.0 and 1.0, got 1.1"));
    }

    @Test
    public void withTruncateAtFractionRejectsNaN() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withTruncateAtFraction(Double.NaN));
        assertThat(exception.getMessage(), is("truncateAtFraction must be between 0.0 and 1.0, got NaN"));
    }

    // --- quota validation ---

    @Test
    public void withQuotaLimitAcceptsNullAndPositive() {
        llmChaosProfile().withQuotaLimit(null);
        llmChaosProfile().withQuotaLimit(1);
        llmChaosProfile().withQuotaLimit(1000);
    }

    @Test
    public void withQuotaLimitRejectsBelowOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withQuotaLimit(0));
        assertThat(exception.getMessage(), is("quotaLimit must be >= 1, got 0"));
    }

    @Test
    public void withQuotaWindowMillisAcceptsNullAndPositive() {
        llmChaosProfile().withQuotaWindowMillis(null);
        llmChaosProfile().withQuotaWindowMillis(1L);
        llmChaosProfile().withQuotaWindowMillis(60000L);
    }

    @Test
    public void withQuotaWindowMillisRejectsBelowOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withQuotaWindowMillis(0L));
        assertThat(exception.getMessage(), is("quotaWindowMillis must be >= 1, got 0"));
    }

    @Test
    public void withQuotaErrorStatusAcceptsNullAndValidRange() {
        llmChaosProfile().withQuotaErrorStatus(null);
        llmChaosProfile().withQuotaErrorStatus(100);
        llmChaosProfile().withQuotaErrorStatus(429);
        llmChaosProfile().withQuotaErrorStatus(599);
    }

    @Test
    public void withQuotaErrorStatusRejectsBelowOneHundred() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withQuotaErrorStatus(42));
        assertThat(exception.getMessage(), is("quotaErrorStatus must be between 100 and 599, got 42"));
    }

    @Test
    public void withQuotaErrorStatusRejectsAboveFiveNineNine() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withQuotaErrorStatus(600));
        assertThat(exception.getMessage(), is("quotaErrorStatus must be between 100 and 599, got 600"));
    }

    // --- tokenQuotaLimit validation ---

    @Test
    public void withTokenQuotaLimitAcceptsNullAndPositive() {
        llmChaosProfile().withTokenQuotaLimit(null);
        llmChaosProfile().withTokenQuotaLimit(1L);
        llmChaosProfile().withTokenQuotaLimit(100_000L);
    }

    @Test
    public void withTokenQuotaLimitRejectsBelowOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withTokenQuotaLimit(0L));
        assertThat(exception.getMessage(), is("tokenQuotaLimit must be >= 1, got 0"));
    }

    // --- tokenQuotaWindowMillis validation ---

    @Test
    public void withTokenQuotaWindowMillisAcceptsNullAndPositive() {
        llmChaosProfile().withTokenQuotaWindowMillis(null);
        llmChaosProfile().withTokenQuotaWindowMillis(1L);
        llmChaosProfile().withTokenQuotaWindowMillis(86_400_000L);
    }

    @Test
    public void withTokenQuotaWindowMillisRejectsBelowOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withTokenQuotaWindowMillis(0L));
        assertThat(exception.getMessage(), is("tokenQuotaWindowMillis must be >= 1, got 0"));
    }

    // --- contentFilterBlockProbability validation ---

    @Test
    public void withContentFilterBlockProbabilityAcceptsNullAndRange() {
        llmChaosProfile().withContentFilterBlockProbability(null);
        llmChaosProfile().withContentFilterBlockProbability(0.0);
        llmChaosProfile().withContentFilterBlockProbability(0.5);
        llmChaosProfile().withContentFilterBlockProbability(1.0);
    }

    @Test
    public void withContentFilterBlockProbabilityRejectsBelowZero() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withContentFilterBlockProbability(-0.1));
        assertThat(exception.getMessage(), is("contentFilterBlockProbability must be between 0.0 and 1.0, got -0.1"));
    }

    @Test
    public void withContentFilterBlockProbabilityRejectsAboveOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withContentFilterBlockProbability(1.1));
        assertThat(exception.getMessage(), is("contentFilterBlockProbability must be between 0.0 and 1.0, got 1.1"));
    }

    @Test
    public void withContentFilterBlockProbabilityRejectsNaN() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> llmChaosProfile().withContentFilterBlockProbability(Double.NaN));
        assertThat(exception.getMessage(), is("contentFilterBlockProbability must be between 0.0 and 1.0, got NaN"));
    }

    @Test
    public void equalsShouldIncludeContentFilterBlockProbability() {
        LlmChaosProfile a = llmChaosProfile().withContentFilterBlockProbability(0.5);
        LlmChaosProfile b = llmChaosProfile().withContentFilterBlockProbability(0.5);
        LlmChaosProfile c = llmChaosProfile().withContentFilterBlockProbability(1.0);
        assertThat(a.equals(b), is(true));
        assertThat(a.hashCode() == b.hashCode(), is(true));
        assertThat(a.equals(c), is(false));
    }

    // --- equals/hashCode with token quota fields ---

    @Test
    public void equalsShouldIncludeTokenQuotaFields() {
        LlmChaosProfile a = llmChaosProfile()
            .withQuotaName("acct")
            .withTokenQuotaLimit(1000L)
            .withTokenQuotaWindowMillis(60_000L);
        LlmChaosProfile b = llmChaosProfile()
            .withQuotaName("acct")
            .withTokenQuotaLimit(1000L)
            .withTokenQuotaWindowMillis(60_000L);
        LlmChaosProfile c = llmChaosProfile()
            .withQuotaName("acct")
            .withTokenQuotaLimit(2000L)
            .withTokenQuotaWindowMillis(60_000L);

        assertThat(a.equals(b), is(true));
        assertThat(a.hashCode() == b.hashCode(), is(true));
        assertThat(a.equals(c), is(false));
    }

    @Test
    public void hashCodeShouldDifferForDifferentTokenQuotaWindow() {
        LlmChaosProfile a = llmChaosProfile()
            .withQuotaName("acct")
            .withTokenQuotaLimit(1000L)
            .withTokenQuotaWindowMillis(60_000L);
        LlmChaosProfile b = llmChaosProfile()
            .withQuotaName("acct")
            .withTokenQuotaLimit(1000L)
            .withTokenQuotaWindowMillis(86_400_000L);

        // Different window -> should not be equal (hashCode may collide, but equals must not)
        assertThat(a.equals(b), is(false));
    }
}
