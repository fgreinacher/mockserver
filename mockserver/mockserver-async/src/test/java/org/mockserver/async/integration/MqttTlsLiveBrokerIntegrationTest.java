package org.mockserver.async.integration;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.async.publish.MqttMessagePublisher;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Credential-enforcement integration tests for MQTT security, driven against a real
 * Mosquitto broker configured with a {@code password_file} and {@code allow_anonymous false}.
 * <p>
 * Unlike {@link MqttLiveBrokerIntegrationTest} (which runs an {@code allow_anonymous true}
 * plaintext broker and therefore never exercises authentication), this suite proves that
 * {@link MqttSecurity} credentials are actually <em>applied and enforced</em> on the wire:
 * <ul>
 *   <li>a publisher wired with the <em>correct</em> username/password authenticates,
 *       connects and delivers a message to an authenticated subscriber; and</li>
 *   <li>a publisher wired with the <em>wrong</em> password (and one with no credentials at
 *       all) is <em>rejected</em> by the broker at CONNECT.</li>
 * </ul>
 * The correct-credentials delivery test is the positive control for the wiring: if
 * {@code MqttSecurityOptions} stopped applying the credentials, the correct-credentials
 * client would be rejected by the {@code allow_anonymous false} broker and that test would
 * turn RED — proving the assertion genuinely depends on credential enforcement rather than
 * passing vacuously.
 * <p>
 * Docker-gated: SKIPS (not fails) when Docker is unavailable.
 */
public class MqttTlsLiveBrokerIntegrationTest {

    private static final int MQTT_PORT = 1883;
    private static final String USERNAME = "mockserver";
    private static final String PASSWORD = "s3cr3t-pw";
    private static final String WRONG_PASSWORD = "not-the-password";

    // A minimal secured Mosquitto: a single reachable listener, anonymous access DISABLED,
    // and a password_file. `user root` keeps the broker from dropping privileges so it can
    // read the password_file that the (root) startup command writes into /mosquitto/config.
    private static final String MOSQUITTO_CONF =
        "listener " + MQTT_PORT + "\n"
            + "allow_anonymous false\n"
            + "password_file /mosquitto/config/pwfile\n"
            + "user root\n";

    // Generate the hashed password file with the image's own mosquitto_passwd, then exec the
    // broker. Doing it at container start avoids committing a pre-hashed (version-specific)
    // password file to the repo.
    private static final String STARTUP_COMMAND =
        "mosquitto_passwd -c -b /mosquitto/config/pwfile " + USERNAME + " " + PASSWORD
            + " && exec mosquitto -c /mosquitto/config/mosquitto.conf";

    @SuppressWarnings("resource")
    private static GenericContainer<?> mosquitto;
    private static String brokerUrl;

    @BeforeClass
    public static void checkDockerAndStartSecuredMosquitto() {
        // Fail-safe shared probe (LAMBDA so instance() is evaluated inside the wrapper's
        // try/catch): an unusable Docker SKIPS rather than errors off-CI.
        Assume.assumeTrue("Docker is not available — skipping MQTT credential-enforcement tests",
            DockerAvailability.isAvailable(
                () -> org.testcontainers.DockerClientFactory.instance().isDockerAvailable()));

        mosquitto = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2.0.22"))
            .withExposedPorts(MQTT_PORT)
            .withCopyToContainer(Transferable.of(MOSQUITTO_CONF), "/mosquitto/config/mosquitto.conf")
            .withCommand("sh", "-c", STARTUP_COMMAND)
            .waitingFor(Wait.forLogMessage(".*mosquitto.*running.*", 1)
                .withStartupTimeout(Duration.ofSeconds(30)));
        mosquitto.start();

        brokerUrl = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(MQTT_PORT);
    }

    @AfterClass
    public static void stopMosquitto() {
        if (mosquitto != null && mosquitto.isRunning()) {
            mosquitto.stop();
        }
    }

    /**
     * POSITIVE + positive control: a publisher wired with the correct {@link MqttSecurity}
     * credentials authenticates against the secured broker and delivers a message that an
     * authenticated subscriber receives. If credential wiring regressed, the correct-credentials
     * publisher would be rejected (anonymous access is disabled) and this test would fail.
     */
    @Test
    public void correctCredentialsShouldAuthenticateAndDeliverViaSecuredBroker() throws Exception {
        String topic = "secure/publish-receive";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        // Authenticated subscriber (plain Paho, correct credentials) verifies delivery.
        MqttConnectOptions subOptions = new MqttConnectOptions();
        subOptions.setUserName(USERNAME);
        subOptions.setPassword(PASSWORD.toCharArray());
        MqttClient subscriber = new MqttClient(brokerUrl, "authed-subscriber");
        subscriber.connect(subOptions);
        subscriber.subscribe(topic, (t, msg) -> {
            receivedPayload.set(new String(msg.getPayload(), StandardCharsets.UTF_8));
            latch.countDown();
        });

        // MockServer's publisher, driven by MqttSecurity credentials.
        MqttSecurity security = MqttSecurity.builder()
            .username(USERNAME)
            .password(PASSWORD)
            .build();
        MqttMessagePublisher publisher = new MqttMessagePublisher(brokerUrl, "authed-publisher", 1, security);
        publisher.publish(topic, "{\"secure\":true}");

        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertThat("authenticated publish should be delivered through the secured broker",
            received, is(true));
        assertThat(receivedPayload.get(), is("{\"secure\":true}"));

        publisher.close();
        subscriber.disconnect();
        subscriber.close();
    }

    /**
     * NEGATIVE: a publisher wired with the WRONG password is rejected by the broker at CONNECT.
     * Proves the broker validates credentials and that the failure surfaces to the caller
     * (rather than silently connecting).
     */
    @Test
    public void wrongPasswordShouldBeRejectedBySecuredBroker() {
        MqttSecurity badSecurity = MqttSecurity.builder()
            .username(USERNAME)
            .password(WRONG_PASSWORD)
            .build();

        RuntimeException thrown = null;
        MqttMessagePublisher publisher = null;
        try {
            publisher = new MqttMessagePublisher(brokerUrl, "wrong-password-publisher", 1, badSecurity);
        } catch (RuntimeException e) {
            thrown = e;
        } finally {
            if (publisher != null) {
                publisher.close();
            }
        }

        assertThat("connecting with the wrong password must be rejected, not silently accepted",
            thrown, is(notNullValue()));
        assertThat(thrown.getMessage(), containsString("Failed to connect to MQTT broker"));
    }

    /**
     * NEGATIVE: a publisher with NO credentials is rejected because anonymous access is disabled.
     * Confirms {@code allow_anonymous false} is actually in force, so the positive control above
     * is a genuine discriminator (an unauthenticated client cannot connect).
     */
    @Test
    public void anonymousConnectionShouldBeRejectedBySecuredBroker() {
        RuntimeException thrown = null;
        MqttMessagePublisher publisher = null;
        try {
            // No security -> Paho no-arg connect() -> anonymous CONNECT.
            publisher = new MqttMessagePublisher(brokerUrl, "anonymous-publisher");
        } catch (RuntimeException e) {
            thrown = e;
        } finally {
            if (publisher != null) {
                publisher.close();
            }
        }

        assertThat("anonymous connection must be rejected when allow_anonymous is false",
            thrown, is(notNullValue()));
        assertThat(thrown.getMessage(), containsString("Failed to connect to MQTT broker"));
    }
}
