package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.NottableString;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Proves the G6 fix end-to-end at the executor boundary: a regex the
 * {@link RegexComplexityClassifier} proves linear skips the shared timeout pool (no event-loop
 * hand-off), while every pattern that could backtrack catastrophically - and every {@code find()}-
 * based caller - keeps the pool's DoS isolation, and that isolation still bounds a real
 * catastrophic pattern to the timeout rather than hanging.
 *
 * <h2>Observable</h2>
 * The deterministic observable is {@link MatchingTimeoutExecutor#submittedTaskCount()}: the number
 * of tasks handed to the shared pool. An inline-safe evaluation must not increment it; a pooled one
 * must. This is the same process-wide counter {@link MatchingLimitsInstanceConfigurationTest} uses,
 * so this class is likewise registered in the sequential surefire phase (see mockserver-core/pom.xml)
 * - it must not race other matching running concurrently.
 */
public class MatchingTimeoutInlineSafeTest {

    private static MockServerLogger loggerWith(Configuration configuration) {
        return new MockServerLogger(configuration, MatchingTimeoutInlineSafeTest.class);
    }

    // ----- anchored matchesWithRegexTimeout (GraphQL / JSON-RPC style) ---------------------------

    @Test
    public void anchoredSafePatternRunsInlineWithoutPool() {
        Pattern safe = Pattern.compile("[0-9]+");
        long before = MatchingTimeoutExecutor.submittedTaskCount();
        boolean result = MatchingTimeoutExecutor.matchesWithRegexTimeout(null, "test", safe,
            () -> safe.matcher("12345").matches(), true);
        long submitted = MatchingTimeoutExecutor.submittedTaskCount() - before;

        assertThat("match result must be correct", result, is(true));
        assertThat("a provably-linear anchored pattern must NOT be submitted to the pool",
            submitted, is(0L));
    }

    @Test
    public void anchoredUnsafePatternStillUsesPool() {
        Pattern unsafe = Pattern.compile("(a+)+");
        long before = MatchingTimeoutExecutor.submittedTaskCount();
        MatchingTimeoutExecutor.matchesWithRegexTimeout(null, "test", unsafe,
            () -> unsafe.matcher("aaaa").matches(), true);
        long submitted = MatchingTimeoutExecutor.submittedTaskCount() - before;

        assertThat("a pattern with a quantified group must keep the pool's isolation",
            submitted, greaterThan(0L));
    }

    /**
     * The {@code find()} path (LLM conversation matcher) uses the 4-arg overload, which never opts
     * into inlining, because find()'s outer position scan can make even a linear pattern quadratic.
     * Even a structurally-safe pattern must stay pooled there.
     */
    @Test
    public void findStylePatternAlwaysUsesPoolEvenWhenStructurallySafe() {
        Pattern safeButFind = Pattern.compile("[0-9]+");
        long before = MatchingTimeoutExecutor.submittedTaskCount();
        MatchingTimeoutExecutor.matchesWithRegexTimeout(null, "llm-style", safeButFind,
            () -> safeButFind.matcher("abc123").find());
        long submitted = MatchingTimeoutExecutor.submittedTaskCount() - before;

        assertThat("find()-based callers must keep the pool regardless of pattern shape",
            submitted, greaterThan(0L));
    }

    // ----- RegexStringMatcher (path / method / string-body) -------------------------------------

    @Test
    public void regexStringMatcherRunsSafePatternInline() {
        Configuration configuration = Configuration.configuration().regexMatchingTimeoutMillis(5000L);
        RegexStringMatcher matcher = new RegexStringMatcher(loggerWith(configuration), false);

        long before = MatchingTimeoutExecutor.submittedTaskCount();
        boolean result = matcher.matches(
            NottableString.string("^/regex/path-[a-z]+-\\d+$"),
            NottableString.string("/regex/path-abcdef-42"));
        long submitted = MatchingTimeoutExecutor.submittedTaskCount() - before;

        assertThat("the G6 benchmark pattern must match", result, is(true));
        assertThat("a provably-linear path regex must run inline, not via the pool",
            submitted, is(0L));
    }

    @Test
    public void regexStringMatcherKeepsPoolForUnsafePattern() {
        Configuration configuration = Configuration.configuration().regexMatchingTimeoutMillis(5000L);
        RegexStringMatcher matcher = new RegexStringMatcher(loggerWith(configuration), false);

        long before = MatchingTimeoutExecutor.submittedTaskCount();
        matcher.matches(
            NottableString.string("(a+)+x"),
            NottableString.string("aaaaaaaaaaaa"));
        long submitted = MatchingTimeoutExecutor.submittedTaskCount() - before;

        assertThat("a pattern with a quantified group must keep the pool's isolation",
            submitted, greaterThan(0L));
    }

    // ----- the load-bearing DoS proof -----------------------------------------------------------

    /**
     * The whole point of keeping the pool for unproven patterns: a genuinely catastrophic pattern
     * must still be bounded to the timeout and treated as a non-match, not hang. {@code (.*a){10}$}
     * on an all-'a' input ending in a non-matching char backtracks exponentially on this JDK - a
     * full evaluation takes seconds and grows with input length. With a short timeout the pool must
     * return a non-match well before that.
     */
    @Test
    public void catastrophicPatternIsBoundedByTimeoutAndTreatedAsNonMatch() {
        // uncapped, this input takes ~2.4s (verified); a 300ms timeout must cut it off far sooner
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 34; i++) {
            sb.append('a');
        }
        sb.append('b');
        String catastrophicInput = sb.toString();

        Configuration configuration = Configuration.configuration().regexMatchingTimeoutMillis(300L);
        RegexStringMatcher matcher = new RegexStringMatcher(loggerWith(configuration), false);

        assertThat("this pattern must be classified unsafe so it keeps the pool",
            RegexComplexityClassifier.isAnchoredInlineSafe("(.*a){10}$", true), is(false));

        long start = System.nanoTime();
        boolean result = matcher.matches(
            NottableString.string("(.*a){10}$"),
            NottableString.string(catastrophicInput));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat("a catastrophic pattern must be treated as a non-match on timeout", result, is(false));
        assertThat("the timeout must bound the evaluation far below its uncapped ~2.4s runtime",
            elapsedMs, lessThan(1_500L));
    }

    /**
     * The primitive guarantee the DoS proof rests on: with the pool engaged (inlineSafe == false),
     * a task that runs far longer than the timeout is abandoned and the caller gets {@code onTimeout}
     * quickly, with the timeout callback fired. This is what frees the event-loop thread.
     */
    @Test
    public void poolBoundsAnArbitrarilyLongTaskToTheTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean timeoutFired = new AtomicBoolean(false);

        long start = System.nanoTime();
        Boolean result = MatchingTimeoutExecutor.callWithTimeout(
            () -> {
                started.countDown();
                Thread.sleep(5_000L);
                return Boolean.TRUE;
            },
            200L,
            Boolean.FALSE,
            ms -> timeoutFired.set(true),
            false);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat("the long task must have actually started on a pool thread",
            started.await(2, java.util.concurrent.TimeUnit.SECONDS), is(true));
        assertThat("the caller must get the timeout sentinel, not the task's real result",
            result, is(Boolean.FALSE));
        assertThat("the timeout callback must fire", timeoutFired.get(), is(true));
        assertThat("the caller must be released near the timeout, not after the 5s task",
            elapsedMs, lessThan(1_500L));
    }

    @Test
    public void inlineSafeFlagRunsTaskOnCallerAndReturnsRealResult() {
        long before = MatchingTimeoutExecutor.submittedTaskCount();
        Boolean result;
        try {
            result = MatchingTimeoutExecutor.callWithTimeout(
                () -> Boolean.TRUE, 5000L, Boolean.FALSE, null, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        long submitted = MatchingTimeoutExecutor.submittedTaskCount() - before;

        assertThat("inlineSafe must return the real result", result, is(Boolean.TRUE));
        assertThat("inlineSafe must not touch the pool", submitted, is(0L));
    }
}
