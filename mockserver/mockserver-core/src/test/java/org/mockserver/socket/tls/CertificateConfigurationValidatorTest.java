package org.mockserver.socket.tls;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;

public class CertificateConfigurationValidatorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @Test
    public void shouldPassWithDefaultConfiguration() {
        Configuration config = configuration();
        new CertificateConfigurationValidator(config, mockServerLogger).validate();
    }

    @Test
    public void shouldFailWhenOnlyPrivateKeyPathSet() throws IOException {
        File keyFile = createTempPemFile("key.pem", getDummyPrivateKeyPem());
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("Both 'privateKeyPath' and 'x509CertificatePath' must be configured together"));
            assertThat(e.getMessage(), containsString("'x509CertificatePath' is not set"));
        }
    }

    @Test
    public void shouldFailWhenOnlyX509CertificatePathSet() throws IOException {
        File certFile = createTempPemFile("cert.pem", getDummyCertPem());
        Configuration config = configuration();
        config.x509CertificatePath(certFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("Both 'privateKeyPath' and 'x509CertificatePath' must be configured together"));
            assertThat(e.getMessage(), containsString("'privateKeyPath' is not set"));
        }
    }

    @Test
    public void shouldFailWithInvalidPrivateKeyFile() throws IOException {
        File keyFile = createTempPemFile("key.pem", "not a valid pem");
        File certFile = createTempPemFile("cert.pem", getDummyCertPem());
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("is not a valid PEM-encoded private key"));
        }
    }

    @Test
    public void shouldFailWithInvalidCertificateFile() throws Exception {
        String[] keyAndCert = generateSelfSignedKeyAndCert();
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", "not a valid pem");
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("is not a valid PEM-encoded certificate"));
        }
    }

    @Test
    public void shouldPassWithMatchingKeyAndCert() throws Exception {
        String[] keyAndCert = generateSelfSignedKeyAndCert();
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert[1]);
        File caCertFile = createTempPemFile("ca.pem", keyAndCert[1]);
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());
        config.certificateAuthorityCertificate(caCertFile.getAbsolutePath());

        new CertificateConfigurationValidator(config, mockServerLogger).validate();
    }

    @Test
    public void shouldFailWithMismatchedKeyAndCert() throws Exception {
        String[] keyAndCert1 = generateSelfSignedKeyAndCert();
        String[] keyAndCert2 = generateSelfSignedKeyAndCert();
        File keyFile = createTempPemFile("key.pem", keyAndCert1[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert2[1]);
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("does not match the certificate"));
        }
    }

    @Test
    public void shouldFailWithInvalidCaCertificateFile() throws Exception {
        String[] keyAndCert = generateSelfSignedKeyAndCert();
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert[1]);
        File caCertFile = createTempPemFile("ca.pem", "not a valid pem");
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());
        config.certificateAuthorityCertificate(caCertFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("is not a valid PEM-encoded X.509 certificate"));
        }
    }

    @Test
    public void shouldFailWhenCertNotSignedByCa() throws Exception {
        String[] keyAndCert = generateSelfSignedKeyAndCert();
        String[] caKeyAndCert = generateSelfSignedKeyAndCert();
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert[1]);
        File caCertFile = createTempPemFile("ca.pem", caKeyAndCert[1]);
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());
        config.certificateAuthorityCertificate(caCertFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("was not signed by the CA certificate"));
        }
    }

    @Test
    public void shouldPassWhenCertIsSignedByCa() throws Exception {
        String[] keyAndCert = generateSelfSignedKeyAndCert();
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert[1]);
        File caCertFile = createTempPemFile("ca.pem", keyAndCert[1]);
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());
        config.certificateAuthorityCertificate(caCertFile.getAbsolutePath());

        new CertificateConfigurationValidator(config, mockServerLogger).validate();
    }

    @Test
    public void shouldFailWithInvalidCaPrivateKeyFile() throws Exception {
        String[] keyAndCert = generateSelfSignedKeyAndCert();
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert[1]);
        File caCertFile = createTempPemFile("ca.pem", keyAndCert[1]);
        File caKeyFile = createTempPemFile("cakey.pem", "not a valid pem");
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());
        config.certificateAuthorityCertificate(caCertFile.getAbsolutePath());
        config.certificateAuthorityPrivateKey(caKeyFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("CA private key file"));
            assertThat(e.getMessage(), containsString("is not a valid PEM-encoded private key"));
        }
    }

    @Test
    public void shouldSkipCaCertFileValidationWhenPathIsDefault() throws Exception {
        String[] keyAndCert = generateSelfSignedKeyAndCert();
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert[1]);
        File caCertFile = createTempPemFile("ca.pem", keyAndCert[1]);
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());
        config.certificateAuthorityCertificate(caCertFile.getAbsolutePath());

        new CertificateConfigurationValidator(config, mockServerLogger).validate();
    }

    @Test
    public void shouldFailWithExpiredCertificate() throws Exception {
        String[] keyAndCert = generateKeyAndCertWithDates(
            new Date(System.currentTimeMillis() - 86400000L * 365 * 2),
            new Date(System.currentTimeMillis() - 86400000L)
        );
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert[1]);
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("expired on"));
            assertThat(e.getMessage(), containsString("Replace it with a valid certificate"));
        }
    }

    @Test
    public void shouldFailWithNotYetValidCertificate() throws Exception {
        String[] keyAndCert = generateKeyAndCertWithDates(
            new Date(System.currentTimeMillis() + 86400000L),
            new Date(System.currentTimeMillis() + 86400000L * 365)
        );
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert[1]);
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("is not yet valid until"));
            assertThat(e.getMessage(), containsString("Replace it with a valid certificate"));
        }
    }

    @Test
    public void shouldFailWhenCustomLeafUsedWithDefaultCa() throws Exception {
        String[] keyAndCert = generateSelfSignedKeyAndCert();
        File keyFile = createTempPemFile("key.pem", keyAndCert[0]);
        File certFile = createTempPemFile("cert.pem", keyAndCert[1]);
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException because custom leaf is not signed by default CA");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("was not signed by the CA certificate"));
        }
    }

    @Test
    public void shouldPassWithMultipleCertsInPemFile() throws Exception {
        String[] keyAndCert1 = generateSelfSignedKeyAndCert();
        String[] keyAndCert2 = generateSelfSignedKeyAndCert();
        String chainPem = keyAndCert1[1] + keyAndCert2[1];
        File keyFile = createTempPemFile("key.pem", keyAndCert1[0]);
        File certFile = createTempPemFile("cert.pem", chainPem);
        File caCertFile = createTempPemFile("ca.pem", keyAndCert1[1]);
        Configuration config = configuration();
        config.privateKeyPath(keyFile.getAbsolutePath());
        config.x509CertificatePath(certFile.getAbsolutePath());
        config.certificateAuthorityCertificate(caCertFile.getAbsolutePath());

        new CertificateConfigurationValidator(config, mockServerLogger).validate();
    }

    // ----------------------------------------------------------------------------------------------
    // Error and mapping paths of the key/certificate match check (issue #2728). These branches are
    // not exercised by the happy-path/genuine-mismatch matrix in
    // CertificateConfigurationValidatorKeyMatchTest, and are unit-level (no subject/issuer matrix),
    // so they live here in the flat test rather than the parameterized one where they would run
    // redundantly for every row.
    // ----------------------------------------------------------------------------------------------

    @Test
    public void shouldMapEverySupportedKeyAlgorithmToItsSignatureAlgorithm() {
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey("RSA"), is("SHA256withRSA"));
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey("EC"), is("SHA256withECDSA"));
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey("ECDSA"), is("SHA256withECDSA"));
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey("DSA"), is("SHA256withDSA"));
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey("Ed25519"), is("Ed25519"));
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey("Ed448"), is("Ed448"));
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey("EdDSA"), is("EdDSA"));
        // the mapping is case-insensitive - a provider that reports "rsa" must map identically
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey("rsa"), is("SHA256withRSA"));
    }

    @Test
    public void shouldReturnNullSignatureAlgorithmForNullKeyAlgorithm() {
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey(null), is(nullValue()));
    }

    @Test
    public void shouldReturnNullSignatureAlgorithmForUnknownKeyAlgorithm() {
        assertThat(CertificateConfigurationValidator.signatureAlgorithmForKey("XMSS"), is(nullValue()));
    }

    @Test
    public void shouldRejectUnsupportedKeyAlgorithmNamingTheAlgorithm() {
        PrivateKey unsupportedKey = new StubPrivateKey("XMSS", new byte[]{1, 2, 3});

        try {
            new CertificateConfigurationValidator(configuration(), mockServerLogger)
                .validateKeyMatchesCertificate(unsupportedKey, null, "key.pem", "cert.pem");
            fail("expected RuntimeException for unsupported key algorithm");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("unsupported key algorithm 'XMSS'"));
        }
    }

    @Test
    public void shouldReportSignatureAlgorithmInapplicableRatherThanMismatch() throws Exception {
        // A key whose algorithm maps to a real signature algorithm the provider cannot apply to it:
        // getAlgorithm() reports RSA (so sigAlgorithm is SHA256withRSA), but the key material is not a
        // usable RSA key, so Signature.initSign throws in the SIGN phase - before any comparison with
        // the certificate. That must NOT be reported as a key/certificate mismatch.
        PrivateKey unusableRsaKey = new StubPrivateKey("RSA", new byte[]{1, 2, 3});
        KeyPair rsa = generateRsaKeyPair();
        X509Certificate certificate = buildCertificate(rsa.getPublic(), rsa.getPrivate(), "SHA256withRSA");

        try {
            new CertificateConfigurationValidator(configuration(), mockServerLogger)
                .validateKeyMatchesCertificate(unusableRsaKey, certificate, "key.pem", "cert.pem");
            fail("expected RuntimeException for inapplicable signature algorithm");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("could not be used with the signature algorithm"));
            assertThat(e.getMessage(), containsString("This is not a mismatch between the key and the certificate"));
            assertThat(e.getMessage(), containsString("(algorithm 'RSA')"));
            assertThat(e.getMessage(), not(containsString("does not match the certificate")));
        }
    }

    @Test
    public void shouldReportMismatchWhenVerifyThrowsForCrossTypeKeyAndCertificate() throws Exception {
        // Sign phase succeeds (real RSA key), but the certificate's public key is EC, so the verify
        // phase's initVerify throws InvalidKeyException rather than returning false - the catch at the
        // verify phase, distinct from the verify-returns-false branch. It is a genuine mismatch, so it
        // must carry the "does not match" advice, and the original cause must be preserved.
        KeyPair rsa = generateRsaKeyPair();
        KeyPair ec = generateEcKeyPair();
        X509Certificate ecSubjectCertificate = buildCertificate(ec.getPublic(), rsa.getPrivate(), "SHA256withRSA");

        Configuration config = configuration();
        config.privateKeyPath(writePem("key.pem", "PRIVATE KEY", rsa.getPrivate().getEncoded()));
        config.x509CertificatePath(writePem("cert.pem", "CERTIFICATE", ecSubjectCertificate.getEncoded()));

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException for RSA key against EC certificate");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("does not match the certificate"));
            assertThat(e.getMessage(), containsString("Regenerate the key pair"));
            assertThat(e.getMessage(), not(containsString("not a mismatch between the key and the certificate")));
            // the verify-throws branch preserves the underlying cause; verify-returns-false does not
            assertThat(e.getCause(), is(notNullValue()));
        }
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static X509Certificate buildCertificate(PublicKey subjectPublicKey, PrivateKey issuerPrivateKey, String issuerSignatureAlgorithm) throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        X500Name dn = new X500Name("CN=Test");
        BigInteger serial = BigInteger.valueOf(System.nanoTime());
        Date notBefore = new Date(System.currentTimeMillis() - 86400000L);
        Date notAfter = new Date(System.currentTimeMillis() + 86400000L * 365);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(dn, serial, notBefore, notAfter, dn, subjectPublicKey);
        ContentSigner signer = new JcaContentSignerBuilder(issuerSignatureAlgorithm).setProvider("BC").build(issuerPrivateKey);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer));
    }

    private String writePem(String name, String type, byte[] encoded) throws IOException {
        return createTempPemFile(name, pemEncode(type, encoded)).getAbsolutePath();
    }

    /**
     * A {@link PrivateKey} that reports a chosen algorithm name with key material that is not a usable
     * key of that type - the only way to drive {@link CertificateConfigurationValidator}'s
     * unsupported-algorithm and sign-phase-failure branches, which a real key loaded from a PEM file
     * cannot reach.
     */
    private static final class StubPrivateKey implements PrivateKey {
        private final String algorithm;
        private final byte[] encoded;

        private StubPrivateKey(String algorithm, byte[] encoded) {
            this.algorithm = algorithm;
            this.encoded = encoded;
        }

        @Override
        public String getAlgorithm() {
            return algorithm;
        }

        @Override
        public String getFormat() {
            return "PKCS#8";
        }

        @Override
        public byte[] getEncoded() {
            return encoded == null ? null : encoded.clone();
        }
    }

    private File createTempPemFile(String name, String content) throws IOException {
        File file = tempFolder.newFile(name);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
        return file;
    }

    private String getDummyPrivateKeyPem() {
        return "-----BEGIN RSA PRIVATE KEY-----\n" +
            "MIIEowIBAAKCAQEA0Z3VS5JJcds3xfn/ygWyF8PbnGy0AHB7MhgHcTz6sE2I2yPB\n" +
            "-----END RSA PRIVATE KEY-----";
    }

    private String getDummyCertPem() {
        return "-----BEGIN CERTIFICATE-----\n" +
            "MIICpDCCAYwCCQDMq2inYDfBQjANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDDAls\n" +
            "-----END CERTIFICATE-----";
    }

    private String[] generateSelfSignedKeyAndCert() throws Exception {
        return generateKeyAndCertWithDates(
            new Date(System.currentTimeMillis() - 86400000L),
            new Date(System.currentTimeMillis() + 86400000L * 365)
        );
    }

    private String[] generateKeyAndCertWithDates(Date notBefore, Date notAfter) throws Exception {
        org.bouncycastle.jce.provider.BouncyCastleProvider bc = new org.bouncycastle.jce.provider.BouncyCastleProvider();
        java.security.Security.addProvider(bc);

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        org.bouncycastle.asn1.x500.X500Name issuer = new org.bouncycastle.asn1.x500.X500Name("CN=Test");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        org.bouncycastle.cert.X509v3CertificateBuilder certBuilder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic()
        );

        org.bouncycastle.operator.ContentSigner signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(keyPair.getPrivate());

        X509Certificate cert = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certBuilder.build(signer));

        String keyPem = pemEncode("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String certPem = pemEncode("CERTIFICATE", cert.getEncoded());

        return new String[]{keyPem, certPem};
    }

    private String pemEncode(String type, byte[] encoded) {
        java.util.Base64.Encoder encoder = java.util.Base64.getMimeEncoder(64, "\n".getBytes());
        return "-----BEGIN " + type + "-----\n" +
            encoder.encodeToString(encoded) +
            "\n-----END " + type + "-----\n";
    }
}
