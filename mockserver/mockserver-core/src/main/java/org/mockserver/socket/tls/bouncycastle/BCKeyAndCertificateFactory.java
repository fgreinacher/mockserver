package org.mockserver.socket.tls.bouncycastle;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.bc.BcX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.IPAddress;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileCreator;
import org.mockserver.file.FilePath;
import org.mockserver.file.FileReader;
import org.mockserver.keys.AsymmetricKeyGenerator;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyAndCertificateFactory;
import org.slf4j.event.Level;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.socket.tls.PEMToFile.*;
import static org.slf4j.event.Level.*;

/**
 * @author jamesdbloom
 */
public class BCKeyAndCertificateFactory implements KeyAndCertificateFactory {

    private static final String PROVIDER_NAME = "BC";
    private static volatile boolean providerRegistered;

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;

    /**
     * Registers the BouncyCastle JCE provider on first use, deferring the ~460-class BouncyCastle
     * load off the startup path: this is invoked lazily by the first key/cert operation that actually
     * needs the provider (key/cert generation, signing, CA loading), NOT from the constructor, so a
     * MockServer that never establishes a TLS connection never pays the cost. The double-checked
     * {@code volatile} guard keeps the fast path a single volatile read once registered, and the
     * {@code synchronized} entry makes concurrent first-TLS-connections safe (idempotent registration).
     */
    private static void ensureProviderRegistered() {
        if (!providerRegistered) {
            registerProvider();
        }
    }

    private static synchronized void registerProvider() {
        if (!providerRegistered) {
            if (Security.getProvider(PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            providerRegistered = true;
        }
    }

    private PrivateKey privateKey;
    private X509Certificate x509Certificate;
    private List<X509Certificate> x509CertificateChain;
    private PrivateKey certificateAuthorityPrivateKey;
    private X509Certificate certificateAuthorityX509Certificate;

    public BCKeyAndCertificateFactory(Configuration configuration, MockServerLogger mockServerLogger) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * build or generate & save ca private key and certificate
     */
    @Override
    public void buildAndSaveCertificateAuthorityPrivateKeyAndX509Certificate() {
        ensureProviderRegistered();
        if (dynamicallyUpdateCertificateAuthority() && certificateAuthorityCertificateNotYetCreated()) {
            try {
                AsymmetricKeyPairAlgorithm keyGenerationAndSigningAlgorithm = KeyAndCertificateFactory.DEFAULT_KEY_GENERATION_AND_SIGNING_ALGORITHM;
                KeyPair caKeyPair = AsymmetricKeyGenerator.createKeyPair(keyGenerationAndSigningAlgorithm);
                saveAsPEMFile(generateCACert(keyGenerationAndSigningAlgorithm, caKeyPair.getPublic(), caKeyPair.getPrivate()), certificateAuthorityX509CertificatePath(), "Certificate Authority X509 Certificate PEM");
                saveAsPEMFile(caKeyPair.getPrivate(), certificateAuthorityPrivateKeyPath(), "Certificate Authority Private Key PEM");
            } catch (Exception e) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while generating certificate authority private key and X509 certificate")
                        .setThrowable(e)
                );
            }
        }
    }

    /**
     * ca private key path
     */
    private String certificateAuthorityPrivateKeyPath() {
        if (dynamicallyUpdateCertificateAuthority()) {
            configuration.certificateAuthorityPrivateKey(new File(new File(configuration.directoryToSaveDynamicSSLCertificate()), "PKCS8CertificateAuthorityPrivateKey.pem").getAbsolutePath());
        }
        return configuration.certificateAuthorityPrivateKey();
    }

