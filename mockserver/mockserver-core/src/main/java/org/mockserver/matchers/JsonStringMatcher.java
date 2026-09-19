package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Joiner;
import net.javacrumbs.jsonunit.core.Configuration;
import net.javacrumbs.jsonunit.core.internal.Diff;
import net.javacrumbs.jsonunit.core.Option;
import net.javacrumbs.jsonunit.core.listener.DifferenceContext;
import net.javacrumbs.jsonunit.core.listener.DifferenceListener;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.Matcher;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.ObjectMapperFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static net.javacrumbs.jsonunit.core.Option.*;
import static org.mockserver.character.Character.NEW_LINE;

/**
 * @author jamesdbloom
 */
public class JsonStringMatcher extends BodyMatcher<String> {
    // matcherJsonNode, baseConfiguration and baseConfigurationMatchers are lazily populated caches
    // derived entirely from matcher/matchType/matchNumbersAsStrings, so — like the derived fields
    // excluded by the sibling matchers (JsonPathMatcher's jsonPath, XmlSchemaMatcher's
    // xmlSchemaValidator, MultipartMatcher's decoder) — they must not take part in equality: two
    // matchers built from the same JSON are the same matcher whether or not either has matched yet.
    private static final String[] EXCLUDED_FIELDS = {"mockServerLogger", "matcherJsonNode", "baseConfiguration", "baseConfigurationMatchers"};
    private static final ObjectWriter PRETTY_PRINTER = ObjectMapperFactory.createObjectMapper(true, false);
    private static final ThreadLocal<Object[]> BODY_PARSE_CACHE = ThreadLocal.withInitial(() -> new Object[2]);
    // Parsing both documents here and handing json-unit the resulting Jackson nodes avoids
    // re-parsing on every match, but it makes matching depend on which JSON provider json-unit
    // resolves to: it chooses a NodeFactory by asking each in turn whether it claims the value and
    // falls back to the last one registered, and only its Jackson2 factory claims a Jackson node.
    // Where json-unit resolves to another provider — e.g. json.org, whether because Jackson is not
    // visible to json-unit or because json-unit.libraries pins it — that factory rejects the node
    // with "Unsupported type class com.fasterxml.jackson.databind.node.ObjectNode" and NO JSON body
    // ever matches (#2496). Probe once at class load; where the nodes are not accepted, fall back
    // to giving json-unit the raw JSON text, which every provider can parse.
    private static final boolean JSON_UNIT_ACCEPTS_JACKSON_NODES = jsonUnitAcceptsJacksonNodes();
    private final MockServerLogger mockServerLogger;
    private final String matcher;
    private volatile JsonNode matcherJsonNode;
    private final MatchType matchType;
    private final boolean matchNumbersAsStrings;
    // the options are derived only from matchType + matchNumbersAsStrings, both fixed per
    // instance, so the EnumSet is computed once and shared (read-only) across all matches()
    private final EnumSet<Option> options;
    // base (template) Configuration carrying the invariant options/tolerance/custom-matchers; the
    // only per-call state is the difference listener, attached via withDifferenceListener() which
    // returns a fresh immutable copy (all Configuration fields are final), so this template is
    // safe to share across concurrent threads. Cached together with the custom-matcher map
    // identity it was built from so a runtime change to the configured matchers rebuilds it,
    // preserving the original load-on-every-call semantics.
    private volatile Configuration baseConfiguration;
    private volatile Map<String, Matcher<?>> baseConfigurationMatchers;

    JsonStringMatcher(MockServerLogger mockServerLogger, String matcher, MatchType matchType) {
        this(mockServerLogger, matcher, matchType, false);
    }

    JsonStringMatcher(MockServerLogger mockServerLogger, String matcher, MatchType matchType, boolean matchNumbersAsStrings) {
        this.mockServerLogger = mockServerLogger;
        this.matcher = matcher;
        this.matchType = matchType;
        this.matchNumbersAsStrings = matchNumbersAsStrings;
        this.options = optionsFor(matchType);
    }

