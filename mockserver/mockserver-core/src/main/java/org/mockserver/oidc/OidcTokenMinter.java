package org.mockserver.oidc;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import org.mockserver.authentication.jwt.JWTGenerator;
import org.mockserver.keys.AsymmetricKeyConverter;
import org.mockserver.keys.AsymmetricKeyPair;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mints the OIDC token-endpoint response (access_token, id_token, refresh_token) at request time.
 *
 * <p>Token minting was moved out of {@link OidcProviderGenerator#generate} so that per-request
 * context — most importantly the {@code nonce} echoed back from the {@code /authorize} request — can
 * be embedded into the {@code id_token}. The provider carries its {@link AsymmetricKeyPair} (and the
 * {@link JWTGenerator} built from it) on {@link OidcAuthorizationStore.Provider}; the same key both
 * signs here and is published at the JWKS endpoint, preserving the sign/publish invariant.
 *
 * <p>Claim split (per OIDC core):
 * <ul>
 *   <li><b>id_token</b> — {@code iss, sub, aud=clientId, exp, iat, nbf, nonce} (when supplied),
 *       profile/email claims for the requested scopes, {@code at_hash}, plus additionalClaims.
 *       Only issued when the {@code openid} scope was requested.</li>
 *   <li><b>access_token</b> — {@code iss, sub, aud=audience, exp, iat, nbf, scope, client_id} plus
 *       additionalClaims.</li>
 * </ul>
 */
public class OidcTokenMinter {

    private final ObjectWriter objectWriter = ObjectMapperFactory.createObjectMapper(true, false);

    private final OidcProviderConfiguration config;
    private final JWTGenerator jwtGenerator;
    private final String jwsAlgorithm;
    private final AsymmetricKeyPair keyPair;

    public OidcTokenMinter(OidcProviderConfiguration config, AsymmetricKeyPair keyPair) {
        this.config = config;
        this.jwtGenerator = new JWTGenerator(keyPair);
        this.jwsAlgorithm = keyPair.getAlgorithm().getJwtAlgorithm();
        this.keyPair = keyPair;
    }

    /**
     * Verifies a JWT access token that this minter issued, returning its claims when the token is
     * genuinely valid and {@code null} when it is not.
     *
     * <p>This is the validation path behind {@link OidcIntrospectionCallback}. Introspection
     * previously ignored the presented token entirely for JWT providers and reported {@code active}
     * from static configuration, so <em>any</em> string — garbage, expired, tampered or revoked —
     * introspected as active. A test asserting "my application rejects a revoked token" therefore
     * passed while proving nothing. Introspection is a security control, so it must fail closed:
     * anything that does not verify against this provider's signing key, within its validity window,
     * is inactive.
     *
     * <p>Checks applied, all of which must pass:
     * <ul>
     *   <li>the token parses as a JWS and its signature verifies against this provider's key pair
     *       (so a token minted by another provider, or one whose signature was tampered with, fails);</li>
     *   <li>{@code exp} is in the future and {@code nbf}/{@code iat} are not in the future, allowing
     *       {@link #CLOCK_SKEW_SECONDS} of skew.</li>
     * </ul>
     *
     * @param token the presented access token
     * @return the token's claims when valid, otherwise {@code null}
     */
    public Map<String, Object> verifyAccessToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            JWSObject jws = JWSObject.parse(token);
            if (!jws.verify(verifier())) {
                return null;
            }
            Map<String, Object> claims = jws.getPayload().toJSONObject();
            if (claims == null || !withinValidityWindow(claims)) {
                return null;
            }
            return claims;
        } catch (Exception e) {
            // A malformed token, an unparseable payload or an unsupported algorithm are all simply
            // "not a valid token" for introspection purposes — fail closed rather than propagating.
            return null;
        }
    }

    /** Tolerance applied to {@code exp}/{@code nbf}/{@code iat}, matching common OIDC library defaults. */
    static final long CLOCK_SKEW_SECONDS = 60L;

    /** The JWS algorithm this provider signs with, advertised in the discovery document. */
    public String getJwsAlgorithm() {
        return jwsAlgorithm;
    }

    private JWSVerifier verifier() throws Exception {
        byte[] publicKeyBytes = keyPair.getKeyPair().getPublic().getEncoded();
        switch (keyPair.getAlgorithm()) {
            case EC256_SHA256:
            case EC384_SHA384:
            case ECP512_SHA512:
                return new ECDSAVerifier(AsymmetricKeyConverter.getECPublicKey(publicKeyBytes));
            case RSA2048_SHA256:
            case RSA3072_SHA384:
            case RSA4096_SHA512:
                return new RSASSAVerifier(AsymmetricKeyConverter.getRSAPublicKey(publicKeyBytes));
            default:
                throw new IllegalArgumentException("Error invalid algorithm has been provided");
        }
    }

    private static boolean withinValidityWindow(Map<String, Object> claims) {
        long now = Instant.now().getEpochSecond();
        Long exp = asEpochSeconds(claims.get("exp"));
        // `exp` is REQUIRED (RFC 7519 §4.1.4 for the id_token, RFC 9068 §2.2 for a JWT access token).
        // Treating a token with no expiry as valid would make a non-expiring token the easiest way to
        // pass validation — fail closed on its absence rather than granting unlimited lifetime.
        if (exp == null) {
            return false;
        }
        if (now > exp + CLOCK_SKEW_SECONDS) {
            return false;
        }
        Long nbf = asEpochSeconds(claims.get("nbf"));
        if (nbf != null && now + CLOCK_SKEW_SECONDS < nbf) {
            return false;
        }
        Long iat = asEpochSeconds(claims.get("iat"));
        return iat == null || now + CLOCK_SKEW_SECONDS >= iat;
    }

    private static Long asEpochSeconds(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Mints a token-endpoint response JSON for the given grant.
     *
     * @param requestedScope the scope string for this grant (space-delimited); falls back to the
     *                       configured scopes when null/blank
     * @param nonce          the nonce echoed from the authorize request, or null
     * @param includeRefresh whether to include a refresh_token (authorization_code + refresh_token grants)
     * @return the serialized token-endpoint response
     */
    /**
     * Mints a token response without a request to derive the issuer from.
     *
     * <p>Prefer {@link #mintTokenResponse(String, String, boolean, String)} whenever a request is
     * available. With no request, {@link OidcIssuerResolver} can only fall back to the configured
     * issuer or, when none is configured, to its hardcoded default — which is precisely the value the
     * per-request derivation exists to avoid. This overload is therefore only appropriate off the
     * request path (e.g. a test minting a token directly).
     */
    public String mintTokenResponse(String requestedScope, String nonce, boolean includeRefresh) {
        return mintTokenResponse(requestedScope, nonce, includeRefresh,
            OidcIssuerResolver.resolve(config, null));
    }

    /**
     * Mints a token-endpoint response using an explicitly resolved issuer.
     *
     * <p>The issuer is passed in rather than read from configuration because it is resolved per
     * request from the {@code Host} header (see {@link OidcIssuerResolver}) — the {@code iss} claim in
     * a minted token must match the issuer advertised by the discovery document, or every conformant
     * relying party rejects the token.
     *
     * @param issuer the issuer resolved for this request
     */
    public String mintTokenResponse(String requestedScope, String nonce, boolean includeRefresh, String issuer) {
        String scopeString = (requestedScope != null && !requestedScope.trim().isEmpty())
            ? requestedScope.trim()
            : String.join(" ", config.getScopes());
        List<String> scopeList = Arrays.asList(scopeString.split("\\s+"));
        boolean openIdRequested = scopeList.contains("openid");

        Instant now = Instant.now();
        long iat = now.getEpochSecond();
        long exp = config.isIssueExpiredToken()
            ? now.minusSeconds(3600).getEpochSecond()
            : now.plusSeconds(config.getTokenExpirySeconds()).getEpochSecond();

        String effectiveIssuer = config.isWrongIssuer() ? issuer + "/wrong" : issuer;

        Map<String, Serializable> accessTokenClaims = buildAccessTokenClaims(effectiveIssuer, scopeString, iat, exp);

        String accessToken;
        if (config.isOpaqueAccessToken()) {
            // Opaque access token: a random reference (not a JWT). Real IdPs frequently issue opaque
            // tokens whose only validation path is introspection — store the token + its claims so the
            // /introspect endpoint can resolve them (RFC 7662).
            accessToken = "mock-opaque-" + java.util.UUID.randomUUID();
            Map<String, Object> introspectionClaims = new LinkedHashMap<String, Object>(accessTokenClaims);
            OidcAuthorizationStore.getInstance().putOpaqueToken(
                accessToken, new OidcAuthorizationStore.OpaqueToken(introspectionClaims, exp));
        } else {
            accessToken = sign(accessTokenClaims);
        }

        String idToken = null;
        if (openIdRequested) {
            // at_hash is computed over the access_token as issued (opaque or JWT).
            idToken = sign(buildIdTokenClaims(effectiveIssuer, scopeList, nonce, accessToken, iat, exp));
        }

        if (config.isTamperedSignature()) {
            // Opaque access tokens have no signature to tamper; only the (JWT) id_token is corrupted.
            if (!config.isOpaqueAccessToken()) {
                accessToken = tamperSignature(accessToken);
            }
            if (idToken != null) {
                idToken = tamperSignature(idToken);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken);
        if (idToken != null) {
            response.put("id_token", idToken);
        }
        response.put("token_type", "Bearer");
        response.put("expires_in", config.getTokenExpirySeconds());
        response.put("scope", scopeString);
        if (includeRefresh) {
            response.put("refresh_token", "mock-refresh-" + java.util.UUID.randomUUID());
        }
        return serializeToJson(response);
    }

    private Map<String, Serializable> buildAccessTokenClaims(String issuer, String scopeString, long iat, long exp) {
        Map<String, Serializable> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", config.getSubject());
        claims.put("aud", config.getAudience());
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", exp);
        claims.put("scope", scopeString);
        claims.put("client_id", config.getClientId());
        if (config.getAdditionalClaims() != null) {
            claims.putAll(config.getAdditionalClaims());
        }
        return claims;
    }

    private Map<String, Serializable> buildIdTokenClaims(String issuer, List<String> scopeList, String nonce,
                                                         String accessToken, long iat, long exp) {
        Map<String, Serializable> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", config.getSubject());
        claims.put("aud", config.getClientId());
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", exp);
        if (nonce != null && !nonce.isEmpty()) {
            claims.put("nonce", nonce);
        }
        String atHash = computeAtHash(accessToken, jwsAlgorithm);
        if (atHash != null) {
            claims.put("at_hash", atHash);
        }
        // Standard profile/email claims for the requested scopes, sourced from additionalClaims when present.
        Map<String, Serializable> additional = config.getAdditionalClaims();
        if (scopeList.contains("profile") && additional != null) {
            copyIfPresent(additional, claims, "name", "given_name", "family_name", "preferred_username", "picture");
        }
        if (scopeList.contains("email") && additional != null) {
            copyIfPresent(additional, claims, "email", "email_verified");
        }
        if (additional != null) {
            claims.putAll(additional);
        }
        return claims;
    }

    private static void copyIfPresent(Map<String, Serializable> source, Map<String, Serializable> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                target.put(key, source.get(key));
            }
        }
    }

    /**
     * Computes the OIDC {@code at_hash} (per OIDC Core 3.1.3.6): base64url (no padding) of the
     * left-most half of the digest of the ASCII access token, where the digest is the hash used by
     * the {@code id_token}'s JWS algorithm — {@code *256 -> SHA-256}, {@code *384 -> SHA-384},
     * {@code *512 -> SHA-512}. Using a fixed SHA-256 would produce an invalid {@code at_hash} for
     * ES384/RS384/ES512/RS512 providers.
     */
    private static String computeAtHash(String accessToken, String jwsAlgorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(digestForJwsAlgorithm(jwsAlgorithm));
            byte[] hash = digest.digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            byte[] leftHalf = Arrays.copyOf(hash, hash.length / 2);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(leftHalf);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Selects the MessageDigest algorithm matching the JWS signing algorithm's hash strength: the
     * trailing {@code 256}/{@code 384}/{@code 512} of {@code RS*}/{@code ES*}/{@code PS*}. Defaults to
     * SHA-256 for any unrecognised algorithm.
     */
    static String digestForJwsAlgorithm(String jwsAlgorithm) {
        if (jwsAlgorithm != null) {
            if (jwsAlgorithm.endsWith("384")) {
                return "SHA-384";
            }
            if (jwsAlgorithm.endsWith("512")) {
                return "SHA-512";
            }
        }
        return "SHA-256";
    }

    private String sign(Map<String, Serializable> claims) {
        return jwtGenerator.signJWT(claims);
    }

    /**
     * Tampers with the signature segment of a JWT (the third dot-separated part) by replacing the
     * first character, causing signature verification to fail.
     */
    static String tamperSignature(String jwt) {
        int lastDot = jwt.lastIndexOf('.');
        if (lastDot < 0) {
            return jwt;
        }
        String signature = jwt.substring(lastDot + 1);
        if (signature.isEmpty()) {
            return jwt;
        }
        char first = signature.charAt(0);
        char replacement = (first == 'A') ? 'B' : 'A';
        return jwt.substring(0, lastDot + 1) + replacement + signature.substring(1);
    }

    private String serializeToJson(Object value) {
        try {
            return objectWriter.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OIDC token response to JSON", e);
        }
    }
}
