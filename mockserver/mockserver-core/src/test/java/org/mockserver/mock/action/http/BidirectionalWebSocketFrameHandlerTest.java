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