    /**
     * Whether json-unit, as resolved on this classpath, accepts pre-parsed Jackson nodes. Probed
     * once with a trivial document; json-unit fixes its provider selection at class load, so the
     * answer cannot change for the life of the JVM.
     */
    private static boolean jsonUnitAcceptsJacksonNodes() {
        try {
            JsonNode probe = ObjectMapperFactory.createObjectMapper().readTree("{\"a\":1}");
            return Diff.create(probe, probe, "", "", Configuration.empty()).similar();
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static EnumSet<Option> optionsFor(MatchType matchType) {
        EnumSet<Option> options = EnumSet.noneOf(Option.class);
        switch (matchType) {
            case STRICT:
                break;
            case ONLY_MATCHING_FIELDS:
                options.add(IGNORING_ARRAY_ORDER);
                options.add(IGNORING_EXTRA_ARRAY_ITEMS);
                options.add(IGNORING_EXTRA_FIELDS);
                break;
        }
        return options;
    }

    /**
     * Returns the invariant Configuration template (options + tolerance + custom matchers) without
     * a difference listener. Built once and reused while the configured custom-matcher map is
     * unchanged; rebuilt if the loader returns a different map (e.g. the
     * {@code mockserver.customJsonUnitMatchersClass} property changed at runtime), preserving the
     * original semantics of loading matchers on every match.
     */
    private Configuration baseConfiguration() {
        Map<String, Matcher<?>> customMatchers = CustomJsonUnitMatcherLoader.load(
            mockServerLogger != null ? mockServerLogger.getConfiguration() : null);
        // read the matcher-key first, then the config: paired with the write order below this
        // guarantees that whenever the key matches, the config field seen was built from it
        Map<String, Matcher<?>> cachedMatchers = baseConfigurationMatchers;
        Configuration current = baseConfiguration;
        if (current != null && cachedMatchers == customMatchers) {
            return current;
        }
        Configuration built = Configuration.empty().withOptions(options);
        if (matchNumbersAsStrings) {
            built = built.withTolerance(BigDecimal.ZERO);
        }
        for (Map.Entry<String, Matcher<?>> entry : customMatchers.entrySet()) {
            built = built.withMatcher(entry.getKey(), entry.getValue());
        }
        // publish the config before the key it was built from; readers that observe the matching
        // key (read above before the config) are then guaranteed to also observe this config
        baseConfiguration = built;
        baseConfigurationMatchers = customMatchers;
        return built;
    }

    public boolean matches(final MatchDifference context, String matched) {
        return matches(context, matched, JSON_UNIT_ACCEPTS_JACKSON_NODES);
    }

    /**
     * Match with the choice of json-unit input made explicit, so that both the pre-parsed Jackson
     * node path and the raw JSON text fallback can be exercised directly. Which one production uses
     * is decided once per JVM by {@link #JSON_UNIT_ACCEPTS_JACKSON_NODES}, which a test cannot vary
     * without forking a JVM.
     */
    boolean matches(final MatchDifference context, String matched, boolean useJacksonNodes) {
        boolean result = false;

        try {
            if (StringUtils.isBlank(matcher)) {
                result = true;
            } else {
                final Difference diffListener = new Difference();
                final Configuration diffConfig = baseConfiguration().withDifferenceListener(diffListener);

                try {
                    Object expected;
                    Object actual;
                    if (useJacksonNodes) {
                        if (matcherJsonNode == null) {
                            matcherJsonNode = ObjectMapperFactory.createObjectMapper().readTree(matcher);
                        }
                        Object[] cache = BODY_PARSE_CACHE.get();
                        JsonNode matchedNode;
                        if (matched.equals(cache[0])) {
                            matchedNode = (JsonNode) cache[1];
                        } else {
                            matchedNode = ObjectMapperFactory.createObjectMapper().readTree(matched);
                            cache[0] = matched;
                            cache[1] = matchedNode;
                        }
                        expected = matcherJsonNode;
                        actual = matchedNode;
                    } else {
                        // hand json-unit the raw JSON text and let it parse with its own provider
                        expected = matcher;
                        actual = matched;
                    }
                    // Pure NEGATIVE structural pre-check on the pre-parsed Jackson nodes. It can
                    // only fast-reject a PROVABLE non-match or defer ("run the real diff"), so it
                    // can never turn a non-match into a match — the cheap reject saves json-unit
                    // building a ComparisonMatrix (the profiled allocation/time hot spot) on bodies
                    // that cannot possibly be similar.
                    //
                    // GATED OFF when detailedMatchFailures is enabled — do NOT "optimise" this gate
                    // away. A pre-filtered reject skips json-unit's difference listener, so the
                    // failure report would fall back to the generic "json match failed" text instead
                    // of json-unit's specific "missing element at ..." diagnostic. Gating on
                    // detailedMatchFailures makes the change OBSERVATIONALLY IDENTICAL: the verdict
                    // is always identical, and whenever anyone has asked for diagnostics the exact
                    // json-unit message is preserved — while the full win is kept in the shipped
                    // default configuration (detailedMatchFailures defaults to false).
                    if (useJacksonNodes && !detailedMatchFailures() && !canMatch((JsonNode) expected, (JsonNode) actual)) {
                        result = false;
                    } else {
                        result = Diff
                            .create(
                                expected,
                                actual,
                                "",
                                "",
                                diffConfig
                            )
                            .similar();
                    }
                } catch (Throwable throwable) {
                    if (context != null) {
                        context.addDifference(mockServerLogger, throwable, "exception while perform json match failed expected:{}found:{}failed because:{}", this.matcher, matched, describe(throwable));
                    }
                }

                if (!result) {
                    if (context != null) {
                        if (diffListener.differences.isEmpty()) {
                            context.addDifference(mockServerLogger, "json match failed expected:{}found:{}", this.matcher, matched);
                        } else {
                            context.addDifference(mockServerLogger, "json match failed expected:{}found:{}failed because:{}", this.matcher, matched, Joiner.on("," + NEW_LINE).join(diffListener.differences));
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            if (context != null) {
                context.addDifference(mockServerLogger, throwable, "json match failed expected:{}found:{}failed because:{}", this.matcher, matched, describe(throwable));
            }
        }

        return not != result;
    }

    /**
     * Describe a throwable for the match-failure report. The exception type is always included
     * because it is the part that identifies the failure: a JSON parse error, a missing json-unit
     * class and a runtime error are indistinguishable from the message alone (and some, such as
     * NullPointerException, may carry no message at all), which left users of the JSON matcher
     * unable to tell why a match had failed.
     */
    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return StringUtils.isNotBlank(message)
            ? throwable.getClass().getName() + ": " + message
            : throwable.getClass().getName();
    }

    /**
     * Whether detailed match-failure reports are enabled, read from the same instance
     * {@link org.mockserver.configuration.Configuration} the logger already carries (the route the
     * sibling matchers use), falling back to {@link ConfigurationProperties} when no instance
     * configuration is set — exactly the {@code null}-handling contract documented on
     * {@link MockServerLogger#getConfiguration()}. Used only to gate the structural pre-filter off
     * when a caller has asked for detailed diagnostics, so the pre-filter never degrades a failure
     * message.
     */
    private boolean detailedMatchFailures() {
        org.mockserver.configuration.Configuration configuration =
            mockServerLogger != null ? mockServerLogger.getConfiguration() : null;
        return configuration != null
            ? configuration.detailedMatchFailures()
            : ConfigurationProperties.detailedMatchFailures();
    }

    /**
     * Pure NEGATIVE structural pre-check over pre-parsed Jackson nodes: returns {@code false} ONLY
     * when a non-match is PROVABLE, and {@code true} meaning "cannot prove a non-match — run the
     * full json-unit diff". It MUST never return {@code false} for a pair json-unit would call
     * similar; the shape of the checks is the correctness argument — it can only fast-reject or
     * defer, never accept, so it can never turn a non-match into a match. The rules are independent
     * of {@link MatchType}: each rejection ("expected object vs non-object actual", "expected key
     * absent from actual", "expected array vs non-array actual") is a non-match under STRICT and
     * ONLY_MATCHING_FIELDS alike.
     */
    private static boolean canMatch(JsonNode expected, JsonNode actual) {
        if (expected == null) {
            return true;
        }
        if (expected.isObject()) {
            // an expected object can only be similar to an actual object
            if (actual == null || !actual.isObject()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode expectedValue = field.getValue();
                // A JSON null expected value carries no key-existence obligation we can rely on
                // (json-unit's null handling is option-dependent), and a json-unit."..." placeholder
                // is a matcher directive whose semantics — including whether a missing key still
                // matches — live entirely in json-unit. Defer both: skipping a field only ever
                // widens what we defer, which is always safe for a pure-negative filter.
                if (expectedValue.isNull() || isPlaceholder(expectedValue)) {
                    continue;
                }
                JsonNode actualValue = actual.get(field.getKey());
                if (actualValue == null) {
                    // expected a concrete field that actual does not have -> "missing element"
                    return false;
                }
                // recurse THROUGH OBJECTS ONLY; array element/order/subset semantics and every
                // scalar comparison (regex, tolerance, matchNumbersAsStrings, custom matchers)
                // stay entirely with json-unit
                if (expectedValue.isObject() && !canMatch(expectedValue, actualValue)) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray()) {
            // check ONLY that actual is an array; never inspect elements
            return actual != null && actual.isArray();
        }
        // scalars: never reject on a value
        return true;
    }

    /**
     * Whether a JSON node is a json-unit placeholder string (e.g. {@code ${json-unit.ignore-element}},
     * {@code ${json-unit.any-string}}, {@code ${json-unit.matches:name}}). Detection is deliberately
     * broad — matching the {@code ${json-unit.} marker anywhere in the string — because
     * over-detecting only defers a field to json-unit (safe for a pure-negative filter), whereas
     * under-detecting could fast-reject a field json-unit would have matched.
     */
    private static boolean isPlaceholder(JsonNode value) {
        return value.isTextual() && value.textValue().contains("${json-unit.");
    }

    private static class Difference implements DifferenceListener {

        public List<String> differences = new ArrayList<>();

        @Override
        public void diff(net.javacrumbs.jsonunit.core.listener.Difference difference, DifferenceContext context) {
            switch (difference.getType()) {
                case EXTRA:
                    differences.add("additional element at \"" + difference.getActualPath() + "\" with value: " + prettyPrint(difference.getActual()));
                    break;
                case MISSING:
                    differences.add("missing element at \"" + difference.getActualPath() + "\"");
                    break;
                case DIFFERENT:
                    differences.add("wrong value at \"" + difference.getActualPath() + "\", expected: " + prettyPrint(difference.getExpected()) + " but was: " + prettyPrint(difference.getActual()));
                    break;
            }
        }

        private String prettyPrint(Object value) {
            try {
                return PRETTY_PRINTER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return String.valueOf(value);
            }
        }
    }

    public boolean isBlank() {
        return StringUtils.isBlank(matcher);
    }

    @Override
    @JsonIgnore
    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }
}
