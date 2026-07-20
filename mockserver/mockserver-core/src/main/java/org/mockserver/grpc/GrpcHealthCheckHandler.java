package org.mockserver.grpc;

import java.nio.charset.StandardCharsets;

/**
 * Handles grpc.health.v1.Health/Check requests without a proto descriptor.
 * Decodes the HealthCheckRequest manually (field 1 = service string),
 * looks up the ServingStatus from GrpcHealthRegistry, and encodes the
 * HealthCheckResponse manually (field 1 = status enum varint).
 */
public class GrpcHealthCheckHandler {

    public static final String HEALTH_CHECK_PATH = "/grpc.health.v1.Health/Check";

    private final GrpcHealthRegistry registry;

    public GrpcHealthCheckHandler(GrpcHealthRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns true if the request path matches the health check path.
     */
    public boolean isHealthCheckRequest(String path) {
        return HEALTH_CHECK_PATH.equals(path);
    }

    /**
     * Returns the ServingStatus for the given service name from this handler's registry.
     * Delegates to the registry so tests using a non-singleton registry get correct results.
     */
    public ServingStatus getStatus(String serviceName) {
        return registry.getStatus(serviceName);
    }

    /**
     * Returns whether {@code Check} for this service name should succeed at all.
     *
     * <p>{@code grpc.health.v1.Health/Check} is specified to fail the RPC with status
     * {@code NOT_FOUND} when the client asks about a service the server does not know about,
     * rather than returning a {@code HealthCheckResponse}. Callers must consult this before
     * {@link #getStatus} and, when it returns false, respond with a {@code NOT_FOUND} status
     * and no message body.</p>
     */
    public boolean isRegistered(String serviceName) {
        return registry.isRegistered(serviceName);
    }

    /**
     * Decodes a HealthCheckRequest from gRPC-framed bytes and returns the service name.
     * Returns empty string if the body is null/empty or cannot be decoded.
     */
    public String decodeServiceName(byte[] grpcFramedBody) {
        if (grpcFramedBody == null || grpcFramedBody.length < 5) {
            return "";
        }
        // skip 5-byte gRPC frame header
        int offset = 5;
        if (grpcFramedBody.length <= offset) {
            return "";
        }
        // parse protobuf: field 1, wire type 2 (length-delimited)
        try {
            int fieldTag = grpcFramedBody[offset] & 0xFF;
            if (fieldTag != 0x0A) { // field 1, wire type 2
                return ""; // no service field — HealthCheckRequest with empty service
            }
            offset++;
            int len = 0;
            int shift = 0;
            while (offset < grpcFramedBody.length) {
                int b = grpcFramedBody[offset++] & 0xFF;
                len |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            if (offset + len > grpcFramedBody.length) {
                return "";
            }
            return new String(grpcFramedBody, offset, len, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Encodes a HealthCheckResponse as gRPC-framed bytes.
     * HealthCheckResponse { ServingStatus status = 1; }
     */
    public byte[] encodeResponse(ServingStatus status) {
        // protobuf: field 1, wire type 0 (varint)
        byte[] proto;
        int statusCode = status.getCode();
        if (statusCode == 0) {
            proto = new byte[0]; // default value — omit field (proto3 default)
        } else {
            proto = new byte[]{0x08, (byte) statusCode}; // field 1 = 0x08, varint
        }
        // gRPC frame: 1 byte compression flag (0) + 4 bytes big-endian message length
        byte[] framed = new byte[5 + proto.length];
        framed[0] = 0; // no compression
        framed[1] = (byte) ((proto.length >> 24) & 0xFF);
        framed[2] = (byte) ((proto.length >> 16) & 0xFF);
        framed[3] = (byte) ((proto.length >> 8) & 0xFF);
        framed[4] = (byte) (proto.length & 0xFF);
        System.arraycopy(proto, 0, framed, 5, proto.length);
        return framed;
    }
}
