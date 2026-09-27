package org.mockserver.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * End-to-end reproduction of the HTTPS forward-proxy (CONNECT + MITM) connection-churn defect over
 * HTTP/2. A JDK {@link HttpClient} opens ONE CONNECT tunnel to MockServer and negotiates HTTP/2 inside
 * it (as Go/k6 and browsers do); MockServer terminates the tunnelled TLS and forwards each decrypted
 * request over HTTP/2 to a real HTTP/2 upstream. Sustained sequential traffic over the single tunnel
 * must reuse ONE pooled upstream connection (a fresh stream per request) rather than opening — and
 * closing — a fresh upstream TCP/TLS connection per request.
 */
public class HttpsConnectForwardConnectionPoolTest {

    @Test(timeout = 60_000)
    public void shouldReuseUpstreamConnectionsForHttp2ConnectForwardProxy() throws Exception {
        Http2CountingUpstream upstream = new Http2CountingUpstream();
        Configuration configuration = Configuration.configuration()
            .forwardConnectionPoolEnabled(true)
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.ANY)
            .forwardProxyTLSHostnameVerificationEnabled(false);
        MockServer mockServer = new MockServer(configuration);
        try {
            int proxyPort = mockServer.getLocalPort();
            int upstreamPort = upstream.port();

            HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(trustAllContext())
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxyPort)))
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            int requests = 12;
            for (int i = 0; i < requests; i++) {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://127.0.0.1:" + upstreamPort + "/path" + i))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat("request #" + i + " status", response.statusCode(), is(200));
                assertThat("request #" + i + " negotiated HTTP/2", response.version(), is(HttpClient.Version.HTTP_2));
            }

            // The JDK HttpClient's HTTP/2 first-connection/ALPN negotiation can briefly open a second
            // tunnel before settling on one multiplexed connection, so allow up to 2; the invariant is
            // that upstream connections do NOT grow with request count (12 requests, not 12 connections).
            // On the unfixed code every forward churned a fresh upstream connection (~12).
            assertThat("distinct upstream connections for " + requests + " HTTP/2 CONNECT forwards",
                upstream.acceptedConnections(), is(lessThanOrEqualTo(2)));
        } finally {
            mockServer.stop();
            upstream.stop();
        }
    }

    private static SSLContext trustAllContext() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new java.security.SecureRandom());
        return sslContext;
    }

    /**
     * A minimal TLS HTTP/2 upstream (self-signed, ALPN h2) that counts accepted TCP connections and
     * answers each request stream with a {@code 200}, so a test can assert that many request streams
     * were multiplexed onto a reused parent connection.
     */
    private static final class Http2CountingUpstream {
        private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        private final AtomicInteger accepted = new AtomicInteger();
        private final Channel serverChannel;

        Http2CountingUpstream() throws Exception {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            final SslContext sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1))
                .build();
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        accepted.incrementAndGet();
                        ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                        ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                            @Override
                            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                                ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                                ctx.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel streamCh) {
                                        streamCh.pipeline().addLast(new SimpleChannelInboundHandler<Http2Frame>() {
                                            @Override
                                            protected void channelRead0(ChannelHandlerContext streamCtx, Http2Frame frame) {
                                                boolean endStream = (frame instanceof Http2HeadersFrame && ((Http2HeadersFrame) frame).isEndStream())
                                                    || (frame instanceof Http2DataFrame && ((Http2DataFrame) frame).isEndStream());
                                                if (endStream) {
                                                    Http2Headers responseHeaders = new DefaultHttp2Headers().status("200");
                                                    streamCtx.write(new DefaultHttp2HeadersFrame(responseHeaders, false));
                                                    streamCtx.writeAndFlush(new DefaultHttp2DataFrame(
                                                        Unpooled.copiedBuffer("OK", CharsetUtil.UTF_8), true));
                                                }
                                            }
                                        });
                                    }
                                }));
                            }
                        });
                    }
                });
            serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        int acceptedConnections() {
            return accepted.get();
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }
}
