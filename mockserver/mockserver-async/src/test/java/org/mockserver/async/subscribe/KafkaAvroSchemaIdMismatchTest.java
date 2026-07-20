package org.mockserver.async.subscribe;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.Test;
import org.mockserver.async.serde.AvroPayloadCodec;
import org.mockserver.async.serde.ConfluentWireFormat;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;

/**
 * Registry-less Avro decoding must honour the schema id embedded in the Confluent wire format.
 *
 * <p>Avro binary carries no field names or types — only values in schema order. Decoding a
 * message written with one schema against a <i>different</i> schema of the same shape therefore
 * succeeds and produces <b>silently wrong values</b>, which is worse than failing. When the
 * embedded schema id does not correspond to the configured inline schema, the message must not
 * be decoded with it.
 */
public class KafkaAvroSchemaIdMismatchTest {

    /** The schema the subscriber is configured with (id 1). */
    private static final String CONFIGURED_SCHEMA = "{"
        + "\"type\":\"record\",\"name\":\"User\",\"fields\":["
        + "{\"name\":\"firstName\",\"type\":\"string\"},"
        + "{\"name\":\"lastName\",\"type\":\"string\"}]}";

    /**
     * A different schema (id 7) with the same wire shape but the fields in the opposite order —
     * binary written with this schema decodes cleanly against the configured one, with the two
     * values transposed and no error raised.
     */
    private static final String OTHER_SCHEMA = "{"
        + "\"type\":\"record\",\"name\":\"User\",\"fields\":["
        + "{\"name\":\"lastName\",\"type\":\"string\"},"
        + "{\"name\":\"firstName\",\"type\":\"string\"}]}";

    private static final int CONFIGURED_SCHEMA_ID = 1;
    private static final int OTHER_SCHEMA_ID = 7;

    @Test
    public void shouldDecodeMessageFramedWithTheConfiguredSchemaId() throws Exception {
        KafkaAvroMessageSubscriber subscriber = subscriber();

        byte[] framed = ConfluentWireFormat.encode(CONFIGURED_SCHEMA_ID,
            AvroPayloadCodec.jsonToAvro(AvroPayloadCodec.parseSchema(CONFIGURED_SCHEMA),
                "{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"}"));

        String json = subscriber.decodeToJson(framed);

        assertThat(json, containsString("\"firstName\":\"Ada\""));
        assertThat(json, containsString("\"lastName\":\"Lovelace\""));
    }

    @Test
    public void shouldNotDecodeMessageFramedWithADifferentSchemaId() throws Exception {
        KafkaAvroMessageSubscriber subscriber = subscriber();

        // written by a producer using a different schema, framed with its own schema id
        byte[] framed = ConfluentWireFormat.encode(OTHER_SCHEMA_ID,
            AvroPayloadCodec.jsonToAvro(AvroPayloadCodec.parseSchema(OTHER_SCHEMA),
                "{\"lastName\":\"Lovelace\",\"firstName\":\"Ada\"}"));

        String json = subscriber.decodeToJson(framed);

        // decoding this against the configured schema would transpose the two values and
        // report Ada's surname as her forename, with no error
        assertThat("a mismatched schema id must not be decoded into transposed values",
            json, not(containsString("\"firstName\":\"Lovelace\"")));
        assertThat(json, not(containsString("\"lastName\":\"Ada\"")));
    }

    @Test
    public void shouldRecordNonAvroPayloadsUnchanged() {
        KafkaAvroMessageSubscriber subscriber = subscriber();

        String plain = "{\"not\":\"avro\"}";
        assertThat(subscriber.decodeToJson(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            is(plain));
    }

    @SuppressWarnings("unchecked")
    private static KafkaAvroMessageSubscriber subscriber() {
        return new KafkaAvroMessageSubscriber(
            mock(KafkaConsumer.class), 100, null, CONFIGURED_SCHEMA, CONFIGURED_SCHEMA_ID);
    }
}
