package org.mockserver.httpclient;

import com.google.common.collect.ImmutableMap;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AttributeKey;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.configuration.Configuration;
import org.mockserver.filters.HopByHopHeaderFilter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.*;
import org.mockserver.proxyconfiguration.NoProxyHostsUtils;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.socket.NettyTransport;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.mockserver.model.HttpResponse.response;

public class NettyHttpClient {

    static final AttributeKey<Boolean> SECURE = AttributeKey.valueOf("SECURE");
    static final AttributeKey<InetSocketAddress> REMOTE_SOCKET = AttributeKey.valueOf("REMOTE_SOCKET");
    static final AttributeKey<CompletableFuture<Message>> RESPONSE_FUTURE = AttributeKey.valueOf("RESPONSE_FUTURE");
    static final AttributeKey<Boolean> ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE = AttributeKey.valueOf("ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE");
    static final AttributeKey<Boolean> DISABLE_RESPONSE_STREAMING = AttributeKey.valueOf("DISABLE_RESPONSE_STREAMING");
    // Set when the OUTGOING request asks for a streamed response, so the response is relayed
    // incrementally even if the upstream omits Content-Type: text/event-stream. Read by
    // StreamingAwareHttpObjectAggregator (same interned key name).
    static final AttributeKey<Boolean> EXPECT_STREAMING_RESPONSE = AttributeKey.valueOf("EXPECT_STREAMING_RESPONSE");
    // A JSON request body that turns on streaming, e.g. {"stream": true} (OpenAI/Anthropic/Codex).
    private static final java.util.regex.Pattern STREAM_TRUE_IN_BODY =
        java.util.regex.Pattern.compile("\"stream\"\\s*:\\s*true");
    static final AttributeKey<AtomicLong> FIRST_BYTE_MILLIS = TimeToFirstByteHandler.FIRST_BYTE_MILLIS;
    /**
     * When set on a channel, {@link HttpClientHandler} returns the channel to this pool (keyed by
     * {@link #POOL_KEY}) after a reusable HTTP/1.1 keep-alive response instead of closing it.
     */
    static final AttributeKey<HttpForwardConnectionPool> CONNECTION_POOL = AttributeKey.valueOf("CONNECTION_POOL");
    static final AttributeKey<String> POOL_KEY = AttributeKey.valueOf("POOL_KEY");
    static final AttributeKey<io.netty.util.concurrent.ScheduledFuture<?>> POOL_IDLE_EVICTION = AttributeKey.valueOf("POOL_IDLE_EVICTION");
    private static final HopByHopHeaderFilter hopByHopHeaderFilter = new HopByHopHeaderFilter();
    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    // Supplies the outbound event-loop group lazily. For the forward/proxy client this is the
    // LifeCycle accessor (a method reference to getForwardClientEventLoopGroup()), so the disjoint
    // forward-client group is only created on the FIRST forward/proxy request — a pure-mock
    // deployment that never forwards never pays for it. May be null for callers (chiefly tests) that
    // never forward; eventLoopGroup() then returns null, exactly matching the historical behaviour.
    private final Supplier<EventLoopGroup> eventLoopGroupSupplier;
    private final Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations;
    private final boolean forwardProxyClient;
    private final NettySslContextFactory nettySslContextFactory;
    private final HttpForwardConnectionPool connectionPool;

    public NettyHttpClient(Configuration configuration, MockServerLogger mockServerLogger, EventLoopGroup eventLoopGroup, List<ProxyConfiguration> proxyConfigurations, boolean forwardProxyClient) {
        this(configuration, mockServerLogger, eventLoopGroup, proxyConfigurations, forwardProxyClient, new NettySslContextFactory(configuration, mockServerLogger, false));
    }

