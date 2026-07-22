package org.mockserver.collections;

import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.BinaryHeaderValueNormalizer;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.RegexStringMatcher;
import org.mockserver.model.NottableString;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        boolean result = true;
        Set<Integer> matchingIndexes = new HashSet<>();
        for (ImmutableEntry subsetItem : subset) {
            Set<Integer> subsetItemMatchingIndexes = matchesIndexes(mockServerLogger, context, regexStringMatcher, subsetItem, superset, binaryHeaderNormalization);
            boolean optionalAndNotPresent = subsetItem.isOptional() && !containsKey(regexStringMatcher, subsetItem, superset);
            boolean nottedAndPresent = nottedAndPresent(regexStringMatcher, subsetItem, superset);
            if ((!optionalAndNotPresent && subsetItemMatchingIndexes.isEmpty()) || nottedAndPresent) {
                result = false;
                break;
            }
            matchingIndexes.addAll(subsetItemMatchingIndexes);
        }

        if (result) {
            long subsetRequiredSize = subset.stream()
                .filter(ImmutableEntry::isNotOptional)
                .filter(ImmutableEntry::isNotNotted)
                .count();
            // this prevents multiple items in the subset from being matched by a single item in the superset
            result = matchingIndexes.size() >= subsetRequiredSize;
        }
        return result;
    }

    private static Set<Integer> matchesIndexes(MockServerLogger mockServerLogger, MatchDifference context, RegexStringMatcher regexStringMatcher, ImmutableEntry matcherItem, List<ImmutableEntry> matchedList, boolean binaryHeaderNormalization) {
        Set<Integer> matchingIndexes = new HashSet<>();
        for (int i = 0; i < matchedList.size(); i++) {
            ImmutableEntry matchedItem = matchedList.get(i);
            boolean keyMatches = regexStringMatcher.matches(mockServerLogger, context, matcherItem.getKey(), matchedItem.getKey());
            NottableString matcherValue = matcherItem.getValue();
            NottableString matchedValue = matchedItem.getValue();
            // gated on keyMatches: the value comparison is irrelevant when the keys do not match, so
            // there is no reason to normalise for every unrelated pair in the superset
            boolean valueMatches;
            if (keyMatches && binaryHeaderNormalization && BinaryHeaderValueNormalizer.shouldNormalize(matcherItem.getKey(), matchedItem.getKey(), matcherValue, matchedValue)) {
                valueMatches = BinaryHeaderValueNormalizer.matchesIgnoringPadding(
                    regexStringMatcher, mockServerLogger, context, matcherValue, matchedValue);
            } else {
                valueMatches = regexStringMatcher.matches(mockServerLogger, context, matcherValue, matchedValue);
            }
            if (keyMatches && valueMatches) {
                matchingIndexes.add(i);
            }
        }
        return matchingIndexes;
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
