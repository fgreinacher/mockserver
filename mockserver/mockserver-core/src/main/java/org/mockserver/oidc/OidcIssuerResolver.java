package org.mockserver.oidc;

import org.mockserver.model.HttpRequest;

/**
 * Resolves the OIDC {@code issuer} for a request.
 *
 * <p>OIDC Discovery §4.3 requires that the {@code issuer} in the discovery document be
 * <em>identical</em> to the URL the relying party used to fetch it — every conformant client library
 * (Spring Security, nimbus, pac4j, `oidc-client`, MSAL) validates this and refuses to proceed when it
 * does not match.
 *
 * <p>The issuer therefore cannot be a fixed string. It was previously hardcoded to
 * {@code http://localhost:1080}, which broke the single most common way people run a mock OIDC
 * provider: a Testcontainers-mapped random port. The relying party fetches discovery from
 * {@code http://localhost:49173}, is told the issuer is {@code http://localhost:1080}, and fails.
 *
 * <p>So the issuer is derived per request from the {@code Host} header, unless
 * {@link OidcProviderConfiguration#getIssuer()} was explicitly configured — an explicit value always
 * wins, so a test that needs a stable, externally-meaningful issuer can still pin one.
 */
public final class OidcIssuerResolver {

    /** Used only when a request carries no usable {@code Host} header (e.g. a malformed HTTP/1.0 request). */
    static final String FALLBACK_ISSUER = "http://localhost:1080";

    private OidcIssuerResolver() {
    }

    /**
     * Resolves the issuer to advertise for this request.
     *
     * @param config  the provider configuration; an explicit {@code issuer} wins
     * @param request the inbound request, used to derive scheme + authority when no issuer is configured
     * @return the issuer, never null and never with a trailing slash
     */
    public static String resolve(OidcProviderConfiguration config, HttpRequest request) {
        if (config != null && isNotBlank(config.getIssuer())) {
            return stripTrailingSlash(config.getIssuer().trim());
        }
        return deriveFromRequest(request);
    }

    private static String deriveFromRequest(HttpRequest request) {
        if (request == null) {
            return FALLBACK_ISSUER;
        }
        String host = firstHeaderValue(request, "host");
        if (!isNotBlank(host)) {
            return FALLBACK_ISSUER;
        }
        return scheme(request) + "://" + stripTrailingSlash(host.trim());
    }

    /**
     * Honours a forwarding proxy's {@code X-Forwarded-Proto} before falling back to whether the
     * request itself arrived over TLS, so a provider behind a TLS-terminating ingress advertises the
     * https issuer its clients actually used.
     */
    private static String scheme(HttpRequest request) {
        String forwardedProto = firstHeaderValue(request, "x-forwarded-proto");
        if (isNotBlank(forwardedProto)) {
            // May be a comma-separated list when several proxies appended to it; the first is the
            // scheme the original client used.
            String first = forwardedProto.split(",")[0].trim().toLowerCase();
            // Allow-list, not pass-through: this header is client-controlled and the value is spliced
            // into the issuer URL, which a browser-based relying party may navigate to. Echoing it
            // verbatim would let `X-Forwarded-Proto: javascript` yield a `javascript:` issuer. Only the
            // two schemes an OIDC issuer can legitimately use are accepted; anything else falls back to
            // how the request actually arrived.
            if ("https".equals(first) || "http".equals(first)) {
                return first;
            }
        }
        return Boolean.TRUE.equals(request.isSecure()) ? "https" : "http";
    }

    private static String firstHeaderValue(HttpRequest request, String name) {
        String value = request.getFirstHeader(name);
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