    public NettyHttpClient(Configuration configuration, MockServerLogger mockServerLogger, EventLoopGroup eventLoopGroup, List<ProxyConfiguration> proxyConfigurations, boolean forwardProxyClient, NettySslContextFactory nettySslContextFactory) {
        // Wrap a concrete group as a constant supplier so all call paths share the lazy-resolution
        // seam; a null group stays null (eventLoopGroup() returns null) preserving prior behaviour.
        this(configuration, mockServerLogger, eventLoopGroup == null ? (Supplier<EventLoopGroup>) null : () -> eventLoopGroup, proxyConfigurations, forwardProxyClient, nettySslContextFactory);
    }

    /**
     * Supplier-based constructor: the outbound event-loop group is resolved lazily on first use
     * ({@link #eventLoopGroup()}) rather than captured eagerly at construction. The forward/proxy
     * client passes the {@code LifeCycle} accessor here so the disjoint forward-client group is only
     * created the first time MockServer actually forwards/proxies.
     */
    public NettyHttpClient(Configuration configuration, MockServerLogger mockServerLogger, Supplier<EventLoopGroup> eventLoopGroupSupplier, List<ProxyConfiguration> proxyConfigurations, boolean forwardProxyClient, NettySslContextFactory nettySslContextFactory) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.eventLoopGroupSupplier = eventLoopGroupSupplier;
        this.proxyConfigurations = proxyConfigurations != null ? proxyConfigurations.stream().collect(Collectors.toMap(ProxyConfiguration::getType, proxyConfiguration -> proxyConfiguration)) : ImmutableMap.of();
        this.forwardProxyClient = forwardProxyClient;
        this.nettySslContextFactory = nettySslContextFactory;
        this.connectionPool = Boolean.TRUE.equals(configuration.forwardConnectionPoolEnabled())
            ? new HttpForwardConnectionPool(
                configuration.forwardConnectionPoolMaxIdlePerKey(),
                configuration.forwardConnectionPoolIdleTimeoutMillis(),
                Boolean.TRUE.equals(configuration.forwardConnectionPoolKeepAlive()),
                configuration.forwardConnectionPoolMaxTotalPerKey())
            : null;
    }

    /**
     * Resolves the outbound event-loop group, triggering its (lazy) creation on the FIRST call for a
     * forward/proxy client. Returns {@code null} when no supplier was provided (callers that never
     * forward), matching the historical behaviour where the group field could be {@code null}. The
     * {@code LifeCycle} accessor memoises the group, so repeated calls return the same instance.
     */
    private EventLoopGroup eventLoopGroup() {
        return eventLoopGroupSupplier == null ? null : eventLoopGroupSupplier.get();
    }

    public CompletableFuture<HttpResponse> sendRequest(final HttpRequest httpRequest) throws SocketConnectionException {
        return sendRequest(httpRequest, httpRequest.socketAddressFromHostHeader());
    }

    /**
     * Whether the OUTGOING request asks for a streamed (Server-Sent Events style) response, so
     * the upstream response should be relayed to the proxy client incrementally even if it omits
     * {@code Content-Type: text/event-stream}. Some streaming backends do — notably the OpenAI
     * Codex backend used by the opencode CLI ({@code chatgpt.com/backend-api/codex/responses}),
     * whose SSE response carries no content-type at all; without this hint MockServer would
     * aggregate the whole 10–30s stream and the client would time out waiting for response headers.
     * Detected from the client's own intent: an {@code Accept: text/event-stream} header, or a JSON
     * request body containing {@code "stream": true}.
     */
    static boolean requestExpectsStreamingResponse(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String accept = request.getFirstHeader("accept");
        if (accept != null && accept.toLowerCase().contains("text/event-stream")) {
            return true;
        }
        String contentType = request.getFirstHeader("content-type");
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            String body = request.getBodyAsString();
            if (body != null && !body.isEmpty() && STREAM_TRUE_IN_BODY.matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    public CompletableFuture<HttpResponse> sendRequest(final HttpRequest httpRequest, @Nullable InetSocketAddress remoteAddress) throws SocketConnectionException {
        return sendRequest(httpRequest, remoteAddress, configuration.socketConnectionTimeoutInMillis());
    }

    public CompletableFuture<HttpResponse> sendRequest(final HttpRequest httpRequest, @Nullable InetSocketAddress remoteAddress, Long connectionTimeoutMillis) throws SocketConnectionException {
        return sendRequest(httpRequest, remoteAddress, connectionTimeoutMillis, false);
    }

    public CompletableFuture<HttpResponse> sendRequest(final HttpRequest httpRequest, @Nullable InetSocketAddress remoteAddress, Long connectionTimeoutMillis, boolean disableStreaming) throws SocketConnectionException {
        // Resolve (lazily creating on first forward) once per request so the whole request uses one group.
        final EventLoopGroup eventLoopGroup = eventLoopGroup();
        if (!eventLoopGroup.isShuttingDown()) {
            if (proxyConfigurations != null && !Boolean.TRUE.equals(httpRequest.isSecure())
                && proxyConfigurations.containsKey(ProxyConfiguration.Type.HTTP)
                && isHostNotOnNoProxyHostList(remoteAddress)) {
                ProxyConfiguration proxyConfiguration = proxyConfigurations.get(ProxyConfiguration.Type.HTTP);
                remoteAddress = proxyConfiguration.getProxyAddress();
                proxyConfiguration.addProxyAuthenticationHeader(httpRequest);
            } else if (remoteAddress == null) {
                remoteAddress = httpRequest.socketAddressFromHostHeader();
            }
            if (Protocol.HTTP_3.equals(httpRequest.getProtocol())) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("HTTP3 (QUIC) cannot be forwarded over a TCP connection so protocol will be negotiated by ALPN (HTTP1 or HTTP2)")
                );
                httpRequest.withProtocol(null);
            }
            if (Protocol.HTTP_2.equals(httpRequest.getProtocol()) && !Boolean.TRUE.equals(httpRequest.isSecure())) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("HTTP2 requires ALPN but request is not secure (i.e. TLS) so protocol changed to HTTP1")
                );
                httpRequest.withProtocol(Protocol.HTTP_1_1);
            }

            final CompletableFuture<HttpResponse> httpResponseFuture = new CompletableFuture<>();
            final CompletableFuture<Message> responseFuture = new CompletableFuture<>();
            final Protocol httpProtocol = httpRequest.getProtocol() != null ? httpRequest.getProtocol() : Protocol.HTTP_1_1;

            final long requestStartedMillis = System.currentTimeMillis();
            final AtomicLong connectionEstablishedMillis = new AtomicLong();
            final AtomicLong firstByteMillis = new AtomicLong();

            final boolean secure = httpRequest.isSecure() != null && httpRequest.isSecure();
            // Relay the response as a stream (not aggregated) when the client asked for one, so a
            // streaming upstream that omits Content-Type: text/event-stream (e.g. opencode's Codex
            // backend) is not buffered to completion before its headers reach the client.
            final boolean expectStreaming = !disableStreaming && requestExpectsStreamingResponse(httpRequest);
            final InetSocketAddress effectiveRemoteAddress = remoteAddress;
            // Only plain HTTP/1.1 keep-alive connections are pooled. HTTP/2, HTTP/3 (multiplexed
            // differently), binary forwarding, and any proxy-tunnelled connection bypass the pool
            // and use a fresh connection. Streaming responses are excluded automatically because the
            // streaming relay handler removes HttpClientHandler before any pooling return path runs.
            final boolean poolable = connectionPool != null
                && Protocol.HTTP_1_1.equals(httpProtocol)
                && (proxyConfigurations == null || proxyConfigurations.isEmpty());
            final String poolKey = poolable ? HttpForwardConnectionPool.keyFor(effectiveRemoteAddress, secure) : null;

            Channel pooledChannel = poolKey != null ? connectionPool.acquire(poolKey) : null;
            if (pooledChannel != null) {
                // Reuse an idle keep-alive connection: re-arm per-request channel attributes and
                // dispatch directly on the channel's event loop (pipeline is already configured).
                connectionEstablishedMillis.set(requestStartedMillis);
                final Channel reused = pooledChannel;
                reused.attr(RESPONSE_FUTURE).set(responseFuture);
                reused.attr(ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE).set(true);
                reused.attr(FIRST_BYTE_MILLIS).set(firstByteMillis);
                reused.attr(DISABLE_RESPONSE_STREAMING).set(disableStreaming ? Boolean.TRUE : null);
                reused.attr(EXPECT_STREAMING_RESPONSE).set(expectStreaming ? Boolean.TRUE : null);
                reused.eventLoop().execute(() -> {
                    if (reused.isActive()) {
                        // Guard this in-flight request: a reused idle connection whose upstream has
                        // silently gone away would otherwise hang with no read timeout (pooled channels
                        // carry none while idle). Removed again on return to the pool.
                        armPooledInFlightReadTimeout(reused);
                        reused.writeAndFlush(httpRequest).addListener((ChannelFutureListener) writeFuture -> {
                            if (!writeFuture.isSuccess()) {
                                responseFuture.completeExceptionally(writeFuture.cause());
                                reused.close();
                            }
                        });
                    } else {
                        // Raced with a server-side close between acquire and dispatch — fall back to a
                        // fresh connection. Safe to call from the event loop: bootstrap.connect() is non-blocking.
                        reused.close();
                        connectFresh(httpRequest, effectiveRemoteAddress, connectionTimeoutMillis, disableStreaming, secure, httpProtocol, poolKey, responseFuture, firstByteMillis, connectionEstablishedMillis, httpResponseFuture);
                    }
                });
            } else {
                connectFresh(httpRequest, remoteAddress, connectionTimeoutMillis, disableStreaming, secure, httpProtocol, poolKey, responseFuture, firstByteMillis, connectionEstablishedMillis, httpResponseFuture);
            }

            responseFuture
                .whenComplete((message, throwable) -> {
                    if (throwable == null) {
                        long responseReceivedMillis = System.currentTimeMillis();
                        long firstByte = firstByteMillis.get();
                        long totalTime = responseReceivedMillis - requestStartedMillis;
                        Timing timing = Timing.timing()
                            .withRequestStartedMillis(requestStartedMillis)
                            .withConnectionEstablishedMillis(connectionEstablishedMillis.get())
                            .withResponseReceivedMillis(responseReceivedMillis)
                            .withConnectionTimeInMillis(connectionEstablishedMillis.get() - requestStartedMillis)
                            .withTimeToFirstByteInMillis(firstByte > 0 ? firstByte - requestStartedMillis : null)
                            .withTotalTimeInMillis(totalTime);
                        // Slow-request flagging
                        long threshold = configuration.slowRequestThresholdMillis();
                        if (threshold > 0 && totalTime > threshold) {
                            Metrics.incrementSlowRequestTotal();
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.WARN)
                                    .setMessageFormat("slow forwarded request {} took {}ms (threshold {}ms)")
                                    .setArguments(
                                        httpRequest.getMethod("") + " " + httpRequest.getPath(),
                                        totalTime,
                                        threshold
                                    )
                            );
                        }
                        if (message != null) {
                            HttpResponse response = (HttpResponse) message;
                            response.withTiming(timing);
                            if (forwardProxyClient) {
                                httpResponseFuture.complete(hopByHopHeaderFilter.onResponse(response));
                            } else {
                                httpResponseFuture.complete(response);
                            }
                        } else {
                            httpResponseFuture.complete(response().withTiming(timing));
                        }
                    } else {
                        httpResponseFuture.completeExceptionally(throwable);
                    }
                });

            return httpResponseFuture;
        } else {
            throw new IllegalStateException("Request sent after client has been stopped - the event loop has been shutdown so it is not possible to send a request");
        }
    }

    /**
     * Opens a fresh upstream connection and dispatches the request. When {@code poolKey} is non-null
     * the channel is marked (via {@link #CONNECTION_POOL}/{@link #POOL_KEY}) so that, after a
     * reusable HTTP/1.1 keep-alive response, {@link HttpClientHandler} returns it to the pool instead
     * of closing it. This is the only connection path when pooling is disabled, so that path remains
     * byte-identical to the historical behaviour.
     */
    private void connectFresh(HttpRequest httpRequest, InetSocketAddress remoteAddress, Long connectionTimeoutMillis, boolean disableStreaming, boolean secure, Protocol httpProtocol, String poolKey, CompletableFuture<Message> responseFuture, AtomicLong firstByteMillis, AtomicLong connectionEstablishedMillis, CompletableFuture<HttpResponse> httpResponseFuture) {
        final HttpClientInitializer clientInitializer = new HttpClientInitializer(proxyConfigurations, mockServerLogger, forwardProxyClient, nettySslContextFactory, httpProtocol, configuration);
        final EventLoopGroup eventLoopGroup = eventLoopGroup();
        Bootstrap bootstrap = new Bootstrap()
            .group(eventLoopGroup)
            .channel(NettyTransport.socketChannelClassFor(eventLoopGroup))
            .option(ChannelOption.AUTO_READ, true)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(8 * 1024, 32 * 1024))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeoutMillis != null ? (int) Math.min(connectionTimeoutMillis, Integer.MAX_VALUE) : null)
            .attr(SECURE, secure)
            .attr(REMOTE_SOCKET, remoteAddress)
            .attr(RESPONSE_FUTURE, responseFuture)
            .attr(ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE, true)
            .attr(FIRST_BYTE_MILLIS, firstByteMillis)
            .handler(clientInitializer);
        applyForwardSocketKeepAlive(bootstrap);
        if (disableStreaming) {
            bootstrap.attr(DISABLE_RESPONSE_STREAMING, true);
        }
        if (!disableStreaming && requestExpectsStreamingResponse(httpRequest)) {
            bootstrap.attr(EXPECT_STREAMING_RESPONSE, true);
        }
        if (poolKey != null) {
            // Mark the fresh channel so HttpClientHandler returns it to the pool after a reusable
            // keep-alive response instead of closing it.
            bootstrap.attr(CONNECTION_POOL, connectionPool);
            bootstrap.attr(POOL_KEY, poolKey);
        }
        bootstrap.connect(remoteAddress)
            .addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    connectionEstablishedMillis.set(System.currentTimeMillis());
                    clientInitializer.whenComplete((protocol, throwable) -> {
                        if (throwable != null) {
                            httpResponseFuture.completeExceptionally(throwable);
                        } else {
                            // A fresh POOLED channel skipped the build-time read timeout; arm it for this
                            // first in-flight request so a connected-but-silent upstream cannot hang it.
                            // (A non-pooled fresh channel already has its build-time read timeout, so this
                            // is a no-op for it.) Removed again on return to the pool.
                            armPooledInFlightReadTimeout(future.channel());
                            future.channel().writeAndFlush(httpRequest);
                        }
                    });
                } else {
                    httpResponseFuture.completeExceptionally(future.cause());
                }
            });
    }

    /**
     * Arm an in-flight read timeout on a POOLED channel for the duration of one request.
     * <p>
     * Non-pooled channels get their {@link ReadTimeoutHandler} at pipeline-build time
     * ({@link HttpClientInitializer#addReadTimeoutHandlerIfNotPooled}); pooled channels deliberately do
     * NOT, because a blanket read timeout would fire while the connection sits idle in the pool between
     * requests and tear down a healthy keep-alive connection. But a pooled channel with a request IN
     * FLIGHT (a reused connection, or a fresh pooled channel's first request) was left unguarded — a
     * stalled upstream that connects/keep-alives but never sends the response would hang the request
     * future until {@code maxFutureTimeoutInMillis} at best, or indefinitely. We therefore arm the read
     * timeout just before dispatching a request on a pooled channel and remove it again when the channel
     * is returned to the pool ({@link HttpClientHandler#tryReturnToPool}); a streaming response removes
     * it earlier ({@link org.mockserver.codec.StreamingAwareHttpObjectAggregator}, which strips any
     * {@link ReadTimeoutHandler} by type when it switches to streaming, so a long inter-chunk pause is
     * not mistaken for a stall). No-op for non-pooled channels (already guarded) and when the timeout is
     * disabled or already armed.
     */
    private void armPooledInFlightReadTimeout(Channel channel) {
        if (configuration == null || channel.attr(CONNECTION_POOL).get() == null) {
            return;
        }
        Long readTimeoutMillis = configuration.maxSocketTimeoutInMillis();
        if (readTimeoutMillis != null && readTimeoutMillis > 0 && channel.pipeline().get(ReadTimeoutHandler.class) == null) {
            channel.pipeline().addFirst("pooledInFlightReadTimeout", new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * Applies tuned TCP keepalive to a forward/proxy client bootstrap, when enabled (default on via
     * {@code forwardSocketKeepAlive}). This is robustness hardening that COMPLEMENTS — not replaces —
     * the pool's existing {@code isActive()}/{@code closeFuture}/idle-reaper defences: it lets the OS
     * detect dead or half-open upstream connections faster (most valuable during active/long-lived or
     * streaming requests, and for keep-warm users who raise {@code forwardConnectionPoolIdleTimeoutMillis}
     * above the keepalive idle so the idle reaper no longer pre-empts NAT/firewall mapping drops) and
     * keeps NAT/firewall mappings warm.
     * <p>
     * {@link ChannelOption#SO_KEEPALIVE} alone uses the OS default idle (~2h on Linux), which is useless
     * for NAT, so when the native epoll transport is in use the per-connection keepalive timers are also
     * tuned via {@code EpollChannelOption.TCP_KEEPIDLE}/{@code TCP_KEEPINTVL}/{@code TCP_KEEPCNT} (target
     * ~1–2 min dead-peer detection). Epoll is detected by reusing the codebase's existing group-derived
     * transport selection ({@link NettyTransport#socketChannelClassFor(EventLoopGroup)}), so it always
     * matches the channel class the bootstrap actually uses — including graceful epoll→NIO fallback. On
     * the NIO transport (macOS/Windows, or {@code useNativeTransport=false}) only SO_KEEPALIVE is set;
     * interval tuning requires epoll. The epoll option classes are referenced only inside a guarded
     * helper so this never hard-loads native classes on a non-epoll platform.
     */
    private void applyForwardSocketKeepAlive(Bootstrap bootstrap) {
        applyForwardSocketKeepAlive(
            bootstrap,
            eventLoopGroup(),
            Boolean.TRUE.equals(configuration.forwardSocketKeepAlive()),
            configuration.forwardSocketKeepAliveIdleSeconds(),
            configuration.forwardSocketKeepAliveIntervalSeconds(),
            configuration.forwardSocketKeepAliveCount(),
            mockServerLogger
        );
    }

    /**
     * Package-private seam (so it can be unit-tested deterministically against a constructed
     * {@link Bootstrap} on any platform). When {@code enabled}, sets {@link ChannelOption#SO_KEEPALIVE}
     * and, only when the group selects the native epoll transport, the tuned epoll keepalive timers.
     * Epoll is detected by reusing the codebase's group-derived selection
     * ({@link NettyTransport#socketChannelClassFor(EventLoopGroup)}) so it always matches the channel
     * class the bootstrap actually uses (including graceful epoll→NIO fallback). Values are clamped to
     * at least 1. No-op when disabled, so the historical "no SO_KEEPALIVE" behaviour is exactly
     * restored by {@code forwardSocketKeepAlive=false}.
     */
    static void applyForwardSocketKeepAlive(Bootstrap bootstrap, EventLoopGroup eventLoopGroup, boolean enabled, int idleSeconds, int intervalSeconds, int count, MockServerLogger mockServerLogger) {
        if (!enabled) {
            return;
        }
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        // Tune the keepalive timers only on epoll (the only transport that exposes them). Detected by
        // the same group-derived selection used to pick the channel class, so it can never desync.
        if (isEpollChannelClass(NettyTransport.socketChannelClassFor(eventLoopGroup))) {
            applyEpollKeepAliveOptions(
                bootstrap,
                Math.max(1, idleSeconds),
                Math.max(1, intervalSeconds),
                Math.max(1, count),
                mockServerLogger
            );
        }
    }

    /**
     * True when the selected channel class is the native epoll socket channel. Guarded against
     * {@link NoClassDefFoundError} so it is safe on NIO-only platforms (macOS/Windows) where the epoll
     * API classes may be absent — there it simply returns false.
     */
    private static boolean isEpollChannelClass(Class<? extends Channel> channelClass) {
        try {
            return channelClass == io.netty.channel.epoll.EpollSocketChannel.class;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Sets the epoll-specific keepalive timer options. Isolated into its own method (and guarded
     * against {@link NoClassDefFoundError}) so the {@code EpollChannelOption} references are only
     * resolved when epoll has already been selected — keeping the class loadable on NIO-only platforms.
     */
    private static void applyEpollKeepAliveOptions(Bootstrap bootstrap, int idleSeconds, int intervalSeconds, int count, MockServerLogger mockServerLogger) {
        try {
            bootstrap
                .option(io.netty.channel.epoll.EpollChannelOption.TCP_KEEPIDLE, idleSeconds)
                .option(io.netty.channel.epoll.EpollChannelOption.TCP_KEEPINTVL, intervalSeconds)
                .option(io.netty.channel.epoll.EpollChannelOption.TCP_KEEPCNT, count);
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            // epoll classes unexpectedly unavailable despite the channel-class match — SO_KEEPALIVE
            // (already set) still applies with OS-default timers; nothing else to do.
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("unable to set epoll TCP keepalive options, falling back to SO_KEEPALIVE with OS-default timers: " + e.getMessage())
                );
            }
        }
    }

    public CompletableFuture<BinaryMessage> sendRequest(final BinaryMessage binaryRequest, final boolean isSecure, InetSocketAddress remoteAddress, Long connectionTimeoutMillis) throws SocketConnectionException {
        final EventLoopGroup eventLoopGroup = eventLoopGroup();
        if (!eventLoopGroup.isShuttingDown()) {
            if (proxyConfigurations != null && !isSecure && proxyConfigurations.containsKey(ProxyConfiguration.Type.HTTP)) {
                remoteAddress = proxyConfigurations.get(ProxyConfiguration.Type.HTTP).getProxyAddress();
            } else if (remoteAddress == null) {
                throw new IllegalArgumentException("Remote address cannot be null");
            }

            final CompletableFuture<BinaryMessage> binaryResponseFuture = new CompletableFuture<>();
            final CompletableFuture<Message> responseFuture = new CompletableFuture<>();

            Bootstrap binaryBootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NettyTransport.socketChannelClassFor(eventLoopGroup))
                .option(ChannelOption.AUTO_READ, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(8 * 1024, 32 * 1024))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeoutMillis != null ? (int) Math.min(connectionTimeoutMillis, Integer.MAX_VALUE) : null)
                .attr(SECURE, isSecure)
                .attr(REMOTE_SOCKET, remoteAddress)
                .attr(RESPONSE_FUTURE, responseFuture)
                .attr(ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE, !configuration.forwardBinaryRequestsWithoutWaitingForResponse())
                .handler(new HttpClientInitializer(proxyConfigurations, mockServerLogger, forwardProxyClient, nettySslContextFactory, null));
            applyForwardSocketKeepAlive(binaryBootstrap);
            binaryBootstrap
                .connect(remoteAddress)
                .addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.DEBUG)
                                    .setMessageFormat("sending bytes hex{}to{}")
                                    .setArguments(ByteBufUtil.hexDump(binaryRequest.getBytes()), future.channel().attr(REMOTE_SOCKET).get())
                            );
                        }
                        // send the binary request
                        future.channel().writeAndFlush(Unpooled.copiedBuffer(binaryRequest.getBytes()));
                    } else {
                        binaryResponseFuture.completeExceptionally(future.cause());
                    }
                });

            responseFuture
                .whenComplete((message, throwable) -> {
                    if (throwable == null) {
                        binaryResponseFuture.complete((BinaryMessage) message);
                    } else {
                        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.WARN)
                                    .setMessageFormat("exception while sending binary request - " + throwable.getMessage())
                                    .setThrowable(throwable)
                            );
                        }
                        binaryResponseFuture.completeExceptionally(throwable);
                    }
                });

            return binaryResponseFuture;
        } else {
            throw new IllegalStateException("Request sent after client has been stopped - the event loop has been shutdown so it is not possible to send a request");
        }
    }

    public HttpResponse sendRequest(HttpRequest httpRequest, long timeout, TimeUnit unit, boolean ignoreErrors) {
        HttpResponse httpResponse = null;
        try {
            httpResponse = sendRequest(httpRequest).get(timeout, unit);
        } catch (TimeoutException e) {
            if (!ignoreErrors) {
                throw new SocketCommunicationException("Response was not received from MockServer after " + configuration.maxSocketTimeoutInMillis() + " milliseconds, to wait longer please use \"mockserver.maxSocketTimeout\" system property or ConfigurationProperties.maxSocketTimeout(long milliseconds)", e.getCause());
            }
        } catch (InterruptedException | ExecutionException ex) {
            if (!ignoreErrors) {
                Throwable cause = ex.getCause();
                if (cause instanceof SocketConnectionException) {
                    throw (SocketConnectionException) cause;
                } else if (cause instanceof ConnectException) {
                    throw new SocketConnectionException("Unable to connect to socket " + httpRequest.socketAddressFromHostHeader(), cause);
                } else if (cause instanceof UnknownHostException) {
                    throw new SocketConnectionException("Unable to resolve host " + httpRequest.socketAddressFromHostHeader(), cause);
                } else if (cause instanceof IOException) {
                    throw new SocketConnectionException(cause.getMessage(), cause);
                } else {
                    throw new RuntimeException("Exception while sending request - " + ex.getMessage(), ex);
                }
            }
        }
        return httpResponse;
    }

    public HttpResponse sendRequest(HttpRequest httpRequest, long timeout, TimeUnit unit) {
        return sendRequest(httpRequest, timeout, unit, false);
    }

    private boolean isHostNotOnNoProxyHostList(InetSocketAddress remoteAddress) {
        if (remoteAddress == null
            || StringUtils.isBlank(configuration.noProxyHosts())) {
            return true;
        }
        if (NoProxyHostsUtils.isHostOnNoProxyList(remoteAddress.getHostString(), configuration.noProxyHosts())) {
            return false;
        }
        // Forward targets are now unresolved (DNS happens on the Netty event loop, not the calling
        // thread), so getAddress() is null for them and this IP-literal branch is skipped: only
        // hostname-form no_proxy entries match a hostname target; an IP-literal no_proxy entry will
        // not match a hostname target by its resolved IP. This branch still applies when the address
        // arrived already resolved (e.g. a literal-IP target).
        if (remoteAddress.getAddress() != null) {
            String ipAddress = remoteAddress.getAddress().getHostAddress();
            return !NoProxyHostsUtils.isHostOnNoProxyList(ipAddress, configuration.noProxyHosts());
        }
        return true;
    }
}
