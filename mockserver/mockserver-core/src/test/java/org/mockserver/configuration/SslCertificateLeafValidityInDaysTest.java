package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Resolution of the {@code mockserver.sslCertificateLeafValidityInDays} property: default (397), static
 * override, non-positive fallback, and the per-instance {@link Configuration} form.
 * <p>
 * Mutates the process-wide {@link ConfigurationProperties} static store, so it is registered in the
 * sequential (parallel-excluded) phase of {@code mockserver-core/pom.xml}.
 * <p>
 * {@link ConfigurationProperties} caches every resolved value — including built-in defaults — in its
 * static {@code propertyCache}, so a raw {@link System#setProperty} performed after another test has
 * already read (and therefore cached) the default is silently ignored. Every mutation here is paired
 * with a {@link #clearCacheEntry cache clear} so each read resolves from scratch, mirroring the
 * production {@code clearProperty()} cleanup without widening its visibility (see
 * {@link PrometheusRemoteWriteProtocolVersionConfigurationTest} for the same convention).
 */
public class SslCertificateLeafValidityInDaysTest {

    private static final String KEY = "mockserver.sslCertificateLeafValidityInDays";

    @Before
    @After
    public void resetProperty() throws Exception {
        System.clearProperty(KEY);
        clearCacheEntry(KEY);
        clearProgrammaticallySetKey(KEY);
    }

    @Test
    public void shouldDefaultTo397Days() {
        assertThat(ConfigurationProperties.sslCertificateLeafValidityInDays(), is(397));
    }

    @Test
    public void shouldReturnOverriddenValue() {
        ConfigurationProperties.sslCertificateLeafValidityInDays(3650);

        assertThat(ConfigurationProperties.sslCertificateLeafValidityInDays(), is(3650));
    }

    @Test
    public void shouldFallBackToDefaultForNonPositiveValue() {
        ConfigurationProperties.sslCertificateLeafValidityInDays(0);

        assertThat(ConfigurationProperties.sslCertificateLeafValidityInDays(), is(397));
    }

    @Test
    public void shouldResolveSystemPropertyOverride() throws Exception {
        System.setProperty(KEY, "825");
        clearCacheEntry(KEY);

        assertThat(ConfigurationProperties.sslCertificateLeafValidityInDays(), is(825));
    }

    @Test
    public void configurationInstanceShouldFallBackToStaticStore() {
        Configuration configuration = Configuration.configuration();

        assertThat(configuration.sslCertificateLeafValidityInDays(), is(ConfigurationProperties.sslCertificateLeafValidityInDays()));
    }

    @Test
    public void configurationInstanceOverrideShouldWin() {
        Configuration configuration = Configuration.configuration().sslCertificateLeafValidityInDays(730);

        assertThat(configuration.sslCertificateLeafValidityInDays(), is(730));
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
