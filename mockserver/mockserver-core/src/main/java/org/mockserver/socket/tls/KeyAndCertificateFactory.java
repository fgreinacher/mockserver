package org.mockserver.socket.tls;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * @author jamesdbloom
 */
public interface KeyAndCertificateFactory {

    /**
     * default key pair generation and signing algorithm
     */
    AsymmetricKeyPairAlgorithm DEFAULT_KEY_GENERATION_AND_SIGNING_ALGORITHM = AsymmetricKeyPairAlgorithm.RSA2048_SHA256;
    /**
     * Number of years the generated CA and leaf certificates remain valid. The
     * generated CA is the trust anchor users pin into their trust stores, so it
     * needs to outlive a typical test/CI lifetime rather than expiring after a
     * single year and silently breaking pinned-CA deployments. Ten years is long
     * enough to avoid surprise expiry while staying well below the X.509 ceiling
     * that older clients (e.g. Apple iOS 8, issue #6) reject.
     */
    long CERTIFICATE_VALIDITY_YEARS = 10;
    /**
     * Fraction of a certificate's validity window that must elapse before it is proactively renewed.
     * Renewing at 80% elapsed keeps a comfortable safety margin for both today's 10-year certificates
     * and the short-lived certificates a later hardening wave will introduce, so a long-running server
     * never keeps serving an expired leaf from its cached SSL context.
     */
    double RENEWAL_ELAPSED_FRACTION = 0.8;

    /**
     * @param certificate the certificate to test (may be null)
     * @param fraction    the proportion of the validity window that must elapse before renewal
     * @param now         the current time in epoch milliseconds
     * @return true when {@code now} is at or past {@code notBefore + fraction * (notAfter - notBefore)};
     * false when {@code certificate} is null (nothing to renew yet)
     */
    static boolean isPastRenewalThreshold(X509Certificate certificate, double fraction, long now) {
        if (certificate == null) {
            return false;
        }
        long notBefore = certificate.getNotBefore().getTime();
        long notAfter = certificate.getNotAfter().getTime();
        long renewAt = notBefore + (long) ((notAfter - notBefore) * fraction);
        return now >= renewAt;
    }

    /**
     * @return true when the in-memory self-generated leaf (or the dynamically generated CA) has passed
     * its renewal threshold and must be regenerated before the cached TLS context is reused. Always
     * false for user-supplied fixed certificates — those are validated (and loudly rejected on expiry)
     * by {@link CertificateConfigurationValidator}. Defaults to false for factories that do not
     * self-renew (so third-party/mock implementations keep working unchanged).
     */
    default boolean certificateNeedsRenewal() {
        return false;
    }

    /**
     * The not-before validity bound for a freshly issued certificate: the current time minus 5 days,
     * just in case the software clock goes back due to time synchronization.
     * <p>
     * Computed per issuance (rather than once at class load) so that certificates generated on the fly
     * — e.g. leaf certificates minted long after the JVM started — are anchored to issuance time rather
     * than to JVM start time.
     */
    static Date notBefore() {
        return new Date(System.currentTimeMillis() - 86400000L * 5);
    }

    /**
     * The not-after validity bound for a freshly issued certificate, {@link #CERTIFICATE_VALIDITY_YEARS}
     * years in the future from issuance time.
     * <p>
     * The maximum possible value in the X.509 specification is 9999-12-31 23:59:59
     * (new Date(253402300799000L)), but Apple iOS 8 fails with a certificate
     * expiration date greater than Mon, 24 Jan 6084 02:07:59 GMT (issue #6).
     * <p>
     * Computed per issuance (rather than once at class load) so that on-the-fly generated certificates
     * are anchored to issuance time rather than to JVM start time.
     */
    static Date notAfter() {
        return new Date(System.currentTimeMillis() + 86400000L * 365 * CERTIFICATE_VALIDITY_YEARS);
    }
    /**
     * CN for CA distinguishing name
     */
    String ROOT_COMMON_NAME = "www.mockserver.com";
    /**
     * default CN for leaf distinguishing name
     */
    String CERTIFICATE_DOMAIN = "localhost";
    /**
     * O for distinguishing name
     */
    String ORGANISATION = "MockServer";
    /**
     * L for distinguishing name
     */
    String LOCALITY = "London";
    /**
     * ST for distinguishing name
     */
    String STATE = "England";
    /**
     * C for distinguishing name
     */
    String COUNTRY = "UK";

