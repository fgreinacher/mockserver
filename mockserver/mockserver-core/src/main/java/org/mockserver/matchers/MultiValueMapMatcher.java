package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.collections.NottableStringMultiMap;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Headers;
import org.mockserver.model.KeyMatchStyle;
import org.mockserver.model.KeyToMultiValue;
import org.mockserver.model.KeysToMultiValues;


/**
 * @author jamesdbloom
 */
@SuppressWarnings("rawtypes")
public class MultiValueMapMatcher extends NotMatcher<KeysToMultiValues<? extends KeyToMultiValue, ? extends KeysToMultiValues>> {
    private static final String[] EXCLUDED_FIELDS = {"mockServerLogger"};
    private final MockServerLogger mockServerLogger;
    private final NottableStringMultiMap matcher;
    private final KeysToMultiValues keysToMultiValues;
    private final boolean controlPlaneMatcher;
    private Boolean allKeysNotted;
    private Boolean allKeysOptional;

    MultiValueMapMatcher(MockServerLogger mockServerLogger, KeysToMultiValues<? extends KeyToMultiValue, ? extends KeysToMultiValues> keysToMultiValues, boolean controlPlaneMatcher) {
        this.mockServerLogger = mockServerLogger;
        this.keysToMultiValues = keysToMultiValues;
        this.controlPlaneMatcher = controlPlaneMatcher;
        if (keysToMultiValues != null) {
            // only a header map enables base64-padding-insensitive matching for gRPC binary metadata
            // ({@code -bin}) keys; query-string and path parameter matching is unaffected
            this.matcher = new NottableStringMultiMap(this.mockServerLogger, this.controlPlaneMatcher, keysToMultiValues.getKeyMatchStyle(), keysToMultiValues.getEntries(), keysToMultiValues instanceof Headers);
        } else {
            this.matcher = null;
        }
    }

    public boolean matches(final MatchDifference context, KeysToMultiValues<? extends KeyToMultiValue, ? extends KeysToMultiValues> matched) {
        boolean result;

        if (matcher == null || matcher.isEmpty()) {
            result = true;
        } else if (matched == null || matched.isEmpty()) {
            if (allKeysNotted == null) {
                allKeysNotted = matcher.allKeysNotted();
            }
            if (allKeysOptional == null) {
                allKeysOptional = matcher.allKeysOptional();
            }
            result = allKeysNotted || allKeysOptional;
        } else {
            result = NottableStringMultiMap.multiMap(mockServerLogger, controlPlaneMatcher, matched).containsAll(mockServerLogger, context, matcher);
        }

        if (!result && context != null) {
            context.addDifference(mockServerLogger, "multimap match failed expected:{}found:{}failed because:{}", keysToMultiValues, matched != null ? matched : "none", matched != null ? (matcher.getKeyMatchStyle() == KeyMatchStyle.SUB_SET ? "multimap is not a subset" : "multimap values don't match") : "none is not a subset");
        }

        return not != result;
    }

    public boolean isBlank() {
        return matcher == null || matcher.isEmpty();
    }

    @Override
    @JsonIgnore
    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }
}
