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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Verifies that {@code file_containing_symbol} / {@code file_by_filename} reflection responses
 * carry the full TRANSITIVE closure of a file's imports, not just its direct dependencies.
 *
 * <h3>Why this needs its own fixture</h3>
 * <p>The checked-in {@code orders.proto} fixture has a local import
 * ({@code orders_common.proto}), but that import declares no imports of its own — so the chain is
 * only one level deep and a direct-dependencies-only implementation satisfies it. The defect this
 * test exists to catch appears at TWO levels, so the fixture here is built programmatically:</p>
 *
 * <pre>
 *   root.proto  --imports--&gt;  middle.proto  --imports--&gt;  leaf.proto
 * </pre>
 *
 * <p>Assembling it with {@link DescriptorProtos.FileDescriptorProto} builders rather than a
 * checked-in {@code .dsc} keeps the chain depth visible in the test and avoids needing protoc to
 * regenerate a descriptor set.</p>
 *
 * <h3>What counts as passing</h3>
 * <p>The acceptance criterion is the one a real reflection client applies: take ONLY the
 * {@code FileDescriptorProto}s present in the response and link them into a descriptor pool,
 * resolving each file's {@code import} statements against nothing but that set. This is what
 * {@code grpcurl} does (via {@code jhump/protoreflect}) and what grpc-java consumers of
 * {@code ProtoReflectionService} do; protobuf-java's
 * {@link Descriptors.FileDescriptor#buildFrom} performs the same resolution and throws
 * {@link Descriptors.DescriptorValidationException} when an import cannot be satisfied.</p>
 *
 * <p>Because that pool-build is a LIBRARY check, the tests below deliberately do not rely on it
 * alone — {@link #shouldReturnEveryFileInTheImportChain()} asserts the exact set of filenames
 * MockServer chose to emit, which is the half this code is actually responsible for. A response
 * could in principle satisfy the linker while emitting a wrong-but-linkable set, and a response
 * could carry the right names in an order the linker rejects; the two assertions are split so
 * each half fails independently.</p>
 */
public class GrpcServerReflectionTransitiveClosureTest {

    private static final String LEAF_FILE = "closure/leaf.proto";
    private static final String MIDDLE_FILE = "closure/middle.proto";
    private static final String ROOT_FILE = "closure/root.proto";

    private GrpcServerReflectionHandler handler;

    @Before
    public void setUp() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSet(buildThreeLevelDescriptorSet());
        handler = new GrpcServerReflectionHandler(store);
    }

    // --- the half this class is responsible for: which files get emitted ---

    @Test
    public void shouldReturnEveryFileInTheImportChain() throws Exception {
        List<DescriptorProtos.FileDescriptorProto> returned =
            returnedFilesFor(buildFileContainingSymbolRequest("closure.RootService"));

        assertThat(namesOf(returned), containsInAnyOrder(ROOT_FILE, MIDDLE_FILE, LEAF_FILE));
    }

    @Test
    public void shouldReturnTheImportChainWhenLookingUpByFilename() throws Exception {
        List<DescriptorProtos.FileDescriptorProto> returned =
            returnedFilesFor(buildFileByFilenameRequest(ROOT_FILE));

        assertThat(namesOf(returned), containsInAnyOrder(ROOT_FILE, MIDDLE_FILE, LEAF_FILE));
    }

    @Test
    public void shouldReturnTheTransitivelyImportedLeafFile() throws Exception {
        // The specific file a direct-dependencies-only implementation omits. Stated on its own so
        // the failure message names the missing file rather than reporting a set mismatch.
        List<DescriptorProtos.FileDescriptorProto> returned =
            returnedFilesFor(buildFileContainingSymbolRequest("closure.RootService"));

        assertThat(namesOf(returned), hasItem(LEAF_FILE));
    }

    @Test
    public void shouldNotRepeatAFileReachableByTwoImportPaths() throws Exception {
        // root imports middle AND leaf directly, while middle also imports leaf: a diamond.
        // The visited set must collapse leaf to a single entry — a reflection client building a
        // pool from a response containing the same filename twice rejects it as a duplicate.
        GrpcProtoDescriptorStore diamondStore = new GrpcProtoDescriptorStore(new MockServerLogger());
        diamondStore.loadDescriptorSet(buildDiamondDescriptorSet());
        GrpcServerReflectionHandler diamondHandler = new GrpcServerReflectionHandler(diamondStore);

        List<DescriptorProtos.FileDescriptorProto> returned = parseReturnedFiles(
            diamondHandler.handleReflectionRequest(
                GrpcServerReflectionHandler.grpcFrame(buildFileContainingSymbolRequest("closure.RootService"))));

        List<String> names = namesOf(returned);
        assertThat(names, containsInAnyOrder(ROOT_FILE, MIDDLE_FILE, LEAF_FILE));
        assertThat("leaf must appear exactly once", names.stream().filter(LEAF_FILE::equals).count(), is(1L));
    }

    // --- the other half: the emitted set must actually link ---

    @Test
    public void shouldReturnAResponseThatLinksIntoADescriptorPool() throws Exception {
        // Resolves imports against ONLY the returned files, exactly as a reflection client does.
        // Throws DescriptorValidationException if any import in the chain is unsatisfied.
        List<DescriptorProtos.FileDescriptorProto> returned =
            returnedFilesFor(buildFileContainingSymbolRequest("closure.RootService"));

        Map<String, Descriptors.FileDescriptor> pool = linkPool(returned);

        Descriptors.FileDescriptor root = pool.get(ROOT_FILE);
        assertThat(root, is(notNullValue()));
        assertThat(root.findServiceByName("RootService"), is(notNullValue()));
        // Walk root -> middle -> leaf through the linked pool. Reaching closure.Leaf proves the
        // two-level chain resolved, which is what a client needs in order to describe or format
        // the request message.
        Descriptors.Descriptor request = root.findMessageTypeByName("RootRequest");
        Descriptors.Descriptor middle = request.findFieldByName("middle").getMessageType();
        assertThat(middle.getFullName(), is("closure.Middle"));
        assertThat(middle.findFieldByName("leaf").getMessageType().getFullName(), is("closure.Leaf"));
    }

    /**
     * Links {@code files} into a descriptor pool, resolving every {@code import} against that set
     * alone. Mirrors the pool construction performed by {@code grpcurl} and grpc-java reflection
     * clients.
     *
     * @throws Descriptors.DescriptorValidationException if a declared import is not present
     */
    private Map<String, Descriptors.FileDescriptor> linkPool(List<DescriptorProtos.FileDescriptorProto> files)
        throws Descriptors.DescriptorValidationException {
        Map<String, DescriptorProtos.FileDescriptorProto> byName = new HashMap<>();
        for (DescriptorProtos.FileDescriptorProto file : files) {
            byName.put(file.getName(), file);
        }
        Map<String, Descriptors.FileDescriptor> linked = new HashMap<>();
        for (DescriptorProtos.FileDescriptorProto file : files) {
            link(file.getName(), byName, linked);
        }
        return linked;
    }

    private Descriptors.FileDescriptor link(String name,
                                            Map<String, DescriptorProtos.FileDescriptorProto> byName,
                                            Map<String, Descriptors.FileDescriptor> linked)
        throws Descriptors.DescriptorValidationException {
        Descriptors.FileDescriptor already = linked.get(name);
        if (already != null) {
            return already;
        }
        DescriptorProtos.FileDescriptorProto proto = byName.get(name);
        if (proto == null) {
            throw new IllegalStateException(
                "reflection response did not include imported file '" + name + "'; a client cannot link its pool");
        }
        List<Descriptors.FileDescriptor> dependencies = new ArrayList<>();
        for (String dependencyName : proto.getDependencyList()) {
            dependencies.add(link(dependencyName, byName, linked));
        }
        Descriptors.FileDescriptor built =
            Descriptors.FileDescriptor.buildFrom(proto, dependencies.toArray(new Descriptors.FileDescriptor[0]));
        linked.put(name, built);
        return built;
    }

    // --- fixture construction ---

    /** root.proto -> middle.proto -> leaf.proto, a strictly two-level import chain. */
    private byte[] buildThreeLevelDescriptorSet() {
        return DescriptorProtos.FileDescriptorSet.newBuilder()
            .addFile(leafFile())
            .addFile(middleFile())
            .addFile(rootFile(false))
            .build()
            .toByteArray();
    }

    /** As above, but root additionally imports leaf directly, forming a diamond. */
    private byte[] buildDiamondDescriptorSet() {
        return DescriptorProtos.FileDescriptorSet.newBuilder()
            .addFile(leafFile())
            .addFile(middleFile())
            .addFile(rootFile(true))
            .build()
            .toByteArray();
    }

    private DescriptorProtos.FileDescriptorProto leafFile() {
        return DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName(LEAF_FILE)
            .setPackage("closure")
            .setSyntax("proto3")
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Leaf")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                    .setName("value")
                    .setNumber(1)
                    .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)))
            .build();
    }

    private DescriptorProtos.FileDescriptorProto middleFile() {
        return DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName(MIDDLE_FILE)
            .setPackage("closure")
            .setSyntax("proto3")
            .addDependency(LEAF_FILE)
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Middle")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                    .setName("leaf")
                    .setNumber(1)
                    .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".closure.Leaf")))
            .build();
    }

    private DescriptorProtos.FileDescriptorProto rootFile(boolean alsoImportLeafDirectly) {
        DescriptorProtos.FileDescriptorProto.Builder builder = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName(ROOT_FILE)
            .setPackage("closure")
            .setSyntax("proto3")
            .addDependency(MIDDLE_FILE);
        if (alsoImportLeafDirectly) {
            builder.addDependency(LEAF_FILE);
        }
        return builder
            // Note: root references ONLY .closure.Middle. Protobuf does not grant transitive type
            // visibility — a file may reference types from its direct imports only — so root
            // CANNOT name .closure.Leaf without importing leaf.proto itself. leaf.proto is
            // nonetheless required in the response, because middle.proto's own import of it must
            // resolve before middle.proto can be linked at all.
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                .setName("RootRequest")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                    .setName("middle")
                    .setNumber(1)
                    .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".closure.Middle")))
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                .setName("RootResponse"))
            .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("RootService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName("Call")
                    .setInputType(".closure.RootRequest")
                    .setOutputType(".closure.RootResponse")))
            .build();
    }

    // --- request/response codec helpers ---

    private List<DescriptorProtos.FileDescriptorProto> returnedFilesFor(byte[] request) throws Exception {
        return parseReturnedFiles(
            handler.handleReflectionRequest(GrpcServerReflectionHandler.grpcFrame(request)));
    }

    private List<String> namesOf(List<DescriptorProtos.FileDescriptorProto> files) {
        List<String> names = new ArrayList<>();
        for (DescriptorProtos.FileDescriptorProto file : files) {
            names.add(file.getName());
        }
        return names;
    }

    private List<DescriptorProtos.FileDescriptorProto> parseReturnedFiles(byte[] grpcFramed) throws IOException {
        List<DescriptorProtos.FileDescriptorProto> files = new ArrayList<>();
        for (byte[] fileBytes : parseFileDescriptorResponse(grpcFramed)) {
            files.add(DescriptorProtos.FileDescriptorProto.parseFrom(fileBytes));
        }
        return files;
    }

    private List<byte[]> parseFileDescriptorResponse(byte[] grpcFramed) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(stripGrpcFrame(grpcFramed));
        List<byte[]> fileProtos = new ArrayList<>();
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            if (WireFormat.getTagFieldNumber(tag) == GrpcServerReflectionHandler.RESP_FILE_DESCRIPTOR_RESPONSE_FIELD) {
                fileProtos.addAll(parseFileDescriptorResponseInner(cis.readByteArray()));
            } else {
                cis.skipField(tag);
            }
        }
        return fileProtos;
    }

    private List<byte[]> parseFileDescriptorResponseInner(byte[] bytes) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(bytes);
        List<byte[]> protos = new ArrayList<>();
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            if (WireFormat.getTagFieldNumber(tag) == GrpcServerReflectionHandler.FDR_FILE_DESCRIPTOR_PROTO_FIELD) {
                protos.add(cis.readByteArray());
            } else {
                cis.skipField(tag);
            }
        }
        return protos;
    }

    private byte[] buildFileContainingSymbolRequest(String symbol) throws IOException {
        return encodeRequest(GrpcServerReflectionHandler.REQ_FILE_CONTAINING_SYMBOL_FIELD, symbol);
    }

    private byte[] buildFileByFilenameRequest(String filename) throws IOException {
        return encodeRequest(GrpcServerReflectionHandler.REQ_FILE_BY_FILENAME_FIELD, filename);
    }

    private byte[] encodeRequest(int field, String value) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeString(field, value);
        cos.flush();
        return baos.toByteArray();
    }

    private byte[] stripGrpcFrame(byte[] grpcFramed) {
        byte[] payload = new byte[grpcFramed.length - 5];
        System.arraycopy(grpcFramed, 5, payload, 0, payload.length);
        return payload;
    }
}
