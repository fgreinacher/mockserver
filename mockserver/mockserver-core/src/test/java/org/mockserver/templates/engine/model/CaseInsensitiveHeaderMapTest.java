package org.mockserver.templates.engine.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;

public class CaseInsensitiveHeaderMapTest {

    private static List<String> values(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    @Test
    public void shouldResolveEveryCasingCombination() {
        // issue #2575: HTTP field names are case-insensitive (RFC 9110), and the casing that reaches
        // this map is protocol-dependent (HTTP/2 lower-cases on the wire, HTTP/1.1 does not), so all
        // four combinations of stored-casing x looked-up-casing must resolve.
        CaseInsensitiveHeaderMap upperCaseStored = new CaseInsensitiveHeaderMap();
        upperCaseStored.put("Host", values("mock-server.com"));
        assertThat(upperCaseStored.get("host"), is(values("mock-server.com")));
        assertThat(upperCaseStored.get("Host"), is(values("mock-server.com")));
        assertThat(upperCaseStored.get("HOST"), is(values("mock-server.com")));

        CaseInsensitiveHeaderMap lowerCaseStored = new CaseInsensitiveHeaderMap();
        lowerCaseStored.put("host", values("mock-server.com"));
        assertThat(lowerCaseStored.get("Host"), is(values("mock-server.com")));
        assertThat(lowerCaseStored.get("host"), is(values("mock-server.com")));
    }

    @Test
    public void shouldReportContainsKeyAndGetOrDefaultCaseInsensitively() {
        // Mustache's fetcher calls containsKey before get, so containsKey must agree with get.
        CaseInsensitiveHeaderMap headers = new CaseInsensitiveHeaderMap();
        headers.put("Host", values("mock-server.com"));

        assertThat(headers.containsKey("host"), is(true));
        assertThat(headers.containsKey("Host"), is(true));
        assertThat(headers.containsKey("not-a-header"), is(false));
        assertThat(headers.getOrDefault("host", values("fallback")), is(values("mock-server.com")));
        assertThat(headers.getOrDefault("not-a-header", values("fallback")), is(values("fallback")));
    }

    @Test
    public void shouldReturnNullForAbsentKeyRatherThanMatchingLoosely() {
        CaseInsensitiveHeaderMap headers = new CaseInsensitiveHeaderMap();
        headers.put("Host", values("mock-server.com"));

        assertThat(headers.get("hos"), is(nullValue()));
        assertThat(headers.get("hostname"), is(nullValue()));
        assertThat(headers.get(null), is(nullValue()));
        assertThat(headers.get(1), is(nullValue()));
        assertThat(headers.containsKey(1), is(false));
    }

    @Test
    public void shouldPreserveInsertionOrderAndOriginalCasing() {
        // loop-over-headers templates and Jackson serialisation both iterate the views, which must
        // show the wire casing in arrival order — the reason this extends LinkedHashMap rather than
        // being a TreeMap with a case-insensitive comparator, which would reorder alphabetically.
        CaseInsensitiveHeaderMap headers = new CaseInsensitiveHeaderMap();
        headers.put("Host", values("mock-server.com"));
        headers.put("Accept", values("text/plain"));
        headers.put("x-custom", values("value"));

        assertThat(new ArrayList<>(headers.keySet()), contains("Host", "Accept", "x-custom"));
    }

    @Test
    public void shouldMergeValuesWhenSameNameArrivesWithDifferentCasing() {
        // HTTP combines same-name fields, so the second spelling must not silently overwrite the
        // first; the first-seen casing and position stay canonical.
        CaseInsensitiveHeaderMap headers = new CaseInsensitiveHeaderMap();
        headers.put("Host", values("first.example.com"));
        headers.put("Accept", values("text/plain"));
        headers.put("host", values("second.example.com"));

        assertThat(headers.get("host"), is(values("first.example.com", "second.example.com")));
        assertThat(headers.get("Host"), is(values("first.example.com", "second.example.com")));
        assertThat(headers.size(), is(2));
        assertThat(new ArrayList<>(headers.keySet()), contains("Host", "Accept"));
    }

    @Test
    public void shouldOverwriteRatherThanMergeWhenTheSameCasingIsPutTwice() {
        // an exact-case re-put is an ordinary Map replacement, not a same-name-field combination
        CaseInsensitiveHeaderMap headers = new CaseInsensitiveHeaderMap();
        headers.put("Host", values("first.example.com"));
        List<String> previous = headers.put("Host", values("second.example.com"));

        assertThat(previous, is(values("first.example.com")));
        assertThat(headers.get("Host"), is(values("second.example.com")));
    }

    @Test
    public void shouldRemoveByAnyCasing() {
        CaseInsensitiveHeaderMap headers = new CaseInsensitiveHeaderMap();
        headers.put("Host", values("mock-server.com"));

        assertThat(headers.remove("HOST"), is(values("mock-server.com")));
        assertThat(headers.containsKey("host"), is(false));
        assertThat(headers.get("Host"), is(nullValue()));
        assertThat(headers.isEmpty(), is(true));
        assertThat(headers.remove("Host"), is(nullValue()));
    }

    @Test
    public void shouldStayCaseInsensitiveAfterMutationThatBypassesPut() {
        // HashMap routes putAll / putIfAbsent / merge / compute* at its internal storage without
        // calling the overridden put, and the keySet view mutates it directly, so case-insensitive
        // resolution must be derived from the live entries rather than a side index that these
        // paths would leave stale. This map is public and Velocity's Uberspector will invoke exactly
        // these public methods from a template expression.
        CaseInsensitiveHeaderMap viaPutAll = new CaseInsensitiveHeaderMap();
        viaPutAll.putAll(Collections.singletonMap("Host", values("mock-server.com")));
        assertThat(viaPutAll.get("host"), is(values("mock-server.com")));
        assertThat(viaPutAll.containsKey("host"), is(true));

        CaseInsensitiveHeaderMap viaPutIfAbsent = new CaseInsensitiveHeaderMap();
        viaPutIfAbsent.putIfAbsent("Host", values("mock-server.com"));
        assertThat(viaPutIfAbsent.get("host"), is(values("mock-server.com")));

        CaseInsensitiveHeaderMap viaMerge = new CaseInsensitiveHeaderMap();
        viaMerge.merge("Host", values("mock-server.com"), (existing, replacement) -> replacement);
        assertThat(viaMerge.get("host"), is(values("mock-server.com")));

        CaseInsensitiveHeaderMap viaComputeIfAbsent = new CaseInsensitiveHeaderMap();
        viaComputeIfAbsent.computeIfAbsent("Host", name -> values("mock-server.com"));
        assertThat(viaComputeIfAbsent.get("host"), is(values("mock-server.com")));

        CaseInsensitiveHeaderMap removedThroughView = new CaseInsensitiveHeaderMap();
        removedThroughView.put("Host", values("mock-server.com"));
        removedThroughView.keySet().remove("Host");
        assertThat(removedThroughView.containsKey("host"), is(false));
        assertThat(removedThroughView.get("host"), is(nullValue()));
    }

    @Test
    public void shouldBehaveAsAnOrdinaryMapForEqualityAndClearing() {
        CaseInsensitiveHeaderMap headers = new CaseInsensitiveHeaderMap();
        headers.put("Host", values("mock-server.com"));

        Map<String, List<String>> equivalent = new LinkedHashMap<>();
        equivalent.put("Host", values("mock-server.com"));
        assertThat(headers, is(equivalent));

        headers.clear();
        assertThat(headers.isEmpty(), is(true));
        assertThat(headers.containsKey("host"), is(false));
    }
}
