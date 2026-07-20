package org.mockserver.oidc;

import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.oidc.OidcDeviceAuthorizationCallback.DEVICE_CODE_GRANT_TYPE;

/**
 * Mock OIDC {@code /token} endpoint.
 *
 * <p>For {@code client_credentials}, {@code refresh_token}, or any request without an
 * {@code authorization_code} grant, the provider's {@link OidcTokenMinter} mints a fresh token
 * response at request time, honouring the requested scope (and including a refresh_token for the
 * {@code refresh_token} grant).
 *
 * <p>For {@code grant_type=authorization_code} it completes the authorization-code flow: it consumes
 * the single-use {@code code} issued by {@link OidcAuthorizationCodeCallback}, validates the
 * {@code redirect_uri} matches the one bound at {@code /authorize}, validates the PKCE
 * {@code code_verifier} against the stored {@code code_challenge} (when one was supplied), then mints
 * the token response at request time — embedding the {@code nonce} echoed from the authorize request
 * into the {@code id_token}.
 */
public class OidcTokenCallback implements ExpectationResponseCallback {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    @Override
    public HttpResponse handle(HttpRequest request) {
        Map<String, String> form = parseFormBody(request.getBodyAsString());
        String grantType = form.get("grant_type");

        OidcAuthorizationStore store = OidcAuthorizationStore.getInstance();
        OidcAuthorizationStore.Provider provider = store.providerForTokenPath(request.getPath().getValue());

        if (provider == null) {
            return response()
                .withStatusCode(200)
                .withHeader("content-type", JSON_CONTENT_TYPE)
                .withBody("{}");
        }

        // Token-endpoint client authentication (RFC 6749 §2.3 / §3.2.1). When enforced, validate
        // client_secret_basic (Authorization: Basic) and client_secret_post (form params); a missing or
        // wrong credential yields RFC 6749 §5.2 invalid_client (HTTP 401 + WWW-Authenticate: Basic).
        if (provider.config.isEnforceClientAuthentication()) {
            HttpResponse authFailure = validateClientAuthentication(request, form, provider.config);
            if (authFailure != null) {
                return authFailure;
            }
        }

        // The `iss` claim minted into the tokens must match the issuer advertised by the discovery
        // document, which is resolved per request from the Host header — otherwise every conformant
        // relying party rejects the token.
        String issuer = OidcIssuerResolver.resolve(provider.config, request);

        if (DEVICE_CODE_GRANT_TYPE.equals(grantType)) {
            return handleDeviceCodeGrant(store, provider, form, issuer);
        }

        if (!"authorization_code".equals(grantType)) {
            // client_credentials, refresh_token, or unspecified. Tokens are minted at request time so
            // the requested scope is honoured. No nonce/id_token for client_credentials unless the
            // openid scope is requested; the refresh_token grant returns a fresh refresh_token.
            boolean refreshGrant = "refresh_token".equals(grantType);
            String tokenResponse = provider.tokenMinter.mintTokenResponse(form.get("scope"), null, refreshGrant, issuer);
            return tokenSuccess(tokenResponse);
        }

        String code = form.get("code");
        OidcAuthorizationStore.AuthorizationCode authorizationCode = store.consumeCode(code);
        if (authorizationCode == null) {
            return tokenError("invalid_grant", "authorization code is invalid, expired, or already used", 400);
        }

        String redirectUri = form.get("redirect_uri");
        if (authorizationCode.redirectUri != null && !authorizationCode.redirectUri.equals(redirectUri)) {
            return tokenError("invalid_grant", "redirect_uri does not match the authorization request", 400);
        }

        if (authorizationCode.codeChallenge != null) {
            String codeVerifier = form.get("code_verifier");
            if (codeVerifier == null || codeVerifier.isEmpty()) {
                return tokenError("invalid_grant", "code_verifier is required for PKCE", 400);
            }
            if (!verifyPkce(authorizationCode.codeChallenge, authorizationCode.codeChallengeMethod, codeVerifier)) {
                return tokenError("invalid_grant", "PKCE verification failed", 400);
            }
        }

        // Mint at exchange time so the id_token carries the nonce echoed from /authorize. The
        // authorization_code grant always returns a refresh_token.
        String tokenResponse = provider.tokenMinter.mintTokenResponse(
            authorizationCode.scope, authorizationCode.nonce, true, issuer);
        return tokenSuccess(tokenResponse);
    }

