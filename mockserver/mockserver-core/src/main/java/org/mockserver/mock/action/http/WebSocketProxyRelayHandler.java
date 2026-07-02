package org.mockserver.mock.action.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.model.CrossProtocolTrigger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.socket.NettyTransport;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpResponse.response;

/**
 * Proxy passthrough relay for WebSocket connections.
 *
 * <p>When a WebSocket upgrade request (HTTP GET with {@code Upgrade: websocket}) arrives in proxy mode and no
 * WebSocket mock expectation matches (or a {@code FORWARD} expectation matches), MockServer opens the upstream
 * WebSocket connection (honouring scheme/TLS), relays the {@code 101 Switching Protocols} handshake, and then relays
 * frames bidirectionally (text, binary, ping, pong, close) until either side closes.</p>
 *
 * <p>The relay reuses the inbound channel's event loop for the upstream connection, so both the client-facing and
 * upstream-facing halves run on the same single thread — there are no cross-thread races between the two frame relays.</p>
 *
 * <p>The upgrade exchange is recorded as a {@code FORWARDED_REQUEST} in the event log (request = the upgrade GET,
 * response = {@code 101} carrying the transcript of relayed frames), so {@code retrieveRecordedRequests} and the
 * dashboard show the WebSocket traffic. The transcript is bounded by
 * {@link Configuration#webSocketProxyMaxRecordedFrames()} frames per connection and each frame payload is capped at
 * {@link #MAX_RECORDED_FRAME_BYTES} bytes, mirroring the {@code maxLogEntries} memory-management philosophy.</p>
 *
 * <p><b>Scope (v1):</b> HTTP/1.1 upgrade relay only (plain and TLS upstream). HTTP/2 extended-CONNECT WebSocket is a
 * documented boundary — see docs/code/netty-pipeline.md.</p>
 */
public class WebSocketProxyRelayHandler {

    /**
     * Per-frame payload byte cap for the recorded transcript. Frames larger than this are relayed in full but their
     * recorded payload is truncated to this many bytes (flagged {@code truncated}). Keeps a single recorded frame from
     * pinning an unbounded buffer while still capturing enough to identify the traffic.
     */
    static final int MAX_RECORDED_FRAME_BYTES = 32 * 1024;
    /**
     * Absolute per-connection cap on the accumulated transcript JSON (the dominant memory consumer). Bounds the
     * worst case independently of the frame-count cap: 1000 frames × 32KB base64 (×1.33) could otherwise pin tens of
     * MB. Once the transcript reaches this size, further frames are relayed but not recorded (transcript truncated).
     */
    static final int MAX_TRANSCRIPT_JSON_CHARS = 8 * 1024 * 1024;
    private static final int MAX_FRAME_PAYLOAD_LENGTH = 65536;

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final NettySslContextFactory nettySslContextFactory;

