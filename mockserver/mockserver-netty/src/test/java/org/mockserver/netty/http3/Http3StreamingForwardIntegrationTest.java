package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
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
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Drives the HTTP/3 streaming response-body path through the FULL mocking action pipeline with a real
 * Netty QUIC client: a {@code forward} expectation on the HTTP/3 port relays an upstream Server-Sent
 * Events stream, and the client asserts it receives the streamed events as SEPARATE DATA frames arriving
 * INCREMENTALLY on its own request stream.
 * <p>
 * Motivation (test-coverage audit gap #28): the existing HTTP/3 streaming coverage does not exercise the
 * production pipeline end-to-end over QUIC.
 * <ul>
 *   <li>{@link Http3StreamingIntegrationTest} drives {@link Http3ResponseWriter} DIRECTLY from a
 *       hand-built QUIC server handler — it proves the writer frames a {@code StreamingBody} correctly
 *       but bypasses {@code MockServerClient} / expectation matching / {@code HttpActionHandler}, so a
 *       defect anywhere between "expectation matched" and "writer invoked" would be invisible.</li>
 *   <li>{@link Http3MockingMatrixIntegrationTest} drives the real pipeline over QUIC (respond / forward /
 *       callback / error) but every arm is NON-streaming: the forwarded upstream returns a complete body,
 *       so {@link Http3ResponseWriter#writeStreamingResponse} is never reached and incremental delivery is
 *       never asserted.</li>
 * </ul>
 * This test is the missing intersection: the streaming forward relay ({@code streamingResponsesEnabled})
 * produces a {@code StreamingBody} response which funnels through {@code ResponseWriter.writeResponse ->
 * Http3ResponseWriter.sendResponse -> writeStreamingResponse}, emitting one HTTP/3 DATA frame per relayed
 * chunk. The decisive assertions are on TIMING: the early event reaches the QUIC client well before the
 * upstream withholds the late event for 1.5s, and the two events are separated by that gap on the wire —
 * which only holds if chunks are streamed as they arrive rather than buffered and flushed together.
 * <p>
 * QUIC-gated exactly like the sibling HTTP/3 integration tests via {@link #assumeQuicAvailable()} so it
 * SKIPS cleanly (rather than errors) on any platform without the native QUIC transport (BoringSSL). In CI
 * the {@code assert-suite-ran.sh} surefire guard fails the build loudly if a QUIC-capable agent skips the
 * whole suite, so a fail-safe skip off-CI does not become a silent false-green on-CI.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3StreamingForwardIntegrationTest {

    /** The upstream withholds the late SSE event for this long after the early event. */
    private static final long UPSTREAM_LATE_EVENT_DELAY_MS = 1500L;

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int http3Port;

    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;

    private NioEventLoopGroup clientGroup;

    @BeforeClass
    public static void startServers() throws Exception {
        // A bare HTTP/1.1 Netty upstream that serves an SSE stream: response head + early event
        // immediately, then the late event + end-of-stream withheld for UPSTREAM_LATE_EVENT_DELAY_MS.
        upstreamGroup = new NioEventLoopGroup(1);
        upstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(65536));
                    ch.pipeline().addLast(new SseUpstreamHandler());
                }
            })
            .bind(0).sync().channel();
        upstreamPort = ((InetSocketAddress) upstreamChannel.localAddress()).getPort();

        // Front MockServer with HTTP/3 (QUIC) enabled AND streaming-response relaying enabled, so a
        // forwarded SSE upstream is relayed incrementally as a StreamingBody rather than aggregated.
        int udpPort = org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .http3MaxIdleTimeout(30000L)
            .streamingResponsesEnabled(true);
        mockServer = new MockServer(config, 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        http3Port = mockServer.getHttp3Port();
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully();
        }
    }

    @Before
    public void reset() {
        assumeQuicAvailable();
        // http3Port(0) means "HTTP/3 disabled"; if the H3 server never started there is nothing to drive.
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);
        mockServerClient.reset();
    }

    @After
    public void tearDown() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    @Test
    public void shouldStreamForwardedSseEventsIncrementallyOverHttp3() throws Exception {
        // given - a forward expectation on the HTTP/3 port pointing at the SSE upstream. The match /
        // forward / streaming-relay all run inside the server; nothing here writes to the QUIC stream by
        // hand (contrast Http3StreamingIntegrationTest, which drives the writer directly).
        mockServerClient
            .when(request().withPath("/h3_stream_forward"))
            .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

        // when - a real QUIC client requests it over HTTP/3, signalling streaming intent
        StreamResult result = sendHttp3StreamingRequest("/h3_stream_forward");

        // then - both events arrived on the client's own QUIC request stream, through the full pipeline
        assertThat("early event received over http3: <" + result.body + ">",
            result.body, containsString("data: early"));
        assertThat("late event received over http3: <" + result.body + ">",
            result.body, containsString("data: late"));

        // decisive incremental assertions (these fail RED if the write path buffers chunks and flushes
        // them together, i.e. if Http3ResponseWriter.writeStreamingResponse aggregates instead of
        // streaming): the early event reaches the client well before the upstream's late-event delay ...
        assertThat("early event should arrive promptly (streamed), not after the " + UPSTREAM_LATE_EVENT_DELAY_MS
                + "ms upstream delay; earlyMs=" + result.earlyEventMs,
            result.earlyEventMs, lessThan(UPSTREAM_LATE_EVENT_DELAY_MS - 300));
        // ... and the two events are separated on the wire by (most of) that delay, proving they arrived
        // as distinct DATA frames over time rather than bundled into one flush.
        assertThat("early and late events should arrive as separate DATA frames spread across the upstream "
                + "delay; earlyMs=" + result.earlyEventMs + " lateMs=" + result.lateEventMs,
            result.lateEventMs - result.earlyEventMs, greaterThanOrEqualTo(UPSTREAM_LATE_EVENT_DELAY_MS - 500));
    }

    // ---- upstream SSE handler ----

    /**
     * Serves {@code /h3_stream_forward} as an HTTP/1.1 chunked {@code text/event-stream}: head + early
     * event immediately, then the late event + {@link LastHttpContent} withheld for
     * {@link #UPSTREAM_LATE_EVENT_DELAY_MS}. The delay is what makes the client-side timing assertions
     * decisive between "streamed incrementally" and "buffered then flushed".
     */
    @io.netty.channel.ChannelHandler.Sharable
    private static final class SseUpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            head.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);
            ctx.writeAndFlush(new DefaultHttpContent(
                Unpooled.copiedBuffer("data: early\n\n", StandardCharsets.UTF_8)));
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttpContent(
                            Unpooled.copiedBuffer("data: late\n\n", StandardCharsets.UTF_8)))
                        .addListener(f -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                            .addListener(ChannelFutureListener.CLOSE));
                }
            }, UPSTREAM_LATE_EVENT_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    // ---- client ----

    /**
     * What a real QUIC client observed on its own request stream: the concatenated DATA-frame body and
     * the elapsed time (ms, from request send) at which the early and late SSE markers first appeared.
     */
    private static final class StreamResult {
        volatile String body = "";
        volatile long earlyEventMs = -1;
        volatile long lateEventMs = -1;
    }

    /**
     * Send a single GET over HTTP/3 (QUIC) on a dedicated request stream, timestamping each DATA frame as
     * it arrives, and return the collected body plus when the early / late SSE events first appeared.
     * Completes on end-of-stream, reset, or connection close.
     */
    private StreamResult sendHttp3StreamingRequest(String path) throws Exception {
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
            .remoteAddress(new InetSocketAddress("127.0.0.1", http3Port))
            .connect()
            .get(15, TimeUnit.SECONDS);

        StreamResult result = new StreamResult();
        StringBuilder collected = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        // Set on the event loop when the request headers are flushed, so DATA-frame timestamps are
        // measured from the moment the request left the client.
        long[] startNanos = {System.nanoTime()};

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    // status not asserted here; the body markers + timing are the subject of this test
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    long elapsedMs = (System.nanoTime() - startNanos[0]) / 1_000_000L;
                    synchronized (collected) {
                        collected.append(content.toString(StandardCharsets.UTF_8));
                        String soFar = collected.toString();
                        if (result.earlyEventMs < 0 && soFar.contains("data: early")) {
                            result.earlyEventMs = elapsedMs;
                        }
                        if (result.lateEventMs < 0 && soFar.contains("data: late")) {
                            result.lateEventMs = elapsedMs;
                        }
                    }
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    done.countDown();
                    ctx.close();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
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
        requestHeaders.headers().authority("127.0.0.1:" + http3Port);
        // signal streaming intent so the relay streams regardless of upstream content-type detection
        requestHeaders.headers().add("accept", "text/event-stream");

        startNanos[0] = System.nanoTime();
        requestStream.writeAndFlush(requestHeaders)
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            .sync();

        // wait for the whole stream: early event (prompt) + late event (~1.5s) + end-of-stream
        done.await(20, TimeUnit.SECONDS);
        synchronized (collected) {
            result.body = collected.toString();
        }

        quicChannel.close().sync();
        clientChannel.close().sync();

        return result;
    }

    // ---- assume / trust helpers ----

    private static void assumeQuicAvailable() {
        try {
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 streaming-forward test",
                io.netty.handler.codec.quic.Quic.isAvailable()
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 streaming-forward test", t
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
