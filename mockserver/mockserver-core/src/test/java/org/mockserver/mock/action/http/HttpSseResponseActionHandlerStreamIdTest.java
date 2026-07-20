package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponse;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * SSE over HTTP/2 delivers nothing unless the response head carries the request's stream id.
 * <p>
 * {@link HttpSseResponseActionHandler} builds a Netty response head by hand and writes it straight
 * to the channel, so it never reaches {@code MockServerHttpResponseToFullHttpResponse} — the only
 * other place that turns the stream id into the {@code x-http2-stream-id} wire header. Without the
 * header {@code HttpToHttp2ConnectionHandler} routes the head (and every chunk that follows it) onto
 * a fresh server-initiated stream, so the client receives nothing and hangs. This is the direct
 * sibling of GitHub issue #2419, in which server-streaming gRPC delivered zero messages for exactly
 * this reason.
 * <p>
 * Events carry no delay, so the handler runs synchronously on the calling thread.
 */
public class HttpSseResponseActionHandlerStreamIdTest {

    private final HttpSseResponseActionHandler handler =
        new HttpSseResponseActionHandler(new MockServerLogger(HttpSseResponseActionHandlerStreamIdTest.class), mock(Scheduler.class), configuration());

    private HttpResponse headFor(org.mockserver.model.HttpRequest request) {
        ChannelInboundHandlerAdapter dummy = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(dummy);
        ChannelHandlerContext ctx = channel.pipeline().context(dummy);

        handler.handle(
            HttpSseResponse.sseResponse().withEvent(SseEvent.sseEvent().withData("some_data")),
            ctx,
            request
        );

        Object head = channel.readOutbound();
        assertThat("first outbound object should be the response head", head instanceof HttpResponse, is(true));
        return (HttpResponse) head;
    }

    @Test
    public void shouldSendSseResponseHeadDownTheRequestHttp2Stream() {
        // given - a request that arrived on HTTP/2 stream 5
        // when
        HttpResponse head = headFor(request("/some_path").withStreamId(5));

        // then - the head is routed back onto stream 5 rather than a new server-initiated stream
        assertThat(head.headers().getInt(Http2StreamIds.STREAM_ID_HEADER), is(5));
    }

    @Test
    public void shouldNotAddStreamIdHeaderOnHttp1() {
        // given - an HTTP/1.1 request has no stream id
        // when
        HttpResponse head = headFor(request("/some_path"));

        // then - no HTTP/2 extension header leaks onto an HTTP/1.1 response
        assertThat(head.headers().get(Http2StreamIds.STREAM_ID_HEADER), nullValue());
    }
}
