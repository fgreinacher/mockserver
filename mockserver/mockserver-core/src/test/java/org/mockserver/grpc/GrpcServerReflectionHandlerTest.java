package org.mockserver.grpc;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.WireFormat;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GrpcServerReflectionHandlerTest {

    private GrpcProtoDescriptorStore store;
    private GrpcServerReflectionHandler handler;

    @Before
    public void setUp() {
        store = new GrpcProtoDescriptorStore(new MockServerLogger());
        Path descriptorPath = Paths.get("src/test/resources/grpc/greeting.dsc");
        store.loadDescriptorSetFromPath(descriptorPath);
        handler = new GrpcServerReflectionHandler(store);
    }

    // --- isReflectionRequest ---

    @Test
    public void shouldMatchV1ReflectionPath() {
        assertThat(handler.isReflectionRequest(
            "/grpc.reflection.v1.ServerReflection/ServerReflectionInfo"), is(true));
    }

    @Test
    public void shouldMatchV1AlphaReflectionPath() {
        assertThat(handler.isReflectionRequest(
            "/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo"), is(true));
    }

    @Test
    public void shouldNotMatchOtherPath() {
        assertThat(handler.isReflectionRequest("/other.Service/Method"), is(false));
    }

    @Test
    public void shouldNotMatchNullPath() {
        assertThat(handler.isReflectionRequest(null), is(false));
    }

    @Test
    public void shouldNotMatchEmptyPath() {
        assertThat(handler.isReflectionRequest(""), is(false));
    }

    @Test
    public void shouldNotMatchPartialPath() {
        assertThat(handler.isReflectionRequest(
            "/grpc.reflection.v1.ServerReflection/OtherMethod"), is(false));
    }

    // --- list_services ---

    @Test
    public void shouldListServices() throws IOException {
        byte[] requestBody = buildListServicesRequest("");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        // Parse the gRPC-framed response
        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(5)));

        List<String> serviceNames = parseListServicesResponse(responseBody);
        assertThat(serviceNames, hasItem("com.example.grpc.GreetingService"));
        assertThat(serviceNames.size(), is(1));
    }

    @Test
    public void shouldListServicesWithHost() throws IOException {
        byte[] requestBody = buildListServicesRequest("localhost");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        List<String> serviceNames = parseListServicesResponse(responseBody);
        assertThat(serviceNames, hasItem("com.example.grpc.GreetingService"));
    }

    // --- file_containing_symbol ---

    @Test
    public void shouldReturnFileDescriptorForServiceSymbol() throws IOException {
        byte[] requestBody = buildFileContainingSymbolRequest("com.example.grpc.GreetingService");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(5)));

        List<byte[]> fileDescriptorProtos = parseFileDescriptorResponse(responseBody);
        assertThat(fileDescriptorProtos, is(not(empty())));

        // Parse the first file descriptor proto and verify it has the expected file name
        DescriptorProtos.FileDescriptorProto fdProto =
            DescriptorProtos.FileDescriptorProto.parseFrom(fileDescriptorProtos.get(0));
        assertThat(fdProto.getName(), is("greeting.proto"));
        assertThat(fdProto.getPackage(), is("com.example.grpc"));
    }

    @Test
    public void shouldReturnFileDescriptorForMessageSymbol() throws IOException {
        byte[] requestBody = buildFileContainingSymbolRequest("com.example.grpc.HelloRequest");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        List<byte[]> fileDescriptorProtos = parseFileDescriptorResponse(responseBody);
        assertThat(fileDescriptorProtos, is(not(empty())));

        DescriptorProtos.FileDescriptorProto fdProto =
            DescriptorProtos.FileDescriptorProto.parseFrom(fileDescriptorProtos.get(0));
        assertThat(fdProto.getName(), is("greeting.proto"));
    }

    /**
     * "not null and longer than 5 bytes" is true of EVERY reflection response, so it cannot tell an
     * error envelope from a successful list-services or file-descriptor response. Assert the actual
     * {@code error_response} (field 5) with its NOT_FOUND code and message, which is what a client
     * such as {@code grpcurl} surfaces to the user.
     */
    @Test
    public void shouldReturnErrorForUnknownSymbol() throws IOException {
        byte[] requestBody = buildFileContainingSymbolRequest("com.example.NonExistent");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(5)));

        ErrorResponse error = parseErrorResponse(responseBody);
        assertThat("the response must carry error_response (field 5), not a success oneof",
            error, is(notNullValue()));
        assertThat(error.code, is(5)); // NOT_FOUND
        assertThat(error.message, is("symbol not found: com.example.NonExistent"));
    }

    // --- file_by_filename ---

    @Test
    public void shouldReturnFileDescriptorByFilename() throws IOException {
        byte[] requestBody = buildFileByFilenameRequest("greeting.proto");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(5)));

        List<byte[]> fileDescriptorProtos = parseFileDescriptorResponse(responseBody);
        assertThat(fileDescriptorProtos, is(not(empty())));

        DescriptorProtos.FileDescriptorProto fdProto =
            DescriptorProtos.FileDescriptorProto.parseFrom(fileDescriptorProtos.get(0));
        assertThat(fdProto.getName(), is("greeting.proto"));
    }

    @Test
    public void shouldReturnErrorForUnknownFilename() throws IOException {
        byte[] requestBody = buildFileByFilenameRequest("nonexistent.proto");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(5)));

        ErrorResponse error = parseErrorResponse(responseBody);
        assertThat("the response must carry error_response (field 5), not a success oneof",
            error, is(notNullValue()));
        assertThat(error.code, is(5)); // NOT_FOUND
        assertThat(error.message, is("file not found: nonexistent.proto"));
    }

    /**
     * A successful response must NOT be an error envelope -- the negative half of the guard above,
     * so swapping the two response shapes cannot pass both tests.
     */
    @Test
    public void shouldNotReturnAnErrorEnvelopeForAKnownSymbol() throws IOException {
        byte[] responseBody = handler.handleReflectionRequest(
            buildFileContainingSymbolRequest("com.example.grpc.GreetingService"));

        assertThat(parseErrorResponse(responseBody), is(nullValue()));
    }

    // --- valid_host echo ---

    /**
     * The reflection protocol requires the response to echo the request's {@code host} in
     * {@code valid_host} (field 1). The request-side decode is asserted above, but nothing pinned
     * the response side, so dropping the echo entirely stayed green.
     */
    @Test
    public void shouldEchoValidHostOnTheResponse() throws IOException {
        byte[] responseBody = handler.handleReflectionRequest(buildListServicesRequest("myhost.example.com"));

        assertThat(parseValidHost(responseBody), is("myhost.example.com"));
    }

    @Test
    public void shouldOmitValidHostWhenTheRequestCarriedNoHost() throws IOException {
        byte[] responseBody = handler.handleReflectionRequest(buildListServicesRequest(""));

        assertThat(parseValidHost(responseBody), is(nullValue()));
    }

    // --- edge cases ---

    @Test
    public void shouldHandleNullBody() {
        byte[] responseBody = handler.handleReflectionRequest(null);
        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(4))); // at least the gRPC frame header
    }

    @Test
    public void shouldHandleEmptyBody() {
        byte[] responseBody = handler.handleReflectionRequest(new byte[5]);
        assertThat(responseBody, is(notNullValue()));
    }

    @Test
    public void shouldHandleShortBody() {
        byte[] responseBody = handler.handleReflectionRequest(new byte[3]);
        assertThat(responseBody, is(notNullValue()));
    }

    // --- decodeRequest ---

    @Test
    public void shouldDecodeListServicesRequest() throws IOException {
        byte[] requestBody = buildListServicesRequest("myhost");
        GrpcServerReflectionHandler.ReflectionRequest req = handler.decodeRequest(requestBody);
        assertThat(req.type, is(GrpcServerReflectionHandler.RequestType.LIST_SERVICES));
        assertThat(req.host, is("myhost"));
    }

    @Test
    public void shouldDecodeFileContainingSymbolRequest() throws IOException {
        byte[] requestBody = buildFileContainingSymbolRequest("com.example.MyService");
        GrpcServerReflectionHandler.ReflectionRequest req = handler.decodeRequest(requestBody);
        assertThat(req.type, is(GrpcServerReflectionHandler.RequestType.FILE_CONTAINING_SYMBOL));
        assertThat(req.argument, is("com.example.MyService"));
    }

    @Test
    public void shouldDecodeFileByFilenameRequest() throws IOException {
        byte[] requestBody = buildFileByFilenameRequest("test.proto");
        GrpcServerReflectionHandler.ReflectionRequest req = handler.decodeRequest(requestBody);
        assertThat(req.type, is(GrpcServerReflectionHandler.RequestType.FILE_BY_FILENAME));
        assertThat(req.argument, is("test.proto"));
    }

    @Test
    public void shouldDecodeUnknownRequestType() {
        GrpcServerReflectionHandler.ReflectionRequest req = handler.decodeRequest(new byte[5]);
        assertThat(req.type, is(GrpcServerReflectionHandler.RequestType.UNKNOWN));
    }

    // --- grpcFrame ---

    @Test
    public void shouldProduceValidGrpcFrame() {
        byte[] proto = new byte[]{0x0A, 0x05, 0x68, 0x65, 0x6C, 0x6C, 0x6F}; // dummy proto
        byte[] framed = GrpcServerReflectionHandler.grpcFrame(proto);
        assertThat(framed.length, is(5 + proto.length));
        assertThat(framed[0], is((byte) 0)); // no compression
        // big-endian length
        int length = ((framed[1] & 0xFF) << 24) | ((framed[2] & 0xFF) << 16)
            | ((framed[3] & 0xFF) << 8) | (framed[4] & 0xFF);
        assertThat(length, is(proto.length));
    }

    /**
     * The 7-byte payload above cannot see the length bytes at all: {@code framed[1..3]} are zero for
     * any payload under 256 bytes, so they are indistinguishable from default array initialisation
     * and deleting the three high-order length assignments keeps that test green. Production
     * payloads are not that small -- the real {@code file_containing_symbol} response is several
     * hundred bytes (asserted below), which without the high byte would under-declare its length
     * and be truncated by every real gRPC client.
     * <p>
     * The fixtures below are sized so {@code framed[3]} and {@code framed[2]} each carry a non-zero
     * value. {@code framed[1]} would need a payload above 16 MiB to observe, which is not worth
     * allocating in a unit test; it is left to the shared arithmetic with the bytes that ARE pinned.
     */
    @Test
    public void shouldEncodeFrameLengthAcrossAllLengthBytes() {
        assertFrameDeclaresLength(300);     // exercises framed[3] (0x01) and framed[4] (0x2C)
        assertFrameDeclaresLength(70_000);  // exercises framed[2] (0x01)
    }

    private static void assertFrameDeclaresLength(int payloadLength) {
        byte[] proto = new byte[payloadLength];
        for (int i = 0; i < payloadLength; i++) {
            proto[i] = (byte) (i % 251);
        }

        byte[] framed = GrpcServerReflectionHandler.grpcFrame(proto);

        assertThat(framed.length, is(5 + payloadLength));
        assertThat(framed[0], is((byte) 0));
        int declared = ((framed[1] & 0xFF) << 24) | ((framed[2] & 0xFF) << 16)
            | ((framed[3] & 0xFF) << 8) | (framed[4] & 0xFF);
        assertThat("the declared frame length must match the payload a client will read",
            declared, is(payloadLength));
        // and the payload must still be intact after the header
        byte[] payload = new byte[payloadLength];
        System.arraycopy(framed, 5, payload, 0, payloadLength);
        assertThat(payload, is(proto));
    }

    /**
     * The real reflection responses must declare their own length correctly, not just a synthetic
     * fixture. The {@code file_containing_symbol} response for {@code greeting.dsc} is several
     * hundred bytes, which is exactly the size at which a dropped high-order length byte truncates.
     */
    @Test
    public void shouldDeclareCorrectFrameLengthForRealReflectionResponse() throws IOException {
        byte[] responseBody = handler.handleReflectionRequest(
            buildFileContainingSymbolRequest("com.example.grpc.GreetingService"));

        assertThat("this guard is only meaningful if the payload exceeds one length byte",
            responseBody.length - 5, is(greaterThan(255)));
        int declared = ((responseBody[1] & 0xFF) << 24) | ((responseBody[2] & 0xFF) << 16)
            | ((responseBody[3] & 0xFF) << 8) | (responseBody[4] & 0xFF);
        assertThat(declared, is(responseBody.length - 5));
    }

    // --- Helper methods to build gRPC-framed ServerReflectionRequest messages ---

    /**
     * Builds a gRPC-framed ServerReflectionRequest with list_services (field 7).
     */
    private byte[] buildListServicesRequest(String host) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        if (host != null && !host.isEmpty()) {
            cos.writeString(GrpcServerReflectionHandler.REQ_HOST_FIELD, host);
        }
        // list_services = field 7, value is typically "*" or empty
        cos.writeString(GrpcServerReflectionHandler.REQ_LIST_SERVICES_FIELD, "*");
        cos.flush();
        return GrpcServerReflectionHandler.grpcFrame(baos.toByteArray());
    }

    /**
     * Builds a gRPC-framed ServerReflectionRequest with file_containing_symbol (field 4).
     */
    private byte[] buildFileContainingSymbolRequest(String symbol) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeString(GrpcServerReflectionHandler.REQ_FILE_CONTAINING_SYMBOL_FIELD, symbol);
        cos.flush();
        return GrpcServerReflectionHandler.grpcFrame(baos.toByteArray());
    }

    /**
     * Builds a gRPC-framed ServerReflectionRequest with file_by_filename (field 3).
     */
    private byte[] buildFileByFilenameRequest(String filename) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeString(GrpcServerReflectionHandler.REQ_FILE_BY_FILENAME_FIELD, filename);
        cos.flush();
        return GrpcServerReflectionHandler.grpcFrame(baos.toByteArray());
    }

    // --- Helper methods to parse gRPC-framed ServerReflectionResponse ---

    /**
     * Parses a gRPC-framed ServerReflectionResponse and extracts service names
     * from a ListServiceResponse (field 6).
     */
    private List<String> parseListServicesResponse(byte[] grpcFramed) throws IOException {
        byte[] proto = stripGrpcFrame(grpcFramed);
        CodedInputStream cis = CodedInputStream.newInstance(proto);
        List<String> serviceNames = new ArrayList<>();

        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == GrpcServerReflectionHandler.RESP_LIST_SERVICES_RESPONSE_FIELD) {
                // embedded ListServiceResponse message
                byte[] listServiceBytes = cis.readByteArray();
                serviceNames.addAll(parseListServiceResponse(listServiceBytes));
            } else {
                cis.skipField(tag);
            }
        }
        return serviceNames;
    }

    private List<String> parseListServiceResponse(byte[] bytes) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(bytes);
        List<String> names = new ArrayList<>();
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == GrpcServerReflectionHandler.LSR_SERVICE_FIELD) {
                // embedded ServiceResponse message
                byte[] serviceBytes = cis.readByteArray();
                names.add(parseServiceResponseName(serviceBytes));
            } else {
                cis.skipField(tag);
            }
        }
        return names;
    }

    private String parseServiceResponseName(byte[] bytes) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(bytes);
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == GrpcServerReflectionHandler.SR_NAME_FIELD) {
                return cis.readString();
            } else {
                cis.skipField(tag);
            }
        }
        return "";
    }

    /**
     * Parses a gRPC-framed ServerReflectionResponse and extracts the
     * file_descriptor_proto bytes from a FileDescriptorResponse (field 4).
     */
    private List<byte[]> parseFileDescriptorResponse(byte[] grpcFramed) throws IOException {
        byte[] proto = stripGrpcFrame(grpcFramed);
        CodedInputStream cis = CodedInputStream.newInstance(proto);
        List<byte[]> fdProtos = new ArrayList<>();

        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == GrpcServerReflectionHandler.RESP_FILE_DESCRIPTOR_RESPONSE_FIELD) {
                // embedded FileDescriptorResponse message
                byte[] fdrBytes = cis.readByteArray();
                fdProtos.addAll(parseFileDescriptorResponseInner(fdrBytes));
            } else {
                cis.skipField(tag);
            }
        }
        return fdProtos;
    }

    private List<byte[]> parseFileDescriptorResponseInner(byte[] bytes) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(bytes);
        List<byte[]> protos = new ArrayList<>();
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == GrpcServerReflectionHandler.FDR_FILE_DESCRIPTOR_PROTO_FIELD) {
                protos.add(cis.readByteArray());
            } else {
                cis.skipField(tag);
            }
        }
        return protos;
    }

    /** The reflection {@code ErrorResponse}: field 1 = error_code, field 2 = error_message. */
    private static final class ErrorResponse {
        private final int code;
        private final String message;

        private ErrorResponse(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /**
     * Returns the {@code error_response} (ServerReflectionResponse field 5), or {@code null} when
     * the response carries a success oneof instead.
     */
    private ErrorResponse parseErrorResponse(byte[] grpcFramed) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(stripGrpcFrame(grpcFramed));
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            if (WireFormat.getTagFieldNumber(tag) == 5) {
                CodedInputStream error = CodedInputStream.newInstance(cis.readByteArray());
                int code = 0;
                String message = null;
                while (!error.isAtEnd()) {
                    int errorTag = error.readTag();
                    switch (WireFormat.getTagFieldNumber(errorTag)) {
                        case 1:
                            code = error.readInt32();
                            break;
                        case 2:
                            message = error.readString();
                            break;
                        default:
                            error.skipField(errorTag);
                            break;
                    }
                }
                return new ErrorResponse(code, message);
            }
            cis.skipField(tag);
        }
        return null;
    }

    /**
     * Returns the echoed {@code valid_host} (ServerReflectionResponse field 1), or {@code null}.
     */
    private String parseValidHost(byte[] grpcFramed) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(stripGrpcFrame(grpcFramed));
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            if (WireFormat.getTagFieldNumber(tag) == GrpcServerReflectionHandler.RESP_VALID_HOST_FIELD) {
                return cis.readString();
            }
            cis.skipField(tag);
        }
        return null;
    }

    /**
     * Reads the payload the way a real gRPC client does: by trusting the DECLARED length in the
     * 5-byte header rather than by assuming "everything after offset 5". Copying blindly from
     * offset 5 made every reflection test blind to the frame length header entirely, so a frame
     * that under-declared its length still parsed here while truncating on the wire.
     */
    private byte[] stripGrpcFrame(byte[] grpcFramed) {
        assertThat("a gRPC frame needs at least a 5-byte header", grpcFramed.length, is(greaterThanOrEqualTo(5)));
        assertThat("compression flag must be unset", grpcFramed[0], is((byte) 0));
        int declared = ((grpcFramed[1] & 0xFF) << 24) | ((grpcFramed[2] & 0xFF) << 16)
            | ((grpcFramed[3] & 0xFF) << 8) | (grpcFramed[4] & 0xFF);
        assertThat("the frame must declare the length a client will actually read",
            declared, is(grpcFramed.length - 5));
        byte[] proto = new byte[declared];
        System.arraycopy(grpcFramed, 5, proto, 0, declared);
        return proto;
    }
}
