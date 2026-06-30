package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AttributeKey;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.HttpClientHandler;
import org.mockserver.httpclient.StreamingResponseRelayHandler;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * An {@link HttpObjectAggregator} that can recognise streaming responses — specifically
 * Server-Sent Events ({@code Content-Type: text/event-stream}) — so that, when
 * MockServer is acting as a proxy, such responses can be relayed to the client
 * incrementally instead of being fully buffered in memory before being forwarded.
 * <p>
 * When streaming is detected and enabled, this handler removes itself from the pipeline
 * and installs a {@link StreamingResponseRelayHandler} that processes the unaggregated
 * {@link HttpObject}s. The {@link HttpClientHandler} is also removed to prevent double
 * completion of the response future.
 * <p>
 * When streaming is not detected (or not enabled), the handler delegates to
 * {@link HttpObjectAggregator} so the non-streaming path stays byte-identical.
 * <p>
 * Only the {@code text/event-stream} content type triggers streaming detection.
 * Ordinary chunked responses (e.g. Tomcat/servlet responses that use chunked
 * transfer-encoding without {@code Content-Length}) are aggregated normally.
 */
public class StreamingAwareHttpObjectAggregator extends HttpObjectAggregator {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final boolean relayOnly;

    /**
     * Create an aggregator with streaming awareness for the {@link NettyHttpClient} forward path.
     * When a streaming response is detected, it installs a {@link StreamingResponseRelayHandler}
     * that completes the {@code RESPONSE_FUTURE} at head time.
     *
     * @param maxContentLength the maximum content length for non-streaming responses
     * @param configuration    the MockServer configuration (for streaming properties)
     * @param mockServerLogger the logger
     */
    public StreamingAwareHttpObjectAggregator(int maxContentLength, Configuration configuration, MockServerLogger mockServerLogger) {
        this(maxContentLength, configuration, mockServerLogger, false);
    }

    /**
     * Create an aggregator with streaming awareness.
     *
     * @param maxContentLength the maximum content length for non-streaming responses
     * @param configuration    the MockServer configuration (for streaming properties)
     * @param mockServerLogger the logger
     * @param relayOnly        if true, when a streaming response is detected the aggregator simply
     *                         removes itself from the pipeline so that unaggregated {@link HttpObject}s
     *                         flow through to the next handler. This mode is used on the loopback
     *                         pipeline where there is no {@code RESPONSE_FUTURE} to complete.
     */
    public StreamingAwareHttpObjectAggregator(int maxContentLength, Configuration configuration, MockServerLogger mockServerLogger, boolean relayOnly) {
        super(maxContentLength);
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.relayOnly = relayOnly;
    }

    /**
     * Backwards-compatible constructor for use without streaming support (e.g. in relay
     * pipelines where Configuration is not yet threaded through). Behaves identically to
     * a plain {@link HttpObjectAggregator}.
     *
     * @param maxContentLength the maximum content length
     */
    public StreamingAwareHttpObjectAggregator(int maxContentLength) {
        super(maxContentLength);
        this.configuration = null;
        this.mockServerLogger = null;
        this.relayOnly = false;
    }

    /**
     * A response should be relayed as a stream rather than aggregated when it is a
     * Server-Sent Events stream ({@code Content-Type: text/event-stream}).
     * <p>
     * Only SSE responses are detected as streaming. Ordinary chunked responses
     * (e.g. those produced by servlet containers like Tomcat, which strip
     * {@code Content-Length} and use chunked transfer-encoding for all responses)
     * are aggregated normally. Real streaming APIs — Anthropic, OpenAI, and MCP
     * streamable-HTTP — all use {@code text/event-stream}.
     *
     * @param response the response head (status line and headers)
     * @return true when the response body should be streamed through incrementally
     */
    public static boolean isStreamingResponse(HttpResponse response) {
        String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
        return contentType != null && contentType.toLowerCase(Locale.US).contains("text/event-stream");
    }

    /**
     * Channel attribute key set by {@code NettyHttpClient} when streaming must be disabled
     * for a particular request (e.g. FORWARD_REPLACE with a response override). When set
     * to {@code true}, this aggregator always delegates to the standard
     * {@link HttpObjectAggregator} path regardless of response content type.
     */
    private static final AttributeKey<Boolean> DISABLE_RESPONSE_STREAMING = AttributeKey.valueOf("DISABLE_RESPONSE_STREAMING");

    /**
     * Channel attribute set by {@code NettyHttpClient} (forward leg) or
     * {@code UpstreamProxyRelayHandler} (CONNECT-proxy loopback leg) when the OUTGOING request asked
     * for a streamed response (Accept: text/event-stream, or a JSON body with {@code "stream": true}).
     * When set, the response is relayed as a stream even if it omits
     * {@code Content-Type: text/event-stream} — covering streaming backends that do not send it,
     * such as the OpenAI Codex backend used by the opencode CLI.
     * <p>
     * Set per-request and consumed when the matching response head arrives, so a keep-alive CONNECT
     * tunnel carrying many requests applies the intent to the correct response only.
     */
    public static final AttributeKey<Boolean> EXPECT_STREAMING_RESPONSE = AttributeKey.valueOf("EXPECT_STREAMING_RESPONSE");

