package org.mockserver.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.apache.commons.text.StringEscapeUtils;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardHandler;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.metrics.MetricsHandler;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.PortBinding;
import org.mockserver.netty.proxy.connect.HttpConnectHandler;
import org.mockserver.netty.responsewriter.NettyResponseWriter;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.Base64Converter;
import org.mockserver.serialization.ConfigurationSerializer;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.PortBindingSerializer;
import org.mockserver.serialization.model.ConfigurationDTO;
import org.slf4j.event.Level;

import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.log.model.LogEntry.LogMessageType.AUTHENTICATION_FAILED;
import static org.mockserver.metrics.Metrics.Name.REQUESTS_RECEIVED_COUNT;
import static org.mockserver.mock.HttpState.PATH_PREFIX;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.PortBinding.portBinding;
import static org.mockserver.netty.unification.PortUnificationHandler.enableSslUpstreamAndDownstream;
import static org.mockserver.netty.unification.PortUnificationHandler.isSslEnabledUpstream;

/**
 * @author jamesdbloom
 */
@ChannelHandler.Sharable
@SuppressWarnings("FieldMayBeFinal")
public class HttpRequestHandler extends SimpleChannelInboundHandler<HttpRequest> {

    public static final AttributeKey<Boolean> PROXYING = AttributeKey.valueOf("PROXYING");
    public static final AttributeKey<Set<String>> LOCAL_HOST_HEADERS = AttributeKey.valueOf("LOCAL_HOST_HEADERS");
    private static final Base64Converter BASE_64_CONVERTER = new Base64Converter();
    private final Configuration configuration;
    private LifeCycle server;
    private HttpState httpState;
    private Metrics metrics;
    private MockServerLogger mockServerLogger;
    private PortBindingSerializer portBindingSerializer;
    private HttpActionHandler httpActionHandler;
    private DashboardHandler dashboardHandler;
    private MetricsHandler metricsHandler;
    private OpenAPISpecHandler openAPISpecHandler;
    private ConfigurationSerializer configurationSerializer;

    public HttpRequestHandler(Configuration configuration, LifeCycle server, HttpState httpState, HttpActionHandler httpActionHandler) {
        super(false);
        this.configuration = configuration;
        this.server = server;
        this.httpState = httpState;
        this.metrics = new Metrics(configuration);
        this.mockServerLogger = httpState.getMockServerLogger();
        this.portBindingSerializer = new PortBindingSerializer(mockServerLogger);
        this.httpActionHandler = httpActionHandler;
        this.dashboardHandler = new DashboardHandler();
        this.metricsHandler = new MetricsHandler(configuration);
        this.openAPISpecHandler = new OpenAPISpecHandler();
        this.configurationSerializer = new ConfigurationSerializer(mockServerLogger);
        // Wire the replay handler so PUT /mockserver/replay can re-issue
        // requests using the existing NettyHttpClient (forward/proxy client).
        httpState.setReplayHandler(req -> httpActionHandler.getHttpClient().sendRequest(req));
        // Wire the load-scenario sender similarly so PUT /mockserver/loadScenario can drive
        // traffic using the existing NettyHttpClient, without core depending on it directly.
        org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance()
            .setSender(req -> httpActionHandler.getHttpClient().sendRequest(req));
        org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance()
            .setConfiguration(configuration);
        // Wire the drift-alert webhook sender similarly so DriftAlertNotifier can POST drift alerts
        // using the existing NettyHttpClient, without core depending on it directly. Fire-and-forget;
        // a webhook failure never affects drift analysis or the served response.
        org.mockserver.mock.drift.DriftAlertNotifier.getInstance()
            .setSender(req -> httpActionHandler.getHttpClient().sendRequest(req));
        // Wire the preemption simulator's in-flight count to the live LifeCycle gauge so
        // GET /mockserver/preemption reports the real number of draining requests (not 0).
        org.mockserver.mock.action.http.PreemptionSimulator.getInstance().setInFlightSupplier(server::getRequestsInFlight);
    }

