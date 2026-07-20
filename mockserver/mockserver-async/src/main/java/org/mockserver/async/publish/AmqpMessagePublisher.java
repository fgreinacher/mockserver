package org.mockserver.async.publish;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.mockserver.async.asyncapi.AmqpBinding;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link MessagePublisher} that delivers messages to an AMQP 0.9.1 broker
 * (RabbitMQ) using the official {@code com.rabbitmq:amqp-client} library.
 *
 * <p>The AsyncAPI channel name does not map directly to an AMQP destination the
 * way it does for Kafka topics or MQTT topics. Instead, the destination is derived
 * from the channel's {@link AmqpBinding} ({@code channels.<name>.bindings.amqp}):
 *
 * <ul>
 *   <li><b>{@code is: routingKey}</b> (default) — publish to the binding's
 *       {@code exchange.name} using the channel name (or the binding's explicit
 *       {@code routingKey}) as the AMQP routing key. When no exchange is named, the
 *       default exchange ({@code ""}) is used with the channel name as the routing
 *       key (i.e. direct-to-queue by name).</li>
 *   <li><b>{@code is: queue}</b> — publish to the default exchange ({@code ""})
 *       using the binding's {@code queue.name} (or the channel name) as the routing
 *       key, which RabbitMQ routes to the like-named queue.</li>
 * </ul>
 *
 * <p>When a channel has no AMQP binding at all, the publisher falls back to
 * publishing to the default exchange with the channel name as the routing key —
 * mirroring the topic-name-as-destination behaviour of the Kafka/MQTT publishers.
 *
 * <p>For exchange-based channels, the exchange is declared (idempotently, once per
 * publisher lifetime) before the first publish using the binding's exchange type
 * (default {@code direct}) and durability. For queue-based channels the named queue
 * is declared once. This makes the mock self-sufficient against a fresh broker.
 *
 * <h2>Unroutable messages</h2>
 * Declaring an exchange does not create any queue bound to it, so a publish to an
 * exchange-routed channel can still reach no consumer. Per AMQP 0-9-1 §3.1.3 the broker
 * discards such a message <i>silently</i> unless {@code mandatory} is set. Every publish is
 * therefore sent with {@code mandatory=true} on a channel in publisher-confirm mode, and an
 * unroutable message raises an error instead of being reported as a successful publish.
 *
 * <h2>Supported binding fields</h2>
 * {@code is}, {@code exchange.name}, {@code exchange.type}, {@code exchange.durable},
 * {@code queue.name}, {@code queue.durable}, and an explicit {@code routingKey}.
 *
 * <h2>Deferred</h2>
 * {@code exchange.autoDelete}/{@code vhost}, {@code queue.exclusive}/{@code autoDelete}/{@code vhost},
 * and operation/message-level AMQP bindings ({@code cc}, {@code bcc}, {@code deliveryMode},
 * {@code priority}, {@code expiration}, …). See {@link AmqpBinding}.
 */
