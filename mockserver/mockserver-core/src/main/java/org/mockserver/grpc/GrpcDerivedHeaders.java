package org.mockserver.grpc;

import org.mockserver.model.HttpRequest;

/**
 * The {@code x-grpc-*} request headers MockServer <strong>derives itself</strong> from the gRPC
 * request (the {@code :path} and the decoded frame), as opposed to metadata the client sent.
 * <p>
 * <strong>Why this exists.</strong> {@code HttpRequest.withHeader} <em>appends</em> rather than
 * replaces, so a client that sends its own {@code x-grpc-service: evil} leaves the converted request
 * carrying both values — {@code [evil, com.example.GreetingService]}. Header matching is SUB_SET, so
 * an expectation qualified by the spoofed service name would then match a stream that belongs to a
 * different service. Routing itself is driven by the real {@code :path}, so the impact is on
 * matching integrity rather than dispatch, but these headers are documented as server-derived and
 * must actually be.
 * <p>
 * {@link #strip(HttpRequest)} is therefore called immediately before the derived values are set, on
 * every transport — HTTP/1.1 and HTTP/2 ({@code GrpcToHttpRequestHandler}), HTTP/2 bidi
 * ({@code GrpcBidiRouterHandler}) and HTTP/3 ({@code GrpcHttp3Adapter},
 * {@code Http3MockServerHandler}).
 */
public class GrpcDerivedHeaders {

    public static final String SERVICE = "x-grpc-service";
    public static final String METHOD = "x-grpc-method";
    public static final String ORIGINAL_CONTENT_TYPE = "x-grpc-original-content-type";
    public static final String CLIENT_STREAMING = "x-grpc-client-streaming";

    private GrpcDerivedHeaders() {
    }

    /**
     * Removes any client-supplied copy of the derived headers, so the value MockServer sets next is
     * the only one on the request. Returns the same instance for chaining.
     */
    public static HttpRequest strip(HttpRequest request) {
        if (request == null) {
            return null;
        }
        return request
            .removeHeader(SERVICE)
            .removeHeader(METHOD)
            .removeHeader(ORIGINAL_CONTENT_TYPE)
            .removeHeader(CLIENT_STREAMING);
    }
}
