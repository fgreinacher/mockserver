package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Jwt;
import org.mockserver.model.NottableString;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.mockserver.model.NottableString.string;

/**
 * Matches an expectation's {@link Jwt} criteria against the JSON Web Token carried in a request
 * header.
 * <p>
 * The matcher reads the configured header from the request, strips the configured scheme prefix
 * (default {@code Bearer}, case-insensitive), splits the token into its {@code header.payload.signature}
 * segments and base64url-decodes the {@code header} and {@code payload} segments into JSON. Claim
 * criteria are matched against the payload; the {@code algorithm} convenience criterion is matched
 * against the JOSE header {@code alg}. See {@link Jwt} for the full description of the criteria and
 * their {@link NottableString} (regex / negation) semantics.
 * <p>
 * <strong>No signature verification is performed</strong> — this is request matching for test
 * routing only. A blank criterion matches every request; a non-blank criterion never matches a
 * request whose header is absent or whose token is malformed (a clean non-match, never an
 * exception).
 *
 * @author jamesdbloom
 */
public class JwtMatcher extends ObjectWithReflectiveEqualsHashCodeToString implements Matcher<String> {

    private static final String[] EXCLUDED_FIELDS = {"mockServerLogger", "claimMatchers", "issuerMatcher", "audienceMatcher", "algorithmMatcher"};
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final MockServerLogger mockServerLogger;
    private final Jwt jwt;
    private final Map<String, RegexStringMatcher> claimMatchers;
    private final RegexStringMatcher issuerMatcher;
    private final RegexStringMatcher audienceMatcher;
    private final RegexStringMatcher algorithmMatcher;

    JwtMatcher(MockServerLogger mockServerLogger, Jwt jwt, boolean controlPlaneMatcher) {
        this.mockServerLogger = mockServerLogger;
        this.jwt = jwt;
        this.claimMatchers = new HashMap<>();
        if (jwt != null && jwt.getClaims() != null) {
            for (Map.Entry<String, NottableString> claim : jwt.getClaims().entrySet()) {
                if (claim.getValue() != null) {
                    this.claimMatchers.put(claim.getKey(), new RegexStringMatcher(mockServerLogger, claim.getValue(), controlPlaneMatcher));
                }
            }
        }
        this.issuerMatcher = jwt != null && jwt.getIssuer() != null
            ? new RegexStringMatcher(mockServerLogger, jwt.getIssuer(), controlPlaneMatcher) : null;
        this.audienceMatcher = jwt != null && jwt.getAudience() != null
            ? new RegexStringMatcher(mockServerLogger, jwt.getAudience(), controlPlaneMatcher) : null;
        this.algorithmMatcher = jwt != null && jwt.getAlgorithm() != null
            ? new RegexStringMatcher(mockServerLogger, jwt.getAlgorithm(), controlPlaneMatcher) : null;
    }

    /**
     * The request header from which the JWT is read (default {@code authorization}). Exposed so the
     * request matcher can extract the header value to match against.
     */
    public String getHeaderName() {
        return jwt != null && StringUtils.isNotBlank(jwt.getHeader()) ? jwt.getHeader() : Jwt.DEFAULT_HEADER;
    }

    @Override
    public boolean isBlank() {
        return (claimMatchers.isEmpty() || claimMatchers.values().stream().allMatch(RegexStringMatcher::isBlank))
            && (issuerMatcher == null || issuerMatcher.isBlank())
            && (audienceMatcher == null || audienceMatcher.isBlank())
            && (algorithmMatcher == null || algorithmMatcher.isBlank());
    }

