package org.mockserver.oidc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the OIDC mock identity provider. All fields are optional with
 * sensible defaults so that {@code PUT /mockserver/oidc} with an empty body produces
 * a fully functional OIDC discovery document, JWKS endpoint, token endpoint, userinfo
 * endpoint, introspection endpoint, and revocation endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OidcProviderConfiguration implements Serializable {

    // Null by default, meaning "derive the issuer per request from the Host header"
    // (see OidcIssuerResolver). OIDC Discovery §4.3 requires the advertised issuer to be identical to
    // the URL the relying party used, so a hardcoded default broke every client running the mock on a
    // random port (Testcontainers) — Spring Security, nimbus and pac4j all reject the mismatch. An
    // explicitly configured issuer still wins.
    private String issuer = null;
    private String jwksPath = "/.well-known/jwks.json";
    private String tokenPath = "/token";
    private String authorizePath = "/authorize";
    private String userinfoPath = "/userinfo";
    private String introspectPath = "/introspect";
    private String revokePath = "/revoke";
    private String deviceAuthorizationPath = "/device_authorization";

    private String subject = "mock-user";
    private String clientId = "mock-client";
    // Security: the client secret must NEVER be serialized back out (credential leak via JSON /
    // discovery / response). WRITE_ONLY lets an inbound PUT body supply it while excluding it from
    // serialization; the typed client re-injects it on the outbound PUT so a supplied value still
    // reaches the server.
    @JsonProperty(value = "clientSecret", access = JsonProperty.Access.WRITE_ONLY)
    private String clientSecret = "mock-client-secret";
    private String audience = "mock-audience";
    private List<String> scopes = Arrays.asList("openid", "profile", "email");
    private int tokenExpirySeconds = 3600;
    private Map<String, Serializable> additionalClaims = new LinkedHashMap<>();

    // Device authorization grant (RFC 8628). Number of /token polls that return
    // authorization_pending before the device code is approved and tokens are minted.
    // 0 (default) approves immediately on the first poll.
    private int deviceCodePendingPolls = 0;

    // Token-endpoint client authentication (RFC 6749 §2.3 / §3.2.1). When enforced, the /token
    // endpoint validates client_secret_basic and client_secret_post against clientId/clientSecret.
    private boolean enforceClientAuthentication = false;

    // When true the access_token is an opaque random string (not a JWT). The id_token stays a
    // signed JWT. The opaque token + its claims are stored so /introspect can validate it.
    private boolean opaqueAccessToken = false;

    // Signing configuration (optional). When no key material is supplied a fresh key pair is
    // generated per generate() using signingAlgorithm (default RS256 / RSA-2048).
    private String signingAlgorithm = "RS256";
    // Security: the signing private key must NEVER be serialized back out (key leak via JSON /
    // JWKS / response). WRITE_ONLY lets an inbound PUT body supply it while excluding it from
    // serialization; the typed client re-injects it on the outbound PUT so a supplied value still
    // reaches the server.
    @JsonProperty(value = "privateKeyPem", access = JsonProperty.Access.WRITE_ONLY)
    private String privateKeyPem;
    private String certificatePem;
    private String jwkJson;
    private String keyId;

    // Negative-testing flags
    private boolean issueExpiredToken = false;
    private boolean wrongIssuer = false;
    private boolean tamperedSignature = false;

    public OidcProviderConfiguration() {
    }

    @JsonProperty("issuer")
    public String getIssuer() {
        return issuer;
    }

    public OidcProviderConfiguration setIssuer(String issuer) {
        this.issuer = issuer;
        return this;
    }

    @JsonProperty("jwksPath")
    public String getJwksPath() {
        return jwksPath;
    }

    public OidcProviderConfiguration setJwksPath(String jwksPath) {
        this.jwksPath = jwksPath;
        return this;
    }

    @JsonProperty("tokenPath")
    public String getTokenPath() {
        return tokenPath;
    }

    public OidcProviderConfiguration setTokenPath(String tokenPath) {
        this.tokenPath = tokenPath;
        return this;
    }

    @JsonProperty("authorizePath")
    public String getAuthorizePath() {
        return authorizePath;
    }

    public OidcProviderConfiguration setAuthorizePath(String authorizePath) {
        this.authorizePath = authorizePath;
        return this;
    }

    @JsonProperty("userinfoPath")
    public String getUserinfoPath() {
        return userinfoPath;
    }

    public OidcProviderConfiguration setUserinfoPath(String userinfoPath) {
        this.userinfoPath = userinfoPath;
        return this;
    }

    @JsonProperty("introspectPath")
    public String getIntrospectPath() {
        return introspectPath;
    }

    public OidcProviderConfiguration setIntrospectPath(String introspectPath) {
        this.introspectPath = introspectPath;
        return this;
    }

    @JsonProperty("revokePath")
    public String getRevokePath() {
        return revokePath;
    }

    public OidcProviderConfiguration setRevokePath(String revokePath) {
        this.revokePath = revokePath;
        return this;
    }

    @JsonProperty("deviceAuthorizationPath")
    public String getDeviceAuthorizationPath() {
        return deviceAuthorizationPath;
    }

    public OidcProviderConfiguration setDeviceAuthorizationPath(String deviceAuthorizationPath) {
        this.deviceAuthorizationPath = deviceAuthorizationPath;
        return this;
    }

    @JsonProperty("subject")
    public String getSubject() {
        return subject;
    }

    public OidcProviderConfiguration setSubject(String subject) {
        this.subject = subject;
        return this;
    }

    @JsonProperty("clientId")
    public String getClientId() {
        return clientId;
    }

    public OidcProviderConfiguration setClientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    @JsonIgnore
    public String getClientSecret() {
        return clientSecret;
    }

    public OidcProviderConfiguration setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
        return this;
    }

    @JsonProperty("audience")
    public String getAudience() {
        return audience;
    }

    public OidcProviderConfiguration setAudience(String audience) {
        this.audience = audience;
        return this;
    }

    @JsonProperty("scopes")
    public List<String> getScopes() {
        return scopes;
    }

    public OidcProviderConfiguration setScopes(List<String> scopes) {
        this.scopes = scopes;
        return this;
    }

    @JsonProperty("tokenExpirySeconds")
    public int getTokenExpirySeconds() {
        return tokenExpirySeconds;
    }

    public OidcProviderConfiguration setTokenExpirySeconds(int tokenExpirySeconds) {
        this.tokenExpirySeconds = tokenExpirySeconds;
        return this;
    }

    @JsonProperty("additionalClaims")
    public Map<String, Serializable> getAdditionalClaims() {
        return additionalClaims;
    }

    public OidcProviderConfiguration setAdditionalClaims(Map<String, Serializable> additionalClaims) {
        this.additionalClaims = additionalClaims;
        return this;
    }

    @JsonProperty("signingAlgorithm")
    public String getSigningAlgorithm() {
        return signingAlgorithm;
    }

    public OidcProviderConfiguration setSigningAlgorithm(String signingAlgorithm) {
        this.signingAlgorithm = signingAlgorithm;
        return this;
    }

    @JsonIgnore
    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public OidcProviderConfiguration setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
        return this;
    }

    @JsonProperty("certificatePem")
    public String getCertificatePem() {
        return certificatePem;
    }

    public OidcProviderConfiguration setCertificatePem(String certificatePem) {
        this.certificatePem = certificatePem;
        return this;
    }

    @JsonProperty("jwkJson")
    public String getJwkJson() {
        return jwkJson;
    }

    public OidcProviderConfiguration setJwkJson(String jwkJson) {
        this.jwkJson = jwkJson;
        return this;
    }

    @JsonProperty("keyId")
    public String getKeyId() {
        return keyId;
    }

    public OidcProviderConfiguration setKeyId(String keyId) {
        this.keyId = keyId;
        return this;
    }

    @JsonProperty("deviceCodePendingPolls")
    public int getDeviceCodePendingPolls() {
        return deviceCodePendingPolls;
    }

    public OidcProviderConfiguration setDeviceCodePendingPolls(int deviceCodePendingPolls) {
        this.deviceCodePendingPolls = deviceCodePendingPolls;
        return this;
    }

    @JsonProperty("enforceClientAuthentication")
    public boolean isEnforceClientAuthentication() {
        return enforceClientAuthentication;
    }

    public OidcProviderConfiguration setEnforceClientAuthentication(boolean enforceClientAuthentication) {
        this.enforceClientAuthentication = enforceClientAuthentication;
        return this;
    }

    @JsonProperty("opaqueAccessToken")
    public boolean isOpaqueAccessToken() {
        return opaqueAccessToken;
    }

    public OidcProviderConfiguration setOpaqueAccessToken(boolean opaqueAccessToken) {
        this.opaqueAccessToken = opaqueAccessToken;
        return this;
    }

    @JsonProperty("issueExpiredToken")
    public boolean isIssueExpiredToken() {
        return issueExpiredToken;
    }

    public OidcProviderConfiguration setIssueExpiredToken(boolean issueExpiredToken) {
        this.issueExpiredToken = issueExpiredToken;
        return this;
    }

    @JsonProperty("wrongIssuer")
    public boolean isWrongIssuer() {
        return wrongIssuer;
    }

    public OidcProviderConfiguration setWrongIssuer(boolean wrongIssuer) {
        this.wrongIssuer = wrongIssuer;
        return this;
    }

    @JsonProperty("tamperedSignature")
    public boolean isTamperedSignature() {
        return tamperedSignature;
    }

    public OidcProviderConfiguration setTamperedSignature(boolean tamperedSignature) {
        this.tamperedSignature = tamperedSignature;
        return this;
    }
}
