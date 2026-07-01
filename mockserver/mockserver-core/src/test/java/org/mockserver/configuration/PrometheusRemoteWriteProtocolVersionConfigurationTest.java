package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Verifies {@link ConfigurationProperties#prometheusRemoteWriteProtocolVersion()} resolution:
 * it defaults to {@code v1} and honours a system-property / setter override.
 * <p>
 * This test mutates the global {@code mockserver.prometheusRemoteWriteProtocolVersion} system
 * property and the property cache, so it must run in the sequential Surefire phase (see
 * mockserver-core/pom.xml).
 */
public class PrometheusRemoteWriteProtocolVersionConfigurationTest {

    private static final String KEY = "mockserver.prometheusRemoteWriteProtocolVersion";

    @Before
    @After
    public void resetProperty() throws Exception {
        // mirror the production clearProperty() cleanup: system property, property cache,
        // and the programmatically-set-keys set, so this sequential-phase test leaks no state
        System.clearProperty(KEY);
        clearCacheEntry(KEY);
        clearProgrammaticallySetKey(KEY);
    }

    @Test
    public void defaultsToV1WhenUnset() {
        assertThat(ConfigurationProperties.prometheusRemoteWriteProtocolVersion(), is("v1"));
    }

    @Test
    public void honoursSystemPropertyOverride() throws Exception {
        System.setProperty(KEY, "v2");
        clearCacheEntry(KEY);

        assertThat(ConfigurationProperties.prometheusRemoteWriteProtocolVersion(), is("v2"));
    }

    @Test
    public void roundTripsViaSetter() {
        ConfigurationProperties.prometheusRemoteWriteProtocolVersion("v2");

        assertThat(ConfigurationProperties.prometheusRemoteWriteProtocolVersion(), is("v2"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> propertyCache() throws Exception {
        java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        return cache instanceof Map ? (Map<String, String>) cache : null;
    }

    private static void clearCacheEntry(String key) throws Exception {
        Map<String, String> cache = propertyCache();
        if (cache != null) {
            cache.remove(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearProgrammaticallySetKey(String key) throws Exception {
        java.lang.reflect.Field keysField = ConfigurationProperties.class.getDeclaredField("programmaticallySetKeys");
        keysField.setAccessible(true);
        Object keys = keysField.get(null);
        if (keys instanceof Set) {
            ((Set<String>) keys).remove(key);
        }
    }
}
