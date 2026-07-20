package org.mockserver.oidc;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock OIDC/OAuth2 introspection endpoint (RFC 7662).
 *
 * <p>The presented {@code token} form parameter is always validated — introspection is a security
 * control, so it fails closed:
 * <ul>
 *   <li>no {@code token} parameter → {@code 400 invalid_request} (RFC 7662 §2.1);</li>
 *   <li>revoked token → {@code {"active":false}} (RFC 7009);</li>
 *   <li>known, unexpired opaque token → {@code {"active":true, ...claims}};</li>
 *   <li>JWT token that verifies against the provider's signing key and is inside its validity
 *       window → {@code {"active":true, ...claims from the token}};</li>
 *   <li>anything else — garbage, expired, tampered, another provider's token → {@code {"active":false}}
 *       and nothing else (RFC 7662 §2.2).</li>
 * </ul>
 *
 * <p><b>Behaviour change.</b> This endpoint previously ignored the token entirely for JWT providers
 * (the default) and reported {@code active} from static configuration, so every string introspected as
 * active. Tests asserting that an application rejects an expired or revoked token passed while proving
 * nothing; such tests will now correctly fail if the application does not in fact reject them.
 */
public class OidcIntrospectionCallback implements ExpectationResponseCallback {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    // ObjectWriter is immutable and thread-safe once configured, and the configuration here is
    // stateless (pretty=true, serialiseDefaultValues=false), so a single shared instance is reused
    // across all requests rather than re-created per callback construction.
    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);

    @Override
    public HttpResponse handle(HttpRequest request) {
        OidcAuthorizationStore store = OidcAuthorizationStore.getInstance();
        OidcAuthorizationStore.Provider provider = store.providerForIntrospectPath(request.getPath().getValue());

        Map<String, String> form = parseFormBody(request.getBodyAsString());
        String token = emptyToNull(form.get("token"));

        // RFC 7662 §2.1: `token` is REQUIRED. A request without one is an invalid_request, not an
        // introspection result — answering it with `active:true` would be the strongest possible form
        // of the fabricated-success defect.
        if (token == null) {
            return error(400, "invalid_request", "the 'token' parameter is REQUIRED (RFC 7662 section 2.1)");
        }
        // Revoked / opaque / JWT resolution lives in OidcTokenResolver so this endpoint and
        // /userinfo cannot reach different verdicts about the same token.
        Map<String, Object> claims = OidcTokenResolver.resolveActiveClaims(provider, token);
        return claims == null ? inactive() : active(claims);
    }

    /**
     * An active-token response: {@code active:true} plus the token's own claims.
     *
     * <p>The claims come from the token itself, never from static provider configuration, so
     * introspection describes the token that was actually presented.
     */
    private HttpResponse active(Map<String, Object> claims) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", true);
        body.putAll(claims);
        return json(body);
    }

    /**
     * An inactive-token response.
     *
     * <p>RFC 7662 §2.2: "If the introspection call is properly authorized but the token is not
     * active [...] the authorization server MUST return an introspection response with the
     * {@code active} field set to {@code false}. Note that to avoid disclosing too much of the
     * authorization server's state to a third party, the authorization server SHOULD NOT include any
     * additional information about an inactive token." So this body carries {@code active:false} and
     * nothing else — previously an inactive result still leaked {@code sub}, {@code iss},
     * {@code aud}, {@code scope} and every configured additional claim.
     */
    private HttpResponse inactive() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", false);
        return json(body);
    }

    private HttpResponse error(int statusCode, String error, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        return response()
            .withStatusCode(statusCode)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withHeader("cache-control", "no-store")
            .withHeader("pragma", "no-cache")
            .withBody(serializeToJson(body));
    }

    private HttpResponse json(Object body) {
        return response()
            .withStatusCode(200)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            // RFC 7662 §2.2 / RFC 6749 §5.1: introspection responses carry token state and MUST NOT be
            // cached, otherwise an intermediary can serve a stale `active:true` for a token that has
            // since expired or been revoked.
            .withHeader("cache-control", "no-store")
            .withHeader("pragma", "no-cache")
            .withBody(serializeToJson(body));
    }

    private String serializeToJson(Object value) {
        try {
            return OBJECT_WRITER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OIDC introspection response to JSON", e);
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> form = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return form;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                form.put(urlDecode(pair), "");
            } else {
                form.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
        return form;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
