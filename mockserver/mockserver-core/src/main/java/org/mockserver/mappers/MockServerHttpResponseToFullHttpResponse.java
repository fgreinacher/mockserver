package org.mockserver.mappers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http2.HttpConversionUtil;
import org.mockserver.codec.BodyDecoderEncoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static org.apache.commons.lang3.StringUtils.*;

/**
 * @author jamesdbloom
 */
public class MockServerHttpResponseToFullHttpResponse {

    private final MockServerLogger mockServerLogger;
    private final BodyDecoderEncoder bodyDecoderEncoder;

    public MockServerHttpResponseToFullHttpResponse(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
        this.bodyDecoderEncoder = new BodyDecoderEncoder();
    }

    public List<DefaultHttpObject> mapMockServerResponseToNettyResponse(HttpResponse httpResponse) {
        try {
            ConnectionOptions connectionOptions = httpResponse.getConnectionOptions();
            boolean hasTrailers = httpResponse.getTrailerMultimap() != null && !httpResponse.getTrailerMultimap().isEmpty();
            boolean chunkedBySize = connectionOptions != null && connectionOptions.getChunkSize() != null && connectionOptions.getChunkSize() > 0;
            if (hasTrailers && !chunkedBySize) {
                return mapResponseWithTrailers(httpResponse, connectionOptions);
            }
            if (chunkedBySize) {
                List<DefaultHttpObject> httpMessages = new ArrayList<>();
                ByteBuf body = getBody(httpResponse);
                DefaultHttpResponse defaultHttpResponse = new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1,
                    getStatus(httpResponse)
                );
                // body is only read for its length here; release it even if setHeaders throws
                // so the ByteBuf cannot leak on the exception path (refcount stays at one).
                try {
                    setHeaders(httpResponse, defaultHttpResponse, body);
                } finally {
                    body.release();
                }
                HttpUtil.setTransferEncodingChunked(defaultHttpResponse, true);
                if (hasTrailers) {
                    addTrailerHeader(httpResponse, defaultHttpResponse);
                }
                setCookies(httpResponse, defaultHttpResponse);
                httpMessages.add(defaultHttpResponse);

                ByteBuf[] chunks = bodyDecoderEncoder.bodyToByteBuf(httpResponse.getBody(), httpResponse.getFirstHeader(CONTENT_TYPE.toString()), connectionOptions.getChunkSize());
                for (int i = 0; i < chunks.length - 1; i++) {
                    DefaultHttpContent defaultHttpContent = new DefaultHttpContent(chunks[i]);
                    httpMessages.add(defaultHttpContent);
                }
                DefaultLastHttpContent lastContent = new DefaultLastHttpContent(chunks[chunks.length - 1]);
                if (hasTrailers) {
                    setTrailers(httpResponse, lastContent);
                }
                httpMessages.add(lastContent);
                return httpMessages;
            } else {
                ByteBuf body = getBody(httpResponse);
                DefaultFullHttpResponse defaultFullHttpResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    getStatus(httpResponse),
                    body
                );
                // the FullHttpResponse now owns body; release it (via the message) if a
                // subsequent step throws so the ByteBuf cannot leak on the exception path.
                try {
                    setHeaders(httpResponse, defaultFullHttpResponse, body);
                    setCookies(httpResponse, defaultFullHttpResponse);
                } catch (Throwable throwable) {
                    defaultFullHttpResponse.release();
                    throw throwable;
                }
                return Collections.singletonList(defaultFullHttpResponse);
            }
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception encoding response{}")
                    .setArguments(httpResponse)
                    .setThrowable(throwable)
            );
            return Collections.singletonList(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, getStatus(httpResponse)));
        }
    }

    /**
     * Map a response that carries general HTTP trailers (trailing headers).
     * <p>
     * Trailers (RFC 9110 / RFC 7230 &sect;3.3.1) require chunked transfer-encoding on HTTP/1.1:
     * a fixed {@code Content-Length} and {@code Transfer-Encoding: chunked} are mutually
     * exclusive, and Netty's {@code HttpObjectEncoder} only writes the trailing-header block in
     * its chunked state. We therefore <strong>always</strong> emit a {@code DefaultHttpResponse}
     * head with chunked transfer-encoding (removing any {@code Content-Length} and ignoring
     * {@code contentLengthHeaderOverride} for framing purposes), an automatic {@code Trailer}
     * header listing the field names, the body as a single {@code DefaultHttpContent}, and a
     * {@code DefaultLastHttpContent} whose
     * {@link io.netty.handler.codec.http.LastHttpContent#trailingHeaders()} carry the trailers.
     * <p>
     * This is safe for HTTP/2 / HTTP/3 because their adapters strip transfer-encoding and ride
     * the trailers on the trailing HEADERS frame, so this single wiring covers all three
     * protocols. A body-less status (204/304 or HEAD) yields an empty body content -- the
     * trailers still ride on the {@code LastHttpContent}.
     */
    private List<DefaultHttpObject> mapResponseWithTrailers(HttpResponse httpResponse, ConnectionOptions connectionOptions) {
        List<DefaultHttpObject> httpMessages = new ArrayList<>();
        ByteBuf body = getBody(httpResponse);
        boolean bodyTransferred = false;
        try {
            DefaultHttpResponse defaultHttpResponse = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1,
                getStatus(httpResponse)
            );
            setHeaders(httpResponse, defaultHttpResponse, body);
            // Trailers REQUIRE chunked transfer-encoding on HTTP/1.1 (RFC 7230 section 3.3.1 --
            // Content-Length and chunked are mutually exclusive, and Netty's HttpObjectEncoder
            // only writes the trailing-header block while in its chunked state). We therefore
            // always force chunked and drop any Content-Length (and ignore
            // contentLengthHeaderOverride for framing). HTTP/2 / HTTP/3 adapters strip
            // transfer-encoding and ride the trailers on the trailing HEADERS frame, so this is
            // safe across all three protocols.
            defaultHttpResponse.headers().remove(CONTENT_LENGTH);
            HttpUtil.setTransferEncodingChunked(defaultHttpResponse, true);
            addTrailerHeader(httpResponse, defaultHttpResponse);
            setCookies(httpResponse, defaultHttpResponse);
            httpMessages.add(defaultHttpResponse);

            // body content (may be empty for body-less responses)
            if (body.readableBytes() > 0) {
                httpMessages.add(new DefaultHttpContent(body));
                bodyTransferred = true;
            }

            DefaultLastHttpContent lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER);
            setTrailers(httpResponse, lastContent);
            httpMessages.add(lastContent);
            return httpMessages;
        } finally {
            // Keep the body refcount at exactly one across all branches: release it here unless
            // it was handed to a DefaultHttpContent (which now owns it). On an exception between
            // allocation and transfer this also releases it, preventing a ByteBuf leak.
            if (!bodyTransferred) {
                body.release();
            }
        }
    }

    /**
     * Add (or extend) the {@code Trailer} response header that announces the trailer field
     * names, as required by RFC 9110 section 6.5.1. Does not overwrite an existing user-set
     * {@code Trailer} header.
     */
    private void addTrailerHeader(HttpResponse httpResponse, DefaultHttpResponse response) {
        if (httpResponse.getTrailerMultimap() == null) {
            return;
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        httpResponse.getTrailerMultimap().keySet().forEach(name -> names.add(sanitizeHeaderValue(name.getValue())));
        if (names.isEmpty()) {
            return;
        }
        if (!response.headers().contains(TRAILER)) {
            response.headers().set(TRAILER, join(names, ", "));
        }
    }

    /**
     * Copy the response trailers onto the {@link LastHttpContent} trailing headers.
     */
    private void setTrailers(HttpResponse httpResponse, LastHttpContent lastHttpContent) {
        if (httpResponse.getTrailerMultimap() != null) {
            httpResponse
                .getTrailerMultimap()
                .entries()
                .forEach(entry -> {
                    // Skip a null trailer value for the same reason as setHeaders: Netty's encoder
                    // rejects a null value with a NullPointerException, which would fail the
                    // response (issue #2358).
                    String value = sanitizeHeaderValue(entry.getValue().getValue());
                    if (value != null) {
                        lastHttpContent
                            .trailingHeaders()
                            .add(sanitizeHeaderValue(entry.getKey().getValue()), value);
                    }
                });
        }
    }

    private HttpResponseStatus getStatus(HttpResponse httpResponse) {
        int statusCode = httpResponse.getStatusCode() != null ? httpResponse.getStatusCode() : 200;
        if (!isEmpty(httpResponse.getReasonPhrase())) {
            return new HttpResponseStatus(statusCode, sanitizeHeaderValue(httpResponse.getReasonPhrase()));
        } else {
            return HttpResponseStatus.valueOf(statusCode);
        }
    }

    private ByteBuf getBody(HttpResponse httpResponse) {
        // Return the response body bytes verbatim. Unlike the request/forward path (which must
        // re-compress because the inbound HttpContentDecompressor decompressed a forwarded request
        // body while PreserveHeadersNettyRemoves kept its Content-Encoding header), a mock response
        // body must be written to the wire exactly as supplied to withBody(...). The Content-Encoding
        // header is still emitted literally by setHeaders, so users have byte-level control over the
        // wire body and an already-compressed body is never double-compressed (issue #2375).
        return bodyDecoderEncoder.bodyToByteBuf(httpResponse.getBody(), httpResponse.getFirstHeader(CONTENT_TYPE.toString()));
    }

    private void setHeaders(HttpResponse httpResponse, DefaultHttpResponse response, ByteBuf body) {
        if (httpResponse.getHeaderMultimap() != null) {
            httpResponse
                .getHeaderMultimap()
                .entries()
                .forEach(entry -> {
                    // Netty's header encoder rejects a null value with a NullPointerException,
                    // which would fail the whole response. A header with no value carries no
                    // information, so skip it rather than crash the encode (issue #2358).
                    String value = sanitizeHeaderValue(entry.getValue().getValue());
                    if (value != null) {
                        response
                            .headers()
                            .add(sanitizeHeaderValue(entry.getKey().getValue()), value);
                    }
                });
        }

        // Content-Type
        if (isBlank(httpResponse.getFirstHeader(CONTENT_TYPE.toString()))) {
            if (httpResponse.getBody() != null
                && httpResponse.getBody().getContentType() != null) {
                response.headers().set(CONTENT_TYPE, sanitizeHeaderValue(httpResponse.getBody().getContentType()));
            }
        }

        // Content-Length
        ConnectionOptions connectionOptions = httpResponse.getConnectionOptions();
        if (isBlank(httpResponse.getFirstHeader(CONTENT_LENGTH.toString()))) {
            boolean overrideContentLength = connectionOptions != null && connectionOptions.getContentLengthHeaderOverride() != null;
            boolean addContentLength = connectionOptions == null || !Boolean.TRUE.equals(connectionOptions.getSuppressContentLengthHeader());
            boolean chunkedEncoding = (connectionOptions != null && connectionOptions.getChunkSize() != null) || response.headers().contains(HttpHeaderNames.TRANSFER_ENCODING);
            if (overrideContentLength) {
                response.headers().set(CONTENT_LENGTH, connectionOptions.getContentLengthHeaderOverride());
            } else if (addContentLength && !chunkedEncoding) {
                response.headers().set(CONTENT_LENGTH, body.readableBytes());
            }
            if (chunkedEncoding) {
                response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            }
        }

        // HTTP2 extension headers
        // Belt-and-braces: strip any x-http2-stream-id that may have leaked in as an ordinary model
        // header (e.g. from a forwarded/proxied HTTP/2 response) BEFORE deriving the stream id from
        // the protocol-guarded field. The field is the single source of truth for the outbound stream
        // id; a leaked header carrying a foreign (upstream) stream id would otherwise make the server
        // HTTP/2 codec write on the wrong stream, triggering a PROTOCOL_ERROR / GOAWAY that hangs an
        // HTTP/2 client.
        response.headers().remove(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
        Integer streamId = httpResponse.getStreamId();
        if (streamId != null) {
            response.headers().add(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), streamId);
        }
    }

    private void setCookies(HttpResponse httpResponse, DefaultHttpResponse response) {
        if (httpResponse.getCookieMap() != null) {
            for (Map.Entry<NottableString, NottableString> cookie : httpResponse.getCookieMap().entrySet()) {
                String cookieValue = cookie.getValue().toString();
                if (httpResponse.cookieHeaderDoesNotAlreadyExists(cookie.getKey().getValue(), cookieValue)) {
                    response.headers().add(SET_COOKIE, io.netty.handler.codec.http.cookie.ServerCookieEncoder.LAX.encode(new DefaultCookie(cookie.getKey().getValue(), cookieValue)));
                }
            }
        }
    }

    private static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r", "").replace("\n", "");
    }
}
