package org.mockserver.netty.http3;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.responsewriter.StreamErrorWriter;
import org.mockserver.telemetry.TraceContextAttributes;
import org.mockserver.telemetry.W3CTraceContext;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ResponseWriter} that serialises the MockServer {@link HttpResponse}
 * as HTTP/3 frames and writes them to a QUIC stream channel.
 * <p>
 * This allows the standard request-processing pipeline ({@code HttpState},
 * {@code HttpActionHandler}) to write responses identically regardless of
 * whether the request arrived via HTTP/1.1, HTTP/2, or HTTP/3.
 * <p>
 * <strong>Streaming support:</strong> when the response carries a
 * {@link StreamingBody} (SSE, chunked proxy forwarding, LLM streaming),
 * the headers are sent immediately and each chunk is forwarded as an HTTP/3
 * DATA frame. The QUIC stream output is shut down when the stream completes.
 * Backpressure is implemented via {@link StreamingBody#requestMore()}: each
 * chunk write completion triggers the next upstream read.
 */
public class Http3ResponseWriter extends ResponseWriter implements StreamErrorWriter {

    private final ChannelHandlerContext ctx;

    public Http3ResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
    }

    /**
     * Reset the QUIC stream with the supplied HTTP/3 error code (RESET_STREAM, RFC 9114
     * section 4.1). Only this request stream is reset; other streams on the QUIC connection are
     * unaffected. No ByteBuf is allocated, so there is nothing to release.
     */
    @Override
    public void writeStreamError(long errorCode) {
        if (ctx.channel() instanceof QuicStreamChannel && ctx.channel().isActive()) {
            // Netty's QuicStreamChannel.shutdownOutput takes an int, but a QUIC application error code
            // is a 62-bit varint. Every RFC 9114 §8.1 HTTP/3 code is tiny (<= 0x110), so this only
            // matters for out-of-range vendor codes — clamp (and warn) rather than silently truncating.
            int resetCode;
            if (errorCode < 0 || errorCode > Integer.MAX_VALUE) {
                if (MockServerLogger.isEnabled(Level.WARN) && mockServerLogger != null) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("HTTP/3 stream error code {} is out of the supported int range, clamping to {}")
                            .setArguments(errorCode, Integer.MAX_VALUE)
                    );
                }
                resetCode = Integer.MAX_VALUE;
            } else {
                resetCode = (int) errorCode;
            }
            // shutdownOutput(int) sends a RESET_STREAM frame carrying the application error code,
            // tearing down this stream's output without affecting the rest of the QUIC connection.
            ((QuicStreamChannel) ctx.channel()).shutdownOutput(resetCode);
        } else if (ctx.channel().isActive()) {
            ctx.close();
        }
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        if (response == null) {
            response = HttpResponse.notFoundResponse();
        }

        // W3C trace-context propagation on outbound HTTP/3 responses, gated by
        // otelPropagateTraceContext -- mirrors the outbound logic of
        // TraceContextHandler on the TCP path.
        propagateTraceContext(response);

        warnIfConnectionOptionsIgnored(response);

        if (response.getStreamingBody() != null) {
            writeStreamingResponse(request, response);
        } else {
            writeStaticResponse(response);
        }
    }

    /**
     * Warn, once per response, when an expectation carries {@link ConnectionOptions} that this
     * writer does not act on.
     *
     * <h3>Why a warning and not an implementation</h3>
     * <p>{@code ConnectionOptions} is honoured in nineteen places in the HTTP/1.1 writer and in
     * NONE on HTTP/3, so a user who sets {@code closeSocket} or {@code chunkSize} and switches a
     * test to HTTP/3 silently gets none of it while the expectation still reports as created.
     * Accepting an option and ignoring it is the worst of the three available behaviours, so
     * until the applicable subset is implemented the omission is at least made observable.</p>
     *
     * <p>The fields split into two groups:</p>
     * <ul>
     *   <li><b>Inapplicable by protocol</b> — {@code suppressConnectionHeader} and
     *       {@code keepAliveOverride} govern the {@code Connection}/{@code Keep-Alive} headers,
     *       which RFC 9114 section 4.2 forbids on HTTP/3 outright. There is nothing to
     *       implement; these are reported as not applicable rather than as missing.</li>
     *   <li><b>Applicable but unimplemented</b> — {@code closeSocket}, {@code closeSocketDelay},
     *       {@code chunkSize}, {@code chunkDelay}, {@code suppressContentLengthHeader} and
     *       {@code contentLengthHeaderOverride} all have meaningful QUIC equivalents (closing
     *       the connection, segmenting the body across STREAM frames, header manipulation).
     *       These are the follow-up work.</li>
     * </ul>
     *
     * <p>Deliberately not a hard rejection: failing the response would break users who set
     * {@code ConnectionOptions} globally across a suite that happens to include HTTP/3, turning
     * a silent no-op into a broken test run.</p>
     *
     * <p>This fires once per response carrying {@code connectionOptions}, which is repetitive for
     * a suite that sets them globally. Note that de-duplicating per writer would achieve nothing —
     * {@code Http3MockServerHandler} constructs a new writer for every request. Meaningful dedup
     * would have to hang off the QUIC CONNECTION (the stream channel's parent), which is left as
     * follow-up rather than added speculatively to this path.</p>
     */
    private void warnIfConnectionOptionsIgnored(HttpResponse response) {
        ConnectionOptions connectionOptions = response.getConnectionOptions();
        // Null-guarded on the logger as writeStreamError above is: this runs on the response path
        // and must never be the reason a response fails to be written. Deliberately NOT also gated
        // on MockServerLogger.isEnabled(WARN) — that reads the global configured log level, which
        // would make the emission depend on mutable process-wide state and any test of it
        // order-dependent. The body only runs when connectionOptions is set, which is rare.
        if (connectionOptions == null || mockServerLogger == null) {
            return;
        }
        List<String> unimplemented = unimplementedOnHttp3(connectionOptions);
        List<String> notApplicable = notApplicableOnHttp3(connectionOptions);
        if (unimplemented.isEmpty() && notApplicable.isEmpty()) {
            return;
        }
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.WARN)
                .setMessageFormat(
                    "connectionOptions are not applied on HTTP/3 and have been ignored - "
                        + "not yet implemented:{}- not applicable to HTTP/3 (RFC 9114 section 4.2 forbids "
                        + "connection-specific header fields):{}")
                .setArguments(unimplemented, notApplicable)
        );
    }

    /**
     * The {@link ConnectionOptions} fields that are set, have a meaningful HTTP/3 equivalent, and
     * are nonetheless not acted on by this writer. Package-private so the classification can be
     * asserted directly rather than only inferred from a log line.
     */
    static List<String> unimplementedOnHttp3(ConnectionOptions connectionOptions) {
        List<String> unimplemented = new ArrayList<>();
        if (connectionOptions == null) {
            return unimplemented;
        }
        if (connectionOptions.getCloseSocket() != null) {
            unimplemented.add("closeSocket");
        }
        if (connectionOptions.getCloseSocketDelay() != null) {
            unimplemented.add("closeSocketDelay");
        }
        if (connectionOptions.getChunkSize() != null) {
            unimplemented.add("chunkSize");
        }
        if (connectionOptions.getChunkDelay() != null) {
            unimplemented.add("chunkDelay");
        }
        if (connectionOptions.getSuppressContentLengthHeader() != null) {
            unimplemented.add("suppressContentLengthHeader");
        }
        if (connectionOptions.getContentLengthHeaderOverride() != null) {
            unimplemented.add("contentLengthHeaderOverride");
        }
        return unimplemented;
    }

    /**
     * The {@link ConnectionOptions} fields that are set but cannot apply to HTTP/3 at all,
     * because RFC 9114 section 4.2 forbids connection-specific header fields.
     */
    static List<String> notApplicableOnHttp3(ConnectionOptions connectionOptions) {
        List<String> notApplicable = new ArrayList<>();
        if (connectionOptions == null) {
            return notApplicable;
        }
        if (connectionOptions.getSuppressConnectionHeader() != null) {
            notApplicable.add("suppressConnectionHeader");
        }
        if (connectionOptions.getKeepAliveOverride() != null) {
            notApplicable.add("keepAliveOverride");
        }
        return notApplicable;
    }

    /**
     * When {@code otelPropagateTraceContext} is enabled, copy the trace context
     * headers (traceparent and optionally tracestate) from the channel attribute
     * onto the response. This mirrors the outbound write() logic of
     * {@code TraceContextHandler} on the TCP path.
     */
    private void propagateTraceContext(HttpResponse response) {
        if (configuration.otelPropagateTraceContext()) {
            W3CTraceContext context = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
            if (context != null && context.isValid()) {
                response.withHeader("traceparent", context.toTraceparent());
                if (context.getTraceState() != null && !context.getTraceState().isEmpty()) {
                    response.withHeader("tracestate", context.getTraceState());
                }
            }
        }
    }

    /**
     * Write a streaming response: send headers immediately, then subscribe to the
     * {@link StreamingBody} to forward each chunk as an HTTP/3 DATA frame. When the
     * stream completes (or errors), shut down the QUIC stream output.
     */
    private void writeStreamingResponse(HttpRequest request, HttpResponse response) {
        StreamingBody streamingBody = response.getStreamingBody();

        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.DEBUG)
                .setHttpRequest(request)
                .setMessageFormat("streaming response over HTTP/3 for request:{}")
                .setArguments(request)
        );

        // Send the response headers immediately (without SHUTDOWN_OUTPUT). Dropping content-length:
        // a streamed body's length is unknown at header time, and one copied from a relayed upstream
        // response describes a different body than the one actually streamed.
        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response, true);
        ctx.writeAndFlush(headersFrame);

        // Subscribe to the streaming body to forward chunks as HTTP/3 DATA frames.
        // After each chunk write completes, call streamingBody.requestMore() to trigger
        // the next upstream read -- this implements backpressure so a slow client does
        // not cause unbounded buffering.
        streamingBody.subscribe(
            // onChunk
            chunk -> {
                if (ctx.channel().isActive()) {
                    DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
                        Unpooled.copiedBuffer(chunk)
                    );
                    ctx.writeAndFlush(dataFrame).addListener(future ->
                        streamingBody.requestMore()
                    );
                } else {
                    // Channel is no longer active; still request more so the upstream can
                    // detect the closed channel on the next read and clean up.
                    streamingBody.requestMore();
                }
            },
            // onComplete -- flush an empty DATA frame to ensure all prior chunk
            // writes have drained through the QUIC pipeline before shutting down
            // the stream output (avoids truncation race with pending async writes).
            // When the response carries trailers, emit a trailing HEADERS frame after
            // the final DATA frame and before shutting down the stream output.
            () -> {
                if (ctx.channel().isActive()) {
                    DefaultHttp3HeadersFrame trailersFrame = Http3RequestBridge.toHttp3TrailersFrame(response);
                    if (trailersFrame != null) {
                        ctx.write(new DefaultHttp3DataFrame(Unpooled.EMPTY_BUFFER));
                        ctx.writeAndFlush(trailersFrame)
                            .addListener(future -> shutdownQuicStreamOutput());
                    } else {
                        ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.EMPTY_BUFFER))
                            .addListener(future -> shutdownQuicStreamOutput());
                    }
                }
            },
            // onError
            error -> {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setHttpRequest(request)
                        .setMessageFormat("streaming response error over HTTP/3 for request:{}error:{}")
                        .setArguments(request, error.getMessage())
                        .setThrowable(error)
                );
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.EMPTY_BUFFER))
                        .addListener(future -> shutdownQuicStreamOutput());
                }
            }
        );
    }

    /**
     * Write a static (non-streaming) response: headers + optional body DATA frame,
     * then shut down the QUIC stream output.
     */
    private void writeStaticResponse(HttpResponse response) {
        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);
        DefaultHttp3DataFrame dataFrame = Http3RequestBridge.toHttp3DataFrame(response);
        DefaultHttp3HeadersFrame trailersFrame = Http3RequestBridge.toHttp3TrailersFrame(response);

        ctx.write(headersFrame);
        if (dataFrame != null) {
            if (trailersFrame != null) {
                // headers + data + trailing HEADERS frame, then shutdown the stream output
                ctx.write(dataFrame);
                ctx.writeAndFlush(trailersFrame)
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            } else {
                ctx.writeAndFlush(dataFrame)
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            }
        } else if (trailersFrame != null) {
            // body-less response with trailers: headers + trailing HEADERS frame
            ctx.writeAndFlush(trailersFrame)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        } else {
            ctx.flush();
            shutdownQuicStreamOutput();
        }
    }

    /**
     * Shut down the output side of the QUIC stream, signalling to the peer that no
     * more data will be sent on this stream. Safe to call multiple times.
     */
    private void shutdownQuicStreamOutput() {
        if (ctx.channel() instanceof QuicStreamChannel) {
            ((QuicStreamChannel) ctx.channel()).shutdownOutput();
        }
    }
}
