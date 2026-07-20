package org.mockserver.grpc;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Handles gRPC Server Reflection requests without a generated proto stub.
 * Decodes {@code ServerReflectionRequest} and encodes {@code ServerReflectionResponse}
 * manually using protobuf's {@link CodedInputStream}/{@link CodedOutputStream},
 * mirroring the approach used by {@link GrpcHealthCheckHandler}.
 *
 * <p>Supports both the v1 and v1alpha reflection service paths:
 * <ul>
 *   <li>{@code /grpc.reflection.v1.ServerReflection/ServerReflectionInfo}</li>
 *   <li>{@code /grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo}</li>
 * </ul>
 *
 * <h3>Canonical field numbers (from grpc/reflection/v1/reflection.proto)</h3>
 *
 * <b>ServerReflectionRequest:</b>
 * <pre>
 *   field 1 = host (string)
 *   field 3 = file_by_filename (string)        [oneof message_request]
 *   field 4 = file_containing_symbol (string)   [oneof message_request]
 *   field 7 = list_services (string)            [oneof message_request]
 * </pre>
 *
 * <b>ServerReflectionResponse:</b>
 * <pre>
 *   field 1 = valid_host (string)
 *   field 2 = original_request (ServerReflectionRequest)
 *   field 4 = file_descriptor_response (FileDescriptorResponse)  [oneof message_response]
 *   field 6 = list_services_response (ListServiceResponse)       [oneof message_response]
 * </pre>
 *
 * <b>FileDescriptorResponse:</b>
 * <pre>
 *   field 1 = file_descriptor_proto (repeated bytes)
 * </pre>
 *
 * <b>ListServiceResponse:</b>
 * <pre>
 *   field 1 = service (repeated ServiceResponse)
 * </pre>
 *
 * <b>ServiceResponse:</b>
 * <pre>
 *   field 1 = name (string)
 * </pre>
 *
 * <h3>Limitation</h3>
 * <p>The current gRPC path in MockServer is buffered-unary: each HTTP/2 request carries
 * exactly one gRPC message. This implementation therefore handles a single
 * {@code ServerReflectionRequest} per HTTP/2 request, which is sufficient for tools
 * like {@code grpcurl list} and single symbol/file lookups. Fully-interactive
 * bidi-streaming reflection (a long-lived stream with multiple back-and-forth messages)
 * is not supported by the buffered pipeline.</p>
 */
public class GrpcServerReflectionHandler {

    // --- Reflection service paths (v1 and v1alpha) ---
    public static final String REFLECTION_V1_PATH =
        "/grpc.reflection.v1.ServerReflection/ServerReflectionInfo";
    public static final String REFLECTION_V1ALPHA_PATH =
        "/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo";

    // --- ServerReflectionRequest field numbers ---
    public static final int REQ_HOST_FIELD = 1;
    public static final int REQ_FILE_BY_FILENAME_FIELD = 3;
    public static final int REQ_FILE_CONTAINING_SYMBOL_FIELD = 4;
    public static final int REQ_LIST_SERVICES_FIELD = 7;

    // --- ServerReflectionResponse field numbers ---
    public static final int RESP_VALID_HOST_FIELD = 1;
    public static final int RESP_FILE_DESCRIPTOR_RESPONSE_FIELD = 4;
    public static final int RESP_LIST_SERVICES_RESPONSE_FIELD = 6;

    // --- FileDescriptorResponse field numbers ---
    public static final int FDR_FILE_DESCRIPTOR_PROTO_FIELD = 1;

    // --- ListServiceResponse / ServiceResponse field numbers ---
    public static final int LSR_SERVICE_FIELD = 1;
    public static final int SR_NAME_FIELD = 1;

    private final GrpcProtoDescriptorStore descriptorStore;

    public GrpcServerReflectionHandler(GrpcProtoDescriptorStore descriptorStore) {
        this.descriptorStore = descriptorStore;
    }

    /**
     * Returns {@code true} if the given path matches either the v1 or v1alpha
     * gRPC Server Reflection method path.
     */
    public boolean isReflectionRequest(String path) {
        return isReflectionPath(path);
    }

