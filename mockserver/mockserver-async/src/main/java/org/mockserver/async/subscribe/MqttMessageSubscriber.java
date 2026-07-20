package org.mockserver.async.subscribe;

import org.eclipse.paho.client.mqttv3.*;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.async.security.MqttSecurityOptions;
import org.mockserver.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A {@link MessageSubscriber} that uses an MQTT {@link MqttClient} to subscribe
 * to topics and record received messages.
 * <p>
 * Recorded messages are stored in a bounded {@link BoundedMessageStore} per channel
 * to prevent unbounded memory growth.
 */
public class MqttMessageSubscriber implements MessageSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(MqttMessageSubscriber.class);
    private static final int DEFAULT_QOS = 1;
    private static final int MAX_LOG_PAYLOAD_LENGTH = 100;

    private final MqttClient client;
    private final int qos;
    private final int maxRecordedMessages;
    private final ConcurrentMap<String, BoundedMessageStore> recordedMessages = new ConcurrentHashMap<>();

    /**
     * Tracks broker connectivity. Set to {@code false} when the MQTT callback reports a
     * lost connection so callers can detect an unhealthy subscriber via {@link #isHealthy()}.
     */
    private volatile boolean connected = true;

    /**
     * Create a subscriber connected to the given MQTT broker with no security.
     * Backward-compatible entry point.
     *
     * @param brokerUrl the MQTT broker URL (e.g. {@code tcp://localhost:1883})
     * @param clientId  the client identifier
     */
    public MqttMessageSubscriber(String brokerUrl, String clientId) {
        this(brokerUrl, clientId, DEFAULT_QOS);
    }

    /**
     * Create a subscriber with a specific QoS level and no security.
     */
    public MqttMessageSubscriber(String brokerUrl, String clientId, int qos) {
        this(brokerUrl, clientId, qos, BoundedMessageStore.DEFAULT_MAX_RECORDED_MESSAGES);
    }

    /**
     * Create a subscriber with a specific QoS level and recorded-message cap,
     * no security. Backward-compatible entry point.
     */
    public MqttMessageSubscriber(String brokerUrl, String clientId, int qos, int maxRecordedMessages) {
        this(brokerUrl, clientId, qos, maxRecordedMessages, null);
    }

    /**
     * Create a subscriber with optional security configuration.
     *
     * @param brokerUrl          the MQTT broker URL (e.g. {@code ssl://localhost:8883})
     * @param clientId           the client identifier
     * @param qos                the MQTT QoS level (0, 1, or 2)
     * @param maxRecordedMessages maximum recorded messages per channel
     * @param security           security configuration (may be null for plaintext)
     */
    public MqttMessageSubscriber(String brokerUrl, String clientId, int qos,
                                 int maxRecordedMessages, MqttSecurity security) {
        try {
            this.client = new MqttClient(brokerUrl, clientId);
            MqttConnectOptions options = MqttSecurityOptions.buildConnectOptions(security);
            if (options != null) {
                this.client.connect(options);
            } else {
                this.client.connect();
            }
            this.qos = qos;
            this.maxRecordedMessages = maxRecordedMessages;
            installCallback();
        } catch (MqttException e) {
            throw new RuntimeException("Failed to connect MQTT subscriber to broker: " + brokerUrl, e);
        }
    }

    /**
     * Package-private constructor for injecting a mock client in tests.
     */
    MqttMessageSubscriber(MqttClient client, int qos) {
        this(client, qos, BoundedMessageStore.DEFAULT_MAX_RECORDED_MESSAGES);
    }

    /**
     * Package-private constructor for injecting a mock client with custom cap in tests.
     */
    MqttMessageSubscriber(MqttClient client, int qos, int maxRecordedMessages) {
        this.client = client;
        this.qos = qos;
        this.maxRecordedMessages = maxRecordedMessages;
        installCallback();
    }

    private void installCallback() {
        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                connected = false;
                LOG.warn("MQTT subscriber connection lost; subscriber is no longer healthy: {}",
                    cause != null ? cause.getMessage() : "unknown cause");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                RecordedMessage msg = new RecordedMessage(topic, null, payload, Collections.emptyMap());
                recordedMessages.computeIfAbsent(topic,
                    k -> new BoundedMessageStore(maxRecordedMessages)).add(msg);
                Metrics.incrementAsyncMessageConsumed(topic);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Recorded message from MQTT topic '{}': {}", topic, truncate(payload));
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // not relevant for subscriber
            }
        });
    }

    /**
     * Returns {@code false} if the MQTT callback has reported a lost connection (the
     * subscriber will record no further messages until reconnected); {@code true} otherwise.
     */
    public boolean isHealthy() {
        return connected;
    }

    @Override
    public void subscribe(String channel) {
        try {
            recordedMessages.putIfAbsent(channel, new BoundedMessageStore(maxRecordedMessages));
            client.subscribe(channel, qos);
            LOG.info("Subscribed to MQTT topic '{}'", channel);
        } catch (MqttException e) {
            throw new RuntimeException("Failed to subscribe to MQTT topic: " + channel, e);
        }
    }

    @Override
    public void unsubscribe(String channel) {
        try {
            client.unsubscribe(channel);
            LOG.info("Unsubscribed from MQTT topic '{}'", channel);
        } catch (MqttException e) {
            LOG.warn("Error unsubscribing from MQTT topic '{}': {}", channel, e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Messages are recorded under the <b>concrete</b> topic they arrived on, but a
     * subscription may have used a wildcard <b>filter</b>. When the requested channel is a
     * wildcard filter, every recorded topic matching it (per MQTT 3.1.1 §4.7) is returned —
     * without this a wildcard subscription would record messages that could never be
     * retrieved, and verification would always report zero matches.
     */
    @Override
    public List<RecordedMessage> getRecordedMessages(String channel) {
        if (!MqttTopicFilter.containsWildcard(channel)) {
            BoundedMessageStore store = recordedMessages.get(channel);
            return store != null ? Collections.unmodifiableList(store.snapshot()) : Collections.emptyList();
        }
        List<RecordedMessage> matched = new ArrayList<>();
        for (Map.Entry<String, BoundedMessageStore> entry : recordedMessages.entrySet()) {
            if (MqttTopicFilter.matches(channel, entry.getKey())) {
                matched.addAll(entry.getValue().snapshot());
            }
        }
        matched.sort(Comparator.comparing(RecordedMessage::getTimestamp));
        return Collections.unmodifiableList(matched);
    }

    @Override
    public List<RecordedMessage> getAllRecordedMessages() {
        List<RecordedMessage> all = new ArrayList<>();
        recordedMessages.values().forEach(store -> all.addAll(store.snapshot()));
        all.sort(Comparator.comparing(RecordedMessage::getTimestamp));
        return Collections.unmodifiableList(all);
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            LOG.warn("Error closing MQTT subscriber: {}", e.getMessage());
        }
        recordedMessages.clear();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_LOG_PAYLOAD_LENGTH
            ? value
            : value.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...(" + value.length() + " chars)";
    }
}
