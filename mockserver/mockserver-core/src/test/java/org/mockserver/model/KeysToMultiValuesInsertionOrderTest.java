package org.mockserver.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.Parameter.param;

/**
 * Characterises the GLOBAL cross-key insertion ordering that the {@link com.google.common.collect.LinkedListMultimap}
 * backing {@link KeysToMultiValues} preserves. This is the differential corpus for any replacement of the internal
 * structure: a faithful replacement preserves true insertion order across interleaved duplicate keys, a key-grouping
 * structure (e.g. {@code ArrayListMultimap}) does not.
 *
 * @author jamesdbloom
 */
public class KeysToMultiValuesInsertionOrderTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private static List<String> entriesAsPairs(KeysToMultiValues<?, ?> collection) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<NottableString, NottableString> entry : collection.getMultimap().entries()) {
            pairs.add(entry.getKey().getValue() + "=" + (entry.getValue() == null ? "null" : entry.getValue().getValue()));
        }
        return pairs;
    }

    private static List<String> keySetValues(KeysToMultiValues<?, ?> collection) {
        List<String> keys = new ArrayList<>();
        for (NottableString key : collection.keySet()) {
            keys.add(key.getValue());
        }
        return keys;
    }

    private static List<String> entryNames(List<? extends KeyToMultiValue> entries) {
        List<String> names = new ArrayList<>();
        for (KeyToMultiValue entry : entries) {
            names.add(entry.getName().getValue());
        }
        return names;
    }

    private Headers interleaved() {
        Headers headers = new Headers();
        headers.withEntry("Host", "h");
        headers.withEntry("Set-Cookie", "c1");
        headers.withEntry("Accept", "a");
        headers.withEntry("Set-Cookie", "c2");
        headers.withEntry("Host", "h2");
        return headers;
    }

    @Test
    public void multimapEntriesArePreservedInTrueGlobalInsertionOrder() {
        // this is THE differential assertion — LinkedListMultimap keeps the interleaved order,
        // a key-grouping multimap would collapse it to [Host=h, Host=h2, Set-Cookie=c1, Set-Cookie=c2, Accept=a]
        assertThat(entriesAsPairs(interleaved()), is(java.util.Arrays.asList(
            "Host=h",
            "Set-Cookie=c1",
            "Accept=a",
            "Set-Cookie=c2",
            "Host=h2"
        )));
    }

    @Test
    public void keySetIsInFirstOccurrenceOrderOfDistinctKeys() {
        assertThat(keySetValues(interleaved()), is(java.util.Arrays.asList(
            "Host",
            "Set-Cookie",
            "Accept"
        )));
    }

    @Test
    public void getEntriesGroupsByKeyInFirstOccurrenceOrder() {
        Headers headers = interleaved();
        List<Header> entries = headers.getEntries();
        assertThat(entryNames(entries), is(java.util.Arrays.asList(
            "Host",
            "Set-Cookie",
            "Accept"
        )));
        // grouped: the two Host values and the two Set-Cookie values collapse onto their single key,
        // each preserving per-key value order
        assertThat(entries, is(java.util.Arrays.asList(
            header("Host", "h", "h2"),
            header("Set-Cookie", "c1", "c2"),
            header("Accept", "a")
        )));
    }

    @Test
    public void parametersPreserveTheSameGlobalInsertionOrder() {
        Parameters parameters = new Parameters();
        parameters.withEntry("Host", "h");
        parameters.withEntry("Set-Cookie", "c1");
        parameters.withEntry("Accept", "a");
        parameters.withEntry("Set-Cookie", "c2");
        parameters.withEntry("Host", "h2");
        assertThat(entriesAsPairs(parameters), is(java.util.Arrays.asList(
            "Host=h",
            "Set-Cookie=c1",
            "Accept=a",
            "Set-Cookie=c2",
            "Host=h2"
        )));
        assertThat(keySetValues(parameters), is(java.util.Arrays.asList("Host", "Set-Cookie", "Accept")));
        assertThat(parameters.getEntries(), is(java.util.Arrays.asList(
            param("Host", "h", "h2"),
            param("Set-Cookie", "c1", "c2"),
            param("Accept", "a")
        )));
    }

    @Test
    public void manyInterleavedKeysPreserveEveryPairPosition() {
        Headers headers = new Headers();
        headers.withEntry("A", "1");
        headers.withEntry("B", "2");
        headers.withEntry("A", "3");
        headers.withEntry("C", "4");
        headers.withEntry("B", "5");
        headers.withEntry("A", "6");
        headers.withEntry("D", "7");
        headers.withEntry("C", "8");
        assertThat(entriesAsPairs(headers), is(java.util.Arrays.asList(
            "A=1", "B=2", "A=3", "C=4", "B=5", "A=6", "D=7", "C=8"
        )));
        assertThat(keySetValues(headers), is(java.util.Arrays.asList("A", "B", "C", "D")));
        assertThat(headers.getValues("A"), is(java.util.Arrays.asList("1", "3", "6")));
        assertThat(headers.getValues("B"), is(java.util.Arrays.asList("2", "5")));
        assertThat(headers.getValues("C"), is(java.util.Arrays.asList("4", "8")));
    }

    @Test
    public void keySetPreservesFirstOccurrenceOrderIndependentOfKeyHashOrder() {
        // Zebra/Apple/Mango deliberately do NOT hash in insertion order, so this pins first-occurrence
        // ordering without leaning on a mutation test or on a coincidental hash order.
        Headers headers = new Headers();
        headers.withEntry("Zebra", "1");
        headers.withEntry("Apple", "2");
        headers.withEntry("Mango", "3");
        assertThat(keySetValues(headers), is(java.util.Arrays.asList("Zebra", "Apple", "Mango")));
    }

    @Test
    public void removeOfMidListKeyPreservesGlobalOrderOfSurvivors() {
        // guards against a swap-remove replacement: removing B from [A,B,C,D] must yield [A,C,D],
        // NOT [A,D,C] (which arr[i]=arr[--size] would produce)
        Headers headers = new Headers();
        headers.withEntry("A", "1");
        headers.withEntry("B", "2");
        headers.withEntry("C", "3");
        headers.withEntry("D", "4");
        headers.remove("B");
        assertThat(entriesAsPairs(headers), is(java.util.Arrays.asList("A=1", "C=3", "D=4")));
    }

    @Test
    public void removeOfInterleavedMultiOccurrenceKeyPreservesGlobalOrderOfSurvivors() {
        Headers headers = new Headers();
        headers.withEntry("A", "1");
        headers.withEntry("B", "2");
        headers.withEntry("A", "3");
        headers.withEntry("C", "4");
        headers.withEntry("B", "5");
        headers.withEntry("A", "6");
        headers.withEntry("D", "7");
        headers.withEntry("C", "8");
        headers.remove("A");
        assertThat(entriesAsPairs(headers), is(java.util.Arrays.asList("B=2", "C=4", "B=5", "D=7", "C=8")));
    }

    @Test
    public void replaceOfInterleavedMultiOccurrenceKeyPreservesSurvivorsAndAppendsOnce() {
        Headers headers = new Headers();
        headers.withEntry("A", "1");
        headers.withEntry("B", "2");
        headers.withEntry("A", "3");
        headers.withEntry("C", "4");
        headers.withEntry("B", "5");
        headers.withEntry("A", "6");
        headers.withEntry("D", "7");
        headers.withEntry("C", "8");
        headers.replaceEntry(header("A", "X"));
        assertThat(entriesAsPairs(headers), is(java.util.Arrays.asList("B=2", "C=4", "B=5", "D=7", "C=8", "A=X")));
        assertThat(keySetValues(headers), is(java.util.Arrays.asList("B", "C", "D", "A")));
    }

    @Test
    public void parametersRemoveOfMidListKeyPreservesGlobalOrderOfSurvivors() {
        Parameters parameters = new Parameters();
        parameters.withEntry("A", "1");
        parameters.withEntry("B", "2");
        parameters.withEntry("C", "3");
        parameters.withEntry("D", "4");
        parameters.remove("B");
        assertThat(entriesAsPairs(parameters), is(java.util.Arrays.asList("A=1", "C=3", "D=4")));
    }

    @Test
    public void parametersRemoveOfInterleavedMultiOccurrenceKeyPreservesGlobalOrderOfSurvivors() {
        Parameters parameters = new Parameters();
        parameters.withEntry("A", "1");
        parameters.withEntry("B", "2");
        parameters.withEntry("A", "3");
        parameters.withEntry("C", "4");
        parameters.withEntry("B", "5");
        parameters.withEntry("A", "6");
        parameters.withEntry("D", "7");
        parameters.withEntry("C", "8");
        parameters.remove("A");
        assertThat(entriesAsPairs(parameters), is(java.util.Arrays.asList("B=2", "C=4", "B=5", "D=7", "C=8")));
    }

    @Test
    public void parametersReplaceOfInterleavedMultiOccurrenceKeyPreservesSurvivorsAndAppendsOnce() {
        Parameters parameters = new Parameters();
        parameters.withEntry("A", "1");
        parameters.withEntry("B", "2");
        parameters.withEntry("A", "3");
        parameters.withEntry("C", "4");
        parameters.withEntry("B", "5");
        parameters.withEntry("A", "6");
        parameters.withEntry("D", "7");
        parameters.withEntry("C", "8");
        parameters.replaceEntry(param("A", "X"));
        assertThat(entriesAsPairs(parameters), is(java.util.Arrays.asList("B=2", "C=4", "B=5", "D=7", "C=8", "A=X")));
        assertThat(keySetValues(parameters), is(java.util.Arrays.asList("B", "C", "D", "A")));
    }

    @Test
    public void serializedJsonIsSortedDescendingByKeyNotInsertionOrder() throws Exception {
        // Surprising but current reality: KeysToMultiValuesSerializer SORTS keys, and
        // NottableString.compareTo is reversed, so JSON field order is DESCENDING by key and
        // independent of insertion order. This is therefore NOT a differential signal for the
        // internal-structure change; it is pinned here so a replacement does not accidentally
        // change the serialized shape.
        assertThat(objectMapper.writeValueAsString(interleaved()),
            is("{\"Set-Cookie\":[\"c1\",\"c2\"],\"Host\":[\"h\",\"h2\"],\"Accept\":[\"a\"]}"));
    }
}
