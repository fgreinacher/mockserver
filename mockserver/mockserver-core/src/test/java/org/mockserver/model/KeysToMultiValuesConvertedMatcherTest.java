package org.mockserver.model;

import org.junit.Test;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.NottableString.string;

/**
 * Characterises the memoized request-side conversion cache on {@link KeysToMultiValues}
 * ({@code getConvertedMatcher} / {@code setConvertedMatcher}). Per the optimisation-safety hazard-class
 * guidance, a cache must be tested for its INVALIDATION path, not only for hits: every mutation must clear
 * the cached value. Both the control-plane (true) and data-plane (false) slots are covered. This is the one
 * sanctioned implementation-coupled test, because the behaviour is inherently about internal caching.
 *
 * @author jamesdbloom
 */
public class KeysToMultiValuesConvertedMatcherTest {

    @Test
    public void cacheReturnsNullBeforeAnythingIsStored() {
        Headers headers = new Headers();
        assertThat(headers.getConvertedMatcher(false), is(nullValue()));
        assertThat(headers.getConvertedMatcher(true), is(nullValue()));
    }

    @Test
    public void storedValueIsReturnedForTheMatchingControlPlaneFlag() {
        Headers headers = new Headers();
        headers.setConvertedMatcher(false, "data-plane");
        headers.setConvertedMatcher(true, "control-plane");
        assertThat(headers.getConvertedMatcher(false), is("data-plane"));
        assertThat(headers.getConvertedMatcher(true), is("control-plane"));
    }

    @Test
    public void theTwoSlotsAreIndependent() {
        Headers headers = new Headers();
        headers.setConvertedMatcher(false, "only-data");
        assertThat(headers.getConvertedMatcher(false), is("only-data"));
        assertThat(headers.getConvertedMatcher(true), is(nullValue()));
    }

    @Test
    public void withEntryClearsBothCacheSlots() {
        Headers headers = new Headers();
        headers.setConvertedMatcher(false, "data");
        headers.setConvertedMatcher(true, "control");
        headers.withEntry("a", "1");
        assertThat(headers.getConvertedMatcher(false), is(nullValue()));
        assertThat(headers.getConvertedMatcher(true), is(nullValue()));
    }

    @Test
    public void withEntryHeaderClearsCache() {
        Headers headers = new Headers();
        headers.setConvertedMatcher(false, "data");
        headers.withEntry(header("a", "1"));
        assertThat(headers.getConvertedMatcher(false), is(nullValue()));
    }

    @Test
    public void withEntriesClearsCache() {
        Headers headers = new Headers();
        headers.setConvertedMatcher(true, "control");
        headers.withEntries(header("a", "1"));
        assertThat(headers.getConvertedMatcher(true), is(nullValue()));
    }

    @Test
    public void removeClearsCache() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.setConvertedMatcher(false, "data");
        headers.remove("a");
        assertThat(headers.getConvertedMatcher(false), is(nullValue()));
    }

    @Test
    public void replaceEntryClearsCache() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.setConvertedMatcher(false, "data");
        headers.replaceEntry(header("a", "2"));
        assertThat(headers.getConvertedMatcher(false), is(nullValue()));
    }

    @Test
    public void replaceEntryIfExistsClearsCache() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.setConvertedMatcher(true, "control");
        headers.replaceEntryIfExists(header("a", "2"));
        assertThat(headers.getConvertedMatcher(true), is(nullValue()));
    }

    @Test
    public void withKeyMatchStyleClearsCache() {
        // withKeyMatchStyle changes matching behaviour, so it invalidates the cache like any other mutation.
        Headers headers = new Headers();
        headers.setConvertedMatcher(false, "data");
        headers.withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
        assertThat(headers.getConvertedMatcher(false), is(nullValue()));
    }

    @Test
    public void parametersWithEntryClearsCacheAndRawParameterString() {
        Parameters parameters = new Parameters();
        parameters.withRawParameterString("a=1");
        parameters.setConvertedMatcher(false, "data");
        parameters.withEntry("a", "1");
        assertThat(parameters.getConvertedMatcher(false), is(nullValue()));
        assertThat(parameters.getRawParameterString(), is(nullValue()));
    }

    @Test
    public void buildForEmptyValuesDoesNotTouchCache() {
        // build is a pure factory; sanity that it does not mutate cache state
        Headers headers = new Headers();
        headers.setConvertedMatcher(false, "data");
        headers.build(string("x"), Collections.<NottableString>emptyList());
        assertThat(headers.getConvertedMatcher(false), is("data"));
    }
}
