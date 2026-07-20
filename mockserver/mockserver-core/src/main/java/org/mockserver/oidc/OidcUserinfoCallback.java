package org.mockserver.oidc;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock OIDC userinfo endpoint (OIDC Core §5.3).
 *
 * <p>Userinfo is an <b>OAuth2 protected resource</b>: it MUST be called with a bearer access token and
 * MUST return {@code 401} when that token is missing or invalid (OIDC Core §5.3.1, RFC 6750 §3.1).
 *
 * <p><b>Behaviour change.</b> This endpoint was previously a static response that returned {@code sub}
 * and every configured additional claim to <em>any</em> caller, with no inspection of the
 * {@code Authorization} header at all. That made two common tests impossible to fail:
 * "my application handles a 401 from userinfo when the access token has expired" could never see a
 * 401, and "my application only calls userinfo with a valid token" passed unconditionally. It is the
 * same fabricated-success defect as the introspection endpoint, one endpoint over, and it survived for
 * the same reason the hardcoded issuer did: no test ever exercised the failure path.
 *
 * <p>Token resolution is delegated to {@link OidcTokenResolver} so this endpoint and
 * {@code /introspect} cannot drift into disagreeing about the same token.
 */
public class OidcUserinfoCallback implements ExpectationResponseCallback {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);

    @Override
    public HttpResponse handle(HttpRequest request) {
        OidcAuthorizationStore.Provider provider = OidcAuthorizationStore.getInstance()
            .providerForUserinfoPath(request.getPath().getValue());

        String token = OidcTokenResolver.bearerToken(request.getFirstHeader("authorization"));
        if (token == null) {
            // RFC 6750 §3.1: no credentials presented — challenge without an error code, so the client
            // learns it must authenticate rather than that its token was rejected. The body must stay
            // empty for the same reason: emitting {"error":"invalid_token"} here would contradict the
            // header and re-blur exactly the distinction the challenge draws.
            return unauthorized("Bearer", "");
        }

        Map<String, Object> claims = OidcTokenResolver.resolveActiveClaims(provider, token);
        if (claims == null) {
            return unauthorized("Bearer error=\"invalid_token\", "
                    + "error_description=\"the access token is expired, revoked, malformed, or invalid\"",
                "{\"error\":\"invalid_token\"}");
        }

        return response()
            .withStatusCode(200)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withHeader("cache-control", "no-store")
            .withHeader("pragma", "no-cache")
            .withBody(serializeToJson(userinfoFor(provider, claims)));
    }

    /**
     * Builds the userinfo response. {@code sub} is taken from the presented token rather than from
     * configuration, so userinfo describes the subject the token was actually issued for; the
     * provider's configured additional claims are then merged in as the "profile" the mock serves.
     */
    private Map<String, Object> userinfoFor(OidcAuthorizationStore.Provider provider, Map<String, Object> claims) {
        Map<String, Object> userinfo = new LinkedHashMap<>();
        Object subject = claims.get("sub");
        userinfo.put("sub", subject != null ? subject : provider.getConfig().getSubject());
        Map<String, Serializable> additionalClaims = provider.getConfig().getAdditionalClaims();
        if (additionalClaims != null) {
            userinfo.putAll(additionalClaims);
        }
        return userinfo;
    }

    /**
     * A 401 challenge. The body is passed in rather than fixed because it must agree with the
     * challenge: RFC 6750 §3.1 omits the error code when no credentials were presented, so that path
     * carries an empty body. Emitting {@code {"error":"invalid_token"}} for a request that presented no
     * token would contradict the header and re-blur the distinction the challenge exists to draw.
     */
    private HttpResponse unauthorized(String challenge, String body) {
        return response()
            .withStatusCode(401)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withHeader("WWW-Authenticate", challenge)
            .withHeader("cache-control", "no-store")
            .withHeader("pragma", "no-cache")
            .withBody(body);
    }

    private String serializeToJson(Object value) {
        try {
            return OBJECT_WRITER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OIDC userinfo response to JSON", e);
        }
    }
}
