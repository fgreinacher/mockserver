package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.model.*;

import java.util.Arrays;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.charset.StandardCharsets.UTF_16;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.MediaType.DEFAULT_JSON_HTTP_CHARACTER_SET;
import static org.mockserver.model.MediaType.DEFAULT_TEXT_HTTP_CHARACTER_SET;
import static org.mockserver.model.StringBody.exact;

@SuppressWarnings("rawtypes")
public class BodyDecoderEncoderTest {

    @Test
    public void shouldSerialiseBodyToByteBufWithNoContentType() {
        // given
        Body body = new StringBody("şarəs");

        // when
        ByteBuf result = new BodyDecoderEncoder().bodyToByteBuf(body, null);

        // then
        byte[] bodyBytes = new byte[result.readableBytes()];
        result.readBytes(bodyBytes);
        assertThat(bodyBytes, is("şarəs".getBytes(DEFAULT_JSON_HTTP_CHARACTER_SET)));
    }

    @Test
    public void shouldSerialiseBodyToByteBufWithInvalidContentType() {
        // given
        String bodyValue = new String(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        Body body = new StringBody(bodyValue);

        // when
        ByteBuf result = new BodyDecoderEncoder().bodyToByteBuf(body, "image/png");

        // then
        byte[] bodyBytes = new byte[result.readableBytes()];
        result.readBytes(bodyBytes);
        assertThat(bodyBytes, is(bodyValue.getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET)));
    }