    /**
     * load ca private key
     */
    private PrivateKey certificateAuthorityPrivateKey() {
        if (certificateAuthorityPrivateKey == null) {
            if (dynamicallyUpdateCertificateAuthority()) {
                buildAndSaveCertificateAuthorityPrivateKeyAndX509Certificate();
            }
            certificateAuthorityPrivateKey = privateKeyFromPEMFile(certificateAuthorityPrivateKeyPath());
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setMessageFormat("loaded CA private key from path{}containing PEM{}")
                        .setArguments(FilePath.absolutePathFromClassPathOrPath(certificateAuthorityPrivateKeyPath()), certificateAuthorityPrivateKey)
                );
            } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(INFO)
                        .setMessageFormat("loaded CA private key from path{}")
                        .setArguments(FilePath.absolutePathFromClassPathOrPath(certificateAuthorityPrivateKeyPath()))
                );
            }
        }
        return certificateAuthorityPrivateKey;
    }

    /**
     * ca certificate path
     */
    private String certificateAuthorityX509CertificatePath() {
        if (dynamicallyUpdateCertificateAuthority()) {
            String absolutePath = new File(new File(configuration.directoryToSaveDynamicSSLCertificate()), "CertificateAuthorityCertificate.pem").getAbsolutePath();
            configuration.certificateAuthorityCertificate(absolutePath);
        }
        return configuration.certificateAuthorityCertificate();
    }

    /**
     * load ca certificate
     */
    public X509Certificate certificateAuthorityX509Certificate() {
        ensureProviderRegistered();
        if (certificateAuthorityX509Certificate == null) {
            if (dynamicallyUpdateCertificateAuthority()) {
                buildAndSaveCertificateAuthorityPrivateKeyAndX509Certificate();
            }
            certificateAuthorityX509Certificate = x509FromPEMFile(certificateAuthorityX509CertificatePath());
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(DEBUG)
                        .setMessageFormat("loaded CA X509 from path{}containing PEM{}as{}")
                        .setArguments(
                            FilePath.absolutePathFromClassPathOrPath(certificateAuthorityX509CertificatePath()),
                            FileReader.readFileFromClassPathOrPath(certificateAuthorityX509CertificatePath()),
                            certificateAuthorityX509Certificate
                        )
                );
            } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(INFO)
                        .setMessageFormat("loaded CA X509 from path{}containing PEM{}")
                        .setArguments(FilePath.absolutePathFromClassPathOrPath(certificateAuthorityX509CertificatePath()), FileReader.readFileFromClassPathOrPath(certificateAuthorityX509CertificatePath()))
                );
            }
        }
        return certificateAuthorityX509Certificate;
    }

    /**
     * generate ca certificate
     */
    private X509Certificate generateCACert(AsymmetricKeyPairAlgorithm keyGenerationAndSigningAlgorithm, PublicKey publicKey, PrivateKey privateKey) throws Exception {

        // signers name
        X500Name issuerName = new X500Name("CN=" + ROOT_COMMON_NAME + ", O=" + ORGANISATION + ", L=" + LOCALITY + ", ST=" + STATE + ", C=" + COUNTRY);

        // serial (RFC 5280 recommends unpredictable serials)
        BigInteger serial = new BigInteger(64, new SecureRandom());

        // create the certificate - version 3 (with subjects name same as issues as self signed)
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuerName, serial, KeyAndCertificateFactory.notBefore(), KeyAndCertificateFactory.notAfter(), issuerName, publicKey);
        builder.addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyIdentifier(publicKey));
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        KeyUsage usage = new KeyUsage(KeyUsage.keyCertSign | KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment | KeyUsage.cRLSign);
        builder.addExtension(Extension.keyUsage, false, usage);

        ASN1EncodableVector purposes = new ASN1EncodableVector();
        purposes.add(KeyPurposeId.id_kp_serverAuth);
        purposes.add(KeyPurposeId.id_kp_clientAuth);
        purposes.add(KeyPurposeId.anyExtendedKeyUsage);
        builder.addExtension(Extension.extendedKeyUsage, false, new DERSequence(purposes));

        X509Certificate cert = signCertificate(keyGenerationAndSigningAlgorithm, builder, privateKey);
        cert.checkValidity(new Date());
        cert.verify(publicKey);

        return cert;
    }

    /**
     * build or generate & save leaf private key and certificate
     */
    @Override
    public void buildAndSavePrivateKeyAndX509Certificate() {
        ensureProviderRegistered();
        if (shouldGenerateCertificates()) {
            try {
                if (dynamicallyUpdateCertificateAuthority()) {
                    buildAndSaveCertificateAuthorityPrivateKeyAndX509Certificate();
                }
                AsymmetricKeyPairAlgorithm keyGenerationAndSigningAlgorithm = KeyAndCertificateFactory.DEFAULT_KEY_GENERATION_AND_SIGNING_ALGORITHM;
                KeyPair keyPair = AsymmetricKeyGenerator.createKeyPair(keyGenerationAndSigningAlgorithm);
                privateKey = keyPair.getPrivate();
                x509Certificate = generateLeafCert(
                    keyGenerationAndSigningAlgorithm,
                    keyPair.getPublic(),
                    certificateAuthorityX509Certificate(),
                    certificateAuthorityPrivateKey(),
                    certificateAuthorityX509Certificate().getPublicKey(),
                    configuration.sslCertificateDomainName(),
                    configuration.sslSubjectAlternativeNameDomains(),
                    configuration.sslSubjectAlternativeNameIps()
                );
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(TRACE)
                            .setMessageFormat("created new X509{}with SAN Domain Names{}and IPs{}")
                            .setArguments(x509Certificate(), configuration.sslSubjectAlternativeNameDomains(), configuration.sslSubjectAlternativeNameIps())
                    );
                }
                if (configuration.preventCertificateDynamicUpdate() || configuration.proactivelyInitialiseTLS()) {
                    saveAsPEMFile(x509Certificate, x509CertificatePath(), "X509 Certificate PEM");
                    saveAsPEMFile(privateKey, privateKeyPath(), "Private Key PEM");
                }
            } catch (Exception e) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while generating private key and X509 certificate")
                        .setThrowable(e)
                );
            }
        }
    }

    /**
     * leaf private key path
     */
    private String privateKeyPath() {
        configuration.privateKeyPath(new File(new File(configuration.directoryToSaveDynamicSSLCertificate()), "PKCS8PrivateKey.pem").getAbsolutePath());
        return configuration.privateKeyPath();
    }

    /**
     * load leaf private key
     */
    public PrivateKey privateKey() {
        ensureProviderRegistered();
        if (shouldGenerateCertificates()) {
            return privateKey;
        } else {
            return privateKeyFromPEMFile(configuration.privateKeyPath());
        }
    }

    /**
     * leaf certificate path
     */
    private String x509CertificatePath() {
        configuration.x509CertificatePath(new File(new File(configuration.directoryToSaveDynamicSSLCertificate()), "Certificate.pem").getAbsolutePath());
        return configuration.x509CertificatePath();
    }

    /**
     * load leaf certificate
     */
    public X509Certificate x509Certificate() {
        ensureProviderRegistered();
        if (shouldGenerateCertificates()) {
            return x509Certificate;
        } else {
            List<X509Certificate> chain = x509ChainFromPEMFile(configuration.x509CertificatePath());
            if (chain.isEmpty()) {
                throw new RuntimeException("The file '" + configuration.x509CertificatePath() + "' does not contain any valid PEM-encoded certificates");
            }
            return chain.get(0);
        }
    }

    /**
     * generate signed leaf certificate
     */
    private X509Certificate generateLeafCert(AsymmetricKeyPairAlgorithm keyGenerationAndSigningAlgorithm, PublicKey publicKey, X509Certificate certificateAuthorityCert, PrivateKey certificateAuthorityPrivateKey, PublicKey certificateAuthorityPublicKey, String domain, Set<String> subjectAlternativeNameDomains, Set<String> subjectAlternativeNameIps) throws Exception {

        // signers name
        X500Name issuer = new X509CertificateHolder(certificateAuthorityCert.getEncoded()).getSubject();

        // subjects name - the same as we are self signed.
        X500Name subject = new X500Name("CN=" + domain + ", O=" + ORGANISATION + ", L=" + LOCALITY + ", ST=" + STATE + ", C=" + COUNTRY);

        // serial (RFC 5280 recommends unpredictable serials)
        BigInteger serial = new BigInteger(64, new SecureRandom());

        // create the certificate - version 3
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, serial, KeyAndCertificateFactory.notBefore(), KeyAndCertificateFactory.notAfter(), subject, publicKey);
        builder.addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyIdentifier(publicKey));
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));

        // subject alternative name
        List<ASN1Encodable> subjectAlternativeNames = new ArrayList<>();
        if (subjectAlternativeNameDomains != null) {
            subjectAlternativeNames.add(new GeneralName(GeneralName.dNSName, domain));
            for (String subjectAlternativeNameDomain : subjectAlternativeNameDomains) {
                subjectAlternativeNames.add(new GeneralName(GeneralName.dNSName, subjectAlternativeNameDomain));
            }
        }
        if (subjectAlternativeNameIps != null) {
            for (String subjectAlternativeNameIp : subjectAlternativeNameIps) {
                if (IPAddress.isValidIPv6WithNetmask(subjectAlternativeNameIp)
                    || IPAddress.isValidIPv6(subjectAlternativeNameIp)
                    || IPAddress.isValidIPv4WithNetmask(subjectAlternativeNameIp)
                    || IPAddress.isValidIPv4(subjectAlternativeNameIp)) {
                    subjectAlternativeNames.add(new GeneralName(GeneralName.iPAddress, subjectAlternativeNameIp));
                }
            }
        }
        if (subjectAlternativeNames.size() > 0) {
            DERSequence subjectAlternativeNamesExtension = new DERSequence(subjectAlternativeNames.toArray(new ASN1Encodable[0]));
            builder.addExtension(Extension.subjectAlternativeName, false, subjectAlternativeNamesExtension);
        }
        X509Certificate signedX509Certificate = signCertificate(keyGenerationAndSigningAlgorithm, builder, certificateAuthorityPrivateKey);

        // validate
        signedX509Certificate.checkValidity(new Date());
        signedX509Certificate.verify(certificateAuthorityPublicKey);

        return signedX509Certificate;
    }

    /**
     * sign CA or leaf certificate
     */
    private X509Certificate signCertificate(AsymmetricKeyPairAlgorithm keyGenerationAndSigningAlgorithm, X509v3CertificateBuilder certificateBuilder, PrivateKey privateKey) throws OperatorCreationException, CertificateException {
        ContentSigner signer = new JcaContentSignerBuilder(keyGenerationAndSigningAlgorithm.getSigningAlgorithm()).setProvider(PROVIDER_NAME).build(privateKey);
        return new JcaX509CertificateConverter().setProvider(PROVIDER_NAME).getCertificate(certificateBuilder.build(signer));
    }

    private SubjectKeyIdentifier createSubjectKeyIdentifier(Key key) throws IOException {
        try (ASN1InputStream is = new ASN1InputStream(new ByteArrayInputStream(key.getEncoded()))) {
            ASN1Sequence seq = (ASN1Sequence) is.readObject();
            SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(seq);
            return new BcX509ExtensionUtils().createSubjectKeyIdentifier(info);
        }
    }

    public boolean certificateNotYetCreated() {
        return shouldGenerateCertificates() && x509Certificate == null;
    }

    private boolean shouldGenerateCertificates() {
        return isBlank(configuration.privateKeyPath()) || isBlank(configuration.x509CertificatePath());
    }

    private boolean dynamicallyUpdateCertificateAuthority() {
        return configuration.dynamicallyCreateCertificateAuthorityCertificate();
    }

    public boolean certificateAuthorityCertificateNotYetCreated() {
        return !validX509PEMFileExists(certificateAuthorityX509CertificatePath());
    }

    private void saveAsPEMFile(Object object, String absolutePath, String type) throws IOException {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(INFO)
                    .setMessageFormat("created dynamic " + type + " file at{}")
                    .setArguments(absolutePath)
            );
        }
        try (FileWriter pemfileWriter = new FileWriter(FileCreator.createFileIfNotExists(type, new File(absolutePath)))) {
            try (JcaPEMWriter jcaPEMWriter = new JcaPEMWriter(pemfileWriter)) {
                jcaPEMWriter.writeObject(object);
            }
        }
    }

    @Override
    public String writeCertificateAuthorityToDisk() {
        ensureProviderRegistered();
        // certificateAuthorityX509Certificate() lazily generates the dynamic CA when enabled, or loads
        // the baked-in/custom CA otherwise. Only the PUBLIC certificate is written, atomically, into the
        // instance-scoped directory (the shared helper handles PEM encoding + atomic move).
        return KeyAndCertificateFactory.writeCertificateAuthorityPem(
            certificateAuthorityX509Certificate(),
            configuration.directoryToSaveDynamicSSLCertificate()
        );
    }

    @Override
    public List<X509Certificate> certificateChain() {
        ensureProviderRegistered();
        final List<X509Certificate> result = new ArrayList<>();
        if (shouldGenerateCertificates()) {
            result.add(x509Certificate());
        } else {
            List<X509Certificate> chain = x509ChainFromPEMFile(configuration.x509CertificatePath());
            if (chain.isEmpty()) {
                throw new RuntimeException("The file '" + configuration.x509CertificatePath() + "' does not contain any valid PEM-encoded certificates");
            }
            result.addAll(chain);
        }
        // append the CA only when the supplied chain does not already end with it
        // (a full leaf+CA PEM would otherwise yield [leaf, CA, CA], which JDK 17's
        // PKCS12 setKeyEntry rejects as an invalid chain)
        X509Certificate ca = certificateAuthorityX509Certificate();
        if (ca != null && !result.contains(ca)) {
            result.add(ca);
        }
        return result;
    }
}
