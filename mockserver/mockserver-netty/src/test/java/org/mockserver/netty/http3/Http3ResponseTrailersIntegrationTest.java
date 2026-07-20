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
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration test for general HTTP/3 response trailers using an in-JVM Netty QUIC
 * client and server. Verifies that a non-streaming response carrying trailers is
 * emitted as initial HEADERS + DATA + a trailing HEADERS frame, mirroring the
 * gRPC-over-HTTP/3 trailer pattern. Skips gracefully when the native QUIC transport
 * (BoringSSL) is not available.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3ResponseTrailersIntegrationTest {

    private static final Configuration CONFIGURATION = configuration();
    private static final MockServerLogger LOGGER = new MockServerLogger(Http3ResponseTrailersIntegrationTest.class);

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
    public void shouldSendResponseTrailersAsTrailingHeadersFrameOverHttp3() throws Exception {
        // given
        HttpResponse response = response()
            .withStatusCode(200)
            .withBody("hello trailer world")
            .withTrailer("x-checksum", "abc123")
            .withTrailer("x-signature", "deadbeef");

        int port = startServer(response);

        // when
        Result result = sendHttp3Request(port, "GET", "/with-trailers");

        // then
        assertThat("status should be 200", result.status, is("200"));
        assertThat("body should be the response body", result.body, is("hello trailer world"));
        // the second (trailing) HEADERS frame carries the trailers
        assertThat("trailers should be received", result.trailers, is(notNullValue()));
        assertThat(result.trailers.get("x-checksum"), is("abc123"));
        assertThat(result.trailers.get("x-signature"), is("deadbeef"));
        // a trailer frame must not carry the :status pseudo-header
        assertThat(result.trailers.containsKey(":status"), is(false));
    }

    @Test
    public void shouldSendTrailersOnStreamingResponseAsTrailingHeadersFrameOverHttp3() throws Exception {
        // given -- a STREAMING response that also carries trailers (INC-02 coverage for HTTP/3):
        // after the final DATA frame the writer must emit a trailing HEADERS frame.
        int port = startStreamingServer(new String[]{"chunk-1", "chunk-2"});

        // when
        Result result = sendHttp3Request(port, "GET", "/events");

        // then
        assertThat("status should be 200", result.status, is("200"));
        assertThat("body should be the concatenated chunks", result.body, is("chunk-1chunk-2"));
        assertThat("trailers should be received", result.trailers, is(notNullValue()));
        assertThat(result.trailers.get("x-checksum"), is("abc123"));
        assertThat(result.trailers.containsKey(":status"), is(false));
    }

    // ---- server ----

    private int startStreamingServer(String[] chunks) throws Exception {
        return startServerWithInitializer(ch -> ch.pipeline().addLast(new StreamingResponseTestHandler(chunks)));
    }

    private static class StreamingResponseTestHandler extends Http3RequestStreamInboundHandler {
        private final String[] chunks;

        StreamingResponseTestHandler(String[] chunks) {
            this.chunks = chunks;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
            org.mockserver.model.StreamingBody streamingBody = new org.mockserver.model.StreamingBody(65536);
            HttpResponse resp = response()
                .withStatusCode(200)
                .withHeader("content-type", "text/event-stream")
                .withStreamingBody(streamingBody)
                .withTrailer("x-checksum", "abc123");
            HttpRequest req = HttpRequest.request().withMethod("GET").withPath("/events");

            new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx).sendResponse(req, resp);

            ctx.channel().eventLoop().execute(() -> {
                for (String chunk : chunks) {
                    io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(chunk.getBytes(StandardCharsets.UTF_8));
                    streamingBody.addChunk(buf);
                    buf.release();
                }
                streamingBody.complete();
            });
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
            dataFrame.release();
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
        }
    }

    private int startServer(HttpResponse response) throws Exception {
        return startServerWithInitializer(ch -> ch.pipeline().addLast(new ResponseTestHandler(response)));
    }

    private int startServerWithInitializer(java.util.function.Consumer<QuicStreamChannel> streamInitializer) throws Exception {
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
                            streamInitializer.accept(ch);
                        }
                    }
                ))
                .build())
            .bind(new InetSocketAddress(0))
            .sync()
            .channel();

        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    private static class ResponseTestHandler extends Http3RequestStreamInboundHandler {
        private final HttpResponse response;

        ResponseTestHandler(HttpResponse response) {
            this.response = response;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
            HttpRequest req = HttpRequest.request().withMethod("GET").withPath("/with-trailers");
            new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx).sendResponse(req, response);
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
            dataFrame.release();
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
        }
    }

    // ---- client ----

    private static final class Result {
        String status;
        String body;
        Map<String, String> trailers;
    }

    private Result sendHttp3Request(int port, String method, String path) throws Exception {
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

        BlockingQueue<Result> resultQueue = new LinkedBlockingQueue<>();

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                private final Result result = new Result();
                private final StringBuilder bodyBuilder = new StringBuilder();
                private boolean seenInitialHeaders = false;

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    if (!seenInitialHeaders) {
                        seenInitialHeaders = true;
                        CharSequence status = headersFrame.headers().status();
                        result.status = status != null ? status.toString() : "null";
                    } else {
                        // trailing HEADERS frame
                        Map<String, String> trailers = new LinkedHashMap<>();
                        headersFrame.headers().forEach(entry ->
                            trailers.put(entry.getKey().toString(), entry.getValue().toString()));
                        result.trailers = trailers;
                    }
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    bodyBuilder.append(content.toString(StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    result.body = bodyBuilder.toString();
                    resultQueue.offer(result);
                    ctx.close();
                }
            }
        ).sync().getNow();

        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method(method);
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);

        requestStream.writeAndFlush(requestHeaders)
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            .sync();

        Result result = resultQueue.poll(20, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        if (result == null) {
            throw new AssertionError("no response received over HTTP/3 within timeout");
        }
        return result;
    }

    // ---- crypto / assume helpers (mirrors Http3StreamingIntegrationTest) ----

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

    private static void assumeQuicAvailable() {
        try {
            boolean available = io.netty.handler.codec.quic.Quic.isAvailable();
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 trailers test",
                available
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 trailers test",
                t
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
