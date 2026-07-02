package org.mockserver.async.subscribe;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import org.mockserver.async.asyncapi.AmqpBinding;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * A {@link MessageSubscriber} that consumes from an AMQP 0.9.1 broker (RabbitMQ)
 * using the official {@code com.rabbitmq:amqp-client} library, the consumer-side
 * counterpart of {@link org.mockserver.async.publish.AmqpMessagePublisher}.
 *
 * <p>Like the publisher, the AMQP source of a channel is derived from the channel's
 * {@link AmqpBinding} ({@code channels.<name>.bindings.amqp}) — the AsyncAPI channel
 * name does not map directly to a queue the way a Kafka/MQTT topic does:
 *
 * <ul>
 *   <li><b>{@code is: queue}</b> (or no binding) — consume directly from the queue
 *       named by {@code queue.name} (or the channel name). The queue is declared
 *       (durable per {@code queue.durable}) so recording works against a fresh
 *       broker, matching the publisher's queue declaration.</li>
 *   <li><b>{@code is: routingKey}</b> — declare the binding's {@code exchange.name}
 *       (idempotently, with the binding's type/durability), declare a private
 *       server-named exclusive auto-delete queue, and bind it to the exchange on the
 *       routing key (the binding's explicit {@code routingKey}, else the channel
 *       name). Messages published to that exchange/routing-key are then recorded.</li>
 * </ul>
 *
 * <p>Recorded messages carry the AMQP message-property headers (if any) as the
 * {@link RecordedMessage} headers; the key is always null (AMQP has no per-message
 * key concept the way Kafka does). Recorded messages are stored in a bounded
 * {@link BoundedMessageStore} per channel to prevent unbounded memory growth.
 */
public class AmqpMessageSubscriber implements MessageSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpMessageSubscriber.class);
    private static final String DEFAULT_EXCHANGE = "";
    private static final String DEFAULT_EXCHANGE_TYPE = "direct";
    // fail fast instead of blocking for the OS TCP timeout when the broker is unreachable
    private static final int CONNECTION_TIMEOUT_MILLIS = 5000;

    private final Connection connection;
    private final Channel channel;
    private final Map<String, AmqpBinding> bindingsByChannel;
    private final int maxRecordedMessages;
    private final ConcurrentMap<String, BoundedMessageStore> recordedMessages = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> consumerTagsByChannel = new ConcurrentHashMap<>();

    /**
     * Connect to an AMQP broker from a connection URI and resolve per-channel
     * consume sources from the given spec.
     *
     * @param amqpUri             the AMQP connection URI (e.g. {@code amqp://user:pass@host:5672/vhost})
     * @param spec                the loaded AsyncAPI spec (used to map channel names to AMQP bindings); may be null
     * @param maxRecordedMessages maximum recorded messages retained per channel
     */
    public AmqpMessageSubscriber(String amqpUri, AsyncApiSpec spec, int maxRecordedMessages) {
        this(buildFactoryFromUri(amqpUri), spec, maxRecordedMessages);
    }

    private AmqpMessageSubscriber(ConnectionFactory factory, AsyncApiSpec spec, int maxRecordedMessages) {
        this.bindingsByChannel = buildBindingMap(spec);
        this.maxRecordedMessages = maxRecordedMessages;
        Connection conn = null;
        try {
            conn = factory.newConnection();
            this.connection = conn;
            this.channel = conn.createChannel();
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
            throw new RuntimeException("Failed to connect AMQP subscriber to broker: " + e.getMessage(), e);
        }
    }

    /**
     * Package-private constructor for injecting a mock channel/connection in tests.
     */
    AmqpMessageSubscriber(Connection connection, Channel channel, AsyncApiSpec spec, int maxRecordedMessages) {
        this.connection = connection;
        this.channel = channel;
        this.bindingsByChannel = buildBindingMap(spec);
        this.maxRecordedMessages = maxRecordedMessages;
    }

    private static Map<String, AmqpBinding> buildBindingMap(AsyncApiSpec spec) {
        Map<String, AmqpBinding> map = new HashMap<>();
        if (spec != null) {
            for (AsyncApiChannel ch : spec.getChannels()) {
                if (ch.getAmqpBinding() != null) {
                    map.put(ch.getName(), ch.getAmqpBinding());
                }
            }
        }
        return map;
    }

    private static ConnectionFactory buildFactoryFromUri(String amqpUri) {
        ConnectionFactory factory = new ConnectionFactory();
        try {
            factory.setUri(amqpUri);
        } catch (URISyntaxException | NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Invalid AMQP URI: " + redactAmqpUri(amqpUri), e);
        }
        factory.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        return factory;
    }

    /** Strip {@code user:password@} userinfo from an AMQP URI before it appears in an error/log. */
    private static String redactAmqpUri(String amqpUri) {
        if (amqpUri == null) {
            return "null";
        }
        return amqpUri.replaceAll("://[^@/]*@", "://****@");
    }

    @Override
    public void subscribe(String channelName) {
        recordedMessages.putIfAbsent(channelName, new BoundedMessageStore(maxRecordedMessages));
        try {
            String queue = resolveConsumeQueue(channelName, bindingsByChannel.get(channelName));
            DeliverCallback deliverCallback = (consumerTag, delivery) -> record(channelName, delivery);
            String tag = channel.basicConsume(queue, true, deliverCallback, cancelledTag -> {
                LOG.warn("AMQP consumer for channel '{}' (queue '{}') was cancelled by the broker", channelName, queue);
            });
            consumerTagsByChannel.put(channelName, tag);
            LOG.info("Subscribed to AMQP channel '{}' via queue '{}'", channelName, queue);
        } catch (Exception e) {
            throw new RuntimeException("Failed to subscribe to AMQP channel '" + channelName + "': "
                + e.getMessage(), e);
        }
    }

    /**
     * Resolve (and declare/bind, once) the AMQP queue to consume from for a channel.
     *
     * <ul>
     *   <li>Queue-based channel or no binding: declare and consume the named queue
     *       (queue.name / channel name).</li>
     *   <li>RoutingKey-based channel with a non-default exchange: declare the
     *       exchange, declare a private server-named exclusive auto-delete queue,
     *       bind it on the routing key, and consume it.</li>
     *   <li>RoutingKey-based channel with the default exchange: consume the queue
     *       named by the routing key (channel name).</li>
     * </ul>
     */
    private String resolveConsumeQueue(String channelName, AmqpBinding binding) throws Exception {
        if (binding == null || binding.getChannelType() == AmqpBinding.ChannelType.QUEUE) {
            String queue = (binding != null && binding.getQueueName() != null)
                ? binding.getQueueName() : channelName;
            boolean durable = binding == null || binding.isQueueDurable();
            channel.queueDeclare(queue, durable, false, false, null);
            return queue;
        }

        // routingKey-based
        String exchange = binding.getExchangeName() != null ? binding.getExchangeName() : DEFAULT_EXCHANGE;
        String routingKey = binding.getRoutingKey() != null ? binding.getRoutingKey() : channelName;

        if (exchange.isEmpty()) {
            // default exchange: routing key maps to a like-named queue
            channel.queueDeclare(routingKey, true, false, false, null);
            return routingKey;
        }

        // named exchange: declare it, then a private queue bound to the routing key
        String type = binding.getExchangeType() != null ? binding.getExchangeType() : DEFAULT_EXCHANGE_TYPE;
        channel.exchangeDeclare(exchange, normaliseExchangeType(type), binding.isExchangeDurable());
        // server-named, exclusive, auto-delete queue — cleaned up when this subscriber's connection closes
        String queue = channel.queueDeclare().getQueue();
        channel.queueBind(queue, exchange, routingKey);
        LOG.debug("Bound private queue '{}' to exchange '{}' on routing key '{}'", queue, exchange, routingKey);
        return queue;
    }

    /**
     * Normalise a (possibly non-lowercase) AsyncAPI exchange type to a
     * {@link BuiltinExchangeType}, mirroring
     * {@link org.mockserver.async.publish.AmqpMessagePublisher} so the consumer
     * and producer sides declare exchanges identically. Falls back to
     * {@code direct} for an unknown type.
     */
    static BuiltinExchangeType normaliseExchangeType(String type) {
        try {
            return BuiltinExchangeType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown AMQP exchange type '{}'; defaulting to direct", type);
            return BuiltinExchangeType.DIRECT;
        }
    }

    private void record(String channelName, Delivery delivery) {
        String payload = new String(delivery.getBody(), StandardCharsets.UTF_8);
        Map<String, String> headers = extractHeaders(delivery);
        RecordedMessage msg = new RecordedMessage(channelName, null, payload, headers);
        recordedMessages.computeIfAbsent(channelName,
            k -> new BoundedMessageStore(maxRecordedMessages)).add(msg);
        Metrics.incrementAsyncMessageConsumed(channelName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Recorded message from AMQP channel '{}': {}", channelName,
                payload.length() <= 100 ? payload : payload.substring(0, 100) + "...(" + payload.length() + " chars)");
        }
    }

    private static Map<String, String> extractHeaders(Delivery delivery) {
        if (delivery.getProperties() == null || delivery.getProperties().getHeaders() == null) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        delivery.getProperties().getHeaders().forEach((k, v) ->
            headers.put(k, v != null ? v.toString() : null));
        return headers;
    }

    @Override
    public void unsubscribe(String channelName) {
        String tag = consumerTagsByChannel.remove(channelName);
        if (tag != null) {
            try {
                channel.basicCancel(tag);
                LOG.info("Unsubscribed from AMQP channel '{}'", channelName);
            } catch (Exception e) {
                LOG.warn("Error unsubscribing from AMQP channel '{}': {}", channelName, e.getMessage());
            }
        }
    }

    @Override
    public List<RecordedMessage> getRecordedMessages(String channelName) {
        BoundedMessageStore store = recordedMessages.get(channelName);
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
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception e) {
            LOG.warn("Error closing AMQP subscriber channel: {}", e.getMessage());
        }
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception e) {
            LOG.warn("Error closing AMQP subscriber connection: {}", e.getMessage());
        }
        recordedMessages.clear();
        consumerTagsByChannel.clear();
    }
}
