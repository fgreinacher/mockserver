package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * A composite {@link BodyMatcher} that matches only when <strong>every</strong> component body
 * matcher matches the request body.
 * <p>
 * The composite is a thin holder for its component matchers. The actual per-component dispatch lives
 * in {@link BodyMatching} (which is where the request/response body source, the JSON-schema body
 * decoder and the logger are in scope), so each component is matched exactly as it would be on its
 * own — the composition changes no individual matcher's semantics.
 *
 * @author jamesdbloom
 */
public class AllOfBodyMatcher extends BodyMatcher<Object> {

    private static final String[] EXCLUDED_FIELDS = {"matchers"};
    private final List<BodyMatcher> matchers;

    AllOfBodyMatcher(List<BodyMatcher> matchers) {
        this.matchers = matchers != null ? matchers : new ArrayList<>();
    }

    List<BodyMatcher> getMatchers() {
        return matchers;
    }

    /**
     * Whether any component matcher is one of the JSON family that needs the request's XML→JSON
     * body decoder, so the request matcher knows to allocate one.
     */
    boolean containsJsonMatcher() {
        for (BodyMatcher matcher : matchers) {
            if (matcher instanceof JsonStringMatcher
                || matcher instanceof JsonSchemaMatcher
                || matcher instanceof JsonPathMatcher
                || (matcher instanceof AllOfBodyMatcher && ((AllOfBodyMatcher) matcher).containsJsonMatcher())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Not called on the composite directly — {@link BodyMatching} performs the per-component dispatch
     * so it can supply each component the body source and decoder it needs. Present only to satisfy
     * the {@link Matcher} contract.
     */
    @Override
    public boolean matches(MatchDifference context, Object matched) {
        throw new UnsupportedOperationException("AllOfBodyMatcher is dispatched by BodyMatching, not matched directly");
    }

    @Override
    public boolean isBlank() {
        if (matchers.isEmpty()) {
            return true;
        }
        for (BodyMatcher matcher : matchers) {
            if (matcher != null && !matcher.isBlank()) {
                return false;
            }
        }
        return true;
    }

    @Override
    @JsonIgnore
    public String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }
}
