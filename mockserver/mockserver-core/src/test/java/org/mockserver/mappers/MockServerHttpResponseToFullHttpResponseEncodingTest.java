package org.mockserver.mappers;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpObject;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.util.ReferenceCounted;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpResponse.response;

/**
 * Wire-level tests for {@link MockServerHttpResponseToFullHttpResponse}: these push the mapped
 * objects through a real {@link HttpResponseEncoder} and assert on the <strong>bytes actually
 * written to the socket</strong>.
 * <p>
 * This class exists because the in-memory assertions in
 * {@code MockServerHttpResponseToFullHttpResponseTest} cannot see framing defects. Netty's
 * {@code HttpObjectEncoder} decides framing from the status code: for a body-less status
 * ({@code 1xx}, {@code 204}, {@code 205}, {@code 304}) it enters {@code ST_CONTENT_ALWAYS_EMPTY}
 * and writes a bare empty buffer, so the trailing-header block -- which is only written from the
 * {@code ST_CONTENT_CHUNK} branch -- never reaches the wire no matter what the in-memory
 * {@code LastHttpContent} carries.
 * <p>
 * Worse, {@code HttpResponseEncoder.sanitizeHeadersBeforeEncode} removes a
 * {@code Transfer-Encoding} header for {@code 1xx}, {@code 204} and {@code 205} but
 * <strong>not</strong> for {@code 304} (verified against Netty 4.2.16). A {@code 304} that
 * advertises {@code Transfer-Encoding: chunked} therefore goes out with no terminating
 * {@code 0\r\n\r\n} chunk -- a framing violation that leaves the peer waiting for a chunk that
 * never arrives and can wedge a keep-alive connection.
 */
public class MockServerHttpResponseToFullHttpResponseEncodingTest {

    private final MockServerHttpResponseToFullHttpResponse mapper =
        new MockServerHttpResponseToFullHttpResponse(new MockServerLogger());

    /**
     * Encode a mapped response through a real {@link HttpResponseEncoder} and return the exact
     * bytes that would be written to the socket.
     */
    private String encodeToWire(HttpResponse httpResponse) {
        List<DefaultHttpObject> mapped = mapper.mapMockServerResponseToNettyResponse(httpResponse);
        EmbeddedChannel channel = new EmbeddedChannel(new HttpResponseEncoder());
        StringBuilder wire = new StringBuilder();
        try {
            for (DefaultHttpObject object : mapped) {
                channel.writeOutbound(object);
            }
            channel.finish();
            ByteBuf outbound;
            while ((outbound = channel.readOutbound()) != null) {
                try {
                    wire.append(outbound.toString(StandardCharsets.ISO_8859_1));
                } finally {
                    outbound.release();
                }
            }
        } finally {
            channel.releaseOutbound();
            channel.close();
        }
        return wire.toString();
    }

    /**
     * A {@code 304} carrying trailers must not advertise chunked transfer-encoding, because
     * Netty will never emit the chunked body or its terminating chunk for a body-less status.
     * Emitting the announcement without the terminator is a framing violation.
     */
    @Test
    public void shouldNotEmitChunkedFramingForNotModifiedWithTrailers() {
        // given -- a body-less 304 that also carries trailers
        HttpResponse httpResponse = response()
            .withStatusCode(304)
            .withTrailer("x-checksum", "abc123");

        // when
        String wire = encodeToWire(httpResponse);

        // then -- the response must be well framed: no chunked announcement at all, because a
        // 304 can carry no chunked body and therefore no terminating chunk either
        assertThat("304 must not advertise chunked transfer-encoding (Netty's encoder will not "
                + "emit a body or a terminating chunk for a body-less status, so the framing "
                + "would be truncated): " + wire,
            wire.toLowerCase(), not(containsString("transfer-encoding: chunked")));
    }

