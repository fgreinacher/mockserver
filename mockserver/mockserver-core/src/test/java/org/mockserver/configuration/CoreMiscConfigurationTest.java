package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Covers the two additional core-misc configuration properties: {@code templateFakerSeed}
 * (template faker sample-data seed) and {@code forwardProxyClientCertificatesByHost}
 * (per-host outbound mTLS cert/key map).
 */
public class CoreMiscConfigurationTest {

    @Before
    @After
    public void resetProperties() {
        System.clearProperty("mockserver.templateFakerSeed");
        System.clearProperty("mockserver.forwardProxyClientCertificatesByHost");
        ConfigurationProperties.templateFakerSeed(0L);
        ConfigurationProperties.forwardProxyClientCertificatesByHost("");
    }

    // ----- templateFakerSeed -----

    @Test
    public void shouldDefaultTemplateFakerSeedToZero() {
        assertThat(ConfigurationProperties.templateFakerSeed(), is(0L));
    }

    @Test
    public void shouldSetAndGetTemplateFakerSeed() {
        ConfigurationProperties.templateFakerSeed(4242L);
        assertThat(ConfigurationProperties.templateFakerSeed(), is(4242L));
    }

    @Test
    public void shouldDefaultTemplateFakerSeedFromConfiguration() {
        assertThat(Configuration.configuration().templateFakerSeed(), is(0L));
    }

    @Test
    public void shouldSetAndGetTemplateFakerSeedFromConfiguration() {
        assertThat(Configuration.configuration().templateFakerSeed(99L).templateFakerSeed(), is(99L));
    }

    // ----- forwardProxyClientCertificatesByHost -----

    @Test
    public void shouldDefaultForwardProxyClientCertificatesByHostToEmpty() {
        assertThat(ConfigurationProperties.forwardProxyClientCertificatesByHost(), is(""));
    }

    @Test
    public void shouldSetAndGetForwardProxyClientCertificatesByHost() {
        ConfigurationProperties.forwardProxyClientCertificatesByHost("api.internal=/certs/c.pem;/certs/k.pem");
        assertThat(ConfigurationProperties.forwardProxyClientCertificatesByHost(), is("api.internal=/certs/c.pem;/certs/k.pem"));
    }

    @Test
    public void shouldTreatNullForwardProxyClientCertificatesByHostAsEmpty() {
        ConfigurationProperties.forwardProxyClientCertificatesByHost(null);
        assertThat(ConfigurationProperties.forwardProxyClientCertificatesByHost(), is(""));
    }

    @Test
    public void shouldSetAndGetForwardProxyClientCertificatesByHostFromConfiguration() {
        Configuration configuration = Configuration.configuration()
            .forwardProxyClientCertificatesByHost("a=/c.pem;/k.pem");
        assertThat(configuration.forwardProxyClientCertificatesByHost(), is("a=/c.pem;/k.pem"));
    }
}
