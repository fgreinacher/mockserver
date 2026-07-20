package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpError;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Integration test proving that {@link Http3ResponseWriter#writeStreamError(long)} (the HTTP/3 leg
 * of the {@link HttpError} stream-error action) resets the QUIC request stream rather than returning
 * a normal response. Uses an in-JVM Netty QUIC client/server.
 * <p>
 * Skips gracefully when the native QUIC transport (BoringSSL) is not available.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3StreamErrorIntegrationTest {

    private static final Configuration CONFIGURATION = configuration();
    private static final MockServerLogger LOGGER = new MockServerLogger(Http3StreamErrorIntegrationTest.class);

    private Channel serverChannel;
    private NioEventLoopGroup serverGroup;
    private NioEventLoopGroup clientGroup;

    @Before
    public void setUp() {
        assumeQuicAvailable();
    }

    @After
    public void tearDown() {
        if (serverChannel != null) {
            try { serverChannel.close().sync(); } catch (Exception ignored) {}
            serverChannel = null;
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully();
            serverGroup = null;
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    @Test
    public void shouldResetHttp3StreamInsteadOfResponding() throws Exception {
        // given - a QUIC/HTTP3 server whose handler resets the stream with H3_REQUEST_CANCELLED
        long errorCode = HttpError.StreamErrorCode.H3_REQUEST_CANCELLED.code();
        int port = startStreamErrorServer(errorCode);

        // when - send an HTTP/3 request and observe the stream outcome
        StreamOutcome outcome = sendHttp3Request(port, "/reset");

        // then - the client receives NO response headers/body and the stream is reset (not completed
        // with a normal 200). The QUIC stack surfaces the peer RESET_STREAM as a stream exception
        // and/or an immediate input-close without any HEADERS frame.
        assertThat("no response HEADERS frame should be received when the stream is reset",
            outcome.receivedHeaders, is(false));
        assertThat("the stream should have been reset (exception) or closed without a response",
            outcome.resetOrClosedWithoutResponse(), is(true));
    }

    // ---- server ----

    private int startStreamErrorServer(long errorCode) throws Exception {
        serverGroup = new NioEventLoopGroup(1);

        KeyPair keyPair = generateKeyPair();
        X509Certificate cert = generateSelfSignedCert(keyPair);

        QuicSslContext sslContext = QuicSslContextBuilder
            .forServer(keyPair.getPrivate(), null, cert)
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        serverChannel = new Bootstrap()
            .group(serverGroup)
            .channel(NioDatagramChannel.class)
            .handler(Http3.newQuicServerCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamDataBidirectionalRemote(1000000)
                .initialMaxStreamsBidirectional(100)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new Http3ServerConnectionHandler(
                    new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel ch) {
                            ch.pipeline().addLast(new StreamErrorTestHandler(errorCode));
                        }
                    }
                ))
                .build())
            .bind(new InetSocketAddress(0))
            .sync()
            .channel();

        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /**
     * Server handler that, on receiving the request headers, applies the HTTP/3 stream-error reset via
     * the production {@link Http3ResponseWriter#writeStreamError(long)} seam.
     */
    private static class StreamErrorTestHandler extends Http3RequestStreamInboundHandler {
        private final long errorCode;

        StreamErrorTestHandler(long errorCode) {
            this.errorCode = errorCode;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
            new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx).writeStreamError(errorCode);
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
            dataFrame.release();
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            // nothing to do
        }
    }

    // ---- client ----

    private static class StreamOutcome {
        volatile boolean receivedHeaders = false;
        volatile boolean exceptionRaised = false;
        volatile boolean inputClosed = false;

        boolean resetOrClosedWithoutResponse() {
            return !receivedHeaders && (exceptionRaised || inputClosed);
        }
    }

    private StreamOutcome sendHttp3Request(int port, String path) throws Exception {
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

        StreamOutcome outcome = new StreamOutcome();
        CountDownLatch done = new CountDownLatch(1);

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    outcome.receivedHeaders = true;
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    outcome.inputClosed = true;
                    done.countDown();
                    ctx.close();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    outcome.exceptionRaised = true;
                    done.countDown();
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    done.countDown();
                }
            }
        ).sync().getNow();

        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method("GET");
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);

        requestStream.writeAndFlush(requestHeaders)
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);

        done.await(5, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        return outcome;
    }

    // ---- crypto helpers ----

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256, new SecureRandom());
        return gen.generateKeyPair();
    }

    private static X509Certificate generateSelfSignedCert(KeyPair keyPair) throws Exception {
        org.bouncycastle.asn1.x500.X500Name issuer =
            new org.bouncycastle.asn1.x500.X500Name("CN=MockServer HTTP/3 Test, O=MockServer");
        java.math.BigInteger serial = new java.math.BigInteger(64, new SecureRandom());
        java.util.Date notBefore = new java.util.Date();
        java.util.Date notAfter = new java.util.Date(notBefore.getTime() + TimeUnit.DAYS.toMillis(1));

        org.bouncycastle.operator.ContentSigner signer =
            new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
                .build(keyPair.getPrivate());
        org.bouncycastle.cert.X509CertificateHolder holder =
            new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic()
            ).build(signer);

        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder);
    }

    // ---- assume / trust helpers ----

    private static void assumeQuicAvailable() {
        try {
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 stream-error test",
                io.netty.handler.codec.quic.Quic.isAvailable()
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 stream-error test", t
            );
        }
    }

    @SuppressWarnings("TrustAllX509TrustManager")
    private static TrustManager trustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
