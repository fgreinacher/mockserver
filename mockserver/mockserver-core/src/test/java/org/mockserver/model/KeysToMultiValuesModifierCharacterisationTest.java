package org.mockserver.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HeadersModifier.headersModifier;
import static org.mockserver.model.Parameter.param;
import static org.mockserver.model.QueryParametersModifier.queryParametersModifier;

/**
 * Characterises {@link KeysToMultiValuesModifier} (via {@link HeadersModifier} and {@link QueryParametersModifier})
 * add/replace/remove semantics as applied by {@code update}, including the fixed order (replace, then add, then
 * remove) and the null-target behaviour.
 *
 * @author jamesdbloom
 */
public class KeysToMultiValuesModifierCharacterisationTest {

    private static List<String> pairs(KeysToMultiValues<?, ?> collection) {
        List<String> out = new ArrayList<>();
        collection.getMultimap().entries().forEach(e ->
            out.add(e.getKey().getValue() + "=" + (e.getValue() == null ? "null" : e.getValue().getValue())));
        return out;
    }

    @Test
    public void addAppendsNewEntries() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        HeadersModifier modifier = headersModifier().add(header("b", "2"));
        modifier.update(headers);
        assertThat(pairs(headers), is(Arrays.asList("a=1", "b=2")));
    }

    @Test
    public void addWithExistingKeyAppendsAdditionalValueRatherThanReplacing() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headersModifier().add(header("a", "2")).update(headers);
        assertThat(headers.getValues("a"), is(Arrays.asList("1", "2")));
    }

    @Test
    public void replaceOnlyAffectsKeysThatAlreadyExist() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.withEntry("b", "2");
        headersModifier()
            .withReplace(new Headers(header("a", "X"), header("absent", "Y")))
            .update(headers);
        assertThat(headers.getValues("a"), is(Collections.singletonList("X")));
        assertThat(headers.containsEntry("absent"), is(false));
    }

    @Test
    public void removeDeletesNamedKeys() {
        Headers headers = new Headers();
        headers.withEntry("a", "1");
        headers.withEntry("b", "2");
        headersModifier().remove("a").update(headers);
        assertThat(pairs(headers), is(Collections.singletonList("b=2")));
    }

    @Test
    public void updateAppliesReplaceThenAddThenRemove() {
        Headers headers = new Headers();
        headers.withEntry("keep", "1");
        headers.withEntry("changeme", "old");
        headers.withEntry("dropme", "x");
        headersModifier()
            .withReplace(new Headers(header("changeme", "new")))
            .withAdd(new Headers(header("added", "z")))
            .withRemove(Collections.singletonList("dropme"))
            .update(headers);
        assertThat(headers.getValues("changeme"), is(Collections.singletonList("new")));
        assertThat(headers.getValues("added"), is(Collections.singletonList("z")));
        assertThat(headers.containsEntry("dropme"), is(false));
        assertThat(headers.containsEntry("keep"), is(true));
    }

    @Test
    public void updateOnNullTargetReturnsCloneOfAdditions() {
        HeadersModifier modifier = headersModifier().add(header("a", "1"));
        Headers result = modifier.update(null);
        assertThat(result, is(new Headers(header("a", "1"))));
    }

    @Test
    public void queryParametersModifierAppliesTheSameSemantics() {
        Parameters parameters = new Parameters();
        parameters.withEntry("keep", "1");
        parameters.withEntry("changeme", "old");
        parameters.withEntry("dropme", "x");
        queryParametersModifier()
            .withReplace(new Parameters(param("changeme", "new")))
            .withAdd(new Parameters(param("added", "z")))
            .withRemove(Collections.singletonList("dropme"))
            .update(parameters);
        assertThat(parameters.getValues("changeme"), is(Collections.singletonList("new")));
        assertThat(parameters.getValues("added"), is(Collections.singletonList("z")));
        assertThat(parameters.containsEntry("dropme"), is(false));
    }

    @Test
    public void modifierRemoveOfMidListKeyPreservesGlobalOrderOfSurvivors() {
        Headers headers = new Headers();
        headers.withEntry("A", "1");
        headers.withEntry("B", "2");
        headers.withEntry("C", "3");
        headers.withEntry("D", "4");
        headersModifier().remove("B").update(headers);
        assertThat(pairs(headers), is(Arrays.asList("A=1", "C=3", "D=4")));
    }

    @Test
    public void modifierReplaceOfInterleavedKeyPreservesSurvivorsAndAppendsOnce() {
        Headers headers = new Headers();
        headers.withEntry("A", "1");
        headers.withEntry("B", "2");
        headers.withEntry("A", "3");
        headers.withEntry("C", "4");
        headers.withEntry("B", "5");
        headers.withEntry("A", "6");
        headers.withEntry("D", "7");
        headers.withEntry("C", "8");
        headersModifier().withReplace(new Headers(header("A", "X"))).update(headers);
        assertThat(pairs(headers), is(Arrays.asList("B=2", "C=4", "B=5", "D=7", "C=8", "A=X")));
    }

    @Test
    public void queryParametersModifierRemoveOfMidListKeyPreservesGlobalOrderOfSurvivors() {
        Parameters parameters = new Parameters();
        parameters.withEntry("A", "1");
        parameters.withEntry("B", "2");
        parameters.withEntry("C", "3");
        parameters.withEntry("D", "4");
        queryParametersModifier().remove("B").update(parameters);
        assertThat(pairs(parameters), is(Arrays.asList("A=1", "C=3", "D=4")));
    }

    @Test
    public void queryParametersModifierReplaceOfInterleavedKeyPreservesSurvivorsAndAppendsOnce() {
        Parameters parameters = new Parameters();
        parameters.withEntry("A", "1");
        parameters.withEntry("B", "2");
        parameters.withEntry("A", "3");
        parameters.withEntry("C", "4");
        parameters.withEntry("B", "5");
        parameters.withEntry("A", "6");
        parameters.withEntry("D", "7");
        parameters.withEntry("C", "8");
        queryParametersModifier().withReplace(new Parameters(param("A", "X"))).update(parameters);
        assertThat(pairs(parameters), is(Arrays.asList("B=2", "C=4", "B=5", "D=7", "C=8", "A=X")));
    }

    @Test
    public void modifierEqualityAndHashCodeTrackAddReplaceRemove() {
        HeadersModifier a = headersModifier().add(header("a", "1")).remove("b");
        HeadersModifier b = headersModifier().add(header("a", "1")).remove("b");
        assertThat(a.equals(b), is(true));
        assertThat(a.hashCode() == b.hashCode(), is(true));
        HeadersModifier c = headersModifier().add(header("a", "2")).remove("b");
        assertThat(a.equals(c), is(false));
    }
}
