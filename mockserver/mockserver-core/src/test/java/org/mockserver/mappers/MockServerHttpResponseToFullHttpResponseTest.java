package org.mockserver.mappers;

import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCounted;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.StringBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class MockServerHttpResponseToFullHttpResponseTest {

    private final MockServerHttpResponseToFullHttpResponse mapper =
        new MockServerHttpResponseToFullHttpResponse(new MockServerLogger());

    // --- status code ---

    @Test
    public void shouldMapStatusCode200() {
        // given
        HttpResponse httpResponse = response().withStatusCode(200);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        assertThat(result, hasSize(1));
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.status().code(), equalTo(200));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapStatusCode404() {
        // given
        HttpResponse httpResponse = response().withStatusCode(404);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.status().code(), equalTo(404));
            assertThat(fullResponse.status().reasonPhrase(), equalTo("Not Found"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapCustomReasonPhrase() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withReasonPhrase("Custom Reason");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.status().reasonPhrase(), equalTo("Custom Reason"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldDefaultToStatus200WhenNull() {
        // given
        HttpResponse httpResponse = response();

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.status().code(), equalTo(200));
        } finally {
            fullResponse.release();
        }
    }

    // --- headers ---

    @Test
    public void shouldMapHeaders() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeaders(
                new Header("headerName1", "headerValue1"),
                new Header("headerName2", "headerValue2")
            );

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get("headerName1"), equalTo("headerValue1"));
            assertThat(fullResponse.headers().get("headerName2"), equalTo("headerValue2"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapMultipleHeaderValues() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeader("headerName1", "value1", "value2");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().getAll("headerName1"), hasSize(2));
            assertThat(fullResponse.headers().getAll("headerName1"), contains("value1", "value2"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldNotFailEncodingWhenHeaderValueIsNull() {
        // given - a response carrying a header whose value is null (issue #2358: the dashboard
        // served favicon.svg with a Content-Type built from an unmapped MIME lookup that returned
        // null, which crashed Netty's header encoder with "NullPointerException: value")
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeader("Content-Type", (String) null)
            .withHeader("headerName", "headerValue")
            .withBody("some body");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then - encoding succeeds (no NPE) and the response is genuinely usable
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.status().code(), equalTo(200));
            // the null-valued header is omitted rather than written to the wire
            assertThat(fullResponse.headers().contains("Content-Type"), is(false));
            // other headers are unaffected
            assertThat(fullResponse.headers().get("headerName"), equalTo("headerValue"));
        } finally {
            fullResponse.release();
        }
    }

    // --- body ---

    @Test
    public void shouldMapTextBody() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("some body content");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            byte[] bodyBytes = new byte[fullResponse.content().readableBytes()];
            fullResponse.content().readBytes(bodyBytes);
            assertThat(new String(bodyBytes, StandardCharsets.UTF_8), equalTo("some body content"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapBinaryBody() {
        // given
        byte[] binaryData = new byte[]{0x10, 0x20, 0x30};
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody(binaryData);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            byte[] bodyBytes = new byte[fullResponse.content().readableBytes()];
            fullResponse.content().readBytes(bodyBytes);
            assertThat(bodyBytes, equalTo(binaryData));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapEmptyBody() {
        // given
        HttpResponse httpResponse = response().withStatusCode(200);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.content().readableBytes(), equalTo(0));
        } finally {
            fullResponse.release();
        }
    }

    // --- content length ---

    @Test
    public void shouldSetContentLengthForBody() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("12345");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH), equalTo("5"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldOverrideContentLengthFromConnectionOptions() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("12345")
            .withConnectionOptions(
                new ConnectionOptions()
                    .withContentLengthHeaderOverride(999)
            );

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH), equalTo("999"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldSuppressContentLengthHeader() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("12345")
            .withConnectionOptions(
                new ConnectionOptions()
                    .withSuppressContentLengthHeader(true)
            );

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH), is(nullValue()));
        } finally {
            fullResponse.release();
        }
    }

    // --- cookies ---

    @Test
    public void shouldMapCookies() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withCookie("cookieName1", "cookieValue1")
            .withCookie("cookieName2", "cookieValue2");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            List<String> setCookieHeaders = fullResponse.headers().getAll(HttpHeaderNames.SET_COOKIE);
            assertThat(setCookieHeaders, hasSize(2));
            assertThat(setCookieHeaders.toString(), containsString("cookieName1=cookieValue1"));
            assertThat(setCookieHeaders.toString(), containsString("cookieName2=cookieValue2"));
        } finally {
            fullResponse.release();
        }
    }

    // --- content type from body ---

    @Test
    public void shouldSetContentTypeFromBody() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody(new StringBody("json content", null, false, MediaType.APPLICATION_JSON));

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_TYPE), equalTo("application/json"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldNotOverrideExplicitContentTypeHeader() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeader("Content-Type", "text/html")
            .withBody(new StringBody("some content", null, false, MediaType.APPLICATION_JSON));

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_TYPE), equalTo("text/html"));
        } finally {
            fullResponse.release();
        }
    }

    // --- chunked transfer encoding ---

    @Test
    public void shouldMapChunkedResponse() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("some chunked body content")
            .withConnectionOptions(
                new ConnectionOptions()
                    .withChunkSize(5)
            );

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        assertThat(result.size(), greaterThan(1));
        DefaultHttpResponse firstPart = (DefaultHttpResponse) result.get(0);
        assertThat(firstPart.status().code(), equalTo(200));
        assertThat(firstPart.headers().get(HttpHeaderNames.TRANSFER_ENCODING), equalTo("chunked"));

        // release every reference-counted element. The first element is a DefaultHttpResponse
        // (header object), the remainder are DefaultHttpContent chunks (last one is
        // DefaultLastHttpContent). Guarding only on DefaultHttpContent would leak the header.
        for (DefaultHttpObject httpObject : result) {
            if (httpObject instanceof ReferenceCounted) {
                ((ReferenceCounted) httpObject).release();
            }
        }
    }

    // --- stream id for HTTP/2 ---

    @Test
    public void shouldSetStreamIdForHTTP2() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("body")
            .withStreamId(5);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().getInt("x-http2-stream-id"), equalTo(5));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldNotEmitLeakedStreamIdHeaderWhenNoStreamIdField() {
        // given - a model response that somehow still carries x-http2-stream-id as an ordinary header
        // (e.g. a forwarded HTTP/2 upstream response whose synthetic header leaked into the model). With
        // no protocol-guarded stream-id field set, the leaked header must NOT be emitted to the wire -- a
        // foreign stream id would make the server HTTP/2 codec write on the wrong stream (PROTOCOL_ERROR).
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeader("x-http2-stream-id", "3")
            .withBody("body");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().contains("x-http2-stream-id"), is(false));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldLetStreamIdFieldGovernOverLeakedStreamIdHeader() {
        // given - both a leaked x-http2-stream-id header (foreign/upstream id 3) and the real
        // protocol-guarded stream-id field (5). The field is the single source of truth: exactly one
        // x-http2-stream-id must be emitted, and it must be the field value, never the leaked header.
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeader("x-http2-stream-id", "3")
            .withBody("body")
            .withStreamId(5);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().getAll("x-http2-stream-id"), contains("5"));
        } finally {
            fullResponse.release();
        }
    }

    // --- trailers ---

    @Test
    public void shouldNotChangeShapeWhenNoTrailers() {
        // given
        HttpResponse httpResponse = response().withStatusCode(200).withBody("body");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then -- regression: still a single FullHttpResponse, no Trailer header, fixed length
        assertThat(result, hasSize(1));
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().contains(HttpHeaderNames.TRAILER), is(false));
            assertThat(fullResponse.headers().contains(HttpHeaderNames.TRANSFER_ENCODING), is(false));
            assertThat(fullResponse.headers().getInt(HttpHeaderNames.CONTENT_LENGTH.toString()), equalTo(4));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldEmitChunkedResponseWithTrailingHeaders() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("body")
            .withTrailer("x-checksum", "abc123")
            .withTrailer("x-signature", "deadbeef");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then -- head (chunked + Trailer header) + content + LastHttpContent with trailers
        try {
            assertThat(result, hasSize(3));

            DefaultHttpResponse head = (DefaultHttpResponse) result.get(0);
            assertThat(head, not(instanceOf(DefaultFullHttpResponse.class)));
            assertThat(head.headers().get(HttpHeaderNames.TRANSFER_ENCODING), equalTo("chunked"));
            assertThat(head.headers().contains(HttpHeaderNames.CONTENT_LENGTH), is(false));
            String trailerHeader = head.headers().get(HttpHeaderNames.TRAILER);
            assertThat(trailerHeader, containsString("x-checksum"));
            assertThat(trailerHeader, containsString("x-signature"));

            assertThat(result.get(1), instanceOf(DefaultHttpContent.class));

            LastHttpContent last = (LastHttpContent) result.get(2);
            assertThat(last.trailingHeaders().get("x-checksum"), equalTo("abc123"));
            assertThat(last.trailingHeaders().get("x-signature"), equalTo("deadbeef"));
        } finally {
            for (DefaultHttpObject object : result) {
                if (object instanceof ReferenceCounted) {
                    ((ReferenceCounted) object).release();
                }
            }
        }
    }

    @Test
    public void shouldNotFailEncodingWhenTrailerValueIsNull() {
        // given - a response with a trailer whose value is null (same null-header hazard as
        // issue #2358, on the trailing-header path)
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("body")
            .withTrailer("x-null", (String) null)
            .withTrailer("x-checksum", "abc123");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then - encoding succeeds (no NPE); the null-valued trailer is omitted, others survive
        try {
            assertThat(result, hasSize(3));
            LastHttpContent last = (LastHttpContent) result.get(2);
            assertThat(last.trailingHeaders().contains("x-null"), is(false));
            assertThat(last.trailingHeaders().get("x-checksum"), equalTo("abc123"));
        } finally {
            for (DefaultHttpObject object : result) {
                if (object instanceof ReferenceCounted) {
                    ((ReferenceCounted) object).release();
                }
            }
        }
    }

    /**
     * A body-less status keeps its trailers on the {@code LastHttpContent} -- HTTP/2 and HTTP/3
     * can carry them on a trailing HEADERS frame -- but must NOT be forced to chunked and must
     * NOT advertise a {@code Trailer} header. Netty's encoder emits no chunked body, no
     * terminating chunk and no trailing-header block for these statuses, so on HTTP/1.1 the
     * announcement could never be honoured, and for {@code 304} the chunked announcement is not
     * even stripped, leaving a truncated message on the wire. The framing itself is asserted at
     * byte level in {@link MockServerHttpResponseToFullHttpResponseEncodingTest}.
     */
    @Test
    public void shouldEmitTrailersForBodylessResponse() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(204)
            .withTrailer("x-checksum", "abc123");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then -- head + empty LastHttpContent carrying the trailers (no content frame)
        try {
            assertThat(result, hasSize(2));
            DefaultHttpResponse head = (DefaultHttpResponse) result.get(0);
            assertThat("a body-less status must not advertise trailers it can never send on HTTP/1.1",
                head.headers().get(HttpHeaderNames.TRAILER), nullValue());
            assertThat("a body-less status must not be forced to chunked -- Netty emits no "
                    + "terminating chunk for it",
                head.headers().get(HttpHeaderNames.TRANSFER_ENCODING), nullValue());
            LastHttpContent last = (LastHttpContent) result.get(1);
            assertThat("trailers stay on the LastHttpContent so HTTP/2 / HTTP/3 can still deliver them",
                last.trailingHeaders().get("x-checksum"), equalTo("abc123"));
        } finally {
            for (DefaultHttpObject object : result) {
                if (object instanceof ReferenceCounted) {
                    ((ReferenceCounted) object).release();
                }
            }
        }
    }

    @Test
    public void shouldForceChunkedAndDropContentLengthWhenTrailersWithExplicitContentLength() {
        // given -- an explicit Content-Length header alongside trailers (COR-10): trailers and a
        // fixed Content-Length are mutually exclusive on HTTP/1.1, and Netty only writes the
        // trailing-header block in its chunked state, so Content-Length must be dropped and the
        // response forced to chunked or the trailers would silently vanish on the wire.
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeader("content-length", "4")
            .withBody("body")
            .withTrailer("x-checksum", "abc123");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then -- chunked head with NO Content-Length, and the trailers on the LastHttpContent
        try {
            assertThat(result, hasSize(3));

            DefaultHttpResponse head = (DefaultHttpResponse) result.get(0);
            assertThat(head, not(instanceOf(DefaultFullHttpResponse.class)));
            assertThat(head.headers().get(HttpHeaderNames.TRANSFER_ENCODING), equalTo("chunked"));
            assertThat(head.headers().contains(HttpHeaderNames.CONTENT_LENGTH), is(false));
            assertThat(head.headers().get(HttpHeaderNames.TRAILER), containsString("x-checksum"));

            assertThat(result.get(1), instanceOf(DefaultHttpContent.class));

            LastHttpContent last = (LastHttpContent) result.get(2);
            assertThat(last.trailingHeaders().get("x-checksum"), equalTo("abc123"));
        } finally {
            for (DefaultHttpObject object : result) {
                if (object instanceof ReferenceCounted) {
                    ((ReferenceCounted) object).release();
                }
            }
        }
    }

    @Test
    public void shouldForceChunkedAndDropContentLengthOverrideWhenTrailers() {
        // given -- contentLengthHeaderOverride must likewise be ignored for framing (COR-10)
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("body")
            .withTrailer("x-checksum", "abc123")
            .withConnectionOptions(new ConnectionOptions().withContentLengthHeaderOverride(99));

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then -- chunked, no Content-Length, trailers preserved
        try {
            assertThat(result, hasSize(3));
            DefaultHttpResponse head = (DefaultHttpResponse) result.get(0);
            assertThat(head.headers().get(HttpHeaderNames.TRANSFER_ENCODING), equalTo("chunked"));
            assertThat(head.headers().contains(HttpHeaderNames.CONTENT_LENGTH), is(false));
            LastHttpContent last = (LastHttpContent) result.get(result.size() - 1);
            assertThat(last.trailingHeaders().get("x-checksum"), equalTo("abc123"));
        } finally {
            for (DefaultHttpObject object : result) {
                if (object instanceof ReferenceCounted) {
                    ((ReferenceCounted) object).release();
                }
            }
        }
    }
}
