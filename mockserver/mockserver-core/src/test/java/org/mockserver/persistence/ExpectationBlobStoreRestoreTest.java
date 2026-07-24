package org.mockserver.persistence;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.server.initialize.ExpectationInitializerLoader;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobStore;
import org.mockserver.state.FilesystemBlobStore;
import org.mockserver.state.InMemoryBlobStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Docker-free unit coverage for the startup restore of cloud-persisted expectations added to
 * {@link ExpectationFileSystemPersistence}. The production fix lives in {@code mockserver-core},
 * so its tests belong here where they always execute — the end-to-end MinIO test in
 * {@code mockserver-blob-s3} is a supplement, not the only proof.
 * <p>
 * {@link InMemoryBlobStore} stands in for a cloud store: the restore path skips only
 * {@link FilesystemBlobStore}, so any other implementation exercises exactly the production
 * code path S3/GCS/Azure take.
 */
public class ExpectationBlobStoreRestoreTest {

    private static final String ONE_EXPECTATION_JSON = "[ {" +
        "  \"httpRequest\" : { \"path\" : \"/restored\" }," +
        "  \"httpResponse\" : { \"body\" : \"restored-body\" }," +
        "  \"id\" : \"restored-one\"," +
        "  \"priority\" : 0," +
        "  \"timeToLive\" : { \"unlimited\" : true }," +
        "  \"times\" : { \"unlimited\" : true }" +
        "} ]";

    private MockServerLogger mockServerLogger;
    private RequestMatchers requestMatchers;

    @Before
    public void createRequestMatchers() {
        Configuration matcherConfiguration = configuration();
        mockServerLogger = new MockServerLogger(matcherConfiguration, ExpectationBlobStoreRestoreTest.class);
        requestMatchers = new RequestMatchers(matcherConfiguration, mockServerLogger, new Scheduler(matcherConfiguration, mockServerLogger), new WebSocketClientRegistry(matcherConfiguration, mockServerLogger));
    }