    /**
     * Guards the framing invariant directly: if a response ever does advertise chunked, the
     * terminating {@code 0\r\n\r\n} chunk must be present on the wire.
     * <p>
     * NOTE: the assertion is inside a conditional, so on a green run this test asserts nothing --
     * no body-less status advertises chunked once the mapper is correct. That is intentional and
     * it is NOT dead weight: it is the invariant, stated independently of which statuses the
     * mapper currently forces to chunked, and it DOES fire against the unfixed mapper (verified --
     * it fails on 304 with "advertised chunked transfer-encoding but emitted no terminating
     * chunk"). It therefore catches any future change that reintroduces chunked framing on a
     * status Netty will not terminate, including statuses not enumerated here. Please do not
     * delete it as vacuous.
     */
    @Test
    public void shouldNeverAdvertiseChunkedWithoutTerminatingChunkForBodylessStatuses() {
        for (int statusCode : new int[]{204, 205, 304}) {
            // given
            HttpResponse httpResponse = response()
                .withStatusCode(statusCode)
                .withTrailer("x-checksum", "abc123");

            // when
            String wire = encodeToWire(httpResponse);

            // then
            if (wire.toLowerCase().contains("transfer-encoding: chunked")) {
                assertThat("status " + statusCode + " advertised chunked transfer-encoding but "
                        + "emitted no terminating chunk, which truncates the message: " + wire,
                    wire, endsWith("0\r\n\r\n"));
            }
        }
    }

    /**
     * The trailers themselves are unsendable on HTTP/1.1 for a body-less status. Since they
     * cannot be delivered, the misleading {@code Trailer} announcement header must not be sent
     * either -- announcing trailer fields that never arrive is what the peer would wait for.
     */
    @Test
    public void shouldNotAnnounceTrailersThatCannotBeSentForBodylessStatus() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(304)
            .withTrailer("x-checksum", "abc123");

        // when
        String wire = encodeToWire(httpResponse);

        // then -- no Trailer announcement, and no trailer field on the wire
        assertThat("304 must not announce a Trailer header for trailers it cannot send: " + wire,
            wire.toLowerCase(), not(containsString("trailer:")));
        assertThat("the trailer field must not appear on the wire for a body-less status: " + wire,
            wire.toLowerCase(), not(containsString("x-checksum")));
    }

    /**
     * The framing of a body-less response must not depend on whether trailers happen to be
     * attached. Adding a trailer is a request to send extra metadata; it must never change how
     * the message itself is delimited.
     */
    @Test
    public void shouldFrameBodylessStatusIdenticallyWithAndWithoutTrailers() {
        for (int statusCode : new int[]{204, 205, 304}) {
            // given
            String withoutTrailers = encodeToWire(response().withStatusCode(statusCode));
            String withTrailers = encodeToWire(response()
                .withStatusCode(statusCode)
                .withTrailer("x-checksum", "abc123"));

            // then
            assertThat("attaching a trailer changed the framing of a " + statusCode,
                withTrailers, equalTo(withoutTrailers));
        }
    }

    /**
     * The regression guard for the normal path: a {@code 200} with trailers must still be
     * chunked, must carry the {@code Trailer} announcement, and must deliver the trailing-header
     * block followed by the terminating chunk.
     */
    @Test
    public void shouldEmitChunkedBodyAndTrailingHeadersForResponseWithBody() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("body")
            .withTrailer("x-checksum", "abc123");

        // when
        String wire = encodeToWire(httpResponse);

        // then
        assertThat(wire.toLowerCase(), containsString("transfer-encoding: chunked"));
        assertThat("the Trailer announcement header must be present: " + wire,
            wire.toLowerCase(), containsString("trailer: x-checksum"));
        assertThat("the body must be sent as a chunk: " + wire, wire, containsString("body"));
        assertThat("the trailing-header block must reach the wire: " + wire,
            wire, containsString("x-checksum: abc123"));
        assertThat("the message must be terminated: " + wire, wire, endsWith("\r\n\r\n"));
    }
}
