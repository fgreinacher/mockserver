package org.mockserver.socket.tls;

import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class CertificateConfigurationValidator {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;

    public CertificateConfigurationValidator(Configuration configuration, MockServerLogger mockServerLogger) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
    }

    public void validate() {
        String privateKeyPath = configuration.privateKeyPath();
        String x509CertificatePath = configuration.x509CertificatePath();

        boolean hasPrivateKey = isNotBlank(privateKeyPath);
        boolean hasCertificate = isNotBlank(x509CertificatePath);

        if (!hasPrivateKey && !hasCertificate) {
            return;
        }

        if (hasPrivateKey && !hasCertificate) {
            throw new RuntimeException(
                "Both 'privateKeyPath' and 'x509CertificatePath' must be configured together."
                    + " You set 'privateKeyPath' to '" + privateKeyPath + "'"
                    + " but 'x509CertificatePath' is not set."
            );
        }
        if (!hasPrivateKey) {
            throw new RuntimeException(
                "Both 'privateKeyPath' and 'x509CertificatePath' must be configured together."
                    + " You set 'x509CertificatePath' to '" + x509CertificatePath + "'"
                    + " but 'privateKeyPath' is not set."
            );
        }

        PrivateKey privateKey;
        try {
            privateKey = PEMToFile.privateKeyFromPEMFile(privateKeyPath);
        } catch (Exception e) {
            throw new RuntimeException(
                "The file '" + privateKeyPath + "' is not a valid PEM-encoded private key."
                    + " Ensure the file contains a '-----BEGIN PRIVATE KEY-----' or '-----BEGIN RSA PRIVATE KEY-----' block.",
                e
            );
        }

        List<X509Certificate> certificateChain;
        try {
            certificateChain = PEMToFile.x509ChainFromPEMFile(x509CertificatePath);
        } catch (Exception e) {
            throw new RuntimeException(
                "The file '" + x509CertificatePath + "' is not a valid PEM-encoded certificate."
                    + " Ensure the file contains a '-----BEGIN CERTIFICATE-----' block.",
                e
            );
        }
        if (certificateChain.isEmpty()) {
            throw new RuntimeException(
                "The file '" + x509CertificatePath + "' does not contain any valid PEM-encoded certificates."
            );
        }

        X509Certificate leafCert = certificateChain.get(0);

        try {
            leafCert.checkValidity(new Date());
        } catch (CertificateExpiredException e) {
            throw new RuntimeException(
                "The certificate at '" + x509CertificatePath + "' expired on " + leafCert.getNotAfter().toInstant() + "."
                    + " Replace it with a valid certificate.",
                e
            );
        } catch (CertificateNotYetValidException e) {
            throw new RuntimeException(
                "The certificate at '" + x509CertificatePath + "' is not yet valid until " + leafCert.getNotBefore().toInstant() + "."
                    + " Replace it with a valid certificate.",
                e
            );
        }

        validateKeyMatchesCertificate(privateKey, leafCert, privateKeyPath, x509CertificatePath);

        String caCertPath = configuration.certificateAuthorityCertificate();
        if (isNotBlank(caCertPath)) {
            validateCaCertificate(caCertPath);
            validateLeafSignedByCa(leafCert, caCertPath, x509CertificatePath);
        }

        String caKeyPath = configuration.certificateAuthorityPrivateKey();
        if (isNotBlank(caKeyPath) && !isDefaultCaKeyPath(caKeyPath)) {
            validateCaPrivateKey(caKeyPath);
        }

        checkExtendedKeyUsage(leafCert, x509CertificatePath);
    }

    private void validateKeyMatchesCertificate(PrivateKey privateKey, X509Certificate certificate, String privateKeyPath, String x509CertificatePath) {
        // Derive the challenge signature algorithm from the private KEY, not from
        // certificate.getSigAlgName(): the latter is the algorithm the issuing CA used to sign the
        // certificate and says nothing about the subject key's own type. An RSA certificate issued
        // by an EC CA reports SHA256withECDSA, which cannot be used with the RSA private key.
        String keyAlgorithm = privateKey.getAlgorithm();
        String sigAlgorithm = signatureAlgorithmForKey(keyAlgorithm);
        if (sigAlgorithm == null) {
            throw new RuntimeException(
                "The private key at '" + privateKeyPath + "' uses the unsupported key algorithm '" + keyAlgorithm + "'."
                    + " MockServer can validate RSA, EC, DSA and Ed25519/Ed448 private keys."
            );
        }

        byte[] challenge = "mockserver-validation-challenge".getBytes(StandardCharsets.UTF_8);
        byte[] signed;
        try {
            Signature signature = Signature.getInstance(sigAlgorithm);
            signature.initSign(privateKey);
            signature.update(challenge);
            signed = signature.sign();
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new RuntimeException(
                "The private key at '" + privateKeyPath + "' (algorithm '" + keyAlgorithm + "') could not be used with the signature algorithm '" + sigAlgorithm + "'"
                    + " (the certificate at '" + x509CertificatePath + "' reports signature algorithm '" + certificate.getSigAlgName() + "')."
                    + " This is not a mismatch between the key and the certificate - usually the running JVM's security providers cannot apply that algorithm, though a malformed key file would also land here.",
                e
            );
        }

        try {
            Signature verifier = Signature.getInstance(sigAlgorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(challenge);
            if (!verifier.verify(signed)) {
                throw keyDoesNotMatchCertificate(privateKeyPath, x509CertificatePath, null);
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            // A failure verifying against the certificate's public key means the key and certificate
            // do not correspond (typically different key types, e.g. an RSA key with an EC certificate).
            throw keyDoesNotMatchCertificate(privateKeyPath, x509CertificatePath, e);
        }
    }

    private static RuntimeException keyDoesNotMatchCertificate(String privateKeyPath, String x509CertificatePath, Exception cause) {
        String message = "The private key at '" + privateKeyPath + "' does not match the certificate at '" + x509CertificatePath + "'."
            + " The public key fingerprints differ."
            + " Regenerate the key pair or check that the files correspond to each other.";
        return cause == null ? new RuntimeException(message) : new RuntimeException(message, cause);
    }

    private static String signatureAlgorithmForKey(String keyAlgorithm) {
        if (keyAlgorithm == null) {
            return null;
        }
        switch (keyAlgorithm.toUpperCase(java.util.Locale.ROOT)) {
            case "RSA":
                return "SHA256withRSA";
            case "EC":
            case "ECDSA":
                return "SHA256withECDSA";
            case "DSA":
                return "SHA256withDSA";
            case "ED25519":
                return "Ed25519";
            case "ED448":
                return "Ed448";
            case "EDDSA":
                return "EdDSA";
            default:
                return null;
        }
    }

    private void validateCaCertificate(String caCertPath) {
        if (isDefaultCaCertPath(caCertPath)) {
            return;
        }
        try {
            PEMToFile.x509FromPEMFile(caCertPath);
        } catch (Exception e) {
            throw new RuntimeException(
                "The CA certificate file '" + caCertPath + "' is not a valid PEM-encoded X.509 certificate."
                    + " Check that the file path is correct and that it is not swapped with the private key.",
                e
            );
        }
    }

    private void validateCaPrivateKey(String caKeyPath) {
        try {
            PEMToFile.privateKeyFromPEMFile(caKeyPath);
        } catch (Exception e) {
            throw new RuntimeException(
                "The CA private key file '" + caKeyPath + "' is not a valid PEM-encoded private key."
                    + " Ensure the file contains a '-----BEGIN PRIVATE KEY-----' or '-----BEGIN RSA PRIVATE KEY-----' block.",
                e
            );
        }
    }

    private void validateLeafSignedByCa(X509Certificate leafCert, String caCertPath, String x509CertificatePath) {
        try {
            X509Certificate caCert = PEMToFile.x509FromPEMFile(caCertPath);
            leafCert.verify(caCert.getPublicKey());
        } catch (Exception e) {
            throw new RuntimeException(
                "The certificate at '" + x509CertificatePath + "' was not signed by the CA certificate at '" + caCertPath + "'."
                    + " Verify the certificate chain or update 'certificateAuthorityCertificate'.",
                e
            );
        }
    }

    private void checkExtendedKeyUsage(X509Certificate leafCert, String x509CertificatePath) {
        try {
            List<String> extendedKeyUsage = leafCert.getExtendedKeyUsage();
            if (extendedKeyUsage != null && !extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1")) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("The certificate at '" + x509CertificatePath + "' does not include the 'serverAuth' Extended Key Usage extension."
                                + " Some strict TLS clients may reject it.")
                    );
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isDefaultCaCertPath(String path) {
        return "org/mockserver/socket/CertificateAuthorityCertificate.pem".equals(path);
    }

    private boolean isDefaultCaKeyPath(String path) {
        return "org/mockserver/socket/PKCS8CertificateAuthorityPrivateKey.pem".equals(path);
    }
}
