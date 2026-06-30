package org.mockserver.httpclient;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.mockserver.codec.MockServerBinaryClientCodec;
import org.mockserver.codec.MockServerHttpClientCodec;
import org.mockserver.codec.StreamingAwareHttpObjectAggregator;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.LoggingHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.Protocol;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.socket.tls.NettySslContextFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.httpclient.NettyHttpClient.CONNECTION_POOL;
import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;
import static org.mockserver.httpclient.NettyHttpClient.SECURE;
import static org.slf4j.event.Level.DEBUG;
import static org.slf4j.event.Level.TRACE;

@ChannelHandler.Sharable
public class HttpClientInitializer extends ChannelInitializer<SocketChannel> {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final boolean forwardProxyClient;
    private final Protocol httpProtocol;
    private final HttpClientConnectionErrorHandler httpClientConnectionHandler;
    private final CompletableFuture<Protocol> protocolFuture;
    private final HttpClientHandler httpClientHandler;
    private final Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations;
    private final NettySslContextFactory nettySslContextFactory;

    HttpClientInitializer(Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations, MockServerLogger mockServerLogger, boolean forwardProxyClient, NettySslContextFactory nettySslContextFactory, Protocol httpProtocol) {
        this(proxyConfigurations, mockServerLogger, forwardProxyClient, nettySslContextFactory, httpProtocol, null);
    }

    HttpClientInitializer(Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations, MockServerLogger mockServerLogger, boolean forwardProxyClient, NettySslContextFactory nettySslContextFactory, Protocol httpProtocol, Configuration configuration) {
        this.proxyConfigurations = proxyConfigurations;
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.forwardProxyClient = forwardProxyClient;
        this.httpProtocol = httpProtocol;
        this.protocolFuture = new CompletableFuture<>();
        this.httpClientHandler = new HttpClientHandler(mockServerLogger);
        this.httpClientConnectionHandler = new HttpClientConnectionErrorHandler();
        this.nettySslContextFactory = nettySslContextFactory;
    }

    public void whenComplete(BiConsumer<? super Protocol, ? super Throwable> action) {
        protocolFuture.whenComplete(action);
    }

    @Override
    public void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        boolean secure = channel.attr(SECURE) != null && channel.attr(SECURE).get() != null && channel.attr(SECURE).get();

        if (proxyConfigurations != null) {
            if (secure && proxyConfigurations.containsKey(ProxyConfiguration.Type.HTTPS)) {
                ProxyConfiguration proxyConfiguration = proxyConfigurations.get(ProxyConfiguration.Type.HTTPS);
                if (isNotBlank(proxyConfiguration.getUsername()) && isNotBlank(proxyConfiguration.getPassword())) {
                    pipeline.addLast(new HttpProxyHandler(proxyConfiguration.getProxyAddress(), proxyConfiguration.getUsername(), proxyConfiguration.getPassword()));
                } else {
                    pipeline.addLast(new HttpProxyHandler(proxyConfiguration.getProxyAddress()));
                }
            } else if (proxyConfigurations.containsKey(ProxyConfiguration.Type.SOCKS5)) {
                ProxyConfiguration proxyConfiguration = proxyConfigurations.get(ProxyConfiguration.Type.SOCKS5);
                if (isNotBlank(proxyConfiguration.getUsername()) && isNotBlank(proxyConfiguration.getPassword())) {
                    pipeline.addLast(new Socks5ProxyHandler(proxyConfiguration.getProxyAddress(), proxyConfiguration.getUsername(), proxyConfiguration.getPassword()));
                } else {
                    pipeline.addLast(new Socks5ProxyHandler(proxyConfiguration.getProxyAddress()));
                }
            }
        }
        pipeline.addLast(httpClientConnectionHandler);

        if (secure) {
            InetSocketAddress remoteAddress = channel.attr(REMOTE_SOCKET).get();
            pipeline.addLast(nettySslContextFactory.createClientSslContext(forwardProxyClient, httpProtocol != null && httpProtocol.equals(Protocol.HTTP_2)).newHandler(channel.alloc(), remoteAddress.getHostName(), remoteAddress.getPort()));
        }

        // add logging
        if (mockServerLogger.isEnabledForInstance(TRACE)) {
            pipeline.addLast(new LoggingHandler(HttpClientHandler.class.getName()));
        }

