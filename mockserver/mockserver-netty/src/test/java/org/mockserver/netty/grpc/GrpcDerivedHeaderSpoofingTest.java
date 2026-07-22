package org.mockserver.netty.grpc;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.grpc.GrpcDerivedHeaders;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.http3.GrpcHttp3Adapter;

import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.model.HttpRequest.request;

/**
 * {@code x-grpc-service} and {@code x-grpc-method} are <strong>server-derived</strong> from the gRPC
 * request path so expectations can match on service and method. A client must not be able to
 * contribute a value.
 * <p>
 * <strong>Why this needs a test at every exit rather than one representative case.</strong>
 * {@code HttpRequest.withHeader} <em>appends</em>; it does not replace. So the defence is a
 * {@link GrpcDerivedHeaders#strip} call that has to be present on every code path that sets the
 * derived headers, and a path that forgets it is silently vulnerable while every other path is
 * fine. Header matching is SUB_SET, so a surviving forged value means an expectation qualified by
 * {@code x-grpc-service: com.example.evil.Other} can match a request that actually belongs to
 * {@code com.example.grpc.GreetingService}. Routing still uses the real path, so this is a
 * matching-integrity defect rather than a dispatch bypass — but it is the exact thing the
 * derived headers exist to make trustworthy.
 * <p>
 * The conversion methods have three exits each — empty body, one message, many messages — and this
 * class covers all three on both transports. The empty-body exit on HTTP/1.1 and HTTP/2 is the one
 * that actually shipped vulnerable: it returned the request untouched, with neither the strip nor
 * the derived headers, which additionally diverged from HTTP/3 where the same request did get
 * tagged.
 * <p>
 * <strong>Every assertion is on the value <em>list</em>, never {@code getFirstHeader}.</strong> The
 * bug leaves the request carrying {@code [evil, com.example.grpc.GreetingService]}; a
 * {@code getFirstHeader} assertion can read whichever value it likes and pass while the spoof is
 * still present and still matchable.
 */
public class GrpcDerivedHeaderSpoofingTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String UNARY_METHOD = "Greeting";
    private static final String CLIENT_STREAMING_METHOD = "CollectGreetings";
    private static final String FORGED_SERVICE = "com.example.evil.OtherService";
    private static final String FORGED_METHOD = "Evil";

    private GrpcProtoDescriptorStore descriptorStore;
    private GrpcJsonMessageConverter converter;

    @Before
    public void setUp() {
        descriptorStore = new GrpcProtoDescriptorStore(new MockServerLogger());
        descriptorStore.loadDescriptorSetFromPath(
            Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));
        converter = descriptorStore.getConverter();
    }

    // ---- HTTP/1.1 + HTTP/2: GrpcToHttpRequestHandler.convertGrpcRequest, all three exits ----

    @Test
    public void shouldNotKeepSpoofedDerivedHeadersOnAnEmptyBodiedRequestOverHttp2() {
        assertOnlyDerivedValues(convertOverHttp2(spoofed(UNARY_METHOD).withBody(new byte[0])), UNARY_METHOD);
    }

    @Test
    public void shouldNotKeepSpoofedDerivedHeadersOnASingleMessageRequestOverHttp2() {
        HttpRequest converted = convertOverHttp2(
            spoofed(UNARY_METHOD).withBody(framed(helloRequestBytes("World"))));
        assertOnlyDerivedValues(converted, UNARY_METHOD);
    }

    @Test
    public void shouldNotKeepSpoofedDerivedHeadersOnAMultiMessageRequestOverHttp2() {
        byte[] first = framed(helloRequestBytes("One"));
        byte[] second = framed(helloRequestBytes("Two"));
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);

        HttpRequest converted = convertOverHttp2(spoofed(CLIENT_STREAMING_METHOD).withBody(both));

        assertOnlyDerivedValues(converted, CLIENT_STREAMING_METHOD);
        assertThat("a client-supplied x-grpc-client-streaming must not survive either",
            values(converted, GrpcDerivedHeaders.CLIENT_STREAMING), contains("true"));
    }

    // ---- HTTP/3: GrpcHttp3Adapter.transformGrpcRequest, all three exits ----

    @Test
    public void shouldNotKeepSpoofedDerivedHeadersOnAnEmptyBodiedRequestOverHttp3() {
        assertOnlyDerivedValues(
            GrpcHttp3Adapter.transformGrpcRequest(spoofed(UNARY_METHOD).withBody(new byte[0]), descriptorStore),
            UNARY_METHOD);
    }

    @Test
    public void shouldNotKeepSpoofedDerivedHeadersOnASingleMessageRequestOverHttp3() {
        assertOnlyDerivedValues(
            GrpcHttp3Adapter.transformGrpcRequest(
                spoofed(UNARY_METHOD).withBody(framed(helloRequestBytes("World"))), descriptorStore),
            UNARY_METHOD);
    }

    @Test
    public void shouldNotKeepSpoofedDerivedHeadersOnAMultiMessageRequestOverHttp3() {
        byte[] first = framed(helloRequestBytes("One"));
        byte[] second = framed(helloRequestBytes("Two"));
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);

        HttpRequest converted = GrpcHttp3Adapter.transformGrpcRequest(
            spoofed(CLIENT_STREAMING_METHOD).withBody(both), descriptorStore);

        assertOnlyDerivedValues(converted, CLIENT_STREAMING_METHOD);
        assertThat(values(converted, GrpcDerivedHeaders.CLIENT_STREAMING), contains("true"));
    }

    // ---- transport parity ----

    /**
     * The empty-body exit is the one that diverged: HTTP/3 tagged the request, HTTP/1.1 and HTTP/2
     * returned it untouched, so the same request was matchable by service over one transport and not
     * the other. Pinned explicitly so the two cannot drift apart again.
     */
    @Test
    public void shouldTagAnEmptyBodiedRequestIdenticallyOnHttp2AndHttp3() {
        HttpRequest overHttp2 = convertOverHttp2(clean(UNARY_METHOD).withBody(new byte[0]));
        HttpRequest overHttp3 = GrpcHttp3Adapter.transformGrpcRequest(
            clean(UNARY_METHOD).withBody(new byte[0]), descriptorStore);

        assertThat(values(overHttp2, GrpcDerivedHeaders.SERVICE), contains(SERVICE));
        assertThat(values(overHttp3, GrpcDerivedHeaders.SERVICE), contains(SERVICE));
        assertThat(values(overHttp2, GrpcDerivedHeaders.METHOD), contains(UNARY_METHOD));
        assertThat(values(overHttp3, GrpcDerivedHeaders.METHOD), contains(UNARY_METHOD));
    }

    // ---- helpers ----

    /**
     * Asserts the derived headers carry exactly the path-derived value and nothing else — the forged
     * value must be gone, not merely outranked.
     */
    private void assertOnlyDerivedValues(HttpRequest converted, String expectedMethod) {
        assertThat(converted, is(notNullValue()));
        assertThat("x-grpc-service must carry ONLY the path-derived service",
            values(converted, GrpcDerivedHeaders.SERVICE), contains(SERVICE));
        assertThat("x-grpc-method must carry ONLY the path-derived method",
            values(converted, GrpcDerivedHeaders.METHOD), contains(expectedMethod));
    }

    /** The full value list — never {@code getFirstHeader}, which would hide a surviving spoof. */
    private List<String> values(HttpRequest request, String name) {
        return request.getHeader(name);
    }

    /** A gRPC request carrying a forged copy of every derived header. */
    private HttpRequest spoofed(String method) {
        return clean(method)
            .withHeader(GrpcDerivedHeaders.SERVICE, FORGED_SERVICE)
            .withHeader(GrpcDerivedHeaders.METHOD, FORGED_METHOD)
            .withHeader(GrpcDerivedHeaders.ORIGINAL_CONTENT_TYPE, "application/evil")
            .withHeader(GrpcDerivedHeaders.CLIENT_STREAMING, "true");
    }

    private HttpRequest clean(String method) {
        return request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + method)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
    }

    /**
     * Drives the request through {@link GrpcToHttpRequestHandler} and returns what it forwarded for
     * matching. {@code convertGrpcRequest} is private, so the handler is exercised as a handler —
     * which is also the shape the real pipeline uses.
     */
    private HttpRequest convertOverHttp2(HttpRequest request) {
        EmbeddedChannel channel = new EmbeddedChannel(
            new GrpcToHttpRequestHandler(new MockServerLogger(), descriptorStore));
        try {
            channel.writeInbound(request);
            HttpRequest forwarded = channel.readInbound();
            assertThat("the handler must forward a converted request for matching",
                forwarded, is(notNullValue()));
            return forwarded;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private byte[] helloRequestBytes(String name) {
        return converter.toProtobuf("{\"name\":\"" + name + "\"}",
            descriptorStore.getMethod(SERVICE, UNARY_METHOD).getInputType());
    }

    private byte[] framed(byte[] message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] framedBytes = GrpcFrameCodec.encode(message);
        out.write(framedBytes, 0, framedBytes.length);
        return out.toByteArray();
    }
}
