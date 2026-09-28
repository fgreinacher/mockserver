package org.mockserver.logging;

import org.mockserver.version.Version;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Drop-in replacement for the {@link java.util.logging.SimpleFormatter} MockServer previously installed
 * with the pattern {@code "%1$tF %1$tT <version> %4$s %5$s %6$s%n"}. It produces byte-for-byte identical
 * output (guarded by {@code MockServerLogFormatterTest}) but avoids the ~1&micro;s-per-line cost of
 * {@code SimpleFormatter}'s full-line {@code String.format} on the single event-log consumer thread, which
 * formats every entry at {@code INFO} (the default level). The timestamp is second-resolution, so it is
 * formatted once per second and reused; the rest is a plain concatenation.
 * <p>
 * Deliberately free of any {@code ConfigurationProperties} dependency (it is instantiated by
 * {@code java.util.logging} from {@link MockServerLogger}'s bootstrap) so it cannot re-introduce the
 * {@code MockServerLogger} &harr; {@code ConfigurationProperties} class-init deadlock.
 */
public class MockServerLogFormatter extends Formatter {

    private static final String VERSION = Version.getVersion();

    private final DateTimeFormatter timestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private long cachedEpochSecond = Long.MIN_VALUE;
    private String cachedTimestamp = "";

    @Override
    public synchronized String format(LogRecord record) {
        long epochSecond = Math.floorDiv(record.getInstant().toEpochMilli(), 1000L);
        if (epochSecond != cachedEpochSecond) {
            cachedEpochSecond = epochSecond;
            cachedTimestamp = timestampFormatter.format(record.getInstant());
        }
        // Mirrors SimpleFormatter's throwable rendering exactly (a leading newline then the stack trace),
        // so the "%6$s" position is byte-identical.
        String throwable = "";
        if (record.getThrown() != null) {
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            printWriter.println();
            record.getThrown().printStackTrace(printWriter);
            printWriter.close();
            throwable = stringWriter.toString();
        }
        return cachedTimestamp + ' ' + VERSION + ' '
            + record.getLevel().getLocalizedName() + ' '
            + formatMessage(record) + ' ' + throwable + System.lineSeparator();
    }

}
