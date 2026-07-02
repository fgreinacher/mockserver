package org.mockserver.async.subscribe;

import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.async.security.Mqtt5SecurityOptions;
import org.mockserver.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A {@link MessageSubscriber} that uses an MQTT <b>v5</b> {@link MqttClient} to
 * subscribe to topics and record received messages — the v5 counterpart of
 * {@link MqttMessageSubscriber}.
 * <p>
 * MQTT 5 user properties on incoming messages are recorded as the
 * {@link RecordedMessage} headers (MQTT 3 has no equivalent). Recorded messages are
 * stored in a bounded {@link BoundedMessageStore} per channel to prevent unbounded
 * memory growth.
 */
public class Mqtt5MessageSubscriber implements MessageSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(Mqtt5MessageSubscriber.class);
    private static final int DEFAULT_QOS = 1;
    private static final int MAX_LOG_PAYLOAD_LENGTH = 100;

    private final MqttClient client;
    private final int qos;
    private final int maxRecordedMessages;
    private final ConcurrentMap<String, BoundedMessageStore> recordedMessages = new ConcurrentHashMap<>();

    private volatile boolean connected = true;

    /**
     * Create a v5 subscriber connected to the given broker with no security.
     */
    public Mqtt5MessageSubscriber(String brokerUrl, String clientId) {
        this(brokerUrl, clientId, DEFAULT_QOS, BoundedMessageStore.DEFAULT_MAX_RECORDED_MESSAGES, null);
    }

    /**
     * Create a v5 subscriber with a specific QoS and no security.
     */
    public Mqtt5MessageSubscriber(String brokerUrl, String clientId, int qos) {
        this(brokerUrl, clientId, qos, BoundedMessageStore.DEFAULT_MAX_RECORDED_MESSAGES, null);
    }

    /**
     * Create a v5 subscriber with optional security configuration.
     *
     * @param brokerUrl           the MQTT broker URL
     * @param clientId            the client identifier
     * @param qos                 the MQTT QoS level (0, 1, or 2)
     * @param maxRecordedMessages maximum recorded messages per channel
     * @param security            security configuration (may be null for plaintext)
     */
    public Mqtt5MessageSubscriber(String brokerUrl, String clientId, int qos,
                                  int maxRecordedMessages, MqttSecurity security) {
        try {
            this.client = new MqttClient(brokerUrl, clientId);
            MqttConnectionOptions options = Mqtt5SecurityOptions.buildConnectOptions(security);
            if (options != null) {
                this.client.connect(options);
            } else {
                this.client.connect();
            }
            this.qos = qos;
            this.maxRecordedMessages = maxRecordedMessages;
            installCallback();
        } catch (MqttException e) {
            throw new RuntimeException("Failed to connect MQTT 5 subscriber to broker: " + brokerUrl, e);
        }
    }

    /**
     * Package-private constructor for injecting a mock client in tests.
     */
    Mqtt5MessageSubscriber(MqttClient client, int qos) {
        this(client, qos, BoundedMessageStore.DEFAULT_MAX_RECORDED_MESSAGES);
    }

    /**
     * Package-private constructor for injecting a mock client with custom cap in tests.
     */
    Mqtt5MessageSubscriber(MqttClient client, int qos, int maxRecordedMessages) {
        this.client = client;
        this.qos = qos;
        this.maxRecordedMessages = maxRecordedMessages;
        installCallback();
    }

    private void installCallback() {
        client.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {
                connected = false;
                LOG.warn("MQTT 5 subscriber disconnected; subscriber is no longer healthy: {}",
                    disconnectResponse != null ? disconnectResponse.getReasonString() : "unknown reason");
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                LOG.warn("MQTT 5 subscriber error: {}", exception != null ? exception.getMessage() : "unknown");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                Map<String, String> headers = extractUserProperties(message.getProperties());
                RecordedMessage msg = new RecordedMessage(topic, null, payload, headers);
                recordedMessages.computeIfAbsent(topic,
                    k -> new BoundedMessageStore(maxRecordedMessages)).add(msg);
                Metrics.incrementAsyncMessageConsumed(topic);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Recorded message from MQTT 5 topic '{}': {}", topic, truncate(payload));
                }
            }

            @Override
            public void deliveryComplete(IMqttToken token) {
                // not relevant for subscriber
            }

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                connected = true;
            }

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {
                // not relevant for subscriber
            }
        });
    }

    private static Map<String, String> extractUserProperties(MqttProperties properties) {
        if (properties == null || properties.getUserProperties() == null
            || properties.getUserProperties().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (UserProperty up : properties.getUserProperties()) {
            headers.put(up.getKey(), up.getValue());
        }
        return headers;
    }

    /**
     * Returns {@code false} if the MQTT callback has reported a disconnect.
     */
    public boolean isHealthy() {
        return connected;
    }

    @Override
    public void subscribe(String channel) {
        try {
            recordedMessages.putIfAbsent(channel, new BoundedMessageStore(maxRecordedMessages));
            client.subscribe(channel, qos);
            LOG.info("Subscribed to MQTT 5 topic '{}'", channel);
        } catch (MqttException e) {
            throw new RuntimeException("Failed to subscribe to MQTT 5 topic: " + channel, e);
        }
    }

    @Override
    public void unsubscribe(String channel) {
        try {
            client.unsubscribe(channel);
            LOG.info("Unsubscribed from MQTT 5 topic '{}'", channel);
        } catch (MqttException e) {
            LOG.warn("Error unsubscribing from MQTT 5 topic '{}': {}", channel, e.getMessage());
        }
    }

    @Override
    public List<RecordedMessage> getRecordedMessages(String channel) {
        BoundedMessageStore store = recordedMessages.get(channel);
        return store != null ? Collections.unmodifiableList(store.snapshot()) : Collections.emptyList();
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
            LOG.warn("Error closing MQTT 5 subscriber: {}", e.getMessage());
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
