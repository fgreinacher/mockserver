package org.mockserver.async.subscribe;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Wildcard subscriptions must be verifiable.
 *
 * <p>A message always arrives on a <i>concrete</i> topic, so it is recorded under that
 * topic — but a user who subscribed with a wildcard filter verifies using the
 * <i>filter</i>. Retrieving by filter must therefore match concrete topics per
 * MQTT 3.1.1 §4.7, otherwise a wildcard subscription records messages that can never
 * be retrieved and every verification silently reports zero matches.
 *
 * <p>Covers both the MQTT 3.1.1 and MQTT 5 subscribers, which share these semantics.
 */
public class MqttWildcardSubscriptionTest {

    // ---- MQTT 3.1.1 ----

    @Test
    public void shouldRetrieveMessagesOnConcreteTopicViaSingleLevelWildcardFilter() throws Exception {
        org.eclipse.paho.client.mqttv3.MqttClient client =
            mock(org.eclipse.paho.client.mqttv3.MqttClient.class);
        MqttMessageSubscriber subscriber = new MqttMessageSubscriber(client, 1);
        org.eclipse.paho.client.mqttv3.MqttCallback callback = captureV3Callback(client);

        subscriber.subscribe("sensors/+/temperature");
        callback.messageArrived("sensors/kitchen/temperature",
            new org.eclipse.paho.client.mqttv3.MqttMessage(
                "{\"temp\":21}".getBytes(StandardCharsets.UTF_8)));

        List<RecordedMessage> byFilter = subscriber.getRecordedMessages("sensors/+/temperature");
        assertThat("wildcard filter must match the concrete topic the message arrived on",
            byFilter, hasSize(1));
        assertThat(byFilter.get(0).getPayload(), is("{\"temp\":21}"));
        assertThat("the recorded channel remains the concrete topic",
            byFilter.get(0).getChannel(), is("sensors/kitchen/temperature"));
    }

    @Test
    public void shouldRetrieveMessagesAcrossLevelsViaMultiLevelWildcardFilter() throws Exception {
        org.eclipse.paho.client.mqttv3.MqttClient client =
            mock(org.eclipse.paho.client.mqttv3.MqttClient.class);
        MqttMessageSubscriber subscriber = new MqttMessageSubscriber(client, 1);
        org.eclipse.paho.client.mqttv3.MqttCallback callback = captureV3Callback(client);

        subscriber.subscribe("sensors/#");
        callback.messageArrived("sensors/kitchen/temperature", v3Message("a"));
        callback.messageArrived("sensors/hall/humidity/raw", v3Message("b"));
        callback.messageArrived("actuators/valve", v3Message("c"));

        List<RecordedMessage> byFilter = subscriber.getRecordedMessages("sensors/#");
        assertThat("multi-level wildcard must match every topic beneath the prefix",
            byFilter, hasSize(2));
        assertThat(byFilter.stream().map(RecordedMessage::getPayload).toList(),
            containsInAnyOrder("a", "b"));
    }

    @Test
    public void shouldNotMatchUnrelatedTopicsForWildcardFilter() throws Exception {
        org.eclipse.paho.client.mqttv3.MqttClient client =
            mock(org.eclipse.paho.client.mqttv3.MqttClient.class);
        MqttMessageSubscriber subscriber = new MqttMessageSubscriber(client, 1);
        org.eclipse.paho.client.mqttv3.MqttCallback callback = captureV3Callback(client);

        subscriber.subscribe("sensors/+/temperature");
        // one level too deep — '+' matches exactly one level
        callback.messageArrived("sensors/kitchen/inner/temperature", v3Message("x"));
        callback.messageArrived("sensors/kitchen/humidity", v3Message("y"));

        assertThat(subscriber.getRecordedMessages("sensors/+/temperature"), is(empty()));
    }

    @Test
    public void shouldStillMatchExactTopicWhenFilterHasNoWildcard() throws Exception {
        org.eclipse.paho.client.mqttv3.MqttClient client =
            mock(org.eclipse.paho.client.mqttv3.MqttClient.class);
        MqttMessageSubscriber subscriber = new MqttMessageSubscriber(client, 1);
        org.eclipse.paho.client.mqttv3.MqttCallback callback = captureV3Callback(client);

        subscriber.subscribe("sensors/kitchen/temperature");
        callback.messageArrived("sensors/kitchen/temperature", v3Message("exact"));

        assertThat(subscriber.getRecordedMessages("sensors/kitchen/temperature"), hasSize(1));
        assertThat(subscriber.getRecordedMessages("sensors/other/temperature"), is(empty()));
    }

    // ---- MQTT 5 ----

    @Test
    public void mqtt5ShouldRetrieveMessagesOnConcreteTopicViaWildcardFilter() throws Exception {
        org.eclipse.paho.mqttv5.client.MqttClient client =
            mock(org.eclipse.paho.mqttv5.client.MqttClient.class);
        Mqtt5MessageSubscriber subscriber = new Mqtt5MessageSubscriber(client, 1);

        ArgumentCaptor<org.eclipse.paho.mqttv5.client.MqttCallback> captor =
            ArgumentCaptor.forClass(org.eclipse.paho.mqttv5.client.MqttCallback.class);
        verify(client).setCallback(captor.capture());
        org.eclipse.paho.mqttv5.client.MqttCallback callback = captor.getValue();

        subscriber.subscribe("sensors/+/temperature");
        callback.messageArrived("sensors/kitchen/temperature",
            new org.eclipse.paho.mqttv5.common.MqttMessage(
                "{\"temp\":21}".getBytes(StandardCharsets.UTF_8)));

        List<RecordedMessage> byFilter = subscriber.getRecordedMessages("sensors/+/temperature");
        assertThat("MQTT 5 shares MQTT 3.1.1 wildcard semantics", byFilter, hasSize(1));
        assertThat(byFilter.get(0).getPayload(), is("{\"temp\":21}"));
    }

    // ---- helpers ----

    private static org.eclipse.paho.client.mqttv3.MqttCallback captureV3Callback(
        org.eclipse.paho.client.mqttv3.MqttClient client) {
        ArgumentCaptor<org.eclipse.paho.client.mqttv3.MqttCallback> captor =
            ArgumentCaptor.forClass(org.eclipse.paho.client.mqttv3.MqttCallback.class);
        verify(client).setCallback(captor.capture());
        return captor.getValue();
    }

    private static org.eclipse.paho.client.mqttv3.MqttMessage v3Message(String payload) {
        return new org.eclipse.paho.client.mqttv3.MqttMessage(
            payload.getBytes(StandardCharsets.UTF_8));
    }
}
