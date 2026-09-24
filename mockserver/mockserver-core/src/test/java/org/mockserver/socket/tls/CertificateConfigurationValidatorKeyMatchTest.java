package org.mockserver.socket.tls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.io.File;
import java.io.FileWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Matrix coverage for {@link CertificateConfigurationValidator}'s key/certificate match check
 * (issue #2728). The root cause was conflating two independent things: the <b>subject key type</b>
 * (which the private key must match) and the <b>issuer key type</b> (which only determines the
 * certificate's own signature algorithm). The validator picked its challenge algorithm from
 * {@code certificate.getSigAlgName()} — the issuer's — so an RSA certificate issued by an EC CA
 * was rejected even though the key matched perfectly.
 * <p>
 * Every subject/issuer combination must validate when the key matches, and every combination must
 * still be rejected — with the "does not match / regenerate" message, never the algorithm message —
 * when the key genuinely does not belong to the certificate. The negative axis is the one that
 * matters most: a fix that accepted everything would pass every positive row.
 */
@RunWith(Parameterized.class)
public class CertificateConfigurationValidatorKeyMatchTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @Parameterized.Parameter(0)
    public String subjectKeyAlgorithm;

    @Parameterized.Parameter(1)
    public String issuerKeyAlgorithm;

    @Parameterized.Parameters(name = "subject {0} key, certificate issued by {1} CA")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
            {"RSA", "EC"},      // the reported bug: RSA leaf, EC issuer -> cert reports SHA256withECDSA
            {"RSA", "RSA"},
            {"EC", "RSA"},
            {"EC", "EC"},
            {"Ed25519", "EC"},  // Ed25519 subject key, loadable via BouncyCastle even though not self-generated
        });
    }

    @BeforeClass
    public static void registerBouncyCastle() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    public void shouldPassWhenKeyMatchesCertificate() throws Exception {
        KeyPair subject = generateKeyPair(subjectKeyAlgorithm);
        KeyPair issuer = generateKeyPair(issuerKeyAlgorithm);
        X509Certificate leaf = buildCertificate(subject.getPublic(), "CN=Leaf", issuer.getPrivate(), "CN=Issuer", issuerKeyAlgorithm);
        X509Certificate ca = buildCertificate(issuer.getPublic(), "CN=Issuer", issuer.getPrivate(), "CN=Issuer", issuerKeyAlgorithm);

        Configuration config = configuration();
        config.privateKeyPath(writePem("key.pem", "PRIVATE KEY", subject.getPrivate().getEncoded()));
        config.x509CertificatePath(writePem("cert.pem", "CERTIFICATE", leaf.getEncoded()));
        config.certificateAuthorityCertificate(writePem("ca.pem", "CERTIFICATE", ca.getEncoded()));

        new CertificateConfigurationValidator(config, mockServerLogger).validate();
    }

    @Test
    public void shouldRejectWhenKeyDoesNotMatchCertificate() throws Exception {
        KeyPair subject = generateKeyPair(subjectKeyAlgorithm);
        KeyPair impostor = generateKeyPair(subjectKeyAlgorithm);
        KeyPair issuer = generateKeyPair(issuerKeyAlgorithm);
        X509Certificate leaf = buildCertificate(subject.getPublic(), "CN=Leaf", issuer.getPrivate(), "CN=Issuer", issuerKeyAlgorithm);
        X509Certificate ca = buildCertificate(issuer.getPublic(), "CN=Issuer", issuer.getPrivate(), "CN=Issuer", issuerKeyAlgorithm);

        Configuration config = configuration();
        config.privateKeyPath(writePem("key.pem", "PRIVATE KEY", impostor.getPrivate().getEncoded()));
        config.x509CertificatePath(writePem("cert.pem", "CERTIFICATE", leaf.getEncoded()));
        config.certificateAuthorityCertificate(writePem("ca.pem", "CERTIFICATE", ca.getEncoded()));

        try {
            new CertificateConfigurationValidator(config, mockServerLogger).validate();
            fail("expected RuntimeException for mismatched key/certificate");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("does not match the certificate"));
            assertThat(e.getMessage(), containsString("Regenerate the key pair"));
            assertThat(e.getMessage(), not(containsString("unsupported key algorithm")));
            assertThat(e.getMessage(), not(containsString("limitation of the running JVM")));
        }
    }

    private static KeyPair generateKeyPair(String algorithm) throws Exception {
        switch (algorithm) {
            case "RSA": {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "BC");
                generator.initialize(2048);
                return generator.generateKeyPair();
            }
            case "EC": {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                return generator.generateKeyPair();
            }
            case "Ed25519":
                return KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair();
            default:
                throw new IllegalArgumentException("unhandled key algorithm " + algorithm);
        }
    }

    private static String signatureAlgorithmForIssuer(String issuerKeyAlgorithm) {
        switch (issuerKeyAlgorithm) {
            case "RSA":
                return "SHA256withRSA";
            case "EC":
                return "SHA256withECDSA";
            case "Ed25519":
                return "Ed25519";
            default:
                throw new IllegalArgumentException("unhandled issuer algorithm " + issuerKeyAlgorithm);
        }
    }

    private static X509Certificate buildCertificate(PublicKey subjectPublicKey, String subjectDn, PrivateKey issuerPrivateKey, String issuerDn, String issuerKeyAlgorithm) throws Exception {
        X500Name subject = new X500Name(subjectDn);
        X500Name issuer = new X500Name(issuerDn);
        BigInteger serial = BigInteger.valueOf(System.nanoTime());
        Date notBefore = new Date(System.currentTimeMillis() - 86400000L);
        Date notAfter = new Date(System.currentTimeMillis() + 86400000L * 365);

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, subject, subjectPublicKey
        );
        ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithmForIssuer(issuerKeyAlgorithm))
            .setProvider("BC")
            .build(issuerPrivateKey);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(signer));
    }

    private String writePem(String name, String type, byte[] encoded) throws Exception {
        File file = tempFolder.newFile(name);
        Base64.Encoder encoder = Base64.getMimeEncoder(64, "\n".getBytes());
        String pem = "-----BEGIN " + type + "-----\n" + encoder.encodeToString(encoded) + "\n-----END " + type + "-----\n";
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(pem);
        }
        return file.getAbsolutePath();
    }
}
