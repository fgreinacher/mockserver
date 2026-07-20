package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.junit.Test;
import org.mockserver.model.Delay;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.SelectionSetMatchType;
import org.mockserver.model.WebSocketMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;

public class GraphQLSubscriptionHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * A FrameSender that behaves like the production one in the two ways that matter for
     * protocol correctness:
     *
     * <ol>
     *   <li><b>It writes to the channel</b>, the same sink {@code connection_ack}, {@code error}
     *       and {@code complete} use. The previous fake collected {@code next} frames into a
     *       private list while the terminal {@code complete} went to the channel, so the two
     *       were asserted from separate sinks and their relative order on the wire was
     *       structurally unobservable — which is precisely why this suite stayed green while
     *       {@code complete} was being emitted before every {@code next}.</li>
     *   <li><b>It defers a delayed frame</b> rather than discarding the delay, so a delayed send
     *       really does complete later than an undelayed one. Call {@link #fireDelayed()} to
     *       stand in for the scheduler firing.</li>
     * </ol>
     */
    private static class RecordingFrameSender implements GraphQLSubscriptionHandler.FrameSender {
        final List<String> sentMessages = new ArrayList<>();
        final List<Delay> sentDelays = new ArrayList<>();
        private final Deque<Runnable> deferred = new ArrayDeque<>();

        @Override
        public void send(ChannelHandlerContext ctx, String text, Delay delay, Runnable onSent) {
            sentDelays.add(delay);
            Runnable write = () -> {
                if (text != null) {
                    sentMessages.add(text);
                    ctx.writeAndFlush(new TextWebSocketFrame(text));
                }
                onSent.run();
            };
            if (delay != null) {
                deferred.add(write);
            } else {
                write.run();
            }
        }

        /**
         * Run every deferred write, including any newly deferred by a continuation, mimicking
         * the scheduler draining its queue.
         */
        void fireDelayed() {
            while (!deferred.isEmpty()) {
                deferred.poll().run();
            }
        }

        boolean hasDeferred() {
            return !deferred.isEmpty();
        }
    }

    /** Drain every frame currently queued on the channel, in wire order. */
    private static List<String> drainWire(EmbeddedChannel channel) {
        List<String> frames = new ArrayList<>();
        TextWebSocketFrame frame;
        while ((frame = channel.readOutbound()) != null) {
            frames.add(frame.text());
            frame.release();
        }
        return frames;
    }

    /** The {@code type} field of each frame, in wire order. */
    private static List<String> wireTypes(EmbeddedChannel channel) throws Exception {
        List<String> types = new ArrayList<>();
        for (String frame : drainWire(channel)) {
            types.add(OBJECT_MAPPER.readTree(frame).get("type").asText());
        }
        return types;
    }

    private EmbeddedChannel createChannel(GraphQLSubscriptionHandler handler) {
        return new EmbeddedChannel(handler);
    }

    private GraphQLSubscriptionHandler createHandler(
        String subscriptionQuery,
        List<WebSocketMessage> payloads,
        RecordingFrameSender frameSender
    ) {
        GraphQLBody filter = GraphQLBody.graphQL(subscriptionQuery)
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);
        // Use a null handshaker for unit tests -- close operations won't be exercised
        return new GraphQLSubscriptionHandler(filter, payloads, frameSender, null);
    }

    private GraphQLSubscriptionHandler createHandler(
        GraphQLBody filter,
        List<WebSocketMessage> payloads,
        RecordingFrameSender frameSender
    ) {
        return new GraphQLSubscriptionHandler(filter, payloads, frameSender, null);
    }

    // --- connection_init -> connection_ack ---

    @Test
    public void shouldReplyConnectionAckOnConnectionInit() {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { userUpdated { id } }", List.of(), sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));

        // connection_ack is sent directly via ctx.writeAndFlush, not via FrameSender
        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull("Expected connection_ack response", response);
        assertThat(response.text(), containsString("\"type\":\"connection_ack\""));
        response.release();

        channel.finishAndReleaseAll();
    }

    // --- ping -> pong ---

    @Test
    public void shouldReplyPongOnPing() {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { userUpdated { id } }", List.of(), sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"ping\"}"));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull("Expected pong response", response);
        assertThat(response.text(), containsString("\"type\":\"pong\""));
        response.release();

        channel.finishAndReleaseAll();
    }

    // --- subscribe with matching query -> next...next complete ---

    @Test
    public void shouldPushNextMessagesAndCompleteOnMatchingSubscribe() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        List<WebSocketMessage> payloads = List.of(
            webSocketMessage("{\"id\":\"1\",\"name\":\"Alice\"}"),
            webSocketMessage("{\"id\":\"2\",\"name\":\"Bob\"}")
        );
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { userUpdated { id name } }", payloads, sender
        );
        EmbeddedChannel channel = createChannel(handler);

        // Send connection_init first
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        TextWebSocketFrame ack = channel.readOutbound();
        assertNotNull(ack);
        ack.release();

        // Send subscribe
        String subscribeMsg = "{\"id\":\"sub-1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { userUpdated { id name } }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        // Verify next messages were sent via FrameSender
        assertThat(sender.sentMessages.size(), is(2));

        // Verify first next message
        JsonNode next1 = OBJECT_MAPPER.readTree(sender.sentMessages.get(0));
        assertThat(next1.get("id").asText(), is("sub-1"));
        assertThat(next1.get("type").asText(), is("next"));
        assertThat(next1.get("payload").get("data").get("id").asText(), is("1"));
        assertThat(next1.get("payload").get("data").get("name").asText(), is("Alice"));

        // Verify second next message
        JsonNode next2 = OBJECT_MAPPER.readTree(sender.sentMessages.get(1));
        assertThat(next2.get("id").asText(), is("sub-1"));
        assertThat(next2.get("type").asText(), is("next"));
        assertThat(next2.get("payload").get("data").get("id").asText(), is("2"));
        assertThat(next2.get("payload").get("data").get("name").asText(), is("Bob"));

        // Verify the wire order: both next frames, then the terminal complete last
        List<String> wire = drainWire(channel);
        assertThat(wire.size(), is(3));
        assertThat(OBJECT_MAPPER.readTree(wire.get(0)).get("type").asText(), is("next"));
        assertThat(OBJECT_MAPPER.readTree(wire.get(1)).get("type").asText(), is("next"));
        JsonNode complete = OBJECT_MAPPER.readTree(wire.get(2));
        assertThat(complete.get("id").asText(), is("sub-1"));
        assertThat(complete.get("type").asText(), is("complete"));

        channel.finishAndReleaseAll();
    }

    // --- subscribe with non-matching query -> error ---

    @Test
    public void shouldSendErrorOnNonMatchingSubscribe() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { userUpdated { id } }", List.of(), sender
        );
        EmbeddedChannel channel = createChannel(handler);

        // Send connection_init
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        TextWebSocketFrame ack = channel.readOutbound();
        assertNotNull(ack);
        ack.release();

        // Send subscribe with a different query
        String subscribeMsg = "{\"id\":\"sub-1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { orderCreated { id } }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        // No next messages should be sent
        assertThat(sender.sentMessages.size(), is(0));

        // Error should be sent via ctx.writeAndFlush
        TextWebSocketFrame errorFrame = channel.readOutbound();
        assertNotNull("Expected error message", errorFrame);
        JsonNode error = OBJECT_MAPPER.readTree(errorFrame.text());
        assertThat(error.get("id").asText(), is("sub-1"));
        assertThat(error.get("type").asText(), is("error"));
        assertTrue(error.get("payload").isArray());
        assertThat(error.get("payload").get(0).get("message").asText(), containsString("No matching"));
        errorFrame.release();

        channel.finishAndReleaseAll();
    }

    // --- client complete stops the stream ---

    @Test
    public void shouldStopStreamOnClientComplete() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        // Use a filter that will match, but test that client complete is handled
        List<WebSocketMessage> payloads = List.of(
            webSocketMessage("{\"value\":1}"),
            webSocketMessage("{\"value\":2}")
        );
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { counter { value } }", payloads, sender
        );
        EmbeddedChannel channel = createChannel(handler);

        // Connection init
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        TextWebSocketFrame ack = channel.readOutbound();
        assertNotNull(ack);
        ack.release();

        // Client complete for a non-existent subscription -- should be ignored
        channel.writeInbound(new TextWebSocketFrame("{\"id\":\"sub-999\",\"type\":\"complete\"}"));

        // No errors, no crashes
        assertNull("No outbound messages expected for non-existent complete", channel.readOutbound());

        channel.finishAndReleaseAll();
    }

    // --- AST matching uses the existing GraphQLAstMatcher ---

    @Test
    public void shouldMatchSubscriptionQueryViaAstMatcher() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        List<WebSocketMessage> payloads = List.of(webSocketMessage("{\"status\":\"updated\"}"));

        // Filter with AST_SUBSET: expects subscription with userUpdated field
        GraphQLBody filter = GraphQLBody.graphQL("subscription { userUpdated { id } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("userUpdated");

        GraphQLSubscriptionHandler handler = createHandler(filter, payloads, sender);
        EmbeddedChannel channel = createChannel(handler);

        // Init
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        channel.readOutbound(); // ack

        // Subscribe with a query that has additional fields -- AST_SUBSET should match
        String subscribeMsg = "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { userUpdated { id name email } }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        // Should have received next messages
        assertThat(sender.sentMessages.size(), is(1));
        JsonNode next = OBJECT_MAPPER.readTree(sender.sentMessages.get(0));
        assertThat(next.get("type").asText(), is("next"));
        assertThat(next.get("payload").get("data").get("status").asText(), is("updated"));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldNotMatchDifferentOperationType() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        List<WebSocketMessage> payloads = List.of(webSocketMessage("{\"data\":true}"));

        GraphQLBody filter = GraphQLBody.graphQL("subscription { userUpdated { id } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);

        GraphQLSubscriptionHandler handler = createHandler(filter, payloads, sender);
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        channel.readOutbound(); // ack

        // Try to subscribe with a query (not subscription) -- should not match
        String subscribeMsg = "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"query { userUpdated { id } }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        // No next messages
        assertThat(sender.sentMessages.size(), is(0));

        // Error should be sent
        TextWebSocketFrame errorFrame = channel.readOutbound();
        assertNotNull(errorFrame);
        JsonNode error = OBJECT_MAPPER.readTree(errorFrame.text());
        assertThat(error.get("type").asText(), is("error"));
        errorFrame.release();

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldMatchWithExactMatchType() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        List<WebSocketMessage> payloads = List.of(webSocketMessage("{\"ok\":true}"));

        GraphQLBody filter = GraphQLBody.graphQL("subscription OnUpdate { userUpdated }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);

        GraphQLSubscriptionHandler handler = createHandler(filter, payloads, sender);
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        channel.readOutbound(); // ack

        // Exact match -- same operation name and fields
        String subscribeMsg = "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription OnUpdate { userUpdated { id name } }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        assertThat(sender.sentMessages.size(), is(1));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldRejectExactMatchWithExtraFields() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        List<WebSocketMessage> payloads = List.of(webSocketMessage("{\"ok\":true}"));

        GraphQLBody filter = GraphQLBody.graphQL("subscription OnUpdate { userUpdated }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);

        GraphQLSubscriptionHandler handler = createHandler(filter, payloads, sender);
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        channel.readOutbound(); // ack

        // Extra field -- should not match with AST_EXACT
        String subscribeMsg = "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription OnUpdate { userUpdated extraField }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        assertThat(sender.sentMessages.size(), is(0));

        TextWebSocketFrame errorFrame = channel.readOutbound();
        assertNotNull(errorFrame);
        errorFrame.release();

        channel.finishAndReleaseAll();
    }

    // --- buildNextMessage tests ---

    @Test
    public void shouldBuildNextMessageWithJsonPayload() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { x { id } }", List.of(), sender
        );

        String result = handler.buildNextMessage("sub-1", webSocketMessage("{\"name\":\"Alice\",\"age\":30}"));
        assertNotNull(result);

        JsonNode node = OBJECT_MAPPER.readTree(result);
        assertThat(node.get("id").asText(), is("sub-1"));
        assertThat(node.get("type").asText(), is("next"));
        assertThat(node.get("payload").get("data").get("name").asText(), is("Alice"));
        assertThat(node.get("payload").get("data").get("age").asInt(), is(30));
    }

    @Test
    public void shouldBuildNextMessageWithStringPayload() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { x { id } }", List.of(), sender
        );

        String result = handler.buildNextMessage("sub-1", webSocketMessage("plain text data"));
        assertNotNull(result);

        JsonNode node = OBJECT_MAPPER.readTree(result);
        assertThat(node.get("id").asText(), is("sub-1"));
        assertThat(node.get("type").asText(), is("next"));
        assertThat(node.get("payload").get("data").asText(), is("plain text data"));
    }

    @Test
    public void shouldReturnNullForNullPayloadText() {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { x { id } }", List.of(), sender
        );

        String result = handler.buildNextMessage("sub-1", webSocketMessage());
        assertNull(result);
    }

    // --- message delays are passed through ---

    @Test
    public void shouldPassDelaysToFrameSender() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        Delay delay = new Delay(TimeUnit.MILLISECONDS, 500);
        List<WebSocketMessage> payloads = List.of(
            webSocketMessage("{\"v\":1}"),
            webSocketMessage("{\"v\":2}").withDelay(delay)
        );
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { counter { v } }", payloads, sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        channel.readOutbound(); // ack

        String subscribeMsg = "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { counter { v } }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        // The first (undelayed) message is written straight away; the second is still pending
        // on its delay, so the sequence has not yet advanced past it.
        assertThat(sender.sentMessages.size(), is(1));
        assertTrue("second message should still be waiting on its delay", sender.hasDeferred());

        sender.fireDelayed();

        assertThat(sender.sentMessages.size(), is(2));
        // First message has no delay
        assertNull(sender.sentDelays.get(0));
        // Second message has the configured delay
        assertNotNull(sender.sentDelays.get(1));
        assertThat(sender.sentDelays.get(1).getValue(), is(500L));

        channel.finishAndReleaseAll();
    }

    // --- wire ordering: complete must be the last frame, even with delays ---

    /**
     * The regression this suite previously could not see. With any delay configured, the handler
     * used to recurse through the whole payload list without waiting and then write {@code
     * complete} immediately, so {@code complete} reached the wire before a single {@code next}.
     * Both Apollo and graphql-ws discard messages received after {@code complete}, so the
     * subscription delivered nothing at all.
     */
    @Test
    public void shouldNotWriteCompleteBeforeDelayedNextFramesReachTheWire() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        Delay delay = new Delay(TimeUnit.MILLISECONDS, 50);
        List<WebSocketMessage> payloads = List.of(
            webSocketMessage("{\"v\":1}").withDelay(delay),
            webSocketMessage("{\"v\":2}").withDelay(delay)
        );
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { counter { v } }", payloads, sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        assertThat(wireTypes(channel), is(List.of("connection_ack")));

        channel.writeInbound(new TextWebSocketFrame(
            "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { counter { v } }\"}}"));

        // Nothing may reach the wire until the first delay elapses -- in particular the terminal
        // `complete` must not have been written. This is the assertion that goes red on the bug.
        assertThat("no frame may precede the delayed next frames", wireTypes(channel), is(empty()));

        sender.fireDelayed();

        assertThat(wireTypes(channel), is(List.of("next", "next", "complete")));

        channel.finishAndReleaseAll();
    }

    /**
     * Guards the ordering contract for the mixed case, where only some payloads carry a delay.
     */
    @Test
    public void shouldKeepWireOrderWhenOnlySomePayloadsAreDelayed() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        List<WebSocketMessage> payloads = List.of(
            webSocketMessage("{\"v\":1}"),
            webSocketMessage("{\"v\":2}").withDelay(new Delay(TimeUnit.MILLISECONDS, 50)),
            webSocketMessage("{\"v\":3}")
        );
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { counter { v } }", payloads, sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        assertThat(wireTypes(channel), is(List.of("connection_ack")));

        channel.writeInbound(new TextWebSocketFrame(
            "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { counter { v } }\"}}"));

        // Only the first, undelayed, payload has been written so far
        assertThat(wireTypes(channel), is(List.of("next")));

        sender.fireDelayed();

        // The remaining two next frames, then complete -- never complete in the middle
        assertThat(wireTypes(channel), is(List.of("next", "next", "complete")));

        channel.finishAndReleaseAll();
    }

    /**
     * The payloads must arrive in the order they were configured, not merely all before
     * {@code complete}.
     */
    @Test
    public void shouldDeliverDelayedPayloadsInConfiguredOrder() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        Delay delay = new Delay(TimeUnit.MILLISECONDS, 10);
        List<WebSocketMessage> payloads = List.of(
            webSocketMessage("{\"v\":1}").withDelay(delay),
            webSocketMessage("{\"v\":2}").withDelay(delay),
            webSocketMessage("{\"v\":3}").withDelay(delay)
        );
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { counter { v } }", payloads, sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        drainWire(channel); // ack
        channel.writeInbound(new TextWebSocketFrame(
            "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { counter { v } }\"}}"));
        sender.fireDelayed();

        List<String> wire = drainWire(channel);
        assertThat(wire.size(), is(4));
        assertThat(OBJECT_MAPPER.readTree(wire.get(0)).get("payload").get("data").get("v").asInt(), is(1));
        assertThat(OBJECT_MAPPER.readTree(wire.get(1)).get("payload").get("data").get("v").asInt(), is(2));
        assertThat(OBJECT_MAPPER.readTree(wire.get(2)).get("payload").get("data").get("v").asInt(), is(3));
        assertThat(OBJECT_MAPPER.readTree(wire.get(3)).get("type").asText(), is("complete"));

        channel.finishAndReleaseAll();
    }

    // --- legacy graphql-ws (subscriptions-transport-ws) subprotocol ---

    private GraphQLSubscriptionHandler createLegacyHandler(
        String subscriptionQuery,
        List<WebSocketMessage> payloads,
        RecordingFrameSender frameSender
    ) {
        GraphQLBody filter = GraphQLBody.graphQL(subscriptionQuery)
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);
        return new GraphQLSubscriptionHandler(filter, payloads, frameSender, null,
            null, null, null, null, null, "graphql-ws");
    }

    /**
     * The legacy subprotocol was advertised and accepted but its vocabulary was never
     * implemented, so an Apollo client sent {@code start} and hung forever after a
     * healthy-looking {@code connection_ack}.
     */
    @Test
    public void shouldDriveLegacySubscriptionWithStartAndDataMessages() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createLegacyHandler(
            "subscription { counter { v } }",
            List.of(webSocketMessage("{\"v\":1}")),
            sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        assertThat(wireTypes(channel), is(List.of("connection_ack")));

        // legacy clients subscribe with `start`, not `subscribe`
        channel.writeInbound(new TextWebSocketFrame(
            "{\"id\":\"s1\",\"type\":\"start\",\"payload\":{\"query\":\"subscription { counter { v } }\"}}"));

        // and expect `data`, not `next`
        assertThat(wireTypes(channel), is(List.of("data", "complete")));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldCancelLegacySubscriptionOnStop() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        Delay delay = new Delay(TimeUnit.MILLISECONDS, 50);
        GraphQLSubscriptionHandler handler = createLegacyHandler(
            "subscription { counter { v } }",
            List.of(
                webSocketMessage("{\"v\":1}").withDelay(delay),
                webSocketMessage("{\"v\":2}").withDelay(delay)
            ),
            sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        drainWire(channel); // ack
        channel.writeInbound(new TextWebSocketFrame(
            "{\"id\":\"s1\",\"type\":\"start\",\"payload\":{\"query\":\"subscription { counter { v } }\"}}"));

        // legacy clients cancel with `stop`, not `complete`
        channel.writeInbound(new TextWebSocketFrame("{\"id\":\"s1\",\"type\":\"stop\"}"));
        sender.fireDelayed();

        // The first payload was already handed to the scheduler before the cancel arrived, so it
        // still lands; what `stop` must do is stop the sequence advancing -- no second payload,
        // and no `complete`, because a cancelled subscription never completes normally.
        assertThat(wireTypes(channel), is(List.of("data")));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldSendLegacyErrorPayloadAsObjectNotArray() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createLegacyHandler(
            "subscription { counter { v } }", List.of(), sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        drainWire(channel); // ack
        channel.writeInbound(new TextWebSocketFrame(
            "{\"id\":\"s1\",\"type\":\"start\",\"payload\":{\"query\":\"subscription { somethingElse { v } }\"}}"));

        List<String> wire = drainWire(channel);
        assertThat(wire.size(), is(1));
        JsonNode error = OBJECT_MAPPER.readTree(wire.get(0));
        assertThat(error.get("type").asText(), is("error"));
        assertTrue("legacy error payload is a single object", error.get("payload").isObject());
        assertThat(error.get("payload").get("message").asText(), containsString("No matching subscription"));

        channel.finishAndReleaseAll();
    }

    /**
     * The modern protocol must be unaffected by the legacy support: it still emits {@code next}
     * and an array-shaped error payload.
     */
    @Test
    public void shouldKeepModernVocabularyWhenSubprotocolIsGraphqlTransportWs() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = new GraphQLSubscriptionHandler(
            GraphQLBody.graphQL("subscription { counter { v } }")
                .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET),
            List.of(webSocketMessage("{\"v\":1}")),
            sender, null, null, null, null, null, null, "graphql-transport-ws");
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        drainWire(channel); // ack
        channel.writeInbound(new TextWebSocketFrame(
            "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { counter { v } }\"}}"));

        assertThat(wireTypes(channel), is(List.of("next", "complete")));

        channel.finishAndReleaseAll();
    }

    // --- subscribe with no query ---

    @Test
    public void shouldSendErrorWhenSubscribeHasNoQuery() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { x { id } }", List.of(), sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        channel.readOutbound(); // ack

        // Subscribe with no query field
        String subscribeMsg = "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        TextWebSocketFrame errorFrame = channel.readOutbound();
        assertNotNull(errorFrame);
        JsonNode error = OBJECT_MAPPER.readTree(errorFrame.text());
        assertThat(error.get("type").asText(), is("error"));
        assertThat(error.get("id").asText(), is("s1"));
        errorFrame.release();

        channel.finishAndReleaseAll();
    }

    // --- unknown message types are ignored ---

    @Test
    public void shouldIgnoreUnknownMessageTypes() {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { x { id } }", List.of(), sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"unknown_type\"}"));

        // No response expected
        assertNull(channel.readOutbound());
        assertThat(sender.sentMessages.size(), is(0));

        channel.finishAndReleaseAll();
    }

    // --- isGraphQLWebSocketProtocol ---

    @Test
    public void shouldIdentifyGraphqlTransportWsProtocol() {
        assertTrue(GraphQLSubscriptionHandler.isGraphQLWebSocketProtocol("graphql-transport-ws"));
    }

    @Test
    public void shouldIdentifyLegacyGraphqlWsProtocol() {
        assertTrue(GraphQLSubscriptionHandler.isGraphQLWebSocketProtocol("graphql-ws"));
    }

    @Test
    public void shouldNotIdentifyOtherProtocols() {
        assertFalse(GraphQLSubscriptionHandler.isGraphQLWebSocketProtocol("mqtt"));
        assertFalse(GraphQLSubscriptionHandler.isGraphQLWebSocketProtocol(null));
        assertFalse(GraphQLSubscriptionHandler.isGraphQLWebSocketProtocol(""));
    }

    // --- empty payloads produce only complete ---

    @Test
    public void shouldSendCompleteWithNoPayloads() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { events { id } }", List.of(), sender
        );
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        channel.readOutbound(); // ack

        String subscribeMsg = "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { events { id } }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        // No next messages
        assertThat(sender.sentMessages.size(), is(0));

        // Complete should be sent
        TextWebSocketFrame completeFrame = channel.readOutbound();
        assertNotNull(completeFrame);
        JsonNode complete = OBJECT_MAPPER.readTree(completeFrame.text());
        assertThat(complete.get("type").asText(), is("complete"));
        assertThat(complete.get("id").asText(), is("s1"));
        completeFrame.release();

        channel.finishAndReleaseAll();
    }

    // --- default match type when null ---

    @Test
    public void shouldDefaultToSubsetMatchWhenNoMatchTypeSet() throws Exception {
        RecordingFrameSender sender = new RecordingFrameSender();
        List<WebSocketMessage> payloads = List.of(webSocketMessage("{\"ok\":true}"));

        // No selectionSetMatchType set -- should default to AST_SUBSET
        GraphQLBody filter = GraphQLBody.graphQL("subscription { userUpdated { id } }");

        GraphQLSubscriptionHandler handler = createHandler(filter, payloads, sender);
        EmbeddedChannel channel = createChannel(handler);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"connection_init\"}"));
        channel.readOutbound(); // ack

        // Subscribe with additional fields -- should match via SUBSET default
        String subscribeMsg = "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { userUpdated { id name email } }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        assertThat(sender.sentMessages.size(), is(1));

        channel.finishAndReleaseAll();
    }

    // --- regression: explicit fields preserved when selectionSetMatchType is null ---

    @Test
    public void shouldPreserveExplicitFieldsWhenSelectionSetMatchTypeNull() {
        GraphQLBody body = GraphQLBody.graphQL("subscription { userUpdated { id } }")
            .withFields(List.of("userUpdated"));
        // precondition: no explicit match type
        assertNull(body.getSelectionSetMatchType());

        GraphQLBody normalised = GraphQLSubscriptionHandler.normaliseSubscriptionBody(body);

        // defaults to AST_SUBSET but MUST keep the explicitly-configured fields
        assertThat(normalised.getSelectionSetMatchType(), is(SelectionSetMatchType.AST_SUBSET));
        assertThat(normalised.getFields(), is(List.of("userUpdated")));
    }

    // --- regression: subscribe before connection_init is ignored ---

    @Test
    public void shouldNotProcessSubscribeBeforeConnectionInit() {
        RecordingFrameSender sender = new RecordingFrameSender();
        GraphQLSubscriptionHandler handler = createHandler(
            "subscription { userUpdated { id } }",
            List.of(webSocketMessage("{\"v\":1}")),
            sender
        );
        EmbeddedChannel channel = createChannel(handler);

        // subscribe WITHOUT a prior connection_init handshake
        String subscribeMsg = "{\"id\":\"s1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { userUpdated { id } }\"}}";
        channel.writeInbound(new TextWebSocketFrame(subscribeMsg));

        // the guard short-circuits: no next/error frames are emitted
        assertThat(sender.sentMessages, is(empty()));

        channel.finishAndReleaseAll();
    }
}
