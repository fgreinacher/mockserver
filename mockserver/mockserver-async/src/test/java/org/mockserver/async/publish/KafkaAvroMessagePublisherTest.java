package org.mockserver.async.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockserver.async.serde.AvroPayloadCodec;
import org.mockserver.async.serde.ConfluentWireFormat;
import org.mockserver.async.serde.SchemaRegistryClient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for {@link KafkaAvroMessagePublisher} using a mock producer and registry —
 * verifies Confluent wire-format framing without a live broker.
 */
public class KafkaAvroMessagePublisherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA =
        "{\"type\":\"record\",\"name\":\"Order\",\"fields\":["
            + "{\"name\":\"orderId\",\"type\":\"int\"},{\"name\":\"item\",\"type\":\"string\"}]}";

    @Mock
    private KafkaProducer<String, byte[]> mockProducer;

    @Before
    public void setUp() {
        openMocks(this);
    }

    @Test
    public void shouldFrameRegistrylessMessageWithFixedSchemaId() throws Exception {
        KafkaAvroMessagePublisher publisher =
            new KafkaAvroMessagePublisher(mockProducer, SCHEMA, null, 77);
        publisher.publish("orders", "{\"orderId\":42,\"item\":\"widget\"}");

        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = recordCaptor();
        verify(mockProducer).send(captor.capture(), any());
        byte[] value = captor.getValue().value();

        assertThat(ConfluentWireFormat.isWireFormat(value), is(true));
        ConfluentWireFormat.Decoded decoded = ConfluentWireFormat.decode(value);
        assertThat(decoded.getSchemaId(), is(77));

        // The Avro payload decodes back to the original JSON
        Schema schema = AvroPayloadCodec.parseSchema(SCHEMA);
        String json = AvroPayloadCodec.avroToJson(schema, decoded.getPayload());
        assertThat(MAPPER.readTree(json), is(MAPPER.readTree("{\"orderId\":42,\"item\":\"widget\"}")));
    }

    @Test
    public void shouldUseRegistryIdWhenRegistryPresent() {
        SchemaRegistryClient registry = mock(SchemaRegistryClient.class);
        when(registry.register(eq("orders-value"), any())).thenReturn(555);

        KafkaAvroMessagePublisher publisher =
            new KafkaAvroMessagePublisher(mockProducer, SCHEMA, registry, 1);
        publisher.publish("orders", "{\"orderId\":1,\"item\":\"x\"}");

        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = recordCaptor();
        verify(mockProducer).send(captor.capture(), any());
        ConfluentWireFormat.Decoded decoded = ConfluentWireFormat.decode(captor.getValue().value());
        assertThat(decoded.getSchemaId(), is(555));
        verify(registry).register(eq("orders-value"), any());
    }

    @Test
    public void shouldApplyKeyAndHeadersFromPublishOptions() {
        KafkaAvroMessagePublisher publisher =
            new KafkaAvroMessagePublisher(mockProducer, SCHEMA, null, 1);
        PublishOptions options = new PublishOptions("order-key", null, null,
            java.util.Map.of("correlationId", "corr-9"));
        publisher.publish("orders", "{\"orderId\":3,\"item\":\"y\"}", options);

        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = recordCaptor();
        verify(mockProducer).send(captor.capture(), any());
        assertThat(captor.getValue().key(), is("order-key"));
        assertThat(captor.getValue().headers().lastHeader("correlationId"), is(notNullValue()));
    }

    @Test
    public void shouldRejectMissingSchema() {
        assertThrows(IllegalArgumentException.class,
            () -> new KafkaAvroMessagePublisher("localhost:9092", null, null, null, 1));
    }

    @Test
    public void shouldRaiseWhenPayloadDoesNotMatchSchema() {
        KafkaAvroMessagePublisher publisher =
            new KafkaAvroMessagePublisher(mockProducer, SCHEMA, null, 1);
        assertThrows(RuntimeException.class,
            () -> publisher.publish("orders", "{\"orderId\":\"not-an-int\",\"item\":\"z\"}"));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<ProducerRecord<String, byte[]>> recordCaptor() {
        return ArgumentCaptor.forClass(ProducerRecord.class);
    }
}
