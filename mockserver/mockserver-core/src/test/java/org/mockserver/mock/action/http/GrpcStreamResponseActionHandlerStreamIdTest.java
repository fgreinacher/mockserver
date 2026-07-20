package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponse;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * The gRPC streaming handler is the site that motivated the whole {@link Http2StreamIds} choke point
 * (GitHub issue #2419), and it carried a second, subtler instance of the same hazard.
 * <p>
 * It used to stamp the stream id <em>before</em> applying the expectation's own headers, which are
 * applied with {@code add(...)}. A user-supplied {@code x-http2-stream-id} therefore ended up
 * alongside the correct one — and a foreign stream id makes the HTTP/2 codec write on a stream the
 * client does not own, triggering a {@code PROTOCOL_ERROR}/{@code GOAWAY} that hangs the client just
 * as badly as the missing-header case this class was fixing. Stamping now happens after the header
 * loop, through {@code Http2StreamIds}, which replaces rather than appends.
 */
public class GrpcStreamResponseActionHandlerStreamIdTest {

    private final GrpcStreamResponseActionHandler handler = new GrpcStreamResponseActionHandler(
        new MockServerLogger(GrpcStreamResponseActionHandlerStreamIdTest.class),
        mock(Scheduler.class),
        mock(org.mockserver.grpc.GrpcProtoDescriptorStore.class),
        configuration(),
        mock(org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.class));

    private HttpResponse headFor(GrpcStreamResponse grpcStreamResponse, org.mockserver.model.HttpRequest request) {
        ChannelInboundHandlerAdapter dummy = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(dummy);
        ChannelHandlerContext ctx = channel.pipeline().context(dummy);

        handler.handle(grpcStreamResponse, ctx, request);

        Object head = channel.readOutbound();
        assertThat("first outbound object should be the response head", head instanceof HttpResponse, is(true));
        return (HttpResponse) head;
    }

    @Test
    public void shouldSendGrpcStreamHeadDownTheRequestHttp2Stream() {
        HttpResponse head = headFor(GrpcStreamResponse.grpcStreamResponse(), request("/some.Service/Method").withStreamId(5));

        assertThat(head.headers().getInt(Http2StreamIds.STREAM_ID_HEADER), is(5));
    }

    @Test
    public void shouldNotLetAUserSuppliedStreamIdHeaderOverrideTheRealOne() {
        // given - an expectation whose headers include a forged x-http2-stream-id. Writing on that
        // foreign stream triggers PROTOCOL_ERROR/GOAWAY and hangs the client.
        GrpcStreamResponse grpcStreamResponse = GrpcStreamResponse.grpcStreamResponse()
            .withHeader(Http2StreamIds.STREAM_ID_HEADER.toString(), "999");

        // when
        HttpResponse head = headFor(grpcStreamResponse, request("/some.Service/Method").withStreamId(5));

        // then - the real stream id wins outright, and is not merely added alongside the forgery
        assertThat(head.headers().getAll(Http2StreamIds.STREAM_ID_HEADER).size(), is(1));
        assertThat(head.headers().getInt(Http2StreamIds.STREAM_ID_HEADER), is(5));
    }

    @Test
    public void shouldNotAddStreamIdHeaderOnHttp1() {
        HttpResponse head = headFor(GrpcStreamResponse.grpcStreamResponse(), request("/some.Service/Method"));

        assertThat(head.headers().get(Http2StreamIds.STREAM_ID_HEADER), nullValue());
    }
}
