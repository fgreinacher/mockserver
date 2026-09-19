package org.mockserver.junit;

import com.google.common.annotations.VisibleForTesting;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.PortFactory;
import org.slf4j.event.Level;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("FieldMayBeFinal")
public class MockServerRule implements TestRule {

    @VisibleForTesting
    static ClientAndServer perTestSuiteClientAndServer;
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(MockServerRule.class);
    private static final AtomicBoolean DEV_MODE_DEFAULT_LOGGED = new AtomicBoolean(false);
    /**
     * Single switch for whether the JUnit integration turns dev mode on by default.
     * {@code true} = on by default (current): the integration forces dev mode on
     * unless the user has explicitly set {@code mockserver.devMode}. Set to
     * {@code false} to make it opt-in again. This is the one line to change to move
     * between the two shipping choices.
     */
    static final boolean ENABLE_DEV_MODE_BY_DEFAULT = true;
    // Mirror of the package-private ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES /
    // DEV_MODE_MAX_EXPECTATIONS (both 1000), used only to tell whether dev mode actually
    // reduced the effective sizes when composing the discoverability line.
    private static final int DEV_MODE_STORE_SIZE = 1000;
    private final Object target;
    private final Integer[] ports;
    private final boolean perTestSuite;
    private ClientAndServerFactory clientAndServerFactory;
    private ClientAndServer clientAndServer;

    /**
     * Start the MockServer prior to test execution and stop the MockServer after the tests have completed.
     * This constructor dynamically allocates a free port for the MockServer to use.
     * <p>
     * If the test class contains a MockServerClient field it is set with a client configured for the created MockServer.
     *
     * @param target an instance of the test being executed
     */
    public MockServerRule(Object target) {
        this(target, PortFactory.findFreePort());
    }

    /**
     * Start the MockServer prior to test execution and stop the MockServer after the tests have completed.
     * This constructor dynamically allocates a free port for the MockServer to use.
     * <p>
     * If the test class contains a MockServerClient field it is set with a client configured for the created MockServer.
     *
     * @param target       an instance of the test being executed
     * @param perTestSuite indicates how many instances of MockServer are created
     *                     if true a single MockServer is created per JVM
     *                     if false one instance per test class is created
     */
    public MockServerRule(Object target, boolean perTestSuite) {
        this(target, perTestSuite, PortFactory.findFreePort());
    }

    /**
     * Start the proxy prior to test execution and stop the proxy after the tests have completed.
     * This constructor dynamically create a MockServer that accepts HTTP(s) requests on the specified port
     * <p>
     * If the test class contains a MockServerClient field it is set with a client configured for the created MockServer.
     *
     * @param perTestSuite indicates how many instances of MockServer are created
     *                     if true a single MockServer is created per JVM
     *                     if false one instance per test class is created
     * @param ports  the HTTP(S) port for the proxy
     */
    public MockServerRule(Object target, Integer... ports) {
        this(target, true, ports);
    }

    /**
     * Start the proxy prior to test execution and stop the proxy after the tests have completed.
     * This constructor dynamically create a proxy that accepts HTTP(s) requests on the specified port
     *
     * @param target       an instance of the test being executed
     * @param perTestSuite indicates how many instances of MockServer are created
     *                     if true a single MockServer is created per JVM
     *                     if false one instance per test class is created
     * @param ports        the HTTP(S) port for the proxy
     */
    public MockServerRule(Object target, boolean perTestSuite, Integer... ports) {
        this.ports = ports;
        this.target = target;
        this.perTestSuite = perTestSuite;
        this.clientAndServerFactory = new ClientAndServerFactory(ports);
    }

    public Integer getPort() {
        Integer port = null;
        if (clientAndServer != null) {
            port = clientAndServer.getPort();
        } else if (ports.length > 0) {
            port = ports[0];
        }
        return port;
    }

    public Integer[] getPorts() {
        if (clientAndServer != null) {
            List<Integer> ports = clientAndServer.getLocalPorts();
            return ports.toArray(new Integer[0]);
        }
        return ports;
    }

    public Statement apply(Statement base, Description description) {
        return statement(base);
    }

    private Statement statement(final Statement base) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                if (perTestSuite) {
                    synchronized (MockServerRule.class) {
                        if (perTestSuiteClientAndServer == null) {
                            perTestSuiteClientAndServer = clientAndServerFactory.newClientAndServer();
                        } else {
                            perTestSuiteClientAndServer.reset();
                        }
                    }
                    clientAndServer = perTestSuiteClientAndServer;
                    setMockServerClient(target, perTestSuiteClientAndServer);
                    base.evaluate();
                } else {
                    clientAndServer = clientAndServerFactory.newClientAndServer();
                    setMockServerClient(target, clientAndServer);
                    try {
                        base.evaluate();
                    } finally {
                        clientAndServer.stop();
                    }
                }

            }
        };
    }

    private void setMockServerClient(Object target, ClientAndServer clientAndServer) {
        for (Class<?> clazz = target instanceof Class ? (Class<?>) target : target.getClass(); !clazz.equals(Object.class); clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType().equals(MockServerClient.class)) {
                    field.setAccessible(true);
                    try {
                        field.set(target, clientAndServer);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Error setting MockServerClient field on " + target.getClass().getName(), e);
                    }
                }
            }
        }
    }

    public MockServerClient getClient() {
        return clientAndServer;
    }

    /**
     * Apply the JUnit integration's dev-mode policy at server start. Dev mode fixes
     * the in-memory store sizes at {@code maxLogEntries} / {@code maxExpectations} =
     * 1000 instead of the heap-ceiling-derived defaults (up to 100000 / 15000),
     * which lets a suite that starts many short-lived servers in one JVM trim its
     * memory footprint (a measured ~117&nbsp;MB &rarr; ~52&nbsp;MB across 32 in-JVM
     * instances).
     * <p>
     * Dev mode is <strong>on by default</strong> ({@link #ENABLE_DEV_MODE_BY_DEFAULT}
     * is {@code true}): the integration forces it on unless the user has explicitly
     * set {@code mockserver.devMode}. A user turns it off with
     * {@code -Dmockserver.devMode=false} (or {@code MOCKSERVER_DEV_MODE=false}), which
     * still wins because an explicit setting is honoured over the default. When dev
     * mode is in effect, an explicit {@code maxLogEntries} / {@code maxExpectations}
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

    @VisibleForTesting
    static class ClientAndServerFactory {
        private final Integer[] port;

        public ClientAndServerFactory(Integer... port) {
            this.port = port;
        }

        public ClientAndServer newClientAndServer() {
            applyDevModeDefault();
            return ClientAndServer.startClientAndServer(port);
        }
    }
}
