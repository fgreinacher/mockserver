package org.mockserver.socket.tls.bouncycastle;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyAndCertificateFactory;

import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * @author jnormington
 */
public class BCKeyAndCertificateFactoryTest {

    // serverAuth / clientAuth extended-key-usage OIDs (RFC 5280)
    private static final String SERVER_AUTH_EKU_OID = "1.3.6.1.5.5.7.3.1";
    private static final String CLIENT_AUTH_EKU_OID = "1.3.6.1.5.5.7.3.2";

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private KeyAndCertificateFactory keyAndCertificateFactory;

    @Before
    public void setUp() throws Exception {
        // Generate a dynamic CA into a private temp directory so the CA is freshly BouncyCastle-generated
        // (its subjectKeyIdentifier is derived with the same method as the leaf's authorityKeyIdentifier,
        // so the two can be compared for equality) and no global state or shared directory is touched —
        // this keeps the test safely in the parallel phase.
        // Pin the leaf validity to the documented default on the instance (rather than relying on the
        // global default) so the test is hermetic even if another class mutates the static store in parallel.
        Configuration configuration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(true)
            .directoryToSaveDynamicSSLCertificate(tempDir.newFolder("ca").getAbsolutePath())
            .sslCertificateLeafValidityInDays((int) KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_DEFAULT);
        keyAndCertificateFactory = new BCKeyAndCertificateFactory(configuration, new MockServerLogger());
        keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();
    }

    @Test
    public void shouldCreateCACertWithPositiveSerialNumber() {
        assertThat("the ca cert serial number is a positive integer (RFC 5280 §4.1.2.2)",
            keyAndCertificateFactory.certificateAuthorityX509Certificate().getSerialNumber().compareTo(BigInteger.ZERO) > 0, is(true));
    }

    @Test
    public void shouldCreateLeafCertWithPositiveSerialNumber() {
        assertThat("the leaf cert serial number is a positive integer (RFC 5280 §4.1.2.2)",
            keyAndCertificateFactory.x509Certificate().getSerialNumber().compareTo(BigInteger.ZERO) > 0, is(true));
    }

    @Test
    public void shouldGenerateLongLivedCertificateAuthority() {
        // The CA is the trust anchor users pin, so it deliberately keeps its long documented life — only
        // the leaf was shortened. Split from the old single assertion so the CA guard remains explicit.
        X509Certificate ca = keyAndCertificateFactory.certificateAuthorityX509Certificate();
        long now = System.currentTimeMillis();

        assertThat("CA notBefore is not in the future", ca.getNotBefore().getTime(), is(lessThanOrEqualTo(now)));
        long expectedMinimumNotAfter = now + TimeUnit.DAYS.toMillis(365L * KeyAndCertificateFactory.CERTIFICATE_VALIDITY_YEARS - 1);
        assertThat("CA notAfter is at least the documented long-lived period in the future",
            ca.getNotAfter().getTime(), is(greaterThanOrEqualTo(expectedMinimumNotAfter)));
    }

    @Test
    public void shouldGenerateShortLivedLeafWithinAppleServerCertificateCap() {
        // Deliberately the inverse of the CA guard: the leaf's TOTAL validity window (notAfter - notBefore,
        // the span Apple's 825-day server-cert cap measures) must be at most the documented 397 days, so it
        // can no longer silently drift back to the old 10-year leaf that broke Apple clients (issue #2531).
        X509Certificate leaf = keyAndCertificateFactory.x509Certificate();
        long now = System.currentTimeMillis();

        assertThat("leaf notBefore is not in the future (5-day clock-skew back-dating preserved)",
            leaf.getNotBefore().getTime(), is(lessThanOrEqualTo(now)));
        long validityWindowMillis = leaf.getNotAfter().getTime() - leaf.getNotBefore().getTime();
        assertThat("leaf total validity window is at most the documented 397 days",
            validityWindowMillis, is(lessThanOrEqualTo(TimeUnit.DAYS.toMillis(KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_DEFAULT))));
        assertThat("leaf is still valid now", leaf.getNotAfter().getTime(), is(greaterThanOrEqualTo(now)));
    }

