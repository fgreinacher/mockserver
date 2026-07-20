package org.mockserver.async.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.MqttMessagePublisher;
import org.mockserver.async.subscribe.MqttMessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;
import org.mockserver.async.AsyncApiMockOrchestrator;
import org.mockserver.async.MessageExampleGenerator;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for MQTT publishing and subscribing using a real Mosquitto broker
 * via Testcontainers. These tests are Docker-gated: they will SKIP (not fail) when
 * Docker is not available.
 */
public class MqttLiveBrokerIntegrationTest {

    private static final int MQTT_PORT = 1883;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("resource")
    private static GenericContainer<?> mosquitto;
    private static boolean dockerAvailable;
    private static String brokerUrl;

    @BeforeClass
    public static void checkDockerAndStartMosquitto() {
        // Shared fail-safe wrapper: the local catch(Exception) here missed Errors (e.g. a broken
        // test classpath surfacing as NoClassDefFoundError), and duplicating the guard per module
        // is how it drifts. See DockerAvailability for why the probe must never propagate.
        dockerAvailable = DockerAvailability.isAvailable(
            () -> org.testcontainers.DockerClientFactory.instance().isDockerAvailable());
        Assume.assumeTrue("Docker is not available — skipping MQTT integration tests", dockerAvailable);

        // mosquitto 2.0 changed secure defaults: with no config (or `-c /dev/null`, which 2.0.22
        // rejects outright as "not a file") it binds loopback-only and disables anonymous access,
        // so connections from the host would be refused. Supply a minimal valid config that opens a
        // listener reachable from the host and enables anonymous access, then run the image's
        // default `mosquitto -c <conf>` command against it.
        String mosquittoConfig = "listener " + MQTT_PORT + "\nallow_anonymous true\n";
        mosquitto = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2.0.22"))
            .withExposedPorts(MQTT_PORT)
            .withCopyToContainer(Transferable.of(mosquittoConfig), "/mosquitto/config/mosquitto.conf")
            .withCommand("mosquitto", "-c", "/mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forLogMessage(".*mosquitto.*running.*", 1)
                .withStartupTimeout(java.time.Duration.ofSeconds(30)));
        mosquitto.start();

        brokerUrl = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(MQTT_PORT);
    }

    @AfterClass
    public static void stopMosquitto() {
        if (mosquitto != null && mosquitto.isRunning()) {
            mosquitto.stop();
        }
    }

    @Test
    public void shouldPublishAndReceiveMessageViaLiveMqttBroker() throws Exception {
        String topic = "test/publish-receive";

        // Set up a plain Paho subscriber to verify the message arrives
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        MqttClient subscriber = new MqttClient(brokerUrl, "test-plain-subscriber");
        subscriber.connect();
        subscriber.subscribe(topic, (t, msg) -> {
            receivedPayload.set(new String(msg.getPayload(), StandardCharsets.UTF_8));
            latch.countDown();
        });

        // Publish using MockServer's MqttMessagePublisher
        MqttMessagePublisher publisher = new MqttMessagePublisher(brokerUrl, "test-publisher");
        publisher.publish(topic, "{\"orderId\":42}");

        // Wait for the message to arrive
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertThat("message should have been received from MQTT", received, is(true));
        assertThat(receivedPayload.get(), is("{\"orderId\":42}"));

        publisher.close();
        subscriber.disconnect();
        subscriber.close();
    }

    @Test
    public void shouldPublishBinaryPayloadViaLiveBroker() throws Exception {
        String topic = "test/binary";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<byte[]> receivedBytes = new AtomicReference<>();

        MqttClient subscriber = new MqttClient(brokerUrl, "test-binary-subscriber");
        subscriber.connect();
        subscriber.subscribe(topic, (t, msg) -> {
            receivedBytes.set(msg.getPayload());
            latch.countDown();
        });

        MqttMessagePublisher publisher = new MqttMessagePublisher(brokerUrl, "test-binary-publisher");
        byte[] payload = new byte[]{0x00, 0x01, 0x02, 0x03};
        publisher.publishBytes(topic, payload);

        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertThat("binary message should have been received", received, is(true));
        assertThat(receivedBytes.get(), is(payload));

        publisher.close();
        subscriber.disconnect();
        subscriber.close();
    }

    @Test
    public void subscriberShouldRecordMessagesFromLiveBroker() throws Exception {
        String topic = "test/subscriber-record";

        // Start MockServer's subscriber first
        MqttMessageSubscriber msSubscriber = new MqttMessageSubscriber(
            brokerUrl, "test-ms-subscriber"
        );
        msSubscriber.subscribe(topic);

        // Give the subscription a moment to register
        Thread.sleep(500);

        // Publish a message using a plain Paho client
        MqttClient publisher = new MqttClient(brokerUrl, "test-plain-publisher");
        publisher.connect();
        publisher.publish(topic, new MqttMessage("{\"event\":\"created\"}".getBytes(StandardCharsets.UTF_8)));
        publisher.disconnect();
        publisher.close();

        // Wait for the subscriber to record the message
        List<RecordedMessage> messages = List.of();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            messages = msSubscriber.getRecordedMessages(topic);
            if (!messages.isEmpty()) break;
            Thread.sleep(200);
        }

