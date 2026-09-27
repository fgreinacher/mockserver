package org.mockserver.mappers;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpObject;
import io.netty.handler.codec.http.HttpResponseEncoder;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpResponse.response;

/**
 * Wire-level Content-Type behaviour for {@link MockServerHttpResponseToFullHttpResponse}. The mapper
 * resolves the Content-Type header once and reuses it for both body charset resolution and the
 * emitted header; these tests pin that both consumers see the same value, so a broken dedup (a wrong
 * or dropped Content-Type into either consumer) turns them red.
 */
public class MockServerHttpResponseToFullHttpResponseContentTypeTest {

    private final MockServerHttpResponseToFullHttpResponse mapper =
        new MockServerHttpResponseToFullHttpResponse(new MockServerLogger());

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

    @Test
    public void explicitContentTypeHeaderWinsOverBodyDeclaredContentType() {
        // Header says application/json; the body separately declares text/plain. The mapper resolves
        // the header once and, seeing it non-blank, must NOT overwrite it with the body's content
        // type. If the resolved value is lost, setHeaders replaces application/json with text/plain.
        String wire = encodeToWire(response().withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("hello", MediaType.PLAIN_TEXT_UTF_8));
        assertThat(wire.toLowerCase(), containsString("content-type: application/json"));
        assertThat("the body's text/plain must not leak onto the wire",
            wire.toLowerCase().contains("content-type: text/plain"), is(false));
        assertThat("content-type must be emitted exactly once",
            wire.toLowerCase().split("content-type", -1).length - 1, is(1));
    }

    @Test
    public void bodyDeclaredContentTypeIsEmittedWhenNoHeaderPresent() {
        String wire = encodeToWire(response().withStatusCode(200).withBody("{}", MediaType.APPLICATION_JSON));
        assertThat(wire.toLowerCase(), containsString("content-type: application/json"));
    }

    @Test
    public void nonAsciiBodyEncodedUsingContentTypeCharset() {
        // The GBP sign is one byte in ISO-8859-1 (0xA3) and two in UTF-8. The Content-Type header
        // charset must drive the encoding, so Content-Length is 1 and the single 0xA3 byte appears.
        String wire = encodeToWire(response()
            .withStatusCode(200)
            .withHeader("content-type", "text/plain; charset=ISO-8859-1")
            .withBody("£", StandardCharsets.ISO_8859_1));
        assertThat(wire.toLowerCase(), containsString("content-length: 1"));
        assertThat(wire, containsString("£"));
    }
}
