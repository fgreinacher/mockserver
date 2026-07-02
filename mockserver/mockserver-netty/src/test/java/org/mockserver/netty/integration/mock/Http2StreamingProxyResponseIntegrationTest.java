package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.Body;
import org.mockserver.model.ClearType;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.netty.MockServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end check of the HTTP/2 streaming forward relay (the {@code forwardProxyHttp2Upgrade} fix for
 * the opencode/Codex header timeout).
 * <p>
 * Topology: a plain HTTP/1.1 inbound client (raw socket) -> a "forward" MockServer
 * ({@code streamingResponsesEnabled} + {@code forwardProxyHttp2Upgrade}) -> a TLS upstream that
 * negotiates HTTP/2 via ALPN and serves a Server-Sent Events stream whose head + early event are sent
 * immediately and whose final event + endStream are withheld for 2 seconds.
 * <ul>
 *   <li>{@code /sse}: the SSE head must be relayed to the inbound client well before the 2s body delay,
 *       proving the HTTP/2 forward streams incrementally instead of aggregating.</li>
 *   <li>{@code /plain}: a non-streaming HTTP/2 response is aggregated to a complete response.</li>
 *   <li>{@code /fallback}: an upstream that advertises only {@code http/1.1} forces the forward to fall
 *       back to HTTP/1.1 via ALPN and still succeed.</li>
 * </ul>
 */
public class Http2StreamingProxyResponseIntegrationTest {

    private static EventLoopGroup upstreamGroup;
    private static Channel h2UpstreamChannel;
    private static int h2UpstreamPort;
    private static Channel h1OnlyUpstreamChannel;
    private static int h1OnlyUpstreamPort;

    private static MockServer forwardServer;
    private static MockServerClient forwardClient;
    private static int forwardPort;

    @BeforeClass
    public static void startServers() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate();

