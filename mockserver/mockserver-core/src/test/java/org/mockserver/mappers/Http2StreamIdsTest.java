package org.mockserver.mappers;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.model.HttpRequest.request;

/**
 * The stamping contract every direct-write site depends on. See {@link Http2StreamIds} for why a
 * missing <em>or</em> foreign stream id both hang an HTTP/2 client.
 */
public class Http2StreamIdsTest {

    private HttpResponse response() {
        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    }

    @Test
    public void shouldStampStreamIdAsHeader() {
        HttpResponse response = response();

        Http2StreamIds.stamp(response, 5);

        assertThat(response.headers().getInt(Http2StreamIds.STREAM_ID_HEADER), is(5));
    }

    @Test
    public void shouldStripHeaderWhenStreamIdIsNull() {
        // given - a foreign stream id leaked in as an ordinary header (e.g. copied from a proxied
        // upstream HTTP/2 response); writing on it triggers PROTOCOL_ERROR/GOAWAY
        HttpResponse response = response();
        response.headers().add(Http2StreamIds.STREAM_ID_HEADER, 99);

        Http2StreamIds.stamp(response, null);

        assertThat(response.headers().get(Http2StreamIds.STREAM_ID_HEADER), nullValue());
    }

    @Test
    public void shouldReplaceRatherThanAppendExistingHeader() {
        // given - a leaked foreign id; the stamped value must win outright, not be added alongside
        // (a duplicated header would leave the codec picking the wrong one)
        HttpResponse response = response();
        response.headers().add(Http2StreamIds.STREAM_ID_HEADER, 99);

        Http2StreamIds.stamp(response, 7);

        assertThat(response.headers().getAll(Http2StreamIds.STREAM_ID_HEADER).size(), is(1));
        assertThat(response.headers().getInt(Http2StreamIds.STREAM_ID_HEADER), is(7));
    }

    @Test
    public void shouldBeIdempotent() {
        HttpResponse response = response();

        Http2StreamIds.stamp(response, 3);
        Http2StreamIds.stamp(response, 3);

        assertThat(response.headers().getAll(Http2StreamIds.STREAM_ID_HEADER).size(), is(1));
        assertThat(response.headers().getInt(Http2StreamIds.STREAM_ID_HEADER), is(3));
    }

    @Test
    public void shouldStampFromModelRequest() {
        HttpResponse response = response();

        Http2StreamIds.stampFromRequest(response, request("/some_path").withStreamId(11));

        assertThat(response.headers().getInt(Http2StreamIds.STREAM_ID_HEADER), is(11));
    }

    @Test
    public void shouldStampNothingFromHttp1ModelRequest() {
        HttpResponse response = response();

        Http2StreamIds.stampFromRequest(response, request("/some_path"));

        assertThat(response.headers().get(Http2StreamIds.STREAM_ID_HEADER), nullValue());
    }

    @Test
    public void shouldStampFromInboundNettyRequest() {
        HttpResponse response = response();
        io.netty.handler.codec.http.HttpRequest inbound = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.GET, "/some_path");
        inbound.headers().add(Http2StreamIds.STREAM_ID_HEADER, 13);

        Http2StreamIds.stampFromNettyRequest(response, inbound);

        assertThat(response.headers().getInt(Http2StreamIds.STREAM_ID_HEADER), is(13));
    }

    @Test
    public void shouldReadStreamIdFromNettyMessage() {
        HttpResponse response = response();
        response.headers().add(Http2StreamIds.STREAM_ID_HEADER, 21);

        assertThat(Http2StreamIds.streamIdOf(response), is(21));
    }

    @Test
    public void shouldReadNullStreamIdWhenHeaderAbsentOrNotAnInteger() {
        assertThat(Http2StreamIds.streamIdOf(response()), nullValue());
        assertThat(Http2StreamIds.streamIdOf(null), nullValue());

        HttpResponse malformed = response();
        malformed.headers().add(Http2StreamIds.STREAM_ID_HEADER, "not-a-number");
        assertThat(Http2StreamIds.streamIdOf(malformed), nullValue());
    }

    @Test
    public void shouldTolerateNullMessage() {
        // no exception - transport-agnostic callers stamp unconditionally
        Http2StreamIds.stamp(null, 5);
        Http2StreamIds.stampFromRequest(null, request("/some_path").withStreamId(5));
    }
}
