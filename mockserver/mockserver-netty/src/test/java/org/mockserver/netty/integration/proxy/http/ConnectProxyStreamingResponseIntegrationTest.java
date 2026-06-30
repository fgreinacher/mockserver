package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.netty.MockServer;

import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Assert.assertContains;
import static org.mockserver.testing.tls.SSLSocketFactory.sslSocketFactory;

/**
 * Regression test for DEFECT C: the CONNECT-proxy (transparent HTTPS) loopback relay must stream a
 * no-Content-Type Server-Sent Events response (such as the OpenAI Codex backend used by the opencode
 * CLI) incrementally rather than aggregating it to completion.
 * <p>
 * Topology exercised here:
 * <pre>
 *   raw client socket --(TLS)--> MockServer CONNECT proxy --(loopback, TLS)--> MockServer server
 *                                                                                    |
 *                                                            forward action (plaintext) to upstream
 *                                                                                    v
 *                                                                       bare SSE upstream (no Content-Type)
 * </pre>
 * The loopback leg carries the response back through {@code StreamingAwareHttpObjectAggregator}
 * (relay-only mode). Before the fix, that aggregator buffered the whole stream (no {@code text/event-stream}
 * Content-Type and no streaming hint on the loopback channel), so the response head only reached the client
 * after the upstream completed. The fix propagates the request's streaming intent ({@code "stream": true}
 * in the body, or {@code Accept: text/event-stream}) onto the loopback channel so the relay streams.
 */
public class ConnectProxyStreamingResponseIntegrationTest {

    private static final long LATE_EVENT_DELAY_MS = 2000;

    private static MockServerClient mockServerClient;
    private static int mockServerPort;
    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;

    private static boolean originalStreamingEnabled;

