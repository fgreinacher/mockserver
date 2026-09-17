package org.mockserver.junit.jupiter;

import org.junit.jupiter.api.extension.*;
import org.junit.platform.commons.support.AnnotationSupport;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class MockServerExtension implements ParameterResolver, BeforeAllCallback, BeforeEachCallback, AfterAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(MockServerExtension.class);
    private static final String CLIENT_KEY = "clientAndServer";
    private static final String OWNER_KEY = "ownerExtension";
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(MockServerExtension.class);
    private static final AtomicBoolean DEV_MODE_DEFAULT_LOGGED = new AtomicBoolean(false);
    /**
     * Single switch for whether the JUnit integration turns dev mode on by default.
     * {@code false} = opt-in (current): the integration does not force dev mode and
     * only respects an explicit {@code mockserver.devMode}. Flip to {@code true} to
     * turn it on by default. This is the one line to change to move between the two
     * shipping choices.
     */
    static final boolean ENABLE_DEV_MODE_BY_DEFAULT = false;
    // Mirror of the package-private ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES /
    // DEV_MODE_MAX_EXPECTATIONS (both 1000), used only to tell whether dev mode actually
    // reduced the effective sizes when composing the discoverability line.
    private static final int DEV_MODE_STORE_SIZE = 1000;
    protected static ClientAndServer perTestSuiteClientAndServer;
    protected ClientAndServer customClientAndServer;
    protected ClientAndServer clientAndServer;
    protected boolean perTestSuite;
    protected boolean resetBeforeEach;

    public MockServerExtension() {

    }

    public MockServerExtension(ClientAndServer clientAndServer) {
        this.customClientAndServer = clientAndServer;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return MockServerClient.class.isAssignableFrom(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        if (clientAndServer == null) {
            ensureStarted(extensionContext);
        }
        return clientAndServer;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        ensureStarted(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // beforeAll has already run ensureStarted, so clientAndServer and the
        // resetBeforeEach flag are deterministically set by the time we get here.
        if (resetBeforeEach && clientAndServer != null && clientAndServer.isRunning()) {
            clientAndServer.reset();
        }
    }

    private void ensureStarted(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        ClientAndServer existing = store.get(CLIENT_KEY, ClientAndServer.class);
        if (existing != null) {
            clientAndServer = existing;
            return;
        }
        List<Integer> ports = new ArrayList<>();
        Optional<MockServerSettings> mockServerSettingsOptional = retrieveAnnotationFromTestClass(context);
        if (mockServerSettingsOptional.isPresent()) {
            MockServerSettings mockServerSettings = mockServerSettingsOptional.get();
            perTestSuite = mockServerSettings.perTestSuite();
            resetBeforeEach = mockServerSettings.resetBeforeEach();
            for (int port : mockServerSettings.ports()) {
                ports.add(port);
            }
        }
        clientAndServer = instantiateClient(ports);
        store.put(CLIENT_KEY, clientAndServer);
        store.put(OWNER_KEY, this);
    }

    ClientAndServer instantiateClient(List<Integer> ports) {
        applyDevModeDefault();
        synchronized (MockServerExtension.class) {
            if (perTestSuite) {
                if (perTestSuiteClientAndServer == null) {
                    perTestSuiteClientAndServer = ClientAndServer.startClientAndServer(ports);
                    Runtime.getRuntime().addShutdownHook(new Scheduler.SchedulerThreadFactory("MockServer Test Extension ShutdownHook").newThread(() -> perTestSuiteClientAndServer.stop()));
                }
                return perTestSuiteClientAndServer;
            } else if (customClientAndServer != null) {
                return customClientAndServer;
            } else {
                return ClientAndServer.startClientAndServer(ports);
            }
        }
    }

    /**
     * Apply the JUnit integration's dev-mode policy at server start. Dev mode fixes
     * the in-memory store sizes at {@code maxLogEntries} / {@code maxExpectations} =
     * 1000 instead of the heap-ceiling-derived defaults (up to 100000 / 15000),
     * which lets a suite that starts many short-lived servers in one JVM trim its
     * memory footprint (a measured ~117&nbsp;MB &rarr; ~52&nbsp;MB across 32 in-JVM
     * instances).
     * <p>
     * Dev mode is <strong>opt-in</strong> ({@link #ENABLE_DEV_MODE_BY_DEFAULT} is
     * {@code false}): the integration does not force it on. A user enables it with
     * {@code -Dmockserver.devMode=true} (or {@code MOCKSERVER_DEV_MODE=true}). When
     * it is enabled, an explicit {@code maxLogEntries} / {@code maxExpectations}
     * value (system property, environment variable, or {@code mockserver.properties})
     * still wins over the 1000 default through normal property resolution — that is
     * how a suite that needs a larger store keeps one while still using dev mode.
     * <p>
     * Enabling dev mode is global, so it also governs a {@link ClientAndServer}
     * constructed directly later in the same JVM (unless that instance sets its own
     * sizes). Because the store sizes are read once and cached, dev mode only reduces
     * them when it is enabled before the first read; the discoverability line, logged
     * once per JVM at {@code INFO} whenever dev mode is in effect, reports the
     * effective sizes and says plainly which of them dev mode did not reduce.
     */
    static void applyDevModeDefault() {
        if (ENABLE_DEV_MODE_BY_DEFAULT
            && isBlank(System.getProperty("mockserver.devMode"))
            && isBlank(System.getenv("MOCKSERVER_DEV_MODE"))) {
            ConfigurationProperties.devMode(true);
        }
        if (ConfigurationProperties.devMode()
            && MOCK_SERVER_LOGGER.isEnabledForInstance(Level.INFO)
            && DEV_MODE_DEFAULT_LOGGED.compareAndSet(false, true)) {
            int effectiveLogEntries = ConfigurationProperties.maxLogEntries();
            int effectiveExpectations = ConfigurationProperties.maxExpectations();
            String messageFormat;
            if (effectiveLogEntries == DEV_MODE_STORE_SIZE && effectiveExpectations == DEV_MODE_STORE_SIZE) {
                messageFormat = "MockServer dev mode is enabled - in-memory store sizes are fixed at maxLogEntries={} and maxExpectations={} to keep test-suite memory small; a verify that stops matching after this many entries is being silently evicted - raise it with -Dmockserver.maxLogEntries / -Dmockserver.maxExpectations, or disable dev mode with -Dmockserver.devMode=false";
            } else {
                messageFormat = "MockServer dev mode is enabled and the effective in-memory store sizes are maxLogEntries={} and maxExpectations={} - any size not at the dev default of 1000 was either set explicitly or already resolved before dev mode was enabled, so dev mode did not reduce that one; set -Dmockserver.maxLogEntries / -Dmockserver.maxExpectations to control them explicitly";
            }
            MOCK_SERVER_LOGGER.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat(messageFormat)
                    .setArguments(effectiveLogEntries, effectiveExpectations)
            );
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);
        if (!perTestSuite && this == store.get(OWNER_KEY) && clientAndServer != null && clientAndServer.isRunning()) {
            clientAndServer.stop();
            store.remove(CLIENT_KEY);
            store.remove(OWNER_KEY);
        }
    }

    private Optional<MockServerSettings> retrieveAnnotationFromTestClass(final ExtensionContext context) {
        ExtensionContext currentContext = context;
        Optional<MockServerSettings> annotation;

        do {
            annotation = AnnotationSupport.findAnnotation(currentContext.getElement(), MockServerSettings.class);
            if (!currentContext.getParent().isPresent()) {
                break;
            }
            currentContext = currentContext.getParent().get();
        } while (!annotation.isPresent() && currentContext != context.getRoot());

        return annotation;
    }
}