    @Override
    public boolean matches(final MatchDifference context, final String headerValue) {
        if (isBlank()) {
            return true;
        }
        if (StringUtils.isBlank(headerValue)) {
            addDifference(context, "jwt match failed - request presented no \"{}\" header carrying a token", getHeaderName());
            return false;
        }

        String token = stripScheme(headerValue.trim());
        String[] segments = token.split("\\.");
        if (segments.length < 2) {
            addDifference(context, "jwt match failed - value of header \"{}\" is not a well-formed JWT", getHeaderName());
            return false;
        }

        JsonNode joseHeader = decodeSegment(segments[0]);
        JsonNode payload = decodeSegment(segments[1]);
        if (payload == null) {
            addDifference(context, "jwt match failed - token payload could not be base64url/JSON decoded");
            return false;
        }

        if (algorithmMatcher != null && !algorithmMatcher.isBlank()) {
            String alg = joseHeader != null && joseHeader.hasNonNull("alg") ? joseHeader.get("alg").asText() : null;
            List<String> candidates = alg != null ? java.util.Collections.singletonList(alg) : java.util.Collections.emptyList();
            if (!matchesAcrossCandidates(algorithmMatcher, jwt.getAlgorithm().isNot(), candidates)) {
                addDifference(context, "jwt algorithm match failed expected:{}found:{}", jwt.getAlgorithm(), alg);
                return false;
            }
        }
        if (issuerMatcher != null && !issuerMatcher.isBlank()) {
            List<String> candidates = claimValues(payload, "iss");
            if (!matchesAcrossCandidates(issuerMatcher, jwt.getIssuer().isNot(), candidates)) {
                addDifference(context, "jwt issuer match failed expected:{}found:{}", jwt.getIssuer(), candidates);
                return false;
            }
        }
        if (audienceMatcher != null && !audienceMatcher.isBlank()) {
            List<String> candidates = claimValues(payload, "aud");
            if (!matchesAcrossCandidates(audienceMatcher, jwt.getAudience().isNot(), candidates)) {
                addDifference(context, "jwt audience match failed expected:{}found:{}", jwt.getAudience(), candidates);
                return false;
            }
        }
        for (Map.Entry<String, RegexStringMatcher> claim : claimMatchers.entrySet()) {
            RegexStringMatcher matcher = claim.getValue();
            if (matcher.isBlank()) {
                continue;
            }
            NottableString expected = jwt.getClaims().get(claim.getKey());
            List<String> candidates = claimValues(payload, claim.getKey());
            if (!matchesAcrossCandidates(matcher, expected != null && expected.isNot(), candidates)) {
                addDifference(context, "jwt claim \"" + claim.getKey() + "\" match failed expected:{}found:{}", expected, candidates);
                return false;
            }
        }
        return true;
    }

    /**
     * Match a criterion across the candidate values a claim exposes (a scalar claim yields one
     * candidate; an array claim such as {@code aud} yields several) with correct De Morgan negation
     * semantics: a positive criterion matches when it matches <em>any</em> candidate (OR); a negated
     * criterion matches only when it holds for <em>every</em> candidate (AND). With no candidates the
     * vacuous reduction is returned ({@code true} for a negation, {@code false} otherwise), so an
     * absent claim never satisfies a positive criterion.
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

    private String stripScheme(String headerValue) {
        String scheme = jwt != null ? jwt.getScheme() : Jwt.DEFAULT_SCHEME;
        if (StringUtils.isNotBlank(scheme)) {
            String prefix = scheme.trim() + " ";
            if (headerValue.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return headerValue.substring(prefix.length()).trim();
            }
        }
        return headerValue;
    }

    private JsonNode decodeSegment(String segment) {
        try {
            byte[] decoded = BASE64_URL_DECODER.decode(padBase64Url(segment));
            return OBJECT_MAPPER.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (Throwable ignore) {
            // malformed segment contributes nothing - never surfaces as an exception
            return null;
        }
    }

    private static String padBase64Url(String segment) {
        int remainder = segment.length() % 4;
        if (remainder == 0) {
            return segment;
        }
        return segment + "====".substring(remainder);
    }

    private static List<String> claimValues(JsonNode payload, String claimName) {
        List<String> values = new ArrayList<>();
        if (payload == null) {
            return values;
        }
        JsonNode node = payload.get(claimName);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return values;
        }
        if (node.isArray()) {
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                JsonNode element = it.next();
                if (element != null && !element.isNull()) {
                    values.add(element.asText());
                }
            }
        } else {
            values.add(node.asText());
        }
        return values;
    }

    private void addDifference(MatchDifference context, String messageFormat, Object... arguments) {
        if (context != null) {
            context.addDifference(mockServerLogger, messageFormat, arguments);
        }
    }

    @Override
    @JsonIgnore
    public String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }
}