    private static boolean isProxyingRequest(ChannelHandlerContext ctx) {
        if (ctx != null && ctx.channel() != null && ctx.channel().attr(PROXYING).get() != null) {
            return ctx.channel().attr(PROXYING).get();
        }
        return false;
    }

    public static void setProxyingRequest(ChannelHandlerContext ctx, Boolean value) {
        if (ctx != null && ctx.channel() != null) {
            ctx.channel().attr(PROXYING).set(value);
        }
    }

    private static Set<String> getLocalAddresses(ChannelHandlerContext ctx) {
        if (ctx != null &&
            ctx.channel().attr(LOCAL_HOST_HEADERS) != null &&
            ctx.channel().attr(LOCAL_HOST_HEADERS).get() != null) {
            return ctx.channel().attr(LOCAL_HOST_HEADERS).get();
        }
        return new HashSet<>();
    }

    /**
     * Complete the WS7.2 in-flight token for control-plane branches that write their response
     * directly via {@code ctx.writeAndFlush(...)} / a handler render method rather than through
     * {@link NettyResponseWriter}, which is where the token is normally completed. Without this the
     * token only decrements when the channel closes, so on a keep-alive connection the graceful
     * shutdown drain would wait the full {@code stopDrainMillis}. Null-safe and idempotent with the
     * channel {@code closeFuture} safety net (the token's {@link InFlightRequest#complete()} guard).
     */
    private static void completeInFlight(InFlightRequest inFlightRequest) {
        if (inFlightRequest != null) {
            inFlightRequest.complete();
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final HttpRequest request) {

        if (configuration.metricsEnabled()) {
            metrics.increment(REQUESTS_RECEIVED_COUNT);
        }

        // Mark this exchange as in-flight for WS7.2 graceful-shutdown drain. The matching
        // decrement is driven by the InFlightRequest token: NettyResponseWriter.sendResponse(...)
        // completes it on the normal/forward/proxy/error/breakpoint response paths, and a
        // channel-close listener completes it for requests that never produce a response
        // (connection drop or pipeline-killing exception). The token's guard makes the
        // decrement fire exactly once across all of those, so the counter can never leak.
        final InFlightRequest inFlightRequest = InFlightRequest.started(server);
        if (inFlightRequest != null) {
            ctx.channel().closeFuture().addListener(future -> inFlightRequest.complete());
        }

        // L6: connection-lifecycle preemption cordon. When a preemption/SIGTERM simulation is active
        // the server is "cordoned": new data-plane exchanges are turned away while in-flight requests
        // drain. HTTP/1.1 exchanges that the active mode rejects get 503 + Retry-After +
        // Connection: close so a load-balancer routes elsewhere. HTTP/2 clients additionally (or, in
        // goaway-only mode, instead) get a connection-level GOAWAY — the canonical "this connection is
        // going away" drain signal — emitted lazily on this request. The control plane (/mockserver/...)
        // is exempt so the operator can still observe and uncordon. The isCordoned() probe is a single
        // volatile read when no simulation is active, so this adds nothing measurable to the hot path.
        if (configuration.connectionLifecycleChaosEnabled()) {
            String requestPath = request.getPath() != null ? request.getPath().getValue() : null;
            boolean controlPlane = requestPath != null && requestPath.startsWith(PATH_PREFIX);
            org.mockserver.mock.action.http.PreemptionSimulator simulator = org.mockserver.mock.action.http.PreemptionSimulator.getInstance();
            if (!controlPlane && simulator.isCordoned()) {
                // Lazily emit an HTTP/2 GOAWAY when the mode includes goaway and this is an HTTP/2
                // connection. emit(...) returns false on HTTP/1.1 (no Http2ConnectionHandler on the
                // pipeline), so this is also our h2 detection. GOAWAY is a graceful drain signal, NOT a
                // destructive RST, so it is deliberately NOT counted toward the chaos auto-halt breaker.
                boolean emittedGoAway = false;
                if (simulator.emitsGoAway()) {
                    emittedGoAway = org.mockserver.netty.unification.Http2GoAwayEmitter.emit(
                        ctx, simulator.goAwayLastStreamId(), 0L);
                }
                if (simulator.rejectsNewExchanges()) {
                    // HTTP/1.1 (and "both" mode on HTTP/2): turn the new exchange away with a 503.
                    // Like GOAWAY this is a graceful "retry elsewhere" signal, NOT counted toward
                    // auto-halt — only the mid-response RST records a "drop".
                    completeInFlight(inFlightRequest);
                    long retryAfterSeconds = Math.max(1L, (simulator.drainRemainingMillis() + 999L) / 1000L);
                    ctx.writeAndFlush(response()
                        .withStatusCode(SERVICE_UNAVAILABLE.code())
                        .withHeader(CONNECTION.toString(), "close")
                        .withHeader("Retry-After", String.valueOf(retryAfterSeconds))
                        .withBody("{\"error\":\"server is draining (simulated preemption); retry elsewhere\"}", MediaType.JSON_UTF_8)
                    ).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
                    return;
                }
                if (emittedGoAway) {
                    // goaway-only mode on an HTTP/2 connection: the GOAWAY is the drain signal; the
                    // in-flight stream's own response still completes normally, so fall through to
                    // serve this request rather than rejecting it.
                    if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                        mockServerLogger.logEvent(new LogEntry()
                            .setLogLevel(Level.DEBUG)
                            .setMessageFormat("emitted HTTP/2 GOAWAY on cordoned connection (preemption goaway mode)"));
                    }
                }
                // goaway-only on HTTP/1.1 (no GOAWAY possible, reject503 not requested): fall through
                // and serve the request — the simulation cannot signal drain on HTTP/1.1.
            }
        }

        // Ensure the per-server WebSocketClientRegistry is available as a channel attribute
        // so that NettyResponseWriter (and other Netty handlers) can obtain it without
        // holding a process-global singleton reference (COR-06).
        if (ctx.channel().attr(org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.WS_REGISTRY_KEY).get() == null) {
            ctx.channel().attr(org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.WS_REGISTRY_KEY)
                .set(httpState.getWebSocketClientRegistry());
        }
        ResponseWriter responseWriter = new NettyResponseWriter(configuration, mockServerLogger, ctx, httpState.getScheduler(), inFlightRequest);
        try {
            configuration.addSubjectAlternativeName(request.getFirstHeader(HOST.toString()));

            if (!httpState.handle(request, responseWriter, false)) {

                if (request.matches("GET", PATH_PREFIX + "/ready", "/ready")) {

                    // Readiness probe — distinct from liveness/status, which answer 200 the instant
                    // the port binds. Stays 503 until the synchronous expectation initializers and
                    // OpenAPI seeding have completed (HttpState.isInitializationComplete()), so an
                    // orchestrator does not route traffic before the seeded expectations exist.
                    if (httpState.isInitializationComplete()) {
                        responseWriter.writeResponse(request, OK, "{\"status\":\"READY\"}", "application/json");
                    } else {
                        responseWriter.writeResponse(request, SERVICE_UNAVAILABLE, "{\"status\":\"NOT_READY\"}", "application/json");
                    }

                } else if (request.matches("PUT", PATH_PREFIX + "/status", "/status") ||
                    isNotBlank(configuration.livenessHttpGetPath()) && request.matches("GET", configuration.livenessHttpGetPath())) {

                    responseWriter.writeResponse(request, OK, portBindingSerializer.serialize(portBinding(server.getLocalPorts())), "application/json");

                } else if (request.matches("PUT", PATH_PREFIX + "/bind", "/bind")) {

                    // /bind mutates the server's listening ports, so it must take the SAME
                    // control-plane authn + authorization + audit decision as the operations
                    // dispatched through HttpState.handle and the /configuration route. Route
                    // through the shared core gate (which writes the 401/403 and audits on
                    // failure) BEFORE any side effect, and return on false so an unauthenticated
                    // caller cannot rebind ports. Default (no control-plane auth configured) is a
                    // no-op: the gate returns true and binding proceeds with no credentials.
                    if (!httpState.controlPlaneRequestAuthenticated(request, responseWriter)) {
                        return;
                    }
                    PortBinding requestedPortBindings = portBindingSerializer.deserialize(request.getBodyAsString());
                    if (requestedPortBindings != null) {
                        try {
                            List<Integer> actualPortBindings = server.bindServerPorts(requestedPortBindings.getPorts());
                            responseWriter.writeResponse(request, OK, portBindingSerializer.serialize(portBinding(actualPortBindings)), "application/json");
                        } catch (RuntimeException e) {
                            if (e.getCause() instanceof BindException) {
                                responseWriter.writeResponse(request, BAD_REQUEST, e.getMessage() + " port already in use", MediaType.create("text", "plain").toString());
                            } else {
                                throw e;
                            }
                        }
                    } else {
                        // null-body path writes no response via responseWriter, so complete the
                        // in-flight token explicitly to avoid stalling a keep-alive drain.
                        completeInFlight(inFlightRequest);
                    }

                } else if (request.matches("PUT", PATH_PREFIX + "/stop", "/stop")) {

                    // /stop shuts the whole server down, so it must take the SAME control-plane
                    // authn + authorization + audit decision as the operations dispatched through
                    // HttpState.handle and the /configuration route. Run the shared core gate
                    // (which writes the 401/403 and audits on failure) FIRST — BEFORE the OK write
                    // and BEFORE the shutdown thread is spawned — and return on false so an
                    // unauthenticated caller cannot stop the server. Default (no control-plane auth
                    // configured) is a no-op: the gate returns true and /stop proceeds with no
                    // credentials, preserving the existing behaviour.
                    if (!httpState.controlPlaneRequestAuthenticated(request, responseWriter)) {
                        return;
                    }
                    // Control-plane direct-writes bypass NettyResponseWriter.sendResponse, so the
                    // in-flight token is not completed by the response funnel here. Complete it
                    // explicitly (idempotent with the closeFuture safety net via the AtomicBoolean
                    // guard) so the drain triggered by this very /stop does not wait the full
                    // stopDrainMillis on a keep-alive connection whose token would otherwise linger.
                    completeInFlight(inFlightRequest);
                    ctx.writeAndFlush(response().withStatusCode(OK.code()));
                    new Scheduler.SchedulerThreadFactory("MockServer Stop").newThread(() -> server.stop()).start();

                } else if (request.matches("GET", PATH_PREFIX + "/configuration", "/configuration")
                    || request.matches("PUT", PATH_PREFIX + "/configuration", "/configuration")) {

                    // /configuration is serviced here, outside HttpState.handle, but PUT mutates
                    // live configuration — so it must take the SAME authn + authorization + audit
                    // decision as the operations dispatched through HttpState.handle. Route through
                    // the shared core gate (which writes the 401/403 and audits on failure) rather
                    // than the legacy boolean authentication SPI, so an enabled Wave-2 authorization
                    // policy applies here too (e.g. a read-only principal cannot PUT configuration).
                    if (!httpState.controlPlaneRequestAuthenticated(request, responseWriter)) {
                        return;
                    }
                    if (request.getMethod().getValue().equals("GET")) {
                        responseWriter.writeResponse(request, OK, configurationSerializer.serialize(configuration), "application/json");
                    } else {
                        try {
                            ConfigurationDTO configurationDTO = ObjectMapperFactory.createObjectMapper().readValue(request.getBodyAsString(), ConfigurationDTO.class);
                            synchronized (configuration) {
                                configurationDTO.applyTo(configuration);
                            }
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat("configuration updated via API")
                                );
                            }
                            responseWriter.writeResponse(request, OK, configurationSerializer.serialize(configuration), "application/json");
                        } catch (IllegalArgumentException e) {
                            responseWriter.writeResponse(request, BAD_REQUEST, e.getMessage(), MediaType.create("text", "plain").toString());
                        } catch (Exception e) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "Invalid configuration JSON", MediaType.create("text", "plain").toString());
                        }
                    }

                } else if (request.matches("GET", PATH_PREFIX + "/llm/optimisationReport", "/llm/optimisationReport")) {

                    // This GET is serviced here, outside HttpState.handle. Route it through the
                    // shared core gate (which writes the 401/403 and audits on failure) rather than
                    // the legacy authenticate-only SPI, so an enabled Wave-2 authorization policy
                    // applies here too: it is a READ, so a verified principal whose scopes map to NO
                    // role is 403'd while read-only/mutate/admin proceed (default-off unchanged).
                    if (!httpState.controlPlaneRequestAuthenticated(request, responseWriter)) {
                        return;
                    }
                    handleOptimisationReport(request, responseWriter);

                } else if (request.matches("GET", PATH_PREFIX + "/http3status", "/http3status")) {

                    int http3Port = server instanceof MockServer ? ((MockServer) server).getHttp3Port() : -1;
                    int activeConnections = server instanceof MockServer ? ((MockServer) server).getHttp3ActiveConnectionCount() : 0;
                    boolean enabled = http3Port > 0;
                    String json = "{\"enabled\":" + enabled + ",\"port\":" + http3Port + ",\"activeConnections\":" + activeConnections + "}";
                    responseWriter.writeResponse(request, OK, json, "application/json");

                } else if (request.getMethod().getValue().equals("GET") && request.getPath().getValue().startsWith(PATH_PREFIX + "/dashboard")) {

                    // Direct ctx write inside the handler bypasses NettyResponseWriter, so complete
                    // the in-flight token explicitly to keep the graceful-shutdown drain unblocked.
                    completeInFlight(inFlightRequest);
                    dashboardHandler.renderDashboard(ctx, request);

                } else if (request.getMethod().getValue().equals("GET") && request.getPath().getValue().equals(PATH_PREFIX + "/openapi.yaml")) {

                    // This GET is serviced here, outside HttpState.handle. Route it through the
                    // shared core gate (which writes the 401/403 and audits on failure) rather than
                    // the legacy authenticate-only SPI, so an enabled Wave-2 authorization policy
                    // applies here too: it is a READ, so a verified principal whose scopes map to NO
                    // role is 403'd while read-only/mutate/admin proceed (default-off unchanged).
                    // Call the gate FIRST and return on false (the gate writes the 401/403); only
                    // do the direct ctx write — which bypasses NettyResponseWriter, so the in-flight
                    // token must be completed explicitly — once the gate has granted access.
                    if (!httpState.controlPlaneRequestAuthenticated(request, responseWriter)) {
                        return;
                    }
                    completeInFlight(inFlightRequest);
                    openAPISpecHandler.renderOpenAPISpec(ctx, request);

                } else if (request.getMethod().getValue().equals("GET") && request.getPath().getValue().matches(PATH_PREFIX + "/metrics")) {

                    // Always reserve this control-plane path (like /dashboard and /openapi.yaml
                    // above). MetricsHandler serves the metrics when enabled and a CORS-decorated
                    // 404 when disabled, so a cross-origin dashboard reads the disabled state
                    // cleanly instead of the request falling through to mock matching.
                    // Direct ctx write bypasses NettyResponseWriter — complete the in-flight token.
                    completeInFlight(inFlightRequest);
                    metricsHandler.renderMetrics(ctx, request);

                } else if (request.getMethod().getValue().equals("CONNECT")) {

                    String username = configuration.proxyAuthenticationUsername();
                    String password = configuration.proxyAuthenticationPassword();
                    if (isNotBlank(username) && isNotBlank(password) &&
                        !request.containsHeader(PROXY_AUTHORIZATION.toString(), "Basic " + BASE_64_CONVERTER.bytesToBase64String((username + ':' + password).getBytes(StandardCharsets.UTF_8), StandardCharsets.US_ASCII))) {
                        HttpResponse response = response()
                            .withStatusCode(PROXY_AUTHENTICATION_REQUIRED.code())
                            .withHeader(PROXY_AUTHENTICATE.toString(), "Basic realm=\"" + StringEscapeUtils.escapeJava(configuration.proxyAuthenticationRealm()) + "\", charset=\"UTF-8\"");
                        ctx.writeAndFlush(response);
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(AUTHENTICATION_FAILED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setHttpResponse(response)
                                .setExpectation(request, response)
                                .setMessageFormat("proxy authentication failed so returning response:{}for forwarded request:{}")
                                .setArguments(response, request)
                        );
                    } else {
                        setProxyingRequest(ctx, Boolean.TRUE);
                        // assume SSL for CONNECT request
                        enableSslUpstreamAndDownstream(ctx.channel());
                        String[] hostParts = HttpRequest.splitHostPort(request.getPath().getValue());
                        String connectHost = hostParts[0];
                        int port = hostParts.length > 1 ? Integer.parseInt(hostParts[1]) : isSslEnabledUpstream(ctx.channel()) ? 443 : 80;
                        if (isNotBlank(connectHost)) {
                            server.getScheduler().submit(() -> configuration.addSubjectAlternativeName(connectHost));
                        }
                        ctx.pipeline().addLast(new HttpConnectHandler(configuration, server, mockServerLogger, connectHost, port));
                        ctx.pipeline().remove(this);
                        ctx.fireChannelRead(request);
                    }

                } else {

                    // Data-plane authentication gate (opt-in, default off). Reaching this branch means
                    // the request is NOT control-plane (/mockserver/*), NOT a health/status/ready probe
                    // and NOT a CONNECT — those are all handled in the earlier else-if branches above —
                    // so it is a genuine data-plane (mocked endpoint) request. The default-off path is a
                    // single boolean read, so behaviour is byte-identical to a server with no data-plane
                    // auth. The shared gate invokes the core DataPlaneAuthenticator (fail-closed +
                    // constant-time compare) and, on failure, writes the 401 + WWW-Authenticate via the
                    // ResponseWriter and audits — the SAME gate the HTTP/3 handler uses, so all transports
                    // (HTTP/1.1, HTTP/2, gRPC-over-h2, HTTP/3) enforce it identically.
                    if (!DataPlaneAuthenticationGate.isAuthenticated(configuration, mockServerLogger, request, responseWriter)) {
                        return;
                    }

                    if (configuration.tlsMutualAuthenticationRequired() && !isSslEnabledUpstream(ctx.channel())) {
                        HttpResponse upgradeResponse = response()
                            .withStatusCode(426)
                            .withReasonPhrase("Upgrade Required")
                            .withHeader("Upgrade", "TLS/1.2, HTTP/1.1")
                            .withHeader("Connection", "Upgrade");
                        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.INFO)
                                    .setHttpRequest(request)
                                    .setMessageFormat("no tls for data plane request:{}returning response:{}")
                                    .setArguments(request, upgradeResponse)
                            );
                        }
                        responseWriter.writeResponse(request, upgradeResponse, false);
                    } else {
                        try {
                            httpActionHandler.processAction(request, responseWriter, ctx, getLocalAddresses(ctx), isProxyingRequest(ctx), false);
                        } catch (Throwable throwable) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.ERROR)
                                    .setHttpRequest(request)
                                    .setMessageFormat("exception processing request:{}error:{}")
                                    .setArguments(request, throwable.getMessage())
                                    .setThrowable(throwable)
                            );
                        }
                    }

                }
            }
        } catch (IllegalArgumentException iae) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequest(request)
                    .setMessageFormat("exception processing request:{}error:{}")
                    .setArguments(request, iae.getMessage())
            );
            // send request without API CORS headers
            responseWriter.writeResponse(request, BAD_REQUEST, iae.getMessage(), MediaType.create("text", "plain").toString());
        } catch (Exception ex) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequest(request)
                    .setMessageFormat("exception processing " + request)
                    .setThrowable(ex)
            );
            responseWriter.writeResponse(request, response().withStatusCode(BAD_REQUEST.code()).withBody(ex.getMessage()), true);
        }
    }

    /**
     * Serve {@code GET /mockserver/llm/optimisationReport}. Retrieves the
     * recorded request/response pairs from the event log, delegates to the
     * core {@link org.mockserver.llm.analysis.LlmOptimisationReportService} to
     * build the report / brief, and writes JSON (default) or markdown. An empty
     * capture yields a 200 with an empty report / "no LLM traffic" brief.
     */
    private void handleOptimisationReport(HttpRequest request, ResponseWriter responseWriter) {
        // Building the report retrieves, redacts and renders potentially large captured traffic
        // (retrieveRecordedPairs blocks on the retrieve future; service.build redacts + renders), so
        // it must not run on the Netty event loop. Offload to the scheduler and write the response
        // from there — ctx.writeAndFlush is thread-safe and hops back onto the channel event loop, so
        // the response (status/body/content-type) and error handling below are byte-for-byte unchanged.
        httpState.getScheduler().submit(() -> {
            try {
                String format = request.getFirstQueryStringParameter("format");
                if (format == null || format.isEmpty()) {
                    format = "json";
                }
                if (!"json".equalsIgnoreCase(format) && !"markdown".equalsIgnoreCase(format) && !"csv".equalsIgnoreCase(format)) {
                    responseWriter.writeResponse(request, BAD_REQUEST, "format must be one of: json, markdown, csv", MediaType.create("text", "plain").toString());
                    return;
                }

                org.mockserver.llm.analysis.LlmOptimisationReportService.Filter filter =
                    new org.mockserver.llm.analysis.LlmOptimisationReportService.Filter(
                        request.getFirstQueryStringParameter("session"),
                        request.getFirstQueryStringParameter("host"),
                        request.getFirstQueryStringParameter("provider"));

                org.mockserver.llm.analysis.LlmOptimisationReportService service =
                    new org.mockserver.llm.analysis.LlmOptimisationReportService();
                org.mockserver.llm.analysis.LlmOptimisationReportService.Result result =
                    service.build(retrieveRecordedPairs(), filter);

                if ("markdown".equalsIgnoreCase(format)) {
                    responseWriter.writeResponse(request, OK, service.renderBrief(result), "text/markdown; charset=utf-8");
                } else if ("csv".equalsIgnoreCase(format)) {
                    responseWriter.writeResponse(request, OK, service.renderCsv(result), "text/csv; charset=utf-8");
                } else {
                    String json = ObjectMapperFactory.createObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(result.getReport());
                    responseWriter.writeResponse(request, OK, json, "application/json");
                }
            } catch (Exception e) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setHttpRequest(request)
                        .setMessageFormat("exception building LLM optimisation report:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
                responseWriter.writeResponse(request, INTERNAL_SERVER_ERROR, "Internal error generating optimisation report", MediaType.create("text", "plain").toString());
            }
        });
    }

    /**
     * Retrieve all recorded request/response pairs from the event log as a list,
     * for the optimisation report. Returns an empty list when nothing is recorded.
     */
    private List<org.mockserver.model.LogEventRequestAndResponse> retrieveRecordedPairs() {
        HttpRequest retrieveRequest = HttpRequest.request()
            .withMethod("PUT")
            .withPath(PATH_PREFIX + "/retrieve")
            .withQueryStringParameter("type", "REQUEST_RESPONSES")
            .withQueryStringParameter("format", "JSON");
        HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
        String body = retrieveResponse.getBodyAsString();
        java.util.List<org.mockserver.model.LogEventRequestAndResponse> result = new java.util.ArrayList<>();
        if (body != null && !body.trim().isEmpty()) {
            try {
                org.mockserver.model.LogEventRequestAndResponse[] pairs =
                    new org.mockserver.serialization.LogEventRequestAndResponseSerializer(mockServerLogger).deserializeArray(body);
                result.addAll(java.util.Arrays.asList(pairs));
            } catch (IllegalArgumentException e) {
                // no parseable pairs (e.g. "[]") — treat as none
            }
        }
        return result;
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by " + server.getClass() + " handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("SSL or decoder fault caught by " + server.getClass() + " handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
        }
        closeOnFlush(ctx.channel());
    }
}
