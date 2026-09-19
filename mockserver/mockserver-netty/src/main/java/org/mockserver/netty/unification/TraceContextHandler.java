package org.mockserver.netty.unification;

import io.netty.channel.*;
import io.netty.util.AttributeKey;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.telemetry.TraceContextAttributes;
import org.mockserver.telemetry.W3CTraceContext;
import org.mockserver.uuid.UUIDService;

/**
 * Netty handler that extracts W3C {@code traceparent} / {@code tracestate}
 * headers from inbound {@link HttpRequest} objects and stores the parsed
 * {@link W3CTraceContext} as a channel attribute. When
 * {@code otelPropagateTraceContext} is enabled, the same headers are copied
 * to outbound {@link HttpResponse} objects so the caller can correlate a
 * request-response pair within its distributed trace.
 * <p>
 * This handler is {@link ChannelHandler.Sharable} because it keeps no
 * per-channel mutable state itself — all state is stored in the channel
 * attribute {@link #TRACE_CONTEXT}.
 */
@ChannelHandler.Sharable
public class TraceContextHandler extends ChannelDuplexHandler {

    /**
     * Delegates to the shared constant in {@code mockserver-core} so both the
     * Netty handler and the core action handler use the same attribute key.
     */
    public static final AttributeKey<W3CTraceContext> TRACE_CONTEXT =
        TraceContextAttributes.TRACE_CONTEXT;

    private final Configuration configuration;

    public TraceContextHandler(Configuration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            String traceparent = request.getFirstHeader("traceparent");
            String tracestate = request.getFirstHeader("tracestate");

            if (traceparent != null && !traceparent.isEmpty()) {
                W3CTraceContext context = W3CTraceContext.parse(traceparent, tracestate);
                if (context != null && context.isValid()) {
                    ctx.channel().attr(TRACE_CONTEXT).set(context);
                }
            } else if (configuration.otelGenerateTraceId()) {
                W3CTraceContext generated = generateTraceContext();
                ctx.channel().attr(TRACE_CONTEXT).set(generated);
            }
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse && configuration.otelPropagateTraceContext()) {
            HttpResponse response = (HttpResponse) msg;
            W3CTraceContext context = ctx.channel().attr(TRACE_CONTEXT).get();
            if (context != null && context.isValid()) {
                response.withHeader("traceparent", context.toTraceparent());
                if (context.getTraceState() != null && !context.getTraceState().isEmpty()) {
                    response.withHeader("tracestate", context.getTraceState());
                }
            }
        }
        ctx.write(msg, promise);
    }

    /**
     * Generate a new W3C trace context with a random trace ID and parent ID.
     * Uses version 00 and sampled flag 01.
     */
    private static W3CTraceContext generateTraceContext() {
        String traceId = randomHexString(32);
        String parentId = randomHexString(16);
        return new W3CTraceContext("00", traceId, parentId, "01", null);
    }

    /**
     * Generate a lowercase hex string of the specified length from random (version 4) UUIDs.
     * The W3C {@code traceparent} contract forbids an all-zero trace/span id; that is guaranteed
     * structurally here because a v4 UUID always has {@code '4'} in the version nibble (hex position
     * 12) and one of {@code '8'/'9'/'a'/'b'} in the variant nibble (position 16), so the hex string can
     * never be all zeros. A maintainer swapping {@link UUIDService#getNonSecureUUID()} for a different
     * fast RNG MUST re-verify that non-zero property holds for the replacement.
     */
    static String randomHexString(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            sb.append(UUIDService.getNonSecureUUID().replace("-", ""));
        }
        return sb.substring(0, length);
    }
}
