package org.mockserver.netty.integration.authenticatedcontrolplane;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.authentication.jwt.JWKGenerator;
import org.mockserver.authentication.jwt.JWTGenerator;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.keys.AsymmetricKeyGenerator;
import org.mockserver.keys.AsymmetricKeyPair;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
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
import org.mockserver.test.TempFileWriter;
import org.mockserver.testing.integration.mock.AbstractMockingIntegrationTestBase;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.matchers.Times.once;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationSequence.verificationSequence;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * Negative integration test for control-plane authentication via an external OIDC IdP: every control-plane
 * route must be REFUSED when the presented bearer token does not satisfy the configured issuer, audience,
 * signing key, validity window or required scopes.
 *
 * <p>This is the enforcement guard for the OIDC route. The positive path proves only that a good token is
 * accepted, which a handler that authenticates everything — or a {@code null} handler, which maps to
 * "authenticated" — would also satisfy. Both failure modes are caught here and nowhere else.
 *
 * <p>Unlike the older tests in this package, the server's OIDC configuration is pinned on its OWN
 * {@link Configuration} instance rather than the process-global {@code ConfigurationProperties} store.
 * The control-plane authentication handler is derived from the LIVE configuration on every request, and a
 * {@link Configuration} reads through to the global store for any field left unset, so configuring the
 * server globally would leave its trust settings mutable by any other test sharing this JVM.
 *
 * @author jamesdbloom
 */
public class AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest extends AbstractMockingIntegrationTestBase {

    private static final String ISSUER = "https://oidc.mock-server.com";
    private static final String AUDIENCE = "https://mock-server.com";
    private static final String REQUIRED_SCOPE = "control-plane";

    /**
     * The IdP signing key the server trusts, published as the JWK set.
     */
    private static AsymmetricKeyPair trustedKeyPair;
    /**
     * A key the server has never heard of, used to mint correctly-shaped but wrongly-signed tokens.
     */
    private static AsymmetricKeyPair untrustedKeyPair;

    /**
     * Retained so the embedded server can be shut down directly; it owns the server's lifecycle.
     */
    private static ClientAndServer clientAndServer;
    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private final RequestDefinitionSerializer requestDefinitionSerializer = new RequestDefinitionSerializer(mockServerLogger);
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(mockServerLogger);
    private final VerificationSerializer verificationSerializer = new VerificationSerializer(mockServerLogger);
    private final VerificationSequenceSerializer verificationSequenceSerializer = new VerificationSequenceSerializer(mockServerLogger);

