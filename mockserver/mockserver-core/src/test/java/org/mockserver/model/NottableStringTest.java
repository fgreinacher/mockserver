package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.mockserver.model.NottableString.string;

public class NottableStringTest {

    @Test
    public void shouldReturnValuesSetInConstructors() {
        // when
        NottableString nottableString = NottableString.not("value");

        // then
        assertThat(nottableString.isNot(), is(true));
        assertThat(nottableString.getValue(), is("value"));
    }

    @Test
    public void shouldReturnValuesSetInConstructorsWithDefaultNotSetting() {
        // when
        NottableString nottableString = string("value");

        // then
        assertThat(nottableString.isNot(), is(false));
        assertThat(nottableString.getValue(), is("value"));
    }

    @Test
    public void shouldReturnValuesSetInConstructorsWithNullNotParameter() {
        // when
        NottableString nottableString = NottableString.string("value", null);

        // then
        assertThat(nottableString.isNot(), is(false));
        assertThat(nottableString.getValue(), is("value"));
    }

    @Test
    public void hashCodeMatchesObjectsHashArithmetic() {
        String[] values = {null, "", "value", "!value", "www.mock-server.com", "a"};
        Boolean[] nots = {null, Boolean.FALSE, Boolean.TRUE};
        for (String value : values) {
            for (Boolean not : nots) {
                boolean normalisedNot = not != null && not;
                int expected = java.util.Objects.hash(value, normalisedNot);
                assertThat("value=" + value + " not=" + not, string(value, not).hashCode(), is(expected));
            }
        }
    }

    @Test
    public void shouldParseOptionalOnlyPrefixWithoutThrowing() {
        // when
        NottableString nottableString = string("?");

        // then
        assertThat(nottableString.isOptional(), is(true));
        assertThat(nottableString.isNot(), is(false));
        assertThat(nottableString.getValue(), is(""));
    }

    @Test
    public void shouldParseNotOnlyPrefixWithoutThrowing() {
        // when
        NottableString nottableString = string("!");

        // then
        assertThat(nottableString.isOptional(), is(false));
        assertThat(nottableString.isNot(), is(true));
        assertThat(nottableString.getValue(), is(""));
    }

    @Test
    public void shouldParseOptionalThenNotPrefixWithoutThrowing() {
        // when
        NottableString nottableString = string("?!");

        // then
        assertThat(nottableString.isOptional(), is(true));
        assertThat(nottableString.isNot(), is(true));
        assertThat(nottableString.getValue(), is(""));
    }

    @Test
    public void shouldParseNotThenOptionalPrefixWithoutThrowing() {
        // when
        NottableString nottableString = string("!?");

        // then
        assertThat(nottableString.isOptional(), is(true));
        assertThat(nottableString.isNot(), is(true));
        assertThat(nottableString.getValue(), is(""));
    }

    @Test
    public void shouldEqual() {
        assertThat(string("value"), is(string("value")));
        assertThat(NottableString.not("value"), is(NottableString.not("value")));
        assertThat(string("value"), is((Object) "value"));
    }

    @Test
    public void shouldEqualIgnoreCase() {
        assertThat(string("value").equalsIgnoreCase(string("VALUE")), is(true));
        assertThat(NottableString.not("value").equalsIgnoreCase(NottableString.not("vaLUe")), is(true));
        assertThat(string("value").equalsIgnoreCase("VaLue"), is(true));
    }

    @Test
    public void shouldEqualWhenNull() {
        assertThat(string(null), is(string(null)));
        assertThat(string("value"), not(string(null)));
        assertThat(string(null), not(string("value")));
    }

    @Test
    public void shouldEqualForDoubleNegative() {
        assertThat(NottableString.not("value"), not(string("value")));
        assertThat(NottableString.not("value"), not((Object) "value"));

        assertThat(string("value"), not(string("other_value")));
        assertThat(NottableString.string("value"), not((Object) "other_value"));

        assertThat(string("value"), not(NottableString.not("value")));
    }

    @Test
    public void shouldEqualForDoubleNegativeIgnoreCase() {
        assertThat(NottableString.not("value").equalsIgnoreCase(string("VAlue")), is(false));
        assertThat(NottableString.not("value").equalsIgnoreCase("vaLUe"), is(false));

        assertThat(string("value").equalsIgnoreCase(string("other_value")), is(false));
        assertThat(NottableString.string("value").equalsIgnoreCase("OTHER_value"), is(false));

        assertThat(string("value").equalsIgnoreCase(NottableString.not("VALUE")), is(false));
    }

    @Test
    public void shouldEqualForNotValueNull() {
        assertThat(string("value", true), is(NottableString.not("value")));
        assertThat(string("value", false), is(string("value")));

        NottableString initiallyTrueValue = NottableString.string("value");
        assertThat(string("value", null), is(initiallyTrueValue));
        assertThat(string("value", null).isNot(), is(false));
    }

    @Test
    public void shouldConvertToString() {
        assertThat(NottableString.not("value").toString(), is("!value"));
        assertThat("" + NottableString.not("value"), is("!value"));
        assertThat(String.valueOf(NottableString.not("value")), is("!value"));

        assertThat(NottableString.string("value").toString(), is("value"));
        assertThat("" + NottableString.string("value"), is("value"));
        assertThat(String.valueOf(NottableString.string("value")), is("value"));
    }

    @Test
    public void shouldMatchCaseInsensitivelyByDefault() {
        // the original matches(String) overload compiles with CASE_INSENSITIVE | UNICODE_CASE
        assertThat(string("Hello.*").matches("hello world"), is(true));
        assertThat(string("Hello.*").matches("Hello world"), is(true));
    }

    @Test
    public void shouldMatchCaseSensitivelyWhenRequested() {
        // the matches(String, true) overload compiles without CASE_INSENSITIVE | UNICODE_CASE
        assertThat(string("Hello.*").matches("Hello world", true), is(true));
        assertThat(string("Hello.*").matches("hello world", true), is(false));
    }

    @Test
    public void shouldNotThrowForNullValueWhenMatchingCaseSensitively() {
        // a null value can never compile as a regex; the case-sensitive path must return false, not NPE
        assertThat(string(null).matches("anything", true), is(false));
    }

    @Test
    public void shouldCacheCaseSensitiveAndCaseInsensitivePatternsIndependently() {
        // the same NottableString can be used both ways within one instance
        NottableString value = string("Hello.*");
        assertThat(value.matches("hello world"), is(true));
        assertThat(value.matches("hello world", true), is(false));
        assertThat(value.matches("Hello world", true), is(true));
        assertThat(value.matches("HELLO world"), is(true));
    }

}
