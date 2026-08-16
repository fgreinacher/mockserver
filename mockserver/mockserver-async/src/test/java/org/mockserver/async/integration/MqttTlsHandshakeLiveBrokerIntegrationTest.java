package org.mockserver.async.integration;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.async.publish.MqttMessagePublisher;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * TLS-handshake integration tests for MQTT security, driven against a real Mosquitto broker
 * configured with a genuine TLS listener on 8883 (an on-the-fly CA + server certificate mounted
 * as {@code cafile}/{@code certfile}/{@code keyfile}).
 * <p>
 * {@link MqttTlsLiveBrokerIntegrationTest} proves credential enforcement but only over a
 * <em>plaintext</em> listener, so the actual TLS handshake / certificate trust path that
 * {@link MqttSecurity} exposes (its {@code sslProperties}, applied by
 * {@code MqttSecurityOptions.buildConnectOptions()} via Paho's {@code setSSLProperties()}) is
 * never exercised there. This suite closes that hole:
 * <ul>
 *   <li><b>Positive</b>: a publisher wired with the correct TLS trust (a truststore containing the
 *       broker's CA) completes the TLS handshake and delivers a message that an authenticated,
 *       TLS-connected subscriber receives.</li>
 *   <li><b>Negative</b>: a publisher whose truststore does <em>not</em> trust the broker's CA fails
 *       the TLS handshake — asserted specifically as an {@link SSLException} in the failure cause
 *       chain (PKIX path building failure), not a generic "failed to connect".</li>
 * </ul>
 * The positive test is the discriminator for the wiring: if {@code MqttSecurityOptions} stopped
 * applying the SSL properties, the trusting client would fall back to the JVM default truststore
 * (which does not trust the self-signed broker CA), the handshake would fail, and this test would
 * turn RED — proving the assertion genuinely depends on TLS trust being applied.
 * <p>
 * Docker-gated: SKIPS (not fails) when Docker is unavailable.
 */
public class MqttTlsHandshakeLiveBrokerIntegrationTest {

    private static final int MQTT_TLS_PORT = 8883;
    private static final String TRUSTSTORE_PASSWORD = "changeit";

    // A minimal TLS-only Mosquitto: a single TLS listener with the mounted CA/server cert/key.
    // `allow_anonymous true` keeps the focus on the TLS handshake (credential enforcement is
    // covered by MqttTlsLiveBrokerIntegrationTest). `user root` keeps the broker from dropping
    // privileges so it can read the root-owned mounted cert/key files.
    private static final String MOSQUITTO_CONF =
        "listener " + MQTT_TLS_PORT + "\n"
            + "allow_anonymous true\n"
            + "cafile /mosquitto/config/ca.crt\n"
            + "certfile /mosquitto/config/server.crt\n"
            + "keyfile /mosquitto/config/server.key\n"
            + "user root\n";

    @SuppressWarnings("resource")
    private static GenericContainer<?> mosquitto;
    private static String sslBrokerUrl;

    // The CA that signed the broker's server certificate — the trust anchor the client must have.
    private static X509Certificate brokerCaCertificate;
    // An unrelated CA the broker knows nothing about — used to build a truststore that does NOT
    // trust the broker, so the negative client's handshake fails on trust (not on connectivity).
    private static X509Certificate unrelatedCaCertificate;

    @BeforeClass
    public static void checkDockerAndStartTlsMosquitto() throws Exception {
        // Fail-safe shared probe (LAMBDA so instance() is evaluated inside the wrapper's
        // try/catch): an unusable Docker SKIPS rather than errors off-CI.
        Assume.assumeTrue("Docker is not available — skipping MQTT TLS-handshake tests",
            DockerAvailability.isAvailable(
                () -> DockerClientFactory.instance().isDockerAvailable()));

        // --- Generate the broker's CA + server certificate ---
        KeyPair caKeyPair = generateRsaKeyPair();
        brokerCaCertificate = generateSelfSignedCa(caKeyPair, "MockServer Async Test CA");

        // The host the client will actually connect to, resolved BEFORE the container starts
        // because it has to be baked into the server certificate's SAN. Testcontainers returns
        // "localhost" when the tests run on the Docker host, but the Docker BRIDGE GATEWAY IP
        // (e.g. 172.16.0.1) when they run inside a container against a mounted socket — which is
        // exactly how CI runs this step. Paho enables HTTPS endpoint identification by default
        // and verifies the certificate against whichever of those it gets, so a SAN hard-coded
        // to localhost/127.0.0.1 fails the handshake in CI with
        // "No subject alternative names matching IP address 172.16.0.1 found".
        String brokerHost = DockerClientFactory.instance().dockerHostIpAddress();

        KeyPair serverKeyPair = generateRsaKeyPair();
        X509Certificate serverCertificate =
            generateServerCertificate(serverKeyPair, brokerCaCertificate, caKeyPair.getPrivate(), brokerHost);

        // --- Generate an unrelated CA for the negative (does-not-trust-broker) client ---
        KeyPair unrelatedCaKeyPair = generateRsaKeyPair();
        unrelatedCaCertificate = generateSelfSignedCa(unrelatedCaKeyPair, "Unrelated Test CA");

        // --- Boot Mosquitto with a real TLS listener; mount the PEM material ---
        mosquitto = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2.0.22"))
            .withExposedPorts(MQTT_TLS_PORT)
            .withCopyToContainer(Transferable.of(MOSQUITTO_CONF), "/mosquitto/config/mosquitto.conf")
            .withCopyToContainer(Transferable.of(toPem(brokerCaCertificate)), "/mosquitto/config/ca.crt")
            .withCopyToContainer(Transferable.of(toPem(serverCertificate)), "/mosquitto/config/server.crt")
            .withCopyToContainer(Transferable.of(toPem(serverKeyPair.getPrivate())), "/mosquitto/config/server.key")
            .waitingFor(Wait.forLogMessage(".*mosquitto.*running.*", 1)
                .withStartupTimeout(Duration.ofSeconds(30)));
        mosquitto.start();

        // Fail loudly rather than with an opaque handshake error if Testcontainers ever resolves
        // the container host differently from the value baked into the certificate's SAN above.
        assertThat("the host baked into the server certificate SAN must be the host the client"
                + " connects to, otherwise the handshake fails on identity not on trust",
            mosquitto.getHost(), is(brokerHost));

        sslBrokerUrl = "ssl://" + brokerHost + ":" + mosquitto.getMappedPort(MQTT_TLS_PORT);
    }

    @AfterClass
    public static void stopMosquitto() {
        if (mosquitto != null && mosquitto.isRunning()) {
            mosquitto.stop();
        }
    }

    /**
     * POSITIVE + positive control: a publisher wired with a truststore containing the broker's CA
     * completes the TLS handshake and delivers a message a TLS-connected subscriber receives.
     * If the TLS wiring regressed (SSL properties no longer applied), the trusting client would
     * fall back to the JVM default truststore, the handshake against the self-signed broker cert
     * would fail, and this test would turn RED.
     */
    @Test
    public void trustingClientShouldCompleteTlsHandshakeAndDeliverViaSecuredBroker() throws Exception {
        String topic = "secure/tls-publish-receive";

        File trustStore = writeTrustStore("broker-ca", brokerCaCertificate);
        Map<String, String> sslProperties = trustStoreSslProperties(trustStore);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        // TLS-connected subscriber (plain Paho with the same trust) verifies delivery.
        MqttConnectOptions subOptions = new MqttConnectOptions();
        Properties subSslProps = new Properties();
        subSslProps.putAll(sslProperties);
        subOptions.setSSLProperties(subSslProps);
        MqttClient subscriber = new MqttClient(sslBrokerUrl, "tls-subscriber", new MemoryPersistence());
        subscriber.connect(subOptions);
        subscriber.subscribe(topic, (t, msg) -> {
            receivedPayload.set(new String(msg.getPayload(), StandardCharsets.UTF_8));
            latch.countDown();
        });

        // MockServer's publisher, driven by MqttSecurity TLS options.
        MqttSecurity security = MqttSecurity.builder()
            .sslProperties(sslProperties)
            .build();
        MqttMessagePublisher publisher = new MqttMessagePublisher(sslBrokerUrl, "tls-publisher", 1, security);
        publisher.publish(topic, "{\"secure\":true}");

        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertThat("a TLS handshake with correct trust should complete and the message be delivered",
            received, is(true));
        assertThat(receivedPayload.get(), is("{\"secure\":true}"));

        publisher.close();
        subscriber.disconnect();
        subscriber.close();
    }

    /**
     * NEGATIVE: a publisher whose truststore does NOT contain the broker's CA fails the TLS
     * handshake. Asserted specifically as an {@link SSLException} (PKIX trust failure) in the
     * cause chain — proving it is a genuine certificate-trust failure, not a generic connect
     * refusal (which would surface as a socket/connect exception, never an SSLException).
     */
    @Test
    public void clientNotTrustingBrokerCertShouldFailTlsHandshake() throws Exception {
        File wrongTrustStore = writeTrustStore("unrelated-ca", unrelatedCaCertificate);
        MqttSecurity untrustingSecurity = MqttSecurity.builder()
            .sslProperties(trustStoreSslProperties(wrongTrustStore))
            .build();

        RuntimeException thrown = null;
        MqttMessagePublisher publisher = null;
        try {
            publisher = new MqttMessagePublisher(sslBrokerUrl, "untrusting-tls-publisher", 1, untrustingSecurity);
        } catch (RuntimeException e) {
            thrown = e;
        } finally {
            if (publisher != null) {
                publisher.close();
            }
        }

        assertThat("a client that does not trust the broker certificate must fail, not silently connect",
            thrown, is(notNullValue()));
        assertThat(thrown.getMessage(), containsString("Failed to connect to MQTT broker"));
        assertThat("the failure must be a TLS certificate-trust failure (SSLException), not a plain connect error",
            findSslCause(thrown), is(notNullValue()));
        assertThat("the TLS failure should be a PKIX certification-path failure",
            findSslCause(thrown).getMessage(),
            anyOf(containsString("PKIX"), containsString("certification path"), containsString("unable to find valid")));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers: certificate generation (BouncyCastle, available via mockserver-core on the test
    // classpath) and truststore / SSL-property assembly.
    // ------------------------------------------------------------------------------------------

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }

    private static X509Certificate generateSelfSignedCa(KeyPair keyPair, String commonName) throws Exception {
        X500Name name = new X500Name("CN=" + commonName);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            name, newSerial(), notBefore(), notAfter(), name, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
        return sign(builder, keyPair.getPrivate());
    }

    private static X509Certificate generateServerCertificate(KeyPair serverKeyPair,
                                                             X509Certificate caCertificate,
                                                             PrivateKey caPrivateKey,
                                                             String brokerHost) throws Exception {
        X500Name issuer = new X500Name(caCertificate.getSubjectX500Principal().getName());
        X500Name subject = new X500Name("CN=localhost");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            issuer, newSerial(), notBefore(), notAfter(), subject, serverKeyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        builder.addExtension(Extension.extendedKeyUsage, false,
            new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        // SAN must match the host the client connects to; Paho enables HTTPS endpoint
        // identification by default, which verifies it. localhost / 127.0.0.1 cover the
        // on-the-Docker-host case; brokerHost covers running inside a container against a
        // mounted socket (CI), where Testcontainers hands back the bridge gateway IP.
        List<GeneralName> subjectAlternativeNames = new ArrayList<>();
        subjectAlternativeNames.add(new GeneralName(GeneralName.dNSName, "localhost"));
        subjectAlternativeNames.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        if (!"localhost".equals(brokerHost) && !"127.0.0.1".equals(brokerHost)) {
            subjectAlternativeNames.add(new GeneralName(
                isIpLiteral(brokerHost) ? GeneralName.iPAddress : GeneralName.dNSName, brokerHost));
        }
        builder.addExtension(Extension.subjectAlternativeName, false,
            new GeneralNames(subjectAlternativeNames.toArray(new GeneralName[0])));
        return sign(builder, caPrivateKey);
    }

    private static X509Certificate sign(JcaX509v3CertificateBuilder builder, PrivateKey signingKey) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(signingKey);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.contains(":");
    }

    private static BigInteger newSerial() {
        return new BigInteger(64, new SecureRandom());
    }

    private static Date notBefore() {
        return Date.from(Instant.now().minus(Duration.ofDays(1)));
    }

    private static Date notAfter() {
        return Date.from(Instant.now().plus(Duration.ofDays(3650)));
    }

    private static String toPem(Object object) throws Exception {
        StringWriter stringWriter = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
            pemWriter.writeObject(object);
        }
        return stringWriter.toString();
    }

    private static File writeTrustStore(String alias, X509Certificate certificate) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, null);
        trustStore.setCertificateEntry(alias, certificate);
        File file = File.createTempFile("mqtt-tls-truststore-", ".jks");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            trustStore.store(out, TRUSTSTORE_PASSWORD.toCharArray());
        }
        return file;
    }

    private static Map<String, String> trustStoreSslProperties(File trustStore) {
        Map<String, String> sslProperties = new LinkedHashMap<>();
        sslProperties.put("com.ibm.ssl.trustStore", trustStore.getAbsolutePath());
        sslProperties.put("com.ibm.ssl.trustStorePassword", TRUSTSTORE_PASSWORD);
        sslProperties.put("com.ibm.ssl.trustStoreType", "JKS");
        sslProperties.put("com.ibm.ssl.protocol", "TLSv1.2");
        return sslProperties;
    }

    private static SSLException findSslCause(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) {
                return (SSLException) cause;
            }
        }
        return null;
    }
}
