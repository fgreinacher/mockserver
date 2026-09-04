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

    // ---------------------------------------------------------------------------------------------
    // GitHub issue #2641 - streaming responses wrongly closed the connection.
    //
    // The old finishStream() always closed on the default (closeConnection unset), never consulting
    // keep-alive, and closed the shared HTTP/2 parent connection for a single stream. These tests
    // pin the corrected end-of-stream decision at the handler layer (they go red against the old
    // code): an HTTP/1.1 keep-alive stream and any HTTP/2 stream must leave the channel open, while
    // an explicit closeConnection:true or alwaysCloseSocketConnections still closes on HTTP/1.1.
    // ---------------------------------------------------------------------------------------------

    private static final HttpSseResponse ONE_EVENT =
        HttpSseResponse.sseResponse().withEvent(SseEvent.sseEvent().withData("some_data"));

    private boolean channelOpenAfterStream(HttpSseResponseActionHandler handler, org.mockserver.model.HttpRequest request, HttpSseResponse sseResponse) {
        ChannelInboundHandlerAdapter dummy = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(dummy);
        ChannelHandlerContext ctx = channel.pipeline().context(dummy);
        // no event delay, so the handler (and the terminal LastHttpContent write listener) run
        // synchronously on this thread - the close decision has already been applied on return
        handler.handle(sseResponse, ctx, request);
        return channel.isOpen();
    }

    @Test
    public void shouldKeepHttp1ConnectionOpenByDefaultForKeepAliveRequest() {
        // given - an HTTP/1.1 keep-alive request and an SSE response with no closeConnection set
        // when / then - the connection promised keep-alive stays open so the client can reuse it
        assertThat(
            channelOpenAfterStream(handler, request("/some_path").withKeepAlive(true), ONE_EVENT),
            is(true)
        );
    }

    @Test
    public void shouldCloseHttp1ConnectionWhenRequestIsNotKeepAlive() {
        // given - an HTTP/1.1 request that did not request keep-alive, no closeConnection set
        // when / then - the default falls back to the request's own keep-alive intent (close)
        assertThat(
            channelOpenAfterStream(handler, request("/some_path").withKeepAlive(false), ONE_EVENT),
            is(false)
        );
    }

    @Test
    public void shouldCloseHttp1ConnectionWhenCloseConnectionExplicitlyTrue() {
        // given - an explicit closeConnection:true on a keep-alive request
        // when / then - the explicit opt-out still wins on HTTP/1.1 (unchanged behaviour)
        assertThat(
            channelOpenAfterStream(handler, request("/some_path").withKeepAlive(true),
                HttpSseResponse.sseResponse().withEvent(SseEvent.sseEvent().withData("some_data")).withCloseConnection(true)),
            is(false)
        );
    }

    @Test
    public void shouldKeepHttp1ConnectionOpenWhenCloseConnectionExplicitlyFalse() {
        // given - an explicit closeConnection:false on a non-keep-alive request
        // when / then - the explicit opt-in to keep-alive wins over the request's intent
        assertThat(
            channelOpenAfterStream(handler, request("/some_path").withKeepAlive(false),
                HttpSseResponse.sseResponse().withEvent(SseEvent.sseEvent().withData("some_data")).withCloseConnection(false)),
            is(true)
        );
    }

    @Test
    public void shouldNeverCloseHttp2ParentConnectionWhenCloseConnectionUnset() {
        // given - a request that arrived on an HTTP/2 stream, no closeConnection set
        // when / then - closing the parent would GOAWAY every sibling stream, so it stays open
        assertThat(
            channelOpenAfterStream(handler, request("/some_path").withStreamId(7), ONE_EVENT),
            is(true)
        );
    }

    @Test
    public void shouldNeverCloseHttp2ParentConnectionEvenWhenCloseConnectionTrue() {
        // given - an HTTP/2 stream with an explicit closeConnection:true
        // when / then - even the explicit opt-out must not tear down the shared HTTP/2 connection
        assertThat(
            channelOpenAfterStream(handler, request("/some_path").withStreamId(7),
                HttpSseResponse.sseResponse().withEvent(SseEvent.sseEvent().withData("some_data")).withCloseConnection(true)),
            is(true)
        );
    }

    @Test
    public void shouldCloseHttp1ConnectionWhenAlwaysCloseSocketConnectionsConfigured() {
        // given - alwaysCloseSocketConnections forces a close regardless of keep-alive
        HttpSseResponseActionHandler alwaysCloseHandler = new HttpSseResponseActionHandler(
            new MockServerLogger(HttpSseResponseActionHandlerStreamIdTest.class),
            mock(Scheduler.class),
            configuration().alwaysCloseSocketConnections(true)
        );
        // when / then - a keep-alive request with no closeConnection is still closed on HTTP/1.1
        assertThat(
            channelOpenAfterStream(alwaysCloseHandler, request("/some_path").withKeepAlive(true), ONE_EVENT),
            is(false)
        );
    }
}
