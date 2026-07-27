package org.mockserver.netty.responsewriter;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.breakpoint.BreakpointCallbackDispatcher;
import org.mockserver.mock.breakpoint.BreakpointMatcherRegistry;
import org.mockserver.mock.breakpoint.BreakpointPhase;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher;
import org.mockserver.model.StreamingBody;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.WebSocketMessageSerializer;
import org.mockserver.serialization.model.PausedStreamFrameDTO;
import org.mockserver.serialization.model.StreamFrameDecisionDTO;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration test that drives a mock-generated SSE/chunked stream through the real Netty
 * write path ({@link NettyResponseWriter#writeStreamingResponse}) over an {@link EmbeddedChannel}
 * while a RESPONSE_STREAM breakpoint matcher is registered, proving the previously-unproven
 * write-path integration (audit gap #61).
 *
 * <p>The registry-level tests ({@code MockStreamBreakpointTest},
 * {@code StreamFrameBreakpointRegistryTest} in mockserver-core) exercise
 * {@link StreamFrameBreakpointRegistry#pauseFrame} with NO channel and so cannot show that a
 * frame is actually withheld from — or written to — the wire. This test closes that hole by
 * asserting, against real channel outbound:
 * <ul>
 *   <li>each frame is PARKED (not flushed) until a decision arrives over the callback WS;</li>
 *   <li>a CONTINUE decision flushes the original frame bytes;</li>
 *   <li>a MODIFY decision flushes the replacement bytes;</li>
 *   <li>a DROP decision suppresses the frame (nothing flushed).</li>
 * </ul>
 *
 * <p>The breakpoint decision is delivered exactly as in production: the server serialises a
 * {@link PausedStreamFrameDTO} to the callback-client channel, and the test replies with a
 * {@link StreamFrameDecisionDTO} fed back through
 * {@link WebSocketClientRegistry#receivedTextWebSocketFrame}.
 */
public class MockStreamBreakpointWritePathTest {

    private static final String CLIENT_ID = "stream-frame-client";

    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private final WebSocketMessageSerializer serializer = new WebSocketMessageSerializer(mockServerLogger);
    private final Scheduler scheduler = mock(Scheduler.class);

    @Before
    public void resetBreakpointSingletons() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

    @After
    public void cleanupBreakpointSingletons() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

    @Test
    public void shouldParkMockStreamFrameUntilContinueThenFlushOriginal() throws Exception {
        withStreamBreakpointFixture((data, ws, streamingBody) -> {
            // when — a single mock frame is produced
            feedFrame(data, streamingBody, "sse-frame-1");

            // then — the frame is PARKED: nothing beyond the head has been flushed to the wire
            assertThat("frame must be parked, not flushed, before a decision arrives",
                data.readOutbound(), is(nullValue()));

            // and — the paused frame was dispatched over the callback WebSocket
            PausedStreamFrameDTO paused = readPausedFrame(ws);
            assertThat(paused.getStreamId(), endsWith("-stream"));
            assertThat(paused.getDirection(), is(PausedStreamFrame_directionOutbound()));
            assertThat(paused.getPhase(), is(BreakpointPhase.RESPONSE_STREAM.name()));
            assertThat(new String(Base64.getDecoder().decode(paused.getBody()), StandardCharsets.UTF_8),
                is("sse-frame-1"));

            // when — the client replies CONTINUE
            deliverDecision(data, decision(paused, "CONTINUE", null));

            // then — the ORIGINAL frame bytes are flushed
            assertThat(readFrameBody(data), is("sse-frame-1"));
        });
    }

    @Test
    public void shouldFlushModifiedBytesOnModifyDecision() throws Exception {
        withStreamBreakpointFixture((data, ws, streamingBody) -> {
            feedFrame(data, streamingBody, "original-body");
            assertThat("frame must be parked before decision", data.readOutbound(), is(nullValue()));

            PausedStreamFrameDTO paused = readPausedFrame(ws);
            byte[] replacement = "replacement-body".getBytes(StandardCharsets.UTF_8);

            // when — the client replies MODIFY with replacement bytes
            deliverDecision(data, decision(paused, "MODIFY", replacement));

            // then — the REPLACEMENT bytes are flushed, not the original
            assertThat(readFrameBody(data), is("replacement-body"));
        });
    }

    @Test
    public void shouldSuppressFrameOnDropDecision() throws Exception {
        withStreamBreakpointFixture((data, ws, streamingBody) -> {
            feedFrame(data, streamingBody, "doomed-frame");
            assertThat("frame must be parked before decision", data.readOutbound(), is(nullValue()));

            PausedStreamFrameDTO paused = readPausedFrame(ws);

            // when — the client replies DROP
            deliverDecision(data, decision(paused, "DROP", null));

            // then — NOTHING is flushed for this frame (it is suppressed)
            assertThat("dropped frame must never reach the wire", data.readOutbound(), is(nullValue()));
        });
    }

    // --- fixture wiring -----------------------------------------------------

    @FunctionalInterface
    private interface StreamBreakpointScenario {
        void run(EmbeddedChannel dataChannel, EmbeddedChannel wsChannel, StreamingBody streamingBody) throws Exception;
    }

    /**
     * Wires up a data channel driven by {@link NettyResponseWriter}, a separate callback-WS
     * client channel registered in a {@link WebSocketClientRegistry} (exposed to the writer via
     * the channel's {@code WS_REGISTRY_KEY} attribute), and a RESPONSE_STREAM breakpoint matcher
     * that matches the request. The response head is written and drained before the scenario runs.
     */
    private void withStreamBreakpointFixture(StreamBreakpointScenario scenario) throws Exception {
        Configuration configuration = configuration()
            .breakpointTimeoutMillis(30_000L)
            .breakpointMaxHeld(50);

        EmbeddedChannel dataChannel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        EmbeddedChannel wsChannel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        try {
            // callback-WS client registry + registered client whose channel captures dispatched frames
            WebSocketClientRegistry registry = new WebSocketClientRegistry(configuration, mockServerLogger);
            registry.registerClient(CLIENT_ID, wsChannel.pipeline().firstContext());
            // drain the WebSocketClientIdDTO frame the registry sends on registration
            drainOutbound(wsChannel);
            // expose the registry to the writer exactly as the real pipeline does
            dataChannel.attr(WebSocketClientRegistry.WS_REGISTRY_KEY).set(registry);

            // register a RESPONSE_STREAM breakpoint that matches the request
            BreakpointMatcherRegistry.getInstance().register(
                request().withPath("/stream"),
                EnumSet.of(BreakpointPhase.RESPONSE_STREAM),
                CLIENT_ID,
                configuration,
                mockServerLogger
            );

            StreamingBody streamingBody = new StreamingBody(1024);
            streamingBody.setEventLoop(dataChannel.eventLoop());

            org.mockserver.model.HttpResponse response = response()
                .withStatusCode(200)
                .withStreamingBody(streamingBody);

            dataChannel.eventLoop().execute(() ->
                new NettyResponseWriter(configuration, mockServerLogger, dataChannel.pipeline().firstContext(), scheduler)
                    .sendResponse(request("/stream"), response)
            );
            dataChannel.runPendingTasks();

            // the streaming head is written first — drain it so outbound holds only frames afterwards
            HttpResponse head = dataChannel.readOutbound();
            assertThat("a streaming response head must be written", head, is(notNullValue()));
            assertThat(head.headers().get(HttpHeaderNames.TRANSFER_ENCODING), is("chunked"));
            ReferenceCountUtil.release(head);

            scenario.run(dataChannel, wsChannel, streamingBody);

            // complete the stream cleanly so held-frame eviction runs
            dataChannel.eventLoop().execute(streamingBody::complete);
            dataChannel.runPendingTasks();
        } finally {
            dataChannel.finishAndReleaseAll();
            wsChannel.finishAndReleaseAll();
        }
    }

    private void feedFrame(EmbeddedChannel dataChannel, StreamingBody streamingBody, String body) {
        dataChannel.eventLoop().execute(() ->
            streamingBody.addChunk(Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)));
        dataChannel.runPendingTasks();
    }

    private PausedStreamFrameDTO readPausedFrame(EmbeddedChannel wsChannel) throws Exception {
        TextWebSocketFrame frame = wsChannel.readOutbound();
        assertThat("a paused-frame message must be dispatched over the callback WS", frame, is(notNullValue()));
        Object message = serializer.deserialize(frame.text());
        frame.release();
        assertThat(message, is(instanceOf(PausedStreamFrameDTO.class)));
        return (PausedStreamFrameDTO) message;
    }

    private StreamFrameDecisionDTO decision(PausedStreamFrameDTO paused, String action, byte[] body) {
        StreamFrameDecisionDTO decision = new StreamFrameDecisionDTO()
            .setCorrelationId(paused.getCorrelationId())
            .setAction(action);
        if (body != null) {
            decision.setBody(Base64.getEncoder().encodeToString(body));
        }
        return decision;
    }

    private void deliverDecision(EmbeddedChannel dataChannel, StreamFrameDecisionDTO decision) throws Exception {
        WebSocketClientRegistry registry = dataChannel.attr(WebSocketClientRegistry.WS_REGISTRY_KEY).get();
        registry.receivedTextWebSocketFrame(new TextWebSocketFrame(serializer.serialize(decision)));
        // the decision handler marshals the write onto the data channel's event loop
        dataChannel.runPendingTasks();
    }

    private String readFrameBody(EmbeddedChannel dataChannel) {
        Object outbound = dataChannel.readOutbound();
        assertThat("a frame must be flushed after the decision", outbound, is(notNullValue()));
        assertThat(outbound, is(instanceOf(DefaultHttpContent.class)));
        DefaultHttpContent content = (DefaultHttpContent) outbound;
        String body = content.content().toString(StandardCharsets.UTF_8);
        content.release();
        return body;
    }

    private void drainOutbound(EmbeddedChannel channel) {
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(outbound);
        }
    }

    private static String PausedStreamFrame_directionOutbound() {
        return org.mockserver.mock.breakpoint.PausedStreamFrame.Direction.OUTBOUND.name();
    }
}
