package org.mockserver.netty.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
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
