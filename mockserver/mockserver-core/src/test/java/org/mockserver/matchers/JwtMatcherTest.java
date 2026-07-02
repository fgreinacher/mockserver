package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Jwt;
import org.mockserver.model.RequestDefinition;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.Jwt.jwt;
import static org.mockserver.model.NottableString.not;

/**
 * Focused tests for matching an expectation on the claims of a (signature-unverified) JSON Web Token
 * carried in a request header.
 *
 * @author jamesdbloom
 */
public class JwtMatcherTest {

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(JwtMatcherTest.class);

    private HttpRequestPropertiesMatcher matcher(RequestDefinition requestDefinition) {
        HttpRequestPropertiesMatcher httpRequestPropertiesMatcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        httpRequestPropertiesMatcher.update(new Expectation(requestDefinition));
        return httpRequestPropertiesMatcher;
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Build an unsigned JWT string from a JOSE header and a claims payload (the signature segment is a
     * placeholder — this matcher never verifies it).
     */
    private static String token(String headerJson, String payloadJson) {
        return base64Url(headerJson) + "." + base64Url(payloadJson) + ".signature-not-verified";
    }

    private HttpRequest requestWithAuthorization(String token) {
        return request().withHeader("authorization", "Bearer " + token);
    }

    // MATCH BY CLAIM

    @Test
    public void shouldMatchByExactClaim() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-1\"}"))), is(true));
    }

    @Test
    public void shouldMatchByRegexClaim() {
        assertThat(matcher(request().withJwt(jwt().withClaim("scope", ".*admin.*")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"scope\":\"read write admin\"}"))), is(true));
    }

    @Test
    public void shouldMatchByMultipleClaims() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1").withClaim("scope", ".*admin.*")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-1\",\"scope\":\"admin\"}"))), is(true));
    }

    @Test
    public void shouldNotMatchWhenOneClaimDiffers() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1").withClaim("scope", ".*admin.*")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-1\",\"scope\":\"read\"}"))), is(false));
    }

    @Test
    public void shouldNotMatchByDifferentClaim() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-2\"}"))), is(false));
    }

    @Test
    public void shouldNotMatchWhenClaimAbsent() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"other\":\"value\"}"))), is(false));
    }

    @Test
    public void shouldMatchByNegatedClaim() {
        assertThat(matcher(request().withJwt(jwt().withClaims(java.util.Collections.singletonMap("sub", not("admin")))))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-1\"}"))), is(true));
    }

    @Test
    public void shouldNotMatchByNegatedClaimWhenValueMatches() {
        assertThat(matcher(request().withJwt(jwt().withClaims(java.util.Collections.singletonMap("sub", not("admin")))))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"sub\":\"admin\"}"))), is(false));
    }

    // ISSUER / AUDIENCE / ALGORITHM CONVENIENCE FIELDS

    @Test
    public void shouldMatchByIssuer() {
        assertThat(matcher(request().withJwt(jwt().withIssuer("https://issuer.example.com")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"iss\":\"https://issuer.example.com\"}"))), is(true));
    }

    @Test
    public void shouldMatchByAudienceString() {
        assertThat(matcher(request().withJwt(jwt().withAudience("my-api")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"aud\":\"my-api\"}"))), is(true));
    }

    @Test
    public void shouldMatchByAudienceArrayElement() {
        assertThat(matcher(request().withJwt(jwt().withAudience("my-api")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"aud\":[\"other-api\",\"my-api\"]}"))), is(true));
    }

    @Test
    public void shouldNotMatchByAudienceNotInArray() {
        assertThat(matcher(request().withJwt(jwt().withAudience("missing-api")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"aud\":[\"other-api\",\"my-api\"]}"))), is(false));
    }

    @Test
    public void shouldMatchByAlgorithm() {
        assertThat(matcher(request().withJwt(jwt().withAlgorithm("RS256")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"RS256\"}", "{\"sub\":\"user-1\"}"))), is(true));
    }

    @Test
    public void shouldNotMatchByDifferentAlgorithm() {
        assertThat(matcher(request().withJwt(jwt().withAlgorithm("RS256")))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-1\"}"))), is(false));
    }

    @Test
    public void shouldMatchByNegatedAlgorithm() {
        assertThat(matcher(request().withJwt(jwt().withAlgorithm(not("RS256"))))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-1\"}"))), is(true));
    }

    @Test
    public void shouldNotMatchByNegatedAlgorithmWhenValueMatches() {
        assertThat(matcher(request().withJwt(jwt().withAlgorithm(not("RS256"))))
            .matches(null, requestWithAuthorization(token("{\"alg\":\"RS256\"}", "{\"sub\":\"user-1\"}"))), is(false));
    }

    @Test
    public void shouldMatchByNegatedAlgorithmWhenAlgAbsent() {
        // a negated criterion matches vacuously when the token carries no "alg" header field, matching
        // the De Morgan semantics already used for negated issuer / audience / claim criteria
        assertThat(matcher(request().withJwt(jwt().withAlgorithm(not("RS256"))))
            .matches(null, requestWithAuthorization(token("{}", "{\"sub\":\"user-1\"}"))), is(true));
    }

    // HEADER / SCHEME CONFIGURATION

    @Test
    public void shouldReadTokenFromCustomHeader() {
        assertThat(matcher(request().withJwt(jwt().withHeader("x-access-token").withClaim("sub", "user-1")))
            .matches(null, request().withHeader("x-access-token", "Bearer " + token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-1\"}"))), is(true));
    }

    @Test
    public void shouldReadTokenWithoutSchemePrefix() {
        assertThat(matcher(request().withJwt(jwt().withScheme("").withClaim("sub", "user-1")))
            .matches(null, request().withHeader("authorization", token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-1\"}"))), is(true));
    }

    @Test
    public void shouldStripSchemeCaseInsensitively() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1")))
            .matches(null, request().withHeader("authorization", "bearer " + token("{\"alg\":\"HS256\"}", "{\"sub\":\"user-1\"}"))), is(true));
    }

    // ABSENT / MALFORMED TOKENS NEVER MATCH AND NEVER THROW

    @Test
    public void shouldNotMatchWhenHeaderAbsent() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1")))
            .matches(null, request()), is(false));
    }

    @Test
    public void shouldNotMatchWhenTokenNotThreeSegments() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1")))
            .matches(null, requestWithAuthorization("not-a-jwt")), is(false));
    }

    @Test
    public void shouldNotMatchWhenPayloadNotValidBase64Json() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1")))
            .matches(null, requestWithAuthorization("aGVhZGVy.not-valid-base64-$$$.sig")), is(false));
    }

    @Test
    public void shouldNotMatchWhenPayloadNotJson() {
        assertThat(matcher(request().withJwt(jwt().withClaim("sub", "user-1")))
            .matches(null, requestWithAuthorization(base64Url("{\"alg\":\"HS256\"}") + "." + base64Url("this is not json") + ".sig")), is(false));
    }

    // BLANK CRITERIA MATCHES EVERYTHING

    @Test
    public void shouldMatchWhenJwtCriteriaBlank() {
        assertThat(matcher(request().withJwt(jwt()))
            .matches(null, request()), is(true));
    }

    @Test
    public void shouldMatchWhenNoJwtCriteria() {
        assertThat(matcher(request().withPath("/some/path"))
            .matches(null, request().withPath("/some/path")), is(true));
    }
}
