package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.model.Format;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Protocol;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.verify.VerificationTimes.atLeast;

/**
 * Integration test proving negotiated-protocol verifiability for HTTP/3: a request that
 * actually arrives over QUIC/HTTP-3 is recorded with {@code protocol = HTTP_3}, can be
 * verified via {@code verify(request().withProtocol(HTTP_3))}, and is retrievable with the
 * protocol field populated.
 * <p>
 * Requires the native QUIC transport (BoringSSL) and skips gracefully where it is unavailable.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3ProtocolVerificationIntegrationTest {

    private MockServer mockServer;
    private NioEventLoopGroup clientGroup;

    @Before
    public void setUp() {
        assumeQuicAvailable();
    }

    @After
    public void tearDown() {
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    @Test
    public void shouldRecordAndVerifyHttp3Protocol() throws Exception {
        // given -- a MockServer with HTTP/3 enabled and a simple expectation
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);

        mockServer = new MockServer(config, 0);
        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("GET").withPath("/protocol-test")
        ).respond(
            response().withStatusCode(200).withBody("h3-ok")
        );

        // when -- a request arrives over HTTP/3
        String status = sendHttp3Request(http3Port, "/protocol-test");
        assertThat("status should be 200", status, is("200"));

        // then -- verify(request().withProtocol(HTTP_3)) succeeds for the recorded request
        client.verify(
            request().withPath("/protocol-test").withProtocol(Protocol.HTTP_3),
            atLeast(1)
        );

        // and -- an HTTP_2 protocol verification does NOT match (negotiated value is trusted)
        Throwable http2VerifyError = null;
        try {
            client.verify(
                request().withPath("/protocol-test").withProtocol(Protocol.HTTP_2),
                atLeast(1)
            );
        } catch (AssertionError e) {
            http2VerifyError = e;
        }
        assertThat("verifying HTTP_2 should fail for an HTTP/3 request", http2VerifyError, is(notNullValue()));

        // and -- the serialized (JSON) recorded request carries "HTTP_3"
        String recordedJson = client.retrieveRecordedRequests(request().withPath("/protocol-test"), Format.JSON);
        assertThat("serialized recorded request should contain HTTP_3", recordedJson, containsString("HTTP_3"));

        // and -- the recorded request carries protocol = HTTP_3
        HttpRequest[] recorded = client.retrieveRecordedRequests(request().withPath("/protocol-test"));
        assertThat("should have recorded request", recorded.length, greaterThanOrEqualTo(1));
        assertThat("recorded request should be tagged HTTP_3", recorded[0].getProtocol(), is(Protocol.HTTP_3));
    }

    // ---- helpers ----

    /**
     * Find a free UDP port for the HTTP/3 (QUIC) server.
     *
     * <p>Delegates to the shared {@link org.mockserver.testing.socket.TestPortFactory} so the probing
     * strategy lives in one place. A failure to find a port is now raised rather than reported as port
     * {@code 0}: {@code http3Port(0)} means "HTTP/3 disabled", which would silently turn an
     * infrastructure failure into a test that skips or asserts against a server that never started.
     */
    private static int findAvailableUdpPort() {
        return org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort();
    }

    private String sendHttp3Request(int port, String path) throws Exception {
        clientGroup = new NioEventLoopGroup(1);

        QuicSslContext clientSslContext = QuicSslContextBuilder.forClient()
            .trustManager(trustAllManager())
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        Channel clientChannel = new Bootstrap()
            .group(clientGroup)
            .channel(NioDatagramChannel.class)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(clientSslContext)
                .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0)
            .sync()
            .channel();

        QuicChannel quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", port))
            .connect()
            .get(15, TimeUnit.SECONDS);

        BlockingQueue<String> statusQueue = new LinkedBlockingQueue<>();

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    CharSequence status = headersFrame.headers().status();
                    statusQueue.offer(status != null ? status.toString() : "null");
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    dataFrame.content().release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    ctx.close();
                }
            }
        ).sync().getNow();

        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method("GET");
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);

        requestStream.writeAndFlush(requestHeaders)
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            .sync();

        String status = statusQueue.poll(20, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        return status != null ? status : "null";
    }

    private static void assumeQuicAvailable() {
        try {
            boolean available = io.netty.handler.codec.quic.Quic.isAvailable();
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 test",
                available
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 test",
                t
            );
        }
    }

    @SuppressWarnings("TrustAllX509TrustManager")
    private static TrustManager trustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        };
    }
}
