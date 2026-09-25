package org.mockserver.model;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.NottableString.headerName;
import static org.mockserver.model.NottableString.string;

public class NottableStringHeaderNameDedupTest {

    @Test
    public void shouldShareOneInstanceForAWellKnownName() {
        // then - the well-known names that dominate real traffic collapse to a single shared wrapper
        assertThat(headerName("Host"), sameInstance(headerName("Host")));
        assertThat(headerName("Content-Type"), sameInstance(headerName("Content-Type")));
        assertThat(headerName("Accept"), sameInstance(headerName("Accept")));
    }

    @Test
    public void shouldShareInstancesForBothWireCasesOfAName() {
        // HTTP/1.1 tends to send canonical case, HTTP/2 lower-cases every name - both must dedup
        assertThat(headerName("Content-Type"), sameInstance(headerName("Content-Type")));
        assertThat(headerName("content-type"), sameInstance(headerName("content-type")));
        // and the two cases are distinct instances so the original case is preserved on the wire
        assertThat(headerName("Content-Type"), not(sameInstance(headerName("content-type"))));
    }

    @Test
    public void shouldPreserveTheExactCaseOfTheName() {
        assertThat(headerName("Content-Type").getValue(), equalTo("Content-Type"));
        assertThat(headerName("content-type").getValue(), equalTo("content-type"));
    }

    @Test
    public void shouldBeObservablyEquivalentToLiteralStringFactory() {
        // headerName(name) must be indistinguishable from string(name, false) for any name
        for (String name : new String[]{"Host", "Content-Type", "X-Not-Well-Known", "!literal-marker", "?literal-marker"}) {
            NottableString viaHeaderName = headerName(name);
            NottableString viaString = string(name, false);
            assertThat(name, viaHeaderName, equalTo(viaString));
            assertThat(name, viaHeaderName.getValue(), equalTo(viaString.getValue()));
            assertThat(name, viaHeaderName.isNot(), equalTo(viaString.isNot()));
            assertThat(name, viaHeaderName.isOptional(), equalTo(viaString.isOptional()));
            assertThat(name, viaHeaderName.toString(), equalTo(viaString.toString()));
            assertThat(name, viaHeaderName.hashCode(), equalTo(viaString.hashCode()));
        }
    }

    @Test
    public void shouldTreatANameBeginningWithAMarkerAsLiteral() {
        // a header genuinely named "!foo" must be recorded verbatim, never read as a negation matcher
        assertThat(headerName("!foo").getValue(), equalTo("!foo"));
        assertThat(headerName("!foo").isNot(), is(false));
        assertThat(headerName("?foo").getValue(), equalTo("?foo"));
        assertThat(headerName("?foo").isOptional(), is(false));
    }

    @Test
    public void shouldNotCacheUnknownNames() {
        // an unknown name allocates a fresh instance every time - so an attacker sending unbounded
        // distinct header names cannot grow any shared structure (the cache is a fixed vocabulary)
        assertThat(headerName("X-Attacker-0001"), not(sameInstance(headerName("X-Attacker-0001"))));

        Set<NottableString> distinct = new HashSet<>();
        Set<Integer> identityHashes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            NottableString wrapper = headerName("X-Attacker-" + i);
            distinct.add(wrapper);
            identityHashes.add(System.identityHashCode(wrapper));
        }
        // every unknown name is its own value and its own object - nothing was pooled
        assertThat(distinct.size(), equalTo(10_000));
        assertThat(identityHashes.size(), greaterThan(9_000));
    }
}
