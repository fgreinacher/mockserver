package org.mockserver.netty.http3;

import org.junit.Test;
import org.mockserver.socket.tls.KeyAndCertificateFactory;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Covers the HTTP/3 legacy echo-mode self-signed certificate (reached only when {@code configuration ==
 * null}; the configured HTTP/3 path uses the real {@link KeyAndCertificateFactory}).
 * <p>
 * <strong>Deliberate decision:</strong> this certificate is simultaneously trust anchor AND server
 * certificate and has no renewal loop behind it, so — unlike the real short-lived leaf — it keeps the
 * long CA-style validity ({@link KeyAndCertificateFactory#CERTIFICATE_VALIDITY_YEARS}); a short-lived
 * self-signed anchor with nothing to renew it would simply expire the echo endpoint. It is otherwise
 * brought up to standard: 5-day back-dated notBefore, positive serial, SAN, serverAuth EKU and keyUsage.
 * <p>
 * The certificate is generated with pure BouncyCastle/JCA, so this test does not need the native QUIC
 * transport and is not gated on it.
 */
public class Http3ServerSelfSignedCertValidityTest {

    private static final String SERVER_AUTH_EKU_OID = "1.3.6.1.5.5.7.3.1";

    private static X509Certificate generateSelfSignedCert() throws Exception {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("EC");
        keyPairGen.initialize(256, new SecureRandom());
        KeyPair keyPair = keyPairGen.generateKeyPair();

        Method generateSelfSignedCert = Http3Server.class.getDeclaredMethod("generateSelfSignedCert", KeyPair.class);
        generateSelfSignedCert.setAccessible(true);
        return (X509Certificate) generateSelfSignedCert.invoke(null, keyPair);
    }

    @Test
    public void shouldGenerateSelfSignedCertWithLongLivedValidityAndBackDatedNotBefore() throws Exception {
        long now = System.currentTimeMillis();
        X509Certificate certificate = generateSelfSignedCert();

        // notBefore is back-dated for clock-skew tolerance, so it must be clearly in the past (not merely
        // "not in the future") — assert at least 4 days back-dating to prove the back-dating actually happens
        assertThat("notBefore is back-dated into the past for clock-skew tolerance",
            certificate.getNotBefore().getTime(), is(lessThanOrEqualTo(now - TimeUnit.DAYS.toMillis(4))));

        // trust-anchor + server cert with no renewal loop keeps the long CA-style validity
        long expectedMinimumNotAfter = now + TimeUnit.DAYS.toMillis(365L * KeyAndCertificateFactory.CERTIFICATE_VALIDITY_YEARS - 1);
        assertThat("notAfter is at least the documented long-lived period in the future",
            certificate.getNotAfter().getTime(), is(greaterThanOrEqualTo(expectedMinimumNotAfter)));
    }

    @Test
    public void shouldGenerateSelfSignedCertWithPositiveSerial() throws Exception {
        X509Certificate certificate = generateSelfSignedCert();

        assertThat("serial is a positive integer (RFC 5280 §4.1.2.2)",
            certificate.getSerialNumber(), is(greaterThan(BigInteger.ZERO)));
    }

    @Test
    public void shouldGenerateSelfSignedCertWithServerAuthExtendedKeyUsage() throws Exception {
        X509Certificate certificate = generateSelfSignedCert();

        assertThat("cert declares an extendedKeyUsage", certificate.getExtendedKeyUsage(), is(notNullValue()));
        assertThat("cert extendedKeyUsage contains serverAuth (required by Apple)",
            certificate.getExtendedKeyUsage(), hasItem(SERVER_AUTH_EKU_OID));
    }

    @Test
    public void shouldGenerateSelfSignedCertWithKeyUsageAndSubjectAlternativeNames() throws Exception {
        X509Certificate certificate = generateSelfSignedCert();

        boolean[] keyUsage = certificate.getKeyUsage();
        assertThat("cert declares a keyUsage", keyUsage, is(notNullValue()));
        assertThat("cert keyUsage asserts digitalSignature", keyUsage[0], is(true));

        assertThat("cert declares subject alternative names", certificate.getSubjectAlternativeNames(), is(notNullValue()));
        boolean hasLocalhost = certificate.getSubjectAlternativeNames().stream()
            .anyMatch(san -> "localhost".equals(san.get(1)));
        assertThat("cert SAN includes localhost", hasLocalhost, is(true));
    }
}
