package org.mockserver.netty.grpc;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.grpc.GrpcHealthCheckHandler;
import org.mockserver.grpc.GrpcHealthRegistry;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.ServingStatus;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.HttpQuotaRegistry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.model.HttpRequest.request;

/**
 * Wire-level coverage for {@code grpc.health.v1.Health/Check} in {@link GrpcToHttpRequestHandler}.
 *
 * <h3>What this covers that the core tests cannot</h3>
 * <p>{@code GrpcHealthCheckHandlerTest} covers the decision — whether a service name counts as
 * registered. This class covers what that decision becomes on the wire: a {@code Check} for an
 * unknown service must FAIL the RPC with {@code grpc-status: 5} (NOT_FOUND) and carry no
 * {@code HealthCheckResponse} body, rather than answering {@code SERVING}. The two halves are
 * deliberately separate so that neither can pass on the other's behalf — a registry that
 * correctly reports "unregistered" is worthless if the handler still encodes a healthy response,
 * which is exactly the state this test was written against.</p>
 *
 * <p>The consequence of getting this wrong is that a mistyped service name in a health probe
 * reports healthy, so a test asserting "the dependency is down" passes while proving nothing.</p>
 *
 * <h3>Depends on mockserver-netty running tests sequentially</h3>
 * <p>{@link GrpcHealthRegistry#getInstance()} is a process-wide singleton, and these tests mutate
 * it (resetting around each case). That is safe today only because the mockserver-netty surefire
 * configuration declares no {@code <parallel>} (unlike mockserver-core, which runs
 * {@code parallel=classes}). If netty ever gains a parallel phase, move this class to the
 * sequential phase — concurrent tests would otherwise observe each other's registrations and fail
 * intermittently, or worse, pass for the wrong reason.</p>
 */
public class GrpcToHttpRequestHandlerHealthTest {

    private static final String HEALTH_PATH = "/grpc.health.v1.Health/Check";
    private static final String NOT_FOUND_STATUS =
        String.valueOf(GrpcStatusMapper.GrpcStatusCode.NOT_FOUND.getCode());

    private final GrpcHealthRegistry registry = GrpcHealthRegistry.getInstance();

    @Before
    public void setUp() {
        registry.reset();
    }

    @After
    public void tearDown() {
        registry.reset();
    }

    @Test
    public void shouldFailCheckWithNotFoundForAnUnregisteredService() {
        HttpResponse response = check("never.Registered");

        assertThat(trailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is(NOT_FOUND_STATUS));
    }

    @Test
    public void shouldNotReturnAHealthCheckResponseBodyForAnUnregisteredService() {
        // A NOT_FOUND Check is a failed RPC: there is no HealthCheckResponse message to carry.
        // Asserted separately from the status so that emitting NOT_FOUND alongside a stray
        // SERVING body cannot pass.
        HttpResponse response = check("never.Registered");

        assertThat(response.getBodyAsRawBytes() == null || response.getBodyAsRawBytes().length == 0, is(true));
    }

    @Test
    public void shouldNameTheUnknownServiceInTheGrpcMessage() {
        HttpResponse response = check("never.Registered");

        assertThat(trailer(response, GrpcStatusMapper.GRPC_MESSAGE_HEADER),
            is(GrpcStatusMapper.percentEncodeMessage("unknown service never.Registered")));
    }

    @Test
    public void shouldNotInheritTheOverallStatusForAnUnregisteredService() {
        // Setting the overall (empty-name) status must not make an arbitrary name answerable.
        registry.setStatus("", ServingStatus.NOT_SERVING);

        HttpResponse response = check("typo.Servcie");

        assertThat(trailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is(NOT_FOUND_STATUS));
    }

    // --- the calls that must still succeed ---

    @Test
    public void shouldReturnServingForARegisteredService() {
        registry.setStatus("my.Service", ServingStatus.SERVING);

        HttpResponse response = check("my.Service");

        assertThat(trailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        assertThat(decodeServingStatus(response), is(ServingStatus.SERVING));
    }

    @Test
    public void shouldReturnNotServingForARegisteredServiceThatIsDown() {
        registry.setStatus("my.Service", ServingStatus.NOT_SERVING);

        HttpResponse response = check("my.Service");

        assertThat(trailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        assertThat(decodeServingStatus(response), is(ServingStatus.NOT_SERVING));
    }

    @Test
    public void shouldAnswerTheOverallHealthCheckWithoutRegistration() {
        // The empty service name is the overall-server target and must never be NOT_FOUND.
        HttpResponse response = check("");

        assertThat(trailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        assertThat(decodeServingStatus(response), is(ServingStatus.SERVING));
    }

    @Test
    public void shouldReflectTheOverallStatusOnTheOverallHealthCheck() {
        registry.setStatus("", ServingStatus.NOT_SERVING);

        HttpResponse response = check("");

        assertThat(trailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        assertThat(decodeServingStatus(response), is(ServingStatus.NOT_SERVING));
    }

    // --- helpers ---

    private HttpResponse check(String serviceName) {
        EmbeddedChannel channel = new EmbeddedChannel(new GrpcToHttpRequestHandler(
            new MockServerLogger(),
            new GrpcProtoDescriptorStore(new MockServerLogger()),
            new GrpcHealthCheckHandler(registry),
            new GrpcChaosRegistry(System::currentTimeMillis),
            HttpQuotaRegistry.getInstance()));

        channel.writeInbound(healthCheckRequest(serviceName));

        HttpResponse response = channel.readOutbound();
        assertThat("health check must be answered by the handler, not passed through",
            response, is(notNullValue()));
        return response;
    }

    private HttpRequest healthCheckRequest(String serviceName) {
        return request()
            .withMethod("POST")
            .withPath(HEALTH_PATH)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(encodeHealthCheckRequest(serviceName));
    }

    /** {@code HealthCheckRequest { string service = 1; }}, gRPC length-prefixed. */
    private byte[] encodeHealthCheckRequest(String serviceName) {
        ByteArrayOutputStream proto = new ByteArrayOutputStream();
        if (!serviceName.isEmpty()) {
            byte[] nameBytes = serviceName.getBytes(StandardCharsets.UTF_8);
            proto.write(0x0A); // field 1, wire type 2
            proto.write(nameBytes.length); // fixture names are always < 128 bytes
            proto.write(nameBytes, 0, nameBytes.length);
        }
        byte[] message = proto.toByteArray();
        byte[] framed = new byte[5 + message.length];
        framed[0] = 0; // uncompressed
        framed[1] = (byte) ((message.length >> 24) & 0xFF);
        framed[2] = (byte) ((message.length >> 16) & 0xFF);
        framed[3] = (byte) ((message.length >> 8) & 0xFF);
        framed[4] = (byte) (message.length & 0xFF);
        System.arraycopy(message, 0, framed, 5, message.length);
        return framed;
    }

    /** {@code HealthCheckResponse { ServingStatus status = 1; }}, gRPC length-prefixed. */
    private ServingStatus decodeServingStatus(HttpResponse response) {
        byte[] body = response.getBodyAsRawBytes();
        assertThat("expected a HealthCheckResponse body", body != null && body.length >= 5, is(true));
        if (body.length == 5) {
            return ServingStatus.UNKNOWN; // proto3 default: field omitted
        }
        assertThat("expected field 1 varint", body[5], is((byte) 0x08));
        return ServingStatus.forCode(body[6]);
    }

    private String trailer(HttpResponse response, String name) {
        return GrpcToHttpResponseHandler.firstTrailer(response, name);
    }
}
