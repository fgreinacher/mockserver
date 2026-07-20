package org.mockserver.netty.integration.authenticatedcontrolplane;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.mockserver.cli.Main;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.model.RetrieveType;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.RequestDefinitionSerializer;
import org.mockserver.serialization.VerificationSequenceSerializer;
import org.mockserver.serialization.VerificationSerializer;
import org.mockserver.socket.PortFactory;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.testing.integration.mock.AbstractMockingIntegrationTestBase;

import javax.net.ssl.SSLException;
import java.security.cert.X509Certificate;
import java.security.PrivateKey;
import java.util.Collections;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.matchers.Times.once;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.socket.tls.PEMToFile.privateKeyFromPEMFile;
import static org.mockserver.socket.tls.PEMToFile.x509ChainFromPEMFile;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationSequence.verificationSequence;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * @author jamesdbloom
 */
public class AuthenticatedControlPlaneUsingMTLSClientNotAuthenticatedIntegrationTest extends AbstractMockingIntegrationTestBase {

    /**
     * The CA the SERVER pins as its control-plane trust anchor.
     */
    private static final String SERVER_CA_CHAIN = "org/mockserver/netty/integration/tls/ca.pem";
    /**
     * A leaf signed by {@link #SERVER_CA_CHAIN}. Pinned on the server's own Configuration so the
     * {@link ClientAndServer} returned at startup holds credentials the server DOES trust — that client is
     * how the server is stopped in {@link #stopServer()}. Without these the client would fall through to
     * whatever the process-global store happens to hold, and MockServerClient rejects a blank client
     * key/certificate when mTLS is required.
     */
    private static final String SERVER_PRIVATE_KEY = "org/mockserver/netty/integration/tls/leaf-key-pkcs8.pem";
    private static final String SERVER_X509_CERTIFICATE = "org/mockserver/netty/integration/tls/leaf-cert.pem";
    /**
     * A DIFFERENT CA, and a leaf signed by it. The client presents this leaf, so it chains to an
     * authority the server does not trust — which is the whole point of this test.
     */
    private static final String CLIENT_CA_CHAIN = "org/mockserver/netty/integration/tls/separateca/ca.pem";
    private static final String CLIENT_PRIVATE_KEY = "org/mockserver/netty/integration/tls/separateca/leaf-key-pkcs8.pem";
    private static final String CLIENT_X509_CERTIFICATE = "org/mockserver/netty/integration/tls/separateca/leaf-cert.pem";

    /**
     * Retained so the embedded server can be shut down directly. {@link #mockServerClient} is deliberately
     * replaced below with a client the server does not trust, and it is not the handle that owns the
     * embedded server's lifecycle.
     */
    private static ClientAndServer clientAndServer;
    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private final RequestDefinitionSerializer requestDefinitionSerializer = new RequestDefinitionSerializer(mockServerLogger);
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(mockServerLogger);
    private final VerificationSerializer verificationSerializer = new VerificationSerializer(mockServerLogger);
    private final VerificationSequenceSerializer verificationSequenceSerializer = new VerificationSequenceSerializer(mockServerLogger);

