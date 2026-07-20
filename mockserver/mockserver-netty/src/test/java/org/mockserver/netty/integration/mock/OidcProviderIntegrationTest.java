package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.mock.Expectation;
import org.mockserver.oidc.OidcAuthorizationStore;
import org.mockserver.oidc.OidcProviderConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end integration test for the one-call OIDC provider over real HTTP:
 * <ol>
 *   <li>{@code PUT /mockserver/oidc} via the typed client ({@link ClientAndServer#mockOpenIdProvider}),</li>
 *   <li>fetch the discovery document and the JWKS over HTTP,</li>
 *   <li>run the full authorization_code + PKCE(S256) + nonce flow over HTTP,</li>
 *   <li>verify the issued id_token's signature against the fetched JWKS using nimbus-jose-jwt,</li>
 *   <li>assert the nonce round-trips into the id_token.</li>
 * </ol>
 */
public class OidcProviderIntegrationTest {

    private static ClientAndServer mockServer;
    private static int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @BeforeClass
    public static void startServer() {
        mockServer = ClientAndServer.startClientAndServer(0);
        port = mockServer.getLocalPort();
    }

    @AfterClass
    public static void stopServer() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Before
    public void reset() {
        mockServer.reset();
        OidcAuthorizationStore.getInstance().reset();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    public void typedClientMockOpenIdProviderReturnsExpectations() {
        Expectation[] expectations = mockServer.mockOpenIdProvider(
            new OidcProviderConfiguration().setIssuer(base()));
        assertThat(expectations.length, is(9));
    }

    /**
     * The Testcontainers case: no issuer configured, server on an ephemeral port. The issuer must be
     * derived from the address the client actually used, because OIDC Discovery §4.3 requires the
     * advertised issuer to be identical to the URL the document was fetched from — every conformant
     * relying party rejects a mismatch. A hardcoded {@code http://localhost:1080} default made this the
     * most common way to fail to use the mock provider.
     *
     * <p>Note that every other test in this class pins {@code setIssuer(base())} explicitly, which is
     * precisely why the hardcoded default survived: nothing exercised the default path.
     */
    @Test
    public void issuerIsDerivedFromTheAddressTheClientUsedWhenNotConfigured() throws Exception {
        mockServer.mockOpenIdProvider(new OidcProviderConfiguration());

        JsonNode discovery = objectMapper.readTree(get("/.well-known/openid-configuration"));

        assertThat(discovery.get("issuer").asText(), is(base()));
        assertThat(discovery.get("token_endpoint").asText(), is(base() + "/token"));
        assertThat(discovery.get("jwks_uri").asText(), is(base() + "/.well-known/jwks.json"));

        // The iss claim minted into tokens must match the advertised issuer, or the relying party
        // rejects the token even though discovery validated.
        JsonNode tokenResponse = objectMapper.readTree(
            postForm(base() + "/token", "grant_type=client_credentials&scope=openid").body());
        SignedJWT accessToken = SignedJWT.parse(tokenResponse.get("access_token").asText());
        assertThat(accessToken.getJWTClaimsSet().getIssuer(), is(base()));
    }

    /**
     * Introspection over real HTTP must reject a token it never issued. Before this, introspection
     * ignored the presented token entirely for JWT providers (the default) and reported {@code active}
     * from static configuration, so a "my application rejects a bad token" test passed while proving
     * nothing.
     */
    @Test
    public void introspectionRejectsAnArbitraryTokenOverHttp() throws Exception {
        mockServer.mockOpenIdProvider(new OidcProviderConfiguration().setIssuer(base()));

        HttpResponse<String> response = postForm(base() + "/introspect", "token=not-a-real-token");
        JsonNode body = objectMapper.readTree(response.body());

        assertThat(body.get("active").asBoolean(), is(false));
        // RFC 7662 §2.2: an inactive response must not describe the token.
        assertThat(body.has("sub"), is(false));
        // RFC 6749 §5.1 caching directives.
        assertThat(response.headers().firstValue("cache-control").orElse(null), is("no-store"));
    }

    /**
     * Userinfo is an OAuth2 protected resource (OIDC Core §5.3). It was previously a static response
     * that handed the subject and every configured claim to any caller, so "my application handles a
     * 401 from userinfo" was a test that could not fail.
     */
    @Test
    public void userinfoRequiresAValidBearerTokenOverHttp() throws Exception {
        mockServer.mockOpenIdProvider(new OidcProviderConfiguration()
            .setIssuer(base())
            .setSubject("integration-sub"));

        // no credentials → 401 with a bare challenge, and no claims in the body
        HttpResponse<String> anonymous = get(base() + "/userinfo", null);
        assertThat(anonymous.statusCode(), is(401));
        assertThat(anonymous.headers().firstValue("WWW-Authenticate").orElse(null), is("Bearer"));
        assertThat(anonymous.body().contains("integration-sub"), is(false));

        // a bad token → 401 naming the reason
        HttpResponse<String> badToken = get(base() + "/userinfo", "Bearer not-a-real-token");
        assertThat(badToken.statusCode(), is(401));
        assertThat(badToken.headers().firstValue("WWW-Authenticate").orElse(""),
            containsString("invalid_token"));

        // a real token → 200 with the subject
        String accessToken = objectMapper.readTree(
            postForm(base() + "/token", "grant_type=client_credentials&scope=openid").body())
            .get("access_token").asText();
        HttpResponse<String> authorised = get(base() + "/userinfo", "Bearer " + accessToken);
        assertThat(authorised.statusCode(), is(200));
        assertThat(objectMapper.readTree(authorised.body()).get("sub").asText(), is("integration-sub"));
    }

    private HttpResponse<String> get(String url, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        if (authorization != null) {
            builder = builder.header("Authorization", authorization);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The scenario named in the audit: a revoked token must stop introspecting as active. {@code /revoke}
     * previously returned 200 and did nothing.
     */
    @Test
    public void revokedTokenIntrospectsAsInactiveOverHttp() throws Exception {
        mockServer.mockOpenIdProvider(new OidcProviderConfiguration().setIssuer(base()));

        String accessToken = objectMapper.readTree(
            postForm(base() + "/token", "grant_type=client_credentials&scope=openid").body())
            .get("access_token").asText();

        assertThat("precondition: a freshly minted token introspects as active",
            objectMapper.readTree(postForm(base() + "/introspect", "token=" + enc(accessToken)).body())
                .get("active").asBoolean(), is(true));

        postForm(base() + "/revoke", "token=" + enc(accessToken));

        assertThat(objectMapper.readTree(postForm(base() + "/introspect", "token=" + enc(accessToken)).body())
            .get("active").asBoolean(), is(false));
    }

    @Test
    public void suppliedSigningKeyAndSecretViaClientReachServerButAreNotEchoedBack() throws Exception {
        // Fix 3 (WRITE_ONLY round-trip): privateKeyPem and clientSecret are WRITE_ONLY on the config so
        // the server never serializes them back out. The typed client re-injects them on the outbound
        // PUT, so a user-supplied signing key and secret still reach the provider. This proves BOTH:
        //   (a) the secret/key are NOT in the server's serialized response (no leak), and
        //   (b) the client-sent PUT still carried them — the issued token signs with the SUPPLIED key.

        // generate an RSA-2048 signing key, export its PKCS8 private key to PEM, pin a stable kid
        com.nimbusds.jose.jwk.RSAKey rsaKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048)
            .keyID("supplied-kid")
            .generate();
        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(rsaKey.toRSAKey().toPrivateKey().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";

        Expectation[] expectations = mockServer.mockOpenIdProvider(new OidcProviderConfiguration()
            .setIssuer(base())
            .setClientId("integration-client")
            .setClientSecret("SUPER-SECRET-CLIENT")
            .setKeyId("supplied-kid")
            .setPrivateKeyPem(privateKeyPem));

        // (a) the server never echoes the secret/key back out in the returned expectations
        String returnedJson = objectMapper.writeValueAsString(expectations);
        assertTrue("private key PEM must not be echoed back", !returnedJson.contains("BEGIN PRIVATE KEY"));
        assertTrue("client secret must not be echoed back", !returnedJson.contains("SUPER-SECRET-CLIENT"));

        // (b) the published JWKS carries the SUPPLIED public key (proves the client-sent key reached the server)
        JWKSet jwkSet = JWKSet.parse(get(pathOf(objectMapper.readTree(
            get("/.well-known/openid-configuration")).get("jwks_uri").asText())));
        RSAKey publishedKey = (RSAKey) jwkSet.getKeys().get(0);
        assertThat("published kid must be the supplied kid", publishedKey.getKeyID(), is("supplied-kid"));
        assertThat("published modulus must be the SUPPLIED key's modulus, not a generated fallback",
            publishedKey.getModulus(), is(rsaKey.getModulus()));
    }

    @Test
    public void fullAuthorizationCodePkceNonceFlowOverHttp() throws Exception {
        // 1. one-call OIDC provider, issuer pointing at this running server
        mockServer.mockOpenIdProvider(new OidcProviderConfiguration()
            .setIssuer(base())
            .setClientId("integration-client"));

        // 2. discovery
        JsonNode discovery = objectMapper.readTree(get("/.well-known/openid-configuration"));
        String authorizeEndpoint = discovery.get("authorization_endpoint").asText();
        String tokenEndpoint = discovery.get("token_endpoint").asText();
        String jwksUri = discovery.get("jwks_uri").asText();
        assertThat(discovery.get("issuer").asText(), is(base()));

        // 3. JWKS
        JWKSet jwkSet = JWKSet.parse(get(pathOf(jwksUri)));
        JWSVerifier verifier = new RSASSAVerifier((RSAKey) jwkSet.getKeys().get(0));

        // 4. authorize with PKCE + nonce
        String codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String codeChallenge = s256(codeVerifier);
        String nonce = "nonce-12345";
        String redirectUri = "https://app.example.com/callback";

        String authorizeQuery = "response_type=code"
            + "&client_id=integration-client"
            + "&redirect_uri=" + enc(redirectUri)
            + "&scope=" + enc("openid profile email")
            + "&state=xyz"
            + "&nonce=" + nonce
            + "&code_challenge=" + codeChallenge
            + "&code_challenge_method=S256";

        HttpResponse<String> authorizeResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(base() + pathOf(authorizeEndpoint) + "?" + authorizeQuery)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(authorizeResponse.statusCode(), is(302));
        String location = authorizeResponse.headers().firstValue("location").orElse(null);
        assertThat(location, is(notNullValue()));
        String code = extractCode(location);

        // 5. token exchange
        String tokenBody = "grant_type=authorization_code"
            + "&code=" + code
            + "&redirect_uri=" + enc(redirectUri)
            + "&code_verifier=" + codeVerifier;
        HttpResponse<String> tokenResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(base() + pathOf(tokenEndpoint)))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResponse.statusCode(), is(200));

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        assertTrue(tokens.has("access_token"));
        assertTrue(tokens.has("id_token"));
        assertTrue("authorization_code grant returns a refresh_token", tokens.has("refresh_token"));

        // 6. verify id_token signature against the fetched JWKS and assert nonce + aud
        SignedJWT idToken = SignedJWT.parse(tokens.get("id_token").asText());
        assertTrue("id_token must verify against the published JWKS", idToken.verify(verifier));
        assertThat(idToken.getJWTClaimsSet().getStringClaim("nonce"), is(nonce));
        assertThat(idToken.getJWTClaimsSet().getAudience().get(0), is("integration-client"));

        // access_token also verifies
        SignedJWT accessToken = SignedJWT.parse(tokens.get("access_token").asText());
        assertTrue(accessToken.verify(verifier));
    }

    @Test
    public void deviceAuthorizationGrantFlowOverHttp() throws Exception {
        // one-call OIDC provider that requires one pending poll before approval
        mockServer.mockOpenIdProvider(new OidcProviderConfiguration()
            .setIssuer(base())
            .setClientId("device-client")
            .setDeviceCodePendingPolls(1));

        JsonNode discovery = objectMapper.readTree(get("/.well-known/openid-configuration"));
        String deviceEndpoint = discovery.get("device_authorization_endpoint").asText();
        String tokenEndpoint = discovery.get("token_endpoint").asText();
        String jwksUri = discovery.get("jwks_uri").asText();
        JWSVerifier verifier = new RSASSAVerifier((RSAKey) JWKSet.parse(get(pathOf(jwksUri))).getKeys().get(0));

        // 1. device authorization request
        HttpResponse<String> deviceResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(base() + pathOf(deviceEndpoint)))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("scope=" + enc("openid profile")))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(deviceResponse.statusCode(), is(200));
        JsonNode device = objectMapper.readTree(deviceResponse.body());
        String deviceCode = device.get("device_code").asText();
        assertTrue("user_code present", device.has("user_code"));
        assertThat(device.get("interval").asInt(), is(5));

        String tokenBody = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
            + "&device_code=" + enc(deviceCode);

        // 2. first poll → authorization_pending (400)
        HttpResponse<String> pending = postForm(tokenEndpoint, tokenBody);
        assertThat(pending.statusCode(), is(400));
        assertTrue(pending.body().contains("authorization_pending"));

        // 3. second poll → tokens minted (200) and id_token verifies against the JWKS
        HttpResponse<String> minted = postForm(tokenEndpoint, tokenBody);
        assertThat(minted.statusCode(), is(200));
        JsonNode tokens = objectMapper.readTree(minted.body());
        assertTrue(tokens.has("access_token"));
        assertTrue(tokens.has("id_token"));
        assertTrue(SignedJWT.parse(tokens.get("id_token").asText()).verify(verifier));

        // 4. device code is single-use — a further poll fails
        HttpResponse<String> reused = postForm(tokenEndpoint, tokenBody);
        assertThat(reused.statusCode(), is(400));
        assertTrue(reused.body().contains("expired_token"));
    }

    // --- helpers ---

    private HttpResponse<String> postForm(String endpoint, String body) throws Exception {
        return httpClient.send(
            HttpRequest.newBuilder(URI.create(base() + pathOf(endpoint)))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat("GET " + path + " should return 200", response.statusCode(), is(200));
        return response.body();
    }

    private static String pathOf(String urlOrPath) {
        if (urlOrPath.startsWith("http")) {
            return URI.create(urlOrPath).getPath();
        }
        return urlOrPath;
    }

    private static String extractCode(String location) {
        for (String pair : location.substring(location.indexOf('?') + 1).split("&")) {
            if (pair.startsWith("code=")) {
                return pair.substring("code=".length());
            }
        }
        throw new AssertionError("no code in redirect location: " + location);
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String s256(String codeVerifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
