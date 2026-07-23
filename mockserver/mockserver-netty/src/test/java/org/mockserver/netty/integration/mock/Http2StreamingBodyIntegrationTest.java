package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that a {@code StreamingBody} response reaches a real HTTP/2 <em>inbound</em> client
 * as incremental DATA frames on the client's own stream.
 * <p>
 * This closes the coverage gap flagged in {@link Http2SseStreamingIntegrationTest} (lines 99-111): the
 * {@code StreamingBody} sibling of GitHub issue #2419 exercised
 * {@code NettyResponseWriter.writeStreamingResponse}, which copies only the header multimap onto the
 * Netty response head and re-stamps the HTTP/2 stream id from a separate protocol-guarded field
 * (see {@code Http2StreamIds.stamp}). A {@code StreamingBody} cannot be expressed through the client
 * API, so the only end-to-end way to drive that path is the proxy/forward relay
 * ({@code StreamingResponseRelayHandler}), which produces a {@code StreamingBody} when
 * {@code streamingResponsesEnabled} is set and the upstream is a {@code text/event-stream}.
 * <p>
 * Topology: a real prior-knowledge Netty HTTP/2 (h2c) multiplex client -> a "forward" MockServer with
 * {@code streamingResponsesEnabled} -> a bare HTTP/1.1 upstream that serves an SSE stream whose head +
 * early event are sent immediately and whose late event + end-of-stream are withheld for
 * {@link #LATE_EVENT_DELAY_MS}. The h2c client records the arrival time and content of every DATA frame
 * that lands on its own request stream, proving the chunks arrive incrementally (the early event well
 * before the upstream's late-event delay) rather than being buffered into one aggregated response.
 * <p>
 * Sibling coverage lives in {@link Http2StreamingProxyResponseIntegrationTest} (same relay, HTTP/1.1
 * <em>inbound</em> raw socket) and {@link H2cMockingMatrixIntegrationTest} (h2c inbound, but non-streaming
 * respond/forward/callback/error). Neither drives a {@code StreamingBody} over an HTTP/2 inbound stream,
 * which is the exact path this test exercises.
 * <p>
 * Positive control: degrading {@code writeStreamingResponse} to buffer chunks until stream completion
 * (e.g. {@code ctx.write} without a flush, so nothing reaches the wire until the terminating
 * {@code LastHttpContent}) makes {@link #shouldDeliverStreamingBodyIncrementallyToRealHttp2Client} fail
 * on the "early DATA frame arrives promptly" assertion; dropping the stream-id stamp makes the client
 * receive nothing on its stream and every assertion fails. Restoring either turns it green.
 */
public class Http2StreamingBodyIntegrationTest {

    /**
     * How long the upstream withholds the late SSE event (and end-of-stream) after sending the head +
     * early event. Mirrors the 2s inter-event gap used by {@link Http2StreamingProxyResponseIntegrationTest}
     * so the "early frame arrives promptly" window has ample slack under CI load.
     */
    private static final long LATE_EVENT_DELAY_MS = 2000L;

    /**
     * Upper bound on how long the first DATA frame (carrying the early event) may take to arrive and
     * still count as "streamed incrementally". Comfortably below {@link #LATE_EVENT_DELAY_MS}: a
     * buffering regression withholds all DATA frames until ~{@code LATE_EVENT_DELAY_MS}, tripping this.
     */
    private static final long PROMPT_FIRST_FRAME_MS = 1500L;

    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;

    private static MockServer forwardServer;
    private static MockServerClient forwardClient;

    @BeforeClass
    public static void startServers() throws Exception {
        // A bare HTTP/1.1 upstream that serves an SSE stream: head + early event immediately, late event
        // + end-of-stream withheld for LATE_EVENT_DELAY_MS.
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

        // Forward MockServer with streaming enabled: an SSE upstream is relayed as a StreamingBody
        // rather than aggregated. The inbound listener still accepts h2c prior-knowledge clients.
        Configuration configuration = configuration().streamingResponsesEnabled(true);
        forwardServer = new MockServer(configuration);
        forwardClient = new MockServerClient("localhost", forwardServer.getLocalPort());
        forwardClient
            .when(request().withPath("/h2_streaming_sse"))
            .forward(forward().withHost("localhost").withPort(upstreamPort));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(forwardClient);
        stopQuietly(forwardServer);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully();
        }
    }

    @Test(timeout = 30000)
    public void shouldDeliverStreamingBodyIncrementallyToRealHttp2Client() throws Exception {
        // when - a real prior-knowledge h2c client requests the streamed SSE endpoint
        H2cStreamResult result = sendH2cRequestAndCollectFrames("/h2_streaming_sse");

        // then - both events arrived on the client's OWN stream. Without the stream-id stamp on the
        // streaming response head, an HTTP/2 client receives nothing here (the whole body goes out on a
        // phantom server-initiated stream) and this fails outright.
        assertThat("should receive early event on its own h2 stream: <" + result.body + ">",
            result.body, containsString("data: early"));
        assertThat("should receive late event on its own h2 stream: <" + result.body + ">",
            result.body, containsString("data: late"));

        // and - the chunks were delivered INCREMENTALLY as separate DATA frames, not buffered into one:
        // the early event's DATA frame arrived promptly, well before the upstream's late-event delay.
        assertThat("streamed chunks should arrive as >= 2 separate DATA frames (not buffered into one): "
                + result.dataFrameContents, result.dataFrameCount, greaterThanOrEqualTo(2));
        assertThat("first DATA frame (early event) should arrive promptly, proving incremental streaming "
                + "rather than buffering until the " + LATE_EVENT_DELAY_MS + "ms completion; arrived at "
                + result.firstDataFrameMs + "ms", result.firstDataFrameMs, lessThan(PROMPT_FIRST_FRAME_MS));
        // and - the frames arrived in order: the first DATA frame carries the early event, not the late one.
        assertThat("first DATA frame should carry the early event (in-order delivery): <"
                + result.firstDataFrameContent + ">", result.firstDataFrameContent, containsString("data: early"));
    }

    /**
     * What a real h2c client observed on its own stream: the concatenated DATA-frame body, the number of
     * DATA frames, the arrival time (ms since request send) and content of the first DATA frame, and the
     * per-frame contents for diagnostics.
     */
    private static final class H2cStreamResult {
        private String body;
        private int dataFrameCount;
        private long firstDataFrameMs = Long.MAX_VALUE;
        private String firstDataFrameContent = "";
        private final List<String> dataFrameContents = new ArrayList<>();
    }

    /**
     * Send a GET over h2c (cleartext HTTP/2, prior knowledge — no HTTP/1.1 upgrade) on a dedicated
     * stream and collect every DATA frame delivered to THAT stream with its arrival time, returning once
     * the stream ends (or the connection closes). Returns whatever was collected if the wait times out,
     * so a dropped stream reports "received nothing" rather than an opaque timeout.
     */
    private H2cStreamResult sendH2cRequestAndCollectFrames(String path) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            H2cStreamResult result = new H2cStreamResult();
            StringBuilder collected = new StringBuilder();
            CompletableFuture<H2cStreamResult> future = new CompletableFuture<>();

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                // the server must not initiate streams for a response; if it does (the
                                // stream-id defect) nothing lands on our stream and the body stays empty
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                            }
                        }));
                    }
                });

            Channel parent = bootstrap.connect("localhost", forwardServer.getLocalPort()).sync().channel();

            final long[] requestSentMs = {0L};

            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        try {
                            if (msg instanceof Http2DataFrame) {
                                Http2DataFrame data = (Http2DataFrame) msg;
                                String content = data.content().toString(StandardCharsets.UTF_8);
                                if (!content.isEmpty()) {
                                    long arrivedMs = System.currentTimeMillis() - requestSentMs[0];
                                    synchronized (collected) {
                                        if (result.dataFrameCount == 0) {
                                            result.firstDataFrameMs = arrivedMs;
                                            result.firstDataFrameContent = content;
                                        }
                                        result.dataFrameCount++;
                                        result.dataFrameContents.add(content);
                                        collected.append(content);
                                    }
                                }
                                if (data.isEndStream()) {
                                    complete();
                                }
                            } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                                complete();
                            }
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        // SSE closes the connection at end of stream by default
                        complete();
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        future.completeExceptionally(cause);
                    }

                    private void complete() {
                        synchronized (collected) {
                            result.body = collected.toString();
                        }
                        future.complete(result);
                    }
                })
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + forwardServer.getLocalPort())
                .path(path);
            headers.add("accept", "text/event-stream");
            requestSentMs[0] = System.currentTimeMillis();
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            try {
                return future.get(15, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                // report what did arrive - "nothing" is the diagnosis this test exists to deliver
                synchronized (collected) {
                    result.body = collected.toString();
                }
                return result;
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Bare HTTP/1.1 upstream that serves a {@code text/event-stream} SSE response: chunked head + an
     * early {@code data:} event immediately, then the late event + terminating {@code LastHttpContent}
     * withheld for {@link #LATE_EVENT_DELAY_MS}. A correct streaming relay forwards the head + early
     * event promptly; a buffering relay withholds everything until completion.
     */
    private static final class SseUpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
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
            }, LATE_EVENT_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }
}
