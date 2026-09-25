package org.mockserver.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.Cookie.cookie;
import static org.mockserver.model.NottableString.string;

/**
 * Characterises {@link Cookies}, which extends the distinct MAP-based {@link KeysAndValues} base (a
 * {@link java.util.LinkedHashMap} of single values), NOT {@link KeysToMultiValues}. It is therefore unaffected by
 * a flat-array replacement of the KeysToMultiValues internal structure, but is pinned here because {@code build} is
 * abstract per subclass and its semantics (single value per key, last-write-wins, case-sensitive remove) differ from
 * the multi-value collections.
 *
 * @author jamesdbloom
 */
public class KeysAndValuesCharacterisationTest {

    private static List<String> keyValues(Cookies cookies) {
        List<String> out = new ArrayList<>();
        cookies.getMap().forEach((k, v) -> out.add(k.getValue() + "=" + v.getValue()));
        return out;
    }

    @Test
    public void buildProducesACookie() {
        Cookies cookies = new Cookies();
        assertThat(cookies.build(string("name"), string("value")), is(new Cookie(string("name"), string("value"))));
    }

    @Test
    public void keysAreHeldInInsertionOrder() {
        Cookies cookies = new Cookies();
        cookies.withEntry("z", "1");
        cookies.withEntry("a", "2");
        cookies.withEntry("m", "3");
        assertThat(keyValues(cookies), is(Arrays.asList("z=1", "a=2", "m=3")));
    }

    @Test
    public void duplicateKeyIsLastWriteWinsSingleValued() {
        Cookies cookies = new Cookies();
        cookies.withEntry("session", "first");
        cookies.withEntry("session", "second");
        assertThat(cookies.getEntries().size(), is(1));
        assertThat(keyValues(cookies), is(Collections.singletonList("session=second")));
    }

    @Test
    public void removeIsCaseSensitive() {
        Cookies cookies = new Cookies();
        cookies.withEntry("Session", "x");
        assertThat(cookies.remove("session"), is(false));
        assertThat(cookies.remove("Session"), is(true));
        assertThat(cookies.isEmpty(), is(true));
    }

    @Test
    public void removeOfBlankNameReturnsFalse() {
        Cookies cookies = new Cookies();
        cookies.withEntry("a", "1");
        assertThat(cookies.remove(""), is(false));
        assertThat(cookies.remove("  "), is(false));
        assertThat(cookies.isEmpty(), is(false));
    }

    @Test
    public void withEntriesClearsExistingEntriesFirst() {
        Cookies cookies = new Cookies();
        cookies.withEntry("a", "1");
        cookies.withEntries(cookie("b", "2"));
        assertThat(keyValues(cookies), is(Collections.singletonList("b=2")));
    }

    @Test
    public void replaceEntryIfExistsReplacesOnlyWhenPresent() {
        Cookies cookies = new Cookies();
        cookies.withEntry("a", "1");
        cookies.replaceEntryIfExists(cookie("a", "X"));
        cookies.replaceEntryIfExists(cookie("z", "Z"));
        assertThat(keyValues(cookies), is(Collections.singletonList("a=X")));
    }

    @Test
    public void cloneIsEqualButIndependent() {
        Cookies original = new Cookies();
        original.withEntry("a", "1");
        Cookies copy = original.clone();
        assertThat(original.equals(copy), is(true));
        copy.withEntry("b", "2");
        assertThat(keyValues(original), is(Collections.singletonList("a=1")));
        assertThat(keyValues(copy), is(Arrays.asList("a=1", "b=2")));
    }

    @Test
    public void convertedMatcherCacheIsClearedOnMutation() {
        Cookies cookies = new Cookies();
        cookies.setConvertedMatcher(false, "data");
        cookies.setConvertedMatcher(true, "control");
        cookies.withEntry("a", "1");
        assertThat(cookies.getConvertedMatcher(false), is(nullValue()));
        assertThat(cookies.getConvertedMatcher(true), is(nullValue()));
    }

    @Test
    public void isEmptyReflectsContents() {
        Cookies cookies = new Cookies();
        assertThat(cookies.isEmpty(), is(true));
        cookies.withEntry("a", "1");
        assertThat(cookies.isEmpty(), is(false));
    }
}