    @Test
    public void shouldSetServerAuthAndClientAuthExtendedKeyUsageOnLeaf() throws Exception {
        // Apple requires serverAuth on the leaf independently of validity; without it iOS/macOS fail the
        // handshake even with a compliant validity period (issue #2531).
        X509Certificate leaf = keyAndCertificateFactory.x509Certificate();

        assertThat("leaf declares an extendedKeyUsage", leaf.getExtendedKeyUsage(), is(notNullValue()));
        assertThat("leaf extendedKeyUsage contains serverAuth", leaf.getExtendedKeyUsage(), hasItem(SERVER_AUTH_EKU_OID));
        assertThat("leaf extendedKeyUsage contains clientAuth", leaf.getExtendedKeyUsage(), hasItem(CLIENT_AUTH_EKU_OID));
    }

    @Test
    public void shouldSetCriticalTlsServerKeyUsageOnLeaf() {
        X509Certificate leaf = keyAndCertificateFactory.x509Certificate();
        boolean[] keyUsage = leaf.getKeyUsage();

        assertThat("leaf declares a keyUsage", keyUsage, is(notNullValue()));
        assertThat("leaf keyUsage asserts digitalSignature", keyUsage[0], is(true));
        assertThat("leaf keyUsage asserts keyEncipherment", keyUsage[2], is(true));
        assertThat("leaf keyUsage is marked critical",
            leaf.getCriticalExtensionOIDs(), hasItem(Extension.keyUsage.getId()));
    }

    @Test
    public void shouldSetAuthorityKeyIdentifierMatchingCertificateAuthoritySubjectKeyIdentifier() {
        X509Certificate leaf = keyAndCertificateFactory.x509Certificate();
        X509Certificate ca = keyAndCertificateFactory.certificateAuthorityX509Certificate();

        byte[] caSubjectKeyId = SubjectKeyIdentifier.getInstance(unwrap(ca.getExtensionValue(Extension.subjectKeyIdentifier.getId()))).getKeyIdentifier();
        byte[] leafAuthorityKeyId = AuthorityKeyIdentifier.getInstance(unwrap(leaf.getExtensionValue(Extension.authorityKeyIdentifier.getId()))).getKeyIdentifier();

        assertThat("leaf authorityKeyIdentifier is present", leafAuthorityKeyId, is(notNullValue()));
        assertThat("leaf authorityKeyIdentifier equals the CA subjectKeyIdentifier (so AKI->SKI chain building works)",
            leafAuthorityKeyId, is(caSubjectKeyId));
    }

    @Test
    public void shouldNotSetExtendedKeyUsageOnCertificateAuthority() throws Exception {
        // EKU on a trust anchor is non-idiomatic; the old CA also mixed two specific EKUs with the
        // contradictory anyExtendedKeyUsage. A freshly generated CA now carries none.
        X509Certificate ca = keyAndCertificateFactory.certificateAuthorityX509Certificate();

        assertThat("CA carries no extendedKeyUsage", ca.getExtendedKeyUsage(), is(nullValue()));
    }

    @Test
    public void shouldRestoreLongLivedLeafWhenOverrideConfigured() throws Exception {
        // Back-compat escape hatch: setting the validity override restores the historical long-lived leaf.
        Configuration configuration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(true)
            .directoryToSaveDynamicSSLCertificate(tempDir.newFolder("override-ca").getAbsolutePath())
            .sslCertificateLeafValidityInDays(3650);
        KeyAndCertificateFactory factory = new BCKeyAndCertificateFactory(configuration, new MockServerLogger());
        factory.buildAndSavePrivateKeyAndX509Certificate();

        X509Certificate leaf = factory.x509Certificate();
        long validityWindowMillis = leaf.getNotAfter().getTime() - leaf.getNotBefore().getTime();
        assertThat("overridden leaf validity window is ~3650 days",
            validityWindowMillis, is(greaterThanOrEqualTo(TimeUnit.DAYS.toMillis(3650 - 1))));
    }

    // getExtensionValue returns the DER OCTET STRING wrapping the extension value; unwrap one layer to the
    // inner encoded structure.
    private static byte[] unwrap(byte[] rawExtensionValue) {
        return ASN1OctetString.getInstance(rawExtensionValue).getOctets();
    }
}
