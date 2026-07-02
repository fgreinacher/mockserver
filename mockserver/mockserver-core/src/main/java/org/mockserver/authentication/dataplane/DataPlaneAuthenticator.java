package org.mockserver.authentication.dataplane;

import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Opt-in, fail-closed authentication gate for the <strong>data plane</strong> (the mocked
 * endpoints), as opposed to the control plane ({@code /mockserver/*}) which is gated separately
 * by {@code HttpState.controlPlaneRequestAuthenticated}.
 *
 * <p>This helper holds the policy/decision so it can be unit tested in core; the Netty handler
 * merely invokes {@link #authenticate(HttpRequest)} and writes the 401/403 the result describes.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><strong>Default OFF.</strong> When {@code dataPlaneAuthenticationRequired} is {@code false}
 *       (the default) {@link #isEnabled()} is {@code false} and {@link #authenticate(HttpRequest)}
 *       always returns {@link Outcome#authenticated()} — behaviour is byte-identical to a server
 *       with no data-plane auth.</li>
 *   <li><strong>Multi-scheme = accept-any.</strong> When more than one scheme (Basic / Bearer /
 *       API-key) is configured a request is accepted if it satisfies <em>any</em> one of them
 *       (logical OR). This keeps configuration predictable: adding a scheme can only ever widen
 *       the set of accepted credentials, never narrow it. The {@code WWW-Authenticate} challenge
 *       on a 401 advertises the configured Basic/Bearer schemes.</li>
 *   <li><strong>Fail-closed when required-but-unconfigured.</strong> If
 *       {@code dataPlaneAuthenticationRequired} is {@code true} but no scheme is configured (no
 *       Basic username/password, no Bearer token, no API-key value) then {@link #isEnabled()} is
 *       still {@code true} and every request is rejected (401, generic challenge). A
 *       misconfiguration can therefore never silently allow all traffic.</li>
 *   <li><strong>Constant-time secret comparison.</strong> Password, Bearer token and API-key value
 *       comparisons use {@link MessageDigest#isEqual(byte[], byte[])} on UTF-8 bytes, which is
 *       documented to run in constant time, to avoid a timing side-channel. Credential values are
 *       never logged or echoed in a response body.</li>
 * </ul>
 */
public class DataPlaneAuthenticator {

    private static final String AUTHORIZATION = "Authorization";

    private final boolean required;
    private final String basicRealm;
    private final String bearerToken;
    private final String apiKeyHeader;
    private final String apiKeyValue;

    private final boolean basicConfigured;
    private final boolean bearerConfigured;
    private final boolean apiKeyConfigured;
    private final boolean anySchemeConfigured;

    /**
     * Pre-computed Basic challenge token: the base64 of {@code username:password} that an incoming
     * {@code Authorization: Basic <token>} header must equal. Only used (and only non-null) when
     * Basic is configured.
     */
    private final byte[] expectedBasicToken;

    public DataPlaneAuthenticator(Configuration configuration) {
        this.required = configuration.dataPlaneAuthenticationRequired();
        String basicUsername = configuration.dataPlaneBasicAuthenticationUsername();
        String basicPassword = configuration.dataPlaneBasicAuthenticationPassword();
        String realm = configuration.dataPlaneBasicAuthenticationRealm();
        this.basicRealm = isBlank(realm) ? "MockServer" : realm;
        this.bearerToken = configuration.dataPlaneBearerAuthenticationToken();
        this.apiKeyHeader = configuration.dataPlaneApiKeyAuthenticationHeader();
        this.apiKeyValue = configuration.dataPlaneApiKeyAuthenticationValue();

        // Basic requires BOTH username and password to be set; a half-configured Basic scheme is
        // treated as not-configured (it would otherwise be impossible to ever satisfy).
        this.basicConfigured = isNotBlank(basicUsername) && isNotBlank(basicPassword);
        this.bearerConfigured = isNotBlank(bearerToken);
        // API-key requires BOTH a header name and a value.
        this.apiKeyConfigured = isNotBlank(apiKeyHeader) && isNotBlank(apiKeyValue);
        this.anySchemeConfigured = basicConfigured || bearerConfigured || apiKeyConfigured;

        if (basicConfigured) {
            String userPass = basicUsername + ':' + basicPassword;
            this.expectedBasicToken = Base64.getEncoder()
                .encodeToString(userPass.getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        } else {
            this.expectedBasicToken = null;
        }
    }

    /**
     * @return {@code true} when the gate is active (i.e. {@code dataPlaneAuthenticationRequired} is
     * {@code true}). When {@code false} the Netty handler can skip the gate entirely and there is no
     * change to request handling.
     */
    public boolean isEnabled() {
        return required;
    }

    /**
     * Decide whether the given data-plane request is authenticated.
     *
     * <p>When the gate is disabled this is always {@link Outcome#authenticated()}. When enabled but
     * no scheme is configured the request is always rejected (fail-closed). Otherwise the request is
     * accepted if it satisfies any one configured scheme.
     */
    public Outcome authenticate(HttpRequest request) {
        if (!required) {
            return Outcome.authenticated();
        }
        if (!anySchemeConfigured) {
            // required-but-unconfigured → fail closed.
            return reject();
        }
        if (basicConfigured && basicMatches(request)) {
            return Outcome.authenticated();
        }
        if (bearerConfigured && bearerMatches(request)) {
            return Outcome.authenticated();
        }
        if (apiKeyConfigured && apiKeyMatches(request)) {
            return Outcome.authenticated();
        }
        return reject();
    }

    private boolean basicMatches(HttpRequest request) {
        String header = request.getFirstHeader(AUTHORIZATION);
        if (isBlank(header)) {
            return false;
        }
        // Scheme name is case-insensitive per RFC 7617.
        if (header.length() < 6 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String token = header.substring(6).trim();
        if (token.isEmpty()) {
            return false;
        }
        return constantTimeEquals(token.getBytes(StandardCharsets.UTF_8), expectedBasicToken);
    }

    private boolean bearerMatches(HttpRequest request) {
        String header = request.getFirstHeader(AUTHORIZATION);
        if (isBlank(header)) {
            return false;
        }
        if (header.length() < 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            return false;
        }
        return constantTimeEquals(token.getBytes(StandardCharsets.UTF_8),
            bearerToken.getBytes(StandardCharsets.UTF_8));
    }

    private boolean apiKeyMatches(HttpRequest request) {
        String value = request.getFirstHeader(apiKeyHeader);
        if (isBlank(value)) {
            return false;
        }
        return constantTimeEquals(value.getBytes(StandardCharsets.UTF_8),
            apiKeyValue.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Constant-time byte comparison. Delegates to the shared
     * {@link org.mockserver.authentication.ConstantTimeEquals} so the control-plane proxy
     * authentication and this data-plane authenticator share one audited implementation rather than
     * duplicating the {@link MessageDigest#isEqual(byte[], byte[])} call. {@code isEqual} returns
     * false fast when the lengths differ, which leaks only the length of the supplied credential —
     * never the secret's content.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        return org.mockserver.authentication.ConstantTimeEquals.equals(a, b);
    }

    private Outcome reject() {
        // Advertise the Basic challenge (with realm) when Basic is configured, otherwise Bearer when
        // Bearer is configured. When only API-key is configured there is no standard challenge scheme,
        // so no WWW-Authenticate header is emitted (a 401 with no challenge is valid). The status is
        // always 401 — including for the API-key-only case — because the credential was missing or
        // wrong (authentication), not an authorization decision.
        String challenge = null;
        if (basicConfigured) {
            // Escape any embedded double-quote in the realm so the header stays well-formed.
            String safeRealm = basicRealm.replace("\\", "\\\\").replace("\"", "\\\"");
            challenge = "Basic realm=\"" + safeRealm + "\", charset=\"UTF-8\"";
        } else if (bearerConfigured) {
            challenge = "Bearer";
        }
        return new Outcome(false, challenge);
    }

    /**
     * Result of an authentication attempt. {@link #isAuthenticated()} true means proceed; false means
     * the caller must write a 401 with the supplied {@link #wwwAuthenticate()} challenge (which may be
     * null for the API-key-only configuration).
     */
    public static final class Outcome {

        private static final Outcome AUTHENTICATED = new Outcome(true, null);

        private final boolean authenticated;
        private final String wwwAuthenticate;

        private Outcome(boolean authenticated, String wwwAuthenticate) {
            this.authenticated = authenticated;
            this.wwwAuthenticate = wwwAuthenticate;
        }

        static Outcome authenticated() {
            return AUTHENTICATED;
        }

        public boolean isAuthenticated() {
            return authenticated;
        }

        /**
         * @return the {@code WWW-Authenticate} challenge to set on the 401 response, or {@code null}
         * when there is no standard challenge (API-key-only configuration).
         */
        public String wwwAuthenticate() {
            return wwwAuthenticate;
        }
    }
}
