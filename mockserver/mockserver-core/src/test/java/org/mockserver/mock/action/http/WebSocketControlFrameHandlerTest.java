package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * MockServer handshakes mocked WebSockets by hand, which installs only the frame codec — so
 * without {@link WebSocketControlFrameHandler} a mocked WebSocket answers no PING and echoes no
 * CLOSE, and every keepalive-based client eventually tears the session down.
 */
public class WebSocketControlFrameHandlerTest {

    private static WebSocketServerHandshaker handshaker() {
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        request.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        try {
            return new WebSocketServerHandshakerFactory("ws://localhost/ws", null, true, 65536)
                .newHandshaker(request);
        } finally {
            request.release();
        }
    }

    /** Captures whatever the control handler forwards downstream. */
    private static class CapturingHandler extends ChannelInboundHandlerAdapter {
        final List<WebSocketFrame> received = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof WebSocketFrame frame) {
                received.add(frame);
            }
        }
    }

    // --- PING -> PONG (RFC 6455 §5.5.2, a MUST) ---

    @Test
    public void shouldAnswerPingWithPong() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketControlFrameHandler(handshaker()));

        channel.writeInbound(new PingWebSocketFrame(
            Unpooled.copiedBuffer("keepalive", StandardCharsets.UTF_8)));

        Object outbound = channel.readOutbound();
        assertThat("a PING must be answered with a PONG", outbound, instanceOf(PongWebSocketFrame.class));
        PongWebSocketFrame pong = (PongWebSocketFrame) outbound;
        // RFC 6455 §5.5.3 -- the Pong carries the Ping's application data verbatim
        assertThat(pong.content().toString(StandardCharsets.UTF_8), is("keepalive"));
        pong.release();

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldAnswerEmptyPingWithEmptyPong() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketControlFrameHandler(handshaker()));

        channel.writeInbound(new PingWebSocketFrame(Unpooled.EMPTY_BUFFER));

        PongWebSocketFrame pong = channel.readOutbound();
        assertNotNull("an empty PING must still be answered", pong);
        assertThat(pong.content().readableBytes(), is(0));
        pong.release();

        channel.finishAndReleaseAll();
    }

    /**
     * PING is an expressible matcher frame type, so the control handler must answer the ping
     * without consuming it — swallowing it here would silently stop PING matchers firing.
     */
    @Test
    public void shouldForwardPingDownstreamAfterAnswering() {
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new WebSocketControlFrameHandler(handshaker()), downstream);

        channel.writeInbound(new PingWebSocketFrame(
            Unpooled.copiedBuffer("ping", StandardCharsets.UTF_8)));

        assertThat(downstream.received.size(), is(1));
        assertThat(downstream.received.get(0), instanceOf(PingWebSocketFrame.class));
        downstream.received.get(0).release();

        PongWebSocketFrame pong = channel.readOutbound();
        assertNotNull(pong);
        pong.release();

        channel.finishAndReleaseAll();
    }

    // --- CLOSE echo (RFC 6455 §5.5.1) ---

    @Test
    public void shouldEchoCloseWithTheClientsStatusCodeAndReason() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketControlFrameHandler(handshaker()));

        channel.writeInbound(new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, "bye"));

        Object outbound = channel.readOutbound();
        assertThat("a CLOSE must be echoed", outbound, instanceOf(CloseWebSocketFrame.class));
        CloseWebSocketFrame echo = (CloseWebSocketFrame) outbound;
        assertThat(echo.statusCode(), is(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE.code()));
        assertThat(echo.reasonText(), is("bye"));
        echo.release();

        assertFalse("the channel must be closed after the echo", channel.isActive());
        channel.finishAndReleaseAll();
    }

    /**
     * A payload-less close is legal on the wire but 1005 is a reserved code that must never be
     * sent, so the echo falls back to 1000.
     */
    @Test
    public void shouldEchoEmptyCloseAsNormalClosure() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketControlFrameHandler(handshaker()));

        channel.writeInbound(new CloseWebSocketFrame(true, 0, Unpooled.EMPTY_BUFFER));

        CloseWebSocketFrame echo = channel.readOutbound();
        assertNotNull("a payload-less CLOSE must still be echoed", echo);
        assertThat(echo.statusCode(), is(WebSocketCloseStatus.NORMAL_CLOSURE.code()));
        echo.release();

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldNotEchoReservedStatusCodes() {
        // 1006 (abnormal closure) is never valid to send
        CloseWebSocketFrame reserved = new CloseWebSocketFrame(true, 0, Unpooled.copiedBuffer(new byte[]{0x03, (byte) 0xEE}));
        try {
            CloseWebSocketFrame echo = WebSocketControlFrameHandler.echoOf(reserved);
            assertThat(echo.statusCode(), is(WebSocketCloseStatus.NORMAL_CLOSURE.code()));
            echo.release();
        } finally {
            reserved.release();
        }
    }

    // --- non-control frames pass straight through ---

    @Test
    public void shouldPassDataFramesThroughUntouched() {
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new WebSocketControlFrameHandler(handshaker()), downstream);

        channel.writeInbound(new TextWebSocketFrame("hello"));
        channel.writeInbound(new BinaryWebSocketFrame(Unpooled.copiedBuffer(new byte[]{1, 2, 3})));

        assertThat(downstream.received.size(), is(2));
        assertThat(downstream.received.get(0), instanceOf(TextWebSocketFrame.class));
        assertThat(downstream.received.get(1), instanceOf(BinaryWebSocketFrame.class));
        assertNull("no control response for data frames", channel.readOutbound());
        downstream.received.forEach(WebSocketFrame::release);

        channel.finishAndReleaseAll();
    }

    /**
     * A PONG is a response, never a request — it must not itself be answered, or two peers each
     * running a keepalive would ping-pong forever.
     */
    @Test
    public void shouldNotAnswerPong() {
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new WebSocketControlFrameHandler(handshaker()), downstream);

        channel.writeInbound(new PongWebSocketFrame(Unpooled.EMPTY_BUFFER));

        assertNull("a PONG must not be answered", channel.readOutbound());
        assertThat(downstream.received.size(), is(1));
        downstream.received.get(0).release();

        channel.finishAndReleaseAll();
    }
}
