package org.mockserver.configuration;

import org.junit.After;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Resolution of the {@code mockserver.sslCertificateLeafValidityInDays} property: default (397), static
 * override, non-positive fallback, and the per-instance {@link Configuration} form.
 * <p>
 * Mutates the process-wide {@link ConfigurationProperties} static store, so it is registered in the
 * sequential (parallel-excluded) phase of {@code mockserver-core/pom.xml}.
 */
public class SslCertificateLeafValidityInDaysTest {

    @After
    public void tearDown() {
        System.clearProperty("mockserver.sslCertificateLeafValidityInDays");
    }

    @Test
    public void shouldDefaultTo397Days() {
        System.clearProperty("mockserver.sslCertificateLeafValidityInDays");

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
    public void shouldResolveSystemPropertyOverride() {
        System.setProperty("mockserver.sslCertificateLeafValidityInDays", "825");

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
}
