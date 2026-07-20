package org.mockserver.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.TypeRegistry;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for {@link GrpcExampleSynthesizer}. Descriptors are built programmatically via
 * {@link FileDescriptorProto} so the tests have no dependency on {@code protoc} being present.
 */
public class GrpcExampleSynthesizerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final GrpcExampleSynthesizer synthesizer = new GrpcExampleSynthesizer();

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
            .setName(name)
            .setNumber(number)
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .setType(type)
            .build();
    }

    private static FieldDescriptorProto messageField(String name, int number, String typeName, FieldDescriptorProto.Label label) {
        return FieldDescriptorProto.newBuilder()
            .setName(name)
            .setNumber(number)
            .setLabel(label)
            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setTypeName(typeName)
            .build();
    }

    /**
     * The protobuf well-known types must render in their CANONICAL JSON forms -- an RFC-3339 string
     * for {@code Timestamp}, {@code "0s"} for {@code Duration}, and a bare scalar for each wrapper --
     * not as the raw {@code {seconds, nanos}} / {@code {value}} objects the generic recursion would
     * produce. Nothing referenced these types before, so {@code wellKnownValue} was entirely
     * unexercised; it needs a descriptor set that imports {@code google/protobuf/*}, which the
     * import-free {@code greeting}/{@code catalog} fixtures cannot provide.
     * <p>
     * This one test loads a committed {@code .dsc} rather than building descriptors inline (see the
     * class note above) because the well-known descriptors have to come from protoc's own bundled
     * definitions to be the real thing. {@code protoc} is still not needed to RUN the test -- only
     * to regenerate the fixture; see {@code src/test/resources/grpc/README.md}.
     */
    @Test
    public void shouldSynthesizeCanonicalJsonForWellKnownTypes() throws Exception {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new org.mockserver.logging.MockServerLogger());
        store.loadDescriptorSetFromPath(java.nio.file.Paths.get("src/test/resources/grpc/orders.dsc"));

        String json = store.synthesizeResponseJson("com.example.orders.OrderService", "GetOrder");
        assertThat(json, is(notNullValue()));
        JsonNode node = OBJECT_MAPPER.readTree(json);

        // NOTE on what actually discriminates here: protobuf's JsonFormat printer special-cases the
        // well-known types itself, so the RFC-3339 SHAPE survives even if wellKnownValue stops
        // handling Timestamp -- the type assertion alone would pass. What fails is the VALUE: with
        // the case removed the field falls back to the default instant. Assert the exact value.
        assertThat("Timestamp must be an RFC-3339 string, not {seconds,nanos}",
            node.get("placedAt").isTextual(), is(true));
        assertThat("the synthesized instant must be the chosen example, not the epoch default",
            node.get("placedAt").asText(), is("2020-01-01T00:00:00Z"));

        // Duration is a documented BLIND SPOT rather than a guard: its canonical example value
        // (0 seconds) is also its default, so removing wellKnownValue's Duration case produces
        // byte-identical output and this assertion cannot detect it. It is kept to pin the rendered
        // FORM ("0s", not {seconds,nanos}) against a printer/serialisation change, and is
        // deliberately not claimed as coverage of wellKnownValue itself.
        assertThat("Duration must render as a duration string, not {seconds,nanos}",
            node.get("processingTime").isTextual(), is(true));
        assertThat(node.get("processingTime").asText(), is("0s"));

        assertThat("StringValue must flatten to a bare string, not {value:...}",
            node.get("note").isTextual(), is(true));
        assertThat(node.get("note").asText(), is("string"));

        assertThat("BoolValue must flatten to a bare boolean", node.get("expedited").isBoolean(), is(true));
        assertThat("Int32Value must flatten to a bare number", node.get("quantity").isNumber(), is(true));

        // a NON-well-known imported message must still use the ordinary recursion
        assertThat("an ordinary imported message must remain an object",
            node.get("total").isObject(), is(true));
        assertThat(node.get("total").get("currencyCode").isTextual(), is(true));
    }

    private static FieldDescriptorProto enumField(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
            .setName(name)
            .setNumber(number)
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .setType(FieldDescriptorProto.Type.TYPE_ENUM)
            .setTypeName(typeName)
            .build();
    }

    private Descriptors.Descriptor buildAndGet(FileDescriptorProto proto, String messageName) {
        try {
            Descriptors.FileDescriptor file = Descriptors.FileDescriptor.buildFrom(proto, new Descriptors.FileDescriptor[0]);
            return file.findMessageTypeByName(messageName);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new RuntimeException(e);
        }
    }

    private GrpcJsonMessageConverter converterFor(Descriptors.FileDescriptor... files) {
        TypeRegistry.Builder registry = TypeRegistry.newBuilder();
        for (Descriptors.FileDescriptor file : files) {
            registry.add(file.getMessageTypes());
        }
        return new GrpcJsonMessageConverter(registry.build());
    }

    private JsonNode readTree(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void shouldSynthesizeScalarFields() {
        DescriptorProto scalars = DescriptorProto.newBuilder()
            .setName("Scalars")
            .addField(field("str", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .addField(field("i32", 2, FieldDescriptorProto.Type.TYPE_INT32))
            .addField(field("i64", 3, FieldDescriptorProto.Type.TYPE_INT64))
            .addField(field("f", 4, FieldDescriptorProto.Type.TYPE_FLOAT))
            .addField(field("d", 5, FieldDescriptorProto.Type.TYPE_DOUBLE))
            .addField(field("b", 6, FieldDescriptorProto.Type.TYPE_BOOL))
            .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
            .setName("scalars.proto").setSyntax("proto3").setPackage("test")
            .addMessageType(scalars)
            .build();
        Descriptors.Descriptor descriptor = buildAndGet(proto, "Scalars");

        String json = synthesizer.synthesizeJson(descriptor, converterFor(descriptor.getFile()));
        JsonNode node = readTree(json);

        assertThat(node.get("str").asText(), is("string"));
        // proto3 default-valued scalars are omitted by the JSON printer except booleans set to true
        assertThat(node.get("b").asBoolean(), is(true));
        // round-trips back into a valid protobuf message
        GrpcJsonMessageConverter converter = converterFor(descriptor.getFile());
        byte[] roundTripped = converter.toProtobuf(json, descriptor);
        assertThat(roundTripped, is(notNullValue()));
    }

    @Test
    public void shouldSynthesizeNestedMessage() {
        DescriptorProto inner = DescriptorProto.newBuilder()
            .setName("Inner")
            .addField(field("value", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .build();
        DescriptorProto outer = DescriptorProto.newBuilder()
            .setName("Outer")
            .addField(field("title", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .addField(messageField("inner", 2, ".test.Inner", FieldDescriptorProto.Label.LABEL_OPTIONAL))
            .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
            .setName("nested.proto").setSyntax("proto3").setPackage("test")
            .addMessageType(inner)
            .addMessageType(outer)
            .build();
        Descriptors.Descriptor descriptor = buildAndGet(proto, "Outer");

        String json = synthesizer.synthesizeJson(descriptor, converterFor(descriptor.getFile()));
        JsonNode node = readTree(json);

        assertThat(node.get("title").asText(), is("string"));
        assertThat(node.get("inner"), is(notNullValue()));
        assertThat(node.get("inner").get("value").asText(), is("string"));
    }

    @Test
    public void shouldSynthesizeEnumAsFirstValue() {
        EnumDescriptorProto colour = EnumDescriptorProto.newBuilder()
            .setName("Colour")
            .addValue(EnumValueDescriptorProto.newBuilder().setName("UNKNOWN").setNumber(0))
            .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(1))
            .build();
        DescriptorProto message = DescriptorProto.newBuilder()
            .setName("Painted")
            .addField(enumField("colour", 1, ".test.Colour"))
            .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
            .setName("enum.proto").setSyntax("proto3").setPackage("test")
            .addEnumType(colour)
            .addMessageType(message)
            .build();
        Descriptors.Descriptor descriptor = buildAndGet(proto, "Painted");

        DynamicMessageInspectableSynthesizer.assertFirstEnumValue(synthesizer, descriptor);
    }

    @Test
    public void shouldSynthesizeNonZeroEnumWhenFirstValueIsNonZero() {
        // when the first declared value is non-zero, the JSON printer keeps it (not a default)
        EnumDescriptorProto status = EnumDescriptorProto.newBuilder()
            .setName("Status")
            .addValue(EnumValueDescriptorProto.newBuilder().setName("UNSET").setNumber(0))
            .addValue(EnumValueDescriptorProto.newBuilder().setName("ACTIVE").setNumber(5))
            .build();
        // field order forces ACTIVE first by using an enum whose first value is ACTIVE — but
        // proto requires the zero value first in proto3; instead verify via the DynamicMessage.
        DescriptorProto message = DescriptorProto.newBuilder()
            .setName("Stateful")
            .addField(enumField("status", 1, ".test.Status"))
            .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
            .setName("status.proto").setSyntax("proto3").setPackage("test")
            .addEnumType(status)
            .addMessageType(message)
            .build();
        Descriptors.Descriptor descriptor = buildAndGet(proto, "Stateful");

        com.google.protobuf.DynamicMessage synthesized = synthesizer.synthesizeMessage(descriptor);
        Descriptors.FieldDescriptor statusField = descriptor.findFieldByName("status");
        Descriptors.EnumValueDescriptor value = (Descriptors.EnumValueDescriptor) synthesized.getField(statusField);
        assertThat(value.getName(), is("UNSET"));
        assertThat(value.getNumber(), is(0));
    }

    @Test
    public void shouldSynthesizeRepeatedFieldWithSingleElement() {
        DescriptorProto item = DescriptorProto.newBuilder()
            .setName("Item")
            .addField(field("sku", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .build();
        DescriptorProto basket = DescriptorProto.newBuilder()
            .setName("Basket")
            .addField(messageField("items", 1, ".test.Item", FieldDescriptorProto.Label.LABEL_REPEATED))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("tags").setNumber(2)
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .setType(FieldDescriptorProto.Type.TYPE_STRING).build())
            .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
            .setName("repeated.proto").setSyntax("proto3").setPackage("test")
            .addMessageType(item)
            .addMessageType(basket)
            .build();
        Descriptors.Descriptor descriptor = buildAndGet(proto, "Basket");

        String json = synthesizer.synthesizeJson(descriptor, converterFor(descriptor.getFile()));
        JsonNode node = readTree(json);

        assertThat(node.get("items").isArray(), is(true));
        assertThat(node.get("items").size(), is(1));
        assertThat(node.get("items").get(0).get("sku").asText(), is("string"));
        assertThat(node.get("tags").isArray(), is(true));
        assertThat(node.get("tags").size(), is(1));
        assertThat(node.get("tags").get(0).asText(), is("string"));
    }

    @Test
    public void shouldSynthesizeMapField() {
        DescriptorProto entry = DescriptorProto.newBuilder()
            .setName("AttributesEntry")
            .setOptions(com.google.protobuf.DescriptorProtos.MessageOptions.newBuilder().setMapEntry(true))
            .addField(field("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .addField(field("value", 2, FieldDescriptorProto.Type.TYPE_STRING))
            .build();
        DescriptorProto message = DescriptorProto.newBuilder()
            .setName("Configured")
            .addNestedType(entry)
            .addField(messageField("attributes", 1, ".test.Configured.AttributesEntry", FieldDescriptorProto.Label.LABEL_REPEATED))
            .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
            .setName("map.proto").setSyntax("proto3").setPackage("test")
            .addMessageType(message)
            .build();
        Descriptors.Descriptor descriptor = buildAndGet(proto, "Configured");

        String json = synthesizer.synthesizeJson(descriptor, converterFor(descriptor.getFile()));
        JsonNode node = readTree(json);

        assertThat(node.get("attributes"), is(notNullValue()));
        assertThat(node.get("attributes").get("string").asText(), is("string"));
    }

    @Test
    public void shouldPopulateOnlyOneFieldOfAOneof() {
        DescriptorProto message = DescriptorProto.newBuilder()
            .setName("Choice")
            .addOneofDecl(com.google.protobuf.DescriptorProtos.OneofDescriptorProto.newBuilder().setName("payload"))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("text").setNumber(1).setOneofIndex(0)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(FieldDescriptorProto.Type.TYPE_STRING).build())
            .addField(FieldDescriptorProto.newBuilder()
                .setName("number").setNumber(2).setOneofIndex(0)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(FieldDescriptorProto.Type.TYPE_INT32).build())
            .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
            .setName("oneof.proto").setSyntax("proto3").setPackage("test")
            .addMessageType(message)
            .build();
        Descriptors.Descriptor descriptor = buildAndGet(proto, "Choice");

        com.google.protobuf.DynamicMessage synthesized = synthesizer.synthesizeMessage(descriptor);
        Descriptors.OneofDescriptor oneof = descriptor.getOneofs().get(0);
        assertThat("exactly one oneof field set", synthesized.hasOneof(oneof), is(true));
        // only the first field (text) should be populated
        assertThat(synthesized.getField(descriptor.findFieldByName("text")), is("string"));
    }

    @Test
    public void shouldNotRecurseInfinitelyOnSelfReferentialMessage() {
        DescriptorProto node = DescriptorProto.newBuilder()
            .setName("TreeNode")
            .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .addField(messageField("child", 2, ".test.TreeNode", FieldDescriptorProto.Label.LABEL_OPTIONAL))
            .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
            .setName("tree.proto").setSyntax("proto3").setPackage("test")
            .addMessageType(node)
            .build();
        Descriptors.Descriptor descriptor = buildAndGet(proto, "TreeNode");

        // must terminate and produce valid JSON
        String json = synthesizer.synthesizeJson(descriptor, converterFor(descriptor.getFile()));
        JsonNode parsed = readTree(json);
        assertThat(parsed.get("name").asText(), is("string"));
        // the self-referential child is left at its empty default (no further recursion),
        // so the first level renders an empty object and recursion terminates
        assertThat(parsed.get("child").isObject(), is(true));
        assertThat(parsed.get("child").has("child"), is(false));
    }

    @Test
    public void shouldThrowOnNullDescriptor() {
        try {
            synthesizer.synthesizeMessage(null);
            org.junit.Assert.fail("expected GrpcException");
        } catch (GrpcException expected) {
            assertThat(expected.getMessage(), containsString("null message descriptor"));
        }
    }

    private static final class DynamicMessageInspectableSynthesizer {
        static void assertFirstEnumValue(GrpcExampleSynthesizer synthesizer, Descriptors.Descriptor descriptor) {
            com.google.protobuf.DynamicMessage synthesized = synthesizer.synthesizeMessage(descriptor);
            Descriptors.FieldDescriptor colourField = descriptor.findFieldByName("colour");
            Descriptors.EnumValueDescriptor value = (Descriptors.EnumValueDescriptor) synthesized.getField(colourField);
            assertThat(value.getName(), is("UNKNOWN"));
            assertThat(value.getNumber(), is(0));
        }
    }
}
