package org.mockserver.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.mockserver.model.NottableString.string;

/**
 * Expectation criteria for matching a JSON Web Token (JWT) carried in a request header.
 * <p>
 * The token is read from the header named by {@link #getHeader() header} (default {@code
 * authorization}), the {@link #getScheme() scheme} prefix (default {@code Bearer}) is stripped, and
 * the token's {@code header.payload} segments are decoded with base64url + JSON. <strong>No
 * signature verification is performed</strong> — this is request <em>matching</em> for test routing,
 * not authentication. Control-plane JWT authentication (the {@code authentication/} stack) is a
 * separate concern and is unaffected by this matcher.
 * <p>
 * A request is matched when every configured criterion matches the decoded token:
 * <ul>
 *   <li>{@code claims} — each entry asserts the value of a claim in the JWT payload. The value is a
 *   {@link NottableString} so exact, regex and {@code !} negation forms are all supported (e.g.
 *   {@code {"sub":"user-1","scope":".*admin.*"}}). A positive criterion whose claim is absent from
 *   the token never matches; a <em>negated</em> criterion (e.g. {@code !admin}) against an absent
 *   claim matches vacuously — there is no value to contradict it. The same holds for the
 *   {@code issuer}, {@code audience} and {@code algorithm} convenience criteria.</li>
 *   <li>{@code issuer} — convenience criterion for the {@code iss} claim.</li>
 *   <li>{@code audience} — convenience criterion for the {@code aud} claim (which may be a single
 *   string or an array; a match against any array element is a match).</li>
 *   <li>{@code algorithm} — convenience criterion for the JOSE header {@code alg} field.</li>
 * </ul>
 * A malformed or absent token never matches a non-blank {@code Jwt} criterion (and never surfaces an
 * exception to the caller).
 *
 * @author jamesdbloom
 */
@SuppressWarnings("UnusedReturnValue")
public class Jwt extends ObjectWithJsonToString {

    public static final String DEFAULT_HEADER = "authorization";
    public static final String DEFAULT_SCHEME = "Bearer";

    private String header = DEFAULT_HEADER;
    private String scheme = DEFAULT_SCHEME;
    private Map<String, NottableString> claims;
    private NottableString issuer;
    private NottableString audience;
    private NottableString algorithm;
    private int hashCode;

    public static Jwt jwt() {
        return new Jwt();
    }

    public String getHeader() {
        return header;
    }

    /**
     * The name of the request header carrying the JWT (default {@code authorization}).
     */
    public Jwt withHeader(String header) {
        this.header = header;
        this.hashCode = 0;
        return this;
    }

    public String getScheme() {
        return scheme;
    }

    /**
     * The authentication scheme prefix stripped from the header value before decoding (default
     * {@code Bearer}). Matched case-insensitively; a blank scheme means the header value is treated
     * as the raw token.
     */
    public Jwt withScheme(String scheme) {
        this.scheme = scheme;
        this.hashCode = 0;
        return this;
    }

    public Map<String, NottableString> getClaims() {
        return claims;
    }

    public Jwt withClaims(Map<String, NottableString> claims) {
        this.claims = claims;
        this.hashCode = 0;
        return this;
    }

    /**
     * Add a single claim-value criterion. The value is parsed as a {@link NottableString} so a regex
     * or {@code !}-negated form is honoured.
     */
    public Jwt withClaim(String name, String value) {
        return withClaim(name, string(value));
    }

    public Jwt withClaim(String name, NottableString value) {
        if (claims == null) {
            claims = new LinkedHashMap<>();
        }
        claims.put(name, value);
        this.hashCode = 0;
        return this;
    }

    public NottableString getIssuer() {
        return issuer;
    }

    public Jwt withIssuer(NottableString issuer) {
        this.issuer = issuer;
        this.hashCode = 0;
        return this;
    }

    public Jwt withIssuer(String issuer) {
        return withIssuer(string(issuer));
    }

    public NottableString getAudience() {
        return audience;
    }

    public Jwt withAudience(NottableString audience) {
        this.audience = audience;
        this.hashCode = 0;
        return this;
    }

    public Jwt withAudience(String audience) {
        return withAudience(string(audience));
    }

    public NottableString getAlgorithm() {
        return algorithm;
    }

    public Jwt withAlgorithm(NottableString algorithm) {
        this.algorithm = algorithm;
        this.hashCode = 0;
        return this;
    }

    public Jwt withAlgorithm(String algorithm) {
        return withAlgorithm(string(algorithm));
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    public Jwt clone() {
        return jwt()
            .withHeader(header)
            .withScheme(scheme)
            .withClaims(claims != null ? new LinkedHashMap<>(claims) : null)
            .withIssuer(issuer)
            .withAudience(audience)
            .withAlgorithm(algorithm);
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
        Jwt that = (Jwt) o;
        return Objects.equals(header, that.header) &&
            Objects.equals(scheme, that.scheme) &&
            Objects.equals(claims, that.claims) &&
            Objects.equals(issuer, that.issuer) &&
            Objects.equals(audience, that.audience) &&
            Objects.equals(algorithm, that.algorithm);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(header, scheme, claims, issuer, audience, algorithm);
        }
        return hashCode;
    }
}