    @BeforeClass
    public static void startServer() {
        // The server pins its trust anchor on its OWN Configuration instance. This must NOT go through
        // ConfigurationProperties: the control-plane authentication handler is derived from the LIVE
        // configuration on every request, and Configuration reads through to the process-global static
        // store for any field left unset. Setting the CA chain globally and then rewriting it to build a
        // differently-signed client would therefore re-point the RUNNING server's trust anchor at the very
        // CA that signed the "unauthorised" client, and the client would authenticate successfully.
        Configuration serverConfiguration = configuration()
            .controlPlaneTLSMutualAuthenticationCAChain(SERVER_CA_CHAIN)
            .controlPlanePrivateKeyPath(SERVER_PRIVATE_KEY)
            .controlPlaneX509CertificatePath(SERVER_X509_CERTIFICATE)
            .controlPlaneTLSMutualAuthenticationRequired(true)
            // pin the OTHER two mechanisms off rather than leaving them to read through to the global store:
            // any test in this JVM that leaves JWT or OIDC authentication enabled (a failure before its
            // @AfterClass completes would do it) would otherwise silently chain an extra handler here
            .controlPlaneJWTAuthenticationRequired(false)
            .controlPlaneOidcAuthenticationRequired(false);

        clientAndServer = ClientAndServer.startClientAndServer(serverConfiguration);
        clientAndServer.hasStarted();

        // The client is configured entirely on its own Configuration instance, presenting a leaf signed by
        // a CA the server does not trust. No global state is touched, so the server's trust anchor stays
        // pinned to SERVER_CA_CHAIN for the lifetime of this test.
        Configuration clientConfiguration = configuration()
            .controlPlaneTLSMutualAuthenticationCAChain(CLIENT_CA_CHAIN)
            .controlPlanePrivateKeyPath(CLIENT_PRIVATE_KEY)
            .controlPlaneX509CertificatePath(CLIENT_X509_CERTIFICATE)
            .controlPlaneTLSMutualAuthenticationRequired(true);

        mockServerClient = new MockServerClient(clientConfiguration, "localhost", clientAndServer.getPort()).withSecure(true);
        MockServerLogger mockServerLogger = new MockServerLogger();
        NettySslContextFactory nettySslContextFactory = new NettySslContextFactory(clientConfiguration, MOCK_SERVER_LOGGER, false);
        nettySslContextFactory.withClientSslContextBuilderFunction(
            sslContextBuilder -> {
                try {
                    PrivateKey key = privateKeyFromPEMFile(CLIENT_PRIVATE_KEY);
                    X509Certificate[] keyCertChain = x509ChainFromPEMFile(CLIENT_X509_CERTIFICATE).toArray(new X509Certificate[0]);
                    X509Certificate[] trustCertCollection = nettySslContextFactory.trustCertificateChain(CLIENT_CA_CHAIN);
                    sslContextBuilder
                        .keyManager(
                            key,
                            keyCertChain
                        )
                        .trustManager(trustCertCollection);
                    return sslContextBuilder.build();
                } catch (SSLException e) {
                    throw new RuntimeException(e);
                }
            }
        );
        httpClient = new NettyHttpClient(clientConfiguration, mockServerLogger, clientEventLoopGroup, null, false, nettySslContextFactory);
    }

    @AfterClass
    public static void stopServer() {
        // shut down through the ClientAndServer, which owns the embedded server's lifecycle and stops it
        // in-process (shutdown never traverses the control plane, so it cannot be refused by authentication)
        stopQuietly(clientAndServer);
        // stopQuietly swallows any failure, so assert the embedded server actually shut down rather than
        // silently leaking a running server into the rest of the JVM
        assertThat("control plane mTLS server did not shut down", clientAndServer.hasStopped(), equalTo(true));
    }

    @Before
    @Override
    public void resetServer() {
        // do nothing as control plane authentication fails
    }

    @Override
    public int getServerPort() {
        return mockServerClient.getPort();
    }

    @Override
    protected boolean isSecureControlPlane() {
        return true;
    }

