package org.mockserver.netty.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end gRPC test driven by a <strong>real grpc-java client</strong> over h2c.
 * <p>
 * Every other gRPC test in this repository asserts at handler level or drives MockServer with a
 * hand-rolled frame writer, which is precisely why issue #2419 shipped: a real client rejects a
 * unary response that is raw JSON rather than a length-prefixed protobuf frame, and rejects one
 * whose {@code grpc-status} arrives in the initial HEADERS instead of a terminal trailing HEADERS
 * frame. Both defects are invisible to a frame-level assertion that only inspects what MockServer
 * chose to send.
 * <p>
 * The expectation below is registered <em>exactly</em> as the documentation and the issue describe
 * it: the gRPC metadata lives on the request matcher only, and the response carries nothing but a
 * status code, {@code grpc-status} and a JSON body. No {@code x-grpc-*} headers are set on the
 * response.
 * <p>
 * Uses {@code DynamicMessage} plus the loaded descriptor set rather than protoc-generated stubs, so
 * the test needs no code generation step. The channel is built from {@code grpc-netty-shaded} so
 * grpc's bundled Netty 4.1 cannot clash with MockServer's Netty 4.2.
 */
public class GrpcUnaryClientIntegrationTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String METHOD = "Greeting";
    private static final String STREAM_METHOD = "ListGreetings";
    private static final String GREETING_DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    private static final String CATALOG_SERVICE = "com.example.catalog.CatalogService";
    private static final String GET_BOOK = "GetBook";
    private static final String GET_AUTHOR = "GetAuthor";
    private static final String CATALOG_DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/catalog.dsc";

    /**
     * A real gRPC binary metadata key. {@code BINARY_BYTE_MARSHALLER} is what makes grpc-java apply
     * the {@code -bin} base64 rules on the wire, in both directions.
     */
    private static final String TRACE_BIN_HEADER = "x-trace-bin";
    private static final Metadata.Key<byte[]> TRACE_BIN =
        Metadata.Key.of(TRACE_BIN_HEADER, Metadata.BINARY_BYTE_MARSHALLER);

    /**
     * Custom <em>response</em> metadata (initial headers) and custom <em>trailing</em> metadata,
     * as a user authors them with {@code withHeader} / {@code withTrailer}. The checksum value
     * deliberately carries {@code =}, {@code ;}, {@code ,} and spaces so an exact round-trip means
     * something: every one of those is legal in an ASCII metadata value and every one of them is a
     * character a careless split or re-serialization mangles.
     */
    private static final String RESPONSE_ID_HEADER = "x-mock-response-id";
    private static final String RESPONSE_ID_VALUE = "resp-42";
    private static final Metadata.Key<String> RESPONSE_ID =
        Metadata.Key.of(RESPONSE_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER);
    private static final String CHECKSUM_TRAILER = "x-mock-checksum";
    private static final String CHECKSUM_VALUE = "sha256=9f86d081884c7d65; v=2, weight=0.75";
    private static final Metadata.Key<String> CHECKSUM =
        Metadata.Key.of(CHECKSUM_TRAILER, Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Response delay used by the concurrency tests. Every call is dispatched before any response is
     * written, which is exactly the interleaving that a single-slot channel attribute could not
     * survive: on the default (non-multiplex) HTTP/2 pipeline both gRPC handlers sit on the shared
     * connection-level pipeline, so every request would overwrite the previous one's record.
     */
    private static final int CONCURRENCY_DELAY_SECONDS = 2;

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private ManagedChannel channel;

    private final Map<String, Descriptors.ServiceDescriptor> services = new LinkedHashMap<>();
    private Descriptors.Descriptor requestType;
    private Descriptors.Descriptor responseType;
    private MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod;

    @Before
    public void setUp() throws Exception {
        byte[] greetingDescriptorBytes = Files.readAllBytes(Paths.get(GREETING_DESCRIPTOR));
        byte[] catalogDescriptorBytes = Files.readAllBytes(Paths.get(CATALOG_DESCRIPTOR));
        registerServices(greetingDescriptorBytes);
        registerServices(catalogDescriptorBytes);

        Descriptors.MethodDescriptor greeting = method(SERVICE, METHOD);
        requestType = greeting.getInputType();
        responseType = greeting.getOutputType();

        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        // the descriptor store merges uploads, so both services are resolvable at once
        mockServerClient.uploadGrpcDescriptor(greetingDescriptorBytes);
        mockServerClient.uploadGrpcDescriptor(catalogDescriptorBytes);

        channel = NettyChannelBuilder
            .forAddress("localhost", mockServer.getLocalPort())
            .usePlaintext()
            .build();

        grpcMethod = grpcMethodDescriptor(SERVICE, METHOD);
    }

    @After
    public void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(10, TimeUnit.SECONDS);
            channel = null;
        }
        stopQuietly(mockServerClient);
        mockServerClient = null;
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
    }

    /**
     * The documented unary example: a real gRPC client must be able to deserialize the response.
     * Before the fix this failed inside grpc-java, because MockServer returned the raw JSON body
     * with {@code grpc-status} in the initial headers.
     */
    @Test
    public void shouldServeUnaryRpcToRealGrpcClient() {
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath("/" + SERVICE + "/" + METHOD)
                    .withHeader("content-type", "application/grpc")
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status", "0")
                    .withBody("{\"greeting\":\"Hello World\"}")
            );

        DynamicMessage reply = ClientCalls.blockingUnaryCall(
            channel, grpcMethod, CallOptions.DEFAULT, helloRequest("World"));

        assertThat(
            (String) reply.getField(responseType.findFieldByName("greeting")),
            is("Hello World"));
    }

    /**
     * A non-OK status must reach the client as a real gRPC status, which is only possible if it
     * rides in the terminal trailing HEADERS frame.
     */
    @Test
    public void shouldPropagateNonOkGrpcStatusToRealGrpcClient() {
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath("/" + SERVICE + "/" + METHOD)
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status-name", "NOT_FOUND")
                    .withHeader("grpc-message", "no such greeting")
            );

        try {
            ClientCalls.blockingUnaryCall(channel, grpcMethod, CallOptions.DEFAULT, helloRequest("World"));
            throw new AssertionError("expected a StatusRuntimeException for a NOT_FOUND gRPC status");
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode(), is(Status.Code.NOT_FOUND));
            assertThat(e.getStatus().getDescription(), is("no such greeting"));
        }
    }

    // ---- concurrency: several in-flight streams on one connection ----

    /**
     * Four concurrent unary calls on a single {@link ManagedChannel}, held open by a response
     * delay so that every request is read before any response is written.
     * <p>
     * This is the case a single-slot channel attribute cannot serve. In the default configuration
     * {@code grpcBidiStreamingEnabled} is off, so {@code PortUnificationHandler.switchToHttp2}
     * installs both gRPC handlers on the <strong>connection-level</strong> pipeline rather than on
     * per-stream child channels — one attribute shared by every multiplexed stream. Measured
     * before the per-stream registry: calls 0, 1 and 2 failed with the byte-for-byte pre-fix
     * symptom (raw JSON body, {@code grpc-status} in the initial HEADERS, no
     * {@code content-type: application/grpc}) and only call 3 succeeded.
     * <p>
     * Every other gRPC test in this repository is sequential, which is why 34 green tests coexisted
     * with a 1-in-4 real-client success rate.
     */
    @Test
    public void shouldServeConcurrentUnaryRpcsOnOneConnection() throws Exception {
        mockServerClient
            .when(request().withPath("/" + SERVICE + "/" + METHOD))
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status", "0")
                    .withBody("{\"greeting\":\"Hello World\"}")
                    .withDelay(TimeUnit.SECONDS, CONCURRENCY_DELAY_SECONDS));

        List<DynamicMessage> replies = callConcurrently(
            Collections.nCopies(4, (Callable<DynamicMessage>) () ->
                ClientCalls.blockingUnaryCall(channel, grpcMethod, CallOptions.DEFAULT, helloRequest("World"))));

        for (DynamicMessage reply : replies) {
            assertThat(
                (String) reply.getField(responseType.findFieldByName("greeting")),
                is("Hello World"));
        }
    }

    /**
     * Two <em>different</em> methods in flight at once, with deliberately distinct output types.
     * <p>
     * A shared single slot does not merely drop conversions here — it can convert a response
     * against the wrong method's output type, producing a wrong-typed message or throwing into the
     * encoder's catch block and replacing a valid response with {@code grpc-status: 13 INTERNAL}.
     * That is a failure mode the pre-#2419 code did not have, so it needs its own test: each reply
     * must deserialize against the type its own call declared.
     */
    @Test
    public void shouldConvertConcurrentCallsToDifferentMethodsAgainstTheirOwnOutputType() throws Exception {
        mockServerClient
            .when(request().withPath("/" + CATALOG_SERVICE + "/" + GET_BOOK))
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("{\"title\":\"Dune\",\"pages\":412}")
                    .withDelay(TimeUnit.SECONDS, CONCURRENCY_DELAY_SECONDS));
        mockServerClient
            .when(request().withPath("/" + CATALOG_SERVICE + "/" + GET_AUTHOR))
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("{\"name\":\"Frank Herbert\",\"country\":\"US\"}")
                    .withDelay(TimeUnit.SECONDS, CONCURRENCY_DELAY_SECONDS));

        MethodDescriptor<DynamicMessage, DynamicMessage> getBook = grpcMethodDescriptor(CATALOG_SERVICE, GET_BOOK);
        MethodDescriptor<DynamicMessage, DynamicMessage> getAuthor = grpcMethodDescriptor(CATALOG_SERVICE, GET_AUTHOR);
        Descriptors.Descriptor bookType = method(CATALOG_SERVICE, GET_BOOK).getOutputType();
        Descriptors.Descriptor authorType = method(CATALOG_SERVICE, GET_AUTHOR).getOutputType();
        DynamicMessage lookup = lookupRequest(method(CATALOG_SERVICE, GET_BOOK).getInputType(), "1");

        List<DynamicMessage> replies = callConcurrently(Arrays.asList(
            () -> ClientCalls.blockingUnaryCall(channel, getBook, CallOptions.DEFAULT, lookup),
            () -> ClientCalls.blockingUnaryCall(channel, getAuthor, CallOptions.DEFAULT, lookup),
            () -> ClientCalls.blockingUnaryCall(channel, getBook, CallOptions.DEFAULT, lookup),
            () -> ClientCalls.blockingUnaryCall(channel, getAuthor, CallOptions.DEFAULT, lookup)));

        for (int i = 0; i < replies.size(); i++) {
            DynamicMessage reply = replies.get(i);
            if (i % 2 == 0) {
                assertThat((String) reply.getField(bookType.findFieldByName("title")), is("Dune"));
                assertThat((Integer) reply.getField(bookType.findFieldByName("pages")), is(412));
            } else {
                assertThat((String) reply.getField(authorType.findFieldByName("name")), is("Frank Herbert"));
                assertThat((String) reply.getField(authorType.findFieldByName("country")), is("US"));
            }
        }
    }

    // ---- unmatched requests must not be fabricated into a success ----

    /**
     * With no expectation registered, the client must see a real gRPC error.
     * <p>
     * Once conversion fires for every response on the connection, the 404 {@code notFoundResponse}
     * is a conversion candidate too: it carries no {@code grpc-status}, so it would resolve to OK,
     * its empty body would be filled in by the descriptor example synthesizer, and the 404 would be
     * overwritten with 200 — the server log saying {@code 404 Not Found} while the client received
     * {@code OK: greeting: "string"}. A typo'd path would then return a plausible green response
     * forever with no client-side indicator.
     * <p>
     * {@code UNIMPLEMENTED} is what a real gRPC server returns for an unknown method, and is the
     * gRPC-over-HTTP/2 spec's mapping for HTTP 404.
     */
    @Test
    public void shouldReportUnmatchedRequestAsGrpcErrorRatherThanFabricatedSuccess() {
        try {
            DynamicMessage reply = ClientCalls.blockingUnaryCall(
                channel, grpcMethod, CallOptions.DEFAULT, helloRequest("World"));
            throw new AssertionError(
                "an unmatched gRPC request must not be answered with a synthesized success, but got: " + reply);
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode(), is(Status.Code.UNIMPLEMENTED));
            assertThat(e.getStatus().getDescription(), containsString("404"));
        }
    }

    // ---- helpers ----

    /**
     * Runs every call on its own thread, released together, and fails with the underlying gRPC
     * error rather than a bare assertion if any of them does not complete successfully.
     */
    private List<DynamicMessage> callConcurrently(List<Callable<DynamicMessage>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch releaseAll = new CountDownLatch(1);
            List<Future<DynamicMessage>> futures = new ArrayList<>();
            for (Callable<DynamicMessage> call : calls) {
                futures.add(executor.submit(() -> {
                    releaseAll.await();
                    return call.call();
                }));
            }
            releaseAll.countDown();

            List<DynamicMessage> replies = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    replies.add(futures.get(i).get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new AssertionError("concurrent call " + i + " failed: " + e.getCause(), e.getCause());
                }
            }
            return replies;
        } finally {
            executor.shutdownNow();
        }
    }

    private DynamicMessage helloRequest(String name) {
        return DynamicMessage.newBuilder(requestType)
            .setField(requestType.findFieldByName("name"), name)
            .build();
    }

    private DynamicMessage lookupRequest(Descriptors.Descriptor lookupType, String id) {
        return DynamicMessage.newBuilder(lookupType)
            .setField(lookupType.findFieldByName("id"), id)
            .build();
    }

    private Descriptors.MethodDescriptor method(String serviceName, String methodName) {
        Descriptors.ServiceDescriptor serviceDescriptor = services.get(serviceName);
        if (serviceDescriptor == null) {
            throw new IllegalStateException("service not found in descriptors: " + serviceName);
        }
        Descriptors.MethodDescriptor methodDescriptor = serviceDescriptor.findMethodByName(methodName);
        if (methodDescriptor == null) {
            throw new IllegalStateException("method not found: " + serviceName + "/" + methodName);
        }
        return methodDescriptor;
    }

    private MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethodDescriptor(String serviceName, String methodName) {
        Descriptors.MethodDescriptor methodDescriptor = method(serviceName, methodName);
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
            .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(methodDescriptor.getInputType())))
            .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(methodDescriptor.getOutputType())))
            .build();
    }

    /**
     * Registers every service in a compiled descriptor set by full name, so the test needs no
     * protoc-generated stubs.
     */
    private void registerServices(byte[] descriptorBytes) throws Exception {
        DescriptorProtos.FileDescriptorSet fileDescriptorSet =
            DescriptorProtos.FileDescriptorSet.parseFrom(descriptorBytes);
        for (DescriptorProtos.FileDescriptorProto fileDescriptorProto : fileDescriptorSet.getFileList()) {
            Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(
                fileDescriptorProto, new Descriptors.FileDescriptor[0]);
            for (Descriptors.ServiceDescriptor serviceDescriptor : fileDescriptor.getServices()) {
                services.put(serviceDescriptor.getFullName(), serviceDescriptor);
            }
        }
    }

    // ---- binary metadata (-bin), driven by a real io.grpc.Metadata binary key ----

    /**
     * The contract for a {@code -bin} metadata key: the user writes the value <strong>already
     * base64-encoded</strong> and MockServer passes it through untouched, in both directions. It
     * never encodes and never decodes.
     * <p>
     * <strong>What breaks without a fix, and only against a real client.</strong> grpc-java encodes
     * outbound binary metadata with {@code BASE64_ENCODING_OMIT_PADDING}
     * ({@code io.grpc.internal.TransportFrameUtil#toHttp2Headers}), so the value on the wire is
     * {@code AQIDBA} — never the {@code AQIDBA==} that {@code Base64.getEncoder()} produces and that
     * a user naturally writes into an expectation. Before the fix that expectation silently never
     * matched: no error, no warning, just a 404-shaped UNIMPLEMENTED. No handler-level test could
     * catch it, because the padding is applied by the client library, not by MockServer.
     * <p>
     * This asserts the whole loop with a real {@code Metadata.BINARY_BYTE_MARSHALLER} key:
     * <ol>
     *   <li>the padded expectation matches;</li>
     *   <li>the value MockServer actually recorded is the unpadded wire form, so the premise above is
     *       demonstrated rather than assumed;</li>
     *   <li>the padded response value passes through and grpc-java decodes it back to the original
     *       bytes — the return leg, which relies on Guava's base64 decoder, exercised rather than
     *       inferred.</li>
     * </ol>
     */
    @Test
    public void shouldMatchPaddedBinaryMetadataExpectationAgainstUnpaddedWireValueFromRealClient() {
        byte[] traceBytes = {1, 2, 3, 4};
        String padded = Base64.getEncoder().encodeToString(traceBytes);
        assertThat("this test is only meaningful if the padded form really is padded", padded, is("AQIDBA=="));

        mockServerClient
            .when(
                request()
                    .withPath("/" + SERVICE + "/" + METHOD)
                    // the natural, padded spelling — what a user writes
                    .withHeader(TRACE_BIN_HEADER, padded)
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status", "0")
                    // passed through verbatim, still padded
                    .withHeader(TRACE_BIN_HEADER, padded)
                    .withBody("{\"greeting\":\"Hello World\"}")
            );

        AtomicReference<Metadata> headers = new AtomicReference<>();
        AtomicReference<Metadata> trailers = new AtomicReference<>();
        DynamicMessage reply = callWithBinaryMetadata(traceBytes, headers, trailers);

        assertThat((String) reply.getField(responseType.findFieldByName("greeting")), is("Hello World"));

        // (2) the wire really carried the unpadded form
        org.mockserver.model.HttpRequest[] recorded =
            mockServerClient.retrieveRecordedRequests(request().withPath("/" + SERVICE + "/" + METHOD));
        assertThat("the request must have been recorded", recorded.length, is(1));
        assertThat(
            "grpc-java must put the UNPADDED base64 on the wire — if this ever becomes 'AQIDBA=='"
                + " the padding-insensitive matching below is no longer what makes the test pass",
            recorded[0].getFirstHeader(TRACE_BIN_HEADER), is("AQIDBA"));

        // (3) the return leg: grpc-java decodes MockServer's padded pass-through back to the bytes
        byte[] returned = binaryMetadataValue(headers.get(), trailers.get());
        assertThat("the -bin response value must reach the client's binary marshaller", returned, notNullValue());
        assertThat("a padded value passed straight through must decode to the original bytes",
            returned, is(traceBytes));
    }

    /**
     * The mirror of the case above: an expectation written in the unpadded form matches too, and an
     * unpadded response value also decodes cleanly on the client. Together the two tests prove the
     * user may write either spelling and MockServer behaves identically — which is the whole point
     * of the padding-insensitive comparison.
     */
    @Test
    public void shouldMatchUnpaddedBinaryMetadataExpectationAndReturnUnpaddedValueToRealClient() {
        byte[] traceBytes = {1, 2, 3, 4};
        String unpadded = "AQIDBA";

        mockServerClient
            .when(
                request()
                    .withPath("/" + SERVICE + "/" + METHOD)
                    .withHeader(TRACE_BIN_HEADER, unpadded)
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status", "0")
                    .withHeader(TRACE_BIN_HEADER, unpadded)
                    .withBody("{\"greeting\":\"Hello World\"}")
            );

        AtomicReference<Metadata> headers = new AtomicReference<>();
        AtomicReference<Metadata> trailers = new AtomicReference<>();
        DynamicMessage reply = callWithBinaryMetadata(traceBytes, headers, trailers);

        assertThat((String) reply.getField(responseType.findFieldByName("greeting")), is("Hello World"));
        assertThat("an unpadded pass-through must also decode to the original bytes",
            binaryMetadataValue(headers.get(), trailers.get()), is(traceBytes));
    }

    /**
     * Padding-insensitivity must not degrade into value-insensitivity: different bytes still fail to
     * match, and the client sees a real gRPC error rather than a fabricated success.
     */
    @Test
    public void shouldNotMatchBinaryMetadataExpectationWithDifferentBytes() {
        mockServerClient
            .when(
                request()
                    .withPath("/" + SERVICE + "/" + METHOD)
                    .withHeader(TRACE_BIN_HEADER, Base64.getEncoder().encodeToString(new byte[]{9, 9, 9, 9}))
            )
            .respond(response().withStatusCode(200).withHeader("grpc-status", "0").withBody("{\"greeting\":\"nope\"}"));

        try {
            callWithBinaryMetadata(new byte[]{1, 2, 3, 4}, new AtomicReference<>(), new AtomicReference<>());
            throw new AssertionError("a -bin expectation for different bytes must not match");
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode(), is(Status.Code.UNIMPLEMENTED));
        }
    }

    /**
     * Makes a unary call carrying {@code x-trace-bin} as a real binary metadata key, capturing the
     * response headers and trailers so the return leg can be asserted.
     */
    private DynamicMessage callWithBinaryMetadata(byte[] value, AtomicReference<Metadata> headers, AtomicReference<Metadata> trailers) {
        Metadata metadata = new Metadata();
        metadata.put(TRACE_BIN, value);
        Channel intercepted = ClientInterceptors.intercept(
            channel,
            MetadataUtils.newAttachHeadersInterceptor(metadata),
            MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));
        return ClientCalls.blockingUnaryCall(intercepted, grpcMethod, CallOptions.DEFAULT, helloRequest("World"));
    }

    /**
     * Reads {@code x-trace-bin} through grpc-java's binary marshaller from whichever of the response
     * headers or trailers carried it — a unary response may collapse to Trailers-Only.
     */
    private byte[] binaryMetadataValue(Metadata headers, Metadata trailers) {
        if (headers != null && headers.get(TRACE_BIN) != null) {
            return headers.get(TRACE_BIN);
        }
        return trailers != null ? trailers.get(TRACE_BIN) : null;
    }

    // ---- custom response metadata and trailing metadata, read off a real client's Metadata ----

    /**
     * A mocked gRPC response may carry <strong>both</strong> custom response metadata (initial
     * headers) and custom trailing metadata (the terminal trailing HEADERS frame), and a real
     * client must be able to read each from the side it was authored on.
     * <p>
     * <strong>Why only a real client can prove this.</strong> The two sides are authored
     * differently — {@code withHeader(...)} versus {@code withTrailer(...)} — but by the time the
     * response leaves {@code GrpcToHttpResponseHandler} both are just entries on one model object.
     * A handler-level or {@code EmbeddedChannel} assertion inspects that model (or at best the
     * Netty objects MockServer chose to emit) and therefore cannot distinguish "the trailer was
     * emitted as a trailer" from "the trailer was folded into the initial headers", nor catch a
     * custom trailer being dropped by {@code setGrpcTrailers}/{@code removeGrpcTrailers} while
     * {@code grpc-status} still arrives and the call still completes green. Both mistakes are
     * invisible to every existing gRPC test: the {@code -bin} metadata tests above deliberately
     * accept the value from <em>either</em> headers or trailers, because a body-less unary response
     * may legitimately collapse to Trailers-Only.
     * <p>
     * This test pins the discriminating shape. The response has a body, so no Trailers-Only
     * collapse applies and the two sides are genuinely distinct on the wire:
     * <ol>
     *   <li>the custom response metadata must arrive in the initial headers and <strong>not</strong>
     *       in the trailers;</li>
     *   <li>the custom trailing metadata must arrive in the trailers and <strong>not</strong> in the
     *       initial headers;</li>
     *   <li>both values must round-trip byte-for-byte, including a value carrying {@code =},
     *       {@code ;}, {@code ,} and spaces — the punctuation a checksum/quota style header really
     *       uses, and exactly what a naive value split or re-serialization corrupts.</li>
     * </ol>
     */
    @Test
    public void shouldDeliverCustomResponseAndTrailingMetadataToRealGrpcClient() {
        mockServerClient
            .when(
                request()
                    .withPath("/" + SERVICE + "/" + METHOD)
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status", "0")
                    .withHeader(RESPONSE_ID_HEADER, RESPONSE_ID_VALUE)
                    .withTrailer(CHECKSUM_TRAILER, CHECKSUM_VALUE)
                    .withBody("{\"greeting\":\"Hello World\"}")
            );

        AtomicReference<Metadata> headers = new AtomicReference<>();
        AtomicReference<Metadata> trailers = new AtomicReference<>();
        DynamicMessage reply = callCapturingMetadata(headers, trailers);

        assertThat((String) reply.getField(responseType.findFieldByName("greeting")), is("Hello World"));

        assertThat("the client must have received initial response headers", headers.get(), notNullValue());
        assertThat("the client must have received trailing metadata", trailers.get(), notNullValue());

        assertThat("custom response metadata must reach the client's initial headers, exactly as authored",
            headers.get().get(RESPONSE_ID), is(RESPONSE_ID_VALUE));
        assertThat("custom trailing metadata must reach the client's trailers, exactly as authored",
            trailers.get().get(CHECKSUM), is(CHECKSUM_VALUE));

        assertThat("a header authored with withHeader must NOT be emitted as a trailer",
            trailers.get().get(RESPONSE_ID), nullValue());
        assertThat("a trailer authored with withTrailer must NOT be folded into the initial headers",
            headers.get().get(CHECKSUM), nullValue());
    }

    /**
     * Trailing metadata on a <em>failed</em> call must reach the client too — that is the channel
     * gRPC error details ride on, so a dropped trailer here is silent loss of the only diagnostic
     * the caller gets.
     * <p>
     * The status is authored as a header while the custom metadata is authored as a trailer, which
     * also proves the two are handled independently: {@code setGrpcTrailers} rewrites the
     * {@code grpc-status}/{@code grpc-message} trailers on every response, and must leave any
     * user-authored trailer alongside them untouched.
     * <p>
     * Read through {@link StatusRuntimeException#getTrailers()} rather than a capturing
     * interceptor: a body-less error response is free to collapse to Trailers-Only, and grpc-java
     * surfaces the terminal metadata on the exception in both shapes.
     */
    @Test
    public void shouldDeliverCustomTrailingMetadataAlongsideAFailedGrpcStatus() {
        mockServerClient
            .when(
                request()
                    .withPath("/" + SERVICE + "/" + METHOD)
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status-name", "RESOURCE_EXHAUSTED")
                    .withHeader("grpc-message", "monthly quota reached")
                    .withTrailer(CHECKSUM_TRAILER, CHECKSUM_VALUE)
            );

        StatusRuntimeException e = callExpectingFailure();

        assertThat(e.getStatus().getCode(), is(Status.Code.RESOURCE_EXHAUSTED));
        assertThat(e.getStatus().getDescription(), is("monthly quota reached"));
        assertThat("a failed call must still carry its trailing metadata", e.getTrailers(), notNullValue());
        assertThat("custom trailing metadata must survive alongside a non-OK grpc-status, exactly as authored",
            e.getTrailers().get(CHECKSUM), is(CHECKSUM_VALUE));
    }

    /**
     * Makes a unary call with no request metadata, capturing the response headers and trailers the
     * client receives so each side can be asserted separately.
     */
    private DynamicMessage callCapturingMetadata(AtomicReference<Metadata> headers, AtomicReference<Metadata> trailers) {
        Channel intercepted = ClientInterceptors.intercept(
            channel,
            MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));
        return ClientCalls.blockingUnaryCall(intercepted, grpcMethod, CallOptions.DEFAULT, helloRequest("World"));
    }

    // ---- grpc-message percent-encoding, verified through a real client's decoder ----

    /**
     * A literal {@code %} in {@code grpc-message} must survive the client's percent-decode.
     * <p>
     * The message here echoes user input containing {@code %41}. Unencoded, the client decodes that
     * escape and receives {@code "invalid escape A in pattern"} — silent corruption of a
     * plain-ASCII message, no exotic characters involved.
     * <p>
     * Note grpc-java decodes leniently, so a {@code %} NOT followed by two hex digits (for example
     * {@code "quota 50% exceeded"}) happens to survive unencoded; that variant therefore cannot
     * distinguish encoded from unencoded output, and a stricter client would still reject it.
     * This test deliberately uses the form that genuinely round-trips wrong.
     */
    @Test
    public void shouldDeliverGrpcMessageContainingPercentIntactToRealClient() {
        String message = "invalid escape %41 in pattern, quota 50% exceeded";
        expectStatus("RESOURCE_EXHAUSTED", message);

        StatusRuntimeException e = callExpectingFailure();
        assertThat(e.getStatus().getCode(), is(Status.Code.RESOURCE_EXHAUSTED));
        assertThat(e.getStatus().getDescription(), is(message));
    }

    /**
     * Non-ASCII must arrive intact. Unencoded it is byte-cast to ISO-8859-1 on the wire and
     * grpc-java's UTF-8 decode produces mojibake.
     */
    @Test
    public void shouldDeliverNonAsciiGrpcMessageIntactToRealClient() {
        String message = "paiement refusé — solde insuffisant";
        expectStatus("FAILED_PRECONDITION", message);

        StatusRuntimeException e = callExpectingFailure();
        assertThat(e.getStatus().getCode(), is(Status.Code.FAILED_PRECONDITION));
        assertThat(e.getStatus().getDescription(), is(message));
    }

    /**
     * Multi-line messages must not corrupt the trailer block.
     */
    @Test
    public void shouldDeliverMultiLineGrpcMessageIntactToRealClient() {
        String message = "validation failed:\n - name is required\n - age must be positive";
        expectStatus("INVALID_ARGUMENT", message);

        StatusRuntimeException e = callExpectingFailure();
        assertThat(e.getStatus().getCode(), is(Status.Code.INVALID_ARGUMENT));
        assertThat(e.getStatus().getDescription(), is(message));
    }

    /**
     * CRLF injection: a {@code grpc-message} carrying {@code "\r\ngrpc-status: 0"} must not be able
     * to inject a second status and turn an error into a success. The client must still see the
     * authored error status, with the injection text preserved as literal message content.
     */
    @Test
    public void shouldNotAllowGrpcMessageToInjectASecondStatus() {
        String injection = "denied\r\ngrpc-status: 0\r\n";
        expectStatus("PERMISSION_DENIED", injection);

        StatusRuntimeException e = callExpectingFailure();
        assertThat("an injected grpc-status must not downgrade the error to OK",
            e.getStatus().getCode(), is(Status.Code.PERMISSION_DENIED));
        assertThat(e.getStatus().getDescription(), is(injection));
    }

    // ---- grpc-timeout ----

    /**
     * End-to-end smoke test: a client deadline shorter than the expectation's delay surfaces as
     * DEADLINE_EXCEEDED, and grpc-java turns {@code withDeadlineAfter} into the {@code grpc-timeout}
     * header MockServer reads.
     * <p>
     * <strong>This test cannot prove the server terminated the RPC.</strong> grpc-java raises
     * DEADLINE_EXCEEDED locally at the same instant regardless of what the server sends, so this
     * passes even with no server-side enforcement at all. The server-side behaviour is pinned by
     * {@code GrpcToHttpResponseHandlerTest.shouldWriteDeadlineExceededToTheWireForUnaryHttp2}
     * / {@code ...Http11}, which assert the trailer actually reaches the outbound queue.
     */
    @Test
    public void shouldReturnDeadlineExceededWhenDelayOutlastsTheClientDeadline() {
        mockServerClient
            .when(request().withPath("/" + SERVICE + "/" + METHOD))
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("{\"greeting\":\"too late\"}")
                    .withDelay(TimeUnit.SECONDS, 10));

        try {
            ClientCalls.blockingUnaryCall(
                channel, grpcMethod,
                CallOptions.DEFAULT.withDeadlineAfter(1, TimeUnit.SECONDS),
                helloRequest("World"));
            throw new AssertionError("expected DEADLINE_EXCEEDED");
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode(), is(Status.Code.DEADLINE_EXCEEDED));
        }
    }

    /**
     * A deadline comfortably longer than the delay must not fire — the real response wins and the
     * timer is cancelled.
     */
    @Test
    public void shouldServeNormallyWhenTheDeadlineIsNotExceeded() {
        mockServerClient
            .when(request().withPath("/" + SERVICE + "/" + METHOD))
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("{\"greeting\":\"in time\"}"));

        DynamicMessage reply = ClientCalls.blockingUnaryCall(
            channel, grpcMethod,
            CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS),
            helloRequest("World"));

        assertThat((String) reply.getField(responseType.findFieldByName("greeting")), is("in time"));
    }

    private void expectStatus(String statusName, String message) {
        mockServerClient
            .when(request().withPath("/" + SERVICE + "/" + METHOD))
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status-name", statusName)
                    .withHeader("grpc-message", message));
    }

    private StatusRuntimeException callExpectingFailure() {
        try {
            DynamicMessage reply = ClientCalls.blockingUnaryCall(
                channel, grpcMethod, CallOptions.DEFAULT, helloRequest("World"));
            throw new AssertionError("expected a StatusRuntimeException but got: " + reply);
        } catch (StatusRuntimeException e) {
            return e;
        }
    }

    // ---- mid-stream deadline cancellation (server streaming) ----

    /**
     * A <strong>server-streaming</strong> RPC whose per-message delays outlast the client's
     * deadline must be terminated mid-stream with DEADLINE_EXCEEDED, and must not deliver messages
     * after that terminal trailer.
     * <p>
     * The expectation streams five messages five seconds apart (25s total) against a 2s deadline,
     * so termination necessarily lands between messages while the emission chain is still pending —
     * the interleaving case, not merely "the deadline beat the whole response".
     * <p>
     * <strong>Scope of what this proves.</strong> The status alone does not distinguish server-side
     * termination from the client giving up — grpc-java reports DEADLINE_EXCEEDED either way — and
     * with messages 5s apart against a 2s deadline the message-count bound holds trivially. This is
     * an end-to-end smoke test that the streaming path works and surfaces the status; the actual
     * mid-stream termination (exactly one terminal trailer, no message after it) is pinned by
     * {@code GrpcStreamDeadlineTest}, which observes the written frames directly.
     */
    @Test
    public void shouldTerminateServerStreamingMidStreamWhenDeadlineElapses() {
        mockServerClient
            .when(request().withPath("/" + SERVICE + "/" + STREAM_METHOD))
            .respondWithGrpcStream(
                org.mockserver.model.GrpcStreamResponse.grpcStreamResponse()
                    .withStatusName("OK")
                    .withMessage("{\"greeting\":\"one\"}", new org.mockserver.model.Delay(TimeUnit.SECONDS, 5))
                    .withMessage("{\"greeting\":\"two\"}", new org.mockserver.model.Delay(TimeUnit.SECONDS, 5))
                    .withMessage("{\"greeting\":\"three\"}", new org.mockserver.model.Delay(TimeUnit.SECONDS, 5))
                    .withMessage("{\"greeting\":\"four\"}", new org.mockserver.model.Delay(TimeUnit.SECONDS, 5))
                    .withMessage("{\"greeting\":\"five\"}", new org.mockserver.model.Delay(TimeUnit.SECONDS, 5)));

        MethodDescriptor<DynamicMessage, DynamicMessage> streamingMethod = MethodDescriptor
            .<DynamicMessage, DynamicMessage>newBuilder()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, STREAM_METHOD))
            .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(requestType)))
            .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(responseType)))
            .build();

        List<DynamicMessage> received = new ArrayList<>();
        try {
            Iterator<DynamicMessage> replies = ClientCalls.blockingServerStreamingCall(
                channel, streamingMethod,
                CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                helloRequest("World"));
            while (replies.hasNext()) {
                received.add(replies.next());
            }
            throw new AssertionError("expected DEADLINE_EXCEEDED, but the stream completed with "
                + received.size() + " messages");
        } catch (StatusRuntimeException e) {
            assertThat("the server must terminate the stream, not the client give up locally",
                e.getStatus().getCode(), is(Status.Code.DEADLINE_EXCEEDED));
            assertThat("no message may arrive after the terminal trailer — a 2s deadline against"
                    + " 5s-apart messages can deliver at most the first",
                received.size(), lessThanOrEqualTo(1));
        }
    }

    /**
     * A streaming RPC that completes within the deadline must be unaffected: every message is
     * delivered and the deadline timer is cancelled rather than firing afterwards.
     */
    @Test
    public void shouldCompleteServerStreamingNormallyWhenWithinDeadline() {
        mockServerClient
            .when(request().withPath("/" + SERVICE + "/" + STREAM_METHOD))
            .respondWithGrpcStream(
                org.mockserver.model.GrpcStreamResponse.grpcStreamResponse()
                    .withStatusName("OK")
                    .withMessage("{\"greeting\":\"one\"}")
                    .withMessage("{\"greeting\":\"two\"}")
                    .withMessage("{\"greeting\":\"three\"}"));

        MethodDescriptor<DynamicMessage, DynamicMessage> streamingMethod = MethodDescriptor
            .<DynamicMessage, DynamicMessage>newBuilder()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, STREAM_METHOD))
            .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(requestType)))
            .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(responseType)))
            .build();

        List<DynamicMessage> received = new ArrayList<>();
        Iterator<DynamicMessage> replies = ClientCalls.blockingServerStreamingCall(
            channel, streamingMethod,
            CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS),
            helloRequest("World"));
        while (replies.hasNext()) {
            received.add(replies.next());
        }

        assertThat(received.size(), is(3));
        assertThat((String) received.get(2).getField(responseType.findFieldByName("greeting")), is("three"));
    }
}
