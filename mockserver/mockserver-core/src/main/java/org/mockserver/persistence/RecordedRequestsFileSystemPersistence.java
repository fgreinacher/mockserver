package org.mockserver.persistence;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpRequestAndHttpResponseDTO;
import org.slf4j.event.Level;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static org.slf4j.event.Level.INFO;

/**
 * Append-only NDJSON (one JSON object per line) persistence for recorded proxied requests and their
 * responses. Unlike {@link RecordedExpectationFileSystemPersistence} (which re-serialises the whole
 * recorded-expectation set on every event), this writes a single compact line per recorded exchange.
 * It is wired in as a per-entry hook on the event-log consumer thread via
 * {@link org.mockserver.log.MockServerEventLog#setRecordedRequestConsumer(java.util.function.Consumer, Runnable)},
 * which also calls {@link #flush()} at the end of every consumer batch — lines reach the OS once the
 * consumer catches up, not with one syscall per line. A hard kill loses at most the unflushed buffer
 * ({@link #WRITE_BUFFER_BYTES}) plus any line being written; the importer skips a truncated line.
 * <p>
 * When {@code persistRecordedRequestsToDisk} is disabled the instance is inert: all fields are null
 * and {@link #append(LogEntry)} / {@link #flush()} / {@link #stop()} are no-ops.
 */
public class RecordedRequestsFileSystemPersistence {

    static final int WRITE_BUFFER_BYTES = 8192;
    // the line buffer is reused across appends; one grown past this by an unusually large exchange is
    // dropped afterwards so a single multi-megabyte body is not retained for the life of the server
    private static final int RETAINED_LINE_BUFFER_BYTES = 256 * 1024;

    private final MockServerLogger mockServerLogger;
    private final Path filePath;
    private final ObjectWriter objectWriter;
    private final OutputStream outputStream;
    // makes serialise + write, and flush, atomic: append runs on the consumer thread, but flush is also
    // called from a control-plane thread (before a ?source=disk import) and from stop()
    private final ReentrantLock writeOrderLock = new ReentrantLock();
    // retained so the redaction-aware accessors below can consult this server's Configuration
    // instance rather than only the global static store
    private final Configuration configuration;
    private LineBuffer lineBuffer;
    private boolean unflushed;

