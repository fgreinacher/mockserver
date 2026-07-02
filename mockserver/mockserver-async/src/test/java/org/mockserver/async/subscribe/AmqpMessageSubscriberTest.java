package org.mockserver.async.subscribe;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockserver.async.asyncapi.AmqpBinding;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for {@link AmqpMessageSubscriber} using a mocked RabbitMQ {@link Channel}
 * (no live broker) — verifies queue resolution, consume registration, and recording.
 */
public class AmqpMessageSubscriberTest {

    @Mock
    private Connection mockConnection;
    @Mock
    private Channel mockChannel;

    @Before
    public void setUp() {
        openMocks(this);
    }

    private AsyncApiSpec specWith(String channelName, AmqpBinding binding) {
        AsyncApiChannel channel = new AsyncApiChannel(
            channelName, List.of(), null, null, null, null, null, null, binding);
        return new AsyncApiSpec("2.6.0", "Test", List.of(channel));
    }

    private static Delivery delivery(byte[] body, Map<String, Object> headers) {
        Envelope envelope = new Envelope(1L, false, "", "rk");
        AMQP.BasicProperties props = headers != null
            ? new AMQP.BasicProperties.Builder().headers(headers).build()
            : new AMQP.BasicProperties.Builder().build();
        return new Delivery(envelope, props, body);
    }

    @Test
    public void shouldDeclareAndConsumeQueueBasedChannel() throws Exception {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.QUEUE, null, null, true, "orders-q", true, null);
        AsyncApiSpec spec = specWith("orders", binding);
        when(mockChannel.basicConsume(anyString(), anyBoolean(), any(DeliverCallback.class), any(CancelCallback.class)))
            .thenReturn("tag-1");

        AmqpMessageSubscriber subscriber =
            new AmqpMessageSubscriber(mockConnection, mockChannel, spec, 100);
        subscriber.subscribe("orders");

        // declared the named queue and consumed it
        verify(mockChannel).queueDeclare(eq("orders-q"), eq(true), eq(false), eq(false), isNull());
        ArgumentCaptor<DeliverCallback> callbackCaptor = ArgumentCaptor.forClass(DeliverCallback.class);
        verify(mockChannel).basicConsume(eq("orders-q"), eq(true), callbackCaptor.capture(), any(CancelCallback.class));

        // deliver a message and assert it is recorded against the channel name
        callbackCaptor.getValue().handle("tag-1",
            delivery("{\"orderId\":42}".getBytes(StandardCharsets.UTF_8), Map.of("trace", "abc")));

        List<RecordedMessage> messages = subscriber.getRecordedMessages("orders");
        assertThat(messages, hasSize(1));
        assertThat(messages.get(0).getChannel(), is("orders"));
        assertThat(messages.get(0).getPayload(), is("{\"orderId\":42}"));
        assertThat(messages.get(0).getHeaders(), hasEntry("trace", "abc"));
    }

    @Test
    public void shouldDeclareExchangeBindPrivateQueueForRoutingKeyChannel() throws Exception {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "topic", true, null, true, null);
        AsyncApiSpec spec = specWith("user.signedup", binding);

        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(declareOk.getQueue()).thenReturn("amq.gen-private");
        when(mockChannel.queueDeclare()).thenReturn(declareOk);
        when(mockChannel.basicConsume(anyString(), anyBoolean(), any(DeliverCallback.class), any(CancelCallback.class)))
            .thenReturn("tag-2");

        AmqpMessageSubscriber subscriber =
            new AmqpMessageSubscriber(mockConnection, mockChannel, spec, 100);
        subscriber.subscribe("user.signedup");

        verify(mockChannel).exchangeDeclare("events", BuiltinExchangeType.TOPIC, true);
        verify(mockChannel).queueBind("amq.gen-private", "events", "user.signedup");
        verify(mockChannel).basicConsume(eq("amq.gen-private"), eq(true),
            any(DeliverCallback.class), any(CancelCallback.class));
    }

    @Test
    public void shouldNormaliseNonLowercaseExchangeType() throws Exception {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "Topic", true, null, true, null);
        AsyncApiSpec spec = specWith("user.signedup", binding);

        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(declareOk.getQueue()).thenReturn("amq.gen-private");
        when(mockChannel.queueDeclare()).thenReturn(declareOk);
        when(mockChannel.basicConsume(anyString(), anyBoolean(), any(DeliverCallback.class), any(CancelCallback.class)))
            .thenReturn("tag-6");

        AmqpMessageSubscriber subscriber =
            new AmqpMessageSubscriber(mockConnection, mockChannel, spec, 100);
        subscriber.subscribe("user.signedup");

        // mixed-case "Topic" is normalised to BuiltinExchangeType.TOPIC, matching the publisher
        verify(mockChannel).exchangeDeclare("events", BuiltinExchangeType.TOPIC, true);
    }

    @Test
    public void shouldConsumeChannelNameQueueWhenNoBinding() throws Exception {
        AsyncApiSpec spec = specWith("plain-channel", null);
        when(mockChannel.basicConsume(anyString(), anyBoolean(), any(DeliverCallback.class), any(CancelCallback.class)))
            .thenReturn("tag-3");

        AmqpMessageSubscriber subscriber =
            new AmqpMessageSubscriber(mockConnection, mockChannel, spec, 100);
        subscriber.subscribe("plain-channel");

        verify(mockChannel).queueDeclare(eq("plain-channel"), eq(true), eq(false), eq(false), isNull());
        verify(mockChannel).basicConsume(eq("plain-channel"), eq(true),
            any(DeliverCallback.class), any(CancelCallback.class));
    }

    @Test
    public void shouldCancelConsumerOnUnsubscribe() throws Exception {
        AsyncApiSpec spec = specWith("orders", null);
        when(mockChannel.basicConsume(anyString(), anyBoolean(), any(DeliverCallback.class), any(CancelCallback.class)))
            .thenReturn("tag-4");

        AmqpMessageSubscriber subscriber =
            new AmqpMessageSubscriber(mockConnection, mockChannel, spec, 100);
        subscriber.subscribe("orders");
        subscriber.unsubscribe("orders");

        verify(mockChannel).basicCancel("tag-4");
    }

    @Test
    public void shouldEvictOldestWhenCapReached() throws Exception {
        AsyncApiSpec spec = specWith("bounded", null);
        when(mockChannel.basicConsume(anyString(), anyBoolean(), any(DeliverCallback.class), any(CancelCallback.class)))
            .thenReturn("tag-5");

        AmqpMessageSubscriber subscriber =
            new AmqpMessageSubscriber(mockConnection, mockChannel, spec, 2);
        subscriber.subscribe("bounded");

        ArgumentCaptor<DeliverCallback> callbackCaptor = ArgumentCaptor.forClass(DeliverCallback.class);
        verify(mockChannel).basicConsume(eq("bounded"), eq(true), callbackCaptor.capture(), any(CancelCallback.class));
        DeliverCallback callback = callbackCaptor.getValue();

        for (int i = 0; i < 4; i++) {
            callback.handle("tag-5", delivery(("v" + i).getBytes(StandardCharsets.UTF_8), null));
        }

        List<RecordedMessage> messages = subscriber.getRecordedMessages("bounded");
        assertThat(messages, hasSize(2));
        assertThat(messages.get(0).getPayload(), is("v2"));
        assertThat(messages.get(1).getPayload(), is("v3"));
    }
}
