package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.Test;
import org.mockserver.model.WebSocketFrameType;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.model.WebSocketMessageMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;

public class BidirectionalWebSocketFrameHandlerTest {

    @Test
    public void shouldMatchTextFrameByExactText() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    /**
     * A negated text matcher was previously accepted by the model and dropped by the DTO, and even
     * when it survived the handler ignored the flag entirely — so "respond to anything that is NOT
     * ping" matched ping and nothing else. GrpcBidiRuleMatcher already honoured its equivalent flag.
     */
    @Test
    public void shouldHonourNegatedTextMatcher() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextMatcher(org.mockserver.model.NottableString.string("hello", true));

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame matching = new TextWebSocketFrame("hello");
        try {
            assertThat("negated matcher must NOT match its own value", handler.matches(matcher, matching), is(false));
        } finally {
            matching.release();
        }

        TextWebSocketFrame other = new TextWebSocketFrame("world");
        try {
            assertThat("negated matcher must match everything else", handler.matches(matcher, other), is(true));
        } finally {
            other.release();
        }
    }

    @Test
    public void shouldNotMatchTextFrameByDifferentText() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("world");
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldMatchTextFrameByRegex() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex("hello.*");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("hello world");
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldNotMatchTextFrameWhenRegexDoesNotMatch() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex("^ping$");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("pong");
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldMatchAnyFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame textFrame = new TextWebSocketFrame("test");
        try {
            assertThat(handler.matches(matcher, textFrame), is(true));
        } finally {
            textFrame.release();
        }
    }

    @Test
    public void shouldNotMatchWrongFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.BINARY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame textFrame = new TextWebSocketFrame("test");
        try {
            assertThat(handler.matches(matcher, textFrame), is(false));
        } finally {
            textFrame.release();
        }
    }

    @Test
    public void shouldMatchTextFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.TEXT);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame textFrame = new TextWebSocketFrame("test");
        try {
            assertThat(handler.matches(matcher, textFrame), is(true));
        } finally {
            textFrame.release();
        }
    }

    @Test
    public void shouldHandleInvalidRegexGracefully() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex("[invalid");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("test");
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldMatchDefaultMatcherWithNoText() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher();

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("anything");
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    // --- a text matcher must not match non-text frames ---
    //
    // Reachability note: `withText`/`withTextRegex`/`withTextMatcher` all pin `frameType` to
    // TEXT, and `WebSocketMessageMatcherDTO.buildObject` calls `withText` last, so a matcher
    // built from control-plane JSON always ends up frameType=TEXT and never reaches the text
    // comparison with a non-text frame. The fall-through is therefore reachable only by setting
    // the frame type *after* the text on the Java API -- which is exactly what a user writes to
    // express "match this text on any frame type". These tests use that ordering deliberately;
    // building them the other way round would assert the frame-type guard instead and would stay
    // green with the defect present.

    /**
     * The text-content check used to be guarded by {@code frame instanceof TextWebSocketFrame},
     * so a non-text frame skipped the block entirely and fell through to {@code return true} --
     * the matcher then fired its responses on the client's keepalive PING traffic.
     */
    @Test
    public void shouldNotMatchPingFrameWithATextMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat("a text matcher must not be satisfied by a PING frame",
                handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldNotMatchPongFrameWithATextMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        PongWebSocketFrame frame = new PongWebSocketFrame();
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldNotMatchBinaryFrameWithATextMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        BinaryWebSocketFrame frame = new BinaryWebSocketFrame(
            io.netty.buffer.Unpooled.copiedBuffer("hello", java.nio.charset.StandardCharsets.UTF_8));
        try {
            assertThat("a text matcher cannot be satisfied by a binary frame, "
                + "even one whose bytes spell the pattern", handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    /**
     * A text matcher explicitly typed PING is self-contradictory; before the fix it matched every
     * PING regardless of the configured text.
     */
    @Test
    public void shouldNotMatchPingFrameWithATextMatcherTypedPing() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.PING);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldNotMatchNonTextFrameWithATextRegexMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    /**
     * A <em>negated</em> text matcher does not vacuously match a frame carrying no text either.
     *
     * <p>This is the interaction between the non-text guard and the negation support: treating
     * {@code not("hello")} as true for a PING would reopen the very defect the guard closes, with
     * the matcher's responses firing on client keepalive traffic. A matcher expressed in terms of
     * text content does not apply to frames that have none, in either polarity.
     */
    @Test
    public void shouldNotMatchPingFrameWithANegatedTextMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextMatcher(org.mockserver.model.NottableString.string("hello", true))
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat("a negated text matcher must not vacuously match a PING",
                handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    /**
     * A matcher with no text content still matches any frame type -- only a *text* matcher is
     * restricted to text frames.
     */
    @Test
    public void shouldStillMatchPingFrameWithATypeOnlyMatcher() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.PING);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    /** A text matcher must still match the text frame it was written for. */
    @Test
    public void shouldStillMatchTextFrameWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldTrackSentResponses() {
        List<WebSocketMessage> sentMessages = new ArrayList<>();
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(webSocketMessage("pong"));

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg) -> sentMessages.add(msg)
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("ping");
        try {
            // Verify match works correctly
            assertThat(handler.matches(matcher, frame), is(true));
            // Verify responses are configured
            assertThat(matcher.getResponses().size(), is(1));
            assertThat(matcher.getResponses().get(0).getText(), is("pong"));
        } finally {
            frame.release();
        }
    }
}
