package org.mockserver.serialization.java;

import org.junit.Test;

import static org.mockserver.model.NottableString.string;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class NottableStringToJavaSerializerTest {

    @Test
    public void shouldSerializeNottedString() {
        assertThat(
            NottableStringToJavaSerializer.serialize(string("some_value", true), false)
        , is("not(\"some_value\")"));
    }

    @Test
    public void shouldSerializeString() {
        assertThat(
            NottableStringToJavaSerializer.serialize(string("some_value", false), false)
        , is("\"some_value\""));
    }

    /**
     * A bare Java string literal here would be re-read by {@code string(String)} when the generated
     * code runs, so "is !some_value" would compile into "is NOT some_value" — the generated code
     * would mean the opposite of the expectation it was generated from. The two-argument form takes
     * the value verbatim.
     */
    @Test
    public void shouldSerializeLiteralMarkerCharactersUsingTheExplicitNotArgument() {
        assertThat(
            NottableStringToJavaSerializer.serialize(string("!some_value", false), false)
        , is("string(\"!some_value\", false)"));
        assertThat(
            NottableStringToJavaSerializer.serialize(string("?some_value", false), false)
        , is("string(\"?some_value\", false)"));
    }

    /** The negated direction already round-trips as {@code not("!some_value")}. */
    @Test
    public void shouldStillSerializeNegatedLiteralMarkerCharactersAsNot() {
        assertThat(
            NottableStringToJavaSerializer.serialize(string("!some_value", true), false)
        , is("not(\"!some_value\")"));
    }

}
