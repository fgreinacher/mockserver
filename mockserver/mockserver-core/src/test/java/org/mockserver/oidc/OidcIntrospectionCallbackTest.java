package org.mockserver.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.TokenIntrospectionResponse;
import org.mockserver.authentication.jwt.JWTGenerator;
import org.mockserver.keys.AsymmetricKeyPair;
import com.nimbusds.oauth2.sdk.TokenIntrospectionSuccessResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.HttpResponse;

import java.io.Serializable;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * Conformance tests for the mock OIDC introspection endpoint (RFC 7662).
 *
 * <p>Introspection is a security control: applications call it to decide whether to accept a token.
 * When it fabricates {@code active:true}, a user's "my application rejects an expired/revoked token"
 * test passes while proving nothing — the worst failure mode a testing tool has. These tests drive the
 * endpoint the way a real relying party would (form-encoded POST) and parse the responses with the
 * independent nimbus OAuth2 SDK rather than MockServer's own model objects.
 */
public class OidcIntrospectionCallbackTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OidcIntrospectionCallback introspection = new OidcIntrospectionCallback();
    private final OidcRevocationCallback revocation = new OidcRevocationCallback();

    @Before
    @After
    public void resetStore() {
        OidcAuthorizationStore.getInstance().reset();
    }

    // --- the core defect: an arbitrary token must NOT introspect as active ---

    @Test
    public void shouldReportGarbageTokenInactive() {
        generateProvider(new OidcProviderConfiguration());

        assertThat("an arbitrary string must never introspect as active",
            isActive(introspect("not-a-real-token-at-all")), is(false));
    }

    @Test
    public void shouldReportEmptyAndStructurallyJwtLikeGarbageInactive() {
        generateProvider(new OidcProviderConfiguration());

        // A string shaped like a JWT but signed by nobody must not slip past a shape-only check.
        assertThat(isActive(introspect("aaaa.bbbb.cccc")), is(false));
        assertThat(isActive(introspect("....")), is(false));
    }

    @Test
    public void shouldReportTokenFromADifferentProviderInactive() {
        // A token minted by one provider must not introspect as active at another — this is the check
        // that a signature is actually verified, rather than the token merely being well-formed.
        OidcProviderConfiguration foreignConfig = new OidcProviderConfiguration()
            .setIntrospectPath("/foreign-introspect")
            .setTokenPath("/foreign-token")
            .setAuthorizePath("/foreign-authorize")
            .setDeviceAuthorizationPath("/foreign-device");
        String foreignToken = accessTokenFrom(generateProvider(foreignConfig));

        OidcAuthorizationStore.getInstance().reset();
        generateProvider(new OidcProviderConfiguration());

        assertThat("a token signed by a different provider's key must be inactive",
            isActive(introspect(foreignToken)), is(false));
    }

    @Test
    public void shouldReportValidJwtAccessTokenActiveWithItsOwnClaims() {
        OidcProviderConfiguration config = new OidcProviderConfiguration().setSubject("alice");
        String accessToken = accessTokenFrom(generateProvider(config));

        JsonNode body = introspectJson(accessToken);
        assertThat(body.path("active").asBoolean(), is(true));
        // The claims must come from the presented token, not from static configuration.
        assertThat(body.path("sub").asText(), is("alice"));
    }

    @Test
    public void shouldReportExpiredTokenInactive() {
        // issueExpiredToken mints a token whose exp is in the past. Previously introspection read
        // `active` straight from this flag, so it happened to say false — but for the wrong reason, and
        // only for tokens this provider minted. Now expiry is read from the token itself.
        OidcProviderConfiguration config = new OidcProviderConfiguration().setIssueExpiredToken(true);
        String expiredToken = accessTokenFrom(generateProvider(config));

        assertThat(isActive(introspect(expiredToken)), is(false));
    }

    @Test
    public void shouldReportTamperedTokenInactive() {
        OidcProviderConfiguration config = new OidcProviderConfiguration().setTamperedSignature(true);
        String tamperedToken = accessTokenFrom(generateProvider(config));

        assertThat("a token whose signature was tampered with must be inactive",
            isActive(introspect(tamperedToken)), is(false));
    }

    @Test
    public void shouldRejectTokenWithNoExpiryClaim() {
        // `exp` is REQUIRED (RFC 7519 §4.1.4 / RFC 9068 §2.2). Treating its absence as "valid" would
        // make a non-expiring token the easiest possible way to pass validation, so it must fail closed.
        // The token here is signed by the provider's own key, so only the missing `exp` can reject it.
        OidcProviderConfiguration config = new OidcProviderConfiguration();
        AsymmetricKeyPair keyPair = OidcKeyProvider.resolveKeyPair(config);
        OidcTokenMinter minter = new OidcTokenMinter(config, keyPair);

        Map<String, Serializable> claims = new LinkedHashMap<>();
        claims.put("sub", "no-expiry");
        claims.put("iss", "http://localhost:1080");
        claims.put("iat", Instant.now().getEpochSecond());
        String tokenWithoutExp = new JWTGenerator(keyPair).signJWT(claims);

        assertThat("a validly signed token with no exp claim must not verify",
            minter.verifyAccessToken(tokenWithoutExp), is(nullValue()));

        // and the same token, minted with an exp, does verify — so the rejection is specific to exp
        claims.put("exp", Instant.now().getEpochSecond() + 3600);
        assertThat(minter.verifyAccessToken(new JWTGenerator(keyPair).signJWT(claims)),
            is(notNullValue()));
    }

    // --- revocation must actually revoke ---

    @Test
    public void shouldReportRevokedTokenInactive() {
        OidcProviderConfiguration config = new OidcProviderConfiguration();
        String accessToken = accessTokenFrom(generateProvider(config));

        assertThat("precondition: the freshly minted token is active",
            isActive(introspect(accessToken)), is(true));

        revocation.handle(request()
            .withMethod("POST")
            .withPath("/revoke")
            .withBody("token=" + accessToken));

        assertThat("a revoked token must introspect as inactive",
            isActive(introspect(accessToken)), is(false));
    }

    @Test
    public void shouldRevokeOpaqueTokenToo() {
        OidcProviderConfiguration config = new OidcProviderConfiguration().setOpaqueAccessToken(true);
        String opaqueToken = accessTokenFrom(generateProvider(config));

        assertThat(isActive(introspect(opaqueToken)), is(true));
        revocation.handle(request().withMethod("POST").withPath("/revoke")
            .withBody("token=" + opaqueToken));
        assertThat(isActive(introspect(opaqueToken)), is(false));
    }

    // --- RFC 7662 response-shape conformance ---

    @Test
    public void shouldNotLeakAnyClaimsForAnInactiveToken() throws Exception {
        // RFC 7662 §2.2: "the authorization server SHOULD NOT include any additional information about
        // an inactive token". Previously an inactive response still carried sub/iss/aud/scope and every
        // configured additional claim.
        OidcProviderConfiguration config = new OidcProviderConfiguration().setSubject("secret-subject");
        config.getAdditionalClaims().put("internal_role", "admin");
        generateProvider(config);

        JsonNode body = introspectJson("garbage");
        assertThat(body.path("active").asBoolean(), is(false));

        Iterator<String> fields = body.fieldNames();
        StringBuilder leaked = new StringBuilder();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!"active".equals(field)) {
                leaked.append(field).append(' ');
            }
        }
        assertThat("inactive introspection response leaked fields: " + leaked,
            leaked.length(), is(0));
    }

    @Test
    public void shouldRejectRequestWithoutATokenParameter() {
        generateProvider(new OidcProviderConfiguration());

        HttpResponse response = introspect(null);
        // RFC 7662 §2.1 makes `token` REQUIRED. Answering a request with no token at all is the most
        // extreme form of ignoring the token.
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("invalid_request"));
    }

    @Test
    public void shouldSetNoStoreCacheDirectives() {
        generateProvider(new OidcProviderConfiguration());

        HttpResponse response = introspect("garbage");
        assertThat(response.getFirstHeader("cache-control"), is("no-store"));
        assertThat(response.getFirstHeader("pragma"), is("no-cache"));
    }

    @Test
    public void shouldProduceResponseParseableByIndependentOidcSdk() throws Exception {
        // Parsed by the nimbus OAuth2 SDK rather than MockServer's own model, so the assertion is that
        // the response is conformant for a real relying party, not merely self-consistent.
        OidcProviderConfiguration config = new OidcProviderConfiguration().setSubject("bob");
        String accessToken = accessTokenFrom(generateProvider(config));

        TokenIntrospectionResponse active = TokenIntrospectionResponse.parse(
            toNimbusHttpResponse(introspect(accessToken)));
        assertThat(active.indicatesSuccess(), is(true));
        assertThat(active.toSuccessResponse().isActive(), is(true));
        assertThat(active.toSuccessResponse().getSubject().getValue(), is("bob"));

        TokenIntrospectionSuccessResponse inactive = TokenIntrospectionResponse.parse(
            toNimbusHttpResponse(introspect("garbage"))).toSuccessResponse();
        assertThat(inactive.isActive(), is(false));
    }

    // --- helpers ---

    private OidcAuthorizationStore.Provider generateProvider(OidcProviderConfiguration config) {
        new OidcProviderGenerator().generate(config);
        return OidcAuthorizationStore.getInstance().latestProvider();
    }

    /** Mints a token through the provider's minter and returns its access_token. */
    private String accessTokenFrom(OidcAuthorizationStore.Provider provider) {
        try {
            String tokenResponse = provider.getTokenMinter()
                .mintTokenResponse("openid profile", null, false, "http://localhost:1080");
            return OBJECT_MAPPER.readTree(tokenResponse).path("access_token").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse introspect(String token) {
        return (HttpResponse) introspection.handle(request()
            .withMethod("POST")
            .withPath("/introspect")
            .withHeader("host", "localhost:1080")
            .withBody(token == null ? "token_type_hint=access_token" : "token=" + token));
    }

    private JsonNode introspectJson(String token) {
        try {
            return OBJECT_MAPPER.readTree(introspect(token).getBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isActive(HttpResponse response) {
        try {
            return OBJECT_MAPPER.readTree(response.getBodyAsString()).path("active").asBoolean();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static com.nimbusds.oauth2.sdk.http.HTTPResponse toNimbusHttpResponse(HttpResponse response) {
        com.nimbusds.oauth2.sdk.http.HTTPResponse nimbus =
            new com.nimbusds.oauth2.sdk.http.HTTPResponse(response.getStatusCode());
        nimbus.setEntityContentType(com.nimbusds.common.contenttype.ContentType.APPLICATION_JSON);
        nimbus.setBody(response.getBodyAsString());
        return nimbus;
    }
}
