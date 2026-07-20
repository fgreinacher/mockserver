package org.mockserver.oidc;

import java.util.Map;

/**
 * The single place an access token is turned into claims, or rejected.
 *
 * <p>Both {@link OidcIntrospectionCallback} ({@code /introspect}) and {@link OidcUserinfoCallback}
 * ({@code /userinfo}) are OAuth2 protected surfaces that must reach the same verdict about the same
 * token. They previously did not: introspection ignored the token entirely, and userinfo was a static
 * response that never looked at the {@code Authorization} header at all. Keeping the decision in one
 * method means a future change to one endpoint cannot silently leave the other fail-open — the defect
 * pattern this class exists to prevent.
 *
 * <p>The resolution order is deliberate:
 * <ol>
 *   <li><b>revoked first</b>, so revocation cannot be bypassed by a token that is still
 *       cryptographically valid (RFC 7009);</li>
 *   <li><b>opaque by reference</b>, the only way an opaque token can be validated;</li>
 *   <li><b>JWT by signature + validity window</b> against this provider's own key, so a token minted
 *       by a different provider, tampered with, or expired does not verify.</li>
 * </ol>
 */
final class OidcTokenResolver {

    private OidcTokenResolver() {
    }

    /**
     * Resolves a presented access token to its claims.
     *
     * @param provider the provider whose endpoint received the token; may be {@code null}
     * @param token    the presented token; may be {@code null}
     * @return the token's claims when it is genuinely valid, otherwise {@code null}. A {@code null}
     * return always means "reject" — callers must never fall back to static configuration.
     */
    static Map<String, Object> resolveActiveClaims(OidcAuthorizationStore.Provider provider, String token) {
        if (provider == null || token == null || token.isEmpty()) {
            return null;
        }
        OidcAuthorizationStore store = OidcAuthorizationStore.getInstance();

        if (store.isRevoked(token)) {
            return null;
        }

        OidcAuthorizationStore.OpaqueToken opaque = store.lookupOpaqueToken(token);
        if (opaque != null) {
            return opaque.isExpired(System.currentTimeMillis()) ? null : opaque.claims;
        }
        if (provider.config.isOpaqueAccessToken()) {
            // This provider issues opaque tokens and this one is not among them.
            return null;
        }

        return provider.tokenMinter.verifyAccessToken(token);
    }

    /**
     * Extracts a bearer token from an {@code Authorization} header value (RFC 6750 §2.1).
     * The {@code Bearer} scheme name is case-insensitive per RFC 7235 §2.1; the token itself is not.
     *
     * @return the token, or {@code null} when the header is absent or is not a well-formed Bearer
     * credential
     */
    static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String trimmed = authorizationHeader.trim();
        if (trimmed.length() <= 7 || !trimmed.substring(0, 7).equalsIgnoreCase("Bearer ")) {
            return null;
        }
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