    /**
     * Static variant of {@link #isReflectionRequest(String)} for use from routing
     * code that does not hold a handler instance.
     */
    public static boolean isReflectionPath(String path) {
        return REFLECTION_V1_PATH.equals(path) || REFLECTION_V1ALPHA_PATH.equals(path);
    }

    /**
     * Processes a gRPC-framed {@code ServerReflectionRequest} and returns a gRPC-framed
     * {@code ServerReflectionResponse}.
     *
     * @param grpcFramedBody the raw gRPC-framed request bytes (5-byte header + protobuf)
     * @return gRPC-framed response bytes
     */
    public byte[] handleReflectionRequest(byte[] grpcFramedBody) {
        ReflectionRequest request = decodeRequest(grpcFramedBody);
        byte[] responseProto;

        switch (request.type) {
            case LIST_SERVICES:
                responseProto = encodeListServicesResponse(request.host);
                break;
            case FILE_CONTAINING_SYMBOL:
                responseProto = encodeFileContainingSymbolResponse(request.host, request.argument);
                break;
            case FILE_BY_FILENAME:
                responseProto = encodeFileByFilenameResponse(request.host, request.argument);
                break;
            default:
                // Return an empty response for unsupported request types
                responseProto = encodeErrorResponse(request.host, 12, // UNIMPLEMENTED
                    "unsupported reflection request type");
                break;
        }

        return grpcFrame(responseProto);
    }

    // --- Decoding ---

    ReflectionRequest decodeRequest(byte[] grpcFramedBody) {
        if (grpcFramedBody == null || grpcFramedBody.length < 5) {
            return new ReflectionRequest(RequestType.UNKNOWN, "", "");
        }

        // skip 5-byte gRPC frame header
        byte[] protoBytes = new byte[grpcFramedBody.length - 5];
        System.arraycopy(grpcFramedBody, 5, protoBytes, 0, protoBytes.length);

        String host = "";
        RequestType type = RequestType.UNKNOWN;
        String argument = "";

        try {
            CodedInputStream cis = CodedInputStream.newInstance(protoBytes);
            while (!cis.isAtEnd()) {
                int tag = cis.readTag();
                int fieldNumber = WireFormat.getTagFieldNumber(tag);
                switch (fieldNumber) {
                    case REQ_HOST_FIELD:
                        host = cis.readString();
                        break;
                    case REQ_FILE_BY_FILENAME_FIELD:
                        type = RequestType.FILE_BY_FILENAME;
                        argument = cis.readString();
                        break;
                    case REQ_FILE_CONTAINING_SYMBOL_FIELD:
                        type = RequestType.FILE_CONTAINING_SYMBOL;
                        argument = cis.readString();
                        break;
                    case REQ_LIST_SERVICES_FIELD:
                        type = RequestType.LIST_SERVICES;
                        argument = cis.readString();
                        break;
                    default:
                        cis.skipField(tag);
                        break;
                }
            }
        } catch (IOException e) {
            // malformed protobuf — return UNKNOWN
        }

        return new ReflectionRequest(type, host, argument);
    }

    // --- Encoding ---

    byte[] encodeListServicesResponse(String host) {
        try {
            Map<String, Descriptors.ServiceDescriptor> allServices = descriptorStore.getAllServices();
            // Build the ListServiceResponse message
            byte[] listServiceResponse = encodeListServiceResponse(allServices.keySet());
            // Wrap in ServerReflectionResponse
            return encodeServerReflectionResponse(host, RESP_LIST_SERVICES_RESPONSE_FIELD, listServiceResponse);
        } catch (IOException e) {
            throw new GrpcException("failed to encode list_services reflection response", e);
        }
    }

    byte[] encodeFileContainingSymbolResponse(String host, String symbol) {
        try {
            Descriptors.FileDescriptor fileDescriptor = findFileContainingSymbol(symbol);
            if (fileDescriptor == null) {
                return encodeErrorResponse(host, 5, // NOT_FOUND
                    "symbol not found: " + symbol);
            }
            byte[] fdResponse = encodeFileDescriptorResponse(fileDescriptor);
            return encodeServerReflectionResponse(host, RESP_FILE_DESCRIPTOR_RESPONSE_FIELD, fdResponse);
        } catch (IOException e) {
            throw new GrpcException("failed to encode file_containing_symbol reflection response", e);
        }
    }

