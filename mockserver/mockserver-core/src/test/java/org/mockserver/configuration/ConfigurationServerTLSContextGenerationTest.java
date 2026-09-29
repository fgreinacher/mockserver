package org.mockserver.configuration;

import org.junit.Test;

import java.util.Set;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * {@code NettySslContextFactory} re-validates its cached server context only when
 * {@link Configuration#serverTLSContextGeneration()} moves, so every input of that context must move it.
 * One case per input read by {@code NettySslContextFactory.serverContextSignature}.
 */
public class ConfigurationServerTLSContextGenerationTest {

    @Test
    public void shouldAdvanceForEveryServerTlsContextInput() {
        assertAdvances("tlsMutualAuthenticationRequired", configuration -> configuration.tlsMutualAuthenticationRequired(!configuration.tlsMutualAuthenticationRequired()));
        assertAdvances("tlsMutualAuthenticationCertificateChain", configuration -> configuration.tlsMutualAuthenticationCertificateChain("org/mockserver/authentication/mtls/ca.pem"));
        assertAdvances("tlsProtocols", configuration -> configuration.tlsProtocols("TLSv1.3"));
        assertAdvances("tlsAllowInsecureProtocols", configuration -> configuration.tlsAllowInsecureProtocols(!configuration.tlsAllowInsecureProtocols()));
        assertAdvances("http2Enabled", configuration -> configuration.http2Enabled(!configuration.http2Enabled()));
        assertAdvances("certificateAuthorityCertificate", configuration -> configuration.certificateAuthorityCertificate("changed-ca.pem"));
        assertAdvances("certificateAuthorityPrivateKey", configuration -> configuration.certificateAuthorityPrivateKey("changed-ca-key.pem"));
        assertAdvances("dynamicallyCreateCertificateAuthorityCertificate", configuration -> configuration.dynamicallyCreateCertificateAuthorityCertificate(!configuration.dynamicallyCreateCertificateAuthorityCertificate()));
        assertAdvances("proxySetup", configuration -> configuration.proxySetup(true));
        assertAdvances("directoryToSaveDynamicSSLCertificate", configuration -> configuration.directoryToSaveDynamicSSLCertificate("changed-directory"));
        assertAdvances("privateKeyPath", configuration -> configuration.privateKeyPath("changed-key.pem"));
        assertAdvances("x509CertificatePath", configuration -> configuration.x509CertificatePath("changed-cert.pem"));
        assertAdvances("preventCertificateDynamicUpdate", configuration -> configuration.preventCertificateDynamicUpdate(!configuration.preventCertificateDynamicUpdate()));
        assertAdvances("sslSubjectAlternativeNameDomains", configuration -> configuration.sslSubjectAlternativeNameDomains(Set.of("replaced.generation.test")));
        assertAdvances("sslSubjectAlternativeNameIps", configuration -> configuration.sslSubjectAlternativeNameIps(Set.of("10.1.2.3")));
        assertAdvances("addSubjectAlternativeName (domain)", configuration -> configuration.addSubjectAlternativeName("added.generation.test:8443"));
        assertAdvances("addSubjectAlternativeName (ip)", configuration -> configuration.addSubjectAlternativeName("10.9.8.7"));
        assertAdvances("clearSslSubjectAlternativeNameDomains", Configuration::clearSslSubjectAlternativeNameDomains);
        assertAdvances("clearSslSubjectAlternativeNameIps", Configuration::clearSslSubjectAlternativeNameIps);
    }

    @Test
    public void shouldNotAdvanceWhenAKnownSubjectAlternativeNameIsAddedAgain() {
        Configuration configuration = configuration();
        configuration.addSubjectAlternativeName("known.generation.test");
        long before = configuration.serverTLSContextGeneration();

        configuration.addSubjectAlternativeName("known.generation.test");
        configuration.addSubjectAlternativeName("KNOWN.generation.test:443");

        assertThat(configuration.serverTLSContextGeneration(), is(before));
    }

    @Test
    public void shouldAdvanceWhenAnAddEvictsAnotherSubjectAlternativeName() {
        Configuration configuration = configuration().maxSubjectAlternativeNames(1);
        configuration.sslSubjectAlternativeNameDomains(new java.util.HashSet<>());
        configuration.addSubjectAlternativeName("first.generation.test");
        long before = configuration.serverTLSContextGeneration();

        configuration.addSubjectAlternativeName("second.generation.test");

        assertThat(configuration.serverTLSContextGeneration(), greaterThan(before));
    }

    private static void assertAdvances(String input, Consumer<Configuration> change) {
        Configuration configuration = configuration();
        long before = configuration.serverTLSContextGeneration();
        change.accept(configuration);
        assertThat(input + " must advance the server TLS context generation", configuration.serverTLSContextGeneration(), greaterThan(before));
    }
}
