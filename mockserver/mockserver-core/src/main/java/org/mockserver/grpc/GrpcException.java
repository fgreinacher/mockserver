package org.mockserver.grpc;

/**
 * A gRPC protocol-level failure, carrying the {@code grpc-status} the client should be told.
 * <p>
 * The status matters: callers previously inferred it by matching on the message text (only
 * {@code "unknown gRPC method"} was special-cased, everything else became {@code INTERNAL}), so a
 * message that exceeded the receive-size limit was reported as {@code INTERNAL} rather than
 * {@code RESOURCE_EXHAUSTED} as the specification and both grpc-java and grpc-go require. Carrying
 * the status on the exception removes the string matching.
 */
public class GrpcException extends RuntimeException {

    private final GrpcStatusMapper.GrpcStatusCode statusCode;

    public GrpcException(String message) {
        this(message, GrpcStatusMapper.GrpcStatusCode.INTERNAL);
    }

    public GrpcException(String message, GrpcStatusMapper.GrpcStatusCode statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public GrpcException(String message, Throwable cause) {
        this(message, cause, GrpcStatusMapper.GrpcStatusCode.INTERNAL);
    }

    public GrpcException(String message, Throwable cause, GrpcStatusMapper.GrpcStatusCode statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * The {@code grpc-status} to report for this failure.
     */
    public GrpcStatusMapper.GrpcStatusCode getStatusCode() {
        return statusCode == null ? GrpcStatusMapper.GrpcStatusCode.INTERNAL : statusCode;
    }
}
