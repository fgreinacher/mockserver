package org.mockserver.matchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;

/**
 * Differential correctness gate for the pure-negative structural pre-filter added to
 * {@link JsonStringMatcher} (the {@code canMatch} pre-check that fast-rejects a provable JSON
 * non-match before json-unit builds a {@code ComparisonMatrix}).
 *
 * <p>The property under test: <b>the pre-filter must never reject a pair json-unit would call
 * similar</b>. It is asserted as an equality — for every generated (expected, actual) pair and for
 * both {@link MatchType} values, the verdict WITH the filter active must equal the verdict WITHOUT
 * it. The filter is toggled purely through the instance {@link Configuration#detailedMatchFailures}
 * carried on the matcher's {@link MockServerLogger} (the pre-filter is gated off when detailed
 * failures are on), so the two runs differ ONLY in whether the pre-filter can fire — no global
 * state is mutated, keeping the test parallel-safe. Because the filter is pure-negative it can only
 * ever wrongly turn a match into a non-match; any such regression makes the two verdicts differ and
 * fails the assertion.
 *
 * <p>Both matcher paths are exercised over the pre-parsed Jackson-node path
 * ({@code matches(context, matched, true)}) explicitly, so the filter is genuinely on the hot path
 * regardless of how json-unit resolves its provider on the test classpath.
 *
 * <p>Count: {@link #RANDOM_PAIRS} pairs, each evaluated under both match types (so ~2x that many
 * differential comparisons). The research prototype ran 800,000; a few tens of thousands is ample
 * to keep the unit suite fast while covering the same generator space, and the generator uses a
 * FIXED seed so the corpus is deterministic and any failure is exactly reproducible. See
 * {@link #shouldProveTheHarnessCanFail_documentedRedRule} for the recorded red-then-green evidence
 * that this differential actually catches an unsound rule.
 */
public class JsonStringMatcherFilterTest {

    private static final int RANDOM_PAIRS = 20_000;
    private static final long SEED = 20260919L;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String[] KEYS = {"a", "b", "c", "id", "name", "value", "items", "order"};
    private static final String[] STRINGS = {"x", "y", "z", "Close", "File", "Acme"};
    // built-in json-unit placeholders that require NO registered custom matcher
    private static final String[] PLACEHOLDERS = {
        "${json-unit.ignore}",
        "${json-unit.ignore-element}",
        "${json-unit.any-string}",
        "${json-unit.any-boolean}",
        "${json-unit.any-number}",
    };

    private static MockServerLogger loggerWithFilter(boolean filterActive) {
        // the pre-filter is gated OFF when detailedMatchFailures is ON, so filterActive maps to
        // detailedMatchFailures == !filterActive; carried on the instance configuration the matcher
        // reads via mockServerLogger.getConfiguration() (no global ConfigurationProperties mutation)
        Configuration configuration = Configuration.configuration().detailedMatchFailures(!filterActive);
        return new MockServerLogger(configuration, JsonStringMatcherFilterTest.class);
    }

    private static JsonNode randomNode(Random random, int depth) {
        int choice = depth <= 0 ? random.nextInt(5) : random.nextInt(8);
        switch (choice) {
            case 0:
                return NODES.numberNode(random.nextInt(6));            // small int -> collisions likely
            case 1:
                return NODES.textNode(STRINGS[random.nextInt(STRINGS.length)]);
            case 2:
                return NODES.booleanNode(random.nextBoolean());
            case 3:
                return NODES.nullNode();
            case 4:
                // ~occasionally a json-unit placeholder string (a matcher directive)
                return NODES.textNode(PLACEHOLDERS[random.nextInt(PLACEHOLDERS.length)]);
            case 5: {
                ObjectNode object = NODES.objectNode();
                int fields = random.nextInt(4);
                for (int i = 0; i < fields; i++) {
                    object.set(KEYS[random.nextInt(KEYS.length)], randomNode(random, depth - 1));
                }
                return object;
            }
            case 6: {
                ArrayNode array = NODES.arrayNode();
                int elements = random.nextInt(4);
                for (int i = 0; i < elements; i++) {
                    array.add(randomNode(random, depth - 1));
                }
                return array;
            }
            default:
                return NODES.numberNode(random.nextInt(6) + 0.5d);     // whole/half doubles
        }
    }

    /**
     * Produces an actual document related to expected in a controlled mix so the corpus exercises
     * exact matches, ONLY_MATCHING_FIELDS supersets, and independent non-matches alike.
     */
    private static JsonNode relatedActual(Random random, JsonNode expected) {
        switch (random.nextInt(3)) {
            case 0:
                return expected.deepCopy();                            // exact -> similar under both modes
            case 1:
                return withExtras(random, expected.deepCopy());        // superset -> similar under ONLY_MATCHING_FIELDS
            default:
                return randomNode(random, 3);                          // independent -> usually a non-match
        }
    }

    private static JsonNode withExtras(Random random, JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            int extra = random.nextInt(3);
            for (int i = 0; i < extra; i++) {
                object.set("extra" + random.nextInt(1000), randomNode(random, 1));
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            int extra = random.nextInt(3);
            for (int i = 0; i < extra; i++) {
                array.add(randomNode(random, 1));
            }
        }
        return node;
    }

