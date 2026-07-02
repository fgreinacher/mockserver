package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ClientCertificate;
import org.mockserver.model.NottableString;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.mockserver.model.NottableString.string;

/**
 * Matches an expectation's {@link ClientCertificate} criteria against the client-certificate chain a
 * request was received with (the request's
 * {@link org.mockserver.model.HttpRequest#getClientCertificateChain() clientCertificateChain}).
 * <p>
 * Matching is always performed against the <strong>leaf certificate</strong> (index {@code 0}) of
 * the chain — the client's own certificate. See {@link ClientCertificate} for the full description
 * of the {@code subject}, {@code issuer} and {@code fingerprintSha256} criteria and their
 * {@link NottableString} (regex / negation) semantics.
 * <p>
 * A blank criterion (no {@code ClientCertificate}, or every field blank) matches every request,
 * exactly like the other request-property matchers. A non-blank criterion never matches a request
 * that presents no certificate chain.
 *
 * @author jamesdbloom
 */
public class ClientCertificateMatcher extends ObjectWithReflectiveEqualsHashCodeToString implements Matcher<List<org.mockserver.model.X509Certificate>> {

    private static final String[] excludedFields = {"mockServerLogger", "subjectMatcher", "issuerMatcher", "fingerprintMatcher"};
    private final MockServerLogger mockServerLogger;
    private final ClientCertificate clientCertificate;
    private final RegexStringMatcher subjectMatcher;
    private final RegexStringMatcher issuerMatcher;
    private final RegexStringMatcher fingerprintMatcher;

    ClientCertificateMatcher(MockServerLogger mockServerLogger, ClientCertificate clientCertificate, boolean controlPlaneMatcher) {
        this.mockServerLogger = mockServerLogger;
        this.clientCertificate = clientCertificate;
        this.subjectMatcher = clientCertificate != null && clientCertificate.getSubject() != null
            ? new RegexStringMatcher(mockServerLogger, clientCertificate.getSubject(), controlPlaneMatcher) : null;
        this.issuerMatcher = clientCertificate != null && clientCertificate.getIssuer() != null
            ? new RegexStringMatcher(mockServerLogger, clientCertificate.getIssuer(), controlPlaneMatcher) : null;
        this.fingerprintMatcher = clientCertificate != null && clientCertificate.getFingerprintSha256() != null
            ? new RegexStringMatcher(mockServerLogger, normaliseFingerprint(clientCertificate.getFingerprintSha256()), controlPlaneMatcher) : null;
    }

    @Override
    public boolean isBlank() {
        return (subjectMatcher == null || subjectMatcher.isBlank())
            && (issuerMatcher == null || issuerMatcher.isBlank())
            && (fingerprintMatcher == null || fingerprintMatcher.isBlank());
    }

