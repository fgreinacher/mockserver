package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
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
import org.mockserver.model.StreamingBody;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration test for HTTP/3 streaming responses using an in-JVM Netty QUIC
 * client and server. Verifies that streaming chunks (SSE / chunked proxy
 * forwarding / LLM streaming) flow correctly over QUIC streams.
 * <p>
 * Skips gracefully when the native QUIC transport (BoringSSL) is not available.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3StreamingIntegrationTest {

    private static final Configuration CONFIGURATION = configuration();
    private static final MockServerLogger LOGGER = new MockServerLogger(Http3StreamingIntegrationTest.class);

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

    /**
     * Test that multiple streaming chunks sent via Http3ResponseWriter are
     * received by an HTTP/3 client as separate DATA frames.
     */
    @Test
    public void shouldStreamMultipleChunksOverHttp3() throws Exception {
        // The chunks to stream
        String[] chunks = {
            "data: event-1\n\n",
            "data: event-2\n\n",
            "data: event-3\n\n"
        };

        // Start a QUIC server with a custom handler that uses Http3ResponseWriter
        // to send a streaming response
        int port = startStreamingServer(chunks);

        // Connect with QUIC client and collect response
        String[] result = sendHttp3Request(port, "GET", "/events");

        // Verify
        assertThat("status should be 200", result[0], is("200"));
        // All chunks should have been received (concatenated in the body)
        String body = result[1];
        for (String chunk : chunks) {
            assertThat("body should contain chunk: " + chunk, body, containsString(chunk));
        }
        assertThat("body should be exactly the concatenation of all chunks",
            body, is("data: event-1\n\ndata: event-2\n\ndata: event-3\n\n"));
    }

    /**
     * Test that an empty streaming response (headers only, immediate complete)
     * works correctly over HTTP/3.
     */
    @Test
    public void shouldHandleEmptyStreamOverHttp3() throws Exception {
        // No chunks -- immediate complete
        String[] chunks = {};

        int port = startStreamingServer(chunks);
        String[] result = sendHttp3Request(port, "GET", "/empty-stream");

        assertThat("status should be 200", result[0], is("200"));
        assertThat("body should be empty", result[1], is(""));
    }

    /**
     * Test that a streaming response with a single large chunk works correctly.
     */
    @Test
    public void shouldStreamLargeChunkOverHttp3() throws Exception {
        // Build a large payload (~16KB)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("data: line-").append(i)
              .append(" padding-to-make-this-longer-for-realistic-size\n");
        }
        String largeChunk = sb.toString();

        int port = startStreamingServer(new String[]{largeChunk});
        String[] result = sendHttp3Request(port, "GET", "/large");

        assertThat("status should be 200", result[0], is("200"));
        assertThat("body should match the large chunk", result[1], is(largeChunk));
    }

    // ---- server helpers ----

    /**
     * Start a QUIC/HTTP3 server that sends a streaming response with the given chunks.
     * Each chunk is sent as a separate ByteBuf through the StreamingBody.
     */
    private int startStreamingServer(String[] chunks) throws Exception {
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
                            ch.pipeline().addLast(new StreamingTestHandler(chunks));
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
     * A minimal HTTP/3 request handler that responds with a streaming body.
     * Uses Http3ResponseWriter to verify the streaming path end-to-end over
     * real QUIC transport.
     */
    private static class StreamingTestHandler extends Http3RequestStreamInboundHandler {

        private final String[] chunks;

        StreamingTestHandler(String[] chunks) {
            this.chunks = chunks;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
            // Build a streaming response using Http3ResponseWriter
            StreamingBody streamingBody = new StreamingBody(65536);

            HttpResponse resp = response()
                .withStatusCode(200)
                .withHeader("content-type", "text/event-stream")
                .withStreamingBody(streamingBody);

            HttpRequest req = HttpRequest.request()
                .withMethod("GET")
                .withPath("/events");

            // Send via Http3ResponseWriter (this subscribes to the StreamingBody)
            Http3ResponseWriter writer = new Http3ResponseWriter(CONFIGURATION, LOGGER, ctx);
            writer.sendResponse(req, resp);

            // Push chunks into the streaming body (from the event loop to ensure
            // proper ordering). The subscribe callback was registered synchronously
            // above, so the chunks will be forwarded immediately.
            ctx.channel().eventLoop().execute(() -> {
                for (String chunk : chunks) {
                    ByteBuf buf = Unpooled.wrappedBuffer(chunk.getBytes(StandardCharsets.UTF_8));
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
            // nothing to do
        }
    }

    // ---- client helpers ----

    /**
     * Send an HTTP/3 request and return [status, body].
     */
    private String[] sendHttp3Request(int port, String method, String path) throws Exception {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().sync();
        }
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
        BlockingQueue<String> bodyQueue = new LinkedBlockingQueue<>();

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                private final StringBuilder bodyBuilder = new StringBuilder();

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    CharSequence status = headersFrame.headers().status();
                    statusQueue.offer(status != null ? status.toString() : "null");
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    bodyBuilder.append(content.toString(StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    bodyQueue.offer(bodyBuilder.toString());
                    ctx.close();
                }
            }
        ).sync().getNow();

        // send request headers (no body)
        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method(method);
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);

        requestStream.writeAndFlush(requestHeaders)
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            .sync();

        // wait for response
        String status = statusQueue.poll(20, TimeUnit.SECONDS);
        String responseBody = bodyQueue.poll(20, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        return new String[]{
            status != null ? status : "null",
            responseBody != null ? responseBody : ""
        };
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
            boolean available = io.netty.handler.codec.quic.Quic.isAvailable();
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 streaming test",
                available
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 streaming test",
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
