package org.mockserver.async.subscribe;

import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for {@link Mqtt5MessageSubscriber} using a mock MQTT v5 client, including
 * recording MQTT 5 user properties as message headers.
 */
public class Mqtt5MessageSubscriberTest {

    @Mock
    private MqttClient mockClient;

    private Mqtt5MessageSubscriber subscriber;

    @Before
    public void setUp() {
        openMocks(this);
        subscriber = new Mqtt5MessageSubscriber(mockClient, 1);
    }

    @After
    public void tearDown() {
        when(mockClient.isConnected()).thenReturn(false);
        subscriber.close();
    }

    private MqttCallback capturedCallback() {
        ArgumentCaptor<MqttCallback> captor = ArgumentCaptor.forClass(MqttCallback.class);
        verify(mockClient).setCallback(captor.capture());
        return captor.getValue();
    }

    @Test
    public void shouldSubscribeToTopic() throws MqttException {
        subscriber.subscribe("sensor/temp");
        verify(mockClient).subscribe(eq("sensor/temp"), eq(1));
    }

    @Test
    public void shouldRecordMessagesViaCallback() throws Exception {
        MqttCallback callback = capturedCallback();
        subscriber.subscribe("sensor/temp");

        callback.messageArrived("sensor/temp",
            new MqttMessage("{\"temp\":22.5}".getBytes(StandardCharsets.UTF_8)));

        List<RecordedMessage> messages = subscriber.getRecordedMessages("sensor/temp");
        assertThat(messages, hasSize(1));
        assertThat(messages.get(0).getPayload(), is("{\"temp\":22.5}"));
        assertThat(messages.get(0).getKey(), is(nullValue()));
    }

    @Test
    public void shouldRecordUserPropertiesAsHeaders() throws Exception {
        MqttCallback callback = capturedCallback();
        subscriber.subscribe("events");

        MqttMessage message = new MqttMessage("{\"e\":1}".getBytes(StandardCharsets.UTF_8));
        MqttProperties props = new MqttProperties();
        props.setUserProperties(List.of(new UserProperty("correlationId", "corr-77")));
        message.setProperties(props);
        callback.messageArrived("events", message);

        List<RecordedMessage> messages = subscriber.getRecordedMessages("events");
        assertThat(messages, hasSize(1));
        assertThat(messages.get(0).getHeaders(), hasEntry("correlationId", "corr-77"));
    }

    @Test
    public void shouldReportUnhealthyAfterDisconnect() throws Exception {
        MqttCallback callback = capturedCallback();
        assertThat(subscriber.isHealthy(), is(true));
        callback.disconnected(null);
        assertThat(subscriber.isHealthy(), is(false));
    }

    @Test
    public void shouldEvictOldestWhenCapReached() throws Exception {
        when(mockClient.isConnected()).thenReturn(false);
        subscriber.close();
        subscriber = new Mqtt5MessageSubscriber(mockClient, 1, 2);

        ArgumentCaptor<MqttCallback> captor = ArgumentCaptor.forClass(MqttCallback.class);
        verify(mockClient, atLeast(1)).setCallback(captor.capture());
        MqttCallback callback = captor.getValue();

        subscriber.subscribe("bounded");
        for (int i = 0; i < 4; i++) {
            callback.messageArrived("bounded",
                new MqttMessage(("v" + i).getBytes(StandardCharsets.UTF_8)));
        }

        List<RecordedMessage> messages = subscriber.getRecordedMessages("bounded");
        assertThat(messages, hasSize(2));
        assertThat(messages.get(0).getPayload(), is("v2"));
        assertThat(messages.get(1).getPayload(), is("v3"));
    }
}