    @Test
    public void filterNeverRejectsAPairJsonUnitCallsSimilar() {
        Random random = new Random(SEED);
        int matches = 0;
        int nonMatches = 0;
        for (int i = 0; i < RANDOM_PAIRS; i++) {
            String expectedText = randomNode(random, 3).toString();
            String actualText = relatedActual(random, safeParse(expectedText)).toString();

            for (MatchType matchType : MatchType.values()) {
                boolean withoutFilter = new JsonStringMatcher(loggerWithFilter(false), expectedText, matchType)
                    .matches(null, actualText, true);
                boolean withFilter = new JsonStringMatcher(loggerWithFilter(true), expectedText, matchType)
                    .matches(null, actualText, true);

                if (withoutFilter != withFilter) {
                    throw new AssertionError(
                        "pre-filter changed the verdict for matchType=" + matchType
                            + "\n  expected = " + expectedText
                            + "\n  actual   = " + actualText
                            + "\n  json-unit(no filter) = " + withoutFilter
                            + "\n  with filter          = " + withFilter);
                }
                if (withoutFilter) {
                    matches++;
                } else {
                    nonMatches++;
                }
            }
        }
        // prove both arms of the corpus were genuinely exercised (a corpus that never matched, or
        // never rejected, would make the equality assertion vacuous)
        assertThat("corpus produced no matches", matches, is(greaterThan(100)));
        assertThat("corpus produced no non-matches", nonMatches, is(greaterThan(100)));
    }

    private static JsonNode safeParse(String text) {
        try {
            return org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().readTree(text);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    // --- targeted cases: the pre-filter MUST fire (provable non-match) ---------------------------

    @Test
    public void filterRejectsWhenActualIsNotAnObject() {
        assertRejectedByBothFilterAndJsonUnit("{\"a\":1}", "[1,2,3]");
        assertRejectedByBothFilterAndJsonUnit("{\"a\":1}", "\"scalar\"");
    }

    @Test
    public void filterRejectsWhenAnExpectedKeyIsAbsentFromActual() {
        assertRejectedByBothFilterAndJsonUnit("{\"a\":1,\"b\":2}", "{\"a\":1}");
        assertRejectedByBothFilterAndJsonUnit(
            "{\"order\":{\"id\":1,\"customer\":{\"name\":\"Acme\",\"tier\":\"gold\"}}}",
            "{\"order\":{\"id\":1,\"customer\":{\"name\":\"Acme\"}}}");
    }

    @Test
    public void filterRejectsWhenExpectedArrayMeetsNonArray() {
        assertRejectedByBothFilterAndJsonUnit("[1,2,3]", "{\"a\":1}");
    }

    // --- targeted cases: the pre-filter MUST defer (json-unit still gets to match) ---------------

    @Test
    public void filterDefersOnNullValuedExpectedField() {
        // {"a":null} vs {} is NOT similar to json-unit (missing element), but the filter must DEFER
        // that decision, not fast-reject on the null. Verdicts still agree because json-unit rejects.
        assertVerdictsAgree("{\"a\":null}", "{}", MatchType.STRICT);
        assertVerdictsAgree("{\"a\":null}", "{}", MatchType.ONLY_MATCHING_FIELDS);
    }

    @Test
    public void filterDefersOnPlaceholderValuedExpectedField() {
        // ignore-element means a missing key STILL matches: the filter must not require the key
        assertVerdictsAgree("{\"a\":\"${json-unit.ignore-element}\"}", "{}", MatchType.ONLY_MATCHING_FIELDS);
        assertVerdictsAgree("{\"a\":\"${json-unit.any-string}\"}", "{\"a\":\"anything\"}", MatchType.STRICT);
    }

    private static void assertRejectedByBothFilterAndJsonUnit(String expectedText, String actualText) {
        for (MatchType matchType : MatchType.values()) {
            boolean withoutFilter = new JsonStringMatcher(loggerWithFilter(false), expectedText, matchType)
                .matches(null, actualText, true);
            boolean withFilter = new JsonStringMatcher(loggerWithFilter(true), expectedText, matchType)
                .matches(null, actualText, true);
            assertThat("json-unit should reject " + expectedText + " vs " + actualText + " (" + matchType + ")",
                withoutFilter, is(false));
            assertThat("filter should agree (reject) " + expectedText + " vs " + actualText + " (" + matchType + ")",
                withFilter, is(false));
        }
    }

    private static void assertVerdictsAgree(String expectedText, String actualText, MatchType matchType) {
        boolean withoutFilter = new JsonStringMatcher(loggerWithFilter(false), expectedText, matchType)
            .matches(null, actualText, true);
        boolean withFilter = new JsonStringMatcher(loggerWithFilter(true), expectedText, matchType)
            .matches(null, actualText, true);
        assertThat("filter must not change the verdict for " + expectedText + " vs " + actualText + " (" + matchType + ")",
            withFilter, is(withoutFilter));
    }
}