    public RecordedRequestsFileSystemPersistence(Configuration configuration, MockServerLogger mockServerLogger) {
        this.configuration = configuration;
        if (configuration.persistRecordedRequestsToDisk()) {
            this.mockServerLogger = mockServerLogger;
            this.filePath = Paths.get(configuration.persistedRecordedRequestsPath());
            // the compact twin of the pretty-printing writer HttpRequestAndHttpResponseSerializer uses:
            // identical mapper configuration, so a line parses to the same JSON, minus the formatting.
            // Without COMBINE_UNICODE_SURROGATES_IN_UTF8 the byte generator escapes every non-BMP
            // character (emoji) as a pair of escaped surrogates; the old String path wrote raw UTF-8
            this.objectWriter = ObjectMapperFactory.createObjectMapper(false, false).with(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8);
            this.lineBuffer = new LineBuffer();
            OutputStream bufferedOutputStream = null;
            try {
                Path parent = filePath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                bufferedOutputStream = new BufferedOutputStream(Files.newOutputStream(filePath, CREATE, APPEND), WRITE_BUFFER_BYTES);
            } catch (Throwable throwable) {
                if (mockServerLogger != null) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception creating recorded requests persistence file " + filePath)
                            .setThrowable(throwable)
                    );
                }
            }
            this.outputStream = bufferedOutputStream;
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(INFO)
                        .setMessageFormat("created recorded requests file system persistence for{}")
                        .setArguments(configuration.persistedRecordedRequestsPath())
                );
            }
        } else {
            this.mockServerLogger = null;
            this.filePath = null;
            this.objectWriter = null;
            this.outputStream = null;
        }
    }

    /**
     * Append one recorded exchange as a single NDJSON line, buffered until the next {@link #flush()}.
     * The whole line is serialised before any byte is written, so a serialisation failure writes
     * nothing. Runs on the event-log consumer thread, so it never throws into the caller — any failure
     * is logged at ERROR and swallowed.
     */
    public void append(LogEntry logEntry) {
        if (outputStream == null || logEntry == null) {
            return;
        }
        writeOrderLock.lock();
        try {
            // use the redaction-aware accessors so the persisted NDJSON archive honours
            // mockserver.redactSecretsInLog exactly like the in-memory retrieval path; they
            // return the raw object UNCHANGED when redaction is off (the default), so there is
            // zero behavioural change by default and secrets are masked when the flag is on
            RequestDefinition requestDefinition = logEntry.getRedactedHttpRequest(configuration);
            if (!(requestDefinition instanceof HttpRequest)) {
                return;
            }
            HttpRequestAndHttpResponse httpRequestAndHttpResponse = new HttpRequestAndHttpResponse()
                .withHttpRequest((HttpRequest) requestDefinition)
                .withHttpResponse(logEntry.getRedactedHttpResponse(configuration));
            lineBuffer.reset();
            objectWriter.writeValue(lineBuffer, new HttpRequestAndHttpResponseDTO(httpRequestAndHttpResponse));
            lineBuffer.collapseWhitespaceRunsContainingLineBreak();
            lineBuffer.write('\n');
            outputStream.write(lineBuffer.bytes(), 0, lineBuffer.size());
            unflushed = true;
        } catch (Throwable throwable) {
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while persisting recorded request to " + filePath)
                        .setThrowable(throwable)
                );
            }
        } finally {
            if (lineBuffer.capacity() > RETAINED_LINE_BUFFER_BYTES) {
                lineBuffer = new LineBuffer();
            }
            writeOrderLock.unlock();
        }
    }

    /**
     * Hand every buffered line to the OS. A no-op when nothing was appended since the last flush, so
     * calling it at the end of every event-log batch costs nothing for a batch that recorded no exchange.
     */
    public void flush() {
        if (outputStream == null) {
            return;
        }
        writeOrderLock.lock();
        try {
            if (unflushed) {
                // cleared BEFORE flushing: a failing disk must not re-flush in a loop (its ERROR is itself
                // a log entry that ends a batch); the next append re-arms it
                unflushed = false;
                outputStream.flush();
            }
        } catch (Throwable throwable) {
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while flushing recorded requests to " + filePath)
                        .setThrowable(throwable)
                );
            }
        } finally {
            writeOrderLock.unlock();
        }
    }

    public void stop() {
        if (outputStream == null) {
            return;
        }
        writeOrderLock.lock();
        try {
            unflushed = false;
            outputStream.flush();
            outputStream.close();
        } catch (Throwable throwable) {
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while closing recorded requests persistence file " + filePath)
                        .setThrowable(throwable)
                );
            }
        } finally {
            writeOrderLock.unlock();
        }
    }

    /**
     * Reusable serialisation target exposing its backing array, so a line reaches the file without
     * another copy.
     */
    static final class LineBuffer extends ByteArrayOutputStream {

        LineBuffer() {
            super(4096);
        }

        byte[] bytes() {
            return buf;
        }

        int capacity() {
            return buf.length;
        }

        /**
         * Keeps the line a single NDJSON record. Compact output holds a raw line break only when a
         * scalar JSON body is written verbatim ({@code writeRawValue}) and its text had one; JSON forbids
         * raw line breaks inside strings, so such a break is always formatting. Each maximal run of
         * whitespace ({@code [ \t\n\x0B\f\r]}) that contains a {@code \n} or {@code \r} becomes one space
         * ({@code \r} too, so readers that split on it cannot split a record), in place, in linear time.
         */
        void collapseWhitespaceRunsContainingLineBreak() {
            int end = count;
            int firstLineBreak = -1;
            for (int i = 0; i < end; i++) {
                if (isLineBreak(buf[i])) {
                    firstLineBreak = i;
                    break;
                }
            }
            if (firstLineBreak < 0) {
                return;
            }
            int runStart = firstLineBreak;
            while (runStart > 0 && isWhitespace(buf[runStart - 1])) {
                runStart--;
            }
            int write = runStart;
            int read = runStart;
            while (read < end) {
                if (!isWhitespace(buf[read])) {
                    buf[write++] = buf[read++];
                    continue;
                }
                int runEnd = read;
                boolean containsLineBreak = false;
                while (runEnd < end && isWhitespace(buf[runEnd])) {
                    containsLineBreak |= isLineBreak(buf[runEnd]);
                    runEnd++;
                }
                if (containsLineBreak) {
                    buf[write++] = ' ';
                } else {
                    System.arraycopy(buf, read, buf, write, runEnd - read);
                    write += runEnd - read;
                }
                read = runEnd;
            }
            count = write;
        }

        private static boolean isLineBreak(byte b) {
            return b == '\n' || b == '\r';
        }

        private static boolean isWhitespace(byte b) {
            return b == ' ' || b == '\t' || b == '\n' || b == 0x0B || b == '\f' || b == '\r';
        }
    }
}
