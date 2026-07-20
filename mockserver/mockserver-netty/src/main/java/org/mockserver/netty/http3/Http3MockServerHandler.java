package org.mockserver.netty.http3;

import com.google.protobuf.Descriptors;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.JDKCertificateToMockServerX509Certificate;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.DataPlaneAuthenticationGate;
import org.mockserver.netty.mcp.JsonRpcMessage;
import org.mockserver.netty.mcp.McpRequestProcessor;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.telemetry.TraceContextAttributes;
import org.mockserver.telemetry.W3CTraceContext;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;
import static org.mockserver.metrics.Metrics.Name.REQUESTS_RECEIVED_COUNT;

/**
 * HTTP/3 request stream handler that bridges incoming QUIC requests into
 * MockServer's standard request-processing pipeline (expectation matching,
 * actions, recording, proxy forwarding).
 * <p>
 * Each QUIC bidirectional stream gets its own instance. The handler
 * accumulates the request headers and body data frames, then routes the
 * resulting {@link HttpRequest} through the same {@link HttpState} and
 * {@link HttpActionHandler} used by HTTP/1.1 and HTTP/2.
 */
public class Http3MockServerHandler extends Http3RequestStreamInboundHandler {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final HttpState httpState;
    private final HttpActionHandler httpActionHandler;
    private final Metrics metrics;
    private final JDKCertificateToMockServerX509Certificate jdkCertificateToMockServerX509Certificate;
    /** Null when MCP is not wired (legacy/test constructors). */
    private final McpRequestProcessor mcpRequestProcessor;

    // per-stream state: headers + accumulated body
    private Http3RequestBridge.ParsedHeaders parsedHeaders;
    private CompositeByteBuf bodyAccumulator;
    // Running total of accumulated body bytes for enforcing the maxRequestBodySize cap.
    private long accumulatedBodySize;
    // Non-null once this stream has been routed to true bidirectional gRPC streaming;
    // inbound DATA frames are then fed incrementally to it rather than accumulated.
    private Http3GrpcBidiStreamHandler bidiHandler;
    // Set to true once a body-too-large rejection has been sent, to suppress further accumulation.
    private boolean bodyExceeded;

    public Http3MockServerHandler(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        HttpState httpState,
        HttpActionHandler httpActionHandler,
        Metrics metrics
    ) {
        this(configuration, mockServerLogger, httpState, httpActionHandler, metrics, null);
    }

    public Http3MockServerHandler(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        HttpState httpState,
        HttpActionHandler httpActionHandler,
        Metrics metrics,
        McpRequestProcessor mcpRequestProcessor
    ) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.httpState = httpState;
        this.httpActionHandler = httpActionHandler;
        this.metrics = metrics;
        this.jdkCertificateToMockServerX509Certificate = new JDKCertificateToMockServerX509Certificate(mockServerLogger);
        this.mcpRequestProcessor = mcpRequestProcessor;
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
        parsedHeaders = Http3RequestBridge.parseHeaders(headersFrame);
        bodyAccumulator = ctx.alloc().compositeBuffer();