    byte[] encodeFileByFilenameResponse(String host, String filename) {
        try {
            Descriptors.FileDescriptor fileDescriptor = findFileByName(filename);
            if (fileDescriptor == null) {
                return encodeErrorResponse(host, 5, // NOT_FOUND
                    "file not found: " + filename);
            }
            byte[] fdResponse = encodeFileDescriptorResponse(fileDescriptor);
            return encodeServerReflectionResponse(host, RESP_FILE_DESCRIPTOR_RESPONSE_FIELD, fdResponse);
        } catch (IOException e) {
            throw new GrpcException("failed to encode file_by_filename reflection response", e);
        }
    }

    // --- Error response ---

    byte[] encodeErrorResponse(String host, int errorCode, String errorMessage) {
        // ErrorResponse: field 1 = error_code (int32), field 2 = error_message (string)
        // ServerReflectionResponse field 5 = error_response
        try {
            ByteArrayOutputStream errorBaos = new ByteArrayOutputStream();
            CodedOutputStream errorCos = CodedOutputStream.newInstance(errorBaos);
            errorCos.writeInt32(1, errorCode);
            errorCos.writeString(2, errorMessage);
            errorCos.flush();
            byte[] errorBytes = errorBaos.toByteArray();

            return encodeServerReflectionResponse(host, 5, errorBytes);
        } catch (IOException e) {
            throw new GrpcException("failed to encode error reflection response", e);
        }
    }

    // --- Helpers ---

