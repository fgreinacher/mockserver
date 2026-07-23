package org.mockserver.netty.unification;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Unit coverage for {@link Http2StreamIdAuditHandler} — the safety-net that makes the "HTTP/2 response
 * head written without an {@code x-http2-stream-id}" defect class (GitHub issue #2419 and its SSE /
 * streaming-body / metrics / MCP siblings) loud instead of silent.
 * <p>
 * The handler only <em>warns</em>; it never repairs or drops the head. These tests therefore assert on
 * the observable behaviour — whether a WARN reaches the logger — for three cases:
 * an unstamped head warns exactly once, a stamped head is silent, and a second unstamped head on the
 * same connection does not warn again (the per-connection dedup that stops a genuinely-broken write
 * site from flooding the log).
 */
public class Http2StreamIdAuditHandlerTest {

    private EmbeddedChannel channel;

    @After
    public void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A {@link MockServerLogger} whose underlying slf4j {@link Logger} is a Mockito mock, with the
     * configured level pinned to INFO so WARN is always enabled regardless of ambient global state.
     */
    private static MockServerLogger loggerBackedBy(Logger slf4jLogger) {
        Configuration configuration = configuration().logLevel(Level.INFO);
        return new MockServerLogger(configuration, slf4jLogger);
    }

    private static HttpResponse unstampedResponseHead() {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    }

    private static HttpResponse stampedResponseHead(int streamId) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        Http2StreamIds.stamp(response, streamId);
        return response;
    }

    private void drainOutbound() {
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(outbound);
        }
    }

    @Test
    public void shouldWarnOnceWhenResponseHeadHasNoStreamId() {
        // given - an audit handler on a channel, backed by a capturing logger
        Logger slf4jLogger = mock(Logger.class);
        channel = new EmbeddedChannel(new Http2StreamIdAuditHandler(loggerBackedBy(slf4jLogger)));

        // when - an unstamped response head is written outbound
        channel.writeOutbound(unstampedResponseHead());
        drainOutbound();

        // then - exactly one WARN naming the missing header reaches the logger
        verify(slf4jLogger, times(1)).warn(contains(Http2StreamIds.STREAM_ID_HEADER.toString()), (Throwable) any());
    }

    @Test
    public void shouldNotWarnWhenResponseHeadHasStreamId() {
        // given - an audit handler on a channel, backed by a capturing logger
        Logger slf4jLogger = mock(Logger.class);
        channel = new EmbeddedChannel(new Http2StreamIdAuditHandler(loggerBackedBy(slf4jLogger)));

        // when - a correctly-stamped response head is written outbound
        channel.writeOutbound(stampedResponseHead(3));
        drainOutbound();

        // then - the handler stays silent
        verify(slf4jLogger, never()).warn(anyString(), (Throwable) any());
    }

    @Test
    public void shouldWarnOnlyOncePerConnectionForRepeatedUnstampedHeads() {
        // given - a single audit handler instance (one per connection), backed by a capturing logger
        Logger slf4jLogger = mock(Logger.class);
        channel = new EmbeddedChannel(new Http2StreamIdAuditHandler(loggerBackedBy(slf4jLogger)));

        // when - two unstamped response heads are written on the same connection
        channel.writeOutbound(unstampedResponseHead());
        channel.writeOutbound(unstampedResponseHead());
        drainOutbound();

        // then - the per-connection dedup means the WARN fires exactly once, not once per head
        verify(slf4jLogger, times(1)).warn(contains(Http2StreamIds.STREAM_ID_HEADER.toString()), (Throwable) any());
    }
}
