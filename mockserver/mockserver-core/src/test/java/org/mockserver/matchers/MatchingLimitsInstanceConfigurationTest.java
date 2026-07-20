package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.NottableString;
import org.mockserver.xml.XPathEvaluator;

import javax.xml.xpath.XPathConstants;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Proves the matching safety limits {@code regexMatchingTimeoutMillis} and
 * {@code xpathMatchingTimeoutMillis} are honoured when set on a {@link Configuration}
 * <em>instance</em>, at the three enforcement sites that previously read only the static
 * {@link ConfigurationProperties} store: {@link RegexStringMatcher}'s regex runner,
 * {@link MatchingTimeoutExecutor#matchesWithRegexTimeout} and
 * {@link XPathEvaluator#evaluateXPathExpression}.
 *
 * <h2>Why the observable is the submitted-task count, not elapsed time</h2>
 * <p>The obvious test — set a short timeout, run a catastrophically-backtracking pattern, assert it
 * is cut off — does NOT work on a modern JDK. Every classic ReDoS pattern
 * ({@code (a+)+b}, {@code (a|a)*$}, {@code ^(\w+\s?)*$}, {@code (x+x+)+y}) completes in under a
 * millisecond on JDK 21, whose regex engine fails them fast rather than backtracking exponentially.
 * A timing-based assertion therefore passes identically whether the instance value is honoured or
 * ignored — it proves nothing.
 *
 * <p>These tests use a deterministic observable instead. {@link MatchingTimeoutExecutor#callWithTimeout}
 * documents that a NON-POSITIVE timeout disables the timeout and runs the task inline on the calling
 * thread, bypassing the shared executor pool. So a timeout of {@code 0} set on the instance ONLY is
 * observable as "no task was submitted to the pool", whereas the static default (5000ms) always
 * submits one. {@link MatchingTimeoutExecutor#submittedTaskCount()} exposes that count, which is why
 * this class lives in the {@code org.mockserver.matchers} package.
 *
 * <p>Registered in the sequential surefire phase (see mockserver-core/pom.xml) because the submitted
 * task count is a process-wide counter shared with any other matching running concurrently.
 */
public class MatchingLimitsInstanceConfigurationTest {

    private static MockServerLogger loggerWith(Configuration configuration) {
        return new MockServerLogger(configuration, MatchingLimitsInstanceConfigurationTest.class);
    }

    // ----- RegexStringMatcher#runRegexWithTimeout -----------------------------------------------

    @Test
    public void shouldDisableRegexTimeoutPoolFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().regexMatchingTimeoutMillis(0L);

        long before = MatchingTimeoutExecutor.submittedTaskCount();
        new RegexStringMatcher(loggerWith(configuration), false).matches(
            NottableString.string("[0-9]+"), NottableString.string("12345"));

        assertThat("a zero regexMatchingTimeoutMillis on the instance must run the match inline, "
                + "so nothing is submitted to the shared timeout pool",
            MatchingTimeoutExecutor.submittedTaskCount() - before, is(0L));
    }

    @Test
    public void shouldUseRegexTimeoutPoolWhenInstanceLeavesTheTimeoutUnset() {
        // no instance value -> the static default (5000ms) applies -> the pool IS used
        long before = MatchingTimeoutExecutor.submittedTaskCount();
        new RegexStringMatcher(loggerWith(null), false).matches(
            NottableString.string("[0-9]+"), NottableString.string("12345"));

        assertThat(MatchingTimeoutExecutor.submittedTaskCount() - before, is(1L));
    }

    // ----- MatchingTimeoutExecutor#matchesWithRegexTimeout --------------------------------------

    @Test
    public void shouldDisableSharedExecutorTimeoutFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().regexMatchingTimeoutMillis(0L);
        Pattern pattern = Pattern.compile("[0-9]+");

        long before = MatchingTimeoutExecutor.submittedTaskCount();
        boolean matched = MatchingTimeoutExecutor.matchesWithRegexTimeout(
            loggerWith(configuration), "test", pattern, () -> pattern.matcher("12345").matches());

        assertThat(matched, is(true));
        assertThat("a zero regexMatchingTimeoutMillis on the instance must run the match inline",
            MatchingTimeoutExecutor.submittedTaskCount() - before, is(0L));
    }

    @Test
    public void shouldUseSharedExecutorWhenInstanceLeavesTheTimeoutUnset() {
        Pattern pattern = Pattern.compile("[0-9]+");

        long before = MatchingTimeoutExecutor.submittedTaskCount();
        boolean matched = MatchingTimeoutExecutor.matchesWithRegexTimeout(
            loggerWith(null), "test", pattern, () -> pattern.matcher("12345").matches());

        assertThat(matched, is(true));
        assertThat(MatchingTimeoutExecutor.submittedTaskCount() - before, is(1L));
    }

    // ----- XPathEvaluator#evaluateXPathExpression -----------------------------------------------

    @Test
    public void shouldDisableXPathTimeoutPoolFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().xpathMatchingTimeoutMillis(0L);
        XPathEvaluator evaluator = new XPathEvaluator("/root/value/text()", null, configuration);

        long before = MatchingTimeoutExecutor.submittedTaskCount();
        Object result = evaluator.evaluateXPathExpression(
            "<root><value>found</value></root>", (matched, throwable, level) -> {
            }, XPathConstants.STRING);

        assertThat(String.valueOf(result), is("found"));
        assertThat("a zero xpathMatchingTimeoutMillis on the instance must run the evaluation inline",
            MatchingTimeoutExecutor.submittedTaskCount() - before, is(0L));
    }

    @Test
    public void shouldUseXPathTimeoutPoolWhenInstanceLeavesTheTimeoutUnset() {
        XPathEvaluator evaluator = new XPathEvaluator("/root/value/text()", null, null);

        long before = MatchingTimeoutExecutor.submittedTaskCount();
        Object result = evaluator.evaluateXPathExpression(
            "<root><value>found</value></root>", (matched, throwable, level) -> {
            }, XPathConstants.STRING);

        assertThat(String.valueOf(result), is("found"));
        assertThat(MatchingTimeoutExecutor.submittedTaskCount() - before, is(1L));
    }
}
