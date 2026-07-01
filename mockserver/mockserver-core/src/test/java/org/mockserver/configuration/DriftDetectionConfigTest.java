package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.ChaosProbability;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Verifies the drift-detection master switch ({@code driftDetectionEnabled}) and
 * sampling ({@code driftSampleRate}) configuration properties: defaults preserve the
 * historical always-on behaviour, out-of-range sample rates are clamped, per-instance
 * overrides fall back correctly, and the gate expression used at the forward-path call
 * site behaves as expected (enabled=false or rate=0 → drift analysis skipped).
 */
public class DriftDetectionConfigTest {

    @Before
    @After
    public void resetProperties() {
        // Reset to defaults by driving the setters (which also seed the property cache),
        // matching the reset pattern used by the other ConfigurationProperties tests.
        ConfigurationProperties.driftDetectionEnabled(true);
        ConfigurationProperties.driftSampleRate(1.0d);
    }

    // ---- (a) defaults preserve current always-on behaviour ----

    @Test
    public void shouldDefaultDriftDetectionEnabledToTrue() {
        assertThat(ConfigurationProperties.driftDetectionEnabled(), is(true));
    }

    @Test
    public void shouldDefaultDriftSampleRateToOne() {
        assertThat(ConfigurationProperties.driftSampleRate(), is(1.0d));
    }

    @Test
    public void shouldAnalyseByDefault_gateExpression() {
        // given - defaults (enabled + rate 1.0)
        Configuration configuration = Configuration.configuration();

        // then - the forward-path gate evaluates true (analysis runs), preserving behaviour
        boolean gate = configuration.driftDetectionEnabled()
            && ChaosProbability.shouldInject(configuration.driftSampleRate(), null);
        assertThat(gate, is(true));
    }

    // ---- (b) enabled=false skips analysis ----

    @Test
    public void shouldSkipAnalysisWhenDisabled_gateExpression() {
        // given
        Configuration configuration = Configuration.configuration().driftDetectionEnabled(false);

        // then - the gate short-circuits to false so analyseDrift is never called
        boolean gate = configuration.driftDetectionEnabled()
            && ChaosProbability.shouldInject(configuration.driftSampleRate(), null);
        assertThat(gate, is(false));
    }

    @Test
    public void shouldSkipAnalysisWhenSampleRateZero_gateExpression() {
        // given
        Configuration configuration = Configuration.configuration().driftSampleRate(0.0d);

        // then - a zero sample rate never draws in, so analysis is skipped
        boolean gate = configuration.driftDetectionEnabled()
            && ChaosProbability.shouldInject(configuration.driftSampleRate(), null);
        assertThat(gate, is(false));
    }

    // ---- (c) sampleRate clamping ----

    @Test
    public void shouldClampSampleRateBelowZero() {
        ConfigurationProperties.driftSampleRate(-0.5d);

        assertThat(ConfigurationProperties.driftSampleRate(), is(0.0d));
    }

    @Test
    public void shouldClampSampleRateAboveOne() {
        ConfigurationProperties.driftSampleRate(2.5d);

        assertThat(ConfigurationProperties.driftSampleRate(), is(1.0d));
    }

    @Test
    public void shouldAcceptSampleRateWithinRange() {
        ConfigurationProperties.driftSampleRate(0.25d);

        assertThat(ConfigurationProperties.driftSampleRate(), is(0.25d));
    }

    @Test
    public void shouldAcceptSampleRateBoundaries() {
        ConfigurationProperties.driftSampleRate(0.0d);
        assertThat(ConfigurationProperties.driftSampleRate(), is(0.0d));

        ConfigurationProperties.driftSampleRate(1.0d);
        assertThat(ConfigurationProperties.driftSampleRate(), is(1.0d));
    }

    // ---- per-instance override fallback ----

    @Test
    public void shouldFallBackToConfigurationPropertiesWhenUnset() {
        Configuration configuration = Configuration.configuration();

        assertThat(configuration.driftDetectionEnabled(), is(ConfigurationProperties.driftDetectionEnabled()));
        assertThat(configuration.driftSampleRate(), is(ConfigurationProperties.driftSampleRate()));
    }

    @Test
    public void shouldReturnPerInstanceOverride() {
        Configuration configuration = Configuration.configuration()
            .driftDetectionEnabled(false)
            .driftSampleRate(0.1d);

        assertThat(configuration.driftDetectionEnabled(), is(false));
        assertThat(configuration.driftSampleRate(), is(0.1d));
    }
}
