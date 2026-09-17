package org.mockserver.junit.jupiter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.configuration.ConfigurationProperties;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Asserts the JUnit 5 extension's dev-mode policy: dev mode is opt-in (not forced on),
 * an explicit {@code -Dmockserver.devMode=true} enables the fixed 1000/1000 store sizes,
 * an explicit {@code maxLogEntries} still wins over that dev-mode default, and the
 * discoverability line is logged once per JVM and only when INFO is enabled. These mutate global
 * {@link ConfigurationProperties} system-property state, so each test fully resets that
 * state (system property, the cache-first value cache, and the once-per-JVM log latch)
 * before and after it runs. The junit-jupiter module runs its unit tests in a single,
 * non-parallel Surefire fork, so this reset — mirroring {@code ConfigurationTest} in
 * mockserver-core — is what keeps them isolated; there is no parallel phase to register
 * them in.
 */
class MockServerExtensionDevModeDefaultTest {

    private static final String DEV_MODE = "mockserver.devMode";
    private static final String MAX_LOG_ENTRIES = "mockserver.maxLogEntries";
    private static final String MAX_EXPECTATIONS = "mockserver.maxExpectations";
    private static final String LOG_LEVEL = "mockserver.logLevel";

    @BeforeEach
    @AfterEach
    void resetGlobalConfigurationState() {
        clearPropertyAndCache(DEV_MODE);
        clearPropertyAndCache(MAX_LOG_ENTRIES);
        clearPropertyAndCache(MAX_EXPECTATIONS);
        clearPropertyAndCache(LOG_LEVEL);
        setOnceLatch(false);
    }

    @Test
    void doesNotEnableDevModeByDefault() {
        MockServerExtension.applyDevModeDefault();

        assertThat("dev mode is opt-in, not forced on", ConfigurationProperties.devMode(), is(false));
        // the heap-ceiling default is larger than the dev-mode cap on the test JVM
        assertThat(ConfigurationProperties.maxLogEntries(), is(greaterThan(1000)));
        assertThat(ConfigurationProperties.maxExpectations(), is(greaterThan(1000)));
    }

    @Test
    void enablingDevModeFixesStoreSizes() {
        System.setProperty(DEV_MODE, "true");

        MockServerExtension.applyDevModeDefault();

        assertThat("an explicit -Dmockserver.devMode=true is honoured", ConfigurationProperties.devMode(), is(true));
        assertThat(ConfigurationProperties.maxLogEntries(), is(equalTo(1000)));
        assertThat(ConfigurationProperties.maxExpectations(), is(equalTo(1000)));
    }

    @Test
    void explicitMaxLogEntriesWinsOverEnabledDevMode() {
        // the headline capability: a suite that needs a larger store keeps one while using dev mode
        System.setProperty(DEV_MODE, "true");
        System.setProperty(MAX_LOG_ENTRIES, "25000");

        MockServerExtension.applyDevModeDefault();

        assertThat("dev mode is enabled", ConfigurationProperties.devMode(), is(true));
        assertThat("an explicit size wins over the dev-mode 1000 default", ConfigurationProperties.maxLogEntries(), is(equalTo(25000)));
        // the size the user did not set still takes the dev-mode default
        assertThat(ConfigurationProperties.maxExpectations(), is(equalTo(1000)));
    }

    @Test
    void logsOncePerJvmAndOnlyWhenInfoIsEnabled() {
        System.setProperty(DEV_MODE, "true");

        ConfigurationProperties.logLevel("WARN");
        MockServerExtension.applyDevModeDefault();
        assertThat("the INFO line is not emitted (and the latch is not consumed) at WARN", onceLatch(), is(false));

        ConfigurationProperties.logLevel("INFO");
        MockServerExtension.applyDevModeDefault();
        assertThat("the INFO line is emitted once INFO is enabled", onceLatch(), is(true));

        MockServerExtension.applyDevModeDefault();
        assertThat("a second start does not emit the line again", onceLatch(), is(true));
    }

    // --- global-state reset helpers (mirror of ConfigurationTest.clearPropertyAndCache) ---

    private static void clearPropertyAndCache(String key) {
        System.clearProperty(key);
        try {
            Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (cache instanceof Map) {
                ((Map<?, ?>) cache).remove(key);
            }
            Field keysField = ConfigurationProperties.class.getDeclaredField("programmaticallySetKeys");
            keysField.setAccessible(true);
            Object keys = keysField.get(null);
            if (keys instanceof Set) {
                ((Set<?>) keys).remove(key);
            }
        } catch (Exception ignore) {
            // best effort — clearing the system property above still helps
        }
    }

    private static AtomicBoolean onceLatchField() {
        try {
            Field field = MockServerExtension.class.getDeclaredField("DEV_MODE_DEFAULT_LOGGED");
            field.setAccessible(true);
            return (AtomicBoolean) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setOnceLatch(boolean value) {
        onceLatchField().set(value);
    }

    private static boolean onceLatch() {
        return onceLatchField().get();
    }
}