        assertThat("subscriber should have recorded at least one message", messages.size(), greaterThanOrEqualTo(1));
        RecordedMessage recorded = messages.get(0);
        assertThat(recorded.getChannel(), is(topic));
        assertThat(recorded.getPayload(), is("{\"event\":\"created\"}"));

        msSubscriber.close();
    }

    /**
     * A wildcard subscription must be retrievable by the filter it was subscribed with.
     *
     * <p>The broker delivers each message on its concrete topic, so messages are recorded
     * under that topic — retrieving by the wildcard filter must match them (MQTT 3.1.1 §4.7).
     * Otherwise a wildcard subscription records messages that can never be retrieved and every
     * verification against it silently reports zero matches.
     */
    @Test
    public void subscriberShouldRecordWildcardSubscriptionFromLiveBroker() throws Exception {
        String filter = "wildcard/+/events";

        MqttMessageSubscriber msSubscriber = new MqttMessageSubscriber(
            brokerUrl, "test-wildcard-subscriber"
        );
        msSubscriber.subscribe(filter);
        Thread.sleep(500);

        MqttClient publisher = new MqttClient(brokerUrl, "test-wildcard-publisher");
        publisher.connect();
        publisher.publish("wildcard/alpha/events",
            new MqttMessage("{\"n\":1}".getBytes(StandardCharsets.UTF_8)));
        publisher.publish("wildcard/beta/events",
            new MqttMessage("{\"n\":2}".getBytes(StandardCharsets.UTF_8)));
        publisher.disconnect();
        publisher.close();

        List<RecordedMessage> messages = List.of();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            messages = msSubscriber.getRecordedMessages(filter);
            if (messages.size() >= 2) break;
            Thread.sleep(200);
        }

        assertThat("messages delivered on concrete topics must be retrievable by the wildcard filter",
            messages.size(), is(2));
        assertThat(messages.stream().map(RecordedMessage::getPayload).toList(),
            containsInAnyOrder("{\"n\":1}", "{\"n\":2}"));
        assertThat("recorded channel is the concrete topic the broker delivered on",
            messages.stream().map(RecordedMessage::getChannel).toList(),
            containsInAnyOrder("wildcard/alpha/events", "wildcard/beta/events"));

        msSubscriber.close();
    }

    @Test
    public void orchestratorShouldPublishExamplesToLiveMqttBroker() throws Exception {
        String topic = "test/orchestrator";

        // Set up a plain subscriber to verify
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        MqttClient subscriber = new MqttClient(brokerUrl, "test-orch-subscriber");
        subscriber.connect();
        subscriber.subscribe(topic, (t, msg) -> {
            receivedPayload.set(new String(msg.getPayload(), StandardCharsets.UTF_8));
            latch.countDown();
        });

        // Build a minimal AsyncApiSpec and use the orchestrator
        JsonNode example = MAPPER.readTree("{\"hello\":\"mqtt\"}");
        AsyncApiChannel channel = new AsyncApiChannel(topic, List.of(example), null);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "MQTT Test", List.of(channel));

        MqttMessagePublisher publisher = new MqttMessagePublisher(brokerUrl, "test-orch-publisher");
        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(
            spec, publisher, new MessageExampleGenerator()
        );
        orchestrator.publishAll();

        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertThat("orchestrator-published message should land on MQTT", received, is(true));
        assertThat(receivedPayload.get(), is("{\"hello\":\"mqtt\"}"));

        publisher.close();
        subscriber.disconnect();
        subscriber.close();
    }

    @Test
    public void shouldRoundTripPublishAndSubscribeViaMockServerAdapters() throws Exception {
        String topic = "test/roundtrip";

        // Start MockServer's subscriber
        MqttMessageSubscriber msSubscriber = new MqttMessageSubscriber(
            brokerUrl, "test-rt-subscriber"
        );
        msSubscriber.subscribe(topic);

        // Give subscription time to register
        Thread.sleep(500);

        // Publish via MockServer's publisher
        MqttMessagePublisher publisher = new MqttMessagePublisher(brokerUrl, "test-rt-publisher");
        publisher.publish(topic, "{\"roundtrip\":true}");

        // Wait for subscriber to record the message
        List<RecordedMessage> messages = List.of();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            messages = msSubscriber.getRecordedMessages(topic);
            if (!messages.isEmpty()) break;
            Thread.sleep(200);
        }

        assertThat("round-trip message should be recorded", messages.size(), greaterThanOrEqualTo(1));
        assertThat(messages.get(0).getPayload(), is("{\"roundtrip\":true}"));

        publisher.close();
        msSubscriber.close();
    }
}