    @Override
    public boolean matches(final MatchDifference context, List<org.mockserver.model.X509Certificate> chain) {
        if (isBlank()) {
            return true;
        }
        if (chain == null || chain.isEmpty()) {
            if (context != null) {
                context.addDifference(mockServerLogger, "client certificate match failed - request presented no client certificate chain but expectation requires one");
            }
            return false;
        }
        // match against the leaf certificate - the client's own certificate is always first in the chain
        org.mockserver.model.X509Certificate leaf = chain.get(0);

        if (subjectMatcher != null && !subjectMatcher.isBlank()) {
            List<String> candidates = subjectCandidates(leaf);
            if (!matchesAcrossCandidates(subjectMatcher, clientCertificate.getSubject().isNot(), candidates)) {
                if (context != null) {
                    context.addDifference(mockServerLogger, "client certificate subject match failed expected:{}found:{}", clientCertificate.getSubject(), candidates);
                }
                return false;
            }
        }
        if (issuerMatcher != null && !issuerMatcher.isBlank()) {
            List<String> candidates = issuerCandidates(leaf);
            if (!matchesAcrossCandidates(issuerMatcher, clientCertificate.getIssuer().isNot(), candidates)) {
                if (context != null) {
                    context.addDifference(mockServerLogger, "client certificate issuer match failed expected:{}found:{}", clientCertificate.getIssuer(), candidates);
                }
                return false;
            }
        }
        if (fingerprintMatcher != null && !fingerprintMatcher.isBlank()) {
            String fingerprint = sha256Fingerprint(leaf);
            // pass a null context to the sub-matcher so it does not add its own generic
            // "string or regex match failed" entry; the explicit difference below carries the
            // fingerprint-specific expected/found detail
            if (fingerprint == null || !fingerprintMatcher.matches((MatchDifference) null, string(fingerprint))) {
                if (context != null) {
                    context.addDifference(mockServerLogger, "client certificate fingerprint match failed expected:{}found:{}", clientCertificate.getFingerprintSha256(), fingerprint);
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Match a subject/issuer criterion across the several candidate strings a certificate exposes
     * (e.g. CN, full DN, SANs) with correct De Morgan negation semantics:
     * <ul>
     *   <li>a positive criterion {@code X} matches when it matches <em>any</em> candidate (OR) — the
     *   subject "is X" if any of its forms is X;</li>
     *   <li>a negated criterion {@code !X} matches only when it holds for <em>every</em> candidate
     *   (AND) — the subject "is not X" only if none of its forms is X. Without the AND, OR-ing a
     *   negation across candidates would almost always be true (the full DN alone differs from a bare
     *   CN value).</li>
     * </ul>
     * {@link RegexStringMatcher} applies the per-candidate negation itself; this method only chooses
     * the reduction. With no candidates, the vacuous reduction is returned ({@code true} for a
     * negation, {@code false} otherwise).
     * <p>
     * A {@code null} {@link MatchDifference} context is passed to each per-candidate match so the OR
     * scan does not leave a spurious "did not match" entry for every non-winning candidate; the
     * caller records a single, criterion-level difference when the overall reduction fails.
     */
    private boolean matchesAcrossCandidates(RegexStringMatcher matcher, boolean negated, List<String> candidates) {
        if (candidates.isEmpty()) {
            return negated;
        }
        for (String candidate : candidates) {
            boolean candidateMatches = candidate != null && matcher.matches((MatchDifference) null, string(candidate));
            if (negated) {
                if (!candidateMatches) {
                    return false;
                }
            } else if (candidateMatches) {
                return true;
            }
        }
        return negated;
    }

    private static List<String> subjectCandidates(org.mockserver.model.X509Certificate leaf) {
        List<String> candidates = new ArrayList<>();
        String subjectDn = leaf.getSubjectDistinguishedName();
        addIfNotBlank(candidates, extractCommonName(subjectDn));
        addIfNotBlank(candidates, subjectDn);
        candidates.addAll(subjectAlternativeNames(leaf));
        return candidates;
    }

    private static List<String> issuerCandidates(org.mockserver.model.X509Certificate leaf) {
        List<String> candidates = new ArrayList<>();
        String issuerDn = leaf.getIssuerDistinguishedName();
        addIfNotBlank(candidates, extractCommonName(issuerDn));
        addIfNotBlank(candidates, issuerDn);
        return candidates;
    }

    private static void addIfNotBlank(List<String> list, String value) {
        if (StringUtils.isNotBlank(value)) {
            list.add(value);
        }
    }

    /**
     * Extract the Common Name (CN) from an RFC 2253 distinguished name such as
     * {@code CN=my-client,OU=test,O=MockServer}. Handles the escaped {@code \,} separator and
     * returns null when there is no CN component.
     */
    static String extractCommonName(String distinguishedName) {
        if (StringUtils.isBlank(distinguishedName)) {
            return null;
        }
        int length = distinguishedName.length();
        int i = 0;
        while (i < length) {
            // find the start of the next relative distinguished name component
            int start = i;
            StringBuilder attribute = new StringBuilder();
            // read attribute type up to '='
            while (i < length && distinguishedName.charAt(i) != '=') {
                attribute.append(distinguishedName.charAt(i));
                i++;
            }
            if (i >= length) {
                break;
            }
            i++; // skip '='
            StringBuilder value = new StringBuilder();
            while (i < length) {
                char c = distinguishedName.charAt(i);
                if (c == '\\' && i + 1 < length) {
                    // preserve the escaped character verbatim
                    value.append(distinguishedName.charAt(i + 1));
                    i += 2;
                } else if (c == ',') {
                    break;
                } else {
                    value.append(c);
                    i++;
                }
            }
            if (i < length && distinguishedName.charAt(i) == ',') {
                i++; // skip separator
            }
            if ("CN".equalsIgnoreCase(attribute.toString().trim())) {
                return value.toString().trim();
            }
            if (start == i) {
                // no progress - avoid an infinite loop on malformed input
                break;
            }
        }
        return null;
    }

    private static List<String> subjectAlternativeNames(org.mockserver.model.X509Certificate leaf) {
        List<String> names = new ArrayList<>();
        try {
            if (leaf.getCertificate() instanceof java.security.cert.X509Certificate jdkCertificate) {
                Collection<List<?>> subjectAlternativeNames = jdkCertificate.getSubjectAlternativeNames();
                if (subjectAlternativeNames != null) {
                    for (List<?> entry : subjectAlternativeNames) {
                        if (entry != null && entry.size() >= 2 && entry.get(1) instanceof String value) {
                            names.add(value);
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
            // best effort - a certificate whose SANs cannot be parsed simply contributes none
        }
        return names;
    }

    /**
     * Compute the SHA-256 fingerprint of the leaf certificate's DER encoding, as a lowercase hex
     * string with no separators. Null when the certificate bytes are unavailable.
     */
    static String sha256Fingerprint(org.mockserver.model.X509Certificate leaf) {
        byte[] encoded = leaf.getCertificateBytes();
        if (encoded == null && leaf.getCertificate() != null) {
            try {
                encoded = leaf.getCertificate().getEncoded();
            } catch (Throwable ignore) {
                encoded = null;
            }
        }
        if (encoded == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * Normalise a fingerprint matcher value so common human-readable forms compare equal: colons and
     * whitespace are stripped (case-insensitivity is already handled by {@link RegexStringMatcher}).
     * The {@link NottableString} negation flag is preserved.
     */
    private static NottableString normaliseFingerprint(NottableString fingerprint) {
        if (fingerprint == null || fingerprint.getValue() == null) {
            return fingerprint;
        }
        String normalised = fingerprint.getValue().replace(":", "").replaceAll("\\s", "");
        return fingerprint.isNot() ? NottableString.not(normalised) : string(normalised);
    }

    @Override
    @JsonIgnore
    public String[] fieldsExcludedFromEqualsAndHashCode() {
        return excludedFields;
    }
}