    @Test
    public void shouldAuthenticateExpectationCreationViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .when(request())
                .respond(response().withBody("some_body"))
        );
    }

    @Test
    public void shouldAuthenticateExpectationCreationViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(addContextToPath("mockserver/expectation"))
                .withBody(expectationSerializer.serialize(
                    new Expectation(request("/some_path"), once(), TimeToLive.unlimited(), 0)
                        .thenRespond(response().withBody("some_body"))
                ))
        );
    }

    @Test
    public void shouldAuthenticateResetViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .reset()
        );
    }

    @Test
    public void shouldAuthenticateResetViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(addContextToPath("mockserver/reset"))
        );
    }

    @Test
    public void shouldAuthenticateClearViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .clear((RequestDefinition) null)
        );
    }

    @Test
    public void shouldAuthenticateClearViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(addContextToPath("mockserver/clear"))
        );
    }

    @Test
    public void shouldAuthenticateClearWithRequestViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .clear(request())
        );
    }

    @Test
    public void shouldAuthenticateClearWithRequestViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(addContextToPath("mockserver/clear"))
                .withBody(requestDefinitionSerializer.serialize(request()))
        );
    }

    @Test
    public void shouldAuthenticateVerifyZeroInteractionsViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .verifyZeroInteractions()
        );
    }

    @Test
    public void shouldAuthenticateVerifyZeroInteractionsWithRequestViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(addContextToPath("mockserver/clear"))
                .withBody(verificationSerializer.serialize(
                    verification()
                        .withRequest(request())
                        .withTimes(exactly(0))
                ))
        );
    }

    @Test
    public void shouldAuthenticateVerifyViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .verify(request())
        );
    }

    @Test
    public void shouldAuthenticateVerifyWithRequestViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(addContextToPath("mockserver/clear"))
                .withBody(verificationSerializer.serialize(
                    verification()
                        .withRequest(request())
                ))
        );
    }

    @Test
    public void shouldAuthenticateVerifySequenceViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .verify(request(), request())
        );
    }

    @Test
    public void shouldAuthenticateVerifySequenceWithRequestViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(addContextToPath("mockserver/clear"))
                .withBody(verificationSequenceSerializer.serialize(
                    verificationSequence()
                        .withRequests(request(), request())
                ))
        );
    }

    @Test
    public void shouldAuthenticateRetrieveRecordedRequestsViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .retrieveRecordedRequests(request())
        );
    }

    @Test
    public void shouldAuthenticateRetrieveRecordedRequestsWithRequestViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(calculatePath("retrieve"))
                .withQueryStringParameter("type", RetrieveType.REQUESTS.name())
        );
    }

    @Test
    public void shouldAuthenticateRetrieveRecordedRequestsAndResponsesViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .retrieveRecordedRequestsAndResponses(request())
        );
    }

    @Test
    public void shouldAuthenticateRetrieveRecordedRequestsAndResponsesWithRequestViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(calculatePath("retrieve"))
                .withQueryStringParameter("type", RetrieveType.REQUEST_RESPONSES.name())
        );
    }

    @Test
    public void shouldAuthenticateRetrieveRecordedExpectationsViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .retrieveRecordedExpectations(request())
        );
    }

    @Test
    public void shouldAuthenticateRetrieveRecordedExpectationsWithRequestViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(calculatePath("retrieve"))
                .withQueryStringParameter("type", RetrieveType.RECORDED_EXPECTATIONS.name())
        );
    }

    @Test
    public void shouldAuthenticateRetrieveActiveExpectationsViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .retrieveActiveExpectations(request())
        );
    }

    @Test
    public void shouldAuthenticateRetrieveActiveExpectationsWithRequestViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(calculatePath("retrieve"))
                .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
        );
    }

    @Test
    public void shouldAuthenticateRetrieveLogMessagesViaJavaClient() {
        clientOperationIsAuthenticated(() ->
            mockServerClient
                .retrieveLogMessages(request())
        );
    }

    @Test
    public void shouldAuthenticateRetrieveLogMessagesWithRequestViaHttp() {
        httpAPIOperationIsAuthenticated(
            request()
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(calculatePath("retrieve"))
                .withQueryStringParameter("type", RetrieveType.LOGS.name())
        );
    }

    private void clientOperationIsAuthenticated(ThrowingRunnable throwingRunnable) {
        // when
        AuthenticationException authenticationException = assertThrows(AuthenticationException.class, throwingRunnable);

        // then
        assertThat(authenticationException.getMessage(), equalTo("Unauthorized for control plane - control plane request failed authentication no client certificates can be validated by control plane CA"));
    }

    private void httpAPIOperationIsAuthenticated(HttpRequest httpRequest) {
        // when
        HttpResponse httpResponse = makeRequest(httpRequest, Collections.emptyList());

        // then
        assertThat(httpResponse.getStatusCode(), equalTo(401));
        assertThat(httpResponse.getBodyAsString(), equalTo("Unauthorized for control plane - control plane request failed authentication no client certificates can be validated by control plane CA"));
    }

}
