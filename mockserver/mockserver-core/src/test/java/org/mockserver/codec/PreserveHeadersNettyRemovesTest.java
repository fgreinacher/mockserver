package org.mockserver.codec;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.model.Header;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class PreserveHeadersNettyRemovesTest {

    @Test
    public void shouldPreserveContentEncodingIfExists() {
        // given
        PreserveHeadersNettyRemoves preserveHeadersNettyRemoves = new PreserveHeadersNettyRemoves();
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(preserveHeadersNettyRemoves);
        DefaultFullHttpRequest fullHttpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/uri");
        fullHttpRequest.headers().add(HttpHeaderNames.CONTENT_ENCODING, "some_value");

        // when
        embeddedChannel.writeInbound(fullHttpRequest);

        // then
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Collections.singletonList(
            new Header(HttpHeaderNames.CONTENT_ENCODING.toString(), "some_value")
        )));
    }

    @Test
    public void shouldNotPreserveContentEncodingIfDoesNotExists() {
        // given
        PreserveHeadersNettyRemoves preserveHeadersNettyRemoves = new PreserveHeadersNettyRemoves();
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(preserveHeadersNettyRemoves);
        DefaultFullHttpRequest fullHttpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/uri");

        // when
        embeddedChannel.writeInbound(fullHttpRequest);

        // then
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Collections.emptyList()));
    }

    @Test
    public void shouldPreserveTransferEncodingIfExists() {
        // given
        PreserveHeadersNettyRemoves preserveHeadersNettyRemoves = new PreserveHeadersNettyRemoves();
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(preserveHeadersNettyRemoves);
        DefaultFullHttpRequest fullHttpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri");
        fullHttpRequest.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "chunked");

        // when
        embeddedChannel.writeInbound(fullHttpRequest);

        // then
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Collections.singletonList(
            new Header(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked")
        )));
    }

    @Test
    public void shouldNotLeakPreservedHeadersToLaterRequestOnSameChannel() {
        // given - a channel reused for two requests (e.g. a pooled connection)
        PreserveHeadersNettyRemoves preserveHeadersNettyRemoves = new PreserveHeadersNettyRemoves();
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(preserveHeadersNettyRemoves);

        DefaultFullHttpRequest compressedRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri");
        compressedRequest.headers().add(HttpHeaderNames.CONTENT_ENCODING, "gzip");

        DefaultFullHttpRequest plainRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri");

        // when - the first request is compressed and the second is not
        embeddedChannel.writeInbound(compressedRequest);
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Collections.singletonList(
            new Header(HttpHeaderNames.CONTENT_ENCODING.toString(), "gzip")
        )));

        embeddedChannel.writeInbound(plainRequest);

        // then - the second request must not inherit the first request's Content-Encoding
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Collections.emptyList()));
    }

    @Test
    public void shouldPreserveBothContentEncodingAndTransferEncoding() {
        // given
        PreserveHeadersNettyRemoves preserveHeadersNettyRemoves = new PreserveHeadersNettyRemoves();
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(preserveHeadersNettyRemoves);
        DefaultFullHttpRequest fullHttpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri");
        fullHttpRequest.headers().add(HttpHeaderNames.CONTENT_ENCODING, "gzip");
        fullHttpRequest.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "chunked");

        // when
        embeddedChannel.writeInbound(fullHttpRequest);

        // then
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Arrays.asList(
            new Header(HttpHeaderNames.CONTENT_ENCODING.toString(), "gzip"),
            new Header(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked")
        )));
    }


    @Test
    public void shouldCaptureOriginalBodyOfContentEncodedRequestAcrossChunks() {
        // given - a compressed request whose body arrives as a message, two chunks and a last chunk
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new PreserveHeadersNettyRemoves());
        DefaultHttpRequest head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri");
        head.headers().add(HttpHeaderNames.CONTENT_ENCODING, "gzip");

        // when
        embeddedChannel.writeInbound(head);
        embeddedChannel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(new byte[]{1, 2, 3})));
        assertThat("the body is published only once the request is complete",
            PreserveHeadersNettyRemoves.originalRawBody(embeddedChannel), nullValue());
        embeddedChannel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(new byte[]{(byte) 0xff})));
        embeddedChannel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[]{4})));

        // then
        assertThat(PreserveHeadersNettyRemoves.originalRawBody(embeddedChannel), equalTo(new byte[]{1, 2, 3, (byte) 0xff, 4}));
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Collections.singletonList(
            new Header(HttpHeaderNames.CONTENT_ENCODING.toString(), "gzip")
        )));
        embeddedChannel.finishAndReleaseAll();
    }

    @Test
    public void shouldNotLeakOriginalBodyToLaterRequestOnSameChannel() {
        // given - a compressed request followed by a plain one on the same connection
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new PreserveHeadersNettyRemoves());
        DefaultFullHttpRequest compressedRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri", Unpooled.wrappedBuffer(new byte[]{9, 8, 7}));
        compressedRequest.headers().add(HttpHeaderNames.CONTENT_ENCODING, "gzip");
        DefaultFullHttpRequest plainRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri", Unpooled.wrappedBuffer(new byte[]{6}));

        // when
        embeddedChannel.writeInbound(compressedRequest);
        assertThat(PreserveHeadersNettyRemoves.originalRawBody(embeddedChannel), equalTo(new byte[]{9, 8, 7}));
        embeddedChannel.writeInbound(plainRequest);

        // then
        assertThat(PreserveHeadersNettyRemoves.originalRawBody(embeddedChannel), nullValue());
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Collections.emptyList()));
        embeddedChannel.finishAndReleaseAll();
    }

    @Test
    public void shouldNotLeakAbortedContentEncodedRequestToNextRequestOnSameChannel() {
        // given - a compressed request whose body never completes (no LastHttpContent), then a plain one
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new PreserveHeadersNettyRemoves());
        DefaultHttpRequest abortedHead = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri");
        abortedHead.headers().add(HttpHeaderNames.CONTENT_ENCODING, "gzip");

        // when
        embeddedChannel.writeInbound(abortedHead);
        embeddedChannel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(new byte[]{1, 2, 3})));
        embeddedChannel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri", Unpooled.wrappedBuffer(new byte[]{4})));

        // then - the plain request neither inherits the aborted body nor its Content-Encoding
        assertThat(PreserveHeadersNettyRemoves.originalRawBody(embeddedChannel), nullValue());
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Collections.emptyList()));
        embeddedChannel.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepPreservedStateSeparatePerChannel() {
        // given - two connections, each with its own decoder instance
        EmbeddedChannel compressedChannel = new EmbeddedChannel(new PreserveHeadersNettyRemoves());
        EmbeddedChannel plainChannel = new EmbeddedChannel(new PreserveHeadersNettyRemoves());
        DefaultFullHttpRequest compressedRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/uri", Unpooled.wrappedBuffer(new byte[]{5}));
        compressedRequest.headers().add(HttpHeaderNames.CONTENT_ENCODING, "deflate");

        // when
        compressedChannel.writeInbound(compressedRequest);
        plainChannel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/uri"));

        // then
        assertThat(PreserveHeadersNettyRemoves.originalRawBody(compressedChannel), equalTo(new byte[]{5}));
        assertThat(PreserveHeadersNettyRemoves.originalRawBody(plainChannel), nullValue());
        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(plainChannel), equalTo(Collections.emptyList()));
        compressedChannel.finishAndReleaseAll();
        plainChannel.finishAndReleaseAll();
    }

    @Test
    public void shouldReturnEmptyPreservedStateForChannelThatHasSeenNoRequest() {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new PreserveHeadersNettyRemoves());

        assertThat(PreserveHeadersNettyRemoves.preservedHeaders(embeddedChannel), equalTo(Collections.emptyList()));
        assertThat(PreserveHeadersNettyRemoves.originalRawBody(embeddedChannel), nullValue());
    }

}
