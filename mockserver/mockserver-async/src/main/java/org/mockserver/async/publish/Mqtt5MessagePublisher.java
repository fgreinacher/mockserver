package org.mockserver.async.publish;

import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.async.security.Mqtt5SecurityOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link MessagePublisher} that delegates to an MQTT <b>v5</b>
 * {@link MqttClient}. The channel name maps directly to an MQTT topic.
 * <p>
 * Compared with {@link MqttMessagePublisher} (v3.1.1), MQTT 5 adds
 * <b>user properties</b>: {@link PublishOptions#getHeaders()} (e.g. correlation-ID
 * headers) are delivered as MQTT 5 user properties, so header-location correlation
 * IDs — which cannot be carried over MQTT 3 — work here. QoS (0/1/2), retain, and
 * binary payloads are supported as in v3.
 */
public class Mqtt5MessagePublisher implements MessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(Mqtt5MessagePublisher.class);
    private static final int DEFAULT_QOS = 1;

    private final MqttClient client;
    private final int qos;

    /**
     * Create a v5 publisher connected to the given broker with default QoS (1) and no security.
     */
    public Mqtt5MessagePublisher(String brokerUrl, String clientId) {
        this(brokerUrl, clientId, DEFAULT_QOS, null);
    }

    /**
     * Create a v5 publisher connected to the given broker with a specific QoS and no security.
     */
    public Mqtt5MessagePublisher(String brokerUrl, String clientId, int qos) {
        this(brokerUrl, clientId, qos, null);
    }

    /**
     * Create a v5 publisher with optional security configuration.
     *
     * @param brokerUrl the MQTT broker URL (e.g. {@code tcp://localhost:1883} or {@code ssl://localhost:8883})
     * @param clientId  the client identifier
     * @param qos       the MQTT QoS level (0, 1, or 2)
     * @param security  security configuration (may be null for plaintext)
     */
    public Mqtt5MessagePublisher(String brokerUrl, String clientId, int qos, MqttSecurity security) {
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("MQTT QoS must be 0, 1, or 2; got: " + qos);
        }
        try {
            this.client = new MqttClient(brokerUrl, clientId);
            MqttConnectionOptions options = Mqtt5SecurityOptions.buildConnectOptions(security);
            if (options != null) {
                this.client.connect(options);
            } else {
                this.client.connect();
            }
            this.qos = qos;
        } catch (MqttException e) {
            throw new RuntimeException("Failed to connect to MQTT 5 broker: " + brokerUrl, e);
        }
    }

    /**
     * Package-private constructor for injecting a mock client in tests.
     */
    Mqtt5MessagePublisher(MqttClient client, int qos) {
        this.client = client;
        this.qos = qos;
    }

    @Override
    public void publish(String channel, String payload) {
        publishBytes(channel, payload.getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * Publish with per-message options from AsyncAPI bindings. Applies QoS and
     * retain when present, and delivers {@link PublishOptions#getHeaders()} as
     * MQTT 5 user properties.
     */
    @Override
    public void publish(String channel, String payload, PublishOptions options) {
        publishBytes(channel, payload.getBytes(StandardCharsets.UTF_8), options);
    }

    /**
     * Publish a binary payload with no options.
     */
    public void publishBytes(String channel, byte[] payload) {
        publishBytes(channel, payload, null);
    }

    void publishBytes(String channel, byte[] payload, PublishOptions options) {
        try {
            MqttMessage message = new MqttMessage(payload);
            int effectiveQos = (options != null && options.getQos() != null) ? options.getQos() : this.qos;
            message.setQos(effectiveQos);
            if (options != null && options.getRetain() != null) {
                message.setRetained(options.getRetain());
            }
            if (options != null && !options.getHeaders().isEmpty()) {
                MqttProperties props = new MqttProperties();
                List<UserProperty> userProperties = new ArrayList<>();
                for (Map.Entry<String, String> header : options.getHeaders().entrySet()) {
                    userProperties.add(new UserProperty(header.getKey(), header.getValue()));
                }
                props.setUserProperties(userProperties);
                message.setProperties(props);
            }
            LOG.debug("Publishing to MQTT 5 topic '{}': {} bytes", channel, payload.length);
            client.publish(channel, message);
        } catch (MqttException e) {
            throw new RuntimeException("Failed to publish to MQTT 5 topic: " + channel, e);
        }
    }

    /**
     * @return the configured QoS level for this publisher
     */
    public int getQos() {
        return qos;
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            LOG.warn("Error closing MQTT 5 client: {}", e.getMessage());
        }
    }
}
