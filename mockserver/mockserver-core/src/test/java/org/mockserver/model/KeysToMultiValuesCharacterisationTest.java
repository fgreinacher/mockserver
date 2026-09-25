package org.mockserver.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;

/**
 * Characterises the observable behaviour of {@link KeysToMultiValues} through {@link Headers}: case sensitivity of
 * key lookup versus storage, duplicate handling, the ordering effect of every mutation method, null/empty/marker
 * handling, and equality/clone semantics. Assertions capture CURRENT reality, including surprises; suspected bugs
 * are annotated in the test and reported separately, never "corrected" here.
 *
 * @author jamesdbloom
 */
public class KeysToMultiValuesCharacterisationTest {

    private static List<String> keySetValues(KeysToMultiValues<?, ?> collection) {
        List<String> keys = new ArrayList<>();
        for (NottableString key : collection.keySet()) {
            keys.add(key.getValue());
        }
        return keys;
    }

    private static List<String> pairs(KeysToMultiValues<?, ?> collection) {
        List<String> out = new ArrayList<>();
        collection.getMultimap().entries().forEach(e ->
            out.add(e.getKey().getValue() + "=" + (e.getValue() == null ? "null" : e.getValue().getValue())));
        return out;
    }

    // ---- case sensitivity ----------------------------------------------------------------------

    @Test
    public void distinctlyCasedKeysAreStoredAsSeparateEntries() {
        Headers headers = new Headers();
        headers.withEntry("Host", "a");
        headers.withEntry("host", "b");
        assertThat(headers.keySet().size(), is(2));
        assertThat(keySetValues(headers), is(Arrays.asList("Host", "host")));
        assertThat(headers.getEntries().size(), is(2));
    }

    @Test
    public void getValuesByStringIsCaseInsensitiveAndSpansAllCasedKeys() {
        Headers headers = new Headers();
        headers.withEntry("Host", "a");
        headers.withEntry("host", "b");
        assertThat(headers.getValues("HOST"), is(Arrays.asList("a", "b")));
        assertThat(headers.getValues("Host"), is(Arrays.asList("a", "b")));
    }

    @Test
    public void getValuesByNottableStringIsCaseSensitiveExactKeyLookup() {
        Headers headers = new Headers();
        headers.withEntry("Host", "a");
        headers.withEntry("host", "b");
        assertThat(new ArrayList<>(headers.getValues(string("Host"))), is(Collections.singletonList(string("a"))));
        assertThat(new ArrayList<>(headers.getValues(string("host"))), is(Collections.singletonList(string("b"))));
        assertThat(headers.getValues(string("HOST")).isEmpty(), is(true));
    }

    @Test
    public void containsEntryByKeyIsCaseInsensitive() {
        Headers headers = new Headers();
        headers.withEntry("Content-Type", "text/plain");
        assertThat(headers.containsEntry("content-type"), is(true));
        assertThat(headers.containsEntry("CONTENT-TYPE"), is(true));
        assertThat(headers.containsEntry("content-length"), is(false));
    }

    @Test
    public void containsEntryByKeyAndValueIsCaseInsensitiveOnBoth() {
        Headers headers = new Headers();
        headers.withEntry("Accept", "Text/Plain");
        assertThat(headers.containsEntry("accept", "text/plain"), is(true));
        assertThat(headers.containsEntry("ACCEPT", "TEXT/PLAIN"), is(true));
        assertThat(headers.containsEntry("accept", "text/html"), is(false));
    }

    @Test
    public void removeByStringIsCaseInsensitiveAndRemovesEveryCasedKey() {
        Headers headers = new Headers();
        headers.withEntry("Host", "a");
        headers.withEntry("host", "b");
        headers.withEntry("Other", "c");
        assertThat(headers.remove("HOST"), is(true));
        assertThat(keySetValues(headers), is(Collections.singletonList("Other")));
        assertThat(headers.remove("absent"), is(false));
    }

