package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.client.RealtimeMockBuilder;
import org.mockserver.llm.realtime.RealtimeModality;
import org.mockserver.llm.realtime.RealtimeTurn;
import org.mockserver.model.Delay;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A WebSocket bidi reply set (one delayed task per frame) must never be partially delivered because a
 * delayed-task budget is full: the frames are counted but not shed, so a client waiting for the whole turn
 * receives all of it.
 */
public class OverloadWebSocketReplyIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TURN_TEXT = "Bonjour le monde, comment allez-vous aujourd'hui";

    @Test(timeout = 60_000)
    public void shouldDeliverEveryFrameOfABidiReplyWhenTheDelayedBudgetsAreFull() throws Exception {
        MockServer mockServer = new MockServer(configuration().maxPendingDelayedResponses(1), 0);
        try {
            int port = mockServer.getLocalPort();
            Scheduler scheduler = mockServer.getScheduler();
            // fill both delayed budgets so any shed or refused frame would be dropped
            scheduler.submitAsync(Scheduler.sheddable(() -> {
            }), Delay.seconds(30));
            scheduler.schedule(Scheduler.rejectable(() -> {
            }, () -> {
            }), false, Delay.seconds(30));

            RealtimeMockBuilder.geminiLive("/ws/gemini")
                .withModality(RealtimeModality.TEXT)
                .respondingWith(RealtimeTurn.realtimeTurn(TURN_TEXT).withInputTokens(4).withOutputTokens(9))
                .applyTo(new MockServerClient("localhost", port));

            List<String> received = driveSession(port, "/ws/gemini", List.of(
                "{\"setup\":{\"model\":\"models/gemini-2.0-flash\"}}",
                "{\"clientContent\":{\"turns\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"turnComplete\":true}}"));

            List<String> parts = new ArrayList<>();
            boolean sawTurnComplete = false;
            for (String frame : received) {
                JsonNode node = OBJECT_MAPPER.readTree(frame);
                JsonNode frameParts = node.path("serverContent").path("modelTurn").path("parts");
                if (frameParts.isArray() && frameParts.size() > 0 && frameParts.get(0).has("text")) {
                    parts.add(frameParts.get(0).path("text").asText());
                }
                sawTurnComplete |= node.path("serverContent").path("turnComplete").asBoolean(false);
            }
            // order-insensitive: the multi-threaded scheduler does not strictly order closely spaced frames;
            // completeness is what is under test
            assertThat("every text delta arrived", String.join("", parts).length(), is(TURN_TEXT.length()));
            assertThat(sawTurnComplete, is(true));
            assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.SIDE_ACTIONS), is(0L));
        } finally {
            mockServer.stop();
        }
    }

    /**
     * Sends each frame, then collects inbound text frames until a {@code turnComplete} arrives and a short
     * settle period passes, so a frame arriving after the terminal one is still counted.
     */
    private static List<String> driveSession(int port, String path, List<String> framesToSend) throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Boolean> handshakeComplete = new CompletableFuture<>();
        CompletableFuture<Boolean> terminalReached = new CompletableFuture<>();
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                new URI("ws://localhost:" + port + path), WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), Integer.MAX_VALUE);
            Channel channel = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(1 << 20), new SimpleChannelInboundHandler<Object>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                handshaker.handshake(ctx.channel());
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                if (!handshaker.isHandshakeComplete()) {
                                    handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                                    handshakeComplete.complete(true);
                                } else if (msg instanceof TextWebSocketFrame) {
                                    String text = ((TextWebSocketFrame) msg).text();
                                    received.add(text);
                                    if (text.contains("turnComplete")) {
                                        terminalReached.complete(true);
                                    }
                                }
                            }
                        });
                    }
                })
                .connect("localhost", port).sync().channel();
            handshakeComplete.get(5, TimeUnit.SECONDS);
            for (String frame : framesToSend) {
                channel.writeAndFlush(new TextWebSocketFrame(frame)).sync();
                Thread.sleep(50);
            }
            terminalReached.get(10, TimeUnit.SECONDS);
            Thread.sleep(500);
            channel.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
        return received;
    }
}
