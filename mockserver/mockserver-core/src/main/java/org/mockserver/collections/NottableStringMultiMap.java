package org.mockserver.collections;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.BinaryHeaderValueNormalizer;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.RegexStringMatcher;
import org.mockserver.model.*;

import java.util.*;

import static org.mockserver.collections.ImmutableEntry.entry;
import static org.mockserver.collections.SubSetMatcher.containsSubset;
import static org.mockserver.model.NottableString.string;

/**
 * @author jamesdbloom
 */
public class NottableStringMultiMap extends ObjectWithReflectiveEqualsHashCodeToString {

    private final Map<NottableString, List<NottableString>> backingMap = new LinkedHashMap<>();
    private final RegexStringMatcher regexStringMatcher;
    private final KeyMatchStyle keyMatchStyle;
    /**
     * True when this map holds HTTP headers, which is the only place gRPC metadata lands. It enables
     * base64-padding-insensitive comparison for {@code -bin} keys (see
     * {@link org.mockserver.matchers.BinaryHeaderValueNormalizer}) without touching query-string or
     * path parameter matching.
     */
    private final boolean binaryHeaderNormalization;

    public NottableStringMultiMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, KeyMatchStyle keyMatchStyle, List<? extends KeyToMultiValue> entries) {
        this(mockServerLogger, controlPlaneMatcher, keyMatchStyle, entries, false);
    }

    public NottableStringMultiMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, KeyMatchStyle keyMatchStyle, List<? extends KeyToMultiValue> entries, boolean binaryHeaderNormalization) {
        this.keyMatchStyle = keyMatchStyle;
        this.binaryHeaderNormalization = binaryHeaderNormalization;
        regexStringMatcher = new RegexStringMatcher(mockServerLogger, controlPlaneMatcher);
        for (KeyToMultiValue keyToMultiValue : entries) {
            backingMap.put(keyToMultiValue.getName(), keyToMultiValue.getValues());
        }
    }

    /**
     * Returns a {@link NottableStringMultiMap} view of the given request-side collection, reusing a
     * memoized instance held on the collection when one is available for this {@code controlPlaneMatcher}.
     * <p>
     * The conversion is keyed by {@code controlPlaneMatcher} because the resulting map embeds a
     * control-plane-sensitive {@link RegexStringMatcher}; the memo on {@link KeysToMultiValues} is cleared
     * on every mutation, so a collection that is mutated mid-scan (e.g. query parameters split by
     * {@code ExpandedParameterDecoder.splitParameters}) rebuilds rather than serving a stale view.
     * <p>
     * For matcher (expectation) side maps the existing constructor is used directly; this factory is for
     * the request (matched) side, which would otherwise be rebuilt once per candidate expectation.
     */
    public static NottableStringMultiMap multiMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, KeysToMultiValues<? extends KeyToMultiValue, ? extends KeysToMultiValues> matched) {
        Object cached = matched.getConvertedMatcher(controlPlaneMatcher);
        if (cached instanceof NottableStringMultiMap) {
            return (NottableStringMultiMap) cached;
        }
        NottableStringMultiMap converted = new NottableStringMultiMap(mockServerLogger, controlPlaneMatcher, matched.getKeyMatchStyle(), matched.getEntries(), matched instanceof Headers);
        matched.setConvertedMatcher(controlPlaneMatcher, converted);
        return converted;
    }

    @VisibleForTesting
    public NottableStringMultiMap(MockServerLogger mockServerLogger, boolean controlPlaneMatcher, KeyMatchStyle keyMatchStyle, NottableString[]... keyAndValues) {
        this.keyMatchStyle = keyMatchStyle;
        this.binaryHeaderNormalization = false;
        regexStringMatcher = new RegexStringMatcher(mockServerLogger, controlPlaneMatcher);
        for (NottableString[] keyAndValue : keyAndValues) {
            if (keyAndValue.length > 0) {
                backingMap.put(keyAndValue[0], keyAndValue.length > 1 ? Arrays.asList(keyAndValue).subList(1, keyAndValue.length) : Collections.emptyList());
            }
        }
    }

    public KeyMatchStyle getKeyMatchStyle() {
        return keyMatchStyle;
    }

    public boolean containsAll(MockServerLogger mockServerLogger, MatchDifference context, NottableStringMultiMap subset) {
        // either side identifying itself as a header map is enough — the request side is always a
        // Headers instance for header matching, and the expectation side is built from one too
        boolean normalizeBinaryHeaders = this.binaryHeaderNormalization || subset.binaryHeaderNormalization;
        switch (subset.keyMatchStyle) {
            case SUB_SET: {
                boolean isSubset = containsSubset(mockServerLogger, context, regexStringMatcher, subset.entryList(), entryList(), normalizeBinaryHeaders);
                if (!isSubset && context != null) {
                    context.addDifference(mockServerLogger, "multimap subset match failed subset:{}was not a subset of:{}", subset.entryList(), entryList());
                }
                return isSubset;
            }
            case MATCHING_KEY: {
                for (NottableString matcherKey : subset.backingMap.keySet()) {
                    // A notted matcher key (e.g. "!X") asserts that the key is ABSENT, mirroring the
                    // SUB_SET semantic in SubSetMatcher#nottedAndPresent. Without this special case
                    // getAll(matcherKey) would "match" every actual key that is NOT X (via the XOR
                    // not-semantics in RegexStringMatcher), aggregating a bag of unrelated values from
                    // those keys — which is not a meaningful "this key must be absent" assertion. So
                    // instead fail iff some actual (non-notted) key matches the un-notted matcher key,
                    // and otherwise treat the absence requirement as satisfied (no values to assert).
                    if (matcherKey.isNot()) {
                        if (containsUnNottedKey(matcherKey)) {
                            if (context != null) {
                                context.addDifference(mockServerLogger, "multimap matching key match failed for notted key:{}", matcherKey);
                            }
                            return false;
                        }
                        continue;
                    }

                    // the owning actual key is collected alongside each value, because the matcher key
                    // may be a regex spanning several actual keys and the binary-metadata decision
                    // needs the real key this value came from — see #matchesValue
                    List<NottableString> matchedKeysForKey = new ArrayList<>();
                    List<NottableString> matchedValuesForKey = getAll(matcherKey, matchedKeysForKey);
                    if (matchedValuesForKey.isEmpty() && !matcherKey.isOptional()) {
                        if (context != null) {
                            context.addDifference(mockServerLogger, "multimap subset match failed subset:{}did not have expected key:{}", subset, matcherKey);
                        }
                        return false;
                    }

                    List<NottableString> matcherValuesForKey = subset.getAll(matcherKey);
                    for (int valueIndex = 0; valueIndex < matchedValuesForKey.size(); valueIndex++) {
                        NottableString matchedValue = matchedValuesForKey.get(valueIndex);
                        NottableString matchedKey = matchedKeysForKey.get(valueIndex);
                        boolean matchesValue = false;
                        for (NottableString matcherValue : matcherValuesForKey) {
                            // match first as list
                            if (matcherValue instanceof NottableSchemaString && ((NottableSchemaString) matcherValue).matches(mockServerLogger, context, matchedValuesForKey)) {
                                matchesValue = true;
                                break;
                                // otherwise match item by item
                            } else if (matchesValue(mockServerLogger, context, normalizeBinaryHeaders, matcherKey, matchedKey, matcherValue, matchedValue)) {
                                matchesValue = true;
                                break;
                            } else {
                                if (context != null) {
                                    context.addDifference(mockServerLogger, "multimap matching key match failed for key:{}", matcherKey);
                                }
                            }
                        }
                        if (!matchesValue) {
                            return false;
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Compares one matcher value against one matched value, first removing base64 padding from both
     * when this is a header map and <em>either</em> key is a gRPC binary metadata key ({@code -bin}).
     * Both keys are passed — not the matcher key twice — so a regex matcher key such as {@code x-.*}
     * against an actual {@code x-trace-bin} is normalised here exactly as it is on the SUB_SET path
     * in {@link SubSetMatcher}; the two key-match styles must not disagree about the same metadata.
     */
    private boolean matchesValue(MockServerLogger mockServerLogger, MatchDifference context, boolean normalizeBinaryHeaders, NottableString matcherKey, NottableString matchedKey, NottableString matcherValue, NottableString matchedValue) {
        if (normalizeBinaryHeaders && BinaryHeaderValueNormalizer.shouldNormalize(matcherKey, matchedKey, matcherValue, matchedValue)) {
            return BinaryHeaderValueNormalizer.matchesIgnoringPadding(
                regexStringMatcher, mockServerLogger, context, matcherValue, matchedValue);
        }
        return regexStringMatcher.matches(mockServerLogger, context, matcherValue, matchedValue);
    }

    public boolean allKeysNotted() {
        if (!isEmpty()) {
            for (NottableString key : backingMap.keySet()) {
                if (!key.isNot()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean allKeysOptional() {
        if (!isEmpty()) {
            for (NottableString key : backingMap.keySet()) {
                if (!key.isOptional()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean isEmpty() {
        return backingMap.isEmpty();
    }

    /**
     * Returns true when some actual (non-notted) key matches the un-notted form of the given notted
     * matcher key — i.e. the key the {@code "!X"} matcher asserts must be absent is in fact present.
     * Mirrors {@link SubSetMatcher} {@code nottedAndPresent} so MATCHING_KEY and SUB_SET agree on the
     * "this key must be absent" semantic for a notted key.
     */
    private boolean containsUnNottedKey(NottableString nottedKey) {
        if (!isEmpty()) {
            NottableString unNottedKey = string(nottedKey.getValue());
            for (NottableString actualKey : backingMap.keySet()) {
                if (!actualKey.isNot() && regexStringMatcher.matches(unNottedKey, actualKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<NottableString> getAll(NottableString key) {
        return getAll(key, null);
    }

    /**
     * Collects every value whose key matches {@code key}, flattened across keys.
     *
     * @param owningKeys when non-null, receives the actual key each returned value came from, at the
     *                   same index — the matcher key may be a regex spanning several actual keys, and
     *                   a per-key matching decision (binary {@code -bin} metadata) needs the real key
     *                   rather than the pattern that selected it
     */
    private List<NottableString> getAll(NottableString key, List<NottableString> owningKeys) {
        if (!isEmpty()) {
            List<NottableString> values = new ArrayList<>();
            for (Map.Entry<NottableString, List<NottableString>> entry : backingMap.entrySet()) {
                if (regexStringMatcher.matches(key, entry.getKey())) {
                    values.addAll(entry.getValue());
                    if (owningKeys != null) {
                        for (int i = 0; i < entry.getValue().size(); i++) {
                            owningKeys.add(entry.getKey());
                        }
                    }
                }
            }
            return values;
        } else {
            return Collections.emptyList();
        }
    }

    // The backingMap is immutable after construction, so the derived entryList is computed once and reused.
    // This matters because a memoized request-side map (see #multiMap) has its entryList read once per
    // candidate expectation during a request's scan.
    private volatile List<ImmutableEntry> entryList;

    private List<ImmutableEntry> entryList() {
        if (entryList == null) {
            if (!isEmpty()) {
                List<ImmutableEntry> entrySet = new ArrayList<>();
                for (Map.Entry<NottableString, List<NottableString>> entry : backingMap.entrySet()) {
                    for (NottableString value : entry.getValue()) {
                        entrySet.add(entry(regexStringMatcher, entry.getKey(), value));
                    }
                }
                entryList = entrySet;
            } else {
                entryList = Collections.emptyList();
            }
        }
        return entryList;
    }
}



