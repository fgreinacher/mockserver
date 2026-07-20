package org.mockserver.async.integration;

import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.async.publish.Mqtt5MessagePublisher;
import org.mockserver.async.publish.PublishOptions;
import org.mockserver.async.subscribe.Mqtt5MessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for MQTT <b>5</b> publishing and subscribing using a real
 * Mosquitto broker via Testcontainers. Mosquitto 2.x speaks MQTT 5. Docker-gated:
 * these tests SKIP (not fail) when Docker is not available.
 */
public class Mqtt5LiveBrokerIntegrationTest {

    private static final int MQTT_PORT = 1883;

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
        Assume.assumeTrue("Docker is not available — skipping MQTT 5 integration tests", dockerAvailable);

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
    public void shouldPublishAndReceiveViaMqtt5() throws Exception {
        String topic = "test5/publish-receive";

        // Observe via MockServer's own v5 subscriber (uses the safe 2-arg subscribe;
        // Paho v5 1.2.5's 3-arg subscribe(topic, qos, listener) overload self-recurses).
        Mqtt5MessageSubscriber subscriber = new Mqtt5MessageSubscriber(brokerUrl, "test5-recv-sub");
        subscriber.subscribe(topic);
        Thread.sleep(500);

        Mqtt5MessagePublisher publisher = new Mqtt5MessagePublisher(brokerUrl, "test5-publisher");
        publisher.publish(topic, "{\"orderId\":42}");

        List<RecordedMessage> messages = pollRecorded(subscriber, topic);
        assertThat("message should arrive over MQTT 5", messages.size(), greaterThanOrEqualTo(1));
        assertThat(messages.get(0).getPayload(), is("{\"orderId\":42}"));

        publisher.close();
        subscriber.close();
    }

    @Test
    public void shouldDeliverUserPropertiesViaMqtt5() throws Exception {
        String topic = "test5/user-properties";

        Mqtt5MessageSubscriber subscriber = new Mqtt5MessageSubscriber(brokerUrl, "test5-props-sub");
        subscriber.subscribe(topic);
        Thread.sleep(500);

        Mqtt5MessagePublisher publisher = new Mqtt5MessagePublisher(brokerUrl, "test5-props-pub");
        PublishOptions options = new PublishOptions(null, null, null, Map.of("correlationId", "corr-xyz"));
        publisher.publish(topic, "{\"a\":1}", options);

        List<RecordedMessage> messages = pollRecorded(subscriber, topic);
        assertThat("message with user property should arrive", messages.size(), greaterThanOrEqualTo(1));
        // MQTT 5 user properties round-trip through a real broker into RecordedMessage headers
        assertThat(messages.get(0).getHeaders(), hasEntry("correlationId", "corr-xyz"));

        publisher.close();
        subscriber.close();
    }

    private static List<RecordedMessage> pollRecorded(Mqtt5MessageSubscriber subscriber, String topic)
        throws InterruptedException {
        List<RecordedMessage> messages = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            messages = subscriber.getRecordedMessages(topic);
            if (!messages.isEmpty()) break;
            Thread.sleep(200);
        }
        return messages;
    }

    @Test
    public void subscriberShouldRecordViaMqtt5() throws Exception {
        String topic = "test5/subscriber-record";

        Mqtt5MessageSubscriber subscriber = new Mqtt5MessageSubscriber(brokerUrl, "test5-ms-sub");
        subscriber.subscribe(topic);
        Thread.sleep(500);

        // publish via a plain v5 client with a user property
        MqttClient plainPub = new MqttClient(brokerUrl, "test5-plain-pub");
        plainPub.connect();
        MqttMessage message = new MqttMessage("{\"event\":\"created\"}".getBytes(StandardCharsets.UTF_8));
        MqttProperties props = new MqttProperties();
        props.setUserProperties(List.of(new UserProperty("trace", "t-1")));
        message.setProperties(props);
        plainPub.publish(topic, message);
        plainPub.disconnect();
        plainPub.close();

        List<RecordedMessage> messages = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            messages = subscriber.getRecordedMessages(topic);
            if (!messages.isEmpty()) break;
            Thread.sleep(200);
        }

        assertThat("subscriber should record the message", messages.size(), greaterThanOrEqualTo(1));
        assertThat(messages.get(0).getPayload(), is("{\"event\":\"created\"}"));
        assertThat(messages.get(0).getHeaders(), hasEntry("trace", "t-1"));

        subscriber.close();
    }

    @Test
    public void shouldRoundTripViaMqtt5Adapters() throws Exception {
        String topic = "test5/roundtrip";

        Mqtt5MessageSubscriber subscriber = new Mqtt5MessageSubscriber(brokerUrl, "test5-rt-sub");
        subscriber.subscribe(topic);
        Thread.sleep(500);

        Mqtt5MessagePublisher publisher = new Mqtt5MessagePublisher(brokerUrl, "test5-rt-pub");
        publisher.publish(topic, "{\"roundtrip\":true}");

        List<RecordedMessage> messages = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            messages = subscriber.getRecordedMessages(topic);
            if (!messages.isEmpty()) break;
            Thread.sleep(200);
        }

        assertThat("round-trip message should be recorded", messages.size(), greaterThanOrEqualTo(1));
        assertThat(messages.get(0).getPayload(), is("{\"roundtrip\":true}"));

        publisher.close();
        subscriber.close();
    }
}
