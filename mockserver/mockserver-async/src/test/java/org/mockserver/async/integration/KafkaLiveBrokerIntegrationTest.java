package org.mockserver.async.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.KafkaMessagePublisher;
import org.mockserver.async.subscribe.KafkaMessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;
import org.mockserver.async.AsyncApiMockOrchestrator;
import org.mockserver.async.MessageExampleGenerator;
import org.mockserver.test.DockerAvailability;
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
 * Integration tests for Kafka publishing and subscribing using a real Kafka broker
 * via Testcontainers. These tests are Docker-gated: they will SKIP (not fail) when
 * Docker is not available.
 */
public class KafkaLiveBrokerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KafkaContainer kafka;
    private static boolean dockerAvailable;

    @BeforeClass
    public static void checkDockerAndStartKafka() {
        // Shared fail-safe wrapper: the local catch(Exception) here missed Errors (e.g. a broken
        // test classpath surfacing as NoClassDefFoundError), and duplicating the guard per module
        // is how it drifts. See DockerAvailability for why the probe must never propagate.
        dockerAvailable = DockerAvailability.isAvailable(
            () -> org.testcontainers.DockerClientFactory.instance().isDockerAvailable());
        Assume.assumeTrue("Docker is not available — skipping Kafka integration tests", dockerAvailable);

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
    public void shouldPublishAndConsumeMessageViaLiveKafkaBroker() {
        String topic = "test-publish-consume";

        // Publish a message using MockServer's KafkaMessagePublisher
        KafkaMessagePublisher publisher = new KafkaMessagePublisher(kafka.getBootstrapServers());
        publisher.publish(topic, "{\"orderId\":42}");
        publisher.close();

        // Consume via a plain Kafka consumer to verify the message landed
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            String receivedPayload = null;
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.topic().equals(topic)) {
                        receivedPayload = record.value();
                        break;
                    }
                }
                if (receivedPayload != null) break;
            }
            assertThat("message should have been received from Kafka", receivedPayload, is("{\"orderId\":42}"));
        }
    }

    @Test
    public void shouldPublishWithKeyAndHeadersViaLiveBroker() {
        String topic = "test-key-headers";

        KafkaMessagePublisher publisher = new KafkaMessagePublisher(kafka.getBootstrapServers());
        publisher.publish(topic, "order-123", "{\"id\":1}", Collections.singletonMap("trace-id", "abc-123"));
        publisher.close();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-key-headers-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            ConsumerRecord<String, String> received = null;
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.topic().equals(topic)) {
                        received = record;
                        break;
                    }
                }
                if (received != null) break;
            }
            assertThat("message should have been received", received, is(notNullValue()));
            assertThat(received.key(), is("order-123"));
            assertThat(received.value(), is("{\"id\":1}"));
            assertThat(received.headers().lastHeader("trace-id"), is(notNullValue()));
        }
    }

    @Test
    public void subscriberShouldRecordMessagesFromLiveBroker() throws Exception {
        String topic = "test-subscriber-recording";

        // First publish a message
        KafkaMessagePublisher publisher = new KafkaMessagePublisher(kafka.getBootstrapServers());
        publisher.publish(topic, "key-1", "{\"event\":\"created\"}", null);
        publisher.close();

        // Subscribe using MockServer's KafkaMessageSubscriber
        KafkaMessageSubscriber subscriber = new KafkaMessageSubscriber(
            kafka.getBootstrapServers(), "test-subscriber-group"
        );
        subscriber.subscribe(topic);

        // Wait for the message to be recorded
        List<RecordedMessage> messages = Collections.emptyList();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            messages = subscriber.getRecordedMessages(topic);
            if (!messages.isEmpty()) break;
            Thread.sleep(200);
        }

        assertThat("subscriber should have recorded at least one message", messages.size(), greaterThanOrEqualTo(1));
        RecordedMessage recorded = messages.get(0);
        assertThat(recorded.getChannel(), is(topic));
        assertThat(recorded.getKey(), is("key-1"));
        assertThat(recorded.getPayload(), is("{\"event\":\"created\"}"));

        subscriber.close();
    }

    @Test
    public void orchestratorShouldPublishExamplesToLiveBroker() {
        String topic = "test-orchestrator";

        // Build a minimal AsyncApiSpec with one channel
        JsonNode example;
        try {
            example = MAPPER.readTree("{\"hello\":\"world\"}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        AsyncApiChannel channel = new AsyncApiChannel(topic, List.of(example), null);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test API", List.of(channel));

        KafkaMessagePublisher publisher = new KafkaMessagePublisher(kafka.getBootstrapServers());
        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher, new MessageExampleGenerator());
        orchestrator.publishAll();
        publisher.close();

        // Verify message landed
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-orchestrator-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            String receivedPayload = null;
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.topic().equals(topic)) {
                        receivedPayload = record.value();
                        break;
                    }
                }
                if (receivedPayload != null) break;
            }
            assertThat("orchestrator-published message should land on Kafka",
                receivedPayload, is("{\"hello\":\"world\"}"));
        }
    }
}
