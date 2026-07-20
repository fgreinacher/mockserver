package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;

/**
 * Answers WebSocket control frames on mocked (user-facing) WebSocket connections.
 *
 * <p>MockServer performs the WebSocket handshake by hand via
 * {@link WebSocketServerHandshaker#handshake}, which installs only the frame encoder and
 * decoder — unlike Netty's {@code WebSocketServerProtocolHandler}, it contributes no control-frame
 * behaviour at all. Without this handler a mocked WebSocket therefore never answers a PING and
 * never echoes a CLOSE, so every keepalive-based client (browsers, OkHttp's {@code pingInterval},
 * Java-WebSocket's connection-lost detector) eventually tears the connection down mid-test.
 * RFC 6455 §5.5.2 makes the Pong a MUST, and §5.5.1 requires the close echo.
 *
 * <p>Installed immediately after a successful handshake, ahead of
 * {@link GraphQLSubscriptionHandler} and {@link BidirectionalWebSocketFrameHandler}.
 *
 * <p>PING frames are answered <em>and</em> forwarded downstream, because
 * {@link org.mockserver.model.WebSocketFrameType#PING} is an expressible matcher frame type —
 * swallowing the ping here would silently stop those matchers firing.
 */
public class WebSocketControlFrameHandler extends ChannelInboundHandlerAdapter {

    private final WebSocketServerHandshaker handshaker;

    public WebSocketControlFrameHandler(WebSocketServerHandshaker handshaker) {
        this.handshaker = handshaker;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof PingWebSocketFrame pingFrame) {
            // RFC 6455 §5.5.3: the Pong MUST carry the Ping's application data verbatim.
            ctx.writeAndFlush(new PongWebSocketFrame(pingFrame.content().retain()));
            // forward so PING-typed matchers still see the frame
            ctx.fireChannelRead(pingFrame);
            return;
        }
        if (msg instanceof CloseWebSocketFrame closeFrame) {
            try {
                if (handshaker != null && ctx.channel().isActive()) {
                    handshaker.close(ctx.channel(), echoOf(closeFrame));
                } else {
                    ctx.close();
                }
            } finally {
                closeFrame.release();
            }
            return;
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * Build the close frame echoed back to the client, mirroring its status code and reason.
     *
     * <p>A client may legitimately send a close frame with no payload
     * ({@link CloseWebSocketFrame#statusCode()} returns {@code -1}), and codes such as 1005/1006
     * are reserved and must never appear on the wire, so anything not valid to send is echoed as
     * 1000 NORMAL_CLOSURE.
     */
    static CloseWebSocketFrame echoOf(CloseWebSocketFrame closeFrame) {
        int statusCode = closeFrame.statusCode();
        if (statusCode == -1 || !WebSocketCloseStatus.isValidStatusCode(statusCode)) {
            return new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE);
        }
        String reason = closeFrame.reasonText();
        return new CloseWebSocketFrame(statusCode, reason != null ? reason : "");
    }
}
