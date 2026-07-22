package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpClassCallback.callback;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Runs the core mocking-action matrix — respond, forward, class-callback, and error — over cleartext
 * HTTP/2 (h2c) using a REAL prior-knowledge Netty multiplex client on the INSECURE port, asserting on
 * the bytes the client actually receives on its own stream.
 * <p>
 * Motivation (test-coverage audit gap #3): the full action matrix in
 * {@link AbstractExtendedNettyMockingIntegrationTest} runs only over HTTP/1.1 and h2-over-TLS. h2c was
 * exercised only by {@code PortUnificationH2cPipelineTest} (an {@code EmbeddedChannel} pipeline-shape
 * assertion) and gRPC-unary — no test drove a real cleartext-HTTP/2 socket client through respond /
 * forward / callback / error and proved the response <em>body</em> arrived. The shared integration
 * harness cannot cover this: its {@code NettyHttpClient} has no h2c prior-knowledge path (an insecure
 * request with {@code Protocol.HTTP_2} silently falls back to HTTP/1.1 — see the "TODO support http2
 * in plain text" note in {@code HttpClientInitializer#initChannel}), so a subclass of the abstract
 * suite would assert HTTP/1.1 behaviour while claiming h2c coverage. This focused IT instead uses the
 * same real multiplex client the SSE/stream-error h2c tests use, connecting over a socket with the
 * HTTP/2 connection preface (prior knowledge, no upgrade dance).
 * <p>
 * This is the streaming / stream-id sibling of gRPC issue #2419: a server handler that writes a
 * response without stamping the request's HTTP/2 stream id routes it onto a phantom server-initiated
 * stream, the server logs success, and the client's stream receives an empty body. A body-received
 * assertion over a real h2c stream is what catches that class of defect.
 */
public class H2cMockingMatrixIntegrationTest {

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer upstreamServer;
    private static MockServerClient upstreamClient;

    @BeforeClass
    public static void startServer() {
        upstreamServer = new MockServer();
        upstreamClient = new MockServerClient("localhost", upstreamServer.getLocalPort());
        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        stopQuietly(upstreamClient);
        stopQuietly(upstreamServer);
    }

    @Before
    public void reset() {
        mockServerClient.reset();
        upstreamClient.reset();
    }

    @Test
    public void shouldReceiveRespondBodyOverH2c() throws Exception {
        // given
        mockServerClient
            .when(request().withPath("/h2c_respond"))
            .respond(
                response()
                    .withStatusCode(201)
                    .withBody("respond_body_over_h2c")
            );

        // when - a real prior-knowledge h2c client sends the request over cleartext HTTP/2
        H2cResult result = sendH2cRequest("GET", "/h2c_respond", null);

        // then - the response body arrived on the client's own stream over cleartext HTTP/2
        assertThat("status over h2c: <" + result.status + ">", result.status, is("201"));
        assertThat("body received over h2c: <" + result.body + ">", result.body, is("respond_body_over_h2c"));
    }

    @Test
    public void shouldReceiveCallbackBodyOverH2c() throws Exception {
        // given - a class callback that echoes the request body back
        mockServerClient
            .when(request().withPath("/h2c_callback"))
            .respond(callback().withCallbackClass(EchoBodyCallback.class));

        // when
        H2cResult result = sendH2cRequest("POST", "/h2c_callback", "callback_echo_body_over_h2c");

        // then - the callback-produced body arrived over cleartext HTTP/2
        assertThat("body received over h2c: <" + result.body + ">", result.body, is("callback_echo_body_over_h2c"));
    }

    @Test
    public void shouldReceiveForwardedBodyOverH2c() throws Exception {
        // given - an upstream server returning a body, and a forward expectation pointing at it
        upstreamClient
            .when(request().withPath("/h2c_forward"))
            .respond(response().withBody("forwarded_body_over_h2c"));
        mockServerClient
            .when(request().withPath("/h2c_forward"))
            .forward(forward().withHost("localhost").withPort(upstreamServer.getLocalPort()));

        // when
        H2cResult result = sendH2cRequest("GET", "/h2c_forward", null);

        // then - the upstream body was relayed back to the h2c client on its own stream
        assertThat("body received over h2c: <" + result.body + ">", result.body, is("forwarded_body_over_h2c"));
    }

    @Test
    public void shouldResetStreamForErrorOverH2c() throws Exception {
        // given - an error action that resets the matched HTTP/2 stream with REFUSED_STREAM (0x7)
        mockServerClient
            .when(request().withPath("/h2c_error"))
            .error(error().withStreamError(HttpError.StreamErrorCode.REFUSED_STREAM));

        // when
        H2cResult result = sendH2cRequest("GET", "/h2c_error", null);

        // then - the client observed an RST_STREAM carrying the configured error code (no body)
        assertThat("reset code over h2c: <" + result.resetCode + ">", result.resetCode, is(0x7L));
    }

    /**
     * Response callback that echoes the request body back so the received body over h2c proves the
     * callback action actually ran and its response reached the client's own stream.
     */
    @SuppressWarnings("unused")
    public static class EchoBodyCallback implements ExpectationResponseCallback {
        @Override
        public HttpResponse handle(HttpRequest request) {
            return response().withBody(request.getBodyAsString());
        }
    }

    /**
     * What a real h2c client observed on its own stream: the {@code :status} pseudo-header, the
     * concatenated DATA-frame body, and (for the error path) any RST_STREAM error code.
     */
    private static class H2cResult {
        private String status;
        private String body;
        private Long resetCode;
    }

    /**
     * Send a single request over h2c (HTTP/2 cleartext, prior knowledge — no HTTP/1.1 upgrade) on a
     * dedicated stream and collect what the client receives on THAT stream: the response status, its
     * DATA-frame body, and any RST_STREAM code. Completes on end-of-stream, on reset, or on connection
     * close, so a dropped body reports "" rather than an opaque timeout.
     */
    private H2cResult sendH2cRequest(String method, String path, String requestBody) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            H2cResult result = new H2cResult();
            StringBuilder collected = new StringBuilder();
            CompletableFuture<H2cResult> future = new CompletableFuture<>();

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
                            } else if (msg instanceof Http2ResetFrame) {
                                result.resetCode = ((Http2ResetFrame) msg).errorCode();
                                complete();
                            }
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        if (evt instanceof Http2ResetFrame) {
                            result.resetCode = ((Http2ResetFrame) evt).errorCode();
                            complete();
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

            boolean hasBody = requestBody != null;
            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.valueOf(method).asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + mockServer.getLocalPort())
                .path(path);
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, !hasBody));
            if (hasBody) {
                streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.wrappedBuffer(requestBody.getBytes(StandardCharsets.UTF_8)), true));
            }

            try {
                return future.get(15, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                result.body = collected.toString();
                return result;
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