        if (httpProtocol == null) {
            configureBinaryPipeline(pipeline);
        } else if (secure) {
            // use ALPN to determine http1 or http2
            pipeline.addLast(new HttpOrHttp2Initializer(this::configureHttp1Pipeline, this::configureHttp2Pipeline));
        } else {
            // default to http1 without TLS
            configureHttp1Pipeline(pipeline);
        }
    }

    /**
     * Guards an in-flight upstream read with a {@link ReadTimeoutHandler} sized from
     * {@code maxSocketTimeoutInMillis}, so a stalled upstream that connects but never sends a response
     * times out and completes the response future exceptionally (via {@link HttpClientHandler#exceptionCaught})
     * instead of leaving the channel and {@link java.util.concurrent.CompletableFuture} open forever.
     * <p>
     * The handler is added ONLY on non-pooled channels. A pooled keep-alive channel sits idle in the
     * {@link HttpForwardConnectionPool} between requests (with {@code AUTO_READ} on), where a blanket
     * read timeout would fire during legitimate idle keep-alive and tear the connection down — so for
     * pooled channels the pool's own idle eviction owns the lifecycle and no read timeout is armed. A
     * non-pooled channel writes its single request immediately after connect and is closed after the
     * one response, so arming the read timeout at pipeline-build time is correct for it.
     */
    private void addReadTimeoutHandlerIfNotPooled(ChannelPipeline pipeline) {
        if (configuration == null || pipeline.channel().attr(CONNECTION_POOL).get() != null) {
            return;
        }
        long readTimeoutMillis = configuration.maxSocketTimeoutInMillis();
        if (readTimeoutMillis > 0) {
            pipeline.addLast(new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS));
        }
    }

    private void configureHttp1Pipeline(ChannelPipeline pipeline) {
        addReadTimeoutHandlerIfNotPooled(pipeline);
        pipeline.addLast(new HttpClientCodec());
        pipeline.addLast(new HttpContentDecompressor());
        pipeline.addLast(new TimeToFirstByteHandler());
        if (configuration != null) {
            pipeline.addLast(new StreamingAwareHttpObjectAggregator(configuration.maxResponseBodySize(), configuration, mockServerLogger));
        } else {
            pipeline.addLast(new StreamingAwareHttpObjectAggregator(org.mockserver.configuration.ConfigurationProperties.maxResponseBodySize()));
        }
        pipeline.addLast(new MockServerHttpClientCodec(mockServerLogger, proxyConfigurations));
        pipeline.addLast(httpClientHandler);
        recordForwardUpstreamProtocol(pipeline, "http1_1");
        protocolFuture.complete(Protocol.HTTP_1_1);
    }

    /**
     * Record (metric + DEBUG log) the protocol this forward/proxy client connection actually negotiated
     * to the upstream, so operators can confirm whether {@code forwardProxyHttp2Upgrade} is taking effect.
     * A forward shown as {@code http1_1} to a backend that withholds its streaming SSE head over HTTP/1.1
     * (e.g. the OpenAI Codex endpoint) is the classic cause of a high forward time-to-first-byte.
     */
    private void recordForwardUpstreamProtocol(ChannelPipeline pipeline, String protocol) {
        String upstreamHost = "unknown";
        InetSocketAddress remote = pipeline.channel().attr(REMOTE_SOCKET).get();
        if (remote != null) {
            upstreamHost = remote.getHostString();
        }
        Metrics.incrementForwardUpstreamProtocol(upstreamHost, protocol);
        if (mockServerLogger.isEnabledForInstance(DEBUG)) {
            mockServerLogger.logEvent(new LogEntry()
                .setLogLevel(DEBUG)
                .setMessageFormat("forward upstream connection to {} negotiated {}")
                .setArguments(upstreamHost, protocol));
        }
    }

    /**
     * Builds a streaming-capable HTTP/2 forward pipeline using the same multiplex stack the server side
     * uses ({@link Http2FrameCodecBuilder#forClient()} + {@link Http2MultiplexHandler}), so that — unlike
     * the previous {@code InboundHttp2ToHttpAdapter} path which aggregated the whole response — a streamed
     * upstream response (Server-Sent Events) is relayed incrementally to the proxy client.
     * <p>
     * The MockServer request is written to the parent channel by {@link NettyHttpClient} exactly as for
     * HTTP/1.1; {@link Http2ForwardRequestDispatchHandler} (at the tail) intercepts that write, opens a
     * single outbound stream and dispatches the request to it. The per-stream response pipeline (decode,
     * decompress, streaming-aware aggregate, relay) is built by {@link Http2ForwardStreamChildInitializer}.
     * <p>
     * ALPN has already proven HTTP/2 by the time this runs, so {@code protocolFuture} is completed with
     * {@link Protocol#HTTP_2} here; the frame codec consumes the SETTINGS frame, so the old
     * {@link Http2SettingsHandler} (which waited for SETTINGS) is not used.
     */
    private void configureHttp2Pipeline(ChannelPipeline pipeline) {
        // NOTE: deliberately NO parent-channel read timeout here. The per-stream ReadTimeoutHandler is
        // armed on the CHILD pipeline (Http2ForwardStreamChildInitializer) and is swapped for the longer
        // stream IdleStateHandler (streamIdleTimeoutSeconds) when a streaming response is detected. A read
        // timeout on the PARENT would never be swapped, so an inter-event pause longer than maxSocketTimeout
        // (default 20s) during a streaming response — e.g. an LLM reasoning gap — would trip it and close the
        // connection, truncating the stream. The pre-dispatch window (connect + TLS handshake) is already
        // covered by the connect timeout and TLS handshake timeout.

        int maxFrameSize = configuration != null ? configuration.maxResponseBodySize() : org.mockserver.configuration.ConfigurationProperties.maxResponseBodySize();
        Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forClient()
            .initialSettings(Http2Settings.defaultSettings()
                // We are a client: never accept server push.
                .pushEnabled(false)
                .maxFrameSize(maxFrameSize < Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND
                    ? Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND
                    : Math.min(maxFrameSize, Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND)));
        if (mockServerLogger.isEnabledForInstance(TRACE)) {
            frameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.TRACE, HttpClientHandler.class.getName()));
        }
        pipeline.addLast(frameCodecBuilder.build());

        Http2ForwardStreamChildInitializer childInitializer = new Http2ForwardStreamChildInitializer(configuration, mockServerLogger, proxyConfigurations, httpClientHandler, httpClientConnectionHandler);
        // Server-initiated (inbound) streams are unexpected for a forward client (push is disabled) — close them.
        pipeline.addLast(new Http2MultiplexHandler(new ClosedInboundHttp2StreamHandler()));
        // Intercepts the MockServer request written to the parent channel and dispatches it on a new stream.
        pipeline.addLast(new Http2ForwardRequestDispatchHandler(childInitializer));

        // ALPN already proved HTTP/2; the frame codec consumes SETTINGS, so complete immediately.
        recordForwardUpstreamProtocol(pipeline, "http2");
        protocolFuture.complete(Protocol.HTTP_2);
    }

    /**
     * Tail outbound handler on the parent HTTP/2 connection. {@link NettyHttpClient} writes the MockServer
     * {@link org.mockserver.model.HttpRequest} to the parent channel exactly as it does for HTTP/1.1; this
     * handler intercepts that write, opens a single outbound stream channel (configured by
     * {@link Http2ForwardStreamChildInitializer}) and writes the request to it, where the child's encoder
     * turns it into HEADERS + DATA frames. Keeping the dispatch here means {@link NettyHttpClient} needs no
     * HTTP/2-specific code.
     */
    private static class Http2ForwardRequestDispatchHandler extends io.netty.channel.ChannelOutboundHandlerAdapter {

        private final Http2ForwardStreamChildInitializer childInitializer;

        private Http2ForwardRequestDispatchHandler(Http2ForwardStreamChildInitializer childInitializer) {
            this.childInitializer = childInitializer;
        }

        @Override
        public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
            if (msg instanceof org.mockserver.model.HttpRequest) {
                new Http2StreamChannelBootstrap(ctx.channel())
                    .handler(childInitializer)
                    .open()
                    .addListener((io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<Http2StreamChannel>>) openFuture -> {
                        if (openFuture.isSuccess()) {
                            Http2StreamChannel streamChannel = openFuture.getNow();
                            streamChannel.writeAndFlush(msg).addListener(writeFuture -> {
                                if (writeFuture.isSuccess()) {
                                    promise.setSuccess();
                                } else {
                                    promise.setFailure(writeFuture.cause());
                                    streamChannel.close();
                                }
                            });
                        } else {
                            promise.setFailure(openFuture.cause());
                        }
                    });
            } else {
                super.write(ctx, msg, promise);
            }
        }
    }

    /**
     * Handler installed by {@link Http2MultiplexHandler} for any server-initiated (inbound) stream. A
     * forward client disables server push and never expects an inbound stream, so close it immediately.
     */
    private static class ClosedInboundHttp2StreamHandler extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) {
            ch.close();
        }
    }

    private void configureBinaryPipeline(ChannelPipeline pipeline) {
        pipeline.addLast(new MockServerBinaryClientCodec());
        pipeline.addLast(httpClientHandler);
        protocolFuture.complete(null);
    }
}
