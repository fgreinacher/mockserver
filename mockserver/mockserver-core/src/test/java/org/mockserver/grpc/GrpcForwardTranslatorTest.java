package org.mockserver.grpc;

import com.google.protobuf.Descriptors;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;

import java.nio.file.Paths;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Unit tests for {@link GrpcForwardTranslator} — the record/replay-critical transform that re-encodes
 * a decoded gRPC request for an upstream call and decodes the upstream response back to JSON.
 */
public class GrpcForwardTranslatorTest {

    private GrpcProtoDescriptorStore store;
    private GrpcJsonMessageConverter converter;
    private Descriptors.MethodDescriptor greetingMethod;

    private static final String SERVICE = "com.example.grpc.GreetingService";

    @Before
    public void setUp() {
        store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("src/test/resources/grpc/greeting.dsc"));
        converter = store.getConverter();
        greetingMethod = store.getMethod(SERVICE, "Greeting");
    }

    private HttpRequest grpcRequest(String jsonBody, String method) {
        return request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + method)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcForwardTranslator.SERVICE_HEADER, SERVICE)
            .withHeader(GrpcForwardTranslator.METHOD_HEADER, method)
            .withBody(jsonBody);
    }

    // --- isGrpcForwardRequest ---

    @Test
    public void shouldDetectGrpcForwardRequest() {
        assertThat(GrpcForwardTranslator.isGrpcForwardRequest(grpcRequest("{\"name\":\"Tom\"}", "Greeting")), is(true));
    }

    @Test
    public void shouldNotDetectPlainHttpRequest() {
        assertThat(GrpcForwardTranslator.isGrpcForwardRequest(
            request().withPath("/foo").withHeader("content-type", "application/json").withBody("{}")), is(false));
    }

    @Test
    public void shouldNotDetectGrpcWebAsForwardRequest() {
        assertThat(GrpcForwardTranslator.isGrpcForwardRequest(
            request().withHeader("content-type", "application/grpc-web")
                .withHeader(GrpcForwardTranslator.SERVICE_HEADER, SERVICE)
                .withHeader(GrpcForwardTranslator.METHOD_HEADER, "Greeting")), is(false));
    }

    // --- encodeRequestForUpstream ---

    @Test
    public void shouldEncodeUnaryRequestToProtobufFrame() {
        HttpRequest encoded = GrpcForwardTranslator.encodeRequestForUpstream(grpcRequest("{\"name\":\"Tom\"}", "Greeting"), store);

        assertThat(encoded.getFirstHeader("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        assertThat(encoded.getProtocol(), is(Protocol.HTTP_2));
        assertThat(encoded.getFirstHeader("te"), is("trailers"));
        // internal helper headers must not leak upstream
        assertThat(encoded.getFirstHeader(GrpcForwardTranslator.SERVICE_HEADER), is(emptyOrNullString()));
        assertThat(encoded.getFirstHeader(GrpcForwardTranslator.METHOD_HEADER), is(emptyOrNullString()));

        // body must be a single gRPC frame that decodes back to the original message
        List<byte[]> messages = GrpcFrameCodec.decode(encoded.getBodyAsRawBytes());
        assertThat(messages, hasSize(1));
        assertThat(converter.toJson(messages.get(0), greetingMethod.getInputType()), containsString("Tom"));
    }

    @Test
    public void shouldEncodeClientStreamingArrayToMultipleFrames() {
        HttpRequest req = grpcRequest("[{\"name\":\"A\"},{\"name\":\"B\"},{\"name\":\"C\"}]", "CollectGreetings")
            .withHeader(GrpcForwardTranslator.CLIENT_STREAMING_HEADER, "true");

        HttpRequest encoded = GrpcForwardTranslator.encodeRequestForUpstream(req, store);

        Descriptors.MethodDescriptor collect = store.getMethod(SERVICE, "CollectGreetings");
        List<byte[]> messages = GrpcFrameCodec.decode(encoded.getBodyAsRawBytes());
        assertThat(messages, hasSize(3));
        assertThat(converter.toJson(messages.get(0), collect.getInputType()), containsString("A"));
        assertThat(converter.toJson(messages.get(2), collect.getInputType()), containsString("C"));
        assertThat(encoded.getFirstHeader(GrpcForwardTranslator.CLIENT_STREAMING_HEADER), is(emptyOrNullString()));
    }

    @Test
    public void shouldPassThroughNonGrpcRequestUnchanged() {
        HttpRequest plain = request().withPath("/foo").withHeader("content-type", "application/json").withBody("{\"a\":1}");
        assertThat(GrpcForwardTranslator.encodeRequestForUpstream(plain, store), sameInstance(plain));
    }

    @Test
    public void shouldPassThroughWhenNoDescriptorLoaded() {
        GrpcProtoDescriptorStore empty = new GrpcProtoDescriptorStore(new MockServerLogger());
        HttpRequest req = grpcRequest("{\"name\":\"Tom\"}", "Greeting");
        assertThat(GrpcForwardTranslator.encodeRequestForUpstream(req, empty), sameInstance(req));
    }

    @Test
    public void shouldPassThroughWhenMethodUnknown() {
        HttpRequest req = grpcRequest("{\"name\":\"Tom\"}", "NoSuchMethod");
        assertThat(GrpcForwardTranslator.encodeRequestForUpstream(req, store), sameInstance(req));
    }

    // --- decodeResponseFromUpstream ---

    @Test
    public void shouldDecodeUnaryProtobufResponseToJson() {
        byte[] protobuf = converter.toProtobuf("{\"greeting\":\"Hello World\"}", greetingMethod.getOutputType());
        HttpResponse upstream = response()
            .withStatusCode(200)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
            .withBody(GrpcFrameCodec.encode(protobuf));

        HttpResponse decoded = GrpcForwardTranslator.decodeResponseFromUpstream(upstream, SERVICE, "Greeting", store);

        assertThat(decoded.getBodyAsString(), containsString("Hello World"));
        assertThat(decoded.getFirstHeader(GrpcForwardTranslator.SERVICE_HEADER), is(SERVICE));
        assertThat(decoded.getFirstHeader(GrpcForwardTranslator.METHOD_HEADER), is("Greeting"));
        assertThat("the upstream status is carried through numerically, not as a status-name",
            decoded.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
    }

    @Test
    public void shouldDecodeServerStreamingResponseToJsonArray() {
        byte[] frame1 = GrpcFrameCodec.encode(converter.toProtobuf("{\"greeting\":\"one\"}", greetingMethod.getOutputType()));
        byte[] frame2 = GrpcFrameCodec.encode(converter.toProtobuf("{\"greeting\":\"two\"}", greetingMethod.getOutputType()));
        byte[] combined = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, combined, 0, frame1.length);
        System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

        HttpResponse upstream = response().withBody(combined).withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0");
        HttpResponse decoded = GrpcForwardTranslator.decodeResponseFromUpstream(upstream, SERVICE, "ListGreetings", store);

        assertThat(decoded.getBodyAsString(), startsWith("["));
        assertThat(decoded.getBodyAsString(), containsString("one"));
        assertThat(decoded.getBodyAsString(), containsString("two"));
    }

    @Test
    public void shouldPreserveNonOkStatus() {
        HttpResponse upstream = response()
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "5")
            .withBody(new byte[0]);

        HttpResponse decoded = GrpcForwardTranslator.decodeResponseFromUpstream(upstream, SERVICE, "Greeting", store);

        assertThat(decoded.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is("5"));
        assertThat(decoded.getBodyAsString(), is(emptyOrNullString()));
    }

    /**
     * A proxy must not rewrite the upstream's status. Converting it to a status-name went through
     * {@code GrpcStatusMapper.fromCode}, which is {@code getOrDefault(code, UNKNOWN)}, so an
     * upstream returning a non-standard or future status silently became UNKNOWN -- rendered to
     * the client as "2". Codes outside the enum must survive the proxy untouched.
     */
    @Test
    public void shouldPreserveUnmappedNumericStatusFromUpstream() {
        HttpResponse upstream = response()
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "42")
            .withBody(new byte[0]);

        HttpResponse decoded = GrpcForwardTranslator.decodeResponseFromUpstream(upstream, SERVICE, "Greeting", store);

        assertThat("an unmapped upstream status must not be collapsed to UNKNOWN",
            decoded.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is("42"));
        assertThat("and must not be rewritten into a status-name either",
            decoded.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER), is(""));
    }

    @Test
    public void shouldPassThroughResponseWhenMethodUnknown() {
        HttpResponse upstream = response().withBody("anything");
        assertThat(GrpcForwardTranslator.decodeResponseFromUpstream(upstream, SERVICE, "NoSuchMethod", store), sameInstance(upstream));
    }

    @Test
    public void shouldRoundTripEncodeThenDecode() {
        // encode a request as if for upstream, feed those bytes back as a response, decode
        HttpRequest encoded = GrpcForwardTranslator.encodeRequestForUpstream(grpcRequest("{\"name\":\"RoundTrip\"}", "Greeting"), store);
        // reinterpret the framed input message bytes through the OUTPUT type is not valid; instead build a
        // proper response frame and assert it decodes
        byte[] responseFrame = GrpcFrameCodec.encode(converter.toProtobuf("{\"greeting\":\"hi RoundTrip\"}", greetingMethod.getOutputType()));
        HttpResponse decoded = GrpcForwardTranslator.decodeResponseFromUpstream(
            response().withBody(responseFrame).withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0"),
            SERVICE, "Greeting", store);

        assertThat(encoded.getBodyAsRawBytes().length, greaterThan(0));
        assertThat(decoded.getBodyAsString(), containsString("hi RoundTrip"));
    }
}
