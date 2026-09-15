package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.test.Http2FlowControlBodies;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Locks HTTP/2 (and HTTP/1.1) through MockServer's HTTP {@code CONNECT} forward-proxy, at the wire-frame
 * level with a third-party client, mirroring {@link org.mockserver.netty.integration.proxy.socks.NettyHttpProxySocksHttp2IntegrationTest}
 * for the {@code CONNECT} tunnel.
 *
 * <p><b>The gap this closes.</b> The {@code CONNECT} setup in {@code HttpRequestHandler} used to
 * <em>assume TLS</em> for every tunnel and install an {@code SslHandler} before the client had sent a
 * single tunnelled byte. A client speaking <b>cleartext HTTP/2 with prior knowledge (h2c)</b> through the
 * tunnel - it emits the connection preface {@code PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n} then HTTP/2 frames, with
 * no TLS and no HTTP/1.1 Upgrade - therefore had its plaintext bytes fed to a TLS terminator, the handshake
 * failed, and the relay fell back to HTTP/1.1; the preface was then unparseable and the client received
 * nothing. The SOCKS path was fixed for exactly this by deferring the TLS decision and classifying the first
 * tunnelled bytes (issue #2685); the {@code CONNECT} path now defers the same way, so h2c prior-knowledge is
 * detected from the bytes and provisioned as cleartext HTTP/2 on both relay legs.
 *
 * <p><b>Why a raw Netty frame client, not MockServer's own client or the JDK client.</b> The JDK
 * {@link java.net.http.HttpClient} cannot make an h2c prior-knowledge request through an HTTP {@code CONNECT}
 * proxy, and using MockServer's own client would test the code against itself. This test drives a real,
 * independent HTTP/2 implementation ({@link Http2FrameCodecBuilder} + {@link Http2MultiplexHandler}) that
 * emits genuine frames, with {@link HttpProxyHandler} performing the {@code CONNECT} handshake - the same
 * frame-level style {@code H2cMockingMatrixIntegrationTest} and the SOCKS test use.
 *
 * <p><b>Why the bodies exceed the flow-control window.</b> The h2c and TLS+h2 assertions use
 * {@link Http2FlowControlBodies.Size#OVER_WINDOW} (256 KB), comfortably past the 65,535-byte initial
 * flow-control window, so the response spans many DATA frames and the server MUST react to the client's
 * {@code WINDOW_UPDATE} to deliver it all. A sub-window body would hide the {@code writePendingBytes} flush
 * family (#2641/#2667/#2669/#2683). No {@code jdk.httpclient.windowsize} pin is needed here (unlike the JDK
 * client, whose large default window would swallow the whole body in one go): Netty's client advertises the
 * RFC-default 65,535-byte initial window, so the over-window body drives {@code WINDOW_UPDATE} by construction.
 *
 * <p>The topology matches the issue's: the response is served from a MockServer expectation (not a real
 * upstream), so the request is mocked via MockServer's self-loopback and nothing needs to listen on the
 * tunnelled target. The h2c case uses a cleartext port (80) so the target looks like an ordinary
 * {@code http://} endpoint; the TLS cases use {@code 443}. Detection is byte-driven, so the exact port is
 * immaterial.
 */
public class NettyHttpProxyConnectHttp2IntegrationTest {

    private static final String FAKE_HOST = "mocked.example.test";
    private static final int TLS_PORT = 443;
    private static final int CLEARTEXT_PORT = 80;

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Before
    public void reset() {
        mockServerClient.reset();
    }

    // ------------------------------------------------------------------
    // cleartext h2c prior knowledge through CONNECT - the fix
    // ------------------------------------------------------------------

    /**
     * The fix: cleartext HTTP/2 with prior knowledge (h2c, no TLS) through the HTTP {@code CONNECT} tunnel.
     * Red on the assume-TLS code (the tunnel installed an SslHandler, the preface failed the TLS handshake and
     * the relay downgraded to HTTP/1.1, so the client received nothing); green once the {@code CONNECT} path
     * defers the TLS decision and classifies the first tunnelled bytes as the h2c preface. The 256 KB body
     * crosses the flow-control window so a broken WINDOW_UPDATE flush would also fail here.
     */
    @Test(timeout = 30000)
    public void shouldServeLargeMockedResponseOverConnectCleartextHttp2PriorKnowledge() throws Exception {
        String largeBody = Http2FlowControlBodies.body(Http2FlowControlBodies.Size.OVER_WINDOW, "connect-h2c");
        mockServerClient
            .when(request().withPath("/hello"))
            .respond(response().withStatusCode(201).withBody(largeBody));

        Result result = sendViaConnectCleartextHttp2(
            InetSocketAddress.createUnresolved(FAKE_HOST, CLEARTEXT_PORT),
            FAKE_HOST,
            CLEARTEXT_PORT);

        assertThat("status over connect cleartext h2c: <" + result.status + ">", result.status, is("201"));
        assertThat("body length over connect cleartext h2c: <" + result.body.length() + ">", result.body, is(largeBody));
        mockServerClient.verify(request().withPath("/hello"));
    }

    // ------------------------------------------------------------------
    // regression: TLS-over-CONNECT with ALPN (h2 and http/1.1)
    // ------------------------------------------------------------------

    /**
     * Regression guard: TLS through the {@code CONNECT} tunnel with ALPN-negotiated HTTP/2. This worked before
     * the fix (the assume-TLS path terminated the tunnelled TLS and read ALPN) and MUST still work now that the
     * {@code CONNECT} path reaches that same relay-terminated branch via byte-driven detection. A 256 KB body
     * keeps the flow-control flush exercised on the TLS path too.
     */
    @Test(timeout = 30000)
    public void shouldServeLargeMockedResponseOverConnectTlsHttp2() throws Exception {
        String largeBody = Http2FlowControlBodies.body(Http2FlowControlBodies.Size.OVER_WINDOW, "connect-tls-h2");
        mockServerClient
            .when(request().withPath("/hello"))
            .respond(response().withStatusCode(201).withBody(largeBody));

        Result result = sendViaConnectTlsHttp2(
            InetSocketAddress.createUnresolved(FAKE_HOST, TLS_PORT),
            FAKE_HOST,
            TLS_PORT);

        assertThat("status over connect+tls+h2: <" + result.status + ">", result.status, is("201"));
        assertThat("body over connect+tls+h2: <" + result.body.length() + ">", result.body, is(largeBody));
        mockServerClient.verify(request().withPath("/hello"));
    }

    /**
     * Regression guard: TLS through the {@code CONNECT} tunnel with ALPN-negotiated HTTP/1.1. Worked before the
     * fix and must still work on the newly-entered byte-driven branch.
     */
    @Test(timeout = 30000)
    public void shouldServeMockedResponseOverConnectTlsHttp11() throws Exception {
        mockServerClient
            .when(request().withPath("/hello"))
            .respond(response().withStatusCode(201).withBody("hello"));

        Result result = sendViaConnectTlsHttp11(
            InetSocketAddress.createUnresolved(FAKE_HOST, TLS_PORT),
            FAKE_HOST,
            TLS_PORT);

        assertThat("status over connect+tls+h1.1: <" + result.status + ">", result.status, is("201"));
        assertThat("body over connect+tls+h1.1: <" + result.body + ">", result.body, is("hello"));
        mockServerClient.verify(request().withPath("/hello"));
    }

    // ------------------------------------------------------------------
    // regression: cleartext HTTP/1.1 through CONNECT
    // ------------------------------------------------------------------

    /**
     * Regression guard: plaintext HTTP/1.1 through the {@code CONNECT} tunnel (no TLS, no h2c). The byte-driven
     * detector must classify the first bytes as a plaintext HTTP/1.1 request and provision HTTP/1.1, returning
     * the mocked response.
     */
    @Test(timeout = 30000)
    public void shouldServeMockedResponseOverConnectCleartextHttp11() throws Exception {
        mockServerClient
            .when(request().withPath("/hello"))
            .respond(response().withStatusCode(201).withBody("hello"));

        Result result = sendViaConnectCleartextHttp11(
            InetSocketAddress.createUnresolved(FAKE_HOST, CLEARTEXT_PORT),
            FAKE_HOST,
            CLEARTEXT_PORT);

        assertThat("status over connect cleartext h1.1: <" + result.status + ">", result.status, is("201"));
        assertThat("body over connect cleartext h1.1: <" + result.body + ">", result.body, is("hello"));
        mockServerClient.verify(request().withPath("/hello"));
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    /**
     * What the client observed: the HTTP status and the response body, however they arrived (an HTTP/2 stream
     * or an HTTP/1.1 response).
     */
    private static class Result {
        private String status;
        private String body = "";
    }

    private ChannelHandler connectProxyHandler() {
        return new HttpProxyHandler(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()));
    }

    private SslContext clientSslContext(String... alpnProtocols) throws Exception {
        return SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                SelectorFailureBehavior.NO_ADVERTISE,
                SelectedListenerFailureBehavior.ACCEPT,
                alpnProtocols))
            .build();
    }

    /**
     * Send a single cleartext HTTP/2 request with prior knowledge (h2c, no TLS) through MockServer's
     * {@code CONNECT} proxy on a dedicated stream, and collect what the client receives on THAT stream. The
     * Netty client emits the h2c connection preface before any frame, so this is a genuine prior-knowledge
     * tunnel, not an HTTP/1.1 Upgrade. Completes on end-of-stream, connection close, or the {@code @Test}
     * timeout, so a dropped body reports {@code ""} rather than an opaque hang.
     */
    private Result sendViaConnectCleartextHttp2(SocketAddress target, String authorityHost, int targetPort) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Result result = new Result();
            StringBuilder collected = new StringBuilder();
            CompletableFuture<Result> future = new CompletableFuture<>();

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                // do NOT resolve the target host locally - hand it to the CONNECT proxy
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(connectProxyHandler());
                        // no SslHandler - cleartext h2c prior knowledge
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                            }
                        }));
                    }
                });

            Channel parent = bootstrap.connect(target).sync().channel();

            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(streamCollector(result, collected, future))
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority((authorityHost != null ? authorityHost : "127.0.0.1") + ":" + targetPort)
                .path("/hello");
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            return future.get(20, TimeUnit.SECONDS);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Send a single request through MockServer's {@code CONNECT} proxy over TLS with ALPN-negotiated HTTP/2,
     * on a dedicated stream, and collect what the client receives on THAT stream.
     */
    private Result sendViaConnectTlsHttp2(SocketAddress target, String sniHost, int targetPort) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Result result = new Result();
            StringBuilder collected = new StringBuilder();
            CompletableFuture<Result> future = new CompletableFuture<>();

            SslContext sslContext = clientSslContext(ApplicationProtocolNames.HTTP_2);

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(connectProxyHandler());
                        ch.pipeline().addLast(sslHandler(sslContext, ch, sniHost, targetPort));
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                            }
                        }));
                    }
                });

            Channel parent = bootstrap.connect(target).sync().channel();

            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(streamCollector(result, collected, future))
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTPS.name())
                .authority((sniHost != null ? sniHost : "127.0.0.1") + ":" + targetPort)
                .path("/hello");
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            return future.get(20, TimeUnit.SECONDS);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Send a single request through MockServer's {@code CONNECT} proxy over TLS with ALPN-negotiated HTTP/1.1
     * and collect the aggregated response.
     */
    private Result sendViaConnectTlsHttp11(SocketAddress target, String sniHost, int targetPort) throws Exception {
        SslContext sslContext = clientSslContext(ApplicationProtocolNames.HTTP_1_1);
        return sendHttp11(target, sniHost, targetPort, ch -> ch.pipeline().addLast(sslHandler(sslContext, ch, sniHost, targetPort)));
    }

    /**
     * Send a single cleartext (no TLS) HTTP/1.1 request through MockServer's {@code CONNECT} proxy and collect
     * the aggregated response.
     */
    private Result sendViaConnectCleartextHttp11(SocketAddress target, String authorityHost, int targetPort) throws Exception {
        return sendHttp11(target, authorityHost, targetPort, ch -> { /* no TLS */ });
    }

    private interface PipelineStep {
        void apply(SocketChannel ch);
    }

    private Result sendHttp11(SocketAddress target, String authorityHost, int targetPort, PipelineStep afterConnect) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Result result = new Result();
            CompletableFuture<Result> future = new CompletableFuture<>();

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(connectProxyHandler());
                        afterConnect.apply(ch);
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                try {
                                    if (msg instanceof FullHttpResponse) {
                                        FullHttpResponse response = (FullHttpResponse) msg;
                                        result.status = String.valueOf(response.status().code());
                                        result.body = response.content().toString(StandardCharsets.UTF_8);
                                        future.complete(result);
                                    }
                                } finally {
                                    ReferenceCountUtil.release(msg);
                                }
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                future.complete(result);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                future.completeExceptionally(cause);
                            }
                        });
                    }
                });

            Channel channel = bootstrap.connect(target).sync().channel();

            String authority = (authorityHost != null ? authorityHost : "127.0.0.1") + ":" + targetPort;
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello");
            request.headers().set(HttpHeaderNames.HOST, authority);
            channel.writeAndFlush(request);

            return future.get(20, TimeUnit.SECONDS);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private static ChannelInboundHandlerAdapter streamCollector(Result result, StringBuilder collected, CompletableFuture<Result> future) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                try {
                    if (msg instanceof Http2HeadersFrame) {
                        Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                        if (headersFrame.headers().status() != null) {
                            result.status = headersFrame.headers().status().toString();
                        }
                        if (headersFrame.isEndStream()) {
                            complete();
                        }
                    } else if (msg instanceof Http2DataFrame) {
                        Http2DataFrame data = (Http2DataFrame) msg;
                        collected.append(data.content().toString(StandardCharsets.UTF_8));
                        if (data.isEndStream()) {
                            complete();
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                complete();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                future.completeExceptionally(cause);
            }

            private void complete() {
                result.body = collected.toString();
                future.complete(result);
            }
        };
    }

    private static ChannelHandler sslHandler(SslContext sslContext, SocketChannel ch, String sniHost, int targetPort) {
        // Send SNI only for a real hostname; an IPv4 literal is not a valid SNI name.
        return sniHost != null
            ? sslContext.newHandler(ch.alloc(), sniHost, targetPort)
            : sslContext.newHandler(ch.alloc());
    }
}