        // A TLS upstream advertising h2 (and http/1.1) via ALPN, serving raw HTTP/2 frames so the
        // streaming timing is fully controlled.
        SslContext h2ServerSslContext = SslContextBuilder.forServer(cert.certificate(), cert.privateKey())
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2,
                ApplicationProtocolNames.HTTP_1_1))
            .build();

        // A TLS upstream that advertises ONLY http/1.1, to exercise the ALPN h1 fallback.
        SslContext h1OnlyServerSslContext = SslContextBuilder.forServer(cert.certificate(), cert.privateKey())
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_1_1))
            .build();

        upstreamGroup = new NioEventLoopGroup(2);

        h2UpstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(h2ServerSslContext.newHandler(ch.alloc()));
                    ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                        @Override
                        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                                ctx.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel streamChannel) {
                                        streamChannel.pipeline().addLast(new H2UpstreamStreamHandler());
                                    }
                                }));
                            } else {
                                throw new IllegalStateException("h2 upstream negotiated unexpected protocol " + protocol);
                            }
                        }
                    });
                }
            })
            .bind(0).sync().channel();
        h2UpstreamPort = ((InetSocketAddress) h2UpstreamChannel.localAddress()).getPort();

        h1OnlyUpstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(h1OnlyServerSslContext.newHandler(ch.alloc()));
                    ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                        @Override
                        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                            ctx.pipeline().addLast(new HttpServerCodec());
                            ctx.pipeline().addLast(new HttpObjectAggregator(65536));
                            ctx.pipeline().addLast(new H1OnlyUpstreamHandler());
                        }
                    });
                }
            })
            .bind(0).sync().channel();
        h1OnlyUpstreamPort = ((InetSocketAddress) h1OnlyUpstreamChannel.localAddress()).getPort();

        Configuration configuration = configuration()
            .streamingResponsesEnabled(true)
            .forwardProxyHttp2Upgrade(true)
            // Deliberately BELOW the upstream's 2s inter-event gap (see the /sse handler). This makes the
            // streaming tests a regression guard for the parent-channel read-timeout bug: if a read timeout
            // were armed on the h2 PARENT channel (not swapped for the stream idle bound), it would fire at
            // 1s and truncate the stream before the late event arrives at 2s. The head + early event arrive
            // well under 1s, the streaming swap replaces the per-stream read timeout with the longer idle
            // bound, so a healthy stream survives the 2s gap; assertions on "data: late" prove no truncation.
            .maxSocketTimeoutInMillis(1000L);
        forwardServer = new MockServer(configuration);
        forwardPort = forwardServer.getLocalPort();
        forwardClient = new MockServerClient("localhost", forwardPort);

        forwardClient
            .when(request().withPath("/sse"))
            .forward(forward().withHost("127.0.0.1").withPort(h2UpstreamPort).withScheme(HttpForward.Scheme.HTTPS));
        forwardClient
            .when(request().withPath("/plain"))
            .forward(forward().withHost("127.0.0.1").withPort(h2UpstreamPort).withScheme(HttpForward.Scheme.HTTPS));
        forwardClient
            .when(request().withPath("/fallback"))
            .forward(forward().withHost("127.0.0.1").withPort(h1OnlyUpstreamPort).withScheme(HttpForward.Scheme.HTTPS));
        forwardClient
            .when(request().withPath("/sse-no-content-type"))
            .forward(forward().withHost("127.0.0.1").withPort(h2UpstreamPort).withScheme(HttpForward.Scheme.HTTPS));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(forwardClient);
        stopQuietly(forwardServer);
        if (h2UpstreamChannel != null) {
            h2UpstreamChannel.close();
        }
        if (h1OnlyUpstreamChannel != null) {
            h1OnlyUpstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully();
        }
    }

    @Before
    public void resetLog() {
        // Keep the forward expectations but clear the recorded request/response log between tests.
        forwardClient.clear(request(), ClearType.LOG);
    }

    /**
     * Per-stream HTTP/2 upstream handler. Responds to {@code /sse} with an immediate
     * {@code text/event-stream} head + early event and a 2s-delayed late event, and to {@code /plain}
     * with a single aggregated JSON response.
     */
    private static final class H2UpstreamStreamHandler extends ChannelInboundHandlerAdapter {
        private String path = "";

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                    if (headersFrame.headers().path() != null) {
                        path = headersFrame.headers().path().toString();
                    }
                    if (headersFrame.isEndStream()) {
                        respond(ctx);
                    }
                } else if (msg instanceof io.netty.handler.codec.http2.Http2DataFrame) {
                    if (((io.netty.handler.codec.http2.Http2DataFrame) msg).isEndStream()) {
                        respond(ctx);
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        private void respond(ChannelHandlerContext ctx) {
            if (path.startsWith("/plain")) {
                Http2Headers headers = new DefaultHttp2Headers().status("200");
                headers.set("content-type", "application/json");
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(headers, false));
                ctx.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.copiedBuffer("{\"ok\":true}", StandardCharsets.UTF_8), true));
            } else {
                // For /sse-no-content-type mimic the OpenAI Codex backend: an SSE stream with NO
                // content-type at all. It can only be detected as streaming from the request's own
                // intent (Accept: text/event-stream), exercising EXPECT_STREAMING_RESPONSE on the
                // HTTP/2 upstream forward path.
                boolean withContentType = !path.startsWith("/sse-no-content-type");
                Http2Headers headers = new DefaultHttp2Headers().status("200");
                if (withContentType) {
                    headers.set("content-type", "text/event-stream");
                }
                headers.set("cache-control", "no-cache");
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(headers, false));
                ctx.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.copiedBuffer("data: early\n\n", StandardCharsets.UTF_8), false));
                ctx.executor().schedule(() -> {
                    if (ctx.channel().isActive()) {
                        ctx.writeAndFlush(new DefaultHttp2DataFrame(
                            Unpooled.copiedBuffer("data: late\n\n", StandardCharsets.UTF_8), true));
                    }
                }, 2000, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Plain HTTP/1.1 upstream used only by the ALPN-fallback case. */
    private static final class H1OnlyUpstreamHandler extends io.netty.channel.SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            byte[] body = "fallback-http1-ok".getBytes(StandardCharsets.UTF_8);
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(body));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            HttpUtil.setContentLength(resp, body.length);
            ctx.writeAndFlush(resp);
        }
    }

    /** A raw response read back with the time (ms) until the first response byte arrived. */
    private static final class TimedResponse {
        final long firstByteMs;
        final String body;

        TimedResponse(long firstByteMs, String body) {
            this.firstByteMs = firstByteMs;
            this.body = body;
        }
    }

    private TimedResponse sendAndMeasure(String path) throws Exception {
        return sendAndMeasure(path, "");
    }

    private TimedResponse sendAndMeasure(String path, String extraHeaders) throws Exception {
        String rawRequest = "GET " + path + " HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            extraHeaders +
            "Connection: close\r\n\r\n";
        try (Socket socket = new Socket("localhost", forwardPort)) {
            socket.setSoTimeout(15000);
            OutputStream output = socket.getOutputStream();
            long start = System.currentTimeMillis();
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            InputStream in = socket.getInputStream();
            int first = in.read(); // blocks until the first response byte is relayed back
            long firstByteMs = System.currentTimeMillis() - start;
            StringBuilder sb = new StringBuilder();
            if (first != -1) {
                sb.append((char) first);
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            return new TimedResponse(firstByteMs, sb.toString());
        }
    }

    private void pollUntilTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for condition to become true");
            }
            Thread.sleep(50);
        }
    }

    @Test(timeout = 30000)
    public void shouldStreamHttp2SseResponseIncrementally() throws Exception {
        TimedResponse r = sendAndMeasure("/sse");

        assertThat("response should contain HTTP 200", r.body, containsString("200"));
        assertThat("should receive the early event", r.body, containsString("data: early"));
        assertThat("should receive the late event", r.body, containsString("data: late"));
        // The opencode fix: the head is relayed well before the upstream's 2s body completion, proving
        // the HTTP/2 forward streamed incrementally rather than aggregating.
        assertThat("response head should arrive promptly (streaming), not after the 2s body delay",
            r.firstByteMs, lessThan(1500L));

        pollUntilTrue(() -> forwardClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/sse")).length >= 1);
        LogEventRequestAndResponse[] recorded = forwardClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/sse"));
        assertThat("should have recorded at least one /sse request", recorded.length, greaterThanOrEqualTo(1));
        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());
        // The streamed SSE text must be captured as STRING, not BINARY.
        assertThat("SSE streaming body should be logged as STRING",
            loggedResponse.getBody().getType(), is(Body.Type.STRING));
        assertThat("logged body should contain the streamed SSE event text",
            loggedResponse.getBodyAsString(), allOf(containsString("data: early"), containsString("data: late")));
    }

    @Test(timeout = 30000)
    public void shouldStreamHttp2SseResponseWithNoContentTypeWhenRequestSignalsStreaming() throws Exception {
        // The upstream sends an SSE stream with NO content-type; streaming can only be detected from
        // the request's Accept: text/event-stream intent, threaded onto the HTTP/2 upstream forward
        // channel as EXPECT_STREAMING_RESPONSE. Proves the content-type-less streaming parity on the
        // HTTP/2 upstream forward path.
        TimedResponse r = sendAndMeasure("/sse-no-content-type", "Accept: text/event-stream\r\n");

        assertThat("response should contain HTTP 200", r.body, containsString("200"));
        assertThat("should receive the early event", r.body, containsString("data: early"));
        assertThat("should receive the late event", r.body, containsString("data: late"));
        assertThat("head should arrive promptly (streamed), not after the 2s body delay",
            r.firstByteMs, lessThan(1500L));

        pollUntilTrue(() -> forwardClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/sse-no-content-type")).length >= 1);
        LogEventRequestAndResponse[] recorded = forwardClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/sse-no-content-type"));
        assertThat("should have recorded the request", recorded.length, greaterThanOrEqualTo(1));
        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("streamed body should be logged as STRING",
            loggedResponse.getBody().getType(), is(Body.Type.STRING));
        assertThat("logged body should contain the streamed SSE event text",
            loggedResponse.getBodyAsString(), allOf(containsString("data: early"), containsString("data: late")));
    }

    @Test(timeout = 30000)
    public void shouldAggregateNonStreamingHttp2Response() throws Exception {
        TimedResponse r = sendAndMeasure("/plain");

        assertThat("response should contain HTTP 200", r.body, containsString("200"));
        assertThat("response should contain the aggregated JSON body", r.body, containsString("{\"ok\":true}"));

        pollUntilTrue(() -> forwardClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/plain")).length >= 1);
        LogEventRequestAndResponse[] recorded = forwardClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/plain"));
        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());
        // The recorded JSON body is normalised/pretty-printed, so assert on its content not exact form.
        assertThat("aggregated body should contain the JSON",
            loggedResponse.getBodyAsString(), allOf(containsString("\"ok\""), containsString("true")));
    }

    @Test(timeout = 30000)
    public void shouldFallBackToHttp1WhenUpstreamDoesNotNegotiateHttp2() throws Exception {
        TimedResponse r = sendAndMeasure("/fallback");

        assertThat("response should contain HTTP 200", r.body, containsString("200"));
        assertThat("forward should fall back to HTTP/1.1 and still succeed",
            r.body, containsString("fallback-http1-ok"));
    }
}
