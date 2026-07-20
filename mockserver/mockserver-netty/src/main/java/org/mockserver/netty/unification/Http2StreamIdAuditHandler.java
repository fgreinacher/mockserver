package org.mockserver.netty.unification;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.slf4j.event.Level;

/**
 * Makes the "response written on a phantom HTTP/2 stream" defect class loud instead of silent.
 * <p>
 * On the HTTP/2 server pipeline, {@code HttpToHttp2ConnectionHandler} routes an outbound response
 * head onto a stream by reading its {@code x-http2-stream-id} header. When the header is missing it
 * does <em>not</em> fail — it silently allocates a fresh server-initiated stream, so the client
 * never receives the response and simply hangs until its own timeout fires. Nothing is logged, no
 * exception is raised, and every server-side test that inspects model objects still passes. That is
 * how GitHub issue #2419 (server-streaming gRPC delivering zero messages) shipped, and how the SSE,
 * streaming-body, metrics and MCP instances of the same bug shipped alongside it.
 * <p>
 * This handler sits immediately downstream of the HTTP/2 connection handler, so every outbound
 * response head written by any handler added after it passes through here. A head without a stream
 * id is always a bug, so it is logged at WARN naming the handler that is about to mis-route it.
 * <p>
 * It deliberately only <em>warns</em>; it neither throws nor tries to repair the head. Repair is not
 * possible safely: this pipeline multiplexes many concurrent streams over one connection, and a
 * streaming response is written asynchronously long after its request was read, so any stream id
 * this handler could infer from "the most recent inbound request" would sometimes be another
 * client's stream. Writing a response onto the wrong stream leaks one client's data to another,
 * which is strictly worse than the hang it would be fixing. Correctness therefore has to come from
 * the write site stamping the id it already holds ({@link Http2StreamIds}); this handler exists to
 * ensure a site that forgets is discovered in the first test run rather than in production.
 */
public class Http2StreamIdAuditHandler extends ChannelDuplexHandler {

    private final MockServerLogger mockServerLogger;
    // Warn at most once per connection. A genuinely-broken write site emits one unstamped head per
    // response, so an unthrottled warning would flood the log under load — which buries the very
    // signal this handler exists to surface. Deliberately NOT @Sharable: this field is per-channel
    // state, and one instance is created per pipeline.
    private boolean alreadyWarned;

    public Http2StreamIdAuditHandler(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!alreadyWarned && msg instanceof HttpResponse && Http2StreamIds.streamIdOf((HttpResponse) msg) == null) {
            alreadyWarned = true;
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("HTTP/2 response head written without an " + Http2StreamIds.STREAM_ID_HEADER
                            + " header - the HTTP/2 codec will route it onto a new server-initiated stream and the client will "
                            + "receive nothing; the write site must stamp the request's stream id (see Http2StreamIds). "
                            + "Warned once for this connection. status:{}")
                        .setArguments(((HttpResponse) msg).status())
                );
            }
        }
        super.write(ctx, msg, promise);
    }
}
