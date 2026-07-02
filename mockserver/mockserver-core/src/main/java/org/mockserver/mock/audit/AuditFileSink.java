package org.mockserver.mock.audit;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Optional, durable NDJSON file sink for control-plane {@link AuditEntry} records.
 * <p>
 * The in-memory {@link AuditStore} ring buffer is deliberately volatile — it is
 * bounded and is wiped by the very {@code reset} it records. This sink is a
 * <em>separate</em> optional writer that observes the same entries as they are
 * recorded and appends one JSON object per line ("newline-delimited JSON") to a
 * file, giving a tamper-evident, restart-surviving audit trail. It never reads
 * from or mutates the ring buffer — the ring stays exactly as-is, honouring the
 * {@link AuditEntry} contract that the in-memory store must never "become a sink".
 * <p>
 * Configured by the {@code mockserver.auditLogFile} property (empty default = off,
 * behaviour unchanged). The path is resolved once, on the first entry written
 * (fixed thereafter, mirroring {@link AuditStore}'s fixed-at-first-read capacity).
 * All write and open failures are handled by logging a single WARN and disabling
 * the sink — an IO error here must never crash request handling. Rotation is out
 * of scope: the file grows append-only; use external log rotation (e.g. logrotate)
 * or a bounded {@code controlPlaneAuditMaxEntries} + periodic archival if needed.
 */
public class AuditFileSink {

    private static final Logger LOG = LoggerFactory.getLogger(AuditFileSink.class);

    private static final AuditFileSink INSTANCE = new AuditFileSink();

    private final ObjectWriter writer = ObjectMapperFactory.createObjectMapper().writer();
    private final Object lock = new Object();

    private volatile boolean disabled;
    private boolean initialised;
    private Path path;
    private BufferedWriter out;

    AuditFileSink() {
    }

    public static AuditFileSink getInstance() {
        return INSTANCE;
    }

    /**
     * Appends {@code entry} as a single NDJSON line to the configured audit log
     * file, flushing per line. A no-op when {@code configuredPath} is blank, or
     * once the sink has been disabled by an earlier IO error. Never throws.
     *
     * @param entry          the audit entry to persist (also held in the in-memory store)
     * @param configuredPath the target file path; resolved once on first call and fixed thereafter
     */
    public void write(AuditEntry entry, String configuredPath) {
        if (disabled) {
            return;
        }
        synchronized (lock) {
            if (!initialised) {
                initialise(configuredPath);
            }
            if (out == null) {
                return;
            }
            try {
                out.write(writer.writeValueAsString(entry));
                out.write('\n');
                out.flush();
            } catch (IOException | RuntimeException throwable) {
                disableWithError("failed writing control-plane audit entry to " + path, throwable);
            }
        }
    }

    private void initialise(String configuredPath) {
        initialised = true;
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            return; // disabled by default — out stays null, permanent no-op
        }
        try {
            path = Paths.get(configuredPath.trim());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOG.info("control-plane audit file sink enabled, appending NDJSON to {}", path.toAbsolutePath());
        } catch (IOException | RuntimeException throwable) {
            disableWithError("failed to open control-plane audit log file " + configuredPath, throwable);
        }
    }

    private void disableWithError(String message, Throwable throwable) {
        disabled = true;
        closeQuietly();
        LOG.warn("{} — control-plane audit file sink disabled (in-memory audit store unaffected)", message, throwable);
    }

    private void closeQuietly() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // best effort
            }
            out = null;
        }
    }

    /**
     * Test hook: closes the underlying file handle without disabling the sink.
     */
    void closeForTest() {
        synchronized (lock) {
            closeQuietly();
        }
    }
}
