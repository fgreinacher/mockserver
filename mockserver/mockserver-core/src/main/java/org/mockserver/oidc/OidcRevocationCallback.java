package org.mockserver.oidc;

import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock OAuth2 token revocation endpoint (RFC 7009).
 *
 * <p>Records the presented {@code token} as revoked so {@link OidcIntrospectionCallback} subsequently
 * reports it inactive. Previously this endpoint returned a static 200 and did nothing, so a token
 * introspected as {@code active:true} immediately after being revoked — which made
 * "my application rejects a revoked token" a test that passes while proving nothing.
 *
 * <p>Per RFC 7009 §2.2 the endpoint returns 200 regardless of whether the token was recognised
 * (an unknown token is already "not active", which is the requested outcome), so a client cannot use
 * the revocation endpoint as a token oracle.
 */
public class OidcRevocationCallback implements ExpectationResponseCallback {

    @Override
    public HttpResponse handle(HttpRequest request) {
        Map<String, String> form = parseFormBody(request.getBodyAsString());
        String token = form.get("token");
        if (token != null && !token.isEmpty()) {
            OidcAuthorizationStore.getInstance().revokeToken(token);
        }
        // RFC 7009 §2.2: 200 with an empty body, whether or not the token was recognised.
        return response()
            .withStatusCode(200)
            .withHeader("cache-control", "no-store")
            .withHeader("pragma", "no-cache")
            .withBody("");
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
