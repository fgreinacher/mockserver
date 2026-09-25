package org.mockserver.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;

/**
 * Characterises the size-threshold behaviour of {@link KeysToMultiValues}: key grouping switches from a
 * linear scan to a HashMap pass above {@code GROUPING_THRESHOLD}, to keep caller-controlled input (a large
 * form body or query string) off the O(n^2) path. These tests assert the two paths produce IDENTICAL results
 * for the same input — the grouped path (large collections) is compared to an independent reference and, at
 * the boundary, to the linear path (small collections) — and that NOT-key identity survives the grouped path.
 *
 * @author jamesdbloom
 */
public class KeysToMultiValuesGroupingThresholdTest {

    // Interleaved key pattern: keys repeat so the store holds duplicate keys in true insertion order.
    private static Headers interleavedHeaders(int n) {
        Headers headers = new Headers();
        int distinct = Math.max(2, n / 3);
        for (int i = 0; i < n; i++) {
            headers.withEntry("k" + (i % distinct), "v" + i);
        }
        return headers;
    }

    private static LinkedHashMap<String, List<String>> referenceGrouping(int n) {
        LinkedHashMap<String, List<String>> reference = new LinkedHashMap<>();
        int distinct = Math.max(2, n / 3);
        for (int i = 0; i < n; i++) {
            reference.computeIfAbsent("k" + (i % distinct), ignored -> new ArrayList<>()).add("v" + i);
        }
        return reference;
    }

    private static List<String> keySetValues(KeysToMultiValues<?, ?> collection) {
        List<String> keys = new ArrayList<>();
        for (NottableString key : collection.keySet()) {
            keys.add(key.getValue());
        }
        return keys;
    }

    @Test
    public void groupingIsIdenticalForTheSameInputOnBothSidesOfTheThreshold() {
        // 8 and 16 exercise the linear path, 17/40/200 the grouped path; all must match the reference,
        // so the two paths agree.
        for (int n : new int[]{8, 16, 17, 40, 200}) {
            Headers headers = interleavedHeaders(n);
            LinkedHashMap<String, List<String>> reference = referenceGrouping(n);

            assertThat("keySet at n=" + n, keySetValues(headers), is(new ArrayList<>(reference.keySet())));

            List<Header> entries = headers.getEntries();
            List<String> entryNames = new ArrayList<>();
            for (Header entry : entries) {
                entryNames.add(entry.getName().getValue());
                List<String> entryValues = new ArrayList<>();
                for (NottableString value : entry.getValues()) {
                    entryValues.add(value.getValue());
                }
                assertThat("values for " + entry.getName().getValue() + " at n=" + n,
                    entryValues, is(reference.get(entry.getName().getValue())));
            }
            assertThat("entry names at n=" + n, entryNames, is(new ArrayList<>(reference.keySet())));

            for (Map.Entry<String, List<String>> expected : reference.entrySet()) {
                assertThat("getValues(" + expected.getKey() + ") at n=" + n,
                    headers.getValues(expected.getKey()), is(expected.getValue()));
            }
            String firstKey = reference.keySet().iterator().next();
            assertThat("getFirstValue at n=" + n, headers.getFirstValue(firstKey),
                is(reference.get(firstKey).get(0)));
            assertThat("getFirstValue(absent) at n=" + n, headers.getFirstValue("absent"), is(""));
        }
    }

    @Test
    public void groupedPathPreservesNotKeyIdentityInLargeCollection() {
        // above the threshold the grouped path uses a HashMap keyed by the same hashCode-then-equals identity,
        // so a NOT key and an equal-but-differently-hashed plain key stay distinct (as in the linear path).
        Parameters parameters = new Parameters();
        for (int i = 0; i < 20; i++) {
            parameters.withEntry("filler" + i, "f" + i);
        }
        parameters.withEntry(not("secret"), string("1"));
        parameters.withEntry(string("other"), string("2"));
        assertThat(parameters.getEntries().size(), is(22));
        assertThat(new ArrayList<>(parameters.getValues(not("secret"))), is(java.util.Collections.singletonList(string("1"))));
        assertThat(new ArrayList<>(parameters.getValues(string("other"))), is(java.util.Collections.singletonList(string("2"))));
    }

    @Test
    public void equalityAndHashCodeAgreeOnBothSidesOfTheThreshold() {
        // linear path (small): cross-key order does not affect equality or hashCode
        Headers smallA = new Headers();
        smallA.withEntry("a", "1");
        smallA.withEntry("b", "2");
        Headers smallB = new Headers();
        smallB.withEntry("b", "2");
        smallB.withEntry("a", "1");
        assertThat(smallA.equals(smallB), is(true));
        assertThat(smallA.hashCode() == smallB.hashCode(), is(true));

        // grouped path (large): same property holds, and a differing value breaks equality
        int distinct = 30;
        Headers largeForward = new Headers();
        for (int i = 0; i < distinct; i++) {
            largeForward.withEntry("k" + i, "v" + i);
        }
        Headers largeReversed = new Headers();
        for (int i = distinct - 1; i >= 0; i--) {
            largeReversed.withEntry("k" + i, "v" + i);
        }
        assertThat(largeForward.equals(largeReversed), is(true));
        assertThat(largeForward.hashCode() == largeReversed.hashCode(), is(true));

        Headers largeDifferent = new Headers();
        for (int i = 0; i < distinct; i++) {
            largeDifferent.withEntry("k" + i, i == 15 ? "CHANGED" : "v" + i);
        }
        assertThat(largeForward.equals(largeDifferent), is(false));
    }
}
