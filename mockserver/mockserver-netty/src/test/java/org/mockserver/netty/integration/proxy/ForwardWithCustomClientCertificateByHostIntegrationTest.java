package org.mockserver.netty.integration.proxy;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpStatusCode;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.integration.mock.AbstractMockingIntegrationTestBase;

import javax.net.ssl.SSLException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.ConfigurationProperties.forwardProxyClientCertificatesByHost;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.socket.tls.PEMToFile.privateKeyFromPEMFile;
import static org.mockserver.socket.tls.PEMToFile.x509FromPEMFile;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Proves that the {@code forwardProxyClientCertificatesByHost} per-host outbound client-certificate
 * selection is actually presented AT THE TLS HANDSHAKE (not merely configured or cached distinctly).
 * <p>
 * Two secure upstream {@link EchoServer}s are started, each with {@link ClientAuth#REQUIRE} and a trust
 * anchor for exactly ONE of two independent client CAs:
 * <ul>
 *   <li>{@code echoServerCaA} trusts only client cert A ({@code .../tls/} CA)</li>
 *   <li>{@code echoServerCaB} trusts only client cert B ({@code .../tls/separateca/} CA)</li>
 * </ul>
 * MockServer is configured to present cert A for host key {@code localhost} and cert B for host key
 * {@code 127.0.0.1}. Because the host string is the mapping key while the connection target is fixed
 * independently by {@code withPort(...)}, the same upstream server can be reached under either host key.
 * This decouples "which cert is presented" from "which server is contacted", so the handshake outcome
 * is a direct, load-bearing assertion of the per-host selection:
 * <ul>
 *   <li>host {@code localhost} (=&gt; cert A) to the cert-A-trusting server =&gt; accepted (200)</li>
 *   <li>host {@code 127.0.0.1} (=&gt; cert B) to the cert-B-trusting server =&gt; accepted (200)</li>
 *   <li>host {@code localhost} (=&gt; cert A) to the cert-B-trusting server =&gt; REJECTED (502) — same
 *       target as the accepted case, opposite outcome, so the presented cert must be chosen by host</li>
 *   <li>host {@code 127.0.0.1} (=&gt; cert B) to the cert-A-trusting server =&gt; REJECTED (502)</li>
 * </ul>
 * If the per-host selection were degraded to always present a single cert, one of the accepted cases
 * above would flip to 502 (verified as the positive control while authoring this test).
 *
 * @author jamesdbloom
 */
public class ForwardWithCustomClientCertificateByHostIntegrationTest extends AbstractMockingIntegrationTestBase {

    // host keys that both resolve to the loopback interface but are DISTINCT mapping keys
    private static final String HOST_KEY_CERT_A = "localhost";
    private static final String HOST_KEY_CERT_B = "127.0.0.1";

    // client cert A (chain + key) and its CA
    private static final String CERT_A_CHAIN = "org/mockserver/netty/integration/tls/leaf-cert-chain.pem";
    private static final String CERT_A_KEY = "org/mockserver/netty/integration/tls/leaf-key-pkcs8.pem";
    private static final String CERT_A_LEAF = "org/mockserver/netty/integration/tls/leaf-cert.pem";
    private static final String CERT_A_CA = "org/mockserver/netty/integration/tls/ca.pem";

    // client cert B (chain + key) and its INDEPENDENT CA
    private static final String CERT_B_CHAIN = "org/mockserver/netty/integration/tls/separateca/leaf-cert-chain.pem";
    private static final String CERT_B_KEY = "org/mockserver/netty/integration/tls/separateca/leaf-key-pkcs8.pem";
    private static final String CERT_B_LEAF = "org/mockserver/netty/integration/tls/separateca/leaf-cert.pem";
    private static final String CERT_B_CA = "org/mockserver/netty/integration/tls/separateca/ca.pem";

    private static MockServer mockServer;
    private static EchoServer echoServerTrustingCertA;
    private static EchoServer echoServerTrustingCertB;
    private static String originalForwardProxyClientCertificatesByHost;

    @BeforeClass
    public static void startServer() throws SSLException {
        // upstream that requires client auth and trusts ONLY client cert A
        echoServerTrustingCertA = new EchoServer(SslContextBuilder
            .forServer(
                privateKeyFromPEMFile(CERT_A_KEY),
                x509FromPEMFile(CERT_A_LEAF),
                x509FromPEMFile(CERT_A_CA)
            )
            .trustManager(
                x509FromPEMFile(CERT_A_LEAF),
                x509FromPEMFile(CERT_A_CA)
            )
            .clientAuth(ClientAuth.REQUIRE)
            .build());

        // upstream that requires client auth and trusts ONLY client cert B
        echoServerTrustingCertB = new EchoServer(SslContextBuilder
            .forServer(
                privateKeyFromPEMFile(CERT_B_KEY),
                x509FromPEMFile(CERT_B_LEAF),
                x509FromPEMFile(CERT_B_CA)
            )
            .trustManager(
                x509FromPEMFile(CERT_B_LEAF),
                x509FromPEMFile(CERT_B_CA)
            )
            .clientAuth(ClientAuth.REQUIRE)
            .build());

        // present cert A for host "localhost" and cert B for host "127.0.0.1"
        originalForwardProxyClientCertificatesByHost = forwardProxyClientCertificatesByHost();
        forwardProxyClientCertificatesByHost(
            HOST_KEY_CERT_A + "=" + CERT_A_CHAIN + ";" + CERT_A_KEY + "," +
                HOST_KEY_CERT_B + "=" + CERT_B_CHAIN + ";" + CERT_B_KEY
        );

        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort(), servletContext);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServer);
        stopQuietly(mockServerClient);
        stopQuietly(echoServerTrustingCertA);
        stopQuietly(echoServerTrustingCertB);
        forwardProxyClientCertificatesByHost(originalForwardProxyClientCertificatesByHost);
    }

    @Override
    public int getServerPort() {
        return mockServer.getLocalPort();
    }

    @Test
    public void shouldPresentPerHostClientCertificateAtHandshake() {
        // given - four forward expectations pairing a host key (=> cert) with a target upstream port
        forwardExpectation("certA_to_serverA", HOST_KEY_CERT_A, echoServerTrustingCertA.getPort());
        forwardExpectation("certB_to_serverB", HOST_KEY_CERT_B, echoServerTrustingCertB.getPort());
        forwardExpectation("certA_to_serverB", HOST_KEY_CERT_A, echoServerTrustingCertB.getPort());
        forwardExpectation("certB_to_serverA", HOST_KEY_CERT_B, echoServerTrustingCertA.getPort());

        // then - host "localhost" presents cert A; server A trusts cert A => handshake succeeds
        assertAccepted("certA_to_serverA");

        // then - host "127.0.0.1" presents cert B; server B trusts cert B => handshake succeeds
        assertAccepted("certB_to_serverB");

        // then - SAME target as the accepted "certB_to_serverB" case but reached under host "localhost",
        // so cert A is presented; server B trusts only cert B => handshake REJECTED. Proves the presented
        // client certificate is selected by host (not by target, and not a single fixed cert).
        assertRejected("certA_to_serverB");

        // then - symmetric cross-check: host "127.0.0.1" presents cert B to server A (trusts only cert A)
        // => handshake REJECTED.
        assertRejected("certB_to_serverA");
    }

    private void forwardExpectation(String path, String host, int port) {
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath(path))
            )
            .forward(
                forward()
                    .withHost(host)
                    .withPort(port)
                    .withScheme(HttpForward.Scheme.HTTPS)
            );
    }

    private void assertAccepted(String path) {
        assertThat(makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath(path))
                    .withMethod("POST")
                    .withHeaders(
                        header("x-test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_http"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header("x-test", "test_headers_and_body")
                )
                .withBody("an_example_body_http")));
    }

    private void assertRejected(String path) {
        // wrong client certificate => upstream aborts the TLS handshake => forward fails => 502
        assertThat(makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath(path))
                    .withMethod("POST")
                    .withHeaders(
                        header("x-test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_http"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(HttpStatusCode.BAD_GATEWAY_502.code())
                .withReasonPhrase(HttpStatusCode.BAD_GATEWAY_502.reasonPhrase())));
    }

}
