package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.client.RealtimeMockBuilder;
import org.mockserver.llm.realtime.RealtimeModality;
import org.mockserver.llm.realtime.RealtimeTurn;
import org.mockserver.netty.MockServer;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.stop.Stop.stopQuietly;

public class RealtimeMockingIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static int mockServerPort;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    private static JsonNode parse(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    /**
     * Connect a WebSocket client to {@code path}, send each frame in {@code framesToSend} (spaced slightly), and
     * collect every inbound text frame until one contains {@code terminalSubstring} (or a timeout elapses).
     */
    private List<String> driveSession(String path, List<String> framesToSend, String terminalSubstring) throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Boolean> handshakeComplete = new CompletableFuture<>();
        CompletableFuture<Boolean> terminalReached = new CompletableFuture<>();

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            URI uri = new URI("ws://localhost:" + mockServerPort + path);
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), Integer.MAX_VALUE
            );

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                            new HttpClientCodec(),
                            new HttpObjectAggregator(1 << 20),
                            new SimpleChannelInboundHandler<Object>() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    handshaker.handshake(ctx.channel());
                                }

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                    if (!handshaker.isHandshakeComplete()) {
                                        handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                                        handshakeComplete.complete(true);
                                        return;
                                    }
                                    if (msg instanceof TextWebSocketFrame) {
                                        String text = ((TextWebSocketFrame) msg).text();
                                        received.add(text);
                                        if (text.contains(terminalSubstring)) {
                                            terminalReached.complete(true);
                                        }
                                    } else if (msg instanceof CloseWebSocketFrame) {
                                        ctx.close();
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    handshakeComplete.completeExceptionally(cause);
                                    ctx.close();
                                }
                            }
                        );
                    }
                });

            Channel channel = bootstrap.connect("localhost", mockServerPort).sync().channel();
            handshakeComplete.get(5, TimeUnit.SECONDS);
            for (String frame : framesToSend) {
                channel.writeAndFlush(new TextWebSocketFrame(frame)).sync();
                Thread.sleep(50);
            }
            terminalReached.get(5, TimeUnit.SECONDS);
            channel.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
        return received;
    }

    @Test
    public void shouldDriveScriptedOpenAiRealtimeSession() throws Exception {
        RealtimeMockBuilder.openAiRealtime("/v1/realtime")
            .withModel("gpt-realtime")
            .respondingWith(RealtimeTurn.realtimeTurn("The capital of France is Paris.")
                .withInputTokens(20).withOutputTokens(7))
            .applyTo(mockServerClient);

        List<String> received = driveSession("/v1/realtime",
            List.of("{\"type\":\"response.create\"}"), "response.done");

        // pushed on connect
        assertThat(parse(received.get(0)).path("type").asText(), is("session.created"));

        List<String> types = new ArrayList<>();
        StringBuilder transcript = new StringBuilder();
        JsonNode done = null;
        for (String frame : received) {
            JsonNode node = parse(frame);
            String type = node.path("type").asText();
            types.add(type);
            if (type.equals("response.output_audio_transcript.delta")) {
                transcript.append(node.path("delta").asText());
            }
            if (type.equals("response.done")) {
                done = node;
            }
        }

        assertThat(types, hasItems("session.created", "response.created", "response.output_item.added",
            "response.content_part.added", "response.output_audio_transcript.delta", "response.done"));
        assertThat(transcript.toString(), is("The capital of France is Paris."));
        assertThat(done, notNullValue());
        assertThat(done.path("response").path("usage").path("input_tokens").asInt(), is(20));
        assertThat(done.path("response").path("usage").path("output_tokens").asInt(), is(7));
    }

    @Test
    public void shouldAcknowledgeOpenAiSessionUpdate() throws Exception {
        RealtimeMockBuilder.openAiRealtime("/v1/realtime")
            .respondingWith("hello")
            .applyTo(mockServerClient);

        List<String> received = driveSession("/v1/realtime",
            List.of("{\"type\":\"session.update\",\"session\":{\"voice\":\"alloy\"}}"), "session.updated");

        List<String> types = new ArrayList<>();
        for (String frame : received) {
            types.add(parse(frame).path("type").asText());
        }
        assertThat(types, hasItem("session.created"));
        assertThat(types, hasItem("session.updated"));
    }

    @Test
    public void shouldDriveScriptedGeminiLiveSession() throws Exception {
        RealtimeMockBuilder.geminiLive("/ws/gemini")
            .withModality(RealtimeModality.TEXT)
            .respondingWith(RealtimeTurn.realtimeTurn("Bonjour le monde")
                .withInputTokens(4).withOutputTokens(3))
            .applyTo(mockServerClient);

        List<String> received = driveSession("/ws/gemini",
            List.of(
                "{\"setup\":{\"model\":\"models/gemini-2.0-flash\"}}",
                "{\"clientContent\":{\"turns\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"turnComplete\":true}}"),
            "turnComplete");

        boolean sawSetupComplete = false;
        StringBuilder text = new StringBuilder();
        JsonNode turnCompleteMsg = null;
        for (String frame : received) {
            JsonNode node = parse(frame);
            if (node.has("setupComplete")) {
                sawSetupComplete = true;
            }
            JsonNode parts = node.path("serverContent").path("modelTurn").path("parts");
            if (parts.isArray() && parts.size() > 0 && parts.get(0).has("text")) {
                text.append(parts.get(0).path("text").asText());
            }
            if (node.path("serverContent").path("turnComplete").asBoolean(false)) {
                turnCompleteMsg = node;
            }
        }

        assertThat(sawSetupComplete, is(true));
        assertThat(text.toString(), is("Bonjour le monde"));
        assertThat(turnCompleteMsg, notNullValue());
        assertThat(turnCompleteMsg.path("usageMetadata").path("totalTokenCount").asInt(), is(7));
    }
}