    private byte[] encodeServerReflectionResponse(String host, int responseFieldNumber, byte[] responseMessage) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);

        // field 1: valid_host
        if (host != null && !host.isEmpty()) {
            cos.writeString(RESP_VALID_HOST_FIELD, host);
        }

        // oneof message_response
        cos.writeBytes(responseFieldNumber, com.google.protobuf.ByteString.copyFrom(responseMessage));

        cos.flush();
        return baos.toByteArray();
    }

    private byte[] encodeListServiceResponse(Set<String> serviceNames) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);

        for (String name : serviceNames) {
            // Each ServiceResponse is an embedded message in field 1 of ListServiceResponse
            byte[] serviceResponse = encodeServiceResponse(name);
            cos.writeBytes(LSR_SERVICE_FIELD, com.google.protobuf.ByteString.copyFrom(serviceResponse));
        }

        cos.flush();
        return baos.toByteArray();
    }

    private byte[] encodeServiceResponse(String name) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeString(SR_NAME_FIELD, name);
        cos.flush();
        return baos.toByteArray();
    }

    private byte[] encodeFileDescriptorResponse(Descriptors.FileDescriptor fileDescriptor) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);

        // Emit the requested file followed by the TRANSITIVE closure of its imports.
        //
        // Reflection clients (grpcurl, grpc-java's ProtoReflectionService consumers, the Go
        // jhump/protoreflect pool) build a descriptor pool from exactly the files in this
        // response and resolve every `import` against that pool. A file whose import is absent
        // cannot be linked, so returning only the DIRECT dependencies breaks any import chain
        // deeper than one level: a.proto imports b.proto imports c.proto yields {a, b} and the
        // client fails to resolve b's import of c.
        //
        // Emitted in breadth-first order from the root with a visited set, which both terminates
        // on the cyclic-import case and de-duplicates diamonds (two imports sharing a transitive
        // dependency), so each file appears exactly once.
        for (Descriptors.FileDescriptor file : transitiveClosure(fileDescriptor)) {
            byte[] fileProtoBytes = file.toProto().toByteArray();
            cos.writeBytes(FDR_FILE_DESCRIPTOR_PROTO_FIELD, com.google.protobuf.ByteString.copyFrom(fileProtoBytes));
        }

        cos.flush();
        return baos.toByteArray();
    }

    /**
     * Returns {@code root} followed by every file reachable from it through {@code import}
     * declarations, breadth-first, each appearing exactly once.
     *
     * <p>Visited-set termination makes this safe against import cycles (which protoc rejects,
     * but a hand-assembled {@code FileDescriptorSet} loaded through
     * {@link GrpcProtoDescriptorStore} need not have been through protoc) and collapses diamond
     * imports to a single occurrence.</p>
     */
    static List<Descriptors.FileDescriptor> transitiveClosure(Descriptors.FileDescriptor root) {
        List<Descriptors.FileDescriptor> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Descriptors.FileDescriptor> queue = new ArrayDeque<>();
        queue.add(root);
        visited.add(root.getFullName());
        while (!queue.isEmpty()) {
            Descriptors.FileDescriptor current = queue.removeFirst();
            ordered.add(current);
            for (Descriptors.FileDescriptor dependency : current.getDependencies()) {
                if (visited.add(dependency.getFullName())) {
                    queue.addLast(dependency);
                }
            }
        }
        return ordered;
    }

    /**
     * Finds the {@link Descriptors.FileDescriptor} that contains the given fully-qualified
     * symbol (service name or message type name).
     */
    Descriptors.FileDescriptor findFileContainingSymbol(String symbol) {
        // Check services first
        Descriptors.ServiceDescriptor service = descriptorStore.getService(symbol);
        if (service != null) {
            return service.getFile();
        }

        // Check all file descriptors for message types
        Map<String, Descriptors.ServiceDescriptor> allServices = descriptorStore.getAllServices();
        for (Descriptors.ServiceDescriptor svc : allServices.values()) {
            Descriptors.FileDescriptor fd = svc.getFile();
            // Check message types in this file
            for (Descriptors.Descriptor messageType : fd.getMessageTypes()) {
                if (messageType.getFullName().equals(symbol)) {
                    return fd;
                }
            }
            // Check enum types in this file
            for (Descriptors.EnumDescriptor enumType : fd.getEnumTypes()) {
                if (enumType.getFullName().equals(symbol)) {
                    return fd;
                }
            }
            // Check methods (service.method format)
            for (Descriptors.MethodDescriptor method : svc.getMethods()) {
                if ((svc.getFullName() + "." + method.getName()).equals(symbol)) {
                    return fd;
                }
            }
        }

        return null;
    }

    /**
     * Finds a {@link Descriptors.FileDescriptor} by its proto file name.
     * Searches file descriptors reachable from loaded services.
     */
    Descriptors.FileDescriptor findFileByName(String filename) {
        Map<String, Descriptors.ServiceDescriptor> allServices = descriptorStore.getAllServices();
        Set<String> visited = new HashSet<>();
        for (Descriptors.ServiceDescriptor svc : allServices.values()) {
            Descriptors.FileDescriptor result = findFileByNameRecursive(svc.getFile(), filename, visited);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private Descriptors.FileDescriptor findFileByNameRecursive(
        Descriptors.FileDescriptor fd, String filename, Set<String> visited
    ) {
        if (fd.getName().equals(filename)) {
            return fd;
        }
        if (!visited.add(fd.getName())) {
            return null;
        }
        for (Descriptors.FileDescriptor dep : fd.getDependencies()) {
            Descriptors.FileDescriptor result = findFileByNameRecursive(dep, filename, visited);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Wraps a protobuf message in a gRPC frame (5-byte header: 1 byte compression flag +
     * 4-byte big-endian message length).
     */
    public static byte[] grpcFrame(byte[] proto) {
        byte[] framed = new byte[5 + proto.length];
        framed[0] = 0; // no compression
        framed[1] = (byte) ((proto.length >> 24) & 0xFF);
        framed[2] = (byte) ((proto.length >> 16) & 0xFF);
        framed[3] = (byte) ((proto.length >> 8) & 0xFF);
        framed[4] = (byte) (proto.length & 0xFF);
        System.arraycopy(proto, 0, framed, 5, proto.length);
        return framed;
    }

    // --- Inner types ---

    enum RequestType {
        LIST_SERVICES,
        FILE_CONTAINING_SYMBOL,
        FILE_BY_FILENAME,
        UNKNOWN
    }

    static class ReflectionRequest {
        final RequestType type;
        final String host;
        final String argument;

        ReflectionRequest(RequestType type, String host, String argument) {
            this.type = type;
            this.host = host;
            this.argument = argument;
        }
    }
}
