package org.mockserver.async.publish;

import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for {@link Mqtt5MessagePublisher} using a mock MQTT v5 client, including
 * user-property (header) delivery that v3 cannot do.
 */
public class Mqtt5MessagePublisherTest {

    @Mock
    private MqttClient mockClient;

    @Before
    public void setUp() {
        openMocks(this);
    }

    @Test
    public void shouldPublishPayloadWithConfiguredQos() throws MqttException {
        Mqtt5MessagePublisher publisher = new Mqtt5MessagePublisher(mockClient, 2);
        publisher.publish("topic", "{\"a\":1}");

        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("topic"), captor.capture());
        assertThat(captor.getValue().getQos(), is(2));
        assertThat(new String(captor.getValue().getPayload(), StandardCharsets.UTF_8), is("{\"a\":1}"));
    }

    @Test
    public void shouldApplyQosAndRetainFromOptions() throws MqttException {
        Mqtt5MessagePublisher publisher = new Mqtt5MessagePublisher(mockClient, 0);
        publisher.publish("topic", "{\"a\":1}", new PublishOptions(null, 1, true));

        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("topic"), captor.capture());
        assertThat(captor.getValue().getQos(), is(1));
        assertThat(captor.getValue().isRetained(), is(true));
    }

    @Test
    public void shouldDeliverHeadersAsUserProperties() throws MqttException {
        Mqtt5MessagePublisher publisher = new Mqtt5MessagePublisher(mockClient, 1);
        PublishOptions options = new PublishOptions(null, null, null,
            Map.of("correlationId", "corr-123"));
        publisher.publish("topic", "{\"a\":1}", options);

        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("topic"), captor.capture());
        List<UserProperty> userProperties = captor.getValue().getProperties().getUserProperties();
        assertThat(userProperties, hasSize(1));
        assertThat(userProperties.get(0).getKey(), is("correlationId"));
        assertThat(userProperties.get(0).getValue(), is("corr-123"));
    }

    @Test
    public void shouldPublishBinaryPayload() throws MqttException {
        Mqtt5MessagePublisher publisher = new Mqtt5MessagePublisher(mockClient, 1);
        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        publisher.publishBytes("topic", payload);

        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("topic"), captor.capture());
        assertThat(captor.getValue().getPayload(), is(payload));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidQosInConstructor() {
        new Mqtt5MessagePublisher("tcp://localhost:1883", "client", 5);
    }

    @Test
    public void shouldReportConfiguredQos() {
        Mqtt5MessagePublisher publisher = new Mqtt5MessagePublisher(mockClient, 2);
        assertThat(publisher.getQos(), is(2));
    }
}
