package org.mockserver.httpclient;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Message;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.util.Arrays;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.httpclient.NettyHttpClient.CONNECTION_POOL;
import static org.mockserver.httpclient.NettyHttpClient.POOL_KEY;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

@ChannelHandler.Sharable
public class HttpClientHandler extends SimpleChannelInboundHandler<Message> {

    private final List<String> connectionClosedStrings = Arrays.asList(
        "Broken pipe",
        "(broken pipe)",
        "Connection reset"
    );

    private final MockServerLogger mockServerLogger;

    HttpClientHandler() {
        this(new MockServerLogger(HttpClientHandler.class));
    }

    HttpClientHandler(MockServerLogger mockServerLogger) {
        super(false);
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Message response) {
        Channel channel = ctx.channel();
        java.util.concurrent.CompletableFuture<Message> responseFuture = channel.attr(RESPONSE_FUTURE).get();
        // Return the channel to the pool (or decide to close it) BEFORE completing the future so
        // that, by the time the caller is unblocked, an idle keep-alive connection is already
        // available for the next request to the same upstream. When the channel is not poolable
        // this is byte-identical to the historical "complete then close" behaviour.
        boolean returnedToPool = tryReturnToPool(channel, response);
        if (responseFuture != null) {
            responseFuture.complete(response);
        }
        if (!returnedToPool) {
            ctx.close();
        }
    }

    /**
     * If this channel is poolable (marked with {@link NettyHttpClient#CONNECTION_POOL}) and the
     * response permits HTTP keep-alive reuse, clear the per-request future and offer the channel
     * back to the pool. Returns {@code true} when the channel was returned to the pool (and must
     * NOT be closed), {@code false} when it should be closed (current/default behaviour).
     * <p>
     * A channel is never reused when: pooling is off, the upstream signalled {@code Connection:
     * close}, the channel is no longer active, the message is not a complete {@link HttpResponse},
     * the response did not go through clean HTTP framing (see {@link #isCleanlyFramedHttpResponse}),
     * the channel is not genuinely quiescent (see {@link ChannelCleanliness#isQuiescent}), or the
     * pool is saturated for the key.
     * <p>
     * VERIFICATION GUARD: pooling is on by default, and a desync from wrongly pooling a dirty channel
     * (or the loopback self-deadlock from the shared event-loop variant) only surfaces in the FAILSAFE
     * integration phase — this area has regressed TWICE because targeted {@code -Dtest} unit runs skip
     * it. Any change to this pool-return gate, the quiescence check, or the forward client's event-loop
     * wiring MUST be verified by running the Extended / Websocket / proxy {@code *IntegrationTest}
     * classes with pooling on, not just the unit guards (NettyHttpClientConnectionPoolTest /
     * ForwardConnectionPoolLoopbackCallbackTest). See docs/operations/performance-tuning.md.
     */
    private boolean tryReturnToPool(Channel channel, Message response) {
        HttpForwardConnectionPool pool = channel.attr(CONNECTION_POOL).get();
        String key = channel.attr(POOL_KEY).get();
        if (pool == null || key == null || !channel.isActive() || !(response instanceof HttpResponse)) {
            return false;
        }
        HttpResponse httpResponse = (HttpResponse) response;
        if (!isCleanlyFramedHttpResponse(httpResponse) || !permitsKeepAlive(httpResponse)) {
            return false;
        }
        // Final, decisive gate: even a valid in-range status can leave the channel dirty (wrong
        // Content-Length, trailing/pipelined bytes, raw bytes that frame mid-stream). Only pool when
        // the client codec's decoder has no leftover undecoded bytes; otherwise close (fail-closed).
        if (!ChannelCleanliness.isQuiescent(channel)) {
            return false;
        }
        // Remove the in-flight read timeout armed for this request (NettyHttpClient#armPooledInFlightReadTimeout)
        // BEFORE the channel goes idle in the pool — otherwise that read timeout would fire during legitimate
        // idle keep-alive and tear down a healthy pooled connection. A streaming response would already have
        // stripped it, so this is a guarded no-op in that case.
        if (channel.pipeline().get(ReadTimeoutHandler.class) != null) {
            channel.pipeline().remove(ReadTimeoutHandler.class);
        }
        // Detach the completed future so a stale reference cannot be completed again, and so the
        // connection-error handler does not error on a clean idle close.
        channel.attr(RESPONSE_FUTURE).set(null);
        return pool.release(key, channel);
    }

    /**
     * A channel is only safe to reuse when the response that just completed on it went through
     * clean HTTP/1.1 request/response framing, leaving the channel's {@code HttpClientCodec}
     * decoder in a pristine state ready for the next exchange.
     * <p>
     * MockServer's {@code error()} action (HttpError) deliberately writes raw, non-HTTP bytes
     * and/or drops the connection. When such a reply is parsed by the client codec it surfaces here
     * as an {@link HttpResponse} whose status code is outside the valid HTTP range (the decoder
     * marks the message as a failure and the mapper assigns a sentinel code, e.g. {@code 999}). Such
     * a channel must never be pooled. Only a status code in the valid HTTP range [100, 599] indicates
     * a cleanly-framed response, so only those channels are eligible for reuse.
     * <p>
     * This status-range guard is a cheap necessary filter but is not on its own sufficient: a reply
     * with a valid in-range status can still leave undecoded bytes on the channel (wrong
     * {@code Content-Length}, trailing/pipelined bytes, raw bytes that frame mid-stream). The decisive
     * cleanliness gate that catches those cases is {@link ChannelCleanliness#isQuiescent}, which
     * verifies the client codec's decoder has no leftover bytes before the channel is pooled.
     */
    private boolean isCleanlyFramedHttpResponse(HttpResponse response) {
        Integer statusCode = response.getStatusCode();
        return statusCode != null && statusCode >= 100 && statusCode <= 599;
    }

    /**
     * The response permits connection reuse unless it carries a {@code Connection: close} header.
     * MockServer aggregates and forwards HTTP/1.1 responses, whose default is keep-alive, so only
     * an explicit close token disqualifies reuse here.
     */
    private boolean permitsKeepAlive(HttpResponse response) {
        String connectionHeader = response.getFirstHeader("connection");
        return connectionHeader == null || !connectionHeader.toLowerCase().contains("close");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isNotSslException(cause) && isNotConnectionReset(cause)) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("exception caught by HTTP client handler - " + cause.getMessage())
                        .setThrowable(cause)
                );
            }
        }
        java.util.concurrent.CompletableFuture<Message> responseFuture = ctx.channel().attr(RESPONSE_FUTURE).get();
        if (responseFuture != null) {
            responseFuture.completeExceptionally(cause);
        }
        ctx.close();
    }

    private boolean isNotSslException(Throwable cause) {
        return !(cause.getCause() instanceof SSLException || cause instanceof DecoderException || cause instanceof NotSslRecordException);
    }

    private boolean isNotConnectionReset(Throwable cause) {
        return connectionClosedStrings.stream().noneMatch(connectionClosedString ->
            (isNotBlank(cause.getMessage()) && cause.getMessage().contains(connectionClosedString))
                || (cause.getCause() != null && isNotBlank(cause.getCause().getMessage()) && cause.getCause().getMessage().contains(connectionClosedString)));
    }
}
