package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Verifies {@link ConfigurationProperties#otelEndpoint()} resolution, including the
 * fallback to the OpenTelemetry-standard {@code OTEL_EXPORTER_OTLP_ENDPOINT} environment
 * variable when the MockServer-specific property/env is unset.
 * <p>
 * This test mutates the global {@code mockserver.otelEndpoint} system property and the
 * property cache, so it must run in the sequential Surefire phase (see mockserver-core/pom.xml).
 */
public class OtelEndpointConfigurationTest {

    private static final String KEY = "mockserver.otelEndpoint";
    private static final String TEMPORALITY_KEY = "mockserver.otelMetricsTemporality";

    @Before
    @After
    public void resetProperty() throws Exception {
        // mirror the production clearProperty() cleanup: system property, property cache,
        // and the programmatically-set-keys set, so this sequential-phase test leaks no state
        // into the effective-config diagnostic used by other sequential-phase tests
        System.clearProperty(KEY);
        clearCacheEntry(KEY);
        clearProgrammaticallySetKey(KEY);
        System.clearProperty(TEMPORALITY_KEY);
        clearCacheEntry(TEMPORALITY_KEY);
        clearProgrammaticallySetKey(TEMPORALITY_KEY);
    }

    @Test
    public void shouldReturnMockServerSpecificValueWhenSet() {
        // when the MockServer-specific property is set
        ConfigurationProperties.otelEndpoint("http://collector:4318");

        // then it wins (takes precedence over any standard env fallback)
        assertThat(ConfigurationProperties.otelEndpoint(), is("http://collector:4318"));
    }

    @Test
    public void shouldUseStandardEnvAsFallbackDefaultWhenSpecificUnset() {
        // the fallback default passed to the resolver is the standard
        // OTEL_EXPORTER_OTLP_ENDPOINT value when it is present
        assertThat(ConfigurationProperties.otelEndpointFallbackDefault("http://otel-standard:4318"), is("http://otel-standard:4318"));
    }

    @Test
    public void shouldFallBackToEmptyWhenStandardEnvBlank() {
        assertThat(ConfigurationProperties.otelEndpointFallbackDefault(null), is(""));
        assertThat(ConfigurationProperties.otelEndpointFallbackDefault(""), is(""));
        assertThat(ConfigurationProperties.otelEndpointFallbackDefault("   "), is(""));
    }

    @Test
    public void shouldResolveEmptyWhenNeitherSpecificNorStandardEnvSet() {
        // given the MockServer-specific property is unset (reset in @Before) and, in the
        // normal test environment, OTEL_EXPORTER_OTLP_ENDPOINT is unset
        String standardEnv = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        String expected = (standardEnv != null && !standardEnv.trim().isEmpty()) ? standardEnv : "";

        // when / then the resolved endpoint is the standard env (if set) or empty
        assertThat(ConfigurationProperties.otelEndpoint(), is(expected));
    }

    @Test
    public void otelMetricsTemporalityDefaultsToCumulative() {
        // unset (reset in @Before) → default is the previous behaviour, cumulative
        assertThat(ConfigurationProperties.otelMetricsTemporality(), is("cumulative"));
    }

    @Test
    public void otelMetricsTemporalityHonoursSystemPropertyOverride() throws Exception {
        System.setProperty(TEMPORALITY_KEY, "delta");
        clearCacheEntry(TEMPORALITY_KEY);

        assertThat(ConfigurationProperties.otelMetricsTemporality(), is("delta"));
    }

    @Test
    public void otelMetricsTemporalityRoundTripsViaSetter() {
        ConfigurationProperties.otelMetricsTemporality("delta");

        assertThat(ConfigurationProperties.otelMetricsTemporality(), is("delta"));
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