    @SuppressWarnings("unused")
    void buildAndSaveCertificateAuthorityPrivateKeyAndX509Certificate();

    void buildAndSavePrivateKeyAndX509Certificate();

    boolean certificateNotYetCreated();

    PrivateKey privateKey();

    X509Certificate x509Certificate();

    X509Certificate certificateAuthorityX509Certificate();

    List<X509Certificate> certificateChain();

    /**
     * Stable filename, under {@code directoryToSaveDynamicSSLCertificate}, that the active Certificate
     * Authority X.509 certificate (public certificate only — never the private key) is written to so it
     * can be pinned into client trust stores when MockServer is used as a TLS-intercepting proxy.
     */
    String PROXY_SETUP_CA_CERTIFICATE_FILE_NAME = "mockserver-ca.pem";

    /**
     * Materialise the active Certificate Authority X.509 certificate (the baked-in public CA, a custom
     * supplied CA, or the dynamically generated CA, whichever is in effect) to
     * {@code <directoryToSaveDynamicSSLCertificate>/}{@value #PROXY_SETUP_CA_CERTIFICATE_FILE_NAME}.
     * Only the public certificate is written, never the private key.
     * <p>
     * This default implementation is self-contained (no BouncyCastle dependency): it PEM-encodes the
     * DER bytes of {@link #certificateAuthorityX509Certificate()} and writes them atomically, using the
     * directory from the global {@link ConfigurationProperties#directoryToSaveDynamicSSLCertificate()}.
     * Implementations that hold their own {@code Configuration} (e.g. the BouncyCastle factory) override
     * this to honour their instance-scoped directory.
     *
     * @return the absolute path of the written CA certificate PEM file
     */
    default String writeCertificateAuthorityToDisk() {
        return writeCertificateAuthorityPem(certificateAuthorityX509Certificate(), ConfigurationProperties.directoryToSaveDynamicSSLCertificate());
    }

    /**
     * PEM-encode the public certificate (never a private key) and write it atomically to
     * {@code <directory>/}{@value #PROXY_SETUP_CA_CERTIFICATE_FILE_NAME} — written to a sibling temp file
     * then moved into place (ATOMIC_MOVE where supported) so a concurrent reader never observes a
     * truncated/empty file.
     *
     * @param caCertificate the public CA certificate to write
     * @param directory     the directory to write the {@value #PROXY_SETUP_CA_CERTIFICATE_FILE_NAME} file into
     * @return the absolute path of the written CA certificate PEM file
     */
    static String writeCertificateAuthorityPem(X509Certificate caCertificate, String directory) {
        File targetFile = new File(new File(directory), PROXY_SETUP_CA_CERTIFICATE_FILE_NAME);
        String absolutePath = targetFile.toPath().toAbsolutePath().normalize().toString();
        try {
            String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(caCertificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
            File parent = targetFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Path parentPath = parent != null ? parent.toPath() : targetFile.toPath().toAbsolutePath().getParent();
            Path tempPath = Files.createTempFile(parentPath, "mockserver-ca", ".pem.tmp");
            Files.write(tempPath, pem.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tempPath, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicMoveNotSupported) {
                Files.move(tempPath, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return absolutePath;
        } catch (Exception exception) {
            throw new RuntimeException("exception while writing certificate authority X509 certificate to " + absolutePath, exception);
        }
    }

}
