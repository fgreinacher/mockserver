package org.mockserver.netty;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.socket.tls.PEMToFile;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Shared test infrastructure for tests that drive MockServer's TLS endpoints with an independent client
 * (e.g. {@link java.net.http.HttpClient}) and must trust MockServer's certificates properly rather than
 * with a blanket trust-all.
 */
public final class MockServerCaTrustTestSupport {

    private MockServerCaTrustTestSupport() {
        // utility class
    }

    /**
     * An {@link SSLContext} whose only trust anchor is MockServer's bundled Certificate Authority.
     * MockServer signs its generated leaf certificates with this CA only while {@code proxySetup} and
     * {@code dynamicallyCreateCertificateAuthorityCertificate} are both off, so a test using this must
     * keep them off. The default leaf SANs cover {@code localhost} and {@code 127.0.0.1}, so hostname
     * verification can stay ON.
     */
    public static SSLContext caTrustingSslContext() throws Exception {
        String caPem;
        try (InputStream in = MockServerCaTrustTestSupport.class.getClassLoader()
            .getResourceAsStream(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)) {
            if (in == null) {
                throw new IllegalStateException("could not load MockServer CA certificate from classpath: "
                    + ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE);
            }
            caPem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        X509Certificate caCertificate = PEMToFile.x509FromPEM(caPem);

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("mockserver-ca", caCertificate);

        TrustManagerFactory trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return sslContext;
    }
}
