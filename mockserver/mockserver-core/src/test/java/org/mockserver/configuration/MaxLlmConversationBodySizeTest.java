package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Resolution of {@code mockserver.maxLlmConversationBodySize}: default (1048576), setter override, and
 * min/max clamping.
 * <p>
 * Mutates the process-wide {@link ConfigurationProperties} static store, so it is registered in the
 * sequential (parallel-excluded) phase of {@code mockserver-core/pom.xml}.
 * <p>
 * {@link ConfigurationProperties} caches every resolved value — including built-in defaults — in its
 * static {@code propertyCache}. A raw {@link System#clearProperty} does <em>not</em> evict that cache,
 * so once a sibling test (e.g. one of the override cases below) has cached a non-default value,
 * {@link #shouldReturnDefaultValue} would otherwise read the stale cached value instead of the default.
 * Every reset therefore clears through the cache the same way the production {@code clearProperty()}
 * does, without widening its visibility (see
 * {@link PrometheusRemoteWriteProtocolVersionConfigurationTest} for the same convention).
 */
public class MaxLlmConversationBodySizeTest {

    private static final String KEY = "mockserver.maxLlmConversationBodySize";

    @Before
    @After
    public void resetProperty() throws Exception {
        System.clearProperty(KEY);
        clearCacheEntry(KEY);
        clearProgrammaticallySetKey(KEY);
    }

    @Test
    public void shouldReturnDefaultValue() {
        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(1048576));
    }

    @Test
    public void shouldReturnOverriddenValue() {
        ConfigurationProperties.maxLlmConversationBodySize(2097152);

        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(2097152));
    }

    @Test
    public void shouldClampBelowMinimum() {
        // given - set value below minimum (16384)
        ConfigurationProperties.maxLlmConversationBodySize(1000);

        // then - should clamp to 16384
        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(16384));
    }

    @Test
    public void shouldClampAboveMaximum() {
        // given - set value above maximum (67108864)
        ConfigurationProperties.maxLlmConversationBodySize(100000000);

        // then - should clamp to 67108864
        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(67108864));
    }

    @Test
    public void shouldAcceptMinimumBoundary() {
        ConfigurationProperties.maxLlmConversationBodySize(16384);

        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(16384));
    }

    @Test
    public void shouldAcceptMaximumBoundary() {
        ConfigurationProperties.maxLlmConversationBodySize(67108864);

        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(67108864));
    }

    @Test
    public void shouldWorkWithConfigurationInstance() {
        Configuration configuration = Configuration.configuration();

        // when - no override set
        // then - should return default from ConfigurationProperties
        assertThat(configuration.maxLlmConversationBodySize(), is(ConfigurationProperties.maxLlmConversationBodySize()));

        // when - set per-instance value
        configuration.maxLlmConversationBodySize(524288);

        // then - should return per-instance value
        assertThat(configuration.maxLlmConversationBodySize(), is(524288));
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