    public WebSocketProxyRelayHandler(Configuration configuration, MockServerLogger mockServerLogger, NettySslContextFactory nettySslContextFactory) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.nettySslContextFactory = nettySslContextFactory;
    }

    /**
     * Whether the request is an HTTP/1.1 WebSocket upgrade handshake (GET + {@code Upgrade: websocket} +
     * {@code Connection: upgrade} + a {@code Sec-WebSocket-Key}).
     */
    public static boolean isWebSocketUpgrade(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String method = request.getMethod() != null ? request.getMethod().getValue() : "GET";
        if (method != null && !"GET".equalsIgnoreCase(method)) {
            return false;
        }
        String upgrade = request.getFirstHeader("Upgrade");
        String connection = request.getFirstHeader("Connection");
        String key = request.getFirstHeader("Sec-WebSocket-Key");
        return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket")
            && connection != null && connection.toLowerCase(Locale.ROOT).contains("upgrade")
            && key != null && !key.isEmpty();
    }

    /**
     * Establish the upstream WebSocket connection and, on a successful upstream handshake, relay the 101 downstream
     * and begin bidirectional frame relay. On any failure a 502 is written to the client and both channels are closed.
     */
    public void relay(final HttpRequest request, final ChannelHandlerContext clientCtx,
                      final String upstreamHost, final int upstreamPort, final boolean tlsUpstream) {
        final Channel clientChannel = clientCtx.channel();
        // SSRF guard: apply the SAME block checks every matched-forward handler enforces
        // (forwardProxyBlockPrivateNetworks) so a WS upgrade cannot relay to loopback / link-local (169.254.169.254
        // cloud metadata) / RFC1918 targets. No-op when the feature is disabled (the default).
        try {
            org.mockserver.proxyconfiguration.InetAddressValidator.validateForwardTarget(configuration, upstreamHost);
        } catch (IllegalArgumentException e) {
            failClient(clientChannel, request, e.getMessage());
            return;
        }
        final URI uri;
        try {
            uri = buildUpstreamUri(request, upstreamHost, upstreamPort, tlsUpstream);
        } catch (Exception e) {
            failClient(clientChannel, request, "invalid upstream WebSocket URI: " + e.getMessage());
            return;
        }

        // custom headers to forward to the upstream (everything except the handshake headers Netty sets itself)
        final HttpHeaders customHeaders = new DefaultHttpHeaders();
        if (request.getHeaderList() != null) {
            for (Header header : request.getHeaderList()) {
                String name = header.getName().getValue();
                if (isManagedHandshakeHeader(name)) {
                    continue;
                }
                for (NottableString value : header.getValues()) {
                    customHeaders.add(name, value.getValue());
                }
            }
        }
        final String subprotocol = request.getFirstHeader("Sec-WebSocket-Protocol");

        final WebSocketClientHandshaker upstreamHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
            uri, WebSocketVersion.V13, subprotocol == null || subprotocol.isEmpty() ? null : subprotocol,
            true, customHeaders, MAX_FRAME_PAYLOAD_LENGTH);

        final FrameTranscript transcript = new FrameTranscript(configuration.webSocketProxyMaxRecordedFrames());

        Bootstrap bootstrap = new Bootstrap()
            .group(clientChannel.eventLoop())
            .channel(NettyTransport.socketChannelClassFor(clientChannel.eventLoop()))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, configuration.socketConnectionTimeoutInMillis().intValue())
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    if (tlsUpstream) {
                        // forwardProxyClient=true so the configured forward-proxy trust manager applies
                        // (forwardProxyTLSX509CertificatesTrustManagerType) — matching HttpClientInitializer /
                        // RelayConnectHandler. The false branch trusts only MockServer's own CA, which would fail
                        // wss to any real upstream and silently ignore the configured trust policy.
                        pipeline.addLast(nettySslContextFactory
                            .createClientSslContext(true, false)
                            .newHandler(ch.alloc(), upstreamHost, upstreamPort));
                    }
                    pipeline.addLast(new HttpClientCodec());
                    pipeline.addLast(new HttpObjectAggregator(MAX_FRAME_PAYLOAD_LENGTH));
                    pipeline.addLast(new UpstreamHandshakeHandler(request, clientCtx, upstreamHandshaker, subprotocol, transcript));
                }
            });

        bootstrap.connect(upstreamHost, upstreamPort).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                failClient(clientChannel, request, "unable to connect to upstream WebSocket server " + upstreamHost + ":" + upstreamPort
                    + (future.cause() != null ? " - " + future.cause().getMessage() : ""));
            }
        });
    }

    private URI buildUpstreamUri(HttpRequest request, String host, int port, boolean tls) {
        String scheme = tls ? "wss" : "ws";
        String path = request.getPath() != null && request.getPath().getValue() != null ? request.getPath().getValue() : "/";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        StringBuilder query = new StringBuilder();
        if (request.getQueryStringParameters() != null && !request.getQueryStringParameters().isEmpty()) {
            boolean first = true;
            for (org.mockserver.model.Parameter param : request.getQueryStringParameters().getEntries()) {
                for (NottableString value : param.getValues()) {
                    query.append(first ? '?' : '&');
                    query.append(urlEncode(param.getName().getValue())).append('=').append(urlEncode(value.getValue()));
                    first = false;
                }
            }
        }
        return URI.create(scheme + "://" + host + ":" + port + path + query);
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * Headers Netty's client handshaker sets itself; forwarding them from the client request would duplicate or
     * corrupt the upstream handshake, so they are excluded from the forwarded custom headers.
     */
    private static boolean isManagedHandshakeHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("host")
            || lower.equals("upgrade")
            || lower.equals("connection")
            || lower.equals("sec-websocket-key")
            || lower.equals("sec-websocket-version")
            || lower.equals("sec-websocket-protocol")
            || lower.equals("sec-websocket-extensions")
            || lower.equals("content-length");
    }

    private void failClient(Channel clientChannel, HttpRequest request, String message) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat("WebSocket proxy passthrough failed:{}")
                    .setArguments(message)
            );
        }
        if (clientChannel.isActive()) {
            clientChannel.writeAndFlush(response()
                .withStatusCode(HttpResponseStatus.BAD_GATEWAY.code())
                .withBody(message)
            ).addListener(ChannelFutureListener.CLOSE);
        }
    }

    // ---- upstream handshake handler ---------------------------------------------------------------------------

    /**
     * Drives the upstream client-side WebSocket handshake. On completion it relays the 101 downstream (server-side
     * handshake against the original client), installs the two frame-relay handlers, and records the upgrade exchange.
     */
    private final class UpstreamHandshakeHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

        private final HttpRequest request;
        private final ChannelHandlerContext clientCtx;
        private final WebSocketClientHandshaker handshaker;
        private final String requestedSubprotocol;
        private final FrameTranscript transcript;

        private UpstreamHandshakeHandler(HttpRequest request, ChannelHandlerContext clientCtx,
                                         WebSocketClientHandshaker handshaker, String requestedSubprotocol,
                                         FrameTranscript transcript) {
            this.request = request;
            this.clientCtx = clientCtx;
            this.handshaker = handshaker;
            this.requestedSubprotocol = requestedSubprotocol;
            this.transcript = transcript;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // upstream closed before the handshake completed
            if (!handshaker.isHandshakeComplete()) {
                failClient(clientCtx.channel(), request, "upstream WebSocket connection closed before handshake completed");
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
            if (handshaker.isHandshakeComplete()) {
                return;
            }
            final Channel upstreamChannel = ctx.channel();
            final String negotiatedSubprotocol;
            try {
                handshaker.finishHandshake(upstreamChannel, msg);
                negotiatedSubprotocol = handshaker.actualSubprotocol();
            } catch (WebSocketHandshakeException e) {
                failClient(clientCtx.channel(), request, "upstream WebSocket handshake failed: " + e.getMessage());
                upstreamChannel.close();
                return;
            }
            // upstream pipeline now speaks WebSocket frames; remove this handshake handler and relay upstream->client
            upstreamChannel.pipeline().remove(this);
            maybeAddIdleHandler(upstreamChannel.pipeline());
            upstreamChannel.pipeline().addLast(new FrameRelayHandler(
                clientCtx.channel(), upstreamChannel, FrameDirection.UPSTREAM_TO_CLIENT, transcript, request));

            // complete the downstream (server-side) handshake to relay the 101 to the original client
            completeDownstreamHandshake(negotiatedSubprotocol, upstreamChannel);
        }

        private void completeDownstreamHandshake(String negotiatedSubprotocol, Channel upstreamChannel) {
            final Channel clientChannel = clientCtx.channel();
            FullHttpRequest nettyRequest = buildNettyUpgradeRequest(request);
            String host = request.getFirstHeader("Host");
            boolean tlsDownstream = clientChannel.pipeline().get(io.netty.handler.ssl.SslHandler.class) != null;
            String wsScheme = tlsDownstream ? "wss" : "ws";
            String wsUrl = wsScheme + "://" + (host != null ? host : "localhost") + request.getPath().getValue();

            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                wsUrl, negotiatedSubprotocol, true, MAX_FRAME_PAYLOAD_LENGTH);
            final WebSocketServerHandshaker serverHandshaker = wsFactory.newHandshaker(nettyRequest);
            if (serverHandshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(clientChannel);
                nettyRequest.release();
                upstreamChannel.close();
                return;
            }
            serverHandshaker.handshake(clientChannel, nettyRequest).addListener((ChannelFutureListener) future -> {
                try {
                    if (future.isSuccess()) {
                        removeHttpServerHandlers(clientCtx);
                        maybeAddIdleHandler(clientChannel.pipeline());
                        clientChannel.pipeline().addLast(new FrameRelayHandler(
                            upstreamChannel, clientChannel, FrameDirection.CLIENT_TO_UPSTREAM, transcript, request));
                        // ensure both halves tear down together and the transcript is flushed exactly once
                        clientChannel.closeFuture().addListener(f -> closeQuietly(upstreamChannel));
                        upstreamChannel.closeFuture().addListener(f -> closeQuietly(clientChannel));
                        transcript.flushOnClose(clientChannel, upstreamChannel, () ->
                            recordUpgrade(request, negotiatedSubprotocol, transcript));

                        CrossProtocolEventBus.getInstance().fire(
                            CrossProtocolTrigger.WEBSOCKET_CONNECT,
                            request.getPath() != null ? request.getPath().getValue() : "/");

                        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setMessageFormat("relaying WebSocket passthrough for request:{}to upstream:{}")
                                    .setArguments(request, upstreamChannel.remoteAddress())
                            );
                        }
                    } else {
                        failClient(clientChannel, request, "downstream WebSocket handshake failed: "
                            + (future.cause() != null ? future.cause().getMessage() : "unknown"));
                        upstreamChannel.close();
                    }
                } finally {
                    nettyRequest.release();
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!handshaker.isHandshakeComplete()) {
                failClient(clientCtx.channel(), request, "upstream WebSocket error: " + cause.getMessage());
            }
            ctx.close();
        }
    }

    // ---- bidirectional frame relay ----------------------------------------------------------------------------

    private enum FrameDirection {
        CLIENT_TO_UPSTREAM, UPSTREAM_TO_CLIENT
    }

    /**
     * Relays WebSocket frames from the channel it is installed on to the peer channel, recording each frame in the
     * shared transcript. A {@link CloseWebSocketFrame} is relayed and then both channels are closed.
     */
    private final class FrameRelayHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        private final Channel peerChannel;
        private final Channel ownChannel;
        private final FrameDirection direction;
        private final FrameTranscript transcript;
        private final HttpRequest request;

        private FrameRelayHandler(Channel peerChannel, Channel ownChannel, FrameDirection direction,
                                  FrameTranscript transcript, HttpRequest request) {
            this.peerChannel = peerChannel;
            this.ownChannel = ownChannel;
            this.direction = direction;
            this.transcript = transcript;
            this.request = request;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            transcript.record(direction, frame);
            WebSocketFrame relayed = cloneFrame(frame);
            if (peerChannel.isActive()) {
                peerChannel.writeAndFlush(relayed);
            } else {
                relayed.release();
            }
            if (frame instanceof CloseWebSocketFrame) {
                closeQuietly(peerChannel);
                closeQuietly(ownChannel);
            }
        }

        /**
         * Backpressure: when THIS channel's outbound buffer saturates (the peer that feeds it via its own relay
         * handler is producing faster than this channel can drain), pause reads on that source channel — and resume
         * when it drains. This is the standard Netty proxy pattern; without it a slow peer would grow the other side's
         * {@code ChannelOutboundBuffer} without bound. Both relay halves share the client event loop, so mutating the
         * peer channel config here is thread-safe.
         */
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            peerChannel.config().setAutoRead(ctx.channel().isWritable());
            ctx.fireChannelWritabilityChanged();
        }

        /**
         * Idle relay reaping (opt-in): when {@code webSocketProxyIdleTimeoutSeconds > 0}, an {@link IdleStateHandler}
         * on each relay channel fires {@code ALL_IDLE} once neither side has sent a frame for the configured period,
         * and the relay is torn down (which flushes the transcript). Default off (0) — long-lived idle WebSocket
         * connections are left to TCP keep-alive and peer-close propagation.
         */
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof io.netty.handler.timeout.IdleStateEvent
                && ((io.netty.handler.timeout.IdleStateEvent) evt).state() == io.netty.handler.timeout.IdleState.ALL_IDLE) {
                if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.DEBUG)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setMessageFormat("closing idle WebSocket passthrough relay ({})")
                            .setArguments(direction)
                    );
                }
                closeQuietly(peerChannel);
                closeQuietly(ownChannel);
                return;
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            closeQuietly(peerChannel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("WebSocket relay error ({}):{}")
                        .setArguments(direction, cause.getMessage())
                );
            }
            closeQuietly(peerChannel);
            ctx.close();
        }
    }

    private static WebSocketFrame cloneFrame(WebSocketFrame frame) {
        io.netty.buffer.ByteBuf content = frame.content().retainedDuplicate();
        boolean fin = frame.isFinalFragment();
        int rsv = frame.rsv();
        if (frame instanceof TextWebSocketFrame) {
            return new TextWebSocketFrame(fin, rsv, content);
        } else if (frame instanceof BinaryWebSocketFrame) {
            return new BinaryWebSocketFrame(fin, rsv, content);
        } else if (frame instanceof PingWebSocketFrame) {
            return new PingWebSocketFrame(fin, rsv, content);
        } else if (frame instanceof PongWebSocketFrame) {
            return new PongWebSocketFrame(fin, rsv, content);
        } else if (frame instanceof CloseWebSocketFrame) {
            return new CloseWebSocketFrame(fin, rsv, content);
        } else {
            return new ContinuationWebSocketFrame(fin, rsv, content);
        }
    }

    private static void closeQuietly(Channel channel) {
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }

    /**
     * Install an {@link io.netty.handler.timeout.IdleStateHandler} that fires {@code ALL_IDLE} (neither side has sent
     * a frame) after {@code webSocketProxyIdleTimeoutSeconds}, so {@code FrameRelayHandler#userEventTriggered} can tear
     * the relay down. Default off (0/negative) — a no-op, leaving idle WebSocket connections to TCP keep-alive.
     */
    private void maybeAddIdleHandler(ChannelPipeline pipeline) {
        int idleSeconds = configuration.webSocketProxyIdleTimeoutSeconds();
        if (idleSeconds > 0) {
            pipeline.addLast(new io.netty.handler.timeout.IdleStateHandler(0, 0, idleSeconds));
        }
    }

    // ---- pipeline surgery -------------------------------------------------------------------------------------

    /**
     * Strip the HTTP server handlers from the client pipeline after the downstream WebSocket handshake, so the
     * client channel now carries only the WebSocket frame codec (installed by the server handshaker) plus the relay
     * handler. Mirrors {@code HttpWebSocketResponseActionHandler.removePipelineHandlers} but removes by simple class
     * name so this core class does not depend on the netty-module handler types.
     */
    // package-private for unit testing of the pipeline-stripping logic
    static void removeHttpServerHandlers(ChannelHandlerContext ctx) {
        removeBySimpleName(ctx, "MockServerHttpServerCodec");
        removeBySimpleName(ctx, "HttpRequestHandler");
        removeBySimpleName(ctx, "HttpContentLengthRemover");
        removeBySimpleName(ctx, "EarlyMatchingHandler");
        removeBySimpleName(ctx, "CallbackWebSocketServerHandler");
        removeBySimpleName(ctx, "DashboardWebSocketHandler");
        removeBySimpleName(ctx, "McpStreamableHttpHandler");
    }

    private static void removeBySimpleName(ChannelHandlerContext ctx, String simpleName) {
        try {
            for (java.util.Map.Entry<String, ChannelHandler> entry : ctx.pipeline()) {
                if (simpleName.equals(entry.getValue().getClass().getSimpleName())) {
                    ctx.pipeline().remove(entry.getKey());
                }
            }
        } catch (Exception ignored) {
            // handler already removed or pipeline mutated concurrently
        }
    }

    private static FullHttpRequest buildNettyUpgradeRequest(HttpRequest request) {
        String uri = request.getPath().getValue();
        if (request.getQueryStringParameters() != null && !request.getQueryStringParameters().isEmpty()) {
            StringBuilder qs = new StringBuilder("?");
            boolean first = true;
            for (org.mockserver.model.Parameter param : request.getQueryStringParameters().getEntries()) {
                for (NottableString value : param.getValues()) {
                    if (!first) {
                        qs.append("&");
                    }
                    qs.append(urlEncode(param.getName().getValue())).append("=").append(urlEncode(value.getValue()));
                    first = false;
                }
            }
            uri = uri + qs;
        }
        String method = request.getMethod() != null ? request.getMethod().getValue() : "GET";
        DefaultFullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri);
        if (request.getHeaderList() != null) {
            for (Header header : request.getHeaderList()) {
                for (NottableString value : header.getValues()) {
                    nettyRequest.headers().add(header.getName().getValue(), value.getValue());
                }
            }
        }
        if (!nettyRequest.headers().contains(HttpHeaderNames.HOST)) {
            nettyRequest.headers().set(HttpHeaderNames.HOST, "localhost");
        }
        return nettyRequest;
    }

    // ---- recording --------------------------------------------------------------------------------------------

    private void recordUpgrade(HttpRequest request, String negotiatedSubprotocol, FrameTranscript transcript) {
        HttpResponse logResponse = response()
            .withStatusCode(HttpResponseStatus.SWITCHING_PROTOCOLS.code())
            .withReasonPhrase("Switching Protocols")
            .withHeader("Upgrade", "websocket")
            .withHeader("Connection", "Upgrade")
            .withHeader("x-mockserver-websocket-frames", String.valueOf(transcript.recordedCount()))
            .withHeader("x-mockserver-websocket-transcript-truncated", String.valueOf(transcript.isTruncated()))
            .withBody(transcript.toJson());
        if (negotiatedSubprotocol != null && !negotiatedSubprotocol.isEmpty()) {
            logResponse.withHeader("Sec-WebSocket-Protocol", negotiatedSubprotocol);
        }
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setCorrelationId(request.getLogCorrelationId())
                .setHttpRequest(request)
                .setHttpResponse(logResponse)
                .setExpectation(request, logResponse)
                .setMessageFormat("relayed WebSocket passthrough with {} frame(s) for forwarded upgrade request:{}")
                .setArguments(transcript.recordedCount(), request)
        );
    }

    /**
     * Bounded, per-connection capture of relayed WebSocket frames. Records at most {@code maxFrames} frames; each
     * frame payload is truncated to {@link #MAX_RECORDED_FRAME_BYTES}. Text frame payloads are recorded as UTF-8
     * strings, all other opcodes as base64. The transcript is serialised to JSON and flushed to the event log
     * exactly once when the connection closes.
     */
    private static final class FrameTranscript {

        private final int maxFrames;
        private final StringBuilder json = new StringBuilder("[");
        private final AtomicInteger recorded = new AtomicInteger(0);
        private final AtomicBoolean truncated = new AtomicBoolean(false);
        private final AtomicBoolean flushed = new AtomicBoolean(false);

        private FrameTranscript(int maxFrames) {
            this.maxFrames = maxFrames;
        }

        synchronized void record(FrameDirection direction, WebSocketFrame frame) {
            if (maxFrames <= 0) {
                // frame recording disabled (webSocketProxyMaxRecordedFrames=0) — this is NOT truncation; the
                // upgrade handshake is still recorded with an honest (false) truncated flag and a 0 frame count.
                return;
            }
            if (recorded.get() >= maxFrames || json.length() >= MAX_TRANSCRIPT_JSON_CHARS) {
                truncated.set(true);
                return;
            }
            io.netty.buffer.ByteBuf content = frame.content();
            int available = content.readableBytes();
            int len = Math.min(available, MAX_RECORDED_FRAME_BYTES);
            byte[] bytes = new byte[len];
            content.getBytes(content.readerIndex(), bytes);
            boolean framePayloadTruncated = available > len;

            String opcode = opcodeOf(frame);
            boolean isText = frame instanceof TextWebSocketFrame;
            String payloadField;
            String payloadValue;
            if (isText) {
                payloadField = "text";
                payloadValue = new String(bytes, StandardCharsets.UTF_8);
            } else {
                payloadField = "base64";
                payloadValue = Base64.getEncoder().encodeToString(bytes);
            }

            if (recorded.get() > 0) {
                json.append(',');
            }
            json.append("{\"direction\":\"")
                .append(direction == FrameDirection.CLIENT_TO_UPSTREAM ? "CLIENT_TO_UPSTREAM" : "UPSTREAM_TO_CLIENT")
                .append("\",\"opcode\":\"").append(opcode)
                .append("\",\"").append(payloadField).append("\":\"").append(escapeJson(payloadValue))
                .append("\",\"length\":").append(available)
                .append(",\"truncated\":").append(framePayloadTruncated)
                .append('}');
            recorded.incrementAndGet();
        }

        int recordedCount() {
            return recorded.get();
        }

        boolean isTruncated() {
            return truncated.get();
        }

        synchronized String toJson() {
            return json.toString() + "]";
        }

        /**
         * Register close listeners on both channels so that when the FIRST of them closes, the recording callback runs
         * exactly once (guarded by an {@link AtomicBoolean}).
         */
        void flushOnClose(Channel a, Channel b, Runnable flush) {
            ChannelFutureListener listener = f -> {
                if (flushed.compareAndSet(false, true)) {
                    flush.run();
                }
            };
            a.closeFuture().addListener(listener);
            b.closeFuture().addListener(listener);
        }

        private static String opcodeOf(WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame) {
                return "TEXT";
            } else if (frame instanceof BinaryWebSocketFrame) {
                return "BINARY";
            } else if (frame instanceof PingWebSocketFrame) {
                return "PING";
            } else if (frame instanceof PongWebSocketFrame) {
                return "PONG";
            } else if (frame instanceof CloseWebSocketFrame) {
                return "CLOSE";
            } else {
                return "CONTINUATION";
            }
        }

        private static String escapeJson(String value) {
            StringBuilder sb = new StringBuilder(value.length() + 16);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.toString();
        }
    }
}
