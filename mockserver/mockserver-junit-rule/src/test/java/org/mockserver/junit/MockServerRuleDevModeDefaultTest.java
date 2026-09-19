package org.mockserver.junit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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
 * Asserts the JUnit 4 rule's dev-mode policy: dev mode is on by default (fixing the
 * 1000/1000 store sizes), an explicit {@code -Dmockserver.devMode=false} still turns it
 * off, an explicit {@code maxLogEntries} still wins over the dev-mode default, and the
 * discoverability line is logged once per JVM and only when INFO is enabled. These mutate
 * global {@link ConfigurationProperties} system-property state, so each test fully resets
 * that state (system property, the cache-first value cache, and the once-per-JVM log latch)
 * before and after it runs. The junit-rule module runs its unit tests in a single,
 * non-parallel Surefire fork, so this reset — mirroring {@code ConfigurationTest} in
 * mockserver-core — is what keeps them isolated; there is no parallel phase to register
 * them in.
 */
public class MockServerRuleDevModeDefaultTest {

    private static final String DEV_MODE = "mockserver.devMode";
    private static final String MAX_LOG_ENTRIES = "mockserver.maxLogEntries";
    private static final String MAX_EXPECTATIONS = "mockserver.maxExpectations";
    private static final String LOG_LEVEL = "mockserver.logLevel";

    @Before
    @After
    public void resetGlobalConfigurationState() {
        clearPropertyAndCache(DEV_MODE);
        clearPropertyAndCache(MAX_LOG_ENTRIES);
        clearPropertyAndCache(MAX_EXPECTATIONS);
        clearPropertyAndCache(LOG_LEVEL);
        setOnceLatch(false);
    }

    @Test
    public void enablesDevModeByDefault() {
        MockServerRule.applyDevModeDefault();

        assertThat("dev mode is on by default", ConfigurationProperties.devMode(), is(true));
        assertThat(ConfigurationProperties.maxLogEntries(), is(equalTo(1000)));
        assertThat(ConfigurationProperties.maxExpectations(), is(equalTo(1000)));
    }

    @Test
    public void explicitDevModeFalseWinsOverDefault() {
        System.setProperty(DEV_MODE, "false");

        MockServerRule.applyDevModeDefault();

        assertThat("an explicit -Dmockserver.devMode=false is honoured over the default", ConfigurationProperties.devMode(), is(false));
        assertThat(ConfigurationProperties.maxLogEntries(), is(greaterThan(1000)));
        assertThat(ConfigurationProperties.maxExpectations(), is(greaterThan(1000)));
    }

    @Test
    public void explicitMaxLogEntriesWinsOverDefaultDevMode() {
        System.setProperty(MAX_LOG_ENTRIES, "25000");

        MockServerRule.applyDevModeDefault();

        assertThat("dev mode is on by default", ConfigurationProperties.devMode(), is(true));
        assertThat("an explicit size wins over the dev-mode 1000 default", ConfigurationProperties.maxLogEntries(), is(equalTo(25000)));
        assertThat(ConfigurationProperties.maxExpectations(), is(equalTo(1000)));
    }

    @Test
    public void logsOncePerJvmAndOnlyWhenInfoIsEnabled() {
        System.setProperty(DEV_MODE, "true");

        ConfigurationProperties.logLevel("WARN");
        MockServerRule.applyDevModeDefault();
        assertThat("the INFO line is not emitted (and the latch is not consumed) at WARN", onceLatch(), is(false));

        ConfigurationProperties.logLevel("INFO");
        MockServerRule.applyDevModeDefault();
        assertThat("the INFO line is emitted once INFO is enabled", onceLatch(), is(true));

        MockServerRule.applyDevModeDefault();
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
            Field field = MockServerRule.class.getDeclaredField("DEV_MODE_DEFAULT_LOGGED");
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
