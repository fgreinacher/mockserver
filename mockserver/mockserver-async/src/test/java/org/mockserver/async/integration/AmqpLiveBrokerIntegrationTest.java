package org.mockserver.async.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.async.AsyncApiMockOrchestrator;
import org.mockserver.async.MessageExampleGenerator;
import org.mockserver.async.asyncapi.AmqpBinding;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiParser;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.AmqpMessagePublisher;
import org.mockserver.async.subscribe.AmqpMessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for AMQP (RabbitMQ) publishing using a real broker via
 * Testcontainers. These tests are Docker-gated: they SKIP (not fail) when Docker
 * is not available, mirroring {@link KafkaLiveBrokerIntegrationTest}.
 */
public class AmqpLiveBrokerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RabbitMQContainer rabbit;
    private static boolean dockerAvailable;
    private static String amqpUri;

    @BeforeClass
    public static void checkDockerAndStartRabbit() {
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assume.assumeTrue("Docker is not available — skipping AMQP integration tests", dockerAvailable);

        rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));
        rabbit.start();
        amqpUri = rabbit.getAmqpUrl();
    }

    @AfterClass
    public static void stopRabbit() {
        if (rabbit != null && rabbit.isRunning()) {
            rabbit.stop();
        }
    }

    /**
     * Queue-based channel: publish to the default exchange with the queue name as
     * the routing key; a consumer on that queue must receive the message.
     */
    @Test
    public void shouldPublishToQueueBoundChannelViaLiveBroker() throws Exception {
        String queue = "live-orders-queue";
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.QUEUE, null, null, true, queue, true, null);
        AsyncApiSpec spec = specWithChannel("orders", binding);

        AmqpMessagePublisher publisher = new AmqpMessagePublisher(amqpUri, spec);
        publisher.publish("orders", "{\"orderId\":42}");
        publisher.close();

        String received = basicGet(queue);
        assertThat("message should be on the queue", received, is("{\"orderId\":42}"));
    }

    /**
     * Exchange/routing-key channel: declare a topic exchange, bind a queue to a
     * routing key matching the channel name, publish, and assert the queue receives it.
     */
    @Test
    public void shouldPublishToExchangeWithRoutingKeyViaLiveBroker() throws Exception {
        String exchange = "live-events";
        String channelName = "user.signedup";
        String boundQueue = "live-events-consumer";

        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, exchange, "topic", true, null, true, null);
        AsyncApiSpec spec = specWithChannel(channelName, binding);

        // Set up a consumer queue bound to the exchange on the channel-name routing key.
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(amqpUri);
        try (Connection connection = factory.newConnection(); Channel ch = connection.createChannel()) {
            ch.exchangeDeclare(exchange, "topic", true);
            ch.queueDeclare(boundQueue, true, false, false, null);
            ch.queueBind(boundQueue, exchange, channelName);

            AmqpMessagePublisher publisher = new AmqpMessagePublisher(amqpUri, spec);
            publisher.publish(channelName, "{\"user\":\"alice\"}");
            publisher.close();

            String received = pollQueue(ch, boundQueue);
            assertThat("routed message should be on the bound queue", received, is("{\"user\":\"alice\"}"));
        }
    }

    /**
     * End-to-end through the orchestrator + a spec parsed from JSON (queue binding),
     * asserting the schema-generated/example payload lands on the broker.
     */
    @Test
    public void orchestratorShouldPublishExampleToLiveBroker() throws Exception {
        String queue = "live-orchestrator-queue";
        String specJson = "{"
            + "\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"Orders\"},"
            + "\"channels\":{\"orders\":{"
            + "  \"bindings\":{\"amqp\":{\"is\":\"queue\",\"queue\":{\"name\":\"" + queue + "\",\"durable\":true}}},"
            + "  \"publish\":{\"message\":{\"payload\":{\"type\":\"object\"},"
            + "    \"examples\":[{\"payload\":{\"hello\":\"world\"}}]}}"
            + "}}}";

        AsyncApiSpec spec = new AsyncApiParser().parse(specJson);

        AmqpMessagePublisher publisher = new AmqpMessagePublisher(amqpUri, spec);
        AsyncApiMockOrchestrator orchestrator =
            new AsyncApiMockOrchestrator(spec, publisher, new MessageExampleGenerator());
        orchestrator.publishAll();
        publisher.close();

        String received = basicGet(queue);
        assertThat("orchestrator-published example should land on the queue",
            received, is("{\"hello\":\"world\"}"));
    }

    /**
     * Subscribe/record path (queue-based channel): the MockServer subscriber declares
     * and consumes the queue; a message published to it is recorded for verification.
     */
    @Test
    public void subscriberShouldRecordFromQueueBoundChannel() throws Exception {
        String queue = "live-sub-orders-queue";
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.QUEUE, null, null, true, queue, true, null);
        AsyncApiSpec spec = specWithChannel("orders", binding);

        AmqpMessageSubscriber subscriber = new AmqpMessageSubscriber(amqpUri, spec, 100);
        subscriber.subscribe("orders");

        // publish after the subscriber is consuming
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(amqpUri, spec);
        publisher.publish("orders", "{\"orderId\":7}");
        publisher.close();

        List<RecordedMessage> messages = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            messages = subscriber.getRecordedMessages("orders");
            if (!messages.isEmpty()) break;
            Thread.sleep(200);
        }

        assertThat("subscriber should record the published message", messages.size(), greaterThanOrEqualTo(1));
        assertThat(messages.get(0).getChannel(), is("orders"));
        assertThat(messages.get(0).getPayload(), is("{\"orderId\":7}"));

        subscriber.close();
    }

    /**
     * Subscribe/record path (exchange/routing-key channel): the subscriber declares
     * the exchange, binds a private queue on the routing key, and records a routed message.
     */
    @Test
    public void subscriberShouldRecordFromExchangeRoutingKeyChannel() throws Exception {
        String exchange = "live-sub-events";
        String channelName = "user.created";
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, exchange, "topic", true, null, true, null);
        AsyncApiSpec spec = specWithChannel(channelName, binding);

        AmqpMessageSubscriber subscriber = new AmqpMessageSubscriber(amqpUri, spec, 100);
        subscriber.subscribe(channelName);

        AmqpMessagePublisher publisher = new AmqpMessagePublisher(amqpUri, spec);
        publisher.publish(channelName, "{\"user\":\"bob\"}");
        publisher.close();

        List<RecordedMessage> messages = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            messages = subscriber.getRecordedMessages(channelName);
            if (!messages.isEmpty()) break;
            Thread.sleep(200);
        }

        assertThat("subscriber should record the routed message", messages.size(), greaterThanOrEqualTo(1));
        assertThat(messages.get(0).getPayload(), is("{\"user\":\"bob\"}"));

        subscriber.close();
    }

    // ---- helpers ----

    private AsyncApiSpec specWithChannel(String name, AmqpBinding binding) {
        AsyncApiChannel channel = new AsyncApiChannel(
            name, List.of(), null, null, null, null, null, null, binding);
        return new AsyncApiSpec("2.6.0", "Test", List.of(channel));
    }

    /**
     * Open a fresh connection, poll the given queue (waiting briefly), and return the body.
     */
    private String basicGet(String queue) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(amqpUri);
        try (Connection connection = factory.newConnection(); Channel ch = connection.createChannel()) {
            ch.queueDeclare(queue, true, false, false, null);
            return pollQueue(ch, queue);
        }
    }

    private String pollQueue(Channel ch, String queue) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            GetResponse response = ch.basicGet(queue, true);
            if (response != null) {
                return new String(response.getBody(), StandardCharsets.UTF_8);
            }
            Thread.sleep(200);
        }
        return null;
    }
}