    /**
     * Device-code grant polling (RFC 8628 §3.4/§3.5). For the configured number of polls the endpoint
     * answers {@code authorization_pending}; once the device is "approved" it consumes the
     * (single-use) device code and mints tokens. Unknown/expired device codes yield
     * {@code expired_token}.
     */
    private HttpResponse handleDeviceCodeGrant(OidcAuthorizationStore store,
                                               OidcAuthorizationStore.Provider provider,
                                               Map<String, String> form,
                                               String issuer) {
        String deviceCode = form.get("device_code");
        OidcAuthorizationStore.DeviceCode pending = store.peekDeviceCode(deviceCode);
        if (pending == null) {
            // Unknown, already-redeemed, or past its TTL.
            return tokenError("expired_token", "device_code is unknown, expired, or already redeemed", 400);
        }
        // Atomically claim one of the remaining "pending" polls. getAndDecrement returns the value
        // BEFORE decrementing, so a positive prior value means this poll must still answer
        // authorization_pending. Concurrent polls each claim a distinct slot (no lost decrements).
        if (pending.pendingPolls.getAndDecrement() > 0) {
            // The user has not yet approved — tell the client to keep polling.
            return tokenError("authorization_pending", "the user has not yet approved the device", 400);
        }
        // Approved: consume the device code (single-use) and mint tokens honouring the requested scope.
        OidcAuthorizationStore.DeviceCode approved = store.consumeDeviceCode(deviceCode);
        if (approved == null) {
            return tokenError("expired_token", "device_code is unknown, expired, or already redeemed", 400);
        }
        String tokenResponse = provider.tokenMinter.mintTokenResponse(approved.scope, null, true, issuer);
        return tokenSuccess(tokenResponse);
    }

    /**
     * Validates client authentication for the token endpoint (RFC 6749 §2.3.1). Returns {@code null}
     * when authentication succeeds, or an RFC 6749 §5.2 {@code invalid_client} (HTTP 401) response when
     * credentials are missing or wrong.
     */
    private HttpResponse validateClientAuthentication(HttpRequest request, Map<String, String> form,
                                                      OidcProviderConfiguration config) {
        String clientId = null;
        String clientSecret = null;

        // client_secret_basic: Authorization: Basic base64(clientId:clientSecret)
        String authorization = request.getFirstHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(
                    Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon >= 0) {
                    clientId = urlDecode(decoded.substring(0, colon));
                    clientSecret = urlDecode(decoded.substring(colon + 1));
                }
            } catch (IllegalArgumentException e) {
                return invalidClient();
            }
        }

        // client_secret_post: client_id / client_secret in the form body (only if not already in header)
        if (clientId == null && clientSecret == null) {
            clientId = form.get("client_id");
            clientSecret = form.get("client_secret");
        }

        if (clientId == null || clientSecret == null
            || !clientId.equals(config.getClientId())
            || !secretsMatch(clientSecret, config.getClientSecret())) {
            return invalidClient();
        }
        return null;
    }

    /**
     * Constant-time comparison of the presented client_secret against the configured one. Using
     * {@link MessageDigest#isEqual} avoids the early-exit timing side channel of {@link String#equals},
     * so the response time does not leak how many leading characters of the secret were correct.
     */
    private static boolean secretsMatch(String presented, String configured) {
        if (presented == null || configured == null) {
            return false;
        }
        return MessageDigest.isEqual(
            presented.getBytes(StandardCharsets.UTF_8),
            configured.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse invalidClient() {
        return noStore(response()
            .withStatusCode(401)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withHeader("WWW-Authenticate", "Basic")
            .withBody("{\"error\":\"invalid_client\",\"error_description\":\"client authentication failed\"}"));
    }

    private HttpResponse tokenSuccess(String tokenResponse) {
        return noStore(response()
            .withStatusCode(200)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withBody(tokenResponse));
    }

    private HttpResponse tokenError(String error, String description, int statusCode) {
        // RFC 6749 §5.2 error envelope: error, error_description, and an optional error_uri.
        String errorUri = "https://www.mock-server.com/mock_server/mock_openid_connect_provider.html";
        return noStore(response()
            .withStatusCode(statusCode)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withBody("{\"error\":\"" + error + "\",\"error_description\":\"" + description
                + "\",\"error_uri\":\"" + errorUri + "\"}"));
    }

    /**
     * Applies the RFC 6749 §5.1 caching directives. The token endpoint returns credentials, so
     * "The authorization server MUST include the HTTP 'Cache-Control' response header field with a
     * value of 'no-store' [...] and 'Pragma' with a value of 'no-cache'" — otherwise an intermediary
     * or client HTTP cache can replay a token response, including after the token has expired.
     */
    private static HttpResponse noStore(HttpResponse response) {
        return response
            .withHeader("cache-control", "no-store")
            .withHeader("pragma", "no-cache");
    }

    /**
     * Verifies a PKCE code_verifier against the stored code_challenge. Defaults to S256 when the
     * method is absent; supports the {@code plain} method as well.
     */
    private static boolean verifyPkce(String codeChallenge, String codeChallengeMethod, String codeVerifier) {
        String method = (codeChallengeMethod == null || codeChallengeMethod.isEmpty()) ? "S256" : codeChallengeMethod;
        if ("plain".equalsIgnoreCase(method)) {
            return codeChallenge.equals(codeVerifier);
        }
        // S256: BASE64URL-ENCODE(SHA256(ASCII(code_verifier)))
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return computed.equals(codeChallenge);
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
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
