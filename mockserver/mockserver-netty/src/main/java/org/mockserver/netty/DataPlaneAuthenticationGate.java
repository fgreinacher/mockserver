package org.mockserver.netty;

import org.mockserver.authentication.dataplane.DataPlaneAuthenticator;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.responsewriter.ResponseWriter;
import org.slf4j.event.Level;

import static io.netty.handler.codec.http.HttpHeaderNames.WWW_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.AUTHENTICATION_FAILED;
import static org.mockserver.mock.HttpState.PATH_PREFIX;
import static org.mockserver.model.HttpResponse.response;

/**
 * Shared data-plane (mocked endpoint) authentication gate used by every Netty data-plane dispatch
 * path — {@link HttpRequestHandler} (HTTP/1.1, HTTP/2, gRPC-over-h2) and
 * {@code Http3MockServerHandler} (HTTP/3 / QUIC, including gRPC-over-HTTP/3). Keeping the gate in one
 * place ensures the policy and the 401 response are byte-identical across transports and that a new
 * data-plane entry point cannot accidentally skip the check (the HTTP/3 path previously did,
 * fail-OPEN).
 *
 * <p>The decision itself lives in core ({@link DataPlaneAuthenticator}: opt-in, default off,
 * fail-closed when required-but-unconfigured, constant-time secret compare). This helper only
 * invokes it and — on failure — writes the {@code 401} with the correct {@code WWW-Authenticate}
 * challenge through the transport's {@link ResponseWriter} and records the audit log entry.
 */
public final class DataPlaneAuthenticationGate {

    private DataPlaneAuthenticationGate() {
    }

    /**
     * Apply the data-plane authentication gate. Call this immediately before dispatching a data-plane
     * request to {@code httpActionHandler.processAction(...)} on every transport.
     *
     * <p>Cheap default-off path: when {@code dataPlaneAuthenticationRequired} is false this is a single
     * boolean read and returns {@code true} with no allocation, so behaviour is byte-identical to a
     * server without the feature.
     *
     * @return {@code true} if the request is authenticated (or the gate is disabled) and the caller
     * should proceed to {@code processAction}; {@code false} if a {@code 401} has already been written
     * via {@code responseWriter} and the caller must NOT proceed.
     */
    public static boolean isAuthenticated(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        HttpRequest request,
        ResponseWriter responseWriter
    ) {
        if (!configuration.dataPlaneAuthenticationRequired()) {
            return true;
        }
        // NEVER gate control-plane (/mockserver/*) or the configured liveness probe path. On the
        // HTTP/1.1/HTTP/2 path these are already matched in earlier branches before the gate, but the
        // HTTP/3 handler routes some control-plane routes (/mockserver/status, /ready, /bind, /stop,
        // /configuration, dashboard, openapi, metrics) into this same data-plane fall-through (they are
        // serviced by HttpRequestHandler's else-if branches, NOT by httpState.handle). Exempting them
        // here keeps the control plane and health/readiness probes reachable WITHOUT data-plane
        // credentials on every transport, so an operator can still administer a locked-down server.
        if (isControlPlaneOrProbe(configuration, request)) {
            return true;
        }
        DataPlaneAuthenticator.Outcome outcome = new DataPlaneAuthenticator(configuration).authenticate(request);
        if (outcome.isAuthenticated()) {
            return true;
        }
        HttpResponse unauthorizedResponse = response()
            .withStatusCode(UNAUTHORIZED.code())
            .withReasonPhrase(UNAUTHORIZED.reasonPhrase());
        if (isNotBlank(outcome.wwwAuthenticate())) {
            unauthorizedResponse.withHeader(WWW_AUTHENTICATE.toString(), outcome.wwwAuthenticate());
        }
        // Do NOT echo any credential or the configured scheme detail in the body.
        unauthorizedResponse.withBody("Unauthorized for data plane", MediaType.create("text", "plain"));
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(AUTHENTICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setHttpResponse(unauthorizedResponse)
                    .setMessageFormat("data plane authentication failed so returning response:{}for request:{}")
                    .setArguments(unauthorizedResponse, request)
            );
        }
        responseWriter.writeResponse(request, unauthorizedResponse, false);
        return false;
    }

    /**
     * @return {@code true} if the request targets the control plane ({@code /mockserver/*}) or the
     * configured liveness HTTP-GET probe path — neither of which is ever gated by data-plane auth.
     */
    private static boolean isControlPlaneOrProbe(Configuration configuration, HttpRequest request) {
        if (request.getPath() == null) {
            return false;
        }
        String path = request.getPath().getValue();
        if (path == null) {
            return false;
        }
        if (path.startsWith(PATH_PREFIX)) {
            return true;
        }
        String livenessPath = configuration.livenessHttpGetPath();
        return isNotBlank(livenessPath) && path.equals(livenessPath);
    }
}
