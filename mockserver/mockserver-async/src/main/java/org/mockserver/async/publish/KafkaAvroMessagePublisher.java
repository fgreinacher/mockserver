package org.mockserver.async.publish;

import org.apache.avro.Schema;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.mockserver.async.security.KafkaSecurity;
import org.mockserver.async.security.KafkaSecurityProperties;
import org.mockserver.async.serde.AvroPayloadCodec;
import org.mockserver.async.serde.ConfluentWireFormat;
import org.mockserver.async.serde.SchemaRegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * A {@link MessagePublisher} that publishes <b>Avro</b> messages to Kafka in the
 * <b>Confluent Schema Registry wire format</b> (magic byte + schema id + Avro binary),
 * so real Confluent Avro consumers can read them.
 *
 * <p>MockServer's example payloads are JSON; this publisher encodes each JSON payload
 * to Avro binary ({@link AvroPayloadCodec}), resolves the schema id, and frames it
 * ({@link ConfluentWireFormat}). Two modes:
 * <ul>
 *   <li><b>Registry-backed</b> (a {@link SchemaRegistryClient} is supplied): the
 *       schema is registered under {@code <topic>-value} and its returned id is
 *       embedded — exactly what the Confluent serializers do.</li>
 *   <li><b>Registry-less</b> (no client): a fixed, configured schema id is embedded.
 *       Useful for closed round-trips where the same inline schema is used on both
 *       ends.</li>
 * </ul>
 */
public class KafkaAvroMessagePublisher implements MessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAvroMessagePublisher.class);

    private final KafkaProducer<String, byte[]> producer;
    private final Schema schema;
    private final String schemaJson;
    private final SchemaRegistryClient registryClient;
    private final int fixedSchemaId;

    /**
     * @param bootstrapServers comma-separated host:port pairs
     * @param security         Kafka security (may be null for plaintext)
     * @param schemaJson       the Avro schema JSON used to encode payloads (required)
     * @param registryClient   the Schema Registry client, or null for registry-less mode
     * @param fixedSchemaId    the schema id to embed in registry-less mode
     */
    public KafkaAvroMessagePublisher(String bootstrapServers, KafkaSecurity security,
                                     String schemaJson, SchemaRegistryClient registryClient,
                                     int fixedSchemaId) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new IllegalArgumentException("an Avro schema is required to publish Avro messages");
        }
        this.schemaJson = schemaJson;
        this.schema = AvroPayloadCodec.parseSchema(schemaJson);
        this.registryClient = registryClient;
        this.fixedSchemaId = fixedSchemaId;
        this.producer = new KafkaProducer<>(buildProducerProperties(bootstrapServers, security));
    }

    /**
     * Package-private constructor for injecting a mock producer in tests.
     */
    KafkaAvroMessagePublisher(KafkaProducer<String, byte[]> producer, String schemaJson,
                              SchemaRegistryClient registryClient, int fixedSchemaId) {
        this.producer = producer;
        this.schemaJson = schemaJson;
        this.schema = AvroPayloadCodec.parseSchema(schemaJson);
        this.registryClient = registryClient;
        this.fixedSchemaId = fixedSchemaId;
    }

    static Properties buildProducerProperties(String bootstrapServers, KafkaSecurity security) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        KafkaSecurityProperties.applySecurity(props, security);
        return props;
    }

    @Override
    public void publish(String channel, String payload) {
        publish(channel, payload, (PublishOptions) null);
    }

    @Override
    public void publish(String channel, String payload, PublishOptions options) {
        String key = (options != null) ? options.getKey() : null;
        Map<String, String> headers = (options != null && !options.getHeaders().isEmpty())
            ? options.getHeaders() : null;
        byte[] framed = encode(channel, payload);
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(channel, key, framed);
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                byte[] value = header.getValue() != null
                    ? header.getValue().getBytes(StandardCharsets.UTF_8) : null;
                record.headers().add(new RecordHeader(header.getKey(), value));
            }
        }
        LOG.debug("Publishing Avro message to Kafka topic '{}': key={}, {} bytes", channel, key, framed.length);
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                LOG.warn("Failed to deliver Avro message to Kafka topic '{}': {}", channel, exception.getMessage());
            }
        });
    }

    /**
     * Encode a JSON payload to Confluent-framed Avro bytes for the given topic.
     * Package-private so the framing/encoding can be unit-tested without a broker.
     */
    byte[] encode(String channel, String payload) {
        try {
            byte[] avroBinary = AvroPayloadCodec.jsonToAvro(schema, payload);
            int schemaId = registryClient != null
                ? registryClient.register(channel + "-value", schemaJson)
                : fixedSchemaId;
            return ConfluentWireFormat.encode(schemaId, avroBinary);
        } catch (Exception e) {
            throw new RuntimeException("Failed to Avro-encode payload for Kafka topic '" + channel + "': "
                + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