    @BeforeClass
    public static void startServer() {
        trustedKeyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        untrustedKeyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwksFile = TempFileWriter.write(new JWKGenerator().generateJWK(trustedKeyPair));

        Configuration serverConfiguration = configuration()
            .controlPlaneOidcJwksUri(jwksFile)
            .controlPlaneOidcIssuer(ISSUER)
            .controlPlaneOidcAudience(AUDIENCE)
            .controlPlaneOidcRequiredScopes(ImmutableSet.of(REQUIRED_SCOPE))
            .controlPlaneOidcAuthenticationRequired(true)
            // pin the OTHER two mechanisms off rather than leaving them to read through to the global store:
            // any test in this JVM that leaves mTLS or JWT authentication enabled (a failure before its
            // @AfterClass completes would do it) would otherwise silently chain an extra handler here and
            // the positive control below would fail
            .controlPlaneTLSMutualAuthenticationRequired(false)
            .controlPlaneJWTAuthenticationRequired(false);

        clientAndServer = ClientAndServer.startClientAndServer(serverConfiguration);
        // seed a VALID token before the readiness probe: hasStarted() is itself a control-plane request, and
        // every control-plane route is gated once OIDC authentication is required
        clientAndServer.withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::validToken);
        clientAndServer.hasStarted();
        mockServerClient = clientAndServer;
    }

    @AfterClass
    public static void stopServer() {
        // the ClientAndServer owns the embedded server's lifecycle and stops it in-process, so shutdown never
        // traverses the control plane and cannot be refused by authentication
        stopQuietly(clientAndServer);
        // stopQuietly swallows any failure, so assert the embedded server actually shut down rather than
        // silently leaking a running server into the rest of the JVM
        assertThat("control plane OIDC server did not shut down", clientAndServer.hasStopped(), equalTo(true));
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

    private static Map<String, Serializable> claims() {
        return ImmutableMap.of(
            "sub", "control-plane-operator",
            "iss", ISSUER,
            "aud", AUDIENCE,
            "scope", REQUIRED_SCOPE + " openid",
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "nbf", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()
        );
    }

    private static Map<String, Serializable> claimsWith(String claim, Serializable value) {
        Map<String, Serializable> claims = new java.util.LinkedHashMap<>(claims());
        claims.put(claim, value);
        return claims;
    }

    /**
     * A token that satisfies every configured constraint — signed by the trusted key with the expected
     * issuer, audience, scope and validity window.
     */
    private static String validToken() {
        return new JWTGenerator(trustedKeyPair).signJWT(claims());
    }

    /**
     * The default rejected token: correctly shaped, but signed by a key outside the server's JWK set.
     */
    private static String wrongKeyToken() {
        return new JWTGenerator(untrustedKeyPair).signJWT(claims());
    }

    // ---------------------------------------------------------------------------------------------
    // positive control - proves the OIDC configuration above is genuinely wired to the enforcement
    // point, so the negative assertions below cannot pass vacuously against a deny-everything handler
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldAuthoriseValidTokenForExpectationCreationViaJavaClient() {
        // when
        mockServerClient
            .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::validToken)
            .when(request())
            .respond(response().withBody("some_body"));

        // then no exception thrown
    }

    // ---------------------------------------------------------------------------------------------
    // token-shape rejections
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldRejectTokenSignedByUntrustedKey() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::wrongKeyToken)
                .when(request())
                .respond(response().withBody("some_body"))
        );
    }

    @Test
    public void shouldRejectTokenWithWrongIssuer() {
        String token = new JWTGenerator(trustedKeyPair).signJWT(claimsWith("iss", "https://attacker.example.com"));
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(() -> token)
                .when(request())
                .respond(response().withBody("some_body"))
        );
    }

    @Test
    public void shouldRejectTokenWithWrongAudience() {
        String token = new JWTGenerator(trustedKeyPair).signJWT(claimsWith("aud", "https://not-mock-server.com"));
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(() -> token)
                .when(request())
                .respond(response().withBody("some_body"))
        );
    }

    @Test
    public void shouldRejectTokenMissingRequiredScope() {
        String token = new JWTGenerator(trustedKeyPair).signJWT(claimsWith("scope", "openid profile"));
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(() -> token)
                .when(request())
                .respond(response().withBody("some_body"))
        );
    }

    @Test
    public void shouldRejectExpiredToken() {
        String token = new JWTGenerator(trustedKeyPair).signJWT(
            claimsWith("exp", Clock.systemUTC().instant().minus(Duration.ofHours(1)).getEpochSecond())
        );
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(() -> token)
                .when(request())
                .respond(response().withBody("some_body"))
        );
    }

    @Test
    public void shouldRejectTokenWithNoExpiry() {
        Map<String, Serializable> claims = new java.util.LinkedHashMap<>(claims());
        claims.remove("exp");
        String token = new JWTGenerator(trustedKeyPair).signJWT(claims);
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(() -> token)
                .when(request())
                .respond(response().withBody("some_body"))
        );
    }

    @Test
    public void shouldRejectGarbageToken() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(() -> "not-a-jwt")
                .when(request())
                .respond(response().withBody("some_body"))
        );
    }

    @Test
    public void shouldRejectRequestWithNoAuthorizationHeader() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(calculatePath("expectation"))
                .withBody(expectationSerializer.serialize(
                    new Expectation(request("/some_path"), once(), TimeToLive.unlimited(), 0)
                        .thenRespond(response().withBody("some_body"))
                ))
        );
    }

    @Test
    public void shouldRejectRequestWithNonBearerAuthorizationScheme() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withHeader(header(AUTHORIZATION.toString(), "Basic " + validToken()))
                .withPath(calculatePath("expectation"))
                .withBody(expectationSerializer.serialize(
                    new Expectation(request("/some_path"), once(), TimeToLive.unlimited(), 0)
                        .thenRespond(response().withBody("some_body"))
                ))
        );
    }

    // ---------------------------------------------------------------------------------------------
    // every control-plane route must be gated, not just expectation creation
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldRejectExpectationCreationViaHttp() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withHeader(header(AUTHORIZATION.toString(), "Bearer " + wrongKeyToken()))
                .withPath(calculatePath("expectation"))
                .withBody(expectationSerializer.serialize(
                    new Expectation(request("/some_path"), once(), TimeToLive.unlimited(), 0)
                        .thenRespond(response().withBody("some_body"))
                ))
        );
    }

    @Test
    public void shouldRejectResetViaJavaClient() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::wrongKeyToken)
                .reset()
        );
    }

    @Test
    public void shouldRejectResetViaHttp() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withHeader(header(AUTHORIZATION.toString(), "Bearer " + wrongKeyToken()))
                .withPath(calculatePath("reset"))
        );
    }

    @Test
    public void shouldRejectClearViaJavaClient() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::wrongKeyToken)
                .clear((RequestDefinition) null)
        );
    }

    @Test
    public void shouldRejectClearWithRequestViaHttp() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withHeader(header(AUTHORIZATION.toString(), "Bearer " + wrongKeyToken()))
                .withPath(calculatePath("clear"))
                .withBody(requestDefinitionSerializer.serialize(request()))
        );
    }

    @Test
    public void shouldRejectVerifyZeroInteractionsViaJavaClient() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::wrongKeyToken)
                .verifyZeroInteractions()
        );
    }

    @Test
    public void shouldRejectVerifyViaJavaClient() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::wrongKeyToken)
                .verify(request())
        );
    }

    @Test
    public void shouldRejectVerifyWithRequestViaHttp() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withHeader(header(AUTHORIZATION.toString(), "Bearer " + wrongKeyToken()))
                .withPath(calculatePath("verify"))
                .withBody(verificationSerializer.serialize(
                    verification()
                        .withRequest(request())
                        .withTimes(exactly(0))
                ))
        );
    }

    @Test
    public void shouldRejectVerifySequenceViaJavaClient() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::wrongKeyToken)
                .verify(request(), request())
        );
    }

    @Test
    public void shouldRejectVerifySequenceWithRequestViaHttp() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withHeader(header(AUTHORIZATION.toString(), "Bearer " + wrongKeyToken()))
                .withPath(calculatePath("verifySequence"))
                .withBody(verificationSequenceSerializer.serialize(
                    verificationSequence()
                        .withRequests(request(), request())
                ))
        );
    }

    @Test
    public void shouldRejectRetrieveRecordedRequestsViaJavaClient() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::wrongKeyToken)
                .retrieveRecordedRequests(request())
        );
    }

    @Test
    public void shouldRejectRetrieveRecordedRequestsViaHttp() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withHeader(header(AUTHORIZATION.toString(), "Bearer " + wrongKeyToken()))
                .withPath(calculatePath("retrieve"))
                .withQueryStringParameter("type", RetrieveType.REQUESTS.name())
        );
    }

    @Test
    public void shouldRejectRetrieveActiveExpectationsViaJavaClient() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::wrongKeyToken)
                .retrieveActiveExpectations(request())
        );
    }

    @Test
    public void shouldRejectRetrieveActiveExpectationsViaHttp() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withHeader(header(AUTHORIZATION.toString(), "Bearer " + wrongKeyToken()))
                .withPath(calculatePath("retrieve"))
                .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
        );
    }

    @Test
    public void shouldRejectRetrieveLogMessagesViaJavaClient() {
        clientOperationIsRejected(() ->
            mockServerClient
                .withControlPlaneJWT(AuthenticatedControlPlaneUsingOIDCClientNotAuthenticatedIntegrationTest::wrongKeyToken)
                .retrieveLogMessages(request())
        );
    }

    @Test
    public void shouldRejectRetrieveLogMessagesViaHttp() {
        httpAPIOperationIsRejected(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withHeader(header(AUTHORIZATION.toString(), "Bearer " + wrongKeyToken()))
                .withPath(calculatePath("retrieve"))
                .withQueryStringParameter("type", RetrieveType.LOGS.name())
        );
    }

    /**
     * The OIDC handler marks its {@link AuthenticationException} as NOT client-safe, so the detailed reason
     * (expected issuer, audience, required scopes, signature failure) is logged server-side only and the
     * caller receives the generic message. Asserting the generic form is deliberate: a change that started
     * leaking the reason to an unauthenticated caller should turn this test red.
     */
    private void clientOperationIsRejected(ThrowingRunnable throwingRunnable) {
        // when
        AuthenticationException authenticationException = assertThrows(AuthenticationException.class, throwingRunnable);

        // then
        assertThat(authenticationException.getMessage(), equalTo("Unauthorized for control plane"));
    }

    private void httpAPIOperationIsRejected(HttpRequest httpRequest) {
        // when
        HttpResponse httpResponse = makeRequest(httpRequest, Collections.emptyList());

        // then
        assertThat(httpResponse.getStatusCode(), equalTo(401));
        assertThat(httpResponse.getBodyAsString(), equalTo("Unauthorized for control plane"));
    }

}
