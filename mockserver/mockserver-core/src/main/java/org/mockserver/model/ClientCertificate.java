package org.mockserver.model;

import java.util.Objects;

import static org.mockserver.model.NottableString.string;

/**
 * Expectation criteria for matching the mutual-TLS client-certificate chain a request was received
 * with.
 * <p>
 * The criteria are matched against the <strong>leaf certificate</strong> (index {@code 0}) of the
 * request's {@link HttpRequest#getClientCertificateChain() clientCertificateChain} — the client's
 * own certificate, per RFC 5246 / RFC 8446, where the sender's certificate is always first in the
 * chain. Intermediate and root CA certificates further down the chain are not matched here (the
 * {@code issuer} criterion is the intended way to constrain the signing CA).
 * <p>
 * Each criterion is a {@link NottableString} so it supports the usual regex, {@code !} negation and
 * optional semantics:
 * <ul>
 *   <li>{@code subject} — matches the leaf certificate's Common Name (CN), full subject
 *   Distinguished Name, or any Subject Alternative Name (DNS / IP / email / URI). A match against
 *   <em>any</em> of these is a match.</li>
 *   <li>{@code issuer} — matches the leaf certificate's issuer Common Name (CN) or full issuer
 *   Distinguished Name.</li>
 *   <li>{@code fingerprintSha256} — matches the SHA-256 fingerprint of the leaf certificate's DER
 *   encoding. Both the matcher value and the computed fingerprint are normalised (colons and
 *   whitespace removed, compared case-insensitively) so {@code AB:CD:...} and {@code abcd...} are
 *   equivalent.</li>
 * </ul>
 * A request that presents no client-certificate chain never matches a non-blank
 * {@code ClientCertificate} criterion.
 *
 * @author jamesdbloom
 */
@SuppressWarnings("UnusedReturnValue")
public class ClientCertificate extends ObjectWithJsonToString {

    private NottableString subject;
    private NottableString issuer;
    private NottableString fingerprintSha256;
    private int hashCode;

    public static ClientCertificate clientCertificate() {
        return new ClientCertificate();
    }

    public NottableString getSubject() {
        return subject;
    }

    public ClientCertificate withSubject(NottableString subject) {
        this.subject = subject;
        this.hashCode = 0;
        return this;
    }

    public ClientCertificate withSubject(String subject) {
        return withSubject(string(subject));
    }

    public NottableString getIssuer() {
        return issuer;
    }

    public ClientCertificate withIssuer(NottableString issuer) {
        this.issuer = issuer;
        this.hashCode = 0;
        return this;
    }

    public ClientCertificate withIssuer(String issuer) {
        return withIssuer(string(issuer));
    }

    public NottableString getFingerprintSha256() {
        return fingerprintSha256;
    }

    public ClientCertificate withFingerprintSha256(NottableString fingerprintSha256) {
        this.fingerprintSha256 = fingerprintSha256;
        this.hashCode = 0;
        return this;
    }

    public ClientCertificate withFingerprintSha256(String fingerprintSha256) {
        return withFingerprintSha256(string(fingerprintSha256));
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    public ClientCertificate clone() {
        return clientCertificate()
            .withSubject(subject)
            .withIssuer(issuer)
            .withFingerprintSha256(fingerprintSha256);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        ClientCertificate that = (ClientCertificate) o;
        return Objects.equals(subject, that.subject) &&
            Objects.equals(issuer, that.issuer) &&
            Objects.equals(fingerprintSha256, that.fingerprintSha256);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(subject, issuer, fingerprintSha256);
        }
        return hashCode;
    }
}