    /**
     * Diagnostic-only channel attribute recording {@code System.nanoTime()} at the moment the request
     * was forwarded to the upstream on the CONNECT-proxy loopback relay path (set by
     * {@code UpstreamProxyRelayHandler}). Used purely to compute a time-to-first-byte (TTFB) for the
     * DEBUG streaming-decision diagnostic when the matching response head arrives; absent on non-relay
     * paths, in which case the TTFB fields are simply omitted. Behaviour-preserving.
     */
    public static final AttributeKey<Long> REQUEST_FORWARDED_NANOS = AttributeKey.valueOf("REQUEST_FORWARDED_NANOS");

    /**
     * Diagnostic-only channel attribute recording the request line ({@code METHOD uri}) of the request
     * forwarded on the CONNECT-proxy loopback relay path (set by {@code UpstreamProxyRelayHandler}),
     * so the DEBUG streaming-decision diagnostic can identify which request a response belongs to.
     * Absent on non-relay paths. Behaviour-preserving.
     */
    public static final AttributeKey<String> REQUEST_LINE = AttributeKey.valueOf("REQUEST_LINE");

    /**
     * A JSON request body that turns on streaming, e.g. {@code {"stream": true}} (OpenAI/Anthropic/Codex).
     * Mirrors the detection in {@code NettyHttpClient} for the forward leg.
     */
    private static final Pattern STREAM_TRUE_IN_BODY = Pattern.compile("\"stream\"\\s*:\\s*true");

