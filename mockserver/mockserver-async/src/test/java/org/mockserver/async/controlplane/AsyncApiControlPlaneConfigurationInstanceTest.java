package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests that async broker defaults set on a {@link Configuration} <em>instance</em> — as
 * happens when {@code PUT /mockserver/configuration} applies a configuration DTO — are
 * honoured by {@link AsyncApiControlPlaneImpl}.
 * <p>
 * These guard a defect class where enforcement sites read the static
 * {@link ConfigurationProperties} store directly, so instance-set values silently did
 * nothing. Each test sets the value ONLY on the instance and leaves the static store
 * untouched, so a regression to a static read fails the assertion.
 */
public class AsyncApiControlPlaneConfigurationInstanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Configuration configuration = Configuration.configuration();
    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl(configuration);
    private final AsyncApiControlPlaneImpl noConfigControlPlane = new AsyncApiControlPlaneImpl();

    @After
    public void tearDown() {
        controlPlane.reset();
        noConfigControlPlane.reset();
    }

    // ---- broker defaults from the Configuration instance ----

    @Test
    public void shouldUseKafkaBootstrapServersFromConfigurationInstance() throws Exception {
        configuration.asyncKafkaBootstrapServers("instance-kafka:9092");

        JsonNode node = MAPPER.readTree("{\"consume\":false}");
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.kafkaBootstrapServers, is("instance-kafka:9092"));
    }

    @Test
    public void shouldUseMqttBrokerUrlFromConfigurationInstance() throws Exception {
        configuration.asyncMqttBrokerUrl("tcp://instance-mqtt:1883");

        JsonNode node = MAPPER.readTree("{\"consume\":false}");
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.mqttBrokerUrl, is("tcp://instance-mqtt:1883"));
    }

    @Test
    public void shouldUseAmqpUriFromConfigurationInstance() throws Exception {
        configuration.asyncAmqpUri("amqp://instance-amqp:5672");

        JsonNode node = MAPPER.readTree("{\"consume\":false}");
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.amqpUri, is("amqp://instance-amqp:5672"));
    }

    @Test
    public void shouldUseRecordedMessageMaxEntriesFromConfigurationInstance() {
        configuration.asyncRecordedMessageMaxEntries(4321);

        assertThat(controlPlane.recordedMessageMaxEntries(), is(4321));
    }

    // ---- explicit request values still win over the configured defaults ----

    @Test
    public void shouldPreferExplicitRequestValuesOverConfigurationInstance() throws Exception {
        configuration.asyncKafkaBootstrapServers("instance-kafka:9092");
        configuration.asyncMqttBrokerUrl("tcp://instance-mqtt:1883");
        configuration.asyncAmqpUri("amqp://instance-amqp:5672");

        JsonNode node = MAPPER.readTree("{"
            + "\"kafkaBootstrapServers\":\"request-kafka:9092\","
            + "\"mqttBrokerUrl\":\"tcp://request-mqtt:1883\","
            + "\"amqpUri\":\"amqp://request-amqp:5672\""
            + "}");
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.kafkaBootstrapServers, is("request-kafka:9092"));
        assertThat(config.mqttBrokerUrl, is("tcp://request-mqtt:1883"));
        assertThat(config.amqpUri, is("amqp://request-amqp:5672"));
    }

    // ---- the no-Configuration path still falls back to the static store ----

    @Test
    public void shouldFallBackToStaticStoreWhenNoConfigurationInstance() throws Exception {
        String previous = ConfigurationProperties.asyncKafkaBootstrapServers();
        try {
            ConfigurationProperties.asyncKafkaBootstrapServers("static-kafka:9092");

            JsonNode node = MAPPER.readTree("{\"consume\":false}");
            AsyncApiControlPlaneImpl.BrokerConfig config = noConfigControlPlane.parseBrokerConfig(node);

            assertThat(config.kafkaBootstrapServers, is("static-kafka:9092"));
        } finally {
            ConfigurationProperties.asyncKafkaBootstrapServers(previous == null ? "" : previous);
        }
    }

    /**
     * A Configuration instance with no value of its own must still see the static store,
     * because {@link Configuration} falls back to it — so binding the control-plane to an
     * instance must not break property/system-property configuration.
     */
    @Test
    public void shouldFallBackThroughConfigurationInstanceToStaticStore() throws Exception {
        String previous = ConfigurationProperties.asyncMqttBrokerUrl();
        try {
            ConfigurationProperties.asyncMqttBrokerUrl("tcp://static-mqtt:1883");

            JsonNode node = MAPPER.readTree("{\"consume\":false}");
            AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

            assertThat(config.mqttBrokerUrl, is("tcp://static-mqtt:1883"));
        } finally {
            ConfigurationProperties.asyncMqttBrokerUrl(previous == null ? "" : previous);
        }
    }
}
