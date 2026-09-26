package org.mockserver.collections;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.RegexStringMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.mockserver.collections.SubSetMatcher.containsSubset;
import static org.mockserver.model.NottableSchemaString.schemaString;
import static org.mockserver.model.NottableString.string;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
public class SubSetMatcherTest {

    static RegexStringMatcher regexStringMatcher = new RegexStringMatcher(new MockServerLogger(), false);

    @Test
    public void shouldContainSubsetForEmpty() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Collections.emptyList(),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForMultiValuesAllMatching() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, "one", "one_two"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, "one", "one_two"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForMultiValuesSubset() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, "one", "one_two"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForNottedKey() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "!two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "notTwo", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForNottedValue() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "!two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "notTwo"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForNottedMultiValue() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, "one", "!one_two"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, "one", "notOne_two"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForSubsetWithPresentOptional() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three"),
                new ImmutableEntry(regexStringMatcher, "four", "four")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForSubsetWithNotPresentOptional() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForOptionalWrongValue() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three"),
                new ImmutableEntry(regexStringMatcher, "four", "wrong")
            ))), is(false));
    }

    @Test
    public void shouldContainSubsetForOptionalWrongValueInReverse() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three"),
                new ImmutableEntry(regexStringMatcher, "?four", "wrong")
            ))), is(false));
    }

    @Test
    public void shouldContainSubsetForExtraValue() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four"),
                new ImmutableEntry(regexStringMatcher, "five", "five")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three"),
                new ImmutableEntry(regexStringMatcher, "four", "four")
            ))), is(false));
    }

    @Test
    public void shouldContainSubsetForWrongValue() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "wrong"),
                new ImmutableEntry(regexStringMatcher, "three", "three"),
                new ImmutableEntry(regexStringMatcher, "four", "four")
            ))), is(false));
    }

    @Test
    public void shouldContainSubsetForWrongKey() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "wrong", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three"),
                new ImmutableEntry(regexStringMatcher, "four", "four")
            ))), is(false));
    }

    @Test
    public void shouldContainSubsetForSchemaKey() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, schemaString("{\"type\": \"string\", \"pattern\": \"tw.{1}\"}"), string("two"))
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForSchemaValue() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, string("two"), schemaString("{\"type\": \"string\", \"pattern\": \"tw.{1}\"}"))
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForSchemaMultiValue() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, string("one"), schemaString("{\"type\": \"string\", \"pattern\": \"one_t.*\"}")),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, "one", "one_two"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetForSchemaKeyAndMultiValue() {
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, schemaString("{\"type\": \"string\", \"pattern\": \"o.*\"}"), string("one_one")),
                new ImmutableEntry(regexStringMatcher, schemaString("{\"type\": \"string\", \"pattern\": \"o.*\"}"), string("one_two")),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, "one", "one_two"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(true));
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, schemaString("{\"type\": \"string\", \"pattern\": \"o.*\"}"), string("one_one")),
                new ImmutableEntry(regexStringMatcher, schemaString("{\"type\": \"string\", \"pattern\": \"o.*\"}"), string("one_two")),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "?four", "four")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one_one"),
                new ImmutableEntry(regexStringMatcher, "two", "two"),
                new ImmutableEntry(regexStringMatcher, "three", "three")
            ))), is(false));
    }

    @Test
    public void shouldContainSubsetWithPositiveAndNottedKeys() {
        // Issue #1974: notted parameters should not count toward required matches
        // Expectation: name=John AND NOT age
        // Request: name=John (no age parameter)
        // Should match because: 1 positive match (name) >= 1 required (name), and age is not present
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John"),
                new ImmutableEntry(regexStringMatcher, "!age", ".*")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetWithMultiplePositiveAndNottedKeys() {
        // Expectation: name=John AND city=London AND NOT age AND NOT country
        // Request: name=John, city=London (no age or country)
        // Should match because: 2 positive matches >= 2 required, and age/country not present
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John"),
                new ImmutableEntry(regexStringMatcher, "city", "London"),
                new ImmutableEntry(regexStringMatcher, "!age", ".*"),
                new ImmutableEntry(regexStringMatcher, "!country", ".*")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John"),
                new ImmutableEntry(regexStringMatcher, "city", "London")
            ))), is(true));
    }

    @Test
    public void shouldContainSubsetWithOptionalAndNottedKeys() {
        // Expectation: name=John AND ?age AND NOT country
        // Request: name=John (no age or country)
        // Should match: 1 positive match >= 1 required, age optional, country not present
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John"),
                new ImmutableEntry(regexStringMatcher, "?age", ".*"),
                new ImmutableEntry(regexStringMatcher, "!country", ".*")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John")
            ))), is(true));
    }

    @Test
    public void shouldNotContainSubsetWhenNottedKeyIsPresent() {
        // Expectation: name=John AND NOT age
        // Request: name=John, age=25
        // Should NOT match because age is present (notted key found)
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John"),
                new ImmutableEntry(regexStringMatcher, "!age", ".*")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John"),
                new ImmutableEntry(regexStringMatcher, "age", "25")
            ))), is(false));
    }

    // ---- distinct-superset-index rule: a single superset entry may not satisfy two subset entries ----

    @Test
    public void shouldNotContainSubsetWhenTwoRequiredEntriesMatchSameSingleSupersetEntry() {
        // Two REQUIRED subset entries both match the ONE superset entry (one -> one). The distinct-index
        // rule requires two DISTINCT superset entries to satisfy two required subset entries, so this
        // must NOT match. (Guards the count comparison; without it this would falsely match.)
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "o.*"),
                new ImmutableEntry(regexStringMatcher, "one", ".*e")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one")
            ))), is(false));
    }

    @Test
    public void shouldContainSubsetWhenTwoRequiredEntriesMatchTwoDistinctSupersetEntries() {
        // The control for the case above: the SAME two required subset entries DO match when the superset
        // has two distinct entries to satisfy them, so distinct matched indexes (2) >= required (2).
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "o.*"),
                new ImmutableEntry(regexStringMatcher, "one", ".*e")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "one", "one"),
                new ImmutableEntry(regexStringMatcher, "one", "ore")
            ))), is(true));
    }

    @Test
    public void shouldApplyDistinctIndexRuleOnlyToRequiredEntriesWhenNottedAndOptionalAlsoMatchSameEntry() {
        // A notted and an optional subset entry can also match superset entries, but only the REQUIRED
        // entry count gates the distinct comparison. Here the single required entry (name -> John) matches
        // the single superset entry; ?nick is optional-and-not-present and !age is notted-and-not-present,
        // so neither is required and the match succeeds with one distinct matched index.
        assertThat(containsSubset(null, null, regexStringMatcher,
            Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John"),
                new ImmutableEntry(regexStringMatcher, "?nick", ".*"),
                new ImmutableEntry(regexStringMatcher, "!age", ".*")
            ),
            new ArrayList<>(Arrays.asList(
                new ImmutableEntry(regexStringMatcher, "name", "John")
            ))), is(true));
    }

}