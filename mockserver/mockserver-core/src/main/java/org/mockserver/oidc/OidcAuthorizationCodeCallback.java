package org.mockserver.oidc;

import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.uuid.UUIDService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock OIDC {@code /authorize} endpoint implementing the OAuth2 authorization-code grant.
 *
 * <p>This is a mock — there is no login UI or consent screen. It accepts the standard authorization
 * request query parameters, deterministically issues an opaque authorization {@code code}, records
 * the code (with the {@code redirect_uri} and any PKCE challenge) in {@link OidcAuthorizationStore},
 * and 302-redirects the user agent back to {@code redirect_uri?code=<code>&state=<state>}.
 *
 * <p>The matching {@code redirect_uri} and PKCE {@code code_verifier} are validated later when the
 * code is exchanged at {@code /token} by {@link OidcTokenCallback}.
 */
public class OidcAuthorizationCodeCallback implements ExpectationResponseCallback {

    @Override
    public HttpResponse handle(HttpRequest request) {
        String responseType = request.getFirstQueryStringParameter("response_type");
        String redirectUri = request.getFirstQueryStringParameter("redirect_uri");
        String state = request.getFirstQueryStringParameter("state");
        String scope = emptyToNull(request.getFirstQueryStringParameter("scope"));
        String nonce = emptyToNull(request.getFirstQueryStringParameter("nonce"));
        String codeChallenge = emptyToNull(request.getFirstQueryStringParameter("code_challenge"));
        String codeChallengeMethod = emptyToNull(request.getFirstQueryStringParameter("code_challenge_method"));

        if (redirectUri == null || redirectUri.isEmpty()) {
            return response()
                .withStatusCode(400)
                .withHeader("content-type", "application/json; charset=utf-8")
                .withBody("{\"error\":\"invalid_request\",\"error_description\":\"redirect_uri is required\"}");
        }

        // This mock only supports the authorization-code response type.
        if (responseType != null && !responseType.isEmpty() && !"code".equals(responseType)) {
            return errorRedirect(redirectUri, state, "unsupported_response_type");
        }

        OidcAuthorizationStore store = OidcAuthorizationStore.getInstance();
        OidcAuthorizationStore.Provider provider = store.providerForAuthorizePath(request.getPath().getValue());
        if (provider == null) {
            return response()
                .withStatusCode(404)
                .withHeader("content-type", "application/json; charset=utf-8")
                .withBody("{\"error\":\"server_error\",\"error_description\":\"no OIDC provider registered for this authorize endpoint\"}");
        }

        String code = "mock-auth-code-" + UUIDService.getNonSecureUUID();
        store.putCode(code, new OidcAuthorizationStore.AuthorizationCode(
            redirectUri, codeChallenge, codeChallengeMethod, scope, nonce
        ));

        StringBuilder location = new StringBuilder(redirectUri);
        location.append(redirectUri.contains("?") ? '&' : '?');
        location.append("code=").append(urlEncode(code));
        if (state != null) {
            location.append("&state=").append(urlEncode(state));
        }

        return response()
            .withStatusCode(302)
            .withHeader("location", location.toString());
    }

    private HttpResponse errorRedirect(String redirectUri, String state, String error) {
        StringBuilder location = new StringBuilder(redirectUri);
        location.append(redirectUri.contains("?") ? '&' : '?');
        location.append("error=").append(urlEncode(error));
        if (state != null) {
            location.append("&state=").append(urlEncode(state));
        }
        return response()
            .withStatusCode(302)
            .withHeader("location", location.toString());
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