public class AmqpMessagePublisher implements MessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpMessagePublisher.class);
    private static final String DEFAULT_EXCHANGE = "";
    private static final String DEFAULT_EXCHANGE_TYPE = "direct";
    // fail fast instead of blocking the scheduler thread for the OS TCP timeout when the broker is unreachable
    private static final int CONNECTION_TIMEOUT_MILLIS = 5000;

    /**
     * How long to wait for the broker to confirm a single publish before treating it as failed.
     * Applied per message, so a spec publishing N messages can block a caller for up to N times
     * this value; there is no aggregate budget across a publish cycle.
     */
    private static final int CONFIRM_TIMEOUT_MILLIS = 5000;

    private final Connection connection;
    /**
     * Not final: a channel-level AMQP error closes the channel, and a closed channel can never be
     * reopened — only replaced from the connection. {@link #enableUnroutableDetection()} recreates
     * it when {@code confirm.select} is refused, so the publisher degrades instead of being dead
     * on arrival against a broker without publisher confirms.
     *
     * <p><b>Invariant: written only during construction.</b> {@link #replaceClosedChannel()} is
     * called solely from {@link #enableUnroutableDetection()}, which is called solely from the
     * constructors — so this field is never reassigned after the object escapes, and the loss of
     * the final-field freeze costs nothing today. It is {@code volatile} to keep that true under
     * future edits: recovering a channel killed mid-publish would write it under the {@code publish}
     * monitor while {@link #close()} — which is not synchronized — reads it without, and volatile
     * closes that race in advance. Do not reassign outside construction without also making
     * {@link #close()} safe.
     */
    private volatile Channel channel;
    private final Map<String, AmqpBinding> bindingsByChannel;
    private final Set<String> declaredExchanges = new HashSet<>();
    private final Set<String> declaredQueues = new HashSet<>();

    /**
     * Set by the broker's {@code basic.return} callback when a {@code mandatory} publish
     * could not be routed to any queue. Cleared before each publish and read after the
     * publisher confirm, which the broker always sends after any return for that message.
     */
    private final AtomicReference<String> unroutableReturn = new AtomicReference<>();

    /**
     * Whether the channel is in publisher-confirm mode. Publisher confirms are a RabbitMQ
     * extension rather than part of AMQP 0-9-1, so a broker that does not implement them
     * (e.g. Qpid) leaves this false — in which case publishes are sent without {@code mandatory}
     * and without waiting for a confirm, exactly as before this detection existed. Unroutable
     * messages then go undetected, which is what the warning in
     * {@link #enableUnroutableDetection()} reports.
     */
    private volatile boolean confirmsEnabled;

    /**
     * Connect to an AMQP broker from a connection URI (e.g.
     * {@code amqp://user:pass@host:5672/vhost}) and resolve per-channel
     * destinations from the given spec.
     *
     * @param amqpUri the AMQP connection URI
     * @param spec    the loaded AsyncAPI spec (used to map channel names to AMQP bindings); may be null
     */
    public AmqpMessagePublisher(String amqpUri, AsyncApiSpec spec) {
        this(buildFactoryFromUri(amqpUri), spec);
    }

    /**
     * Connect to an AMQP broker from explicit connection parameters and resolve
     * per-channel destinations from the given spec.
     *
     * @param host     broker host
     * @param port     broker port (5672 for plaintext)
     * @param vhost    AMQP virtual host (e.g. {@code /})
     * @param username broker username (may be null for the guest default)
     * @param password broker password (may be null for the guest default)
     * @param spec     the loaded AsyncAPI spec; may be null
     */
    public AmqpMessagePublisher(String host, int port, String vhost,
                                String username, String password, AsyncApiSpec spec) {
        this(buildFactory(host, port, vhost, username, password), spec);
    }

    private AmqpMessagePublisher(ConnectionFactory factory, AsyncApiSpec spec) {
        this.bindingsByChannel = buildBindingMap(spec);
        Connection conn = null;
        try {
            conn = factory.newConnection();
            this.connection = conn;
            this.channel = conn.createChannel();
            enableUnroutableDetection();
        } catch (Exception e) {
            // if the connection opened but createChannel failed, close it so it isn't orphaned
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
            throw new RuntimeException("Failed to connect to AMQP broker: " + e.getMessage(), e);
        }
    }

    /**
     * Package-private constructor for injecting a mock channel/connection in tests.
     */
    AmqpMessagePublisher(Connection connection, Channel channel, AsyncApiSpec spec) {
        this.connection = connection;
        this.channel = channel;
        this.bindingsByChannel = buildBindingMap(spec);
        enableUnroutableDetection();
    }

    /**
     * Put the channel into publisher-confirm mode and register a return listener, so that a
     * {@code mandatory} publish which the broker cannot route to any queue is reported rather
     * than dropped. Per AMQP 0-9-1 §3.1.3 an unroutable message is silently discarded by the
     * broker unless {@code mandatory} is set, which would let MockServer report a successful
     * publish for a message that reached no queue.
     */
    private void enableUnroutableDetection() {
        try {
            channel.confirmSelect();
            channel.addReturnListener((replyCode, replyText, exchange, routingKey, properties, body) ->
                unroutableReturn.set("no queue is bound to exchange '" + exchange
                    + "' for routing key '" + routingKey + "' (broker returned "
                    + replyCode + " " + replyText + ")"));
            confirmsEnabled = true;
        } catch (Exception e) {
            confirmsEnabled = false;
            // A broker that does not implement confirm.select answers 540 NOT_IMPLEMENTED, which is
            // a channel-level error: the channel is now closed and cannot be reopened. Replace it,
            // otherwise the "publishing continues" degradation promised below is a lie and every
            // publish fails with AlreadyClosedException.
            if (!replaceClosedChannel()) {
                throw new RuntimeException("Failed to enable AMQP publisher confirms and could not "
                    + "recover the channel: " + e.getMessage(), e);
            }
            LOG.warn("Could not enable AMQP publisher confirms (they are a RabbitMQ extension, not "
                + "part of AMQP 0-9-1); publishing without confirms, so unroutable messages will "
                + "not be detected and may be silently discarded by the broker: {}", e.getMessage());
        }
    }

    /**
     * Replace the channel if the broker closed it, so publishing can continue without confirms.
     *
     * @return true if a usable channel is available afterwards
     */
    private boolean replaceClosedChannel() {
        try {
            if (channel != null && channel.isOpen()) {
                return true;
            }
            channel = connection.createChannel();
            // topology declared on the dead channel does not carry over to the new one
            declaredExchanges.clear();
            declaredQueues.clear();
            return channel != null;
        } catch (Exception e) {
            LOG.warn("Could not replace the closed AMQP channel: {}", e.getMessage());
            return false;
        }
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
            // redact any user:password@ in the URI so credentials don't leak into the error
            // (this message surfaces in the control-plane HTTP response and logs)
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

    private static ConnectionFactory buildFactory(String host, int port, String vhost,
                                                  String username, String password) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        factory.setHost(host);
        factory.setPort(port);
        if (vhost != null) {
            factory.setVirtualHost(vhost);
        }
        if (username != null) {
            factory.setUsername(username);
        }
        if (password != null) {
            factory.setPassword(password);
        }
        return factory;
    }

    @Override
    public void publish(String channelName, String payload) {
        publish(channelName, payload, (PublishOptions) null);
    }

    /**
     * Publish a message to the AMQP destination derived from the channel's binding.
     * <p>
     * {@link PublishOptions#getHeaders()} (e.g. correlation-ID headers) are emitted
     * as AMQP message headers. The Kafka {@code key} and MQTT {@code qos}/{@code retain}
     * fields are ignored.
     *
     * @param channelName the AsyncAPI channel name
     * @param payload     the message payload (typically JSON)
     * @param options     per-message publish options (may be null)
     */
    @Override
    public synchronized void publish(String channelName, String payload, PublishOptions options) {
        Destination destination = resolveDestination(channelName, bindingsByChannel.get(channelName));
        try {
            ensureTopologyDeclared(channelName, bindingsByChannel.get(channelName), destination);

            AMQP.BasicProperties props = null;
            if (options != null && !options.getHeaders().isEmpty()) {
                Map<String, Object> headers = new HashMap<>(options.getHeaders());
                props = new AMQP.BasicProperties.Builder().headers(headers).build();
            }

            LOG.debug("Publishing to AMQP exchange '{}' with routing key '{}': {}",
                destination.exchange, destination.routingKey, payload);
            unroutableReturn.set(null);
            // mandatory=true so the broker returns the message instead of discarding it when no
            // queue is bound; the confirm below guarantees any such return has arrived. Both are
            // skipped when the broker does not support publisher confirms, since waiting for a
            // confirm that was never selected would fail every publish.
            channel.basicPublish(destination.exchange, destination.routingKey, confirmsEnabled, props,
                payload.getBytes(StandardCharsets.UTF_8));
            if (confirmsEnabled) {
                // deliberately NOT waitForConfirmsOrDie: that closes the channel on both a nack and
                // a timeout (ChannelN.waitForConfirmsOrDie in amqp-client 5.x), and a closed channel
                // is never auto-recovered for an application-initiated close — so a single confirm
                // timeout (RabbitMQ stalls confirms indefinitely under a memory or disk alarm) would
                // make every later publish throw AlreadyClosedException for the lifetime of the mock
                if (!channel.waitForConfirms(CONFIRM_TIMEOUT_MILLIS)) {
                    throw new IOException("broker nacked the message");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish to AMQP channel '" + channelName + "': "
                + e.getMessage(), e);
        }

        String unroutable = unroutableReturn.getAndSet(null);
        if (unroutable != null) {
            throw new RuntimeException("Message published to AMQP channel '" + channelName
                + "' was not routed to any queue: " + unroutable);
        }
    }

    /**
     * Resolve the AMQP (exchange, routingKey) destination for a channel.
     * Package-private and broker-free so it can be unit-tested directly.
     *
     * @param channelName the AsyncAPI channel name
     * @param binding     the channel's AMQP binding (may be null)
     * @return the resolved exchange + routing key
     */
    static Destination resolveDestination(String channelName, AmqpBinding binding) {
        if (binding == null) {
            // No binding: publish to the default exchange using the channel name as routing key.
            return new Destination(DEFAULT_EXCHANGE, channelName);
        }

        if (binding.getChannelType() == AmqpBinding.ChannelType.QUEUE) {
            // Queue-based: default exchange + queue name (or channel name) as routing key.
            String queue = binding.getQueueName() != null ? binding.getQueueName() : channelName;
            return new Destination(DEFAULT_EXCHANGE, queue);
        }

        // routingKey-based (default): publish to the named exchange with the
        // explicit routing key, the queue name, or the channel name (in that order).
        String exchange = binding.getExchangeName() != null ? binding.getExchangeName() : DEFAULT_EXCHANGE;
        String routingKey = binding.getRoutingKey() != null
            ? binding.getRoutingKey()
            : channelName;
        return new Destination(exchange, routingKey);
    }

    /**
     * Declare the exchange and/or queue for a channel once per publisher lifetime,
     * so the mock works against a fresh broker. No-op for the default exchange.
     */
    private void ensureTopologyDeclared(String channelName, AmqpBinding binding, Destination destination)
        throws Exception {
        // Declare a non-default exchange the first time we publish to it.
        if (!destination.exchange.isEmpty() && declaredExchanges.add(destination.exchange)) {
            String type = (binding != null && binding.getExchangeType() != null)
                ? binding.getExchangeType() : DEFAULT_EXCHANGE_TYPE;
            boolean durable = binding == null || binding.isExchangeDurable();
            channel.exchangeDeclare(destination.exchange, normaliseExchangeType(type), durable);
            LOG.debug("Declared AMQP exchange '{}' (type={}, durable={})",
                destination.exchange, type, durable);
        }

        // For a queue-based channel publishing to the default exchange, declare the
        // target queue so the message is not silently dropped against a fresh broker.
        if (binding != null && binding.getChannelType() == AmqpBinding.ChannelType.QUEUE
            && destination.exchange.isEmpty()) {
            String queue = destination.routingKey;
            if (declaredQueues.add(queue)) {
                channel.queueDeclare(queue, binding.isQueueDurable(), false, false, null);
                LOG.debug("Declared AMQP queue '{}' (durable={})", queue, binding.isQueueDurable());
            }
        }
    }

    private static BuiltinExchangeType normaliseExchangeType(String type) {
        try {
            return BuiltinExchangeType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown AMQP exchange type '{}'; defaulting to direct", type);
            return BuiltinExchangeType.DIRECT;
        }
    }

    @Override
    public void close() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception e) {
            LOG.warn("Error closing AMQP channel: {}", e.getMessage());
        }
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception e) {
            LOG.warn("Error closing AMQP connection: {}", e.getMessage());
        }
    }

    /**
     * An AMQP publish destination: the exchange to publish to and the routing key.
     */
    static final class Destination {
        final String exchange;
        final String routingKey;

        Destination(String exchange, String routingKey) {
            this.exchange = exchange;
            this.routingKey = routingKey;
        }

        @Override
        public String toString() {
            return "Destination{exchange='" + exchange + "', routingKey='" + routingKey + "'}";
        }
    }
}
