package org.mockserver.async.serde;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

/**
 * Unit tests for {@link AvroPayloadCodec} — JSON &lt;-&gt; Avro binary conversion.
 */
public class AvroPayloadCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ORDER_SCHEMA =
        "{\"type\":\"record\",\"name\":\"Order\",\"fields\":["
            + "{\"name\":\"orderId\",\"type\":\"int\"},"
            + "{\"name\":\"item\",\"type\":\"string\"}]}";

    @Test
    public void shouldRoundTripJsonThroughAvroBinary() throws Exception {
        Schema schema = AvroPayloadCodec.parseSchema(ORDER_SCHEMA);
        String json = "{\"orderId\":42,\"item\":\"widget\"}";

        byte[] avroBinary = AvroPayloadCodec.jsonToAvro(schema, json);
        // Avro binary is more compact than JSON and is not the JSON text
        assertThat(avroBinary.length, greaterThan(0));

        String decodedJson = AvroPayloadCodec.avroToJson(schema, avroBinary);
        // Compare semantically (field order / spacing may differ)
        assertThat(MAPPER.readTree(decodedJson), is(MAPPER.readTree(json)));
    }

    @Test
    public void shouldEncodeDifferentValuesDistinctly() throws Exception {
        Schema schema = AvroPayloadCodec.parseSchema(ORDER_SCHEMA);
        byte[] a = AvroPayloadCodec.jsonToAvro(schema, "{\"orderId\":1,\"item\":\"a\"}");
        byte[] b = AvroPayloadCodec.jsonToAvro(schema, "{\"orderId\":2,\"item\":\"bb\"}");
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    public void shouldPreserveNumericAndStringTypes() throws Exception {
        String schemaJson =
            "{\"type\":\"record\",\"name\":\"M\",\"fields\":["
                + "{\"name\":\"count\",\"type\":\"long\"},"
                + "{\"name\":\"ratio\",\"type\":\"double\"},"
                + "{\"name\":\"flag\",\"type\":\"boolean\"}]}";
        Schema schema = AvroPayloadCodec.parseSchema(schemaJson);
        String json = "{\"count\":9999999999,\"ratio\":3.5,\"flag\":true}";

        byte[] avroBinary = AvroPayloadCodec.jsonToAvro(schema, json);
        String decodedJson = AvroPayloadCodec.avroToJson(schema, avroBinary);
        assertThat(MAPPER.readTree(decodedJson), is(MAPPER.readTree(json)));
    }

    @Test
    public void shouldThrowOnPayloadNotConformingToSchema() {
        Schema schema = AvroPayloadCodec.parseSchema(ORDER_SCHEMA);
        // orderId should be an int, not a string
        assertThrows(Exception.class,
            () -> AvroPayloadCodec.jsonToAvro(schema, "{\"orderId\":\"not-an-int\",\"item\":\"x\"}"));
    }
}
