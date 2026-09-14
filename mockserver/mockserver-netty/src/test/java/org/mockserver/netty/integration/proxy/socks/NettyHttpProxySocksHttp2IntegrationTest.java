package org.mockserver.netty.integration.proxy.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
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
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
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
 * Locks the SOCKS4/SOCKS5 + TLS + HTTP/2 proxy path (GitHub issue #2685).
 *
 * <p>The four pre-existing secure SOCKS integration tests in {@link NettyHttpProxySOCKSIntegrationTest}
 * all drive HTTP/1.1 (Apache HttpClient 4.x or a raw socket) to an ephemeral-port {@code secureEchoServer},
 * so nothing exercised an HTTPS request that negotiates ALPN {@code h2} through the SOCKS tunnel, and
 * nothing reached the {@code :443} branch of {@code SocksProxyHandler.forwardConnection}. An {@code h2}
 * request failed completely: MockServer, acting as a SOCKS proxy, provisioned its loopback relay for
 * HTTP/1.1 before the tunnelled TLS connection had negotiated its protocol, so the forwarded HTTP/2 frames
 * were unparseable on the loopback, fell through to binary proxying, and the client received nothing (curl
 * reported {@code CURLE_HTTP2}, 0 bytes). SOCKS+TLS+HTTP/1.1, SOCKS cleartext and CONNECT+TLS+h2 all worked,
 * which is why the family shipped uncovered.
 *
 * <p>{@link java.net.http.HttpClient} cannot be reused here: its {@code ProxySelector} supports only
 * {@code Proxy.Type.HTTP}, not SOCKS. This test therefore uses a Netty client — {@link Socks5ProxyHandler}
 * or {@link Socks4ProxyHandler} for the SOCKS negotiation, a TLS {@code SslHandler} advertising the target
 * protocol via ALPN, then either the {@link Http2FrameCodecBuilder}/{@link Http2MultiplexHandler} multiplex
 * stack ({@code h2}) or an {@link HttpClientCodec} ({@code HTTP/1.1}) — the same real-frame client style used
 * by {@code H2cMockingMatrixIntegrationTest}, asserting on the bytes the client's own connection receives.
 *
 * <p>The topology is the issue's: the response is served from a MockServer expectation (not a real upstream),
 * and the SOCKS destination port is {@code 443} so the downstream-TLS heuristic engages exactly as it does
 * for a real {@code https://} target. The SOCKS5 cases ask for a fake host ({@value #FAKE_HOST}) passed to the
 * proxy unresolved (SOCKS5h), so no DNS lookup happens on either side; the SOCKS4 case (no domain support in
 * the protocol) asks for {@code 127.0.0.1:443}. Because the request matches an expectation, MockServer mocks
 * it via its self-loopback rather than dialling the target, so nothing needs to be listening on port 443.
 */
public class NettyHttpProxySocksHttp2IntegrationTest {

    private static final String FAKE_HOST = "mocked.example.test";
    private static final int TLS_PORT = 443;

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
    // SOCKS5
    // ------------------------------------------------------------------

    /**
     * The #2685 regression: SOCKS5 + TLS + ALPN-negotiated HTTP/2. Red on the unfixed code (the loopback is
     * provisioned for HTTP/1.1 before ALPN is known, so no response reaches the client's stream).
     */
    @Test(timeout = 30000)
    public void shouldServeMockedResponseOverSocks5TlsHttp2() throws Exception {
        givenHelloExpectation();

        Result result = sendViaSocksTlsHttp2(
            SocksVersion.SOCKS5,
            InetSocketAddress.createUnresolved(FAKE_HOST, TLS_PORT),
            FAKE_HOST);

        assertThat("status over socks5+tls+h2: <" + result.status + ">", result.status, is("201"));
        assertThat("body over socks5+tls+h2: <" + result.body + ">", result.body, is("hello"));
        mockServerClient.verify(request().withPath("/hello"));
    }

    /**
     * SOCKS5 + TLS + HTTP/1.1 to a {@code :443} target. This flow worked before the fix and MUST still work:
     * the fix makes a {@code :443} SOCKS target take the relay-terminated branch (the same one CONNECT uses)
     * instead of the old leftover-{@code PortUnificationHandler} branch, and this case proves HTTP/1.1 still
     * negotiates and is mocked correctly on that newly-entered branch. It is a change-of-behaviour guard, not
     * a red→green lock — it passes on both the unfixed and fixed code.
     */
    @Test(timeout = 30000)
    public void shouldServeMockedResponseOverSocks5TlsHttp11() throws Exception {
        givenHelloExpectation();

        Result result = sendViaSocksTlsHttp11(
            SocksVersion.SOCKS5,
            InetSocketAddress.createUnresolved(FAKE_HOST, TLS_PORT),
            FAKE_HOST);

        assertThat("status over socks5+tls+h1.1: <" + result.status + ">", result.status, is("201"));
        assertThat("body over socks5+tls+h1.1: <" + result.body + ">", result.body, is("hello"));
        mockServerClient.verify(request().withPath("/hello"));
    }

    // ------------------------------------------------------------------
    // SOCKS4 - the fix is in the shared SocksProxyHandler base class, so it applies to SOCKS4 too. SOCKS4
    // has no domain-name support, so the target is an IPv4 literal on port 443.
    // ------------------------------------------------------------------

    /**
     * SOCKS4 + TLS + ALPN-negotiated HTTP/2. Same defect and same fix as the SOCKS5 case, exercised through
     * {@code Socks4ProxyHandler} so the "SOCKS4/SOCKS5" claim in the changelog is actually tested. Red on the
     * unfixed code for the same reason as the SOCKS5 h2 case.
     */
    @Test(timeout = 30000)
    public void shouldServeMockedResponseOverSocks4TlsHttp2() throws Exception {
        givenHelloExpectation();

        Result result = sendViaSocksTlsHttp2(
            SocksVersion.SOCKS4,
            new InetSocketAddress("127.0.0.1", TLS_PORT),
            null);

        assertThat("status over socks4+tls+h2: <" + result.status + ">", result.status, is("201"));
        assertThat("body over socks4+tls+h2: <" + result.body + ">", result.body, is("hello"));
        mockServerClient.verify(request().withPath("/hello"));
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    private void givenHelloExpectation() {
        mockServerClient
            .when(request().withPath("/hello"))
            .respond(
                response()
                    .withStatusCode(201)
                    .withBody("hello")
            );
    }

    private enum SocksVersion {SOCKS4, SOCKS5}

    private ChannelHandler socksProxyHandler(SocksVersion version, SocketAddress proxyAddress) {
        return version == SocksVersion.SOCKS4
            ? new Socks4ProxyHandler(proxyAddress)
            : new Socks5ProxyHandler(proxyAddress);
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
     * What the client observed: the HTTP status and the response body, however they arrived (an HTTP/2 stream
     * or an HTTP/1.1 response).
     */
    private static class Result {
        private String status;
        private String body;
    }

    /**
     * Send a single request through MockServer's SOCKS proxy over TLS with ALPN-negotiated HTTP/2, on a
     * dedicated stream, and collect what the client receives on THAT stream. Completes on end-of-stream,
     * connection close, or the {@code @Test} timeout, so a dropped body reports {@code ""} rather than an
     * opaque hang.
     */
    private Result sendViaSocksTlsHttp2(SocksVersion version, SocketAddress target, String sniHost) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Result result = new Result();
            StringBuilder collected = new StringBuilder();
            CompletableFuture<Result> future = new CompletableFuture<>();

            SslContext sslContext = clientSslContext(ApplicationProtocolNames.HTTP_2);
            InetSocketAddress proxyAddress = new InetSocketAddress("127.0.0.1", mockServer.getLocalPort());

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                // do NOT resolve the target host locally - hand it to the SOCKS proxy (SOCKS5h)
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(socksProxyHandler(version, proxyAddress));
                        ch.pipeline().addLast(sslHandler(sslContext, ch, sniHost));
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
                .handler(new ChannelInboundHandlerAdapter() {
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
                })
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTPS.name())
                .authority((sniHost != null ? sniHost : "127.0.0.1") + ":" + TLS_PORT)
                .path("/hello");
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            return future.get(20, TimeUnit.SECONDS);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Send a single request through MockServer's SOCKS proxy over TLS with ALPN-negotiated HTTP/1.1 and
     * collect the aggregated response. Completes on the response, connection close, or the {@code @Test}
     * timeout.
     */
    private Result sendViaSocksTlsHttp11(SocksVersion version, SocketAddress target, String sniHost) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Result result = new Result();
            CompletableFuture<Result> future = new CompletableFuture<>();

            SslContext sslContext = clientSslContext(ApplicationProtocolNames.HTTP_1_1);
            InetSocketAddress proxyAddress = new InetSocketAddress("127.0.0.1", mockServer.getLocalPort());

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(socksProxyHandler(version, proxyAddress));
                        ch.pipeline().addLast(sslHandler(sslContext, ch, sniHost));
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

            String authority = (sniHost != null ? sniHost : "127.0.0.1") + ":" + TLS_PORT;
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello");
            request.headers().set(HttpHeaderNames.HOST, authority);
            channel.writeAndFlush(request);

            return future.get(20, TimeUnit.SECONDS);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private static ChannelHandler sslHandler(SslContext sslContext, SocketChannel ch, String sniHost) {
        // Send SNI only for a real hostname; an IPv4 literal (the SOCKS4 case) is not a valid SNI name.
        return sniHost != null
            ? sslContext.newHandler(ch.alloc(), sniHost, TLS_PORT)
            : sslContext.newHandler(ch.alloc());
    }
}
