package org.mockserver.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors;
import org.mockserver.model.BinaryBody;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Translates a MockServer-internal gRPC exchange (the JSON representation that
 * {@code GrpcToHttpRequestHandler} produces after decoding an inbound protobuf request) to and from
 * the wire form a real upstream gRPC server expects, so that a matched {@code FORWARD}-class
 * expectation — or the anonymous proxy no-match path — can relay a gRPC call to an upstream service
 * and record the decoded exchange.
 *
 * <p>Two symmetric transforms:
 * <ul>
 *   <li>{@link #encodeRequestForUpstream(HttpRequest, GrpcProtoDescriptorStore)} — turns the JSON
 *       request body (single message, or a JSON array for client-streaming) back into gRPC
 *       length-prefixed protobuf frames, sets {@code content-type: application/grpc}, forces
 *       HTTP/2, adds {@code te: trailers}, and strips the internal {@code x-grpc-*} helper headers
 *       so they do not leak upstream.</li>
 *   <li>{@link #decodeResponseFromUpstream(HttpResponse, String, String, GrpcProtoDescriptorStore)}
 *       — decodes the upstream's gRPC-framed protobuf response back to JSON and re-stamps
 *       {@code x-grpc-service}/{@code x-grpc-method} (and {@code grpc-status-name}) onto the
 *       response. The JSON body makes the logged {@code FORWARDED_REQUEST} entry replayable as a
 *       mock, and the stamped headers let {@code GrpcToHttpResponseHandler} re-frame the response to
 *       protobuf for the calling gRPC client.</li>
 * </ul>
 *
 * <p>Both transforms are fail-safe: when the request is not a decoded gRPC exchange, the descriptor
 * store has no matching method, or any conversion error occurs, the original message is returned
 * unchanged so ordinary HTTP forwarding is never disrupted.
 *
 * <p><b>Boundary:</b> unary and client-streaming request bodies (single JSON object / JSON array)
 * and unary or server-streaming responses (one or more frames) are handled. Full bidirectional
 * streaming forward is out of scope here — it is driven by the multiplex bidi pipeline, not the
 * request/response forward path.
 */
public class GrpcForwardTranslator {

    public static final String SERVICE_HEADER = "x-grpc-service";
    public static final String METHOD_HEADER = "x-grpc-method";
    public static final String ORIGINAL_CONTENT_TYPE_HEADER = "x-grpc-original-content-type";
    public static final String CLIENT_STREAMING_HEADER = "x-grpc-client-streaming";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GrpcForwardTranslator() {
    }

    /**
     * Whether {@code request} is a decoded gRPC exchange eligible for upstream re-encoding — it must
     * carry a non-empty {@code x-grpc-service} and {@code x-grpc-method} and an {@code application/grpc}
     * content-type (the shape {@code GrpcToHttpRequestHandler} produces).
     */
    public static boolean isGrpcForwardRequest(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String service = request.getFirstHeader(SERVICE_HEADER);
        String method = request.getFirstHeader(METHOD_HEADER);
        return service != null && !service.isEmpty()
            && method != null && !method.isEmpty()
            && GrpcStatusMapper.isGrpcContentType(request.getFirstHeader("content-type"));
    }

    /**
     * Re-encodes a decoded gRPC request's JSON body back to gRPC-framed protobuf for an upstream call.
     * Returns the original request unchanged when it is not an eligible gRPC exchange, when the store
     * has no matching method descriptor, or when conversion fails.
     */
    public static HttpRequest encodeRequestForUpstream(HttpRequest request, GrpcProtoDescriptorStore store) {
        if (store == null || !store.hasServices() || !isGrpcForwardRequest(request)) {
            return request;
        }
        String service = request.getFirstHeader(SERVICE_HEADER);
        String method = request.getFirstHeader(METHOD_HEADER);
        Descriptors.MethodDescriptor methodDescriptor = store.getMethod(service, method);
        if (methodDescriptor == null) {
            return request;
        }
        try {
            String bodyString = request.getBodyAsString();
            GrpcJsonMessageConverter converter = store.getConverter();
            byte[] framed;
            if (bodyString == null || bodyString.isEmpty()) {
                // no body (e.g. an empty unary request) — send a zero-length gRPC frame
                framed = GrpcFrameCodec.encode(new byte[0]);
            } else if (isJsonArray(bodyString)) {
                // client-streaming: one frame per JSON array element
                ByteArrayOutputStream frames = new ByteArrayOutputStream();
                JsonNode array = OBJECT_MAPPER.readTree(bodyString);
                for (JsonNode element : array) {
                    byte[] protobuf = converter.toProtobuf(element.toString(), methodDescriptor.getInputType());
                    frames.write(GrpcFrameCodec.encode(protobuf));
                }
                framed = frames.toByteArray();
            } else {
                byte[] protobuf = converter.toProtobuf(bodyString, methodDescriptor.getInputType());
                framed = GrpcFrameCodec.encode(protobuf);
            }
            return request
                .clone()
                .withBody(new BinaryBody(framed))
                .replaceHeader(new Header("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE))
                .withHeader("te", "trailers")
                .withProtocol(Protocol.HTTP_2)
                .removeHeader(SERVICE_HEADER)
                .removeHeader(METHOD_HEADER)
                .removeHeader(ORIGINAL_CONTENT_TYPE_HEADER)
                .removeHeader(CLIENT_STREAMING_HEADER);
        } catch (Exception e) {
            // fail-safe: leave the request unchanged rather than break the forward
            return request;
        }
    }

    /**
     * Decodes an upstream gRPC-framed protobuf response back to JSON and stamps the
     * {@code x-grpc-service}/{@code x-grpc-method} headers (and {@code grpc-status-name}) so the
     * response is both replayable (JSON body in the recorded exchange) and re-framable to protobuf by
     * {@code GrpcToHttpResponseHandler} for the calling client. Returns the original response when the
     * store has no matching method descriptor or when conversion fails.
     */
    public static HttpResponse decodeResponseFromUpstream(HttpResponse response, String service, String method, GrpcProtoDescriptorStore store) {
        if (response == null || store == null || !store.hasServices()
            || service == null || service.isEmpty() || method == null || method.isEmpty()) {
            return response;
        }
        Descriptors.MethodDescriptor methodDescriptor = store.getMethod(service, method);
        if (methodDescriptor == null) {
            return response;
        }
        try {
            byte[] body = response.getBodyAsRawBytes();
            GrpcJsonMessageConverter converter = store.getConverter();
            String json;
            if (body == null || body.length == 0) {
                json = "";
            } else {
                List<byte[]> messages = GrpcFrameCodec.decode(body);
                if (messages.isEmpty()) {
                    json = "";
                } else if (messages.size() == 1) {
                    json = converter.toJson(messages.get(0), methodDescriptor.getOutputType());
                } else {
                    StringBuilder array = new StringBuilder("[");
                    for (int i = 0; i < messages.size(); i++) {
                        if (i > 0) {
                            array.append(",");
                        }
                        array.append(converter.toJson(messages.get(i), methodDescriptor.getOutputType()));
                    }
                    array.append("]");
                    json = array.toString();
                }
            }
            HttpResponse decoded = response.clone()
                .withBody(json)
                .withHeader(SERVICE_HEADER, service)
                .withHeader(METHOD_HEADER, method);
            // carry the upstream gRPC status through as a status-name so GrpcToHttpResponseHandler
            // re-emits the correct grpc-status trailer when re-framing for the client
            String grpcStatus = response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER);
            if (grpcStatus != null && !grpcStatus.isEmpty()) {
                try {
                    decoded.withHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER,
                        GrpcStatusMapper.fromCode(Integer.parseInt(grpcStatus.trim())).name());
                } catch (NumberFormatException ignored) {
                    // non-numeric grpc-status (protocol violation) — leave the status-name unset
                }
            }
            return decoded;
        } catch (Exception e) {
            // fail-safe: leave the response unchanged rather than break the forward
            return response;
        }
    }

    private static boolean isJsonArray(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.trim();
        return !trimmed.isEmpty() && trimmed.charAt(0) == '[';
    }
}
