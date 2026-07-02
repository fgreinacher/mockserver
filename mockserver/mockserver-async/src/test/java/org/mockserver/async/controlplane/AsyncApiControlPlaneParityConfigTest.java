package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the Tier-2.4a broker-parity config fields parsed by
 * {@link AsyncApiControlPlaneImpl#parseBrokerConfig}: MQTT protocol version,
 * Kafka Avro value format, Schema Registry URL, and inline Avro schema.
 */
public class AsyncApiControlPlaneParityConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl();

    @After
    public void tearDown() {
        controlPlane.reset();
    }

    @Test
    public void shouldDefaultToMqttV3() throws Exception {
        JsonNode node = MAPPER.readTree("{\"mqttBrokerUrl\":\"tcp://localhost:1883\"}");
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);
        assertThat(config.mqttProtocolVersion, is(3));
    }

    @Test
    public void shouldParseMqttProtocolVersion5() throws Exception {
        JsonNode node = MAPPER.readTree(
            "{\"mqttBrokerUrl\":\"tcp://localhost:1883\",\"mqttProtocolVersion\":5}");
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);
        assertThat(config.mqttProtocolVersion, is(5));
    }

    @Test
    public void shouldDefaultToJsonValueFormat() throws Exception {
        JsonNode node = MAPPER.readTree("{\"kafkaBootstrapServers\":\"localhost:9092\"}");
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);
        assertThat(config.kafkaValueFormat, is("json"));
        assertThat(config.kafkaSchemaRegistryUrl, is(nullValue()));
        assertThat(config.avroSchema, is(nullValue()));
        assertThat(config.avroSchemaId, is(1));
    }

    @Test
    public void shouldParseAvroValueFormatWithRegistryAndSchema() throws Exception {
        String json = "{"
            + "\"kafkaBootstrapServers\":\"localhost:9092\","
            + "\"kafkaValueFormat\":\"AVRO\","
            + "\"kafkaSchemaRegistryUrl\":\"http://registry:8081\","
            + "\"avroSchemaId\":100005,"
            + "\"avroSchema\":\"{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"M\\\",\\\"fields\\\":[]}\""
            + "}";
        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.kafkaValueFormat, is("avro"));
        assertThat(config.kafkaSchemaRegistryUrl, is("http://registry:8081"));
        assertThat(config.avroSchemaId, is(100005));
        assertThat(config.avroSchema, containsString("\"record\""));
    }

    @Test
    public void shouldRejectUnsupportedMqttProtocolVersion() throws Exception {
        JsonNode node = MAPPER.readTree(
            "{\"mqttBrokerUrl\":\"tcp://localhost:1883\",\"mqttProtocolVersion\":4}");
        IllegalArgumentException e = org.junit.Assert.assertThrows(IllegalArgumentException.class,
            () -> controlPlane.parseBrokerConfig(node));
        assertThat(e.getMessage(), containsString("mqttProtocolVersion"));
        assertThat(e.getMessage(), containsString("3 or 5"));
    }

    @Test
    public void shouldRejectProtobufKafkaValueFormat() throws Exception {
        JsonNode node = MAPPER.readTree(
            "{\"kafkaBootstrapServers\":\"localhost:9092\",\"kafkaValueFormat\":\"protobuf\"}");
        IllegalArgumentException e = org.junit.Assert.assertThrows(IllegalArgumentException.class,
            () -> controlPlane.parseBrokerConfig(node));
        assertThat(e.getMessage(), containsString("kafkaValueFormat"));
        assertThat(e.getMessage(), containsString("protobuf is not yet supported"));
    }

    @Test
    public void shouldRejectUnknownKafkaValueFormat() throws Exception {
        JsonNode node = MAPPER.readTree(
            "{\"kafkaBootstrapServers\":\"localhost:9092\",\"kafkaValueFormat\":\"yaml\"}");
        IllegalArgumentException e = org.junit.Assert.assertThrows(IllegalArgumentException.class,
            () -> controlPlane.parseBrokerConfig(node));
        assertThat(e.getMessage(), containsString("kafkaValueFormat"));
        assertThat(e.getMessage(), containsString("json or avro"));
    }

    @Test
    public void shouldAcceptInlineAvroSchemaObject() throws Exception {
        String json = "{"
            + "\"kafkaBootstrapServers\":\"localhost:9092\","
            + "\"kafkaValueFormat\":\"avro\","
            + "\"avroSchema\":{\"type\":\"record\",\"name\":\"Order\",\"fields\":[]}"
            + "}";
        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.avroSchema, is(notNullValue()));
        assertThat(config.avroSchema, containsString("\"Order\""));
        // must be valid JSON text (object serialized back to a string)
        assertThat(MAPPER.readTree(config.avroSchema).get("name").asText(), is("Order"));
    }
}
