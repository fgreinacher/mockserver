package org.mockserver.authentication;

import org.mockserver.authentication.jwt.JWTAuthenticationHandler;
import org.mockserver.authentication.mtls.MTLSAuthenticationHandler;
import org.mockserver.authentication.oidc.OidcAuthenticationHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the control-plane {@link AuthenticationHandler} chain from a {@link Configuration}.
 *
 * <p>This exists so the handler chain has exactly ONE construction site that is a pure function of the
 * live configuration. Previously the chain was built once during server bootstrap and pushed into
 * {@link org.mockserver.mock.HttpState}; enabling control-plane authentication afterwards (via a system
 * property, a {@link Configuration} mutation, or {@code PUT /mockserver/configuration}) returned success
 * but left the handler {@code null}, and a {@code null} handler means "authenticated" — so the control
 * plane reported itself locked while remaining fully open. Deriving the handler from the configuration on
 * demand, keyed by {@link #signature(Configuration)}, makes every configuration route take effect.
 *
 * <p><strong>Fail closed.</strong> {@link #build} returns {@code null} only when no control-plane
 * authentication is required. If authentication IS required but the handler cannot be constructed (bad
 * JWKS source, unreadable CA chain, ...) it returns a handler that rejects every request rather than
 * {@code null}, so a misconfiguration can never degrade into an open control plane.
 */
public class ControlPlaneAuthenticationHandlerFactory {

    private ControlPlaneAuthenticationHandlerFactory() {
    }

    /**
     * @return {@code true} if any control-plane authentication mechanism is enabled
     */
    public static boolean authenticationRequired(Configuration configuration) {
        return Boolean.TRUE.equals(configuration.controlPlaneTLSMutualAuthenticationRequired())
            || Boolean.TRUE.equals(configuration.controlPlaneJWTAuthenticationRequired())
            || Boolean.TRUE.equals(configuration.controlPlaneOidcAuthenticationRequired());
    }

    /**
     * A stable string capturing every configuration value that feeds handler construction. When this
     * changes the cached handler must be discarded and rebuilt, which is how a runtime reconfiguration
     * (system property, {@code Configuration} setter, DTO, or {@code PUT /mockserver/configuration})
     * reaches the enforcement point. Sorted collections keep the value order-independent so a
     * semantically identical configuration does not force a pointless rebuild.
     */
    public static String signature(Configuration configuration) {
        StringBuilder signature = new StringBuilder();
        signature
            .append(configuration.controlPlaneTLSMutualAuthenticationRequired()).append('|')
            .append(configuration.controlPlaneTLSMutualAuthenticationCAChain()).append('|')
            .append(configuration.controlPlaneJWTAuthenticationRequired()).append('|')
            .append(configuration.controlPlaneJWTAuthenticationJWKSource()).append('|')
            .append(configuration.controlPlaneJWTAuthenticationExpectedAudience()).append('|')
            .append(sorted(configuration.controlPlaneJWTAuthenticationMatchingClaims())).append('|')
            .append(sorted(configuration.controlPlaneJWTAuthenticationRequiredClaims())).append('|')
            .append(configuration.controlPlaneOidcAuthenticationRequired()).append('|')
            .append(configuration.controlPlaneOidcJwksUri()).append('|')
            .append(configuration.controlPlaneOidcIssuer()).append('|')
            .append(configuration.controlPlaneOidcAudience()).append('|')
            .append(configuration.controlPlaneOidcScopeClaim()).append('|')
            .append(sorted(configuration.controlPlaneOidcRequiredScopes()));
        return signature.toString();
    }

    private static String sorted(Map<String, String> map) {
        return map == null ? "null" : new TreeMap<>(map).toString();
    }

    private static String sorted(Set<String> set) {
        return set == null ? "null" : new TreeSet<>(set).toString();
    }

    /**
     * Build the control-plane authentication handler for the supplied configuration.
     *
     * @return {@code null} when no control-plane authentication is required; otherwise a handler that
     * enforces every enabled mechanism (chained when more than one is enabled), or a deny-all handler if
     * construction failed
     */
    public static AuthenticationHandler build(Configuration configuration, MockServerLogger mockServerLogger) {
        if (!authenticationRequired(configuration)) {
            return null;
        }
        try {
            List<AuthenticationHandler> handlers = new ArrayList<>();
            if (Boolean.TRUE.equals(configuration.controlPlaneTLSMutualAuthenticationRequired())) {
                handlers.add(new MTLSAuthenticationHandler(
                    mockServerLogger,
                    new NettySslContextFactory(configuration, mockServerLogger, true)
                        .trustCertificateChain(configuration.controlPlaneTLSMutualAuthenticationCAChain())
                ));
            }
            if (Boolean.TRUE.equals(configuration.controlPlaneJWTAuthenticationRequired())) {
                handlers.add(new JWTAuthenticationHandler(mockServerLogger, configuration.controlPlaneJWTAuthenticationJWKSource())
                    .withExpectedAudience(configuration.controlPlaneJWTAuthenticationExpectedAudience())
                    .withMatchingClaims(configuration.controlPlaneJWTAuthenticationMatchingClaims())
                    .withRequiredClaims(configuration.controlPlaneJWTAuthenticationRequiredClaims()));
            }
            if (Boolean.TRUE.equals(configuration.controlPlaneOidcAuthenticationRequired())) {
                handlers.add(new OidcAuthenticationHandler(
                    mockServerLogger,
                    configuration.controlPlaneOidcJwksUri(),
                    configuration.controlPlaneOidcIssuer(),
                    configuration.controlPlaneOidcAudience(),
                    configuration.controlPlaneOidcScopeClaim(),
                    configuration.controlPlaneOidcRequiredScopes()
                ));
            }
            if (handlers.size() == 1) {
                return handlers.get(0);
            }
            return new ChainedAuthenticationHandler(handlers.toArray(new AuthenticationHandler[0]));
        } catch (Throwable throwable) {
            // Authentication IS required but could not be constructed. Returning null here would map to
            // "authenticated" at the enforcement point and silently open the control plane, so deny
            // everything instead and make the misconfiguration loud.
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("control plane authentication is enabled but the authentication handler could not be created - denying all control plane requests until the configuration is corrected")
                        .setThrowable(throwable)
                );
            }
            return new DenyAllAuthenticationHandler();
        }
    }

    /**
     * Rejects every control-plane request. Used when authentication is required but the configured
     * mechanism could not be constructed, so the failure mode is closed rather than open.
     */
    public static class DenyAllAuthenticationHandler implements AuthenticationHandler {

        @Override
        public boolean controlPlaneRequestAuthenticated(org.mockserver.model.HttpRequest request) {
            throw new AuthenticationException("control plane authentication is enabled but incorrectly configured", true);
        }
    }
}