    private Configuration persistenceConfiguration(String persistedExpectationsPath) {
        return configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectationsPath);
    }

    private String temporaryPersistedExpectationsPath(String prefix) throws Exception {
        File persistedExpectations = File.createTempFile(prefix, ".json");
        persistedExpectations.deleteOnExit();
        return persistedExpectations.getAbsolutePath();
    }

    /**
     * The key the persistence layer uses for a NON-filesystem blob store: the file NAME of
     * persistedExpectationsPath, never the absolute local path (which is not a valid
     * object-store key -- see {@link org.mockserver.state.BlobKeys}).
     */
    private String blobKeyFor(String persistedExpectationsPath) {
        return new File(persistedExpectationsPath).getName();
    }

    private List<String> activeExpectationIds() {
        return requestMatchers.retrieveActiveExpectations(null).stream().map(Expectation::getId).collect(java.util.stream.Collectors.toList());
    }

    // ---------------------------------------------------------------- happy path

    @Test
    public void shouldRestorePersistedExpectationsFromACloudBlobStoreOnStartup() throws Exception {
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreHappy");
        InMemoryBlobStore blobStore = new InMemoryBlobStore();
        blobStore.put(blobKeyFor(persistedExpectationsPath), ONE_EXPECTATION_JSON.getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(persistenceConfiguration(persistedExpectationsPath), mockServerLogger, requestMatchers, blobStore);

            assertThat(activeExpectationIds(), contains("restored-one"));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    // ---------------------------------------------------------------- filesystem skip

    @Test
    public void shouldNotRestoreThroughTheFilesystemBlobStore() throws Exception {
        // the filesystem store reloads via initializationJsonPath instead; restoring here too
        // would load the same local file twice
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreFilesystem");
        Files.write(new File(persistedExpectationsPath).toPath(), ONE_EXPECTATION_JSON.getBytes(StandardCharsets.UTF_8));

        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(persistenceConfiguration(persistedExpectationsPath), mockServerLogger, requestMatchers, new FilesystemBlobStore(mockServerLogger));

            assertThat(activeExpectationIds(), is(empty()));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    // ---------------------------------------------------------------- blank payload

    @Test
    public void shouldStartCleanlyWhenThePersistedBlobIsBlank() throws Exception {
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreBlank");
        InMemoryBlobStore blobStore = new InMemoryBlobStore();
        blobStore.put(blobKeyFor(persistedExpectationsPath), "   ".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(persistenceConfiguration(persistedExpectationsPath), mockServerLogger, requestMatchers, blobStore);

            assertThat(activeExpectationIds(), is(empty()));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    // ---------------------------------------------------------------- malformed payload

    @Test
    public void shouldStartWhenThePersistedBlobIsMalformedRatherThanFailingStartup() throws Exception {
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreMalformed");
        InMemoryBlobStore blobStore = new InMemoryBlobStore();
        blobStore.put(blobKeyFor(persistedExpectationsPath), "{ this is not valid expectation json".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        ExpectationFileSystemPersistence persistence = null;
        try {
            // must not throw: a corrupt persisted document is recoverable, an unstartable server is not
            persistence = new ExpectationFileSystemPersistence(persistenceConfiguration(persistedExpectationsPath), mockServerLogger, requestMatchers, blobStore);

            assertThat(activeExpectationIds(), is(empty()));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    @Test
    public void shouldNotFailStartupOnARestoreErrorWhenNoLoggerIsSupplied() throws Exception {
        // the four-argument constructor accepts a null logger; a NullPointerException in the
        // failure branch would turn a logged, recoverable restore failure into a hard crash
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreNullLogger");
        BlobStore throwingBlobStore = new ThrowingBlobStore();

        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(persistenceConfiguration(persistedExpectationsPath), null, requestMatchers, throwingBlobStore);

            assertThat(activeExpectationIds(), is(empty()));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    // ---------------------------------------------------------------- missing key

    @Test
    public void shouldStartCleanlyWhenNoPersistedBlobExistsForTheKey() throws Exception {
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreMissing");
        InMemoryBlobStore blobStore = new InMemoryBlobStore();
        // deliberately stored under a DIFFERENT key, as happens when the working directory or
        // persistedExpectationsPath differs between runs
        blobStore.put(blobKeyFor(persistedExpectationsPath) + ".other", ONE_EXPECTATION_JSON.getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        CapturingMockServerLogger capturingLogger = new CapturingMockServerLogger();
        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(persistenceConfiguration(persistedExpectationsPath), capturingLogger, requestMatchers, blobStore);

            assertThat(activeExpectationIds(), is(empty()));
            // a key miss must not pass silently: the key is the FILE NAME of
            // persistedExpectationsPath, so a differently-named path silently misses
            assertThat("a key miss must be reported, with the key it looked for",
                capturingLogger.messagesContaining("no persisted expectations found in blob store under key " + blobKeyFor(persistedExpectationsPath)), is(not(empty())));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    // ---------------------------------------------------------------- initializer collision

    @Test
    public void shouldKeepRestoredExpectationsWhenInitializationJsonPathMatchesPersistedExpectationsPath() throws Exception {
        // the migration case: a user moving from filesystem persistence to a cloud blob store
        // keeps initializationJsonPath pointing at persistedExpectationsPath, which is the
        // documented filesystem setup. The local file is empty (the bucket holds the state), and
        // ExpectationInitializerLoader calls update(EMPTY, new Cause(initializationJsonPath,
        // FILE_INITIALISER)) unconditionally. Cause has value equality and update() removes every
        // matcher whose source equals the cause, so a colliding cause source silently deletes
        // everything just restored.
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreInitCollision");
        InMemoryBlobStore blobStore = new InMemoryBlobStore();
        blobStore.put(blobKeyFor(persistedExpectationsPath), ONE_EXPECTATION_JSON.getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        Configuration configuration = persistenceConfiguration(persistedExpectationsPath)
            .initializationJsonPath(persistedExpectationsPath);

        CapturingMockServerLogger capturingLogger = new CapturingMockServerLogger();
        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(configuration, capturingLogger, requestMatchers, blobStore);
            assertThat("restored before the initializer runs", activeExpectationIds(), contains("restored-one"));

            // HttpState constructs the initializer AFTER the persistence, in this order
            new ExpectationInitializerLoader(configuration, mockServerLogger, requestMatchers);

            assertThat("restored expectations must survive the initializer reading the empty local file",
                activeExpectationIds(), contains("restored-one"));
            assertThat("the confusing configuration must be called out",
                capturingLogger.messagesContaining("initializationJsonPath resolves to persistedExpectationsPath"), is(not(empty())));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    // ---------------------------------------------------------------- bounded startup

    @Test
    public void shouldBoundStartupWhenTheBlobStoreNeverResponds() throws Exception {
        // the restore runs from the HttpState constructor, which the netty LifeCycle constructor
        // builds BEFORE binding any port. An unbounded read against an endpoint that drops packets
        // delays startup for the cloud SDK's whole retry budget (~120s for AWS SDK v2 defaults).
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreHang");
        BlockingBlobStore blobStore = new BlockingBlobStore();

        Configuration configuration = persistenceConfiguration(persistedExpectationsPath)
            .blobStoreRestoreTimeoutSeconds(1);

        CapturingMockServerLogger capturingLogger = new CapturingMockServerLogger();
        ExpectationFileSystemPersistence persistence = null;
        try {
            long startedAt = System.currentTimeMillis();
            persistence = new ExpectationFileSystemPersistence(configuration, capturingLogger, requestMatchers, blobStore);
            long elapsedMillis = System.currentTimeMillis() - startedAt;

            assertThat("construction must return once the restore deadline expires, not when the blob store does",
                elapsedMillis, is(lessThan(30_000L)));
            assertThat(activeExpectationIds(), is(empty()));
            assertThat("expiry must be announced rather than silently starting empty",
                capturingLogger.messagesContaining("timed out after 1 second(s) restoring persisted expectations"), is(not(empty())));
        } finally {
            blobStore.release();
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    @Test
    public void shouldLogTheUnderlyingCauseWhenTheBlobStoreFailsAfterTheDeadlineHasExpired() throws Exception {
        // "timed out" alone is not diagnosable -- the real cause (credentials denied, DNS failure,
        // 403, endpoint typo) is known only to the abandoned worker. It must still reach the log.
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreLateFailure");
        BlobStore slowFailingBlobStore = new BlobStore() {
            @Override
            public void put(String key, byte[] data, Map<String, String> metadata) {
            }

            @Override
            public Optional<Blob> get(String key) {
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted - the worker was cancelled instead of being allowed to report its cause");
                }
                throw new IllegalStateException("the-real-underlying-cause");
            }

            @Override
            public List<String> list(String prefix) {
                return Collections.emptyList();
            }

            @Override
            public boolean delete(String key) {
                return false;
            }
        };

        Configuration configuration = persistenceConfiguration(persistedExpectationsPath)
            .blobStoreRestoreTimeoutSeconds(1);

        CapturingMockServerLogger capturingLogger = new CapturingMockServerLogger();
        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(configuration, capturingLogger, requestMatchers, slowFailingBlobStore);
            assertThat(capturingLogger.messagesContaining("timed out after 1 second(s)"), is(not(empty())));

            // the worker keeps running on its daemon thread; wait for it to fail and report
            long deadline = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < deadline
                && capturingLogger.messagesContainingAll("failed after its 1 second deadline had already expired",
                    "cause: the-real-underlying-cause").isEmpty()) {
                TimeUnit.MILLISECONDS.sleep(100);
            }
            // the REAL cause, not the "interrupted" a shutdownNow() regression would substitute
            assertThat("the abandoned worker must report the real underlying cause once it arrives; captured was "
                    + capturingLogger.allMessages(),
                capturingLogger.messagesContainingAll("failed after its 1 second deadline had already expired",
                    "cause: the-real-underlying-cause"), is(not(empty())));

            // and it must NOT have resurrected anything after startup moved on
            assertThat(activeExpectationIds(), is(empty()));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    @Test
    public void shouldSkipTheRestoreEntirelyWhenTheDeadlineIsZero() throws Exception {
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("restoreDisabled");
        InMemoryBlobStore blobStore = new InMemoryBlobStore();
        blobStore.put(blobKeyFor(persistedExpectationsPath), ONE_EXPECTATION_JSON.getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        Configuration configuration = persistenceConfiguration(persistedExpectationsPath)
            .blobStoreRestoreTimeoutSeconds(0);

        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(configuration, mockServerLogger, requestMatchers, blobStore);

            assertThat(activeExpectationIds(), is(empty()));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    // ---------------------------------------------------------------- object-store key shape

    @Test
    public void shouldUseAValidObjectStoreKeyRatherThanTheAbsoluteLocalPathForACloudStore() throws Exception {
        // An absolute local path is not a valid object-store key: it starts with '/', which S3
        // treats as a distinct name and MinIO rejects outright ("Object name contains unsupported
        // characters", HTTP 400), and combined with a blobStoreKeyPrefix ending in '/' it produced
        // a '//'. The key must be the FILE NAME alone.
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("keyShape");
        KeyCapturingBlobStore blobStore = new KeyCapturingBlobStore();

        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(persistenceConfiguration(persistedExpectationsPath), mockServerLogger, requestMatchers, blobStore);

            assertThat("the restore must read the file-name key", blobStore.requestedKeys, contains(blobKeyFor(persistedExpectationsPath)));
            for (String key : blobStore.requestedKeys) {
                assertThat("an object-store key must not start with a separator", key.startsWith("/"), is(false));
                assertThat("an object-store key must not contain a doubled separator", key.contains("//"), is(false));
                assertThat("an object-store key must not embed the local filesystem layout",
                    key.contains(File.separator), is(false));
            }
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    @Test
    public void shouldKeepTheAbsolutePathAsTheKeyForTheFilesystemBlobStore() throws Exception {
        // the filesystem store INTERPRETS the key as a file path, so shortening it to the file
        // name would silently relocate on-disk persistence to the working directory
        String persistedExpectationsPath = temporaryPersistedExpectationsPath("keyShapeFilesystem");
        Configuration configuration = persistenceConfiguration(persistedExpectationsPath);

        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(configuration, mockServerLogger, requestMatchers, new FilesystemBlobStore(mockServerLogger));
            requestMatchers.add(new Expectation(org.mockserver.model.HttpRequest.request().withPath("/written"))
                .thenRespond(org.mockserver.model.HttpResponse.response().withBody("written-body")), Cause.API);

            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline && new File(persistedExpectationsPath).length() == 0) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertThat("the filesystem store must still write the configured absolute path",
                new String(Files.readAllBytes(new File(persistedExpectationsPath).toPath()), StandardCharsets.UTF_8).contains("/written"), is(true));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    // ---------------------------------------------------------------- test doubles

    /**
     * Records every key the persistence layer asks for, so the SHAPE of the object-store key
     * can be asserted rather than only its round-trip behaviour.
     */
    private static class KeyCapturingBlobStore implements BlobStore {

        private final List<String> requestedKeys = Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void put(String key, byte[] data, Map<String, String> metadata) {
            requestedKeys.add(key);
        }

        @Override
        public Optional<Blob> get(String key) {
            requestedKeys.add(key);
            return Optional.empty();
        }

        @Override
        public List<String> list(String prefix) {
            return Collections.emptyList();
        }

        @Override
        public boolean delete(String key) {
            return false;
        }
    }

    /**
     * Captures log messages per INSTANCE, so log assertions need no mutation of the static
     * {@code MockServerLogger.globalLogEventListener} and this class can stay in the parallel
     * Surefire phase.
     */
    private static class CapturingMockServerLogger extends MockServerLogger {

        private final List<String> messages = Collections.synchronizedList(new java.util.ArrayList<>());

        CapturingMockServerLogger() {
            super(ExpectationBlobStoreRestoreTest.class);
        }

        @Override
        public void logEvent(org.mockserver.log.model.LogEntry logEntry) {
            // append the throwable's message: for the post-deadline diagnostic the whole point is
            // WHICH cause was reported, so asserting on the message text alone would pass equally
            // for the real cause and for an "interrupted" that a shutdownNow() regression produces
            String throwableMessage = logEntry.getThrowable() != null ? " | cause: " + logEntry.getThrowable().getMessage() : "";
            messages.add(String.valueOf(logEntry.getMessageFormat()) + throwableMessage);
            super.logEvent(logEntry);
        }

        List<String> allMessages() {
            synchronized (messages) {
                return new java.util.ArrayList<>(messages);
            }
        }

        List<String> messagesContaining(String fragment) {
            return messagesContainingAll(fragment);
        }

        /**
         * Messages containing EVERY fragment. Fragments are matched independently rather than as one
         * concatenated string because the production messages interleave other text between the
         * parts under test.
         */
        List<String> messagesContainingAll(String... fragments) {
            synchronized (messages) {
                return messages.stream()
                    .filter(message -> java.util.Arrays.stream(fragments).allMatch(message::contains))
                    .collect(java.util.stream.Collectors.toList());
            }
        }
    }

    /**
     * A blob store whose {@code get} never returns until {@link #release()} is called, standing
     * in for an endpoint that accepts the connection and then black-holes the request.
     */
    private static class BlockingBlobStore implements BlobStore {

        private final CountDownLatch released = new CountDownLatch(1);

        void release() {
            released.countDown();
        }

        @Override
        public void put(String key, byte[] data, Map<String, String> metadata) {
        }

        @Override
        public Optional<Blob> get(String key) {
            try {
                released.await(2, TimeUnit.MINUTES);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }

        @Override
        public List<String> list(String prefix) {
            return Collections.emptyList();
        }

        @Override
        public boolean delete(String key) {
            return false;
        }
    }

    private static class ThrowingBlobStore implements BlobStore {

        @Override
        public void put(String key, byte[] data, Map<String, String> metadata) {
        }

        @Override
        public Optional<Blob> get(String key) {
            throw new IllegalStateException("blob store unavailable");
        }

        @Override
        public List<String> list(String prefix) {
            return Collections.emptyList();
        }

        @Override
        public boolean delete(String key) {
            return false;
        }
    }
}