    @BeforeClass
    public static void startServers() throws Exception {
        originalStreamingEnabled = ConfigurationProperties.streamingResponsesEnabled();
        ConfigurationProperties.streamingResponsesEnabled(true);

        // A bare plaintext upstream that streams a Server-Sent Events response with NO Content-Type
        // (mimics the OpenAI Codex backend). It sends the head + an early event immediately, then
        // withholds the late event + completion for LATE_EVENT_DELAY_MS.
        upstreamGroup = new NioEventLoopGroup(1);
        ServerBootstrap b = new ServerBootstrap();
        b.group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(65536));
                    ch.pipeline().addLast(new NoContentTypeSseUpstreamHandler());
                }
            });
        upstreamChannel = b.bind(0).sync().channel();
        upstreamPort = ((InetSocketAddress) upstreamChannel.localAddress()).getPort();

        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully();
        }
        ConfigurationProperties.streamingResponsesEnabled(originalStreamingEnabled);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        // Forward everything to the plaintext SSE upstream. The forward action's default HTTP scheme
        // connects to the upstream in plaintext even though the inbound request arrived over the
        // (secure) CONNECT tunnel.
        mockServerClient
            .when(request())
            .forward(forward().withHost("localhost").withPort(upstreamPort));
    }

    @Test
    public void shouldRelayOrdinaryBufferedResponseThroughConnectProxy() throws Exception {
        // Regression guard: an ordinary fixed-length (non-streaming) response is forwarded and relayed
        // back through the CONNECT loopback unchanged. Proves the streaming-intent propagation does not
        // disturb the normal buffered relay path.
        String req = "GET /normal HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "\r\n";
        TimedResponse r = sendOverTunnelAndMeasure(req, "{\"status\":\"ok\"}");
        assertThat(r.body, containsString("HTTP/1.1 200 OK"));
        assertThat(r.body, containsString("{\"status\":\"ok\"}"));
    }

    @ChannelHandler.Sharable
    static class NoContentTypeSseUpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if ("/normal".equals(request.uri())) {
                byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(body));
                resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                HttpUtil.setContentLength(resp, body.length);
                ctx.writeAndFlush(resp);
                return;
            }
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            // Deliberately NO Content-Type header (this is the codex-backend behaviour).
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

    /** Establishes a CONNECT tunnel through the proxy, upgrades to TLS, and returns the secure socket. */
    private SSLSocket connectTunnel(Socket socket) throws Exception {
        OutputStream output = socket.getOutputStream();
        output.write(("CONNECT 127.0.0.1:443 HTTP/1.1\r\n" +
            "Host: 127.0.0.1:443\r\n" +
            "\r\n").getBytes(UTF_8));
        output.flush();

        // Read the CONNECT response line ("HTTP/1.1 200 OK") byte by byte up to the blank line, so we
        // do not over-read into the TLS handshake bytes that follow on the same socket.
        StringBuilder connectResponse = new StringBuilder();
        InputStream in = socket.getInputStream();
        int b;
        while ((b = in.read()) != -1) {
            connectResponse.append((char) b);
            if (connectResponse.toString().endsWith("\r\n\r\n")) {
                break;
            }
        }
        assertContains(connectResponse.toString(), "HTTP/1.1 200 OK");
        return sslSocketFactory().wrapSocket(socket);
    }

    /** A relayed response read back with the time (ms) until the first byte arrived. */
    private static final class TimedResponse {
        final long firstByteMs;
        final String body;

        TimedResponse(long firstByteMs, String body) {
            this.firstByteMs = firstByteMs;
            this.body = body;
        }
    }

    private TimedResponse sendOverTunnelAndMeasure(String rawRequest, String terminalMarker) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServerPort)) {
            socket.setSoTimeout(15000);
            try (SSLSocket sslSocket = connectTunnel(socket)) {
                OutputStream output = sslSocket.getOutputStream();
                long start = System.currentTimeMillis();
                output.write(rawRequest.getBytes(UTF_8));
                output.flush();

                InputStream in = sslSocket.getInputStream();
                int first = in.read(); // blocks until the first relayed response byte arrives
                long firstByteMs = System.currentTimeMillis() - start;
                StringBuilder sb = new StringBuilder();
                if (first != -1) {
                    sb.append((char) first);
                    byte[] buf = new byte[4096];
                    int n;
                    // Read until the terminal marker is seen (the CONNECT tunnel is keep-alive, so the
                    // socket is not closed when the response completes), or until the peer closes.
                    while (sb.indexOf(terminalMarker) < 0 && (n = in.read(buf)) != -1) {
                        sb.append(new String(buf, 0, n, UTF_8));
                    }
                }
                return new TimedResponse(firstByteMs, sb.toString());
            }
        }
    }

    @Test
    public void shouldStreamNoContentTypeSseThroughConnectProxyWhenClientRequestedStream() throws Exception {
        // The request body carries "stream": true, so the loopback relay must stream the no-Content-Type
        // SSE response incrementally: the head + early event must reach the client well before the
        // upstream's LATE_EVENT_DELAY_MS completion. Before the fix the loopback aggregator buffered the
        // whole stream and the first byte only arrived after completion (~2s).
        String body = "{\"stream\":true,\"model\":\"x\"}";
        String req = "POST /codex-stream HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + body.getBytes(UTF_8).length + "\r\n" +
            "\r\n" + body;

        TimedResponse r = sendOverTunnelAndMeasure(req, "data: late");

        assertThat("response should contain HTTP 200", r.body, containsString("200"));
        assertThat("should receive the early event", r.body, containsString("data: early"));
        assertThat("should receive the late event", r.body, containsString("data: late"));
        assertThat("response head should arrive promptly (streaming), not after the " + LATE_EVENT_DELAY_MS
                + "ms upstream completion",
            r.firstByteMs, lessThan(1500L));
    }

    @Test
    public void shouldAggregateNoContentTypeResponseThroughConnectProxyWhenClientDidNotRequestStream() throws Exception {
        // Same no-Content-Type upstream and CONNECT loopback path, but a plain GET with no streaming
        // intent. The loopback relay must aggregate as before (the WAR/Tomcat chunked-without-content-length
        // guard: chunked-no-content-length is NOT treated as streaming unless the client asked for it), so
        // the first byte only arrives after the upstream completes. Proves the streaming relay is gated on
        // request intent — ordinary buffered proxy traffic is unaffected.
        String req = "GET /codex-stream HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "\r\n";

        TimedResponse r = sendOverTunnelAndMeasure(req, "data: late");

        assertThat("aggregated response should still contain both events",
            r.body, allOf(containsString("data: early"), containsString("data: late")));
        assertThat("without a streaming request the response is buffered until completion (~"
                + LATE_EVENT_DELAY_MS + "ms)",
            r.firstByteMs, greaterThanOrEqualTo(1500L));
    }
}