        // True bidirectional gRPC streaming is routed here, at HEADERS time, because the
        // server must start writing response frames while the client is still sending
        // request frames (QUIC streams are full-duplex). When a bidi method matches a
        // GrpcBidiResponse expectation, subsequent DATA frames are fed incrementally to
        // the bidi handler instead of being accumulated for one-shot processing.
        tryBeginGrpcBidi(ctx);
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
        try {
            if (bodyExceeded) {
                // Already rejected -- discard further data frames silently.
                return;
            }

            int frameSize = dataFrame.content().readableBytes();
            int maxBodySize = configuration.maxRequestBodySize();

            if (bidiHandler != null) {
                // Enforce the cap on the bidi streaming path too.
                if (maxBodySize > 0 && accumulatedBodySize + frameSize > maxBodySize) {
                    bodyExceeded = true;
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("HTTP/3 bidi stream body size {} exceeds maxRequestBodySize {} -- resetting stream")
                            .setArguments(accumulatedBodySize + frameSize, maxBodySize)
                    );
                    bidiHandler.onChannelInactive();
                    bidiHandler = null;
                    if (ctx.channel() instanceof QuicStreamChannel) {
                        ((QuicStreamChannel) ctx.channel()).shutdownOutput();
                    }
                    return;
                }
                accumulatedBodySize += frameSize;
                ByteBuf content = dataFrame.content();
                byte[] bytes = new byte[content.readableBytes()];
                content.readBytes(bytes);
                bidiHandler.onData(bytes);
            } else if (bodyAccumulator != null) {
                // Enforce the maxRequestBodySize cap, mirroring the HTTP/1.1/HTTP/2
                // path which uses HttpObjectAggregator.maxContentLength.
                if (maxBodySize > 0 && accumulatedBodySize + frameSize > maxBodySize) {
                    bodyExceeded = true;
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("HTTP/3 request body size {} exceeds maxRequestBodySize {} -- rejecting with 413")
                            .setArguments(accumulatedBodySize + frameSize, maxBodySize)
                    );
                    releaseBodyAccumulator();
                    sendPayloadTooLarge(ctx);
                    return;
                }
                accumulatedBodySize += frameSize;
                Http3RequestBridge.accumulateBody(bodyAccumulator, dataFrame);
            }
        } finally {
            dataFrame.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (bidiHandler != null) {
            bidiHandler.onChannelInactive();
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelInputClosed(ChannelHandlerContext ctx) {
        try {
            if (bodyExceeded) {
                // Already rejected with 413 -- do not process.
                return;
            }

            if (bidiHandler != null) {
                // Client half-closed a bidi stream: finish once all responses have drained.
                bidiHandler.onInputClosed();
                return;
            }

            if (parsedHeaders == null) {
                // no headers received -- nothing to process
                return;
            }

            byte[] body = bodyAccumulator != null ? Http3RequestBridge.readAccumulatedBody(bodyAccumulator) : new byte[0];

            HttpRequest request = Http3RequestBridge.toHttpRequest(
                parsedHeaders.method(),
                parsedHeaders.path(),
                parsedHeaders.scheme(),
                parsedHeaders.authority(),
                parsedHeaders.headers(),
                body
            );

            // mTLS client-certificate capture: extract the peer certificate chain
            // from the QUIC SSLEngine (analogous to the TCP path's
            // SniHandler.retrieveClientCertificates → MockServerHttpServerCodec →
            // withClientCertificateChain) so cert-based expectation matching and
            // verification work over HTTP/3.
            captureClientCertificates(ctx, request);

            // W3C trace-context extraction: parse traceparent/tracestate from the
            // request headers (or generate a context when otelGenerateTraceId is set)
            // and store on the channel attribute. HttpActionHandler reads this attr
            // to parent OTel spans -- identical logic to TraceContextHandler on the TCP path.
            extractOrGenerateTraceContext(ctx, request);

            if (configuration.metricsEnabled()) {
                metrics.increment(REQUESTS_RECEIVED_COUNT);
            }

            // MCP dispatch: intercept /mockserver/mcp before the normal pipeline
            if (mcpRequestProcessor != null && McpRequestProcessor.isMcpPath(parsedHeaders.path())) {
                handleMcpOverHttp3(ctx, request);
                return;
            }

            // gRPC-over-HTTP/3: detect gRPC requests by content-type and route
            // through the gRPC adapter, which decodes gRPC framing, converts
            // protobuf to JSON for matching, and writes the response with correct
            // gRPC trailing HEADERS framing. Non-gRPC requests take the normal path.
            String contentType = request.getFirstHeader("content-type");
            if (GrpcHttp3Adapter.isGrpcRequest(contentType)) {
                handleGrpcRequest(ctx, request);
                return;
            }

            ResponseWriter responseWriter = new Http3ResponseWriter(configuration, mockServerLogger, ctx);

            // first, try control-plane handling (expectations CRUD, status, etc.)
            if (!httpState.handle(request, responseWriter, false)) {
                // Data-plane authentication gate (opt-in, default off) — identical to the HTTP/1.1/HTTP/2
                // path. The shared gate exempts control-plane (/mockserver/*) and the liveness probe path
                // internally, which matters here because the HTTP/3 handler (unlike HttpRequestHandler)
                // routes control-plane routes such as /mockserver/status and /ready into this same
                // data-plane fall-through rather than servicing them in httpState.handle — so those stay
                // reachable WITHOUT data-plane credentials. On failure the gate writes the 401 +
                // WWW-Authenticate via the Http3ResponseWriter and we must NOT call processAction.
                if (!DataPlaneAuthenticationGate.isAuthenticated(configuration, mockServerLogger, request, responseWriter)) {
                    return;
                }
                // data-plane: match expectations, execute actions, proxy, etc.
                try {
                    httpActionHandler.processAction(
                        request,
                        responseWriter,
                        ctx,
                        buildLocalAddresses(ctx),
                        false,                  // not proxying
                        true                    // synchronous processing
                    );
                } catch (Throwable throwable) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setHttpRequest(request)
                            .setMessageFormat("exception processing HTTP/3 request:{}error:{}")
                            .setArguments(request, throwable.getMessage())
                            .setThrowable(throwable)
                    );
                }
            }
        } finally {
            releaseBodyAccumulator();
        }
    }

    /**
     * Attempt to route this stream to true bidirectional gRPC streaming. Returns
     * {@code true} (and installs {@link #bidiHandler}) when ALL of the following hold:
     * <ul>
     *   <li>{@code grpcBidiStreamingEnabled} is on;</li>
     *   <li>proto descriptors are loaded and the request is gRPC (content-type);</li>
     *   <li>the {@code :path} resolves to a method that is both client- and server-streaming;</li>
     *   <li>a matching expectation with a {@link GrpcBidiResponse} action is found.</li>
     * </ul>
     * The match uses the same two-phase peek-then-consume protocol as the HTTP/2
     * {@code GrpcBidiRouterHandler}: a side-effect-free peek confirms the action type, then a
     * consuming match decrements Times / transitions scenarios / sets responseInProgress (cleared
     * by the completion callback when the stream ends). When any condition is not met, this is a
     * no-op and the stream falls through to the normal accumulate-then-process path (so unary,
     * server-streaming, and non-gRPC requests are unaffected).
     */
    private boolean tryBeginGrpcBidi(ChannelHandlerContext ctx) {
        if (!Boolean.TRUE.equals(configuration.grpcBidiStreamingEnabled())) {
            return false;
        }
        GrpcProtoDescriptorStore descriptorStore = httpState.getGrpcDescriptorStore();
        if (descriptorStore == null || !descriptorStore.hasServices()) {
            return false;
        }
        String path = parsedHeaders.path();
        if (path == null || path.isEmpty()) {
            return false;
        }

        HttpRequest request = Http3RequestBridge.toHttpRequest(
            parsedHeaders.method(), path, parsedHeaders.scheme(), parsedHeaders.authority(),
            parsedHeaders.headers(), new byte[0]
        );
        if (!GrpcHttp3Adapter.isGrpcRequest(request.getFirstHeader("content-type"))) {
            return false;
        }

        String[] parts = GrpcHttp3Adapter.parseGrpcPath(path);
        Descriptors.MethodDescriptor methodDescriptor = descriptorStore.getMethod(parts[0], parts[1]);
        if (methodDescriptor == null || !methodDescriptor.isClientStreaming() || !methodDescriptor.isServerStreaming()) {
            return false;
        }

        // Tag with service/method (header-based matching parity) and capture client certs so
        // cert-based expectation matching works for bidi too.
        request.withHeader("x-grpc-service", parts[0]).withHeader("x-grpc-method", parts[1]);
        if (request.getLogCorrelationId() == null) {
            request.withLogCorrelationId(UUIDService.getUUID());
        }
        captureClientCertificates(ctx, request);

        // Two-phase match: peek (side-effect-free) then consume.
        Expectation peeked = httpState.peekFirstMatchingExpectation(request);
        if (peeked == null || !(peeked.getAction() instanceof GrpcBidiResponse)) {
            return false;
        }
        Expectation consumed = httpState.firstMatchingExpectation(request);
        if (consumed == null || !(consumed.getAction() instanceof GrpcBidiResponse)) {
            // Race between peek and consume -- fall back to the normal path.
            return false;
        }

        // Log the bidi request unconditionally (matching HttpActionHandler) so it is recorded
        // in the request log and remains verifiable regardless of the configured log level.
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setCorrelationId(request.getLogCorrelationId())
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );

        if (configuration.metricsEnabled()) {
            metrics.increment(REQUESTS_RECEIVED_COUNT);
        }

        final Expectation matchedExpectation = consumed;
        Runnable completionCallback = () -> httpState.postProcess(matchedExpectation);

        // Resolve an INBOUND_STREAM breakpoint matcher for this bidi stream. Default-off: when no
        // matcher matches, inboundStreamId stays null and no inbound frames are parked. Mirrors the
        // HTTP/2 GrpcBidiRouterHandler wiring so HTTP/2 and HTTP/3 bidi share identical semantics.
        final String inboundStreamId;
        final String inboundBreakpointClientId;
        final String inboundBreakpointId;
        org.mockserver.mock.breakpoint.BreakpointMatcher inboundMatcher =
            org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance()
                .findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.INBOUND_STREAM);
        if (inboundMatcher != null) {
            inboundStreamId = "grpc-bidi-inbound-" + path + "-h3-" + UUIDService.getUUID();
            inboundBreakpointClientId = inboundMatcher.getClientId();
            inboundBreakpointId = inboundMatcher.getId();
        } else {
            inboundStreamId = null;
            inboundBreakpointClientId = null;
            inboundBreakpointId = null;
        }

        bidiHandler = new Http3GrpcBidiStreamHandler(
            ctx, methodDescriptor, descriptorStore.getConverter(),
            (GrpcBidiResponse) consumed.getAction(), completionCallback, mockServerLogger,
            configuration, inboundStreamId, inboundBreakpointClientId, inboundBreakpointId,
            httpState.getWebSocketClientRegistry()
        );
        bidiHandler.start(request);
        return true;
    }

    /**
     * Handle a gRPC request over HTTP/3: decode the gRPC framing, convert
     * protobuf to JSON for expectation matching, and write the response with
     * correct gRPC wire framing (initial HEADERS + DATA + trailing HEADERS
     * with grpc-status).
     * <p>
     * This reuses the existing {@link GrpcHttp3Adapter} and
     * {@link Http3GrpcResponseWriter} to avoid duplicating any gRPC codec logic.
     * The descriptor store from {@link HttpState} provides the protobuf schema
     * needed for JSON conversion.
     */
    private void handleGrpcRequest(ChannelHandlerContext ctx, HttpRequest request) {
        GrpcProtoDescriptorStore descriptorStore = httpState.getGrpcDescriptorStore();

        if (descriptorStore == null || !descriptorStore.hasServices()) {
            // No proto descriptors loaded: pass the request through unchanged.
            // The body remains gRPC-framed (binary), which lets raw binary
            // expectations match, and the response writer will frame
            // grpc-status correctly in trailing HEADERS.
            Http3GrpcResponseWriter grpcResponseWriter = new Http3GrpcResponseWriter(
                configuration, mockServerLogger, ctx, descriptorStore, null, null,
                httpState.getWebSocketClientRegistry()
            );
            grpcResponseWriter.scheduleDeadline(request);
            processRequestThroughPipeline(ctx, request, grpcResponseWriter);
            return;
        }

        try {
            HttpRequest grpcRequest = GrpcHttp3Adapter.transformGrpcRequest(request, descriptorStore);
            // Extract service/method from the transformed request so the
            // response writer can re-encode the JSON response to protobuf
            String grpcService = grpcRequest.getFirstHeader("x-grpc-service");
            String grpcMethod = grpcRequest.getFirstHeader("x-grpc-method");
            Http3GrpcResponseWriter grpcResponseWriter = new Http3GrpcResponseWriter(
                configuration, mockServerLogger, ctx, descriptorStore, grpcService, grpcMethod,
                httpState.getWebSocketClientRegistry()
            );
            grpcResponseWriter.scheduleDeadline(grpcRequest);
            processRequestThroughPipeline(ctx, grpcRequest, grpcResponseWriter);
        } catch (GrpcException e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("gRPC request error over HTTP/3:{}:{}")
                    .setArguments(request.getPath(), e.getMessage())
            );
            // the status travels on the exception, so oversize -> RESOURCE_EXHAUSTED and an
            // unsupported grpc-encoding -> UNIMPLEMENTED, as on HTTP/1.1 and HTTP/2
            GrpcStatusMapper.GrpcStatusCode statusCode =
                e.getMessage() != null && e.getMessage().startsWith("unknown gRPC method")
                    ? GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED
                    : e.getStatusCode();
            Http3GrpcResponseWriter errorWriter = new Http3GrpcResponseWriter(
                configuration, mockServerLogger, ctx, descriptorStore, null, null
            );
            errorWriter.writeErrorResponse(statusCode, e.getMessage());
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("failed to convert gRPC request to JSON over HTTP/3:{}:{}")
                    .setArguments(request.getPath(), e.getMessage())
            );
            Http3GrpcResponseWriter errorWriter = new Http3GrpcResponseWriter(
                configuration, mockServerLogger, ctx, descriptorStore, null, null
            );
            errorWriter.writeErrorResponse(
                GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                "failed to decode gRPC request: " + e.getMessage()
            );
        }
    }

    /**
     * Route a request through the standard MockServer pipeline (control-plane
     * then data-plane), using the given response writer.
     */
    private void processRequestThroughPipeline(
        ChannelHandlerContext ctx,
        HttpRequest request,
        ResponseWriter responseWriter
    ) {
        if (!httpState.handle(request, responseWriter, false)) {
            // Data-plane authentication gate (opt-in, default off) — same as the non-gRPC HTTP/3 path
            // and the HTTP/1.1/HTTP/2 path. This is the gRPC-over-HTTP/3 data-plane dispatch; gRPC
            // requests carry the same HTTP Authorization / api-key headers, so they are gated too. On
            // failure the gate writes the 401 via the (gRPC) ResponseWriter and we must NOT proceed.
            if (!DataPlaneAuthenticationGate.isAuthenticated(configuration, mockServerLogger, request, responseWriter)) {
                return;
            }
            try {
                httpActionHandler.processAction(
                    request,
                    responseWriter,
                    ctx,
                    buildLocalAddresses(ctx),
                    false,
                    true
                );
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setHttpRequest(request)
                        .setMessageFormat("exception processing gRPC request over HTTP/3:{}error:{}")
                        .setArguments(request, throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
        }
    }

    /**
     * Build a set of local address strings (host:port variants) to pass to the
     * action handler so that unmatched requests are not mistakenly treated as
     * proxy-forwarding candidates.
     */
    private Set<String> buildLocalAddresses(ChannelHandlerContext ctx) {
        Set<String> addresses = new HashSet<>();
        // walk up to the parent QuicChannel to find the UDP local address
        Channel parentChannel = ctx.channel().parent();
        if (parentChannel != null) {
            parentChannel = parentChannel.parent(); // QuicStreamChannel -> QuicChannel -> DatagramChannel
        }
        int port = -1;
        if (parentChannel != null && parentChannel.localAddress() instanceof InetSocketAddress) {
            port = ((InetSocketAddress) parentChannel.localAddress()).getPort();
        } else if (ctx.channel().localAddress() instanceof InetSocketAddress) {
            port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
        }
        if (port > 0) {
            String portSuffix = ":" + port;
            addresses.add("localhost" + portSuffix);
            addresses.add("127.0.0.1" + portSuffix);
            addresses.add("::1" + portSuffix);
            addresses.add("[::1]" + portSuffix);
            addresses.add("0:0:0:0:0:0:0:1" + portSuffix);
        }
        return addresses;
    }

    /**
     * Handle an MCP request over HTTP/3. Determines the HTTP method from the
     * request, delegates to {@link McpRequestProcessor}, and writes the result
     * as HTTP/3 frames (headers + optional data + stream shutdown).
     * <p>
     * Control-plane authentication is enforced for POST, GET, and DELETE --
     * mirroring the TCP path ({@code McpStreamableHttpHandler.authenticateRequest}).
     * OPTIONS (CORS preflight) is exempt, matching TCP behaviour.
     */
    private void handleMcpOverHttp3(ChannelHandlerContext ctx, HttpRequest request) {
        String method = request.getMethod() != null ? request.getMethod().getValue() : "GET";
        String origin = request.getFirstHeader("origin");
        String accessControlRequestHeaders = request.getFirstHeader("access-control-request-headers");
        String mcpSessionId = request.getFirstHeader("mcp-session-id");
        McpRequestProcessor.McpResult result;

        switch (method.toUpperCase()) {
            case "OPTIONS":
                // CORS preflight -- exempt from authentication (matches TCP path)
                boolean hasOrigin = origin != null && !origin.isEmpty();
                result = mcpRequestProcessor.handleOptions(hasOrigin);
                break;
            case "POST":
                org.mockserver.authentication.AuthenticationResult authenticationResult = authenticateMcpRequestResult(request);
                if (authenticationResult == null) {
                    result = buildUnauthorizedResult();
                    break;
                }
                String body = request.getBodyAsString();
                result = mcpRequestProcessor.handlePost(body, mcpSessionId, authenticationResult.getScopes());
                break;
            case "DELETE":
                if (!authenticateMcpRequest(ctx, request)) {
                    result = buildUnauthorizedResult();
                    break;
                }
                result = mcpRequestProcessor.handleDelete(mcpSessionId);
                break;
            case "GET":
                if (!authenticateMcpRequest(ctx, request)) {
                    result = buildUnauthorizedResult();
                    break;
                }
                result = mcpRequestProcessor.handleGet();
                break;
            default:
                result = mcpRequestProcessor.handleOptions(false);
                break;
        }

        writeMcpResultAsHttp3(ctx, result, origin, accessControlRequestHeaders);
    }

    /**
     * Authenticate an MCP request against the control-plane authentication handler,
     * mirroring the TCP path's {@code McpStreamableHttpHandler.authenticateRequest()}.
     * <p>
     * The request already has the client certificate chain attached (captured in
     * {@link #captureClientCertificates}), so mTLS authentication works over H3.
     *
     * @return true if authentication passed (or no handler is configured); false if rejected
     */
    private boolean authenticateMcpRequest(ChannelHandlerContext ctx, HttpRequest request) {
        return authenticateMcpRequestResult(request) != null;
    }

    /**
     * Authenticate an MCP request and return the {@link org.mockserver.authentication.AuthenticationResult}
     * (carrying the verified principal's scopes, used for per-tool control-plane authorization on the
     * POST path), or {@code null} when authentication fails. When no handler is configured, returns an
     * authenticated-but-anonymous result (no scopes) so the caller proceeds unchanged.
     * <p>
     * Uses the richer {@code authenticate()} SPI: legacy boolean handlers are adapted to an
     * authenticated-but-anonymous result by its default method, so behaviour is unchanged for them.
     */
    private org.mockserver.authentication.AuthenticationResult authenticateMcpRequestResult(HttpRequest request) {
        AuthenticationHandler authHandler = httpState.getControlPlaneAuthenticationHandler();
        if (authHandler == null) {
            return org.mockserver.authentication.AuthenticationResult.authenticated(null, "none", java.util.Map.of(), java.util.Set.of());
        }
        try {
            org.mockserver.authentication.AuthenticationResult result = authHandler.authenticate(request);
            return result.isAuthenticated() ? result : null;
        } catch (AuthenticationException e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("MCP-over-H3 authentication failed: {}")
                    .setArguments(e.getMessage())
                    .setThrowable(e)
            );
            return null;
        }
    }

    /**
     * Build an unauthorized (401) MCP result with the same JSON-RPC error body
     * as the TCP path's {@code McpStreamableHttpHandler.writeUnauthorized()}.
     */
    private McpRequestProcessor.McpResult buildUnauthorizedResult() {
        byte[] body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(
                JsonRpcMessage.JsonRpcResponse.error(null, JsonRpcMessage.INVALID_REQUEST, "Unauthorized for control plane"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            body = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Unauthorized for control plane\"},\"id\":null}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return new McpRequestProcessor.McpResult(401, body, null);
    }

    /**
     * Write an {@link McpRequestProcessor.McpResult} as HTTP/3 frames,
     * including CORS headers when the request had an Origin header --
     * mirroring the TCP path's {@code McpStreamableHttpHandler.addCorsHeaders()}.
     *
     * @param origin the request's Origin header value (may be null/empty)
     * @param accessControlRequestHeaders the request's Access-Control-Request-Headers value (may be null)
     */
    private void writeMcpResultAsHttp3(
        ChannelHandlerContext ctx, McpRequestProcessor.McpResult result,
        String origin, String accessControlRequestHeaders
    ) {
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().status(String.valueOf(result.getStatusCode()));
        headersFrame.headers().add("server", "mockserver-http3");

        if (result.hasBody()) {
            headersFrame.headers().add("content-type", "application/json");
        }
        if (result.getSessionId() != null) {
            headersFrame.headers().add("mcp-session-id", result.getSessionId());
        }

        // CORS headers -- mirror the TCP path (McpStreamableHttpHandler.addCorsHeaders)
        if (origin != null && !origin.isEmpty()) {
            headersFrame.headers().add("access-control-allow-origin", origin);
            headersFrame.headers().add("access-control-allow-methods", CORSHeaders.DEFAULT_ALLOW_METHODS);
            String allowHeaders = (accessControlRequestHeaders != null && !accessControlRequestHeaders.isEmpty())
                ? accessControlRequestHeaders : CORSHeaders.DEFAULT_ALLOW_HEADERS;
            headersFrame.headers().add("access-control-allow-headers", allowHeaders);
            headersFrame.headers().add("access-control-expose-headers", "Mcp-Session-Id, " + CORSHeaders.DEFAULT_ALLOW_HEADERS);
            headersFrame.headers().add("access-control-max-age", "300");
        }

        ctx.write(headersFrame);
        if (result.hasBody()) {
            ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(result.getBody())))
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        } else {
            ctx.flush();
            if (ctx.channel() instanceof QuicStreamChannel) {
                ((QuicStreamChannel) ctx.channel()).shutdownOutput();
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // safety-net: release the body accumulator if the handler is removed before
        // channelInputClosed fires (e.g. abrupt disconnect, exception, pipeline change)
        releaseBodyAccumulator();
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.WARN)
                .setMessageFormat("exception in HTTP/3 request handler: {}")
                .setArguments(cause.getMessage())
                .setThrowable(cause)
        );
        ctx.close();
    }

    /**
     * Capture the peer (client) certificate chain from the QUIC handshake and
     * plumb it into the request via {@link HttpRequest#withClientCertificateChain},
     * exactly like the TCP path does via {@code SniHandler.retrieveClientCertificates}
     * → {@code MockServerHttpServerCodec}. This enables cert-based expectation
     * matching and verification over HTTP/3.
     * <p>
     * The QUIC SSLEngine is obtained from the parent {@link QuicChannel}. If
     * the client did not present a certificate (SSLPeerUnverifiedException),
     * the request is left without a certificate chain (no error).
     */
    private void captureClientCertificates(ChannelHandlerContext ctx, HttpRequest request) {
        try {
            // Walk QuicStreamChannel → QuicChannel to get the QUIC SSLEngine
            Channel streamChannel = ctx.channel();
            Channel parentChannel = streamChannel.parent();
            if (parentChannel instanceof QuicChannel) {
                QuicChannel quicChannel = (QuicChannel) parentChannel;
                SSLEngine sslEngine = quicChannel.sslEngine();
                if (sslEngine != null) {
                    SSLSession sslSession = sslEngine.getSession();
                    if (sslSession != null) {
                        try {
                            Certificate[] peerCertificates = sslSession.getPeerCertificates();
                            if (peerCertificates != null && peerCertificates.length > 0) {
                                jdkCertificateToMockServerX509Certificate.setClientCertificates(request, peerCertificates);
                            }
                        } catch (SSLPeerUnverifiedException ignore) {
                            // client did not present a certificate -- normal for non-mTLS connections
                        }
                    }
                }
            }
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setHttpRequest(request)
                    .setMessageFormat("failed to capture client certificates from QUIC session: {}")
                    .setArguments(e.getMessage())
            );
        }
    }

    /**
     * Extract W3C trace context from the request's traceparent/tracestate headers,
     * or generate a new context when {@code otelGenerateTraceId} is enabled and no
     * traceparent header is present. The parsed/generated context is stored on the
     * channel attribute so that {@code HttpActionHandler} can attach it as a remote
     * parent to request-level OpenTelemetry spans.
     * <p>
     * This replicates the inbound logic of {@code TraceContextHandler} (TCP path)
     * using the same {@link W3CTraceContext} and {@link TraceContextAttributes}
     * types and the same configuration gates.
     */
    private void extractOrGenerateTraceContext(ChannelHandlerContext ctx, HttpRequest request) {
        String traceparent = request.getFirstHeader("traceparent");
        String tracestate = request.getFirstHeader("tracestate");

        if (traceparent != null && !traceparent.isEmpty()) {
            W3CTraceContext context = W3CTraceContext.parse(traceparent, tracestate);
            if (context != null && context.isValid()) {
                ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).set(context);
            }
        } else if (configuration.otelGenerateTraceId()) {
            W3CTraceContext generated = generateTraceContext();
            ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).set(generated);
        }
    }

    /**
     * Generate a new W3C trace context with a random trace ID and parent ID.
     * Uses version 00 and sampled flag 01.
     */
    private static W3CTraceContext generateTraceContext() {
        String traceId = randomHexString(32);
        String parentId = randomHexString(16);
        return new W3CTraceContext("00", traceId, parentId, "01", null);
    }

    /**
     * Generate a lowercase hex string of the specified length from random UUIDs.
     */
    static String randomHexString(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            sb.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return sb.substring(0, length);
    }

    /**
     * Write a 413 Payload Too Large response and shut down the QUIC stream output.
     * This mirrors the behaviour of Netty's {@code HttpObjectAggregator} when
     * {@code maxContentLength} is exceeded on the HTTP/1.1 / HTTP/2 paths.
     */
    private void sendPayloadTooLarge(ChannelHandlerContext ctx) {
        byte[] body = "{\"error\":\"request body too large\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DefaultHttp3HeadersFrame headers = new DefaultHttp3HeadersFrame();
        headers.headers().status("413");
        headers.headers().add("content-type", "application/json; charset=utf-8");
        headers.headers().addInt("content-length", body.length);
        headers.headers().add("server", "mockserver-http3");
        ctx.write(headers);
        ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(body)))
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    /**
     * Release the body accumulator if it is non-null and has not already been
     * released. Guards against double-release by nulling the reference.
     */
    private void releaseBodyAccumulator() {
        if (bodyAccumulator != null) {
            bodyAccumulator.release();
            bodyAccumulator = null;
        }
    }
}
