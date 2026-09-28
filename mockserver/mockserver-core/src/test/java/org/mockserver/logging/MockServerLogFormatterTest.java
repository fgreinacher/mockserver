package org.mockserver.logging;

import org.junit.Test;
import org.mockserver.version.Version;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

/**
 * {@link MockServerLogFormatter} exists only to be a faster, byte-for-byte replacement of the
 * {@link java.util.logging.SimpleFormatter} MockServer used to install with the pattern
 * {@code "%1$tF %1$tT <version> %4$s %5$s %6$s%n"}. This test is the guard on that "byte-for-byte":
 * it renders a corpus through the formatter and through an oracle that reproduces SimpleFormatter's
 * own assembly with {@link String#format(String, Object...)}, and asserts the two are equal. It is
 * adversarial in the dimensions the optimisation touches - the second-resolution timestamp cache
 * (records either side of a second boundary) and the "%6$s" throwable slot (with and without a
 * throwable, including a cause chain) - and covers messages with the characters that break a naive
 * concatenation (a literal {@code %}, braces, empty, a trailing space).
 * <p>
 * The oracle deliberately does NOT go through the real SimpleFormatter, which reads its pattern from a
 * JVM-global logging property; setting that would be a parallel-test-state hazard. The one-off
 * comparison against the real SimpleFormatter was done separately during development.
 */
public class MockServerLogFormatterTest {

    private final MockServerLogFormatter formatter = new MockServerLogFormatter();

    // Reproduces SimpleFormatter.format for the installed pattern, via String.format, as an independent oracle.
    private static String simpleFormatterOracle(LogRecord record) {
        String pattern = "%1$tF %1$tT " + Version.getVersion() + " %4$s %5$s %6$s%n";
        ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault());
        String throwable = "";
        if (record.getThrown() != null) {
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            printWriter.println();
            record.getThrown().printStackTrace(printWriter);
            printWriter.close();
            throwable = stringWriter.toString();
        }
        return String.format(pattern, zonedDateTime, record.getSourceClassName(), record.getLoggerName(),
            record.getLevel().getLocalizedName(), record.getMessage(), throwable);
    }

    private LogRecord record(Level level, String message, long epochMilli, boolean withThrowable) {
        LogRecord logRecord = new LogRecord(level, message);
        logRecord.setInstant(Instant.ofEpochMilli(epochMilli));
        logRecord.setLoggerName("org.mockserver.test");
        if (withThrowable) {
            RuntimeException thrown = new RuntimeException("boom");
            thrown.initCause(new IllegalStateException("underlying cause"));
            logRecord.setThrown(thrown);
        }
        return logRecord;
    }

    @Test
    public void shouldRenderByteIdenticallyToSimpleFormatterAcrossCorpus() {
        long base = 1_600_000_000_000L; // fixed epoch so the test is deterministic
        long[] offsets = {0L, 500L, 999L, 1000L, 1001L, 60_000L, 3_600_000L};
        String[] messages = {
            "received request:{}",
            "plain message without braces",
            "message containing a percent 100% literal",
            "json body {\"name\":\"value\"} embedded",
            "",
            "message with a trailing space "
        };
        Level[] levels = {Level.INFO, Level.WARNING, Level.SEVERE, Level.FINE, Level.FINEST};

        for (long offset : offsets) {
            for (String message : messages) {
                for (Level level : levels) {
                    for (boolean withThrowable : new boolean[]{false, true}) {
                        LogRecord logRecord = record(level, message, base + offset, withThrowable);
                        assertThat(
                            "level=" + level + " throwable=" + withThrowable + " message=[" + message + "]",
                            formatter.format(logRecord),
                            is(simpleFormatterOracle(logRecord)));
                    }
                }
            }
        }
    }

    @Test
    public void shouldReuseTheTimestampWithinTheSameSecondAndRefreshItAcrossTheBoundary() {
        // same wall-clock second -> same rendered timestamp; next second -> a different one
        String at000 = formatter.format(record(Level.INFO, "a", 1_600_000_000_000L, false));
        String at999 = formatter.format(record(Level.INFO, "a", 1_600_000_000_999L, false));
        String at1000 = formatter.format(record(Level.INFO, "a", 1_600_000_001_000L, false));

        String tsAt000 = at000.substring(0, 19);
        String tsAt999 = at999.substring(0, 19);
        String tsAt1000 = at1000.substring(0, 19);
        assertThat(tsAt999, is(tsAt000));
        assertThat(tsAt1000, is(not(tsAt000)));
    }
}
