package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for the AsyncAPI control-plane {@code load()} → live-broker path
 * against a real Kafka broker via Testcontainers.
 * <p>
 * The broker-LESS control-plane HTTP endpoints are already covered by
 * {@code AsyncApiControlPlaneIntegrationTest} (mockserver-netty), and the unit
 * {@code AsyncApiControlPlaneImplTest} loads WITHOUT a reachable broker (asserting
 * publishers=0/subscribers=0 or DNS-resolvable-but-not-listening fallbacks). Neither
 * exercises the part of {@link AsyncApiControlPlaneImpl#load(String)} that actually
 * connects to a broker and publishes: {@code createBrokerConnections} and the
 * {@code publishOnLoad} one-shot publish.
 * <p>
 * This test drives {@code load()} with a real {@code brokerConfig} pointing at a live
 * Kafka broker and {@code publishOnLoad:true}, then proves the control-plane genuinely
 * connected and published by consuming the on-load message with a plain (third-party)
 * Kafka client — and that {@code status()} reports {@code publishers>0}, a subscriber,
 * and the recorded round-tripped message.
 * <p>
 * Docker-gated: SKIPS (does not fail) when Docker is not available.
 */
public class AsyncApiControlPlaneLiveBrokerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KafkaContainer kafka;

    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl();

    @BeforeClass
    public static void checkDockerAndStartKafka() {
        // Shared fail-safe probe (lambda, per AGENTS.md): a bare catch(Exception) would miss
        // Errors such as a NoClassDefFoundError from an incomplete test classpath, defeating the
        // assume-guard. DockerAvailability contains the try/catch so this SKIPS off-Docker.
        Assume.assumeTrue("Docker is not available — skipping AsyncAPI control-plane live-broker test",
            DockerAvailability.isAvailable(
                () -> org.testcontainers.DockerClientFactory.instance().isDockerAvailable()));

        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        kafka.start();
    }

    @AfterClass
    public static void stopKafka() {
        if (kafka != null && kafka.isRunning()) {
            kafka.stop();
        }
    }

    @After
    public void tearDown() {
        controlPlane.reset();
    }

    @Test
    public void loadShouldConnectToLiveBrokerAndPublishOnLoad() throws Exception {
        // A channel name doubles as the Kafka topic. Use a distinct topic so the on-load publish
        // and both consumers (third-party + control-plane subscriber) see only this test's message.
        String topic = "control-plane-live-load";
        String expectedPayload = "{\"orderId\":42,\"status\":\"created\"}";

        // Given: a spec whose single channel carries an explicit example (so the published payload
        // is deterministic), wrapped with a brokerConfig pointing at the live Kafka broker,
        // publishOnLoad:true (fire the one-shot publish) and consume:true (start a subscriber so
        // status() round-trips the message back).
        String requestBody = buildRequestBody(topic, kafka.getBootstrapServers());

        // When: the control-plane loads the spec — this drives createBrokerConnections + publishOnLoad
        JsonNode loadResult = controlPlane.load(requestBody);

        // Then: the load response reports a live publisher and subscriber were created
        assertThat("spec should have loaded", loadResult.get("loaded").asBoolean(), is(true));
        assertThat("a Kafka publisher should have been created against the live broker",
            loadResult.get("publishers").asInt(), greaterThanOrEqualTo(1));
        assertThat("no load-time publish failure should have been recorded",
            loadResult.has("validationIssues"), is(false));

        // And: a plain, third-party Kafka consumer (the broker's own client) sees the message the
        // control-plane published on load — proving it actually connected to and published to the
        // live broker, not merely constructed a publisher object.
        String consumedByThirdParty = consumeOne(topic, "third-party-verify-group");
        assertThat("the control-plane's publishOnLoad message should have landed on the live broker",
            consumedByThirdParty, is(expectedPayload));

        // And: status() reports the live publisher, the subscriber, and the recorded round-tripped
        // message consumed back by the control-plane's own subscriber (consume:true).
        JsonNode status = awaitRecordedMessage(TimeUnit.SECONDS.toMillis(30));
        assertThat(status.get("loaded").asBoolean(), is(true));
        assertThat("status should report the live publisher",
            status.get("publishers").asInt(), greaterThanOrEqualTo(1));
        assertThat("status should report the live subscriber",
            status.get("subscribers").asInt(), greaterThanOrEqualTo(1));
        assertThat("status should not report a scheduled-publish failure",
            status.has("lastPublishFailure"), is(false));

        JsonNode recorded = status.get("recordedMessages");
        assertThat("the control-plane subscriber should have recorded the on-load message",
            recorded.size(), greaterThanOrEqualTo(1));
        JsonNode first = recorded.get(0);
        assertThat(first.get("channel").asText(), is(topic));
        assertThat(first.get("payload").asText(), is(expectedPayload));
    }

    private static String buildRequestBody(String topic, String bootstrapServers) throws Exception {
        ObjectNode payloadSchema = MAPPER.createObjectNode();
        payloadSchema.put("type", "object");
        ObjectNode example = payloadSchema.putObject("example");
        example.put("orderId", 42);
        example.put("status", "created");

        ObjectNode message = MAPPER.createObjectNode();
        message.set("payload", payloadSchema);

        ObjectNode publish = MAPPER.createObjectNode();
        publish.set("message", message);

        ObjectNode channel = MAPPER.createObjectNode();
        channel.set("publish", publish);

        ObjectNode channels = MAPPER.createObjectNode();
        channels.set(topic, channel);

        ObjectNode info = MAPPER.createObjectNode();
        info.put("title", "Control-Plane Live Broker");
        info.put("version", "1.0.0");

        ObjectNode spec = MAPPER.createObjectNode();
        spec.put("asyncapi", "2.6.0");
        spec.set("info", info);
        spec.set("channels", channels);

        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("kafkaBootstrapServers", bootstrapServers);
        brokerConfig.put("publishOnLoad", true);
        brokerConfig.put("consume", true);
        brokerConfig.put("kafkaGroupId", "control-plane-subscriber-group");

        ObjectNode body = MAPPER.createObjectNode();
        body.set("spec", spec);
        body.set("brokerConfig", brokerConfig);

        return MAPPER.writeValueAsString(body);
    }

    /**
     * Consume the first message on the topic with a plain Kafka consumer, from the earliest offset.
     */
    private static String consumeOne(String topic, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.topic().equals(topic)) {
                        return record.value();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Poll the control-plane {@code status()} until its subscriber has recorded at least one
     * message or the deadline elapses.
     */
    private JsonNode awaitRecordedMessage(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        JsonNode status = controlPlane.status();
        while (System.currentTimeMillis() < deadline) {
            status = controlPlane.status();
            if (status.get("recordedMessages").size() >= 1) {
                return status;
            }
            Thread.sleep(250);
        }
        return status;
    }
}
