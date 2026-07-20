package org.mockserver.async.publish;

import java.util.Map;

/**
 * Publishes a message payload to a named channel (topic).
 * <p>
 * Implementations exist for Kafka ({@link KafkaMessagePublisher}) and MQTT ({@link MqttMessagePublisher}).
 */
public interface MessagePublisher {

    /**
     * Publish the given payload to the specified channel with no key or headers.
     *
     * @param channel the channel / topic name
     * @param payload the message payload (typically JSON)
     */
    void publish(String channel, String payload);

    /**
     * Publish the given payload with an optional key and headers.
     * Default implementation delegates to {@link #publish(String, String)},
     * ignoring key and headers — implementations that support them override this.
     *
     * @param channel the channel / topic name
     * @param key     the message key (may be null)
     * @param payload the message payload (typically JSON)
     * @param headers optional headers (may be null)
     */
    default void publish(String channel, String key, String payload, Map<String, String> headers) {
        publish(channel, payload);
    }

    /**
     * Publish the given payload with per-message options derived from AsyncAPI bindings.
     * Default implementation delegates to {@link #publish(String, String)},
     * ignoring the options — implementations that support them override this.
     *
     * @param channel the channel / topic name
     * @param payload the message payload (typically JSON)
     * @param options per-message publish options (may be null)
     */
    default void publish(String channel, String payload, PublishOptions options) {
        publish(channel, payload);
    }

    /**
     * Block until every message published so far has been acknowledged by the broker,
     * throwing if any of them failed to be delivered.
     * <p>
     * Implementations that send asynchronously (notably Kafka) would otherwise let a caller
     * report a successful publish before the broker has accepted — or rejected — the message.
     * Callers that report publish success to a user must call this first.
     * <p>
     * The default implementation is a no-op, for publishers that deliver synchronously.
     *
     * @throws RuntimeException if any message published since the last flush failed to deliver
     */
    default void flush() {
        // no-op: synchronous publishers have already delivered
    }

    /**
     * Release any resources held by this publisher (producer connections, etc.).
     */
    void close();
}
