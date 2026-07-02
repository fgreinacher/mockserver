package org.mockserver.async.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.async.publish.KafkaAvroMessagePublisher;
import org.mockserver.async.serde.AvroPayloadCodec;
import org.mockserver.async.serde.ConfluentWireFormat;
import org.mockserver.async.subscribe.KafkaAvroMessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Kafka <b>Avro / Confluent wire-format</b> serde against a
 * real Kafka broker via Testcontainers, in <b>registry-less</b> mode (a fixed schema
 * id + inline schema on both ends). Docker-gated: SKIP when Docker is not available.
 *
 * <p>The Schema Registry HTTP protocol itself is covered by the non-Docker
 * {@code SchemaRegistryClientTest}; this test exercises the end-to-end framing,
 * Avro encode/decode, and real-broker delivery.
 */
public class KafkaAvroLiveBrokerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String AVRO_SCHEMA =
        "{\"type\":\"record\",\"name\":\"Order\",\"fields\":["
            + "{\"name\":\"orderId\",\"type\":\"int\"},"
            + "{\"name\":\"item\",\"type\":\"string\"}]}";

    private static KafkaContainer kafka;
    private static boolean dockerAvailable;

    @BeforeClass
    public static void checkDockerAndStartKafka() {
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assume.assumeTrue("Docker is not available — skipping Kafka Avro integration tests", dockerAvailable);

        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        kafka.start();
    }

    @AfterClass
    public static void stopKafka() {
        if (kafka != null && kafka.isRunning()) {
            kafka.stop();
        }
    }

    @Test
    public void shouldPublishConfluentFramedAvroReadableByPlainConsumer() throws Exception {
        String topic = "avro-publish";

        KafkaAvroMessagePublisher publisher = new KafkaAvroMessagePublisher(
            kafka.getBootstrapServers(), null, AVRO_SCHEMA, null, 77);
        publisher.publish(topic, "{\"orderId\":42,\"item\":\"widget\"}");
        publisher.close();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "avro-plain-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        byte[] value = null;
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            while (System.currentTimeMillis() < deadline && value == null) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    if (record.topic().equals(topic)) {
                        value = record.value();
                        break;
                    }
                }
            }
        }

        assertThat("Avro message should land on Kafka", value, is(notNullValue()));
        assertThat("value should carry the Confluent wire-format header",
            ConfluentWireFormat.isWireFormat(value), is(true));
        ConfluentWireFormat.Decoded decoded = ConfluentWireFormat.decode(value);
        assertThat(decoded.getSchemaId(), is(77));
        Schema schema = AvroPayloadCodec.parseSchema(AVRO_SCHEMA);
        String json = AvroPayloadCodec.avroToJson(schema, decoded.getPayload());
        assertThat(MAPPER.readTree(json), is(MAPPER.readTree("{\"orderId\":42,\"item\":\"widget\"}")));
    }

    @Test
    public void shouldRoundTripAvroThroughMockServerAdapters() throws Exception {
        String topic = "avro-roundtrip";

        KafkaAvroMessagePublisher publisher = new KafkaAvroMessagePublisher(
            kafka.getBootstrapServers(), null, AVRO_SCHEMA, null, 55);
        publisher.publish(topic, "{\"orderId\":9,\"item\":\"gadget\"}", null);
        publisher.close();

        // registry-less subscriber decodes with the inline schema and records JSON
        KafkaAvroMessageSubscriber subscriber = new KafkaAvroMessageSubscriber(
            kafka.getBootstrapServers(), "avro-roundtrip-group", 100, null, null, AVRO_SCHEMA);
        subscriber.subscribe(topic);

        List<RecordedMessage> messages = Collections.emptyList();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            messages = subscriber.getRecordedMessages(topic);
            if (!messages.isEmpty()) break;
            Thread.sleep(200);
        }

        assertThat("subscriber should record the decoded Avro message", messages.size(), greaterThanOrEqualTo(1));
        assertThat(MAPPER.readTree(messages.get(0).getPayload()),
            is(MAPPER.readTree("{\"orderId\":9,\"item\":\"gadget\"}")));

        subscriber.close();
    }
}
