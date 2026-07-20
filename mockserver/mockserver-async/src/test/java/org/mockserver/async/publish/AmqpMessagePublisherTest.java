package org.mockserver.async.publish;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ReturnListener;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.async.asyncapi.AmqpBinding;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AmqpMessagePublisher} destination derivation and publishing,
 * using a mocked AMQP {@link Channel} (no broker).
 */
public class AmqpMessagePublisherTest {

    private Connection connection;
    private Channel amqpChannel;

    @Before
    public void setUp() throws Exception {
        connection = mock(Connection.class);
        amqpChannel = mock(Channel.class);
        when(connection.isOpen()).thenReturn(true);
        when(amqpChannel.isOpen()).thenReturn(true);
        // unstubbed booleans are false in Mockito, and false now means "broker nacked"
        when(amqpChannel.waitForConfirms(anyLong())).thenReturn(true);
    }

    // ---- destination derivation (pure, broker-free) ----

    @Test
    public void shouldDeriveDefaultExchangeWithChannelNameWhenNoBinding() {
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("orders", null);
        assertThat(d.exchange, is(""));
        assertThat(d.routingKey, is("orders"));
    }

    @Test
    public void shouldDeriveExchangeAndChannelNameRoutingKeyForRoutingKeyBinding() {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "topic", true, null, true, null);
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("user.signedup", binding);
        assertThat(d.exchange, is("events"));
        assertThat(d.routingKey, is("user.signedup"));
    }

    @Test
    public void shouldPreferExplicitRoutingKeyOverChannelName() {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "topic", true, null, true, "explicit.key");
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("channel-name", binding);
        assertThat(d.exchange, is("events"));
        assertThat(d.routingKey, is("explicit.key"));
    }

    @Test
    public void shouldDeriveDefaultExchangeAndQueueNameForQueueBinding() {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.QUEUE, null, null, true, "orders-queue", true, null);
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("orders", binding);
        assertThat(d.exchange, is(""));
        assertThat(d.routingKey, is("orders-queue"));
    }

    @Test
    public void shouldFallBackToChannelNameForQueueBindingWithoutQueueName() {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.QUEUE, null, null, true, null, true, null);
        AmqpMessagePublisher.Destination d =
            AmqpMessagePublisher.resolveDestination("orders", binding);
        assertThat(d.exchange, is(""));
        assertThat(d.routingKey, is("orders"));
    }

    // ---- publishing via mocked channel ----

    @Test
    public void shouldPublishToDefaultExchangeWhenNoBinding() throws Exception {
        AsyncApiSpec spec = specWithChannel("plain", null);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        publisher.publish("plain", "{\"a\":1}");

        verify(amqpChannel).basicPublish(eq(""), eq("plain"), eq(true), isNull(),
            eq("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        // default exchange must not be declared
        verify(amqpChannel, never()).exchangeDeclare(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void shouldDeclareAndPublishToExchangeForRoutingKeyBinding() throws Exception {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "topic", true, null, true, null);
        AsyncApiSpec spec = specWithChannel("user.signedup", binding);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        publisher.publish("user.signedup", "{\"e\":1}");

        verify(amqpChannel).exchangeDeclare(eq("events"),
            eq(BuiltinExchangeType.TOPIC), eq(true));
        verify(amqpChannel).basicPublish(eq("events"), eq("user.signedup"), eq(true), isNull(),
            eq("{\"e\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void shouldDeclareQueueAndPublishToDefaultExchangeForQueueBinding() throws Exception {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.QUEUE, null, null, true, "orders-queue", true, null);
        AsyncApiSpec spec = specWithChannel("orders", binding);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        publisher.publish("orders", "{\"o\":1}");

        verify(amqpChannel).queueDeclare(eq("orders-queue"), eq(true), eq(false), eq(false), isNull());
        verify(amqpChannel).basicPublish(eq(""), eq("orders-queue"), eq(true), isNull(),
            eq("{\"o\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void shouldDeclareExchangeOnlyOnceAcrossMultiplePublishes() throws Exception {
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "direct", true, null, true, null);
        AsyncApiSpec spec = specWithChannel("c", binding);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        publisher.publish("c", "{\"n\":1}");
        publisher.publish("c", "{\"n\":2}");

        verify(amqpChannel, times(1)).exchangeDeclare(eq("events"), any(BuiltinExchangeType.class), anyBoolean());
        verify(amqpChannel, times(2)).basicPublish(eq("events"), eq("c"), eq(true), any(), any(byte[].class));
    }

    @Test
    public void shouldEmitHeadersAsAmqpProperties() throws Exception {
        AsyncApiSpec spec = specWithChannel("c", null);
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, spec);

        PublishOptions options = new PublishOptions(null, null, null,
            java.util.Collections.singletonMap("correlationId", "abc-123"));
        publisher.publish("c", "{\"x\":1}", options);

        verify(amqpChannel).basicPublish(eq(""), eq("c"), eq(true),
            argThat((AMQP.BasicProperties props) -> props != null && props.getHeaders() != null
                && "abc-123".equals(String.valueOf(props.getHeaders().get("correlationId")))),
            any(byte[].class));
    }

    // ---- unroutable message detection (AMQP 0-9-1 §3.1.3) ----

    @Test
    public void shouldPutChannelIntoConfirmModeAndListenForReturns() throws Exception {
        new AmqpMessagePublisher(connection, amqpChannel, specWithChannel("c", null));

        verify(amqpChannel).confirmSelect();
        verify(amqpChannel).addReturnListener(any(ReturnListener.class));
    }

    @Test
    public void shouldWaitForPublisherConfirmOnEveryPublish() throws Exception {
        AmqpMessagePublisher publisher =
            new AmqpMessagePublisher(connection, amqpChannel, specWithChannel("c", null));

        publisher.publish("c", "{\"x\":1}");

        // never waitForConfirmsOrDie: it closes the channel on nack/timeout, which cannot be reopened
        verify(amqpChannel).waitForConfirms(anyLong());
        verify(amqpChannel, never()).waitForConfirmsOrDie(anyLong());
    }

    @Test
    public void shouldFailPublishWhenBrokerReturnsMessageAsUnroutable() throws Exception {
        ArgumentCaptor<ReturnListener> listenerCaptor = ArgumentCaptor.forClass(ReturnListener.class);
        AmqpBinding binding = new AmqpBinding(
            AmqpBinding.ChannelType.ROUTING_KEY, "events", "topic", true, null, true, null);
        AmqpMessagePublisher publisher =
            new AmqpMessagePublisher(connection, amqpChannel, specWithChannel("orders.new", binding));
        verify(amqpChannel).addReturnListener(listenerCaptor.capture());
        ReturnListener listener = listenerCaptor.getValue();

        // simulate the broker returning the message because nothing is bound to the exchange
        doAnswer(invocation -> {
            listener.handleReturn(312, "NO_ROUTE", "events", "orders.new", null, new byte[0]);
            return null;
        }).when(amqpChannel).basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));

        RuntimeException failure = null;
        try {
            publisher.publish("orders.new", "{\"o\":1}");
        } catch (RuntimeException e) {
            failure = e;
        }

        assertThat("an unroutable publish must not be reported as success",
            failure != null, is(true));
        assertThat(failure.getMessage().contains("was not routed to any queue"), is(true));
    }

    @Test
    public void shouldNotFailSubsequentPublishAfterAnUnroutableOne() throws Exception {
        ArgumentCaptor<ReturnListener> listenerCaptor = ArgumentCaptor.forClass(ReturnListener.class);
        AmqpMessagePublisher publisher =
            new AmqpMessagePublisher(connection, amqpChannel, specWithChannel("c", null));
        verify(amqpChannel).addReturnListener(listenerCaptor.capture());
        ReturnListener listener = listenerCaptor.getValue();

        doAnswer(invocation -> {
            listener.handleReturn(312, "NO_ROUTE", "", "c", null, new byte[0]);
            return null;
        }).when(amqpChannel).basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));
        try {
            publisher.publish("c", "{\"n\":1}");
        } catch (RuntimeException expected) {
            // first publish is unroutable
        }

        // a routable publish afterwards must succeed — the return state must not leak between publishes
        doAnswer(invocation -> null).when(amqpChannel)
            .basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));
        publisher.publish("c", "{\"n\":2}");
    }

    /**
     * Publisher confirms are a RabbitMQ extension, not part of AMQP 0-9-1. A broker without them
     * answers {@code 540 NOT_IMPLEMENTED}, which is a <b>channel-level</b> error: the broker closes
     * the channel, and a closed AMQP channel can never be reopened — only replaced. So the fixture
     * must model a <em>closed</em> channel after the failure. (An earlier version of this test
     * stubbed {@code isOpen() == true}, a state a real broker never produces, which is why it could
     * not fail for the reason the compatibility claim would fail.)
     */
    @Test
    public void shouldReplaceClosedChannelAndKeepPublishingWhenConfirmsAreUnsupported() throws Exception {
        Channel refused = mock(Channel.class);
        when(refused.confirmSelect()).thenThrow(new java.io.IOException("540 NOT_IMPLEMENTED"));
        // the broker closed the channel as part of refusing confirm.select
        when(refused.isOpen()).thenReturn(false);

        Channel replacement = mock(Channel.class);
        when(replacement.isOpen()).thenReturn(true);
        when(connection.createChannel()).thenReturn(replacement);

        AmqpMessagePublisher publisher =
            new AmqpMessagePublisher(connection, refused, specWithChannel("c", null));

        // the dead channel must have been replaced from the connection
        verify(connection).createChannel();

        // must not throw, and must use the replacement rather than the closed channel
        publisher.publish("c", "{\"x\":1}");

        verify(replacement).basicPublish(eq(""), eq("c"), eq(false), isNull(),
            eq("{\"x\":1}".getBytes(StandardCharsets.UTF_8)));
        verify(refused, never()).basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));
        // and without waiting for a confirm that was never selected
        verify(replacement, never()).waitForConfirms(anyLong());
    }

    /**
     * A nacked message must fail the publish without destroying the channel. {@code
     * waitForConfirmsOrDie} closes it, and since a closed channel cannot be reopened that would
     * make every subsequent publish fail for the lifetime of the mock.
     */
    @Test
    public void shouldFailPublishOnNackWithoutClosingTheChannel() throws Exception {
        when(amqpChannel.waitForConfirms(anyLong())).thenReturn(false);
        AmqpMessagePublisher publisher =
            new AmqpMessagePublisher(connection, amqpChannel, specWithChannel("c", null));

        RuntimeException failure = null;
        try {
            publisher.publish("c", "{\"x\":1}");
        } catch (RuntimeException e) {
            failure = e;
        }

        assertThat("a nacked message must not be reported as published", failure != null, is(true));
        verify(amqpChannel, never()).close();
        verify(amqpChannel, never()).close(anyInt(), anyString());

        // and the publisher must still work once the broker recovers
        when(amqpChannel.waitForConfirms(anyLong())).thenReturn(true);
        publisher.publish("c", "{\"x\":2}");
    }

    /**
     * A confirm timeout must likewise leave the channel usable — RabbitMQ stalls confirms
     * indefinitely under a memory or disk alarm, which is a transient condition.
     */
    @Test
    public void shouldFailPublishOnConfirmTimeoutWithoutClosingTheChannel() throws Exception {
        when(amqpChannel.waitForConfirms(anyLong()))
            .thenThrow(new java.util.concurrent.TimeoutException("no ack"));
        AmqpMessagePublisher publisher =
            new AmqpMessagePublisher(connection, amqpChannel, specWithChannel("c", null));

        RuntimeException failure = null;
        try {
            publisher.publish("c", "{\"x\":1}");
        } catch (RuntimeException e) {
            failure = e;
        }

        assertThat("a confirm timeout must not be reported as published", failure != null, is(true));
        verify(amqpChannel, never()).close();
        verify(amqpChannel, never()).close(anyInt(), anyString());

        // the channel survives, so publishing resumes once the alarm clears
        reset(amqpChannel);
        when(amqpChannel.isOpen()).thenReturn(true);
        when(amqpChannel.waitForConfirms(anyLong())).thenReturn(true);
        publisher.publish("c", "{\"x\":2}");
        verify(amqpChannel).basicPublish(eq(""), eq("c"), eq(true), isNull(),
            eq("{\"x\":2}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void closeShouldCloseChannelAndConnection() throws Exception {
        AmqpMessagePublisher publisher = new AmqpMessagePublisher(connection, amqpChannel, null);
        publisher.close();
        verify(amqpChannel).close();
        verify(connection).close();
    }

    private AsyncApiSpec specWithChannel(String name, AmqpBinding binding) {
        AsyncApiChannel channel = new AsyncApiChannel(
            name, List.of(), null, null, null, null, null, null, binding);
        return new AsyncApiSpec("2.6.0", "Test", List.of(channel));
    }
}