    /**
     * Whether the OUTGOING request asks for a streamed (Server-Sent Events style) response, so the
     * response should be relayed incrementally even if the upstream omits
     * {@code Content-Type: text/event-stream} (e.g. the OpenAI Codex backend used by the opencode CLI,
     * whose SSE response carries no content-type at all). Detected from the client's own intent: an
     * {@code Accept: text/event-stream} header, or a JSON request body containing {@code "stream": true}.
     * <p>
     * This is the netty-{@link HttpRequest} equivalent of {@code NettyHttpClient.requestExpectsStreamingResponse}
     * and is used on the CONNECT-proxy loopback relay path, where the request is a netty {@link FullHttpRequest}
     * rather than a MockServer {@code HttpRequest}. Reading the body via {@link ByteBuf#toString(java.nio.charset.Charset)}
     * is non-destructive (it does not advance the reader index), so the relayed request body is unaffected.
     *
     * @param request the netty request head (and, for body inspection, the {@link FullHttpRequest})
     * @return true when the response to this request should be relayed as a stream
     */
    public static boolean requestExpectsStreamingResponse(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String accept = request.headers().get(HttpHeaderNames.ACCEPT);
        if (accept != null && accept.toLowerCase(Locale.US).contains("text/event-stream")) {
            return true;
        }
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null && contentType.toLowerCase(Locale.US).contains("json")
            && request instanceof FullHttpRequest) {
            ByteBuf content = ((FullHttpRequest) request).content();
            if (content != null && content.isReadable()) {
                String body = content.toString(StandardCharsets.UTF_8);
                if (STREAM_TRUE_IN_BODY.matcher(body).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpResponse && !(msg instanceof FullHttpResponse)) {
            HttpResponse response = (HttpResponse) msg;
            // Diagnostic only — observe the decision and log it at DEBUG without changing the decision.
            logStreamingDecision(ctx, response);
            if (isStreamingEnabled() && !isStreamingDisabledOnChannel(ctx)
                && (isStreamingResponse(response) || isStreamingExpectedByRequest(ctx))) {
                switchToStreamingMode(ctx, response);
                return;
            }
        }
        // Non-streaming: delegate to super (standard aggregation)
        super.channelRead(ctx, msg);
    }

    /**
     * Emits one DEBUG diagnostic LogEntry describing whether this response head will be relayed as a
     * {@code STREAM} or {@code AGGREGATE}d, the response status, the content-type (or {@code <none>}),
     * which condition triggered streaming ({@code sse-content-type} / {@code request-expected-streaming}
     * / {@code none}), and — when available on the (relay) channel — the time-to-first-byte and the
     * request line. Purely observational: it recomputes the same booleans the decision uses but does
     * not change control flow. Gated so it is silent unless DEBUG is enabled and a logger is present
     * (the relay/backwards-compatible constructors pass a {@code null} logger, in which case nothing
     * is emitted).
     */
    private void logStreamingDecision(ChannelHandlerContext ctx, HttpResponse response) {
        if (!MockServerLogger.isEnabled(Level.DEBUG) || mockServerLogger == null) {
            return;
        }
        boolean sseContentType = isStreamingResponse(response);
        boolean expectedByRequest = isStreamingExpectedByRequest(ctx);
        boolean willStream = isStreamingEnabled() && !isStreamingDisabledOnChannel(ctx)
            && (sseContentType || expectedByRequest);
        String decision = willStream ? "STREAM" : "AGGREGATE";
        String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
        String contentTypeDescription = contentType != null ? contentType : "<none>";
        String trigger = sseContentType
            ? "sse-content-type"
            : (expectedByRequest ? "request-expected-streaming" : "none");
        Long forwardedNanos = ctx.channel().attr(REQUEST_FORWARDED_NANOS).get();
        if (forwardedNanos != null) {
            long ttfbMs = (System.nanoTime() - forwardedNanos) / 1_000_000L;
            String requestLine = ctx.channel().attr(REQUEST_LINE).get();
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("streaming decision:{} status:{} content-type:{} trigger:{} ttfbMs:{} request:{}")
                    .setArguments(decision, response.status().code(), contentTypeDescription, trigger, ttfbMs, requestLine != null ? requestLine : "<unknown>")
            );
        } else {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("streaming decision:{} status:{} content-type:{} trigger:{}")
                    .setArguments(decision, response.status().code(), contentTypeDescription, trigger)
            );
        }
    }

    private boolean isStreamingEnabled() {
        return configuration != null && Boolean.TRUE.equals(configuration.streamingResponsesEnabled());
    }

    private boolean isStreamingDisabledOnChannel(ChannelHandlerContext ctx) {
        Boolean disabled = ctx.channel().attr(DISABLE_RESPONSE_STREAMING).get();
        return Boolean.TRUE.equals(disabled);
    }

    private boolean isStreamingExpectedByRequest(ChannelHandlerContext ctx) {
        return Boolean.TRUE.equals(ctx.channel().attr(EXPECT_STREAMING_RESPONSE).get());
    }

    private void switchToStreamingMode(ChannelHandlerContext ctx, HttpResponse responseHead) {
        if (MockServerLogger.isEnabled(Level.DEBUG) && mockServerLogger != null) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("switching response to streaming relay (relayOnly={})")
                    .setArguments(relayOnly)
            );
        }
        ChannelPipeline pipeline = ctx.pipeline();

        if (relayOnly) {
            // Relay-only mode (loopback pipeline): just remove the aggregator so HttpObjects
            // flow directly to the next handler (e.g. DownstreamProxyRelayHandler).
            // ctx.fireChannelRead still works after remove because the context retains its
            // position in the pipeline chain.
            pipeline.remove(this);
            ctx.fireChannelRead(responseHead);
            return;
        }

        // Install the streaming relay handler
        StreamingResponseRelayHandler relayHandler = new StreamingResponseRelayHandler(configuration, mockServerLogger);

        // Remove HttpClientHandler to prevent double completion of RESPONSE_FUTURE
        if (pipeline.get(HttpClientHandler.class) != null) {
            pipeline.remove(HttpClientHandler.class);
        }

        // Remove MockServerHttpClientCodec since it expects FullHttpResponse and would
        // fail on unaggregated HttpObject messages
        if (pipeline.get(MockServerHttpClientCodec.class) != null) {
            pipeline.remove(MockServerHttpClientCodec.class);
        }

        // Swap the per-request socket read timeout for a stream-appropriate idle bound.
        //
        // The socket read timeout (maxSocketTimeout, default 20s) armed on non-pooled channels
        // measures the gap between reads, which during streaming is the gap between chunks — a
        // streaming LLM response can legitimately pause far longer than that between chunks (model
        // reasoning), so it would kill a healthy stream. streamIdleTimeoutSeconds is documented to
        // REPLACE that socket timeout for streaming responses, so once streaming begins the socket
        // timeout is ALWAYS removed — otherwise streamIdleTimeoutSeconds=0 (disabled) would
        // paradoxically leave the 20s socket timeout armed and truncate long-paused streams.
        if (pipeline.get(ReadTimeoutHandler.class) != null) {
            pipeline.remove(ReadTimeoutHandler.class);
        }
        int idleTimeout = configuration.streamIdleTimeoutSeconds();
        if (idleTimeout > 0) {
            // Bound the stream by the stream-appropriate idle timeout (default 60s).
            pipeline.addBefore(ctx.name(), "streamIdleStateHandler", new IdleStateHandler(0, 0, idleTimeout, TimeUnit.SECONDS));
            pipeline.addAfter("streamIdleStateHandler", "streamIdleTimeoutHandler", new StreamIdleTimeoutHandler(mockServerLogger));
        }
        // idleTimeout == 0 explicitly disables the stream idle bound: the stream runs unbounded
        // (the socket timeout has been removed above so a healthy long-paused stream is not cut).

        // Replace this aggregator with the streaming relay handler
        pipeline.replace(this, "streamingResponseRelayHandler", relayHandler);

        // Fire the response head through the new handler using its own context
        ChannelHandlerContext relayCtx = pipeline.context(relayHandler);
        try {
            relayHandler.channelRead(relayCtx, responseHead);
        } catch (Exception e) {
            relayCtx.fireExceptionCaught(e);
        }
    }
}
