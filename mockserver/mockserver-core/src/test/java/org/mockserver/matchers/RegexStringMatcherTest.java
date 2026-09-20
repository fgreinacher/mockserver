package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.NottableString;

import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.mockserver.model.NottableString.string;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class RegexStringMatcherTest {

    @Test
    public void shouldMatchMatchingString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchUnMatchingNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, NottableString.not("not_value")), is(true));
    }

    @Test
    public void shouldMatchUnMatchingNottedMatcher() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("not_value"), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchUnMatchingNottedMatcherAndNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("not_matcher"), false).matches((MatchDifference) null, NottableString.not("not_value")), is(false));
    }

    @Test
    public void shouldNotMatchMatchingNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, NottableString.not("some_value")), is(false));
    }

    @Test
    public void shouldNotMatchMatchingNottedMatcher() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("some_value"), false).matches("some_value"), is(false));
    }

    @Test
    public void shouldNotMatchMatchingNottedMatcherAndNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("some_value"), false).matches((MatchDifference) null, NottableString.not("some_value")), is(true));
    }

    @Test
    public void shouldNotMatchMatchingString() {
        assertThat(notMatcher(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true)).matches("some_value"), is(false));
    }

    @Test
    public void shouldMatchMatchingStringWithRegexSymbols() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), false).matches("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), is(true));
    }

    @Test
    public void shouldMatchMatchingRegex() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{5}"), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchNullExpectation() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string(null), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchEmptyExpectation() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string(""), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("not_matching"), is(false));
    }

    @Test
    public void shouldMatchIncorrectString() {
        assertThat(notMatcher(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true)).matches("not_matching"), is(true));
    }

    @Test
    public void shouldNotMatchMatchingControlPlaneRegex() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("some_[a-z]{5}"), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectStringWithRegexSymbols() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), false).matches("text/html,application/xhtml+xml,application/xml;q=0.9;q=0.8"), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectRegex() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{4}"), false).matches("some_value"), is(false));
    }

    @Test
    public void shouldNotMatchNullTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, string(null)), is(false));
    }

    @Test
    public void shouldNotMatchEmptyTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches(""), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForExpectationAndTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("/{}"), false).matches("/{{}"), is(false));
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("/{}"), false).matches("some_value"), is(false));
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("/{}"), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForExpectation() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("/{}"), false).matches("some_value"), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("/{}"), is(false));
    }

    @Test
    public void shouldReturnFalseOnReDoSPatternRatherThanHanging() {
        // (a+)+b backtracks exponentially on a long run of 'a' followed by a non-match.
        // Without the regex timeout this call would hang far longer than any test budget.
        long previousTimeout = org.mockserver.configuration.ConfigurationProperties.regexMatchingTimeoutMillis();
        try {
            org.mockserver.configuration.ConfigurationProperties.regexMatchingTimeoutMillis(200L);
            String evilInput = repeat('a', 40) + "c";
            long start = System.nanoTime();
            boolean matched = new RegexStringMatcher(new MockServerLogger(), string("(a+)+b"), false).matches(evilInput);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertThat(matched, is(false));
            // generous upper bound: even at the 200ms timeout plus thread scheduling slack,
            // we should never approach minutes (which is what unbounded backtracking would cost).
            assertThat("regex evaluation took " + elapsedMs + "ms, expected to be bounded by timeout", elapsedMs < 5_000L, is(true));
        } finally {
            org.mockserver.configuration.ConfigurationProperties.regexMatchingTimeoutMillis(previousTimeout);
        }
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        java.util.Arrays.fill(buf, c);
        return new String(buf);
    }

    // --- pure-ASCII literal short-circuit (does not enter the timeout-protected executor pool) ---

    @Test
    public void shouldNotEnterExecutorPoolForPureAsciiLiteralNonMatch() throws Exception {
        long submittedBefore = executorSubmittedTaskCount();
        // pure-ASCII literal matcher value, no regex metacharacters: the equalsIgnoreCase check
        // already decides this is a non-match, so the regex/executor path must be skipped entirely.
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("not_matching"), is(false));
        long submittedAfter = executorSubmittedTaskCount();
        assertThat("pure-ASCII literal non-match must not submit any task to the matching executor pool",
            submittedAfter - submittedBefore, is(0L));
    }

    @Test
    public void shouldEnterExecutorPoolForRegexNotProvablyLinear() throws Exception {
        long submittedBefore = executorSubmittedTaskCount();
        // a regex that cannot be proven free of catastrophic backtracking (a quantified group) must
        // still be evaluated via the timeout-protected executor pool - G6 only lets PROVABLY-linear
        // patterns skip it, never one like this.
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("(a+)+z"), false).matches("some_value"), is(false));
        long submittedAfter = executorSubmittedTaskCount();
        assertThat("a regex not provably linear must still be evaluated via the timeout-protected executor pool",
            submittedAfter - submittedBefore > 0L, is(true));
    }

    @Test
    public void shouldRunProvablyLinearRegexInlineWithoutExecutorPool() throws Exception {
        long submittedBefore = executorSubmittedTaskCount();
        // a real regex (contains metacharacters) but provably linear ([a-z]+ and \d+ are disjoint):
        // G6 runs it inline on the calling thread, skipping the pool hand-off entirely.
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{4}"), false).matches("some_value"), is(false));
        long submittedAfter = executorSubmittedTaskCount();
        assertThat("a provably-linear regex must run inline, not via the pool",
            submittedAfter - submittedBefore, is(0L));
    }

    @Test
    public void shouldStillMatchAsciiLiteralCaseInsensitively() {
        // exact and case-insensitive matches are decided before any regex/short-circuit logic
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("Some_Value"), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldKeepRegexPathForNonAsciiLiteralValue() throws Exception {
        // 'İ' (U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE) folds differently under UNICODE_CASE
        // than under String.equalsIgnoreCase, so a non-ASCII value must NOT be short-circuited by the
        // pure-ASCII-literal fast path: it must keep the regex evaluation (which applies UNICODE_CASE).
        // Since G6, a provably-linear regex - including a non-ASCII literal - is evaluated inline
        // rather than via the pool, so the guarantee under test is that it still enters the regex
        // path (isPureAsciiLiteral is false), not which thread runs it.
        assertThat("a non-ASCII literal must not be treated as a pure-ASCII literal (would skip the regex path)",
            invokeIsPureAsciiLiteral("café"), is(false));
    }

    private static boolean invokeIsPureAsciiLiteral(String s) throws Exception {
        java.lang.reflect.Method m = RegexStringMatcher.class.getDeclaredMethod("isPureAsciiLiteral", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, s);
    }

    // --- case-sensitive (matchExactCase) constructor ---

    @Test
    public void shouldMatchAsciiLiteralCaseSensitivelyWhenCaseSensitive() {
        // case-sensitive constructor: only an exact-case literal matches (no equalsIgnoreCase fallback)
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("Some_Value"), false, true).matches("Some_Value"), is(true));
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("Some_Value"), false, true).matches("some_value"), is(false));
    }

    @Test
    public void shouldMatchRegexCaseSensitivelyWhenCaseSensitive() {
        // a real regex (metacharacters) compiled without CASE_INSENSITIVE only matches exact case
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("hello.*"), false, true).matches("hello world"), is(true));
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("hello.*"), false, true).matches("HELLO world"), is(false));
    }

    @Test
    public void shouldDefaultConstructorRemainCaseInsensitive() {
        // the original (no caseSensitive) constructor keeps the historical case-insensitive behaviour
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("hello.*"), false).matches("HELLO world"), is(true));
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("Some_Value"), false).matches("some_value"), is(true));
    }

    // --- looksLikeRegex helper ---

    @Test
    public void looksLikeRegexShouldDetectMetacharacters() throws Exception {
        for (String withMeta : new String[]{
            "a.b", "a*", "a+", "a?", "^a", "a$", "a|b", "(a)", "[a]", "{2}", "a\\d", "a]", "a}", "a)"
        }) {
            assertThat("expected '" + withMeta + "' to look like a regex", invokeLooksLikeRegex(withMeta), is(true));
        }
    }

    @Test
    public void looksLikeRegexShouldRejectPlainLiterals() throws Exception {
        for (String plain : new String[]{"some_value", "Some Value 123", "a-b_c", "", "café", "text/html"}) {
            assertThat("expected '" + plain + "' to NOT look like a regex", invokeLooksLikeRegex(plain), is(false));
        }
    }

    private static boolean invokeLooksLikeRegex(String s) throws Exception {
        java.lang.reflect.Method m = RegexStringMatcher.class.getDeclaredMethod("looksLikeRegex", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, s);
    }

    // --- saturation must never corrupt a match result (review-final regression) ---

    /**
     * Fires far more concurrent genuine regex evaluations than the matching pool's maximum thread
     * count (max(64, cores*16)). All worker threads release from a single barrier and then hammer a
     * pattern that genuinely matches, in a tight loop, maximising simultaneous submit pressure on the
     * pool's {@link java.util.concurrent.SynchronousQueue} (which rejects a submit that cannot hand
     * off to a thread at that instant).
     * <p>
     * Before the fix, such a rejection was converted by {@code callWithTimeout} into the timeout
     * sentinel — a spurious NON-MATCH — so a regex that would have matched silently returned false
     * under legitimate concurrent load. The fix raises the cap so rejection is effectively
     * unreachable and, on any residual rejection, runs the task inline returning the REAL result.
     * This test asserts ZERO spurious non-matches across hundreds of thousands of evaluations.
     * <p>
     * Lives in the sequential Surefire phase (RegexStringMatcherTest is parallel=none) so no other
     * test contends for the shared pool while we deliberately saturate it.
     */
    @Test(timeout = 60_000)
    public void concurrentGenuineMatchesNeverReturnSpuriousNonMatchUnderSaturation() throws Exception {
        final int threads = Math.max(256, Runtime.getRuntime().availableProcessors() * 32);
        final int iterationsPerThread = 200;
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
            final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            // a value that is NOT provably linear (a quantified group) so it takes the timeout-protected
            // executor path - not the pure-literal short-circuit and not the G6 inline fast path - and
            // that genuinely matches the input, so pool saturation is actually exercised.
            final java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    final RegexStringMatcher matcher = new RegexStringMatcher(new MockServerLogger(), string("(some_[a-z]{5})"), false);
                    ready.countDown();
                    start.await();
                    int spurious = 0;
                    for (int r = 0; r < iterationsPerThread; r++) {
                        if (!matcher.matches("some_value")) {
                            spurious++;
                        }
                    }
                    return spurious;
                }));
            }
            assertThat("all worker tasks should reach the start barrier",
                ready.await(30, java.util.concurrent.TimeUnit.SECONDS), is(true));
            start.countDown();

            int spuriousNonMatches = 0;
            for (java.util.concurrent.Future<Integer> result : results) {
                spuriousNonMatches += result.get(45, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat("a genuine match must never return non-match under pool saturation (saw "
                    + spuriousNonMatches + " spurious non-matches across " + ((long) threads * iterationsPerThread)
                    + " concurrent evaluations on " + threads + " threads)",
                spuriousNonMatches, is(0));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Deterministically saturates the executor pool by occupying every thread with a blocking task,
     * then proves that a further submission — which the {@link java.util.concurrent.SynchronousQueue}
     * MUST reject — runs inline and returns its REAL result, never the {@code onTimeout} sentinel.
     * This directly exercises the correctness-preserving rejection fallback that the cap raise makes
     * rare: even under extreme saturation a would-be match is never turned into a non-match.
     */
    @Test(timeout = 60_000)
    public void rejectedSubmissionRunsInlineAndReturnsRealResultNotSentinel() throws Exception {
        final int poolMax = Math.max(64, Runtime.getRuntime().availableProcessors() * 16);
        final java.util.concurrent.CountDownLatch occupy = new java.util.concurrent.CountDownLatch(poolMax);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.ExecutorService blockers = java.util.concurrent.Executors.newFixedThreadPool(poolMax);
        try {
            // fill every pool thread: each blocker calls callWithTimeout with a long timeout and a task
            // that parks until released, so all poolMax evaluator threads are occupied simultaneously.
            for (int i = 0; i < poolMax; i++) {
                blockers.submit(() -> MatchingTimeoutExecutor.callWithTimeout(() -> {
                    occupy.countDown();
                    release.await();
                    return Boolean.TRUE;
                }, 60_000L, Boolean.FALSE, null));
            }
            assertThat("all pool threads should become occupied",
                occupy.await(30, java.util.concurrent.TimeUnit.SECONDS), is(true));

            // pool is now full with a SynchronousQueue (no backlog): this submission is rejected and
            // must run inline. The sentinel is "WRONG"; the task's real result is "RIGHT".
            final long submittedBefore = MatchingTimeoutExecutor.submittedTaskCount();
            String result = MatchingTimeoutExecutor.callWithTimeout(() -> "RIGHT", 5_000L, "WRONG", null);
            final long submittedAfter = MatchingTimeoutExecutor.submittedTaskCount();

            assertThat("rejected-then-inline call must return the task's real result, never the sentinel",
                result, is("RIGHT"));
            assertThat("the inline call must NOT have been counted as a pool submission (i.e. it was rejected)",
                submittedAfter, is(submittedBefore));
        } finally {
            release.countDown();
            blockers.shutdownNow();
        }
    }

    // RegexStringMatcherTest runs in the sequential (parallel=none, forkCount=1) Surefire phase, so
    // no other test submits to the shared pool concurrently. MatchingTimeoutExecutor.submittedTaskCount()
    // is an explicit AtomicLong incremented on successful submit (not the JDK's documented-as-approximate
    // ThreadPoolExecutor.getTaskCount()), making these before/after deltas exact.
    private static long executorSubmittedTaskCount() {
        return MatchingTimeoutExecutor.submittedTaskCount();
    }
}
