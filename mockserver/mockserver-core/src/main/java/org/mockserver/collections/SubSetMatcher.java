package org.mockserver.collections;

import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.BinaryHeaderValueNormalizer;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.RegexStringMatcher;
import org.mockserver.model.NottableString;

import java.util.List;

import static org.mockserver.model.NottableString.string;

public class SubSetMatcher {

    static boolean containsSubset(MockServerLogger mockServerLogger, MatchDifference context, RegexStringMatcher regexStringMatcher, List<ImmutableEntry> subset, List<ImmutableEntry> superset) {
        return containsSubset(mockServerLogger, context, regexStringMatcher, subset, superset, false);
    }

    /**
     * @param binaryHeaderNormalization when true, the value comparison for an entry whose key is a
     *                                  gRPC binary metadata key ({@code -bin}) ignores base64
     *                                  padding — see {@link BinaryHeaderValueNormalizer}. Enabled
     *                                  only for header maps, so query-string and path parameter
     *                                  matching is unchanged.
     */
    static boolean containsSubset(MockServerLogger mockServerLogger, MatchDifference context, RegexStringMatcher regexStringMatcher, List<ImmutableEntry> subset, List<ImmutableEntry> superset, boolean binaryHeaderNormalization) {
        int supersetSize = superset.size();
        // Tracks the DISTINCT superset indexes matched across ALL subset entries. The invariant this
        // preserves (previously held by a HashSet<Integer> of boxed indexes) is that a single superset
        // entry may not satisfy two different subset entries, so the count of distinct matched indexes
        // must reach the number of required (non-optional, non-notted) subset entries for a match.
        boolean[] matchedSupersetIndexes = new boolean[supersetSize];
        int distinctMatchedCount = 0;
        int subsetRequiredSize = 0;
        for (ImmutableEntry subsetItem : subset) {
            NottableString matcherKey = subsetItem.getKey();
            NottableString matcherValue = subsetItem.getValue();
            boolean subsetItemMatchedAny = false;
            for (int i = 0; i < supersetSize; i++) {
                ImmutableEntry matchedItem = superset.get(i);
                boolean keyMatches = regexStringMatcher.matches(mockServerLogger, context, matcherKey, matchedItem.getKey());
                NottableString matchedValue = matchedItem.getValue();
                // gated on keyMatches: the value comparison is irrelevant when the keys do not match, so
                // there is no reason to normalise for every unrelated pair in the superset
                boolean valueMatches;
                if (keyMatches && binaryHeaderNormalization && BinaryHeaderValueNormalizer.shouldNormalize(matcherKey, matchedItem.getKey(), matcherValue, matchedValue)) {
                    valueMatches = BinaryHeaderValueNormalizer.matchesIgnoringPadding(
                        regexStringMatcher, mockServerLogger, context, matcherValue, matchedValue);
                } else {
                    valueMatches = regexStringMatcher.matches(mockServerLogger, context, matcherValue, matchedValue);
                }
                if (keyMatches && valueMatches) {
                    subsetItemMatchedAny = true;
                    if (!matchedSupersetIndexes[i]) {
                        matchedSupersetIndexes[i] = true;
                        distinctMatchedCount++;
                    }
                }
            }
            boolean optionalAndNotPresent = subsetItem.isOptional() && !containsKey(regexStringMatcher, subsetItem, superset);
            boolean nottedAndPresent = nottedAndPresent(regexStringMatcher, subsetItem, superset);
            if ((!optionalAndNotPresent && !subsetItemMatchedAny) || nottedAndPresent) {
                return false;
            }
            if (subsetItem.isNotOptional() && subsetItem.isNotNotted()) {
                subsetRequiredSize++;
            }
        }
        return distinctMatchedCount >= subsetRequiredSize;
    }

    private static boolean containsKey(RegexStringMatcher regexStringMatcher, ImmutableEntry matcherItem, List<ImmutableEntry> matchedList) {
        for (ImmutableEntry matchedItem : matchedList) {
            if (regexStringMatcher.matches(matcherItem.getKey(), matchedItem.getKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean nottedAndPresent(RegexStringMatcher regexStringMatcher, ImmutableEntry matcherItem, List<ImmutableEntry> matchedList) {
        if (matcherItem.getKey().isNot()) {
            NottableString unNottedMatcherItemKey = string(matcherItem.getKey().getValue());
            for (ImmutableEntry matchedItem : matchedList) {
                if (!matchedItem.getKey().isNot()) {
                    if (regexStringMatcher.matches(unNottedMatcherItemKey, matchedItem.getKey())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

}
