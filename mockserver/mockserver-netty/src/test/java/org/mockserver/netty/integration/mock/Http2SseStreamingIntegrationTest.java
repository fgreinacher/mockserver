package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
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
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.netty.MockServer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that streaming responses actually reach a real HTTP/2 client.
 * <p>
 * This is the test that would have caught GitHub issue #2419 and its siblings. The defect class is
 * that a handler writes raw Netty objects without stamping the request's HTTP/2 stream id, so
 * {@code HttpToHttp2ConnectionHandler} routes the response onto a fresh <em>server-initiated</em>
 * stream. The server logs a normal successful response; the client's stream receives nothing and it
 * hangs until its own timeout. Every server-side assertion still passes, which is why #2419 shipped
 * and why the SSE and streaming-body instances shipped alongside it.
 * <p>
 * An in-JVM Netty HTTP/2 (h2c) multiplex client is used rather than the shared integration harness
 * for two reasons: the harness's {@code StreamingAwareHttpObjectAggregator} relays
 * {@code text/event-stream} instead of aggregating it (so it cannot assert an SSE body at all), and
 * only a real multiplex client can prove the data arrived on <em>the client's own stream</em> rather
 * than on some other stream. Before the fix these tests fail by timing out with an empty body.
 */
public class Http2SseStreamingIntegrationTest {

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

    @Test
    public void shouldDeliverSseEventsToRealHttp2Client() throws Exception {
        // given - an SSE expectation with two events
        mockServerClient.upsert(
            new Expectation(request().withPath("/http2_sse"))
                .thenRespondWithSse(
                    HttpSseResponse.sseResponse()
                        .withEvents(
                            SseEvent.sseEvent().withEvent("first").withData("sse_event_one"),
                            SseEvent.sseEvent().withEvent("second").withData("sse_event_two")
                        )
                )
        );

        // when - a real HTTP/2 client requests it over h2c
        String body = sendH2cRequestAndCollectBody("/http2_sse");

        // then - every event arrived on the client's own stream. Without the stream id on the
        // response head this is empty, because the whole stream went out on a phantom stream.
        assertThat("SSE body received: <" + body + ">", body, containsString("event: first"));
        assertThat("SSE body received: <" + body + ">", body, containsString("data: sse_event_one"));
        assertThat("SSE body received: <" + body + ">", body, containsString("event: second"));
        assertThat("SSE body received: <" + body + ">", body, containsString("data: sse_event_two"));
    }

    // NOTE - the StreamingBody sibling of this bug (NettyResponseWriter.writeStreamingResponse,
    // which copied only the header multimap onto the Netty head and so dropped the stream id held in
    // a separate field) is NOT covered here, because a StreamingBody cannot be expressed through the
    // client API: it has no serializer or DTO, and the only production code that sets one is
    // StreamingResponseRelayHandler on the proxy/forward relay path. It is covered at unit level by
    // NettyResponseWriterTest.shouldSendStreamingResponseHeadDownTheRequestHttp2Stream, whose
    // assertion was verified to go red when the fix is degraded.
    //
    // End-to-end coverage would mean an HTTP/2-inbound variant of
    // StreamingProxyResponseIntegrationTest (which today relays an upstream SSE stream over an
    // HTTP/1.1 inbound raw socket). That is worth adding - it is the real-world path for streaming
    // LLM responses through the proxy over HTTP/2 - but it needs upstream-server scaffolding beyond
    // this change.

    @Test
    public void shouldDeliverStaticResponseToRealHttp2Client() throws Exception {
        // control case: the non-streaming path has always been correct because it writes the model
        // response through the mapper. Its presence here keeps the comparison honest - if this ever
        // fails too, the problem is the harness rather than the streaming paths.
        mockServerClient
            .when(request().withPath("/http2_static"))
            .respond(org.mockserver.model.HttpResponse.response().withBody("static_over_http2"));

        String body = sendH2cRequestAndCollectBody("/http2_static");

        assertThat(body, containsString("static_over_http2"));
    }

    /**
     * Send a GET over h2c on a dedicated stream and collect every DATA frame delivered to that
     * stream, returning once the stream ends (or the connection closes). Returns whatever was
     * collected if the wait times out, so a failure reports "received nothing" rather than an
     * opaque {@link java.util.concurrent.TimeoutException}.
     */
    private String sendH2cRequestAndCollectBody(String path) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            StringBuilder collected = new StringBuilder();
            CompletableFuture<String> bodyFuture = new CompletableFuture<>();

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
                                // the server must not initiate streams for a response; if it does
                                // (the exact bug under test) nothing lands on our stream and the
                                // collected body stays empty
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                            }
                        }));
                    }
                });

            Channel parent = bootstrap.connect("localhost", mockServer.getLocalPort()).sync().channel();

            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        try {
                            if (msg instanceof Http2DataFrame) {
                                Http2DataFrame data = (Http2DataFrame) msg;
                                synchronized (collected) {
                                    collected.append(data.content().toString(StandardCharsets.UTF_8));
                                }
                                if (data.isEndStream()) {
                                    bodyFuture.complete(collected.toString());
                                }
                            } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                                bodyFuture.complete(collected.toString());
                            }
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        // SSE closes the connection at end of stream by default
                        synchronized (collected) {
                            bodyFuture.complete(collected.toString());
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        bodyFuture.completeExceptionally(cause);
                    }
                })
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + mockServer.getLocalPort())
                .path(path);
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            try {
                return bodyFuture.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                // report what did arrive - "nothing" is the diagnosis this test exists to deliver
                synchronized (collected) {
                    return collected.toString();
                }
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
