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
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.function.LongSupplier;

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

    // Published from Netty event-loop threads (SNI provisioning) and read from others, so every mutable
    // field is volatile and each is published only once fully built (see buildAndSave* below) to remove
    // the null-then-assign / torn-state races (defects C6, C7).
    private volatile PrivateKey privateKey;
    private volatile X509Certificate x509Certificate;
    private volatile List<X509Certificate> x509CertificateChain;
    private volatile PrivateKey certificateAuthorityPrivateKey;
    private volatile X509Certificate certificateAuthorityX509Certificate;
    // The certificate-authority configuration (paths / dynamic flag / directory) that the memoised CA
    // above was loaded from. When it changes at runtime (CA rotation) the memoised CA is discarded and
    // reloaded rather than being pinned for the JVM lifetime (defect C9).
    private volatile String loadedCertificateAuthoritySignature;

    /**
     * Time source consulted by {@link #certificateNeedsRenewal()} only. Package-private and overridable
     * so tests can advance past a certificate's renewal threshold without waiting years of wall-clock
     * time. Never used for issuance (issuance always anchors to real time via
     * {@link KeyAndCertificateFactory#notBefore()}/{@link KeyAndCertificateFactory#notAfter()}).
     */
    static volatile LongSupplier renewalClock = System::currentTimeMillis;

    // Latches once the dynamically-generated CA has crossed its renewal threshold so the near-expiry WARN
    // (emitted from certificateNeedsRenewal()) is logged only once rather than on every handshake.
    private volatile boolean certificateAuthorityRenewalWarned;

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
        if (!dynamicallyUpdateCertificateAuthority()) {
            return;
        }
        // Serialise check-and-generate across processes sharing directoryToSaveDynamicSSLCertificate, so
        // two MockServer instances cannot both mint a fresh CA and silently clobber each other's — which
        // would invalidate every client trust store pinned to the first CA (defect C11).
        String certificatePath = certificateAuthorityX509CertificatePath();
        String keyPath = certificateAuthorityPrivateKeyPath();
        try (AutoCloseable ignored = acquireCertificateAuthorityLock()) {
            if (!certificateAuthorityCertificateNotYetCreated()) {
                return;
            }
            AsymmetricKeyPairAlgorithm keyGenerationAndSigningAlgorithm = KeyAndCertificateFactory.DEFAULT_KEY_GENERATION_AND_SIGNING_ALGORITHM;
            KeyPair caKeyPair = AsymmetricKeyGenerator.createKeyPair(keyGenerationAndSigningAlgorithm);
            X509Certificate caCertificate = generateCACert(keyGenerationAndSigningAlgorithm, caKeyPair.getPublic(), caKeyPair.getPrivate());
            // Write the PRIVATE KEY first and the public certificate last, each atomically, so a concurrent
            // reader that sees the certificate is guaranteed to also see the matching key — the reverse
            // order left a window serving new-cert / old-key (defect C11).
            saveAsPEMFile(caKeyPair.getPrivate(), keyPath, "Certificate Authority Private Key PEM", true);
            saveAsPEMFile(caCertificate, certificatePath, "Certificate Authority X509 Certificate PEM", false);
        } catch (RuntimeException e) {
            // preserve the clear corrupt-CA / directory failure message unwrapped (defects C11 / C13)
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("exception while generating certificate authority private key and X509 certificate", e);
        }
    }

    /**
     * Acquire an exclusive OS file lock in {@code directoryToSaveDynamicSSLCertificate} for the duration
     * of a CA check-and-generate, so two processes sharing that directory serialise. Returns a real
     * {@link FileLock} (auto-closed by the caller's try-with-resources) or a no-op {@link FileLock} when
     * a lock cannot be taken (e.g. the directory is read-only) so generation can still proceed.
     */
    private AutoCloseable acquireCertificateAuthorityLock() {
        try {
            File lockFile = new File(new File(configuration.directoryToSaveDynamicSSLCertificate()), ".mockserver-ca.lock");
            org.mockserver.file.FileCreator.createParentDirs(lockFile);
            FileChannel channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            return () -> {
                try {
                    lock.release();
                } finally {
                    channel.close();
                }
            };
        } catch (Throwable throwable) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(DEBUG)
                        .setMessageFormat("unable to acquire certificate authority generation lock, proceeding without cross-process serialisation")
                        .setThrowable(throwable)
                );
            }
            return () -> {
            };
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
    /**
     * Signature of the certificate-authority configuration inputs. When any change at runtime (CA
     * rotation via the setters that now raise a rebuild — defect C9) the memoised CA is discarded and
     * reloaded rather than being pinned for the JVM lifetime.
     */
    private String certificateAuthoritySignature() {
        return configuration.dynamicallyCreateCertificateAuthorityCertificate()
            + "|" + configuration.directoryToSaveDynamicSSLCertificate()
            + "|" + configuration.certificateAuthorityCertificate()
            + "|" + configuration.certificateAuthorityPrivateKey();
    }

    private void invalidateCertificateAuthorityIfConfigChanged() {
        String signature = certificateAuthoritySignature();
        if (!signature.equals(loadedCertificateAuthoritySignature)) {
            certificateAuthorityX509Certificate = null;
            certificateAuthorityPrivateKey = null;
            loadedCertificateAuthoritySignature = signature;
        }
    }

    private PrivateKey certificateAuthorityPrivateKey() {
        invalidateCertificateAuthorityIfConfigChanged();
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
        invalidateCertificateAuthorityIfConfigChanged();
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

        // serial (RFC 5280 §4.1.2.2 requires a POSITIVE serial; a plain new BigInteger(64, random) can be zero)
        BigInteger serial = KeyAndCertificateFactory.positiveSerialNumber();

        // create the certificate - version 3 (with subjects name same as issues as self signed)
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuerName, serial, KeyAndCertificateFactory.notBefore(), KeyAndCertificateFactory.notAfter(), issuerName, publicKey);
        builder.addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyIdentifier(publicKey));
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        KeyUsage usage = new KeyUsage(KeyUsage.keyCertSign | KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment | KeyUsage.cRLSign);
        builder.addExtension(Extension.keyUsage, false, usage);

        // NB: no extendedKeyUsage on the root CA. EKU on a trust anchor is non-idiomatic (a root should be
        // able to issue any purpose), and the old list carried both two specific EKUs AND
        // anyExtendedKeyUsage, which is self-contradictory. Removing it is safe for existing users: a
        // dynamically-generated CA already on disk is never regenerated, so only brand-new CAs are
        // affected, and the leaf now carries serverAuth/clientAuth EKU itself (see generateLeafCert).

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
                // Build the new key AND certificate into locals first and publish them together only on
                // success (defect C6). Assigning the field-by-field as we went left a new key paired with
                // the OLD certificate if signing threw mid-flight — every subsequent handshake then failed
                // permanently. On failure we keep the previous working pair and propagate so the caller
                // fails loudly / retries, rather than swallowing the error and serving a torn pair.
                PrivateKey newPrivateKey = keyPair.getPrivate();
                X509Certificate newX509Certificate = generateLeafCert(
                    keyGenerationAndSigningAlgorithm,
                    keyPair.getPublic(),
                    certificateAuthorityX509Certificate(),
                    certificateAuthorityPrivateKey(),
                    certificateAuthorityX509Certificate().getPublicKey(),
                    configuration.sslCertificateDomainName(),
                    configuration.sslSubjectAlternativeNameDomains(),
                    configuration.sslSubjectAlternativeNameIps()
                );
                if (configuration.preventCertificateDynamicUpdate() || configuration.proactivelyInitialiseTLS()) {
                    // persist BEFORE publishing in-memory so a save failure does not leave the served
                    // in-memory pair diverged from the on-disk pair
                    saveAsPEMFile(newX509Certificate, x509CertificatePath(), "X509 Certificate PEM", false);
                    saveAsPEMFile(newPrivateKey, privateKeyPath(), "Private Key PEM", true);
                }
                // atomic publish: key first then cert, both already fully built
                privateKey = newPrivateKey;
                x509Certificate = newX509Certificate;
                x509CertificateChain = null;
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(TRACE)
                            .setMessageFormat("created new X509{}with SAN Domain Names{}and IPs{}")
                            .setArguments(newX509Certificate, configuration.sslSubjectAlternativeNameDomains(), configuration.sslSubjectAlternativeNameIps())
                    );
                }
            } catch (Exception e) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while generating private key and X509 certificate")
                        .setThrowable(e)
                );
                throw new RuntimeException("exception while generating private key and X509 certificate", e);
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

        // serial (RFC 5280 §4.1.2.2 requires a POSITIVE serial; a plain new BigInteger(64, random) can be zero)
        BigInteger serial = KeyAndCertificateFactory.positiveSerialNumber();

        // leaf validity: default 397 days (inside Apple's 825-day server-cert cap), overridable to restore
        // the historical long-lived leaf. The window is anchored to notBefore (back-dated 5 days) so
        // notAfter - notBefore == days. Three input bands are corrected rather than trusted verbatim:
        //   - non-positive: unset/invalid -> fall back to the default (documented behaviour, not a clamp);
        //   - positive but below LEAF_CERTIFICATE_VALIDITY_DAYS_MIN: a broken value that would mint an
        //     already-expired leaf (1..5) or one born past its renewal threshold (~6, a per-handshake
        //     regeneration loop) -> clamp UP to the floor with a WARN;
        //   - above LEAF_CERTIFICATE_VALIDITY_DAYS_MAX: could push notAfter past the X.509/Apple ceiling
        //     -> clamp DOWN to the cap with a WARN.
        long configuredLeafValidityDays = configuration.sslCertificateLeafValidityInDays();
        long leafValidityDays = configuredLeafValidityDays;
        if (leafValidityDays < 1) {
            leafValidityDays = KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_DEFAULT;
        } else if (leafValidityDays < KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_MIN) {
            leafValidityDays = KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_MIN;
        } else if (leafValidityDays > KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_MAX) {
            leafValidityDays = KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_MAX;
        }
        if (leafValidityDays != configuredLeafValidityDays && configuredLeafValidityDays >= 1
            && mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(WARN)
                    .setMessageFormat("configured mockserver.sslCertificateLeafValidityInDays of {} is outside the honoured range [{}, {}] and has been clamped to {} days to avoid minting an unusable leaf certificate")
                    .setArguments(configuredLeafValidityDays, KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_MIN, KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_MAX, leafValidityDays)
            );
        }
        Date notBefore = KeyAndCertificateFactory.notBefore();
        Date notAfter = KeyAndCertificateFactory.notAfter(notBefore, leafValidityDays);

        // create the certificate - version 3
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, serial, notBefore, notAfter, subject, publicKey);
        builder.addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyIdentifier(publicKey));
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));

        // authorityKeyIdentifier derived from the CA so chain builders that match a leaf's AKI to the
        // issuer's subjectKeyIdentifier (RFC 5280 §4.2.1.1) can find the CA. Computed from the CA public
        // key with the same method used for the CA's own SKI, so the two identifiers are byte-identical.
        builder.addExtension(Extension.authorityKeyIdentifier, false, createAuthorityKeyIdentifier(certificateAuthorityPublicKey));

        // extendedKeyUsage: serverAuth is REQUIRED by Apple (iOS/macOS) on the leaf independently of
        // validity — without it the handshake fails even with a compliant validity period (issue #2531).
        // clientAuth is included so the same leaf can also satisfy mTLS client-auth uses.
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(new KeyPurposeId[]{
            KeyPurposeId.id_kp_serverAuth,
            KeyPurposeId.id_kp_clientAuth
        }));

        // keyUsage for a TLS server leaf: digitalSignature (ECDHE/(EC)DSA) + keyEncipherment (RSA key
        // transport), marked critical as required for keyUsage.
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

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

    /**
     * Build an authorityKeyIdentifier from the CA public key. BouncyCastle derives it with the same
     * (SHA-1 of the public key) method it uses for {@link #createSubjectKeyIdentifier(Key)}, so the
     * resulting keyIdentifier is byte-identical to the CA certificate's subjectKeyIdentifier — which is
     * exactly what an AKI→SKI chain builder matches on.
     */
    private AuthorityKeyIdentifier createAuthorityKeyIdentifier(Key certificateAuthorityPublicKey) throws IOException {
        try (ASN1InputStream is = new ASN1InputStream(new ByteArrayInputStream(certificateAuthorityPublicKey.getEncoded()))) {
            ASN1Sequence seq = (ASN1Sequence) is.readObject();
            SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(seq);
            return new BcX509ExtensionUtils().createAuthorityKeyIdentifier(info);
        }
    }

    public boolean certificateNotYetCreated() {
        return shouldGenerateCertificates() && x509Certificate == null;
    }

    /**
     * @return true when the self-generated LEAF has passed its renewal threshold, so the cached TLS
     * context must regenerate before reuse (defect C1). Always false for user-supplied fixed certificates
     * — {@link CertificateConfigurationValidator} rejects those loudly on expiry instead.
     * <p>
     * The renewal trigger deliberately considers ONLY the leaf, never the CA. The renewal action
     * ({@link #buildAndSavePrivateKeyAndX509Certificate()}) regenerates only the leaf — its CA guard
     * short-circuits once a CA is on disk — so treating a past-threshold CA as "needs renewal" would
     * re-mint the leaf on every handshake without ever clearing the trigger, a permanent self-inflicted
     * performance collapse. Silently rotating a dynamic CA is also worse than not rotating (it invalidates
     * every client trust store that imported it), so instead we warn once (see
     * {@link #warnOnceIfCertificateAuthorityNearExpiry(long)}) and leave rotation to the operator.
     */
    @Override
    public boolean certificateNeedsRenewal() {
        if (!shouldGenerateCertificates()) {
            return false;
        }
        long now = renewalClock.getAsLong();
        warnOnceIfCertificateAuthorityNearExpiry(now);
        return KeyAndCertificateFactory.isPastRenewalThreshold(x509Certificate, KeyAndCertificateFactory.RENEWAL_ELAPSED_FRACTION, now);
    }

    /**
     * Emit a single WARN when the dynamically-generated CA has crossed its renewal threshold, so an
     * operator knows to rotate it deliberately. Never triggers automatic regeneration — see
     * {@link #certificateNeedsRenewal()} for why the CA must not drive the leaf-renewal trigger.
     */
    private void warnOnceIfCertificateAuthorityNearExpiry(long now) {
        if (!certificateAuthorityRenewalWarned
            && KeyAndCertificateFactory.isPastRenewalThreshold(certificateAuthorityX509Certificate, KeyAndCertificateFactory.RENEWAL_ELAPSED_FRACTION, now)) {
            certificateAuthorityRenewalWarned = true;
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat("dynamically-generated certificate authority X509 certificate has passed its renewal threshold (80% of validity elapsed) and is nearing expiry{}"
                            + "MockServer will NOT rotate it automatically because that would invalidate every client trust store that imported it; rotate it deliberately (delete the CA files in directoryToSaveDynamicSSLCertificate, or point certificateAuthorityCertificate/certificateAuthorityPrivateKey at a fresh CA) during a maintenance window")
                        .setArguments((Object) System.lineSeparator())
                );
            }
        }
    }

    private boolean shouldGenerateCertificates() {
        return isBlank(configuration.privateKeyPath()) || isBlank(configuration.x509CertificatePath());
    }

    private boolean dynamicallyUpdateCertificateAuthority() {
        return configuration.dynamicallyCreateCertificateAuthorityCertificate();
    }

    public boolean certificateAuthorityCertificateNotYetCreated() {
        String path = certificateAuthorityX509CertificatePath();
        File file = new File(path);
        if (!file.exists()) {
            // genuinely absent — safe to generate a fresh CA
            return true;
        }
        // present but must parse: a corrupt/truncated CA PEM must fail loudly rather than be silently
        // treated as absent and overwritten with a brand-new CA, which would invalidate every client
        // trust store pinned to the original CA with no error (defect C11).
        if (!validX509PEMFileExists(path)) {
            throw new RuntimeException("certificate authority X509 certificate file '" + file.getAbsolutePath()
                + "' exists but does not contain a valid PEM-encoded X.509 certificate; refusing to overwrite it"
                + " — remove or repair the file, or point certificateAuthorityCertificate/directoryToSaveDynamicSSLCertificate elsewhere");
        }
        return false;
    }

    private void saveAsPEMFile(Object object, String absolutePath, String type, boolean ownerOnly) throws IOException {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(INFO)
                    .setMessageFormat("created dynamic " + type + " file at{}")
                    .setArguments(absolutePath)
            );
        }
        // PEM-encode in memory, then write atomically with restrictive permissions (0600 for private key
        // material via ownerOnly, 0644 for public certificates) so keys never land world-readable and a
        // concurrent reader never observes a truncated file (defects C10 / C15).
        StringWriter stringWriter = new StringWriter();
        try (JcaPEMWriter jcaPEMWriter = new JcaPEMWriter(stringWriter)) {
            jcaPEMWriter.writeObject(object);
        }
        FileCreator.writeToFileAtomically(type, new File(absolutePath), stringWriter.toString(), ownerOnly);
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