    @Test
    public void removeByNottableStringIsCaseInsensitive() {
        Headers headers = new Headers();
        headers.withEntry("Host", "a");
        assertThat(headers.remove(string("HOST")), is(true));
        assertThat(headers.isEmpty(), is(true));
    }

    // ---- duplicates ----------------------------------------------------------------------------

    @Test
    public void identicalKeyValuePairAddedTwiceIsNotDeduplicated() {
        Headers headers = new Headers();
        headers.withEntry(header("Set-Cookie", "a=1"));
        headers.withEntry(header("Set-Cookie", "a=1"));
        assertThat(pairs(headers), is(Arrays.asList("Set-Cookie=a=1", "Set-Cookie=a=1")));
        assertThat(headers.getValues("Set-Cookie"), is(Arrays.asList("a=1", "a=1")));
        assertThat(headers.getEntries(), is(Collections.singletonList(header("Set-Cookie", "a=1", "a=1"))));
    }

    // ---- mutation ordering ---------------------------------------------------------------------

    @Test
    public void replaceEntryMovesTheReplacedKeyToTheEnd() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.withEntry("b", "2");
        headers.withEntry("c", "3");
        headers.replaceEntry(header("b", "new"));
        assertThat(keySetValues(headers), is(Arrays.asList("a", "c", "b")));
        assertThat(pairs(headers), is(Arrays.asList("a=1", "c=3", "b=new")));
    }

    @Test
    public void replaceEntryWithStringsMovesTheReplacedKeyToTheEnd() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.withEntry("b", "2");
        headers.replaceEntry("a", "new");
        assertThat(keySetValues(headers), is(Arrays.asList("b", "a")));
    }

    @Test
    public void replaceEntryIfExistsReplacesAndMovesToEndOnlyWhenPresent() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.withEntry("b", "2");
        headers.replaceEntryIfExists(header("a", "X"));
        headers.replaceEntryIfExists(header("z", "Z"));
        assertThat(pairs(headers), is(Arrays.asList("b=2", "a=X")));
        assertThat(headers.containsEntry("z"), is(false));
    }

    @Test
    public void withEntriesVarargsClearsExistingEntriesFirst() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.withEntries(header("b", "2"));
        assertThat(pairs(headers), is(Collections.singletonList("b=2")));
    }

    @Test
    public void withEntriesMapClearsExistingEntriesFirst() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("b", Collections.singletonList("2"));
        headers.withEntries(map);
        assertThat(pairs(headers), is(Collections.singletonList("b=2")));
    }

    @Test
    public void withEntriesNullListClearsExistingEntries() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.withEntries((List<Header>) null);
        assertThat(headers.isEmpty(), is(true));
    }

    // ---- null / empty / marker handling --------------------------------------------------------

    @Test
    public void withEntryEmptyStringVarargsStoresASingleEmptyValue() {
        Headers headers = new Headers();
        headers.withEntry("n");
        assertThat(pairs(headers), is(Collections.singletonList("n=")));
        assertThat(headers.getValues("n"), is(Collections.singletonList("")));
    }

    @Test
    public void withEntryNullNottableListIsANoOp() {
        Headers headers = new Headers();
        headers.withEntry(string("n"), (List<NottableString>) null);
        assertThat(headers.isEmpty(), is(true));
    }

    @Test
    public void withEntryEmptyNottableListIsANoOp() {
        Headers headers = new Headers();
        headers.withEntry(string("n"), Collections.<NottableString>emptyList());
        assertThat(headers.isEmpty(), is(true));
    }

    @Test
    public void withEntryNullStringListStoresANullValued_SUSPECTED_BUG() {
        // SUSPECTED BUG: withEntry(String, List) with a null/empty list stores a literal null value
        // (multimap.put(name, null)). getValues(String) then throws NPE while serialising the null.
        // Pinning current reality; do not "fix" here.
        Headers headers = new Headers();
        headers.withEntry("n", (List<String>) null);
        assertThat(pairs(headers), is(Collections.singletonList("n=null")));
        assertThrows(NullPointerException.class, () -> headers.getValues("n"));
    }

    @Test
    public void withEntryParsesLeadingNotAndOptionalMarkersInStringNameAndValue() {
        Headers headers = new Headers();
        headers.withEntry("!foo", "?bar");
        List<NottableString> keys = new ArrayList<>(headers.keySet());
        assertThat(keys.get(0).getValue(), is("foo"));
        assertThat(keys.get(0).isNot(), is(true));
        List<NottableString> values = new ArrayList<>(headers.getValues(string("!foo")));
        assertThat(values.get(0).getValue(), is("bar"));
        assertThat(values.get(0).isOptional(), is(true));
    }

    @Test
    public void withEntryNottableStringNameDoesNotReparseMarkers() {
        Headers headers = new Headers();
        headers.withEntry(not("foo"), string("bar"));
        List<NottableString> keys = new ArrayList<>(headers.keySet());
        assertThat(keys.get(0).getValue(), is("foo"));
        assertThat(keys.get(0).isNot(), is(true));
    }

    @Test
    public void removeWithNullNameReturnsFalse() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        assertThat(headers.remove((String) null), is(false));
        assertThat(headers.remove((NottableString) null), is(false));
        assertThat(headers.isEmpty(), is(false));
    }

    @Test
    public void nullArgumentsToLookupsAreToleratedAsNonMatches() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        assertThat(headers.containsEntry(null), is(false));
        assertThat(headers.containsEntry("a", null), is(false));
        assertThat(headers.containsEntry(null, "1"), is(false));
        assertThat(headers.getValues((String) null).isEmpty(), is(true));
        assertThat(headers.getValues((NottableString) null).isEmpty(), is(true));
    }

    @Test
    public void nullEntryArgumentsToMutatorsAreNoOps() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.withEntry((Header) null);
        headers.replaceEntry((Header) null);
        headers.replaceEntryIfExists((Header) null);
        assertThat(pairs(headers), is(Collections.singletonList("a=1")));
    }

    // ---- isEmpty / getFirstValue ---------------------------------------------------------------

    @Test
    public void isEmptyReflectsPresenceOfEntries() {
        Headers headers = new Headers();
        assertThat(headers.isEmpty(), is(true));
        headers.withEntry("a", "1");
        assertThat(headers.isEmpty(), is(false));
        headers.remove("a");
        assertThat(headers.isEmpty(), is(true));
    }

    // ---- getFirstValue -------------------------------------------------------------------------

    @Test
    public void getFirstValueReturnsTheFirstValueOfAMatchedKey() {
        Headers headers = new Headers();
        headers.withEntry("Accept", "a1", "a2");
        assertThat(headers.getFirstValue("Accept"), is("a1"));
    }

    @Test
    public void getFirstValueReturnsEmptyStringWhenKeyAbsent() {
        Headers headers = new Headers();
        headers.withEntry("Accept", "a1");
        assertThat(headers.getFirstValue("absent"), is(""));
    }

    @Test
    public void getFirstValueIsCaseInsensitiveInKeySelection() {
        Headers headers = new Headers();
        headers.withEntry("Content-Type", "text/plain");
        assertThat(headers.getFirstValue("CONTENT-TYPE"), is("text/plain"));
    }

    @Test
    public void getFirstValueReturnsFirstOccurrenceKeyAmongDifferentlyCasedKeys() {
        Headers headers = new Headers();
        headers.withEntry("Host", "h1");
        headers.withEntry("host", "h2");
        // the first-occurrence key wins regardless of the case queried
        assertThat(headers.getFirstValue("HOST"), is("h1"));
        assertThat(headers.getFirstValue("host"), is("h1"));
    }

    @Test
    public void getFirstValueReturnsEmptyStringWhenMatchedKeyHasANullFirstValue() {
        // SUSPECTED BUG surface: when the only matched key's first value is null, getFirstValue skips
        // it (does not return the value) and, finding no further matching key, returns "" rather than
        // surfacing the null. Pinned as current reality.
        Headers headers = new Headers();
        headers.withEntry("n", (List<String>) null);
        assertThat(headers.getFirstValue("n"), is(""));
    }

    @Test
    public void getFirstValueSkipsANullFirstValueKeyAndContinuesToTheNextMatchingKey() {
        // when a matched key has a null first value, the scan continues to the next case-insensitively
        // matching key and returns its value
        Headers headers = new Headers();
        headers.withEntry("n", (List<String>) null);
        headers.withEntry("N", "real");
        assertThat(headers.getFirstValue("n"), is("real"));
    }

    // ---- equals / hashCode ---------------------------------------------------------------------

    @Test
    public void equalityIgnoresCrossKeyInsertionOrder() {
        Headers a = new Headers();
        a.withEntry("x", "1");
        a.withEntry("y", "2");
        Headers b = new Headers();
        b.withEntry("y", "2");
        b.withEntry("x", "1");
        assertThat(a.equals(b), is(true));
        assertThat(a.hashCode() == b.hashCode(), is(true));
    }

    @Test
    public void equalityIsSensitiveToPerKeyValueOrder() {
        Headers a = new Headers();
        a.withEntry("x", "1", "2");
        Headers b = new Headers();
        b.withEntry("x", "2", "1");
        assertThat(a.equals(b), is(false));
    }

    @Test
    public void equalityIsSensitiveToKeyCase() {
        Headers a = new Headers();
        a.withEntry("Host", "1");
        Headers b = new Headers();
        b.withEntry("host", "1");
        assertThat(a.equals(b), is(false));
    }

    @Test
    public void notEqualToNullOrOtherType() {
        Headers headers = new Headers();
        headers.withEntry("x", "1");
        assertThat(headers.equals(null), is(false));
        assertThat(headers.equals("x"), is(false));
    }

    // ---- clone ---------------------------------------------------------------------------------

    @Test
    public void cloneIsEqualButIndependentOfTheOriginal() {
        Headers original = new Headers();
        original.withEntry("a", "1");
        Headers copy = original.clone();
        assertThat(original.equals(copy), is(true));
        assertThat(original != copy, is(true));

        copy.withEntry("b", "2");
        assertThat(pairs(original), is(Collections.singletonList("a=1")));
        assertThat(pairs(copy), is(Arrays.asList("a=1", "b=2")));
        assertThat(original.equals(copy), is(false));
    }

    @Test
    public void mutatingOriginalDoesNotAffectClone() {
        Headers original = new Headers();
        original.withEntry("a", "1");
        Headers copy = original.clone();
        original.remove("a");
        assertThat(original.isEmpty(), is(true));
        assertThat(pairs(copy), is(Collections.singletonList("a=1")));
    }

    // ---- keyMatchStyle -------------------------------------------------------------------------

    @Test
    public void keyMatchStyleDefaultsToSubSetAndIsRetained() {
        Headers headers = new Headers();
        assertThat(headers.getKeyMatchStyle(), is(KeyMatchStyle.SUB_SET));
        headers.withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
        assertThat(headers.getKeyMatchStyle(), is(KeyMatchStyle.MATCHING_KEY));
    }

    // ---- build ---------------------------------------------------------------------------------

    @Test
    public void buildProducesConcreteEntryOfTheSubclassType() {
        Headers headers = new Headers();
        Header built = headers.build(string("name"), Arrays.asList(string("v1"), string("v2")));
        assertThat(built, is(new Header(string("name"), string("v1"), string("v2"))));
    }

    @Test
    public void buildWithEmptyValuesDefaultsToMatchAllRegex() {
        // KeyToMultiValue substitutes ".*" when constructed with no values
        Headers headers = new Headers();
        Header built = headers.build(string("name"), Collections.<NottableString>emptyList());
        assertThat(built.getValues(), is(Collections.singletonList(string(".*"))));
    }
}
