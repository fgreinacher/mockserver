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
import org.mockserver.state.BlobKeys;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.serialization.ObjectMapperFactory.createObjectMapper;
import static org.slf4j.event.Level.*;

public class ExpectationFileSystemPersistence implements MockServerMatcherListener {

    /**
     * Prefix applied to the {@link Cause} source of expectations restored from the blob
     * store on startup, so it can never be equal to an {@code initializationJsonPath}.
     * <p>
     * {@link Cause} has value equality on {@code (source, type)} and
     * {@link RequestMatchers#update(Expectation[], Cause)} REMOVES every matcher whose
     * source equals the incoming cause but which is absent from the incoming array. The
     * restore runs before {@code ExpectationInitializerLoader}, which calls
     * {@code update(..., new Cause(initializationJsonPath, FILE_INITIALISER))}
     * unconditionally — including with an empty array when the file is blank. Without this
     * prefix a user who points {@code initializationJsonPath} at the same (absolute) path as
     * {@code persistedExpectationsPath} — long-standing guidance for FILESYSTEM persistence —
     * would have every expectation restored from the cloud blob store silently deleted by the
     * initializer reading the empty local file.
     */
    private static final String BLOB_STORE_CAUSE_SOURCE_PREFIX = "blobstore:";

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
     * Creates persistence backed by the given {@link BlobStore}. The blob key is
     * derived by {@link org.mockserver.state.BlobKeys#forPersistedFile(BlobStore, Path)}:
     * the absolute path of {@code configuration.persistedExpectationsPath()} for the
     * {@link org.mockserver.state.FilesystemBlobStore}, so it writes the exact same file
     * as the previous direct-I/O implementation, and the FILE NAME alone for every other
     * store, because an absolute local path is not a valid object-store key.
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
            this.blobKey = BlobKeys.forPersistedFile(blobStore, filePath);
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
            // Expanded ONCE and reused below. For a glob this walks the file tree AND runs a full
            // ClassGraph classpath scan (FilePath.expandFilePathGlobs), which is far too expensive to
            // repeat on the startup path this class's restore deadline exists to bound.
            List<String> expandedInitializationJsonPaths = FilePath.expandFilePathGlobs(configuration.initializationJsonPath());
            this.initializationPathMatchesPersistencePath = expandedInitializationJsonPaths.contains(configuration.persistedExpectationsPath());
            // Restore previously persisted expectations from the blob store before
            // registering the write-back listener, so a cloud-persisted document
            // (S3/GCS/Azure) is reloaded on restart. This happens BEFORE
            // registerListener so the restore itself does not trigger a redundant
            // write-back of the data we just read.
            reloadPersistedExpectations(expandedInitializationJsonPaths);
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
     * <p>
     * The read is BOUNDED by {@code blobStoreRestoreTimeoutSeconds} (default 10s). This
     * constructor runs from {@code HttpState}, which the netty {@code LifeCycle}
     * constructor builds BEFORE any listening port is bound, so an unbounded read against a
     * blob-store endpoint that drops packets would delay startup for the cloud SDK's full
     * retry budget (measured at ~120s for AWS SDK v2 defaults: 4 attempts x a 30s socket
     * timeout), failing readiness probes and container wait strategies. On expiry MockServer
     * logs a WARN and starts with no restored expectations. Set the property to 0 to skip
     * the restore entirely.
     */
    private void reloadPersistedExpectations(List<String> expandedInitializationJsonPaths) {
        if (blobStore instanceof FilesystemBlobStore) {
            return;
        }
        int timeoutSeconds = configuration.blobStoreRestoreTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            logEvent(DEBUG, "skipping restore of persisted expectations from blob store because blobStoreRestoreTimeoutSeconds is " + timeoutSeconds, null);
            return;
        }
        warnIfInitializationPathShadowsPersistencePath(expandedInitializationJsonPaths);
        ExecutorService restoreExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MockServer-blobStoreRestore");
            // daemon so a blob store that never responds can never hold up JVM exit
            thread.setDaemon(true);
            return thread;
        });
        try {
            // A bare "timed out" is not diagnosable on its own: the real cause (credentials denied,
            // DNS failure, 403, endpoint typo) is known only to the worker, which by then is still
            // running. When the deadline has already passed, the worker logs its own failure at
            // DEBUG. It never touches requestMatchers -- the restore is abandoned, so a late worker
            // can no more resurrect expectations than before; this is diagnostics only.
            AtomicBoolean deadlineExpired = new AtomicBoolean(false);
            Future<Optional<Blob>> pendingBlob = restoreExecutor.submit(() -> {
                try {
                    return blobStore.get(blobKey);
                } catch (Exception exception) {
                    if (deadlineExpired.get()) {
                        logEvent(DEBUG, "blob store restore for key " + blobKey
                            + " failed after its " + timeoutSeconds + " second deadline had already expired; startup continued without it", exception);
                    }
                    throw exception;
                }
            });
            Optional<Blob> blob;
            try {
                blob = pendingBlob.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException timeoutException) {
                deadlineExpired.set(true);
                logEvent(WARN, "timed out after " + timeoutSeconds + " second(s) restoring persisted expectations from blob store key " + blobKey
                    + " - starting with no restored expectations (configure mockserver.blobStoreRestoreTimeoutSeconds to change this deadline)", null);
                return;
            } catch (InterruptedException interruptedException) {
                pendingBlob.cancel(true);
                Thread.currentThread().interrupt();
                logEvent(WARN, "interrupted while restoring persisted expectations from blob store key " + blobKey, null);
                return;
            } catch (ExecutionException executionException) {
                throw executionException.getCause() != null ? executionException.getCause() : executionException;
            }
            if (!blob.isPresent()) {
                // MAJOR: the blob key is the FILE NAME of persistedExpectationsPath, so a
                // differently-named persistedExpectationsPath silently yields a different key
                // and restores nothing. Say so, naming the key that was looked for.
                logEvent(INFO, "no persisted expectations found in blob store under key " + blobKey
                    + " - restore requires the same bucket, the same blobStoreKeyPrefix and the same persistedExpectationsPath file name", null);
                return;
            }
            String json = new String(blob.get().getData(), UTF_8);
            if (!isNotBlank(json)) {
                logEvent(INFO, "persisted expectations blob at key " + blobKey + " is empty - nothing to restore", null);
                return;
            }
            Expectation[] expectations = new ExpectationSerializer(mockServerLogger).deserializeArray(json, true);
            if (expectations == null || expectations.length == 0) {
                logEvent(INFO, "persisted expectations blob at key " + blobKey + " contained no expectations - nothing to restore", null);
                return;
            }
            requestMatchers.update(expectations, new Cause(BLOB_STORE_CAUSE_SOURCE_PREFIX + blobKey, Cause.Type.FILE_INITIALISER));
            logEvent(INFO, "restored " + expectations.length + " persisted expectation(s) from blob store key " + blobKey, null);
        } catch (Throwable throwable) {
            logEvent(Level.ERROR, "exception while restoring persisted expectations from blob store for " + configuration.persistedExpectationsPath(), throwable);
        } finally {
            // shutdown(), NOT shutdownNow(): on every non-timeout path the task has already finished
            // so this terminates the pool immediately, while on the timeout path it lets the
            // abandoned worker run to completion on its DAEMON thread so it can log the real cause.
            // shutdownNow() would interrupt it and replace that cause with "interrupted".
            restoreExecutor.shutdown();
        }
    }

    /**
     * Warns when {@code initializationJsonPath} resolves to {@code persistedExpectationsPath}
     * while a NON-filesystem blob store is configured. That combination is the documented
     * setup for FILESYSTEM persistence, but under a cloud blob store the initializer reads a
     * local file the bucket never populates, so it contributes nothing and only confuses the
     * picture. (The restore itself is protected from it by
     * {@link #BLOB_STORE_CAUSE_SOURCE_PREFIX}.)
     *
     * @param expandedInitializationJsonPaths the already-expanded initialization paths, passed in
     *                                        rather than recomputed because expanding a glob runs a
     *                                        full classpath scan
     */
    private void warnIfInitializationPathShadowsPersistencePath(List<String> expandedInitializationJsonPaths) {
        if (expandedInitializationJsonPaths.isEmpty()) {
            return;
        }
        // compare against the ABSOLUTE local path rather than the blob key: for a cloud store the
        // blob key is now the bare file name, which would both miss an absolute initializationJsonPath
        // and (for a relative persistedExpectationsPath) match one that points somewhere else entirely
        if (expandedInitializationJsonPaths.contains(configuration.persistedExpectationsPath())
            || expandedInitializationJsonPaths.contains(filePath.toAbsolutePath().toString())) {
            logEvent(WARN, "initializationJsonPath resolves to persistedExpectationsPath (" + configuration.persistedExpectationsPath()
                + ") while a non-filesystem blob store is configured - the initializer reads the LOCAL file, which the blob store never populates, "
                + "so it adds nothing; persisted expectations are restored from the blob store instead and initializationJsonPath can be removed", null);
        }
    }

    /**
     * Logs at the given level when a logger is available. The whole restore path must
     * tolerate a null logger: the {@code (Configuration, MockServerLogger, RequestMatchers, BlobStore)}
     * constructor accepts one, and turning a logged, recoverable restore failure into a
     * NullPointerException would fail startup outright. Level filtering is left to
     * {@link MockServerLogger#logEvent(LogEntry)}, which already applies it.
     */
    private void logEvent(Level level, String message, Throwable throwable) {
        if (mockServerLogger == null) {
            return;
        }
        LogEntry logEntry = new LogEntry()
            .setLogLevel(level)
            .setMessageFormat(message);
        if (throwable != null) {
            logEntry.setThrowable(throwable);
        }
        mockServerLogger.logEvent(logEntry);
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
