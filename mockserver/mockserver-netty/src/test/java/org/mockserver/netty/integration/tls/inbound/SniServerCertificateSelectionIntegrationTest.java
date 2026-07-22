package org.mockserver.netty.integration.tls.inbound;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Verifies SNI-driven per-host server-certificate selection at the level of a real TLS handshake:
 * connecting with a chosen {@link SNIHostName} causes MockServer to present a leaf certificate whose
 * Subject Alternative Names contain that host name, and a subsequent connection with a second, distinct
 * SNI host on the same running server causes the served certificate to reflect that host too
 * (dynamic per-host certificate regeneration).
 * <p>
 * This complements {@code SniHandlerTest} (which only asserts the hostname is added to the SAN
 * configuration Set via an EmbeddedChannel) by proving the SNI host actually propagates into the
 * certificate the server serves over the wire. The chosen SNI host names are deliberately NOT default
 * SANs (the default is only {@code localhost}) so the assertion proves SNI propagation rather than a
 * static default SAN.
 *
 * @author jamesdbloom
 */
public class SniServerCertificateSelectionIntegrationTest {

    private static final int GENERAL_NAME_DNS = 2;
    private static final String FIRST_SNI_HOST = "sni-selected-host-one.example";
    private static final String SECOND_SNI_HOST = "sni-selected-host-two.example";

    private static ClientAndServer mockServer;

    @BeforeClass
    public static void startServer() {
        // default configuration: dynamic certificate generation with preventCertificateDynamicUpdate=false,
        // so the served leaf certificate is regenerated per SNI host during the handshake
        mockServer = ClientAndServer.startClientAndServer();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void shouldPresentCertificateWhoseSANsContainTheChosenSniHostAndRegeneratePerHost() throws Exception {
        // when — a real TLS connection is opened presenting the first chosen SNI host
        Set<String> firstServedDnsSans = dnsSubjectAlternativeNamesOfServedCertificate(FIRST_SNI_HOST);

        // then — the served certificate's SANs contain that (non-default) host, proving SNI propagation
        assertThat("served certificate SANs should contain the first SNI host", firstServedDnsSans, hasItem(FIRST_SNI_HOST));

        // when — a second, distinct SNI host is presented on the same running server
        Set<String> secondServedDnsSans = dnsSubjectAlternativeNamesOfServedCertificate(SECOND_SNI_HOST);

        // then — the served certificate is regenerated per host and reflects the second host too
        assertThat("served certificate SANs should contain the second SNI host", secondServedDnsSans, hasItem(SECOND_SNI_HOST));
    }

    @Test
    public void shouldNotContainSniHostThatWasNeverPresented() throws Exception {
        // when — a connection presents only the first SNI host
        Set<String> servedDnsSans = dnsSubjectAlternativeNamesOfServedCertificate(FIRST_SNI_HOST);

        // then — the served certificate must not contain a host that was never presented via SNI
        assertThat("served certificate SANs must not contain a never-presented host", servedDnsSans, not(hasItem("never-presented-host.example")));
    }

    /**
     * Opens a real TLS connection to the running MockServer secure port presenting the given SNI host,
     * completes the handshake, and returns the dNSName Subject Alternative Names of the served peer
     * (leaf) certificate.
     */
    private Set<String> dnsSubjectAlternativeNamesOfServedCertificate(String sniHost) throws Exception {
        SSLSocketFactory socketFactory = trustAllSslContext().getSocketFactory();
        // connect by IP so JSSE adds no implicit SNI, then set the chosen SNI host explicitly
        try (SSLSocket socket = (SSLSocket) socketFactory.createSocket("127.0.0.1", mockServer.getPort())) {
            socket.setSoTimeout(30_000);
            SSLParameters sslParameters = socket.getSSLParameters();
            List<SNIServerName> serverNames = Collections.singletonList(new SNIHostName(sniHost));
            sslParameters.setServerNames(serverNames);
            socket.setSSLParameters(sslParameters);

            socket.startHandshake();

            SSLSession session = socket.getSession();
            Certificate[] peerCertificates = session.getPeerCertificates();
            X509Certificate leafCertificate = (X509Certificate) peerCertificates[0];
            return dnsNames(leafCertificate);
        }
    }

    private static Set<String> dnsNames(X509Certificate certificate) throws Exception {
        Set<String> dnsNames = new HashSet<>();
        Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
        if (subjectAlternativeNames != null) {
            for (List<?> subjectAlternativeName : subjectAlternativeNames) {
                Integer type = (Integer) subjectAlternativeName.get(0);
                if (type == GENERAL_NAME_DNS) {
                    dnsNames.add((String) subjectAlternativeName.get(1));
                }
            }
        }
        return dnsNames;
    }

    private static SSLContext trustAllSslContext() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return sslContext;
    }
}
