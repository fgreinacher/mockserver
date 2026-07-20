package org.mockserver.persistence;

import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;
import org.slf4j.event.Level;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static org.slf4j.event.Level.INFO;

/**
 * Append-only NDJSON (one JSON object per line) persistence for recorded proxied requests and their
 * responses. Unlike {@link RecordedExpectationFileSystemPersistence} (which re-serialises the whole
 * recorded-expectation set on every event), this writes a single compact line per FORWARDED_REQUEST
 * exchange and flushes after every line, so durably-captured traffic survives even if the process is
 * later OOM-killed. It is wired in as a per-entry hook on the event-log consumer thread via
 * {@link org.mockserver.log.MockServerEventLog#setRecordedRequestConsumer}.
 * <p>
 * When {@code persistRecordedRequestsToDisk} is disabled the instance is inert: all fields are null
 * and {@link #append(LogEntry)} / {@link #stop()} are no-ops.
 */
public class RecordedRequestsFileSystemPersistence {

    private final MockServerLogger mockServerLogger;
    private final Path filePath;
    private final HttpRequestAndHttpResponseSerializer serializer;
    private final Writer writer;
    // serialises serialize + write + flush as one atomic unit, matching the sibling persistence;
    // the consumer thread is single-threaded today but the lock keeps append/stop safe if that changes
    private final ReentrantLock writeOrderLock = new ReentrantLock();
    // retained so the redaction-aware accessors below can consult this server's Configuration
    // instance rather than only the global static store
    private final Configuration configuration;

    public RecordedRequestsFileSystemPersistence(Configuration configuration, MockServerLogger mockServerLogger) {
        this.configuration = configuration;
        if (configuration.persistRecordedRequestsToDisk()) {
            this.mockServerLogger = mockServerLogger;
            this.filePath = Paths.get(configuration.persistedRecordedRequestsPath());
            this.serializer = new HttpRequestAndHttpResponseSerializer(mockServerLogger);
            Writer bufferedWriter = null;
            try {
                Path parent = filePath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                bufferedWriter = Files.newBufferedWriter(filePath, UTF_8, CREATE, APPEND);
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
            this.writer = bufferedWriter;
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
            this.serializer = null;
            this.writer = null;
        }
    }

    /**
     * Append one recorded exchange as a single NDJSON line and flush. Runs on the event-log consumer
     * thread, so it never throws into the caller — any failure is logged at ERROR and swallowed.
     */
    public void append(LogEntry logEntry) {
        if (writer == null || logEntry == null) {
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
            // collapse the pretty-printed JSON to a single NDJSON line (string values escape real
            // newlines as \n, so only the formatting newlines are affected — the JSON stays valid)
            String line = serializer.serialize(httpRequestAndHttpResponse).replaceAll("\\s*\\n\\s*", " ").trim();
            writer.write(line);
            writer.write("\n");
            writer.flush();
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
            writeOrderLock.unlock();
        }
    }

    public void stop() {
        if (writer == null) {
            return;
        }
        writeOrderLock.lock();
        try {
            writer.flush();
            writer.close();
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
}
