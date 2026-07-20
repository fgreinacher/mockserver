package org.mockserver.grpc;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GrpcWebTranslatorTest {

    // ---- Content-type detection ----

    @Test
    public void shouldDetectGrpcWebBinaryContentType() {
        assertThat(GrpcWebTranslator.isGrpcWebContentType("application/grpc-web"), is(true));
        assertThat(GrpcWebTranslator.isGrpcWebContentType("application/grpc-web+proto"), is(true));
    }

    @Test
    public void shouldDetectGrpcWebTextContentType() {
        assertThat(GrpcWebTranslator.isGrpcWebContentType("application/grpc-web-text"), is(true));
        assertThat(GrpcWebTranslator.isGrpcWebContentType("application/grpc-web-text+proto"), is(true));
    }

    @Test
    public void shouldNotDetectStandardGrpcAsGrpcWeb() {
        assertThat(GrpcWebTranslator.isGrpcWebContentType("application/grpc"), is(false));
        assertThat(GrpcWebTranslator.isGrpcWebContentType("application/grpc+proto"), is(false));
    }

    @Test
    public void shouldNotDetectNullOrNonGrpc() {
        assertThat(GrpcWebTranslator.isGrpcWebContentType(null), is(false));
        assertThat(GrpcWebTranslator.isGrpcWebContentType("application/json"), is(false));
    }

    @Test
    public void shouldDistinguishTextVariant() {
        assertThat(GrpcWebTranslator.isGrpcWebTextContentType("application/grpc-web-text"), is(true));
        assertThat(GrpcWebTranslator.isGrpcWebTextContentType("application/grpc-web-text+proto"), is(true));
        assertThat(GrpcWebTranslator.isGrpcWebTextContentType("application/grpc-web"), is(false));
        assertThat(GrpcWebTranslator.isGrpcWebTextContentType("application/grpc-web+proto"), is(false));
        assertThat(GrpcWebTranslator.isGrpcWebTextContentType(null), is(false));
    }

    // ---- Request body decoding ----

    @Test
    public void shouldPassThroughBinaryRequestBody() {
        byte[] body = GrpcFrameCodec.encode("hello".getBytes());
        byte[] decoded = GrpcWebTranslator.decodeRequestBody(body, "application/grpc-web");
        assertThat(decoded, is(body));
    }

    @Test
    public void shouldBase64DecodeTextRequestBody() {
        byte[] grpcFrame = GrpcFrameCodec.encode("hello".getBytes());
        byte[] base64Body = Base64.getEncoder().encode(grpcFrame);

        byte[] decoded = GrpcWebTranslator.decodeRequestBody(base64Body, "application/grpc-web-text");
        assertThat(decoded, is(grpcFrame));

        // verify the decoded frames can be parsed by GrpcFrameCodec
        List<byte[]> messages = GrpcFrameCodec.decode(decoded);
        assertThat(messages.size(), is(1));
        assertThat(messages.get(0), is("hello".getBytes()));
    }

    @Test
    public void shouldHandleNullAndEmptyRequestBody() {
        assertThat(GrpcWebTranslator.decodeRequestBody(null, "application/grpc-web"), is(nullValue()));
        assertThat(GrpcWebTranslator.decodeRequestBody(new byte[0], "application/grpc-web").length, is(0));
    }

    // ---- Trailer frame construction ----

    @Test
    public void shouldBuildTrailerFrameWithStatusOnly() {
        byte[] frame = GrpcWebTranslator.buildTrailerFrame("0", null);

        ByteBuffer buf = ByteBuffer.wrap(frame);
        byte flag = buf.get();
        int length = buf.getInt();

        assertThat(flag, is(GrpcWebTranslator.TRAILER_FLAG));
        assertThat(length, is(frame.length - 5));

        byte[] trailerBody = new byte[length];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 0\r\n"));
        assertThat(trailerText, not(containsString("grpc-message")));
    }

    @Test
    public void shouldBuildTrailerFrameWithStatusAndMessage() {
        byte[] frame = GrpcWebTranslator.buildTrailerFrame("13", "internal error");

        ByteBuffer buf = ByteBuffer.wrap(frame);
        byte flag = buf.get();
        int length = buf.getInt();

        assertThat(flag, is(GrpcWebTranslator.TRAILER_FLAG));

        byte[] trailerBody = new byte[length];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 13\r\n"));
        assertThat(trailerText, containsString("grpc-message: internal error\r\n"));
    }

    @Test
    public void shouldDefaultStatusToZeroWhenNull() {
        byte[] frame = GrpcWebTranslator.buildTrailerFrame(null, null);

        ByteBuffer buf = ByteBuffer.wrap(frame);
        buf.get(); // skip flag
        int length = buf.getInt();
        byte[] trailerBody = new byte[length];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 0\r\n"));
    }

    // ---- Response body encoding (binary) ----

    @Test
    public void shouldEncodeResponseBodyWithMessageAndTrailer() {
        byte[] messageFrame = GrpcFrameCodec.encode("response data".getBytes());
        byte[] encoded = GrpcWebTranslator.encodeResponseBody(messageFrame, "0", null, false);

        // should contain the message frame followed by the trailer frame
        ByteBuffer buf = ByteBuffer.wrap(encoded);

        // first frame: message
        byte flag1 = buf.get();
        assertThat(flag1 & 0x80, is(0)); // not a trailer frame
        int len1 = buf.getInt();
        byte[] msg = new byte[len1];
        buf.get(msg);
        assertThat(msg, is("response data".getBytes()));

        // second frame: trailer
        byte flag2 = buf.get();
        assertThat(flag2, is(GrpcWebTranslator.TRAILER_FLAG));
        int len2 = buf.getInt();
        byte[] trailerBody = new byte[len2];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 0\r\n"));

        // should have consumed all bytes
        assertThat(buf.remaining(), is(0));
    }

    @Test
    public void shouldEncodeResponseBodyWithoutMessage() {
        byte[] encoded = GrpcWebTranslator.encodeResponseBody(null, "5", "not found", false);

        ByteBuffer buf = ByteBuffer.wrap(encoded);
        byte flag = buf.get();
        assertThat(flag, is(GrpcWebTranslator.TRAILER_FLAG));

        int len = buf.getInt();
        byte[] trailerBody = new byte[len];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 5\r\n"));
        assertThat(trailerText, containsString("grpc-message: not found\r\n"));
    }

    @Test
    public void shouldEncodeResponseBodyWithErrorStatus() {
        byte[] messageFrame = GrpcFrameCodec.encode("error detail".getBytes());
        byte[] encoded = GrpcWebTranslator.encodeResponseBody(
            messageFrame, "13", "internal server error", false
        );

        ByteBuffer buf = ByteBuffer.wrap(encoded);
        // skip message frame
        buf.get(); // flag
        int len1 = buf.getInt();
        buf.position(buf.position() + len1);
        // trailer frame
        byte flag = buf.get();
        assertThat(flag, is(GrpcWebTranslator.TRAILER_FLAG));
        int len2 = buf.getInt();
        byte[] trailerBody = new byte[len2];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 13\r\n"));
        assertThat(trailerText, containsString("grpc-message: internal server error\r\n"));
    }

    // ---- Response body encoding (text/base64) ----

    @Test
    public void shouldBase64EncodeResponseBodyForTextVariant() {
        byte[] messageFrame = GrpcFrameCodec.encode("hello".getBytes());
        byte[] encoded = GrpcWebTranslator.encodeResponseBody(messageFrame, "0", null, true);

        // should be valid base64
        byte[] decoded = Base64.getDecoder().decode(encoded);

        ByteBuffer buf = ByteBuffer.wrap(decoded);
        // message frame
        byte flag1 = buf.get();
        assertThat(flag1 & 0x80, is(0));
        int len1 = buf.getInt();
        byte[] msg = new byte[len1];
        buf.get(msg);
        assertThat(msg, is("hello".getBytes()));

        // trailer frame
        byte flag2 = buf.get();
        assertThat(flag2, is(GrpcWebTranslator.TRAILER_FLAG));
        int len2 = buf.getInt();
        byte[] trailerBody = new byte[len2];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 0\r\n"));
    }

    // ---- Multi-message response ----

    @Test
    public void shouldEncodeMultiMessageResponseBody() {
        byte[] frame1 = GrpcFrameCodec.encode("msg1".getBytes());
        byte[] frame2 = GrpcFrameCodec.encode("msg2".getBytes());
        byte[] combinedMessages = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, combinedMessages, 0, frame1.length);
        System.arraycopy(frame2, 0, combinedMessages, frame1.length, frame2.length);

        byte[] encoded = GrpcWebTranslator.encodeResponseBody(combinedMessages, "0", null, false);
        ByteBuffer buf = ByteBuffer.wrap(encoded);

        // first message frame
        byte flag1 = buf.get();
        assertThat(flag1 & 0x80, is(0));
        int len1 = buf.getInt();
        byte[] msg1 = new byte[len1];
        buf.get(msg1);
        assertThat(msg1, is("msg1".getBytes()));

        // second message frame
        byte flag2 = buf.get();
        assertThat(flag2 & 0x80, is(0));
        int len2 = buf.getInt();
        byte[] msg2 = new byte[len2];
        buf.get(msg2);
        assertThat(msg2, is("msg2".getBytes()));

        // trailer frame
        byte flag3 = buf.get();
        assertThat(flag3, is(GrpcWebTranslator.TRAILER_FLAG));
        int len3 = buf.getInt();
        byte[] trailerBody = new byte[len3];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 0\r\n"));

        assertThat(buf.remaining(), is(0));
    }

    // ---- Round-trip ----

    @Test
    public void shouldRoundTripBinaryVariant() {
        // simulate a request: encode a gRPC frame as a gRPC-Web binary body
        byte[] originalMessage = "round-trip test".getBytes();
        byte[] grpcFrame = GrpcFrameCodec.encode(originalMessage);

        // decode request (binary = passthrough)
        byte[] decoded = GrpcWebTranslator.decodeRequestBody(grpcFrame, "application/grpc-web");
        List<byte[]> messages = GrpcFrameCodec.decode(decoded);
        assertThat(messages.size(), is(1));
        assertThat(messages.get(0), is(originalMessage));

        // encode response
        byte[] responseFrame = GrpcFrameCodec.encode("response".getBytes());
        byte[] webResponse = GrpcWebTranslator.encodeResponseBody(responseFrame, "0", "OK", false);

        // verify response can be parsed
        ByteBuffer buf = ByteBuffer.wrap(webResponse);
        // message frame
        buf.get(); // flag
        int msgLen = buf.getInt();
        byte[] responseMsg = new byte[msgLen];
        buf.get(responseMsg);
        assertThat(responseMsg, is("response".getBytes()));
        // trailer frame
        assertThat(buf.get(), is(GrpcWebTranslator.TRAILER_FLAG));
    }

    @Test
    public void shouldRoundTripTextVariant() {
        byte[] originalMessage = "round-trip text test".getBytes();
        byte[] grpcFrame = GrpcFrameCodec.encode(originalMessage);
        byte[] base64Request = Base64.getEncoder().encode(grpcFrame);

        // decode request
        byte[] decoded = GrpcWebTranslator.decodeRequestBody(base64Request, "application/grpc-web-text");
        List<byte[]> messages = GrpcFrameCodec.decode(decoded);
        assertThat(messages.size(), is(1));
        assertThat(messages.get(0), is(originalMessage));

        // encode response
        byte[] responseFrame = GrpcFrameCodec.encode("text response".getBytes());
        byte[] webResponse = GrpcWebTranslator.encodeResponseBody(responseFrame, "0", null, true);

        // should be valid base64
        byte[] decodedResponse = Base64.getDecoder().decode(webResponse);
        ByteBuffer buf = ByteBuffer.wrap(decodedResponse);
        buf.get(); // flag
        int msgLen = buf.getInt();
        byte[] responseMsg = new byte[msgLen];
        buf.get(responseMsg);
        assertThat(responseMsg, is("text response".getBytes()));
        assertThat(buf.get(), is(GrpcWebTranslator.TRAILER_FLAG));
    }

    // ---- Response content-type mapping ----

    /**
     * The negotiated {@code +proto} subtype is echoed back rather than collapsed to the bare type.
     * Clients accept the bare form, so this is cosmetic, but the response should reflect what was
     * actually negotiated.
     */
    @Test
    public void shouldReturnCorrectResponseContentType() {
        assertThat(GrpcWebTranslator.responseContentType("application/grpc-web"),
            is("application/grpc-web"));
        assertThat(GrpcWebTranslator.responseContentType("application/grpc-web+proto"),
            is("application/grpc-web+proto"));
        assertThat(GrpcWebTranslator.responseContentType("application/grpc-web-text"),
            is("application/grpc-web-text"));
        assertThat(GrpcWebTranslator.responseContentType("application/grpc-web-text+proto"),
            is("application/grpc-web-text+proto"));
    }

    // ---- Trailer frame parsing ----

    @Test
    public void shouldParseTrailerFrame() {
        String trailerText = "grpc-status: 0\r\ngrpc-message: success\r\n";
        byte[] trailerBody = trailerText.getBytes(StandardCharsets.US_ASCII);
        String parsed = GrpcWebTranslator.parseTrailerFrame(trailerBody);
        assertThat(parsed, is(trailerText));
    }
}
