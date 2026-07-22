package org.mockserver.netty.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.netty.MockServer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end gRPC <strong>client-streaming</strong> and <strong>bidirectional-streaming</strong>
 * tests driven by a <strong>real grpc-java client</strong> over h2c.
 * <p>
 * These are the two RPC shapes that {@link GrpcUnaryClientIntegrationTest} does not cover. Before
 * this class, client-streaming and bidi were asserted <em>only</em> through {@code EmbeddedChannel}
 * ({@code GrpcClientStreamingMultiplexTest}, {@code GrpcBidiInterleavingMultiplexTest}), which
 * inspect the frames MockServer <em>chose</em> to write — the very seam that let issue #2419 ship
 * for the unary/server-streaming paths. A real {@code io.grpc} client instead deframes the wire
 * itself and rejects a response whose bytes are not a valid length-prefixed protobuf, or whose
 * {@code grpc-status} does not ride in a terminal trailing HEADERS frame. That is the boundary
 * these tests pin.
 * <p>
 * The server runs with {@code grpcBidiStreamingEnabled(true)} — the realistic streaming config and
 * the flag the bidi (multiplex) path requires. Client-streaming ({@code CollectGreetings}) is not a
 * bidi method, so it is still routed to the re-aggregating chain even with the flag on.
 * <p>
 * Like the unary test, this uses {@code DynamicMessage} plus the loaded descriptor set (no protoc
 * step) and a {@code grpc-netty-shaded} channel so grpc's bundled Netty cannot clash with
 * MockServer's.
 */
public class GrpcStreamingClientIntegrationTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String COLLECT_METHOD = "CollectGreetings"; // client-streaming
    private static final String CHAT_METHOD = "Chat";                // bidirectional
    private static final String GREETING_DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private ManagedChannel channel;

    private final Map<String, Descriptors.ServiceDescriptor> services = new LinkedHashMap<>();
    private Descriptors.Descriptor requestType;
    private Descriptors.Descriptor responseType;

    @Before
    public void setUp() throws Exception {
        byte[] greetingDescriptorBytes = Files.readAllBytes(Paths.get(GREETING_DESCRIPTOR));
        registerServices(greetingDescriptorBytes);

        Descriptors.MethodDescriptor collect = method(SERVICE, COLLECT_METHOD);
        requestType = collect.getInputType();
        responseType = collect.getOutputType();

        // grpcBidiStreamingEnabled installs the multiplex HTTP/2 pipeline the bidi path needs.
        mockServer = new MockServer(configuration().grpcBidiStreamingEnabled(true));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient.uploadGrpcDescriptor(greetingDescriptorBytes);

        channel = NettyChannelBuilder
            .forAddress("localhost", mockServer.getLocalPort())
            .usePlaintext()
            .build();
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
     * Client-streaming: the client sends N request messages then half-closes, and the server
     * answers with a single response.
     * <p>
     * A real client sends HEADERS + N DATA frames + END_STREAM; MockServer re-aggregates them and
     * matches one expectation, whose single response must reach the client as one valid
     * length-prefixed protobuf frame with {@code grpc-status: 0} in a terminal trailing HEADERS
     * frame. If the response body were unframed JSON, {@link StreamObserver#onNext} would never
     * fire and grpc-java would raise {@code onError}; if the status did not ride in the trailer,
     * {@code onCompleted} would not fire with OK. Both are asserted on the client-received side.
     */
    @Test
    public void shouldServeClientStreamingRpcToRealGrpcClient() throws Exception {
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath("/" + SERVICE + "/" + COLLECT_METHOD)
                    .withHeader("content-type", "application/grpc")
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status", "0")
                    .withBody("{\"greeting\":\"collected 3 greetings\"}")
            );

        MethodDescriptor<DynamicMessage, DynamicMessage> collect =
            streamingMethodDescriptor(COLLECT_METHOD, MethodDescriptor.MethodType.CLIENT_STREAMING);

        AtomicReference<DynamicMessage> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        ClientCall<DynamicMessage, DynamicMessage> call = channel.newCall(collect, CallOptions.DEFAULT);
        StreamObserver<DynamicMessage> requestObserver = ClientCalls.asyncClientStreamingCall(
            call, collectingObserver(Collections.synchronizedList(new ArrayList<>()), responseRef, errorRef, done, null));

        requestObserver.onNext(helloRequest("Alice"));
        requestObserver.onNext(helloRequest("Bob"));
        requestObserver.onNext(helloRequest("Charlie"));
        requestObserver.onCompleted(); // half-close

        assertThat("the RPC must complete within the timeout", done.await(30, TimeUnit.SECONDS), is(true));
        // a non-zero grpc-status (or a missing terminal trailer) surfaces here as onError
        assertThat("grpc-status 0 must arrive in the terminal trailer (no error)", errorRef.get(), is(nullValue()));
        assertThat("the client must receive exactly one deserialized response", responseRef.get(), is(notNullValue()));
        assertThat("the single response must deserialize against the output type",
            (String) responseRef.get().getField(responseType.findFieldByName("greeting")),
            is("collected 3 greetings"));
    }

    /**
     * Bidirectional streaming: the client interleaves sends and receives on one stream.
     * <p>
     * The server is configured with two rules; the client sends "Alice", waits for the rule-1 reply
     * (proving the server responds <em>before</em> the client half-closes — genuine bidi, not
     * collect-then-respond), then sends "Bob" and half-closes. Each reply is decoded by grpc-java's
     * response marshaller, so a successful {@code onNext} is itself proof the frame was a valid
     * length-prefixed protobuf; a malformed frame would raise {@code onError}. The terminal trailer
     * carrying {@code grpc-status: 0} is proven by {@code onCompleted} firing without error.
     */
    @Test
    public void shouldServeBidiStreamingRpcToRealGrpcClient() throws Exception {
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath("/" + SERVICE + "/" + CHAT_METHOD)
                    .withHeader("content-type", "application/grpc")
            )
            .respondWithGrpcBidi(
                GrpcBidiResponse.grpcBidiResponse()
                    .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                        .withResponse("{\"greeting\":\"Hi Alice\"}"))
                    .withRule(GrpcBidiRule.grpcBidiRule(".*Bob.*")
                        .withResponse("{\"greeting\":\"Hi Bob\"}"))
                    .withStatusName("OK")
            );

        MethodDescriptor<DynamicMessage, DynamicMessage> chat =
            streamingMethodDescriptor(CHAT_METHOD, MethodDescriptor.MethodType.BIDI_STREAMING);

        List<DynamicMessage> received = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch firstReply = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        ClientCall<DynamicMessage, DynamicMessage> call = channel.newCall(chat, CallOptions.DEFAULT);
        StreamObserver<DynamicMessage> requestObserver = ClientCalls.asyncBidiStreamingCall(
            call, collectingObserver(received, new AtomicReference<>(), errorRef, done, firstReply));

        requestObserver.onNext(helloRequest("Alice"));
        assertThat("the server must reply to the first message before the client half-closes",
            firstReply.await(30, TimeUnit.SECONDS), is(true));

        requestObserver.onNext(helloRequest("Bob"));
        requestObserver.onCompleted();

        assertThat("the RPC must complete within the timeout", done.await(30, TimeUnit.SECONDS), is(true));
        assertThat("every response frame must be a valid protobuf and the trailer OK (no error)",
            errorRef.get(), is(nullValue()));

        List<String> greetings = new ArrayList<>();
        for (DynamicMessage message : received) {
            greetings.add((String) message.getField(responseType.findFieldByName("greeting")));
        }
        assertThat("the client must receive both interleaved responses in order",
            greetings, contains("Hi Alice", "Hi Bob"));
    }

    // ---- helpers ----

    /**
     * A response observer that records every received message, the single/last message, any error,
     * and fires the completion latch on terminal error or completion. {@code firstReply} (nullable)
     * is counted down on the first {@code onNext} so a bidi test can gate its next send on a real
     * server reply.
     */
    private StreamObserver<DynamicMessage> collectingObserver(
        List<DynamicMessage> received,
        AtomicReference<DynamicMessage> lastRef,
        AtomicReference<Throwable> errorRef,
        CountDownLatch done,
        CountDownLatch firstReply) {
        return new StreamObserver<DynamicMessage>() {
            @Override
            public void onNext(DynamicMessage value) {
                received.add(value);
                lastRef.set(value);
                if (firstReply != null) {
                    firstReply.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        };
    }

    private DynamicMessage helloRequest(String name) {
        return DynamicMessage.newBuilder(requestType)
            .setField(requestType.findFieldByName("name"), name)
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

    private MethodDescriptor<DynamicMessage, DynamicMessage> streamingMethodDescriptor(
        String methodName, MethodDescriptor.MethodType type) {
        Descriptors.MethodDescriptor methodDescriptor = method(SERVICE, methodName);
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(type)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, methodName))
            .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(methodDescriptor.getInputType())))
            .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(methodDescriptor.getOutputType())))
            .build();
    }

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
}
