package org.mockserver.async.security;

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link Mqtt5SecurityOptions} — the MQTT v5 counterpart of
 * {@link MqttSecurityOptions}.
 */
public class Mqtt5SecurityOptionsTest {

    @Test
    public void shouldReturnNullForNullSecurity() {
        assertThat(Mqtt5SecurityOptions.buildConnectOptions(null), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForEmptySecurity() {
        assertThat(Mqtt5SecurityOptions.buildConnectOptions(MqttSecurity.empty()), is(nullValue()));
    }

    @Test
    public void shouldSetUsernameAndPassword() {
        MqttSecurity security = MqttSecurity.builder().username("u").password("p").build();
        MqttConnectionOptions options = Mqtt5SecurityOptions.buildConnectOptions(security);

        assertThat(options, is(notNullValue()));
        assertThat(options.getUserName(), is("u"));
        assertThat(options.getPassword(), is("p".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void shouldSetSslProperties() {
        MqttSecurity security = MqttSecurity.builder()
            .sslProperties(Map.of("com.ibm.ssl.trustStore", "/t.jks"))
            .build();
        MqttConnectionOptions options = Mqtt5SecurityOptions.buildConnectOptions(security);

        assertThat(options, is(notNullValue()));
        assertThat(options.getSSLProperties().getProperty("com.ibm.ssl.trustStore"), is("/t.jks"));
    }

    @Test
    public void shouldNotSetBlankUsername() {
        MqttSecurity security = MqttSecurity.builder().username("").password("pass").build();
        MqttConnectionOptions options = Mqtt5SecurityOptions.buildConnectOptions(security);

        assertThat(options, is(notNullValue()));
        assertThat(options.getUserName(), is(nullValue()));
        assertThat(options.getPassword(), is("pass".getBytes(StandardCharsets.UTF_8)));
    }
}