    @Test
    public void shouldSerialiseBodyToChunkedByteBufWithNoContentType() {
        // given
        Body body = new StringBody("bytes");

        // when
        ByteBuf[] result = new BodyDecoderEncoder().bodyToByteBuf(body, null, 2);

        // then
        assertThat(result.length, is(3));
        byte[] bodyBytes = new byte[result[0].readableBytes()];
        result[0].readBytes(bodyBytes);
        assertThat(bodyBytes, is("by".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET)));
        bodyBytes = new byte[result[1].readableBytes()];
        result[1].readBytes(bodyBytes);
        assertThat(bodyBytes, is("te".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET)));
        bodyBytes = new byte[result[2].readableBytes()];
        result[2].readBytes(bodyBytes);
        assertThat(bodyBytes, is("s".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET)));
    }

    @Test
    public void shouldSerialiseBodyToByteBufWithJsonContentType() {
        // given
        Body body = new StringBody("şarəs");

        // when
        ByteBuf result = new BodyDecoderEncoder().bodyToByteBuf(body, MediaType.APPLICATION_JSON_UTF_8.toString());

        // then
        byte[] bodyBytes = new byte[result.readableBytes()];
        result.readBytes(bodyBytes);
        assertThat(bodyBytes, is(not("şarəs".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET))));
        assertThat(bodyBytes, is("şarəs".getBytes(UTF_8)));
    }

    @Test
    public void shouldSerialiseBodyToChunkedByteBufWithJsonContentType() {
        // given
        Body body = new StringBody("şarəs");
        byte[] bytes = "şarəs".getBytes(UTF_8);

        // when
        ByteBuf[] result = new BodyDecoderEncoder().bodyToByteBuf(body, MediaType.APPLICATION_JSON_UTF_8.toString(), 2);

        // then
        assertThat(result.length, is(4));
        byte[] bodyBytes = new byte[result[0].readableBytes()];
        result[0].readBytes(bodyBytes);
        assertThat(bodyBytes, is(Arrays.copyOfRange(bytes, 0, 2)));
        bodyBytes = new byte[result[1].readableBytes()];
        result[1].readBytes(bodyBytes);
        assertThat(bodyBytes, is(Arrays.copyOfRange(bytes, 2, 4)));
        bodyBytes = new byte[result[2].readableBytes()];
        result[2].readBytes(bodyBytes);
        assertThat(bodyBytes, is(Arrays.copyOfRange(bytes, 4, 6)));
        bodyBytes = new byte[result[3].readableBytes()];
        result[3].readBytes(bodyBytes);
        assertThat(bodyBytes, is(Arrays.copyOfRange(bytes, 6, 7)));
    }

    @Test
    public void shouldReuseMaterialisedBytesWhenJsonBodyDeclaresWireCharset() {
        // given - a JSON body that declares its own UTF-8 charset (json(value, JSON_UTF_8)); the model
        // already materialised rawBytes in UTF-8, which is exactly the wire encoding.
        JsonBody body = json("{ \"some_field\": \"我说中国话\" }", MediaType.APPLICATION_JSON_UTF_8);

        // when
        byte[] wireBytes = new BodyDecoderEncoder().bodyToBytes(body, MediaType.APPLICATION_JSON_UTF_8.toString());

        // then - byte-correct AND the very same array instance, proving the bytes were reused (zero-copy)
        // rather than re-encoded.
        assertThat(wireBytes, is("{ \"some_field\": \"我说中国话\" }".getBytes(UTF_8)));
        assertSame(body.getRawBytes(), wireBytes);
    }

    @Test
    public void shouldReuseMaterialisedBytesWhenStringBodyDeclaresWireCharset() {
        // given - a String body that declares a UTF-16 charset (a non-ASCII, multi-byte charset): reuse
        // is still byte-exact because the declared charset is exactly what rawBytes were built in.
        StringBody body = exact("我说中国话", UTF_16);

        // when
        byte[] wireBytes = new BodyDecoderEncoder().bodyToBytes(body, MediaType.create("text", "plain").withCharset(UTF_16).toString());

        // then
        assertThat(wireBytes, is("我说中国话".getBytes(UTF_16)));
        assertSame(body.getRawBytes(), wireBytes);
    }

    @Test
    public void shouldEmitMalformedRecordReplayBytesVerbatimWhenCharsetDeclared() {
        // Record-and-replay: a body built from wire bytes whose Content-Type declares a charset.
        // Reuse emits those bytes verbatim; the old decode-then-re-encode would have replaced the
        // malformed sequence with U+FFFD and written the mangled re-encoding instead. Verbatim is
        // the intended behaviour (issue #2375 -- write the body exactly as supplied), so pin it:
        // this fails both if reuse stops firing here and if a refactor reverts to mangling.
        byte[] malformed = new byte[]{(byte) 0xFF, (byte) 0xFE, 'a'};

        BodyWithContentType body = new BodyDecoderEncoder()
            .bytesToBody(malformed, "application/json; charset=utf-8");

        byte[] wireBytes = new BodyDecoderEncoder()
            .bodyToBytes(body, "application/json; charset=utf-8");

        assertThat(wireBytes, is(malformed));
        assertSame(body.getRawBytes(), wireBytes);
    }

    @Test
    public void shouldNotReuseLossyMaterialisedBytesForImplicitCharsetBodyServedAsJson() {
        // given - the withBody(String) shape with NO declared charset: rawBytes use the ISO-8859-1
        // default, which is lossy ('?') for characters above U+00FF, but a JSON response is written in
        // UTF-8. Reuse MUST NOT fire - it would emit the lossy bytes. This test fails if the reuse path
        // is ever taken for a body whose materialised bytes differ from the wire encoding.
        StringBody body = new StringBody("şarəs"); // ş (U+015F), ə (U+0259) are above U+00FF
        byte[] lossyRawBytes = body.getRawBytes(); // "şarəs".getBytes(ISO_8859_1), contains 0x3F ('?')

        // when
        byte[] wireBytes = new BodyDecoderEncoder().bodyToBytes(body, MediaType.APPLICATION_JSON_UTF_8.toString());

        // then - correct UTF-8, and NOT the lossy materialised array
        assertThat(wireBytes, is("şarəs".getBytes(UTF_8)));
        assertThat(wireBytes, is(not(lossyRawBytes)));
        assertNotSame(lossyRawBytes, wireBytes);
    }

    @Test
    public void shouldReadByteBufToStringBodyWithNoContentType() {
        // given
        ByteBuf byteBuf = Unpooled.copiedBuffer("bytes".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET));

        // when
        BodyWithContentType result = new BodyDecoderEncoder().byteBufToBody(byteBuf, null);

        // then
        assertThat(result, is(exact("bytes")));
    }

    @Test
    public void shouldReadByteBufToStringBodyWithStringContentType() {
        // given
        ByteBuf byteBuf = Unpooled.copiedBuffer("bytes".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET));

        // when
        BodyWithContentType result = new BodyDecoderEncoder().byteBufToBody(byteBuf, MediaType.TEXT_PLAIN.toString());

        // then
        assertThat(result, is(exact("bytes", MediaType.TEXT_PLAIN)));
    }

    @Test
    public void shouldReadByteBufToStringBodyWithStringContentTypeAndCharset() {
        // given
        ByteBuf byteBuf = Unpooled.copiedBuffer("bytes".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET));

        // when
        BodyWithContentType result = new BodyDecoderEncoder().byteBufToBody(byteBuf, MediaType.TEXT_HTML_UTF_8.toString());

        // then
        assertThat(result, is(exact("bytes", MediaType.TEXT_HTML_UTF_8)));
    }

    @Test
    public void shouldReadByteBufToJsonBodyWithJsonContentType() {
        // given
        ByteBuf byteBuf = Unpooled.copiedBuffer("şarəs".getBytes(UTF_8));

        // when
        BodyWithContentType result = new BodyDecoderEncoder().byteBufToBody(byteBuf, MediaType.APPLICATION_JSON_UTF_8.toString());

        // then
        assertThat(result, is(json("şarəs", MediaType.APPLICATION_JSON_UTF_8)));
    }

    @Test
    public void shouldReadByteBufToJsonBodyWithJsonContentTypeAndCharset() {
        // given
        ByteBuf byteBuf = Unpooled.copiedBuffer("şarəs".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET));

        // when
        BodyWithContentType result = new BodyDecoderEncoder().byteBufToBody(byteBuf, MediaType.APPLICATION_JSON.toString());

        // then
        assertThat(result, is(json("?ar?s", MediaType.APPLICATION_JSON)));
    }

    @Test
    public void shouldReadByteBufToBinaryBodyWithBinaryContentType() {
        // given
        ByteBuf byteBuf = Unpooled.copiedBuffer("bytes".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET));

        // when
        BodyWithContentType result = new BodyDecoderEncoder().byteBufToBody(byteBuf, MediaType.ANY_VIDEO_TYPE.toString());

        // then
        assertThat(result, is(binary("bytes".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET), MediaType.ANY_VIDEO_TYPE)));
    }

    @Test
    public void shouldReadByteBufToBinaryBodyWithBinaryContentTypeAndCharset() {
        // given
        ByteBuf byteBuf = Unpooled.copiedBuffer("bytes".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET));

        // when
        BodyWithContentType result = new BodyDecoderEncoder().byteBufToBody(byteBuf, MediaType.ANY_VIDEO_TYPE.withCharset(UTF_8).toString());

        // then
        assertThat(result, is(binary("bytes".getBytes(DEFAULT_TEXT_HTTP_CHARACTER_SET), MediaType.ANY_VIDEO_TYPE.withCharset(UTF_8))));
    }

    @Test
    public void shouldNotAlterBodyForXmlsWithWrongCharset() {
        // given
        final byte[] rawContent = {-1, -20, 127, 23, 43, 5, -5, -9};
        ByteBuf byteBuf = Unpooled.copiedBuffer(rawContent);

        // when
        BodyWithContentType result = new BodyDecoderEncoder().byteBufToBody(byteBuf, MediaType.XML_UTF_8.toString());

        // then
        assertThat(result.getRawBytes(), is(rawContent));
    }
}