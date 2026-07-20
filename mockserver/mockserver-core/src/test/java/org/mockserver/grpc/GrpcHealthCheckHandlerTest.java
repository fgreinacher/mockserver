package org.mockserver.grpc;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GrpcHealthCheckHandlerTest {

    private GrpcHealthRegistry registry;
    private GrpcHealthCheckHandler handler;

    @Before
    public void setUp() {
        registry = new GrpcHealthRegistry();
        handler = new GrpcHealthCheckHandler(registry);
    }

    // --- isHealthCheckRequest ---

    @Test
    public void shouldMatchHealthCheckPath() {
        assertThat(handler.isHealthCheckRequest("/grpc.health.v1.Health/Check"), is(true));
    }

    @Test
    public void shouldNotMatchOtherPath() {
        assertThat(handler.isHealthCheckRequest("/other.Service/Method"), is(false));
    }

    @Test
    public void shouldNotMatchNullPath() {
        assertThat(handler.isHealthCheckRequest(null), is(false));
    }

    @Test
    public void shouldNotMatchEmptyPath() {
        assertThat(handler.isHealthCheckRequest(""), is(false));
    }

    // --- decodeServiceName ---

    @Test
    public void shouldDecodeServiceNameFromHealthCheckRequest() {
        // Encode "my.Service" in a HealthCheckRequest
        byte[] serviceBytes = "my.Service".getBytes(StandardCharsets.UTF_8);
        // protobuf: field 1 (0x0A), varint length, then bytes
        byte[] proto = new byte[2 + serviceBytes.length];
        proto[0] = 0x0A; // field 1, wire type 2
        proto[1] = (byte) serviceBytes.length;
        System.arraycopy(serviceBytes, 0, proto, 2, serviceBytes.length);
        // gRPC frame: 5 header bytes + proto
        byte[] framed = new byte[5 + proto.length];
        framed[4] = (byte) proto.length;
        System.arraycopy(proto, 0, framed, 5, proto.length);

        assertThat(handler.decodeServiceName(framed), is("my.Service"));
    }

    /**
     * The length prefix is a protobuf VARINT, so a service name of 128 bytes or more is encoded as
     * two bytes, not one. Every fixture above is short enough for a single-byte length, which leaves
     * the varint continuation loop untested -- a decoder that read only one length byte would return
     * a truncated name (and so resolve the wrong service's health status) while staying green.
     */
    @Test
    public void shouldDecodeAServiceNameRequiringAMultiByteVarintLength() {
        StringBuilder name = new StringBuilder("com.example.averylongpackagename.");
        while (name.length() < 300) {
            name.append("segment.");
        }
        name.append("HealthService");

        assertThat("this guard is only meaningful above the one-byte varint boundary",
            name.length(), is(greaterThan(127)));
        assertThat(handler.decodeServiceName(healthCheckRequest(name.toString())), is(name.toString()));
    }

    /**
     * Builds a correctly-framed {@code HealthCheckRequest} using protobuf's own encoder, so the
     * varint length prefix and the 4-byte big-endian frame length are both right by construction.
     */
    private static byte[] healthCheckRequest(String serviceName) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try {
            com.google.protobuf.CodedOutputStream cos = com.google.protobuf.CodedOutputStream.newInstance(baos);
            cos.writeString(1, serviceName);
            cos.flush();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        byte[] proto = baos.toByteArray();
        byte[] framed = new byte[5 + proto.length];
        framed[1] = (byte) ((proto.length >> 24) & 0xFF);
        framed[2] = (byte) ((proto.length >> 16) & 0xFF);
        framed[3] = (byte) ((proto.length >> 8) & 0xFF);
        framed[4] = (byte) (proto.length & 0xFF);
        System.arraycopy(proto, 0, framed, 5, proto.length);
        return framed;
    }

    @Test
    public void shouldDecodeEmptyServiceNameFromEmptyBody() {
        // HealthCheckRequest with no service field = empty body
        byte[] framed = new byte[5]; // just the frame header, empty body
        assertThat(handler.decodeServiceName(framed), is(""));
    }

    @Test
    public void shouldReturnEmptyForNullBody() {
        assertThat(handler.decodeServiceName(null), is(""));
    }

    @Test
    public void shouldReturnEmptyForShortBody() {
        assertThat(handler.decodeServiceName(new byte[3]), is(""));
    }

    @Test
    public void shouldReturnEmptyForUnknownFieldTag() {
        // gRPC frame with a proto that has a different field tag
        byte[] framed = new byte[]{0, 0, 0, 0, 2, 0x10, 0x01}; // field 2 varint instead of field 1 length-delimited
        assertThat(handler.decodeServiceName(framed), is(""));
    }

    // --- encodeResponse ---

    @Test
    public void shouldEncodeServingResponse() {
        byte[] encoded = handler.encodeResponse(ServingStatus.SERVING);
        // should be: 5-byte frame header + proto [0x08, 0x01]
        assertThat(encoded.length, is(7));
        assertThat(encoded[0], is((byte) 0)); // no compression
        assertThat(encoded[4], is((byte) 2)); // message length = 2
        assertThat(encoded[5], is((byte) 0x08)); // field 1, varint
        assertThat(encoded[6], is((byte) 1)); // SERVING = 1
    }

    @Test
    public void shouldEncodeNotServingResponse() {
        byte[] encoded = handler.encodeResponse(ServingStatus.NOT_SERVING);
        assertThat(encoded.length, is(7));
        assertThat(encoded[5], is((byte) 0x08));
        assertThat(encoded[6], is((byte) 2)); // NOT_SERVING = 2
    }

    @Test
    public void shouldEncodeServiceUnknownResponse() {
        byte[] encoded = handler.encodeResponse(ServingStatus.SERVICE_UNKNOWN);
        assertThat(encoded.length, is(7));
        assertThat(encoded[5], is((byte) 0x08));
        assertThat(encoded[6], is((byte) 3)); // SERVICE_UNKNOWN = 3
    }

    @Test
    public void shouldEncodeUnknownStatusWithOmittedField() {
        byte[] encoded = handler.encodeResponse(ServingStatus.UNKNOWN);
        // UNKNOWN = 0 (proto3 default) — field is omitted, empty proto body
        assertThat(encoded.length, is(5)); // just the frame header
        assertThat(encoded[4], is((byte) 0)); // message length = 0
    }

    // --- GrpcHealthRegistry ---

    @Test
    public void shouldReturnServingByDefault() {
        assertThat(registry.getStatus(""), is(ServingStatus.SERVING));
        assertThat(registry.getStatus("any.Service"), is(ServingStatus.SERVING));
    }

    @Test
    public void shouldSetAndGetServiceStatus() {
        registry.setStatus("my.Service", ServingStatus.NOT_SERVING);
        assertThat(registry.getStatus("my.Service"), is(ServingStatus.NOT_SERVING));
        // default should still be SERVING
        assertThat(registry.getStatus("other.Service"), is(ServingStatus.SERVING));
    }

    @Test
    public void shouldSetDefaultStatusViaEmptyServiceName() {
        registry.setStatus("", ServingStatus.NOT_SERVING);
        assertThat(registry.getStatus(""), is(ServingStatus.NOT_SERVING));
        assertThat(registry.getStatus("any.Service"), is(ServingStatus.NOT_SERVING));
    }

    @Test
    public void shouldOverrideServiceStatus() {
        registry.setStatus("my.Service", ServingStatus.NOT_SERVING);
        registry.setStatus("my.Service", ServingStatus.SERVING);
        assertThat(registry.getStatus("my.Service"), is(ServingStatus.SERVING));
    }

    @Test
    public void shouldReset() {
        registry.setStatus("my.Service", ServingStatus.NOT_SERVING);
        registry.setStatus("", ServingStatus.SERVICE_UNKNOWN);
        registry.reset();
        assertThat(registry.getStatus("my.Service"), is(ServingStatus.SERVING));
        assertThat(registry.getStatus(""), is(ServingStatus.SERVING));
    }

    @Test
    public void shouldReturnAllEntries() {
        registry.setStatus("svc1", ServingStatus.NOT_SERVING);
        registry.setStatus("svc2", ServingStatus.SERVICE_UNKNOWN);
        java.util.Map<String, ServingStatus> entries = registry.entries();
        assertThat(entries.size(), is(3)); // svc1, svc2, and default ("")
        assertThat(entries.get("svc1"), is(ServingStatus.NOT_SERVING));
        assertThat(entries.get("svc2"), is(ServingStatus.SERVICE_UNKNOWN));
        assertThat(entries.get(""), is(ServingStatus.SERVING));
    }

    @Test
    public void shouldIgnoreNullServiceName() {
        registry.setStatus(null, ServingStatus.NOT_SERVING);
        assertThat(registry.getStatus(""), is(ServingStatus.SERVING));
    }

    @Test
    public void shouldIgnoreNullStatus() {
        registry.setStatus("my.Service", null);
        assertThat(registry.getStatus("my.Service"), is(ServingStatus.SERVING));
    }

    // --- ServingStatus ---

    @Test
    public void shouldLookUpStatusByCode() {
        assertThat(ServingStatus.forCode(0), is(ServingStatus.UNKNOWN));
        assertThat(ServingStatus.forCode(1), is(ServingStatus.SERVING));
        assertThat(ServingStatus.forCode(2), is(ServingStatus.NOT_SERVING));
        assertThat(ServingStatus.forCode(3), is(ServingStatus.SERVICE_UNKNOWN));
    }

    @Test
    public void shouldReturnUnknownForInvalidCode() {
        assertThat(ServingStatus.forCode(99), is(ServingStatus.UNKNOWN));
        assertThat(ServingStatus.forCode(-1), is(ServingStatus.UNKNOWN));
    }
}
