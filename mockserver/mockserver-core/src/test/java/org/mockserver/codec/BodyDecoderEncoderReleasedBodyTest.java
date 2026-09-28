package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpObject;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.model.Body;
import org.mockserver.model.BodyWithContentType;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.JsonBody;
import org.mockserver.model.MediaType;
import org.mockserver.model.StringBody;
import org.mockserver.model.XmlBody;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.matchers.MatchType.ONLY_MATCHING_FIELDS;
import static org.mockserver.model.HttpResponse.response;

/**
 * A response is logged before it is written, so the event log may already have retained its entry and
 * released the body's decoded String when the writer encodes it. The encode must produce the same bytes
 * as it does from a body with the String cached, and must not re-attach the String to the retained body.
 */
@SuppressWarnings("rawtypes")
public class BodyDecoderEncoderReleasedBodyTest {

    private static final String TEXT = "café şarəs {\"k\":\"v\"} <a>b</a> €";
    private static final List<String> CONTENT_TYPE_HEADERS = Arrays.asList(
        null, "text/plain", "text/plain; charset=utf-8", "text/plain; charset=iso-8859-1", "application/json", "application/xml; charset=utf-16"
    );

    private static List<Supplier<BodyWithContentType>> bodies() {
        List<Supplier<BodyWithContentType>> bodies = new ArrayList<>();
        for (Charset charset : Arrays.asList(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.UTF_16)) {
            byte[] raw = TEXT.getBytes(charset);
            // as decoded from the wire: value and raw bytes, with and without a declared charset
            bodies.add(() -> new StringBody(new String(raw, charset), raw, false, MediaType.TEXT_PLAIN.withCharset(charset)));
            bodies.add(() -> new JsonBody(new String(raw, charset), raw, MediaType.APPLICATION_JSON.withCharset(charset), ONLY_MATCHING_FIELDS));
            bodies.add(() -> new XmlBody(new String(raw, charset), raw, MediaType.APPLICATION_XML.withCharset(charset)));
        }
        bodies.add(() -> new StringBody(TEXT));
        bodies.add(() -> new JsonBody(TEXT));
        bodies.add(() -> new XmlBody(TEXT));
        return bodies;
    }

    @Test
    public void shouldEncodeReleasedBodyToTheSameBytesWithoutReCachingIt() {
        BodyDecoderEncoder encoder = new BodyDecoderEncoder();
        for (Supplier<BodyWithContentType> supplier : bodies()) {
            for (String contentTypeHeader : CONTENT_TYPE_HEADERS) {
                BodyWithContentType cached = supplier.get();
                cached.getValue();
                byte[] expected = encoder.bodyToBytes(cached, contentTypeHeader);

                BodyWithContentType released = supplier.get();
                released.releaseDerivedForms();
                // a body whose String differs from its raw bytes is never released, so compare with the release
                long retainedAfterRelease = released.retainedDerivedFormBytes();
                byte[] actual = encoder.bodyToBytes(released, contentTypeHeader);

                String description = released.getClass().getSimpleName() + " " + released.getContentType() + " / " + contentTypeHeader;
                assertThat(description, actual, is(expected));
                assertThat(description, released.retainedDerivedFormBytes(), is(retainedAfterRelease));
            }
        }
    }

    @Test
    public void shouldHaveReleasedTheLosslessBodies() {
        long released = bodies().stream().map(Supplier::get).filter(body -> {
            body.getValue();
            body.releaseDerivedForms();
            return body.retainedDerivedFormBytes() == 0L;
        }).count();
        assertThat(released >= 9, is(true));
    }

    @Test
    public void shouldWriteReleasedResponseWithoutReCachingItsBody() {
        MockServerHttpResponseToFullHttpResponse mapper = new MockServerHttpResponseToFullHttpResponse(new MockServerLogger());
        for (Supplier<BodyWithContentType> supplier : bodies()) {
            HttpResponse cachedResponse = response().withStatusCode(200).withBody(supplier.get());
            cachedResponse.getBody().getValue();
            byte[] expected = contentOf(mapper.mapMockServerResponseToNettyResponse(cachedResponse));

            HttpResponse releasedResponse = response().withStatusCode(200).withBody(supplier.get());
            Body body = releasedResponse.getBody();
            body.releaseDerivedForms();
            long retainedAfterRelease = body.retainedDerivedFormBytes();
            byte[] actual = contentOf(mapper.mapMockServerResponseToNettyResponse(releasedResponse));

            String description = body.getClass().getSimpleName() + " " + body.getContentType();
            assertThat(description, actual, is(expected));
            assertThat(description, body.retainedDerivedFormBytes(), is(retainedAfterRelease));
        }
    }

    private static byte[] contentOf(List<DefaultHttpObject> httpObjects) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (DefaultHttpObject httpObject : httpObjects) {
            ByteBuf content = null;
            if (httpObject instanceof FullHttpResponse) {
                content = ((FullHttpResponse) httpObject).content();
            } else if (httpObject instanceof DefaultHttpContent) {
                content = ((DefaultHttpContent) httpObject).content();
            }
            if (content != null) {
                byte[] bytes = new byte[content.readableBytes()];
                content.getBytes(content.readerIndex(), bytes);
                out.write(bytes, 0, bytes.length);
            }
            ReferenceCountUtil.release(httpObject);
        }
        return out.toByteArray();
    }
}
