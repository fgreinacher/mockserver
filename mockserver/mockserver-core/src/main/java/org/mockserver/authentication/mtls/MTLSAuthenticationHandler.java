package org.mockserver.authentication.mtls;

import com.google.common.collect.ImmutableMap;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.event.Level;

import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

public class MTLSAuthenticationHandler implements AuthenticationHandler {

    // Extended Key Usage OIDs (RFC 5280 §4.2.1.12).
    private static final String ID_KP_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"; // TLS Web Client Authentication
    private static final String ANY_EXTENDED_KEY_USAGE = "2.5.29.37.0";  // anyExtendedKeyUsage

    private final MockServerLogger mockServerLogger;
    private final X509Certificate[] controlPlaneTLSMutualAuthenticationCAChain;

    public MTLSAuthenticationHandler(MockServerLogger mockServerLogger, X509Certificate[] controlPlaneTLSMutualAuthenticationCAChain) {
        this.mockServerLogger = mockServerLogger;
        this.controlPlaneTLSMutualAuthenticationCAChain = controlPlaneTLSMutualAuthenticationCAChain;
    }

    @Override
    public boolean controlPlaneRequestAuthenticated(HttpRequest request) {
        if (controlPlaneTLSMutualAuthenticationCAChain != null && controlPlaneTLSMutualAuthenticationCAChain.length != 0) {
            if (request.getClientCertificateChain() != null) {
                for (org.mockserver.model.X509Certificate clientCertificate : request.getClientCertificateChain()) {
                    for (X509Certificate caCertificate : controlPlaneTLSMutualAuthenticationCAChain) {
                        String clientCertificateInformation = getClientCertificateInformation(
                            clientCertificate.getSerialNumber(),
                            clientCertificate.getIssuerDistinguishedName(),
                            clientCertificate.getSubjectDistinguishedName()
                        );
                        String caCertificateInformation = getClientCertificateInformation(
                            caCertificate.getSerialNumber().toString(),
                            caCertificate.getIssuerX500Principal().getName(),
                            caCertificate.getSubjectX500Principal().getName()
                        );
                        try {
                            Certificate presentedCertificate = clientCertificate.getCertificate();
                            if (!(presentedCertificate instanceof X509Certificate)) {
                                // Only X.509 certificates can be validated against an X.509 CA chain.
                                throw new CertPathValidatorException("presented certificate is not an X.509 certificate");
                            }
                            X509Certificate presentedX509Certificate = (X509Certificate) presentedCertificate;
                            // Build and validate a proper PKIX CertPath for the presented certificate against
                            // this CA as the sole trust anchor. This performs, in one JDK-audited step:
                            //  - signature verification (the cert chains to the CA's public key),
                            //  - validity-window enforcement (an expired / not-yet-valid but correctly-signed
                            //    cert is rejected — CertPathValidator checks notBefore/notAfter),
                            //  - basic X.509 path processing.
                            // Revocation (CRL/OCSP) is disabled by default, consistent with the rest of the
                            // codebase, so validation never makes a network call.
                            validateCertificatePath(presentedX509Certificate, caCertificate);
                            // Extended Key Usage: when the client certificate carries an EKU extension it MUST
                            // permit clientAuth (id-kp-clientAuth) — a certificate scoped to serverAuth-only (or
                            // any other purpose) must not authenticate a client. A certificate with no EKU
                            // extension is unrestricted and is allowed (RFC 5280 practice).
                            if (!hasClientAuthExtendedKeyUsage(presentedX509Certificate)) {
                                throw new CertPathValidatorException("client certificate extended key usage does not permit clientAuth (id-kp-clientAuth 1.3.6.1.5.5.7.3.2)");
                            }
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.DEBUG)
                                    .setHttpRequest(request)
                                    .setMessageFormat("validated client certificate:{}against control plane trust store certificate:{}")
                                    .setArguments(clientCertificateInformation, caCertificateInformation)
                            );
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.DEBUG)
                                    .setHttpRequest(request)
                                    .setMessageFormat("control plane request passed authentication:{}")
                                    .setArguments(request)
                            );
                            return true;
                        } catch (Throwable throwable) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.TRACE)
                                    .setHttpRequest(request)
                                    .setMessageFormat("exception validating client certificate:{}against control plane trust store certificate:{}")
                                    .setArguments(clientCertificateInformation, caCertificateInformation)
                                    .setThrowable(throwable)
                            );
                        }
                    }
                }
                throw new AuthenticationException("control plane request failed authentication no client certificates can be validated by control plane CA");
            } else {
                throw new AuthenticationException("control plane request failed authentication no client certificates found");
            }
        }
        throw new AuthenticationException("control plane request failed authentication no control plane CA specified");
    }

    /**
     * Builds a single-certificate PKIX {@link CertPath} for the presented client certificate and
     * validates it against the supplied CA as the sole trust anchor. Revocation checking is disabled
     * (no CRL/OCSP fetch), consistent with the rest of the codebase, so validation is fully offline.
     * Throws a {@link java.security.GeneralSecurityException} subtype on any failure (unknown issuer,
     * bad signature, expired/not-yet-valid), which the caller treats as an authentication failure.
     */
    private static void validateCertificatePath(X509Certificate clientCertificate, X509Certificate caCertificate) throws java.security.GeneralSecurityException {
        CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(Collections.singletonList(clientCertificate));
        PKIXParameters pkixParameters = new PKIXParameters(Collections.singleton(new TrustAnchor(caCertificate, null)));
        pkixParameters.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(certPath, pkixParameters);
    }

    /**
     * Returns {@code true} when the certificate either carries no Extended Key Usage extension (an
     * unrestricted certificate, allowed per RFC 5280 practice) or its EKU permits clientAuth
     * ({@code id-kp-clientAuth}) or anyExtendedKeyUsage. Returns {@code false} only when an EKU
     * extension is present but does not include clientAuth.
     */
    static boolean hasClientAuthExtendedKeyUsage(X509Certificate certificate) throws CertificateParsingException {
        List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
        if (extendedKeyUsage == null) {
            return true;
        }
        return extendedKeyUsage.contains(ID_KP_CLIENT_AUTH) || extendedKeyUsage.contains(ANY_EXTENDED_KEY_USAGE);
    }

    private String getClientCertificateInformation(String serialNumber, String issuerDistinguishedName, String subjectDistinguishedName) {
        try {
            return ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(ImmutableMap.of(
                "serialNumber", serialNumber,
                "issuerDistinguishedName", issuerDistinguishedName,
                "subjectDistinguishedName", subjectDistinguishedName
            ));
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.TRACE)
                    .setMessageFormat("exception serialising certificate information")
                    .setThrowable(throwable)
            );
            return "";
        }
    }

}
