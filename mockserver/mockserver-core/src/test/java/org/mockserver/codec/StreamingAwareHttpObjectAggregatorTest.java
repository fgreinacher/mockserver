package org.mockserver.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AttributeKey;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.StreamingResponseRelayHandler;
import org.mockserver.model.Message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;

public class StreamingAwareHttpObjectAggregatorTest {

    @Test
    public void shouldDetectSseResponse() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        HttpUtil.setTransferEncodingChunked(response, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(true));
    }

    @Test
    public void shouldDetectSseResponseWithCharset() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        HttpUtil.setTransferEncodingChunked(response, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(true));
    }

    @Test
    public void shouldNotDetectChunkedResponseWithoutContentLengthWhenNotSse() {
        // Regression guard for WAR deployment: Tomcat uses chunked encoding without
        // Content-Length for all servlet responses. These must NOT be detected as streaming.
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setTransferEncodingChunked(response, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldNotDetectChunkedResponseWithContentLength() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setTransferEncodingChunked(response, true);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 100);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldDetectSseResponseWithoutChunkedEncoding() {
        // SSE responses should be detected even without explicit chunked transfer-encoding
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(true));
    }

    @Test
    public void shouldNotDetectOctetStreamAsStreaming() {
        // application/octet-stream with chunked encoding should NOT be detected as streaming
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
        HttpUtil.setTransferEncodingChunked(response, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldNotDetectNormalResponseWithContentLength() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 42);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldNotDetectResponseWithNoHeaders() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldFullyAggregateWhenDisableResponseStreamingAttributeIsSet() {
        // When DISABLE_RESPONSE_STREAMING is set on the channel (e.g. FORWARD_REPLACE),
        // the aggregator should NOT switch to streaming mode even for a streaming response.
        Configuration configuration = new Configuration();
        configuration.streamingResponsesEnabled(true);

        StreamingAwareHttpObjectAggregator aggregator =
            new StreamingAwareHttpObjectAggregator(Integer.MAX_VALUE, configuration, null);

        EmbeddedChannel channel = new EmbeddedChannel(aggregator);

        // Set the disable attribute
        AttributeKey<Boolean> disableKey = AttributeKey.valueOf("DISABLE_RESPONSE_STREAMING");
        channel.attr(disableKey).set(true);

        // Send a streaming response head
        DefaultHttpResponse streamingHead = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        streamingHead.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        HttpUtil.setTransferEncodingChunked(streamingHead, true);

        // Write the head - should be aggregated (not switched to streaming)
        channel.writeInbound(streamingHead);

        // Write a last content to complete aggregation
        channel.writeInbound(new DefaultLastHttpContent());

        // Should produce a FullHttpResponse (aggregated), not a bare HttpResponse
        Object outbound = channel.readInbound();
        assertThat("should produce FullHttpResponse when streaming is disabled on channel",
            outbound, instanceOf(FullHttpResponse.class));

        // Clean up
        if (outbound instanceof FullHttpResponse) {
            ((FullHttpResponse) outbound).release();
        }
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldStreamWhenDisableResponseStreamingAttributeIsNotSet() {
        // When DISABLE_RESPONSE_STREAMING is NOT set, streaming responses should be detected
        Configuration configuration = new Configuration();
        configuration.streamingResponsesEnabled(true);

        // Just test the static detection method (full pipeline test is in integration tests)
        DefaultHttpResponse streamingHead = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        streamingHead.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        HttpUtil.setTransferEncodingChunked(streamingHead, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(streamingHead), is(true));
    }

    @Test
    public void shouldStreamWhenRequestExpectedStreamingEvenWithoutSseContentType() {
        // Resilience for backends that stream SSE with NO Content-Type (e.g. the OpenAI Codex
        // backend used by opencode): when the client asked for a stream (EXPECT_STREAMING_RESPONSE),
        // the head must be relayed immediately even though the response is not text/event-stream.
        Configuration configuration = new Configuration();
        configuration.streamingResponsesEnabled(true);

        StreamingAwareHttpObjectAggregator aggregator =
            new StreamingAwareHttpObjectAggregator(Integer.MAX_VALUE, configuration, null);
        EmbeddedChannel channel = new EmbeddedChannel(aggregator);

        channel.attr(AttributeKey.<Boolean>valueOf("EXPECT_STREAMING_RESPONSE")).set(true);
        CompletableFuture<Message> responseFuture = new CompletableFuture<>();
        channel.attr(AttributeKey.<CompletableFuture<Message>>valueOf("RESPONSE_FUTURE")).set(responseFuture);

        // Response head WITHOUT text/event-stream — no content type at all, like the Codex backend
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpUtil.setTransferEncodingChunked(head, true);
        channel.writeInbound(head);

        // The head must be relayed immediately (future completed) before any body/last-content
        assertThat("head relayed immediately when client expected a stream", responseFuture.isDone(), is(true));
        Object relayed = responseFuture.getNow(null);
        assertThat(relayed, instanceOf(org.mockserver.model.HttpResponse.class));
        assertThat("relayed as a streaming body (head-only response with a StreamingBody attached)",
            ((org.mockserver.model.HttpResponse) relayed).getStreamingBody(), notNullValue());
        // No aggregated FullHttpResponse should be produced
        assertThat(channel.readInbound(), is(nullValue()));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldAggregateWhenNoRequestIntentAndNoSseContentType() {
        // Guard against over-triggering: a plain non-SSE response with no streaming request intent
        // must still be aggregated normally (so ordinary forward traffic is unaffected).
        Configuration configuration = new Configuration();
        configuration.streamingResponsesEnabled(true);

        StreamingAwareHttpObjectAggregator aggregator =
            new StreamingAwareHttpObjectAggregator(Integer.MAX_VALUE, configuration, null);
        EmbeddedChannel channel = new EmbeddedChannel(aggregator);

        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        head.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setTransferEncodingChunked(head, true);
        channel.writeInbound(head);
        channel.writeInbound(new DefaultLastHttpContent());

        Object outbound = channel.readInbound();
        assertThat("non-streaming response is aggregated when the client did not request a stream",
            outbound, instanceOf(FullHttpResponse.class));
        if (outbound instanceof FullHttpResponse) {
            ((FullHttpResponse) outbound).release();
        }
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldNotStreamWhenDisableSetEvenIfRequestExpectedStreaming() {
        // DISABLE_RESPONSE_STREAMING (e.g. FORWARD_REPLACE response override) must win over both the
        // request intent and an SSE content type, so the response can be fully aggregated and overridden.
        Configuration configuration = new Configuration();
        configuration.streamingResponsesEnabled(true);

        StreamingAwareHttpObjectAggregator aggregator =
            new StreamingAwareHttpObjectAggregator(Integer.MAX_VALUE, configuration, null);
        EmbeddedChannel channel = new EmbeddedChannel(aggregator);

        channel.attr(AttributeKey.<Boolean>valueOf("EXPECT_STREAMING_RESPONSE")).set(true);
        channel.attr(AttributeKey.<Boolean>valueOf("DISABLE_RESPONSE_STREAMING")).set(true);

        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        HttpUtil.setTransferEncodingChunked(head, true);
        channel.writeInbound(head);
        channel.writeInbound(new DefaultLastHttpContent());

        Object outbound = channel.readInbound();
        assertThat("disable overrides request intent and SSE content type",
            outbound, instanceOf(FullHttpResponse.class));
        if (outbound instanceof FullHttpResponse) {
            ((FullHttpResponse) outbound).release();
        }
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldRemoveReadTimeoutHandlerWhenSwitchingToStreaming() {
        // The per-request socket read timeout (maxSocketTimeout, ~20s) would kill a streaming
        // response that pauses longer than that between chunks (LLM reasoning). On switching to
        // streaming it must be removed so the stream idle bound (streamIdleTimeoutSeconds) governs.
        Configuration configuration = new Configuration();
        configuration.streamingResponsesEnabled(true);
        configuration.streamIdleTimeoutSeconds(60);

        StreamingAwareHttpObjectAggregator aggregator =
            new StreamingAwareHttpObjectAggregator(Integer.MAX_VALUE, configuration, null);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("readTimeout", new ReadTimeoutHandler(20, TimeUnit.SECONDS));
        channel.pipeline().addLast(aggregator);

        channel.attr(AttributeKey.<Boolean>valueOf("EXPECT_STREAMING_RESPONSE")).set(true);
        CompletableFuture<Message> responseFuture = new CompletableFuture<>();
        channel.attr(AttributeKey.<CompletableFuture<Message>>valueOf("RESPONSE_FUTURE")).set(responseFuture);

        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpUtil.setTransferEncodingChunked(head, true);
        channel.writeInbound(head);

        assertThat("read timeout removed so a long inter-chunk pause is governed by the stream idle bound",
            channel.pipeline().get(ReadTimeoutHandler.class), is(nullValue()));
        assertThat("stream idle state handler installed to bound the stream when streamIdleTimeoutSeconds>0",
            channel.pipeline().get("streamIdleStateHandler"), notNullValue());
        assertThat("streaming relay handler installed",
            channel.pipeline().get(StreamingResponseRelayHandler.class), notNullValue());

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldRemoveReadTimeoutHandlerWhenStreamIdleTimeoutDisabled() {
        // Regression guard for the streamIdleTimeoutSeconds=0 footgun: 0 means "no idle bound"
        // (unbounded stream). The socket read timeout (maxSocketTimeout, ~20s) must STILL be removed
        // on switching to streaming — otherwise a disabled idle bound paradoxically left the 20s
        // socket timeout armed and truncated long-paused streams. With 0, no stream idle handler is
        // installed (the stream runs unbounded) but the socket timeout is gone.
        Configuration configuration = new Configuration();
        configuration.streamingResponsesEnabled(true);
        configuration.streamIdleTimeoutSeconds(0);

        StreamingAwareHttpObjectAggregator aggregator =
            new StreamingAwareHttpObjectAggregator(Integer.MAX_VALUE, configuration, null);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("readTimeout", new ReadTimeoutHandler(20, TimeUnit.SECONDS));
        channel.pipeline().addLast(aggregator);

        channel.attr(AttributeKey.<Boolean>valueOf("EXPECT_STREAMING_RESPONSE")).set(true);
        CompletableFuture<Message> responseFuture = new CompletableFuture<>();
        channel.attr(AttributeKey.<CompletableFuture<Message>>valueOf("RESPONSE_FUTURE")).set(responseFuture);

        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpUtil.setTransferEncodingChunked(head, true);
        channel.writeInbound(head);

        assertThat("socket read timeout removed even when the stream idle bound is disabled (0)",
            channel.pipeline().get(ReadTimeoutHandler.class), is(nullValue()));
        assertThat("no stream idle state handler installed when streamIdleTimeoutSeconds=0 (unbounded)",
            channel.pipeline().get("streamIdleStateHandler"), is(nullValue()));
        assertThat("streaming relay handler installed",
            channel.pipeline().get(StreamingResponseRelayHandler.class), notNullValue());

        channel.finishAndReleaseAll();
    }
}
