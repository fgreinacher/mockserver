package org.mockserver.persistence;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FilePath;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherListener;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.model.ExpectationDTO;
import org.mockserver.serialization.serializers.response.TimeToLiveDTOPersistenceSerializer;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobStore;
import org.mockserver.state.FilesystemBlobStore;
import org.slf4j.event.Level;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.serialization.ObjectMapperFactory.createObjectMapper;
import static org.slf4j.event.Level.*;

public class ExpectationFileSystemPersistence implements MockServerMatcherListener {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final RequestMatchers requestMatchers;
    private final ObjectWriter objectWriter;
    private final Path filePath;
    private final String blobKey;
    private final boolean initializationPathMatchesPersistencePath;
    private final BlobStore blobStore;
    private final java.util.concurrent.locks.ReentrantLock writeOrderLock = new java.util.concurrent.locks.ReentrantLock();

    /**
     * Creates persistence backed by the given {@link BlobStore}. The blob key
     * is the absolute path of {@code configuration.persistedExpectationsPath()}
     * so that the {@link org.mockserver.state.FilesystemBlobStore} writes to
     * the exact same file as the previous direct-I/O implementation.
     *
     * @param configuration    the MockServer configuration
     * @param mockServerLogger logger for diagnostics
     * @param requestMatchers  the request matchers to observe for changes
     * @param blobStore        the blob store to delegate writes to
     */
    public ExpectationFileSystemPersistence(Configuration configuration, MockServerLogger mockServerLogger, RequestMatchers requestMatchers, BlobStore blobStore) {
        this.configuration = configuration;
        if (configuration.persistExpectations()) {
            this.mockServerLogger = mockServerLogger;
            this.requestMatchers = requestMatchers;
            this.objectWriter = createObjectMapper(true, false, new TimeToLiveDTOPersistenceSerializer());
            this.filePath = Paths.get(configuration.persistedExpectationsPath());
            this.blobKey = filePath.toAbsolutePath().toString();
            this.blobStore = blobStore;
            try {
                Files.createFile(filePath);
            } catch (FileAlreadyExistsException ignore) {
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception creating persisted expectations file " + filePath)
                        .setThrowable(throwable)
                );
            }
            this.initializationPathMatchesPersistencePath = FilePath.expandFilePathGlobs(configuration.initializationJsonPath()).contains(configuration.persistedExpectationsPath());
            // Restore previously persisted expectations from the blob store before
            // registering the write-back listener, so a cloud-persisted document
            // (S3/GCS/Azure) is reloaded on restart. This happens BEFORE
            // registerListener so the restore itself does not trigger a redundant
            // write-back of the data we just read.
            reloadPersistedExpectations();
            requestMatchers.registerListener(this);
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(INFO)
                        .setMessageFormat("created expectation file system persistence for{}")
                        .setArguments(configuration.persistedExpectationsPath())
                );
            }
        } else {
            this.mockServerLogger = null;
            this.requestMatchers = null;
            this.objectWriter = null;
            this.filePath = null;
            this.blobKey = null;
            this.initializationPathMatchesPersistencePath = true;
            this.blobStore = null;
        }
    }

    /**
     * Backwards-compatible constructor that creates a
     * {@link org.mockserver.state.FilesystemBlobStore} internally, preserving
     * the original direct-file-I/O behaviour for callers that do not supply
     * a BlobStore (e.g. existing tests).
     */
    public ExpectationFileSystemPersistence(Configuration configuration, MockServerLogger mockServerLogger, RequestMatchers requestMatchers) {
        this(configuration, mockServerLogger, requestMatchers, new org.mockserver.state.FilesystemBlobStore(mockServerLogger));
    }

    @Override
    public void updated(RequestMatchers requestMatchers, MockServerMatcherNotifier.Cause cause) {
        // ignore non-API changes from the same file
        if (cause == MockServerMatcherNotifier.Cause.API || cause.getType() == MockServerMatcherNotifier.Cause.Type.CLASS_INITIALISER || !initializationPathMatchesPersistencePath) {
            // The lock serialises read-from-matchers + serialize + write-to-blob
            // as one atomic unit, matching the original fileWriteLock semantics.
            // This is necessary because listener callbacks are dispatched async
            // (via Scheduler.submit), so without the lock a later callback could
            // serialize first but write second, overwriting correct data.
            writeOrderLock.lock();
            try {
                try {
                    List<Expectation> expectations = requestMatchers.retrieveActiveExpectations(null);
                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(TRACE)
                                .setMessageFormat("persisting expectations{}to{}")
                                .setArguments(expectations, configuration.persistedExpectationsPath())
                        );
                    } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(DEBUG)
                                .setMessageFormat("persisting expectations to{}")
                                .setArguments(configuration.persistedExpectationsPath())
                        );
                    }
                    byte[] data = serialize(expectations).getBytes(UTF_8);
                    blobStore.put(blobKey, data, Collections.emptyMap());
                } catch (Throwable throwable) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception while persisting expectations to " + filePath.toString())
                            .setThrowable(throwable)
                    );
                }
            } finally {
                writeOrderLock.unlock();
            }
        }
    }

    /**
     * Loads any previously persisted expectations from the blob store and adds
     * them to the request matchers on startup, making persistence symmetric:
     * what is written on change is read back on restart.
     * <p>
     * This is a no-op for the {@link FilesystemBlobStore} because filesystem
     * persistence is already reloaded via the {@code initializationJsonPath}
     * mechanism (users point {@code initializationJsonPath} at
     * {@code persistedExpectationsPath}); reloading here as well would load the
     * same local file twice. Cloud blob stores (S3, GCS, Azure) have no such
     * local-file reload path, so the persisted document would otherwise be
     * write-only — persisted on change but never restored on restart. This
     * method closes that gap by reading the persisted blob directly.
     */
    private void reloadPersistedExpectations() {
        if (blobStore instanceof FilesystemBlobStore) {
            return;
        }
        try {
            Optional<Blob> blob = blobStore.get(blobKey);
            if (blob.isPresent()) {
                String json = new String(blob.get().getData(), UTF_8);
                if (isNotBlank(json)) {
                    Expectation[] expectations = new ExpectationSerializer(mockServerLogger).deserializeArray(json, true);
                    if (expectations != null && expectations.length > 0) {
                        requestMatchers.update(expectations, new Cause(blobKey, Cause.Type.FILE_INITIALISER));
                        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(INFO)
                                    .setMessageFormat("restored " + expectations.length + " persisted expectation(s) from blob store for{}")
                                    .setArguments(configuration.persistedExpectationsPath())
                            );
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception while restoring persisted expectations from blob store for " + configuration.persistedExpectationsPath())
                    .setThrowable(throwable)
            );
        }
    }

    public String serialize(List<Expectation> expectations) {
        return serialize(expectations.toArray(new Expectation[0]));
    }

    public String serialize(Expectation... expectations) {
        try {
            if (expectations != null && expectations.length > 0) {
                ExpectationDTO[] expectationDTOs = new ExpectationDTO[expectations.length];
                for (int i = 0; i < expectations.length; i++) {
                    expectationDTOs[i] = new ExpectationDTO(expectations[i]);
                }
                return objectWriter.writeValueAsString(expectationDTOs);
            } else {
                return "[]";
            }
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception while serializing expectation to JSON with value " + Arrays.asList(expectations))
                    .setThrowable(e)
            );
            throw new RuntimeException("Exception while serializing expectation to JSON with value " + Arrays.asList(expectations), e);
        }
    }

    public void stop() {
        if (requestMatchers != null) {
            requestMatchers.unregisterListener(this);
        }
    }
}
