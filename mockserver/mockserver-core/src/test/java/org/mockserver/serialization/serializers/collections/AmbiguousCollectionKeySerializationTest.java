package org.mockserver.serialization.serializers.collections;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.Test;
import org.mockserver.model.Cookies;
import org.mockserver.model.Headers;
import org.mockserver.model.Parameters;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.IOException;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.Cookie.cookie;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.Parameter.param;

/**
 * A collection KEY that literally begins with a marker character must survive serialisation.
 *
 * <p>Keys go on the wire as JSON <b>field names</b>, and a field name has no object form — so the
 * plain-string encoding is the only one available, and it prefixes {@code !} for negation and
 * {@code ?} for optional. A header genuinely named {@code !foo} therefore serialises to the field
 * name {@code "!foo"}, which the reader strips back to "NOT foo": the expectation is re-read as the
 * exact inverse of what was asked, matching every request that does <i>not</i> carry that header.
 * {@code !} is a legal {@code tchar} in RFC 7230, so such a name is not hypothetical.
 *
 * <p>Read-back of the corrupted form is byte-identical, so no round-trip or client-fidelity test
 * can observe this — the corruption is in the server's <i>interpretation</i> of the name, not in the
 * bytes. These tests assert the emitted shape directly for that reason.
 *
 * <p><b>Both directions are asserted.</b> Checking only that a literal key is preserved would pass
 * against a fix that emitted the array form unconditionally; checking only that ordinary keys still
 * use the map form would pass against no fix at all. Every case asserts the negated/optional
 * counterpart still reaches the compact map form unchanged.
 */
public class AmbiguousCollectionKeySerializationTest {

    private final ObjectWriter writer = ObjectMapperFactory.createObjectMapper(true, false);
    private final ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();

    // ---- headers ----

    @Test
    public void shouldNotEmitLiteralNegationMarkerAsAHeaderFieldName() throws IOException {
        String json = writer.writeValueAsString(new Headers(
            header(string("!foo", false), string("bar"))
        ));

        assertThat("a literal '!foo' name must not be written as a field name the reader negates",
            json, not(containsString("\"!foo\" :")));
        assertThat(json, containsString("\"value\" : \"!foo\""));
        assertThat("the object form must state the intent explicitly", json, containsString("\"not\" : false"));
    }

    @Test
    public void shouldNotEmitLiteralOptionalMarkerAsAHeaderFieldName() throws IOException {
        String json = writer.writeValueAsString(new Headers(
            header(string("?opt", false), string("bar"))
        ));

        assertThat(json, not(containsString("\"?opt\" :")));
        assertThat(json, containsString("\"value\" : \"?opt\""));
    }

    @Test
    public void shouldStillEmitAGenuinelyNegatedHeaderNameInTheCompactForm() throws IOException {
        // the positive direction: a real negation is unambiguous as a field name and must not
        // be pushed into the array form, or every existing expectation changes shape
        String json = writer.writeValueAsString(new Headers(
            header(org.mockserver.model.NottableString.not("foo"), string("bar"))
        ));

        assertThat(json, containsString("\"!foo\""));
        assertThat("an ordinary negated name must keep the map form", json, not(containsString("\"name\"")));
    }

    // ---- query parameters ----

    @Test
    public void shouldNotEmitLiteralNegationMarkerAsAParameterFieldName() throws IOException {
        String json = writer.writeValueAsString(new Parameters(
            param(string("!q", false), string("1"))
        ));

        assertThat(json, not(containsString("\"!q\" :")));
        assertThat(json, containsString("\"value\" : \"!q\""));
    }

    @Test
    public void shouldNotEmitLiteralOptionalMarkerAsAParameterFieldName() throws IOException {
        String json = writer.writeValueAsString(new Parameters(
            param(string("?q", false), string("1"))
        ));

        assertThat(json, not(containsString("\"?q\" :")));
        assertThat(json, containsString("\"value\" : \"?q\""));
    }

    @Test
    public void shouldStillEmitAGenuinelyNegatedParameterNameInTheCompactForm() throws IOException {
        String json = writer.writeValueAsString(new Parameters(
            param(org.mockserver.model.NottableString.not("q"), string("1"))
        ));

        assertThat(json, containsString("\"!q\""));
        assertThat(json, not(containsString("\"name\"")));
    }

    // ---- cookies ----

    @Test
    public void shouldNotEmitLiteralNegationMarkerAsACookieFieldName() throws IOException {
        String json = writer.writeValueAsString(new Cookies(
            cookie(string("!c", false), string("1"))
        ));

        assertThat(json, not(containsString("\"!c\" :")));
        assertThat(json, containsString("\"value\" : \"!c\""));
    }

    @Test
    public void shouldNotEmitLiteralOptionalMarkerAsACookieFieldName() throws IOException {
        String json = writer.writeValueAsString(new Cookies(
            cookie(string("?c", false), string("1"))
        ));

        assertThat(json, not(containsString("\"?c\" :")));
        assertThat(json, containsString("\"value\" : \"?c\""));
    }

    @Test
    public void shouldStillEmitAGenuinelyNegatedCookieNameInTheCompactForm() throws IOException {
        String json = writer.writeValueAsString(new Cookies(
            cookie(org.mockserver.model.NottableString.not("c"), string("1"))
        ));

        assertThat(json, containsString("\"!c\""));
        assertThat(json, not(containsString("\"name\"")));
    }

    // ---- interaction with the value-side fix (92cfde4e8) ----

    @Test
    public void shouldPreserveAnAmbiguousNameAndAnAmbiguousValueTogether() throws IOException {
        // the array branch must carry BOTH: its `values` were typed as plain strings in the schema
        // while the map branch already allowed the object form, so a collection ambiguous on both
        // sides would have regressed the value-side fix the moment the shape switched
        String json = writer.writeValueAsString(new Headers(
            header(string("!hdr", false), string("!val", false))
        ));

        assertThat(json, containsString("\"value\" : \"!hdr\""));
        assertThat(json, containsString("\"value\" : \"!val\""));
        assertThat(json, not(containsString("\"!hdr\" :")));
    }

    // ---- round-trip ----

    @Test
    public void shouldRoundTripALiteralMarkerNameThroughTheSerialisedForm() throws IOException {
        Headers original = new Headers(header(string("!foo", false), string("bar")));

        Headers reread = mapper.readValue(writer.writeValueAsString(original), Headers.class);

        assertThat("the re-read name must still be the literal '!foo'",
            reread.getEntries().get(0).getName().getValue(), is("!foo"));
        assertThat("and must NOT have become a negation",
            reread.getEntries().get(0).getName().isNot(), is(false));
    }

    @Test
    public void shouldRoundTripALiteralMarkerCookieNameThroughTheSerialisedForm() throws IOException {
        // Cookies have their OWN serialiser and deserialiser, and the cookie array reader had to be
        // taught the object form separately — the multi-value reader already understood it. Without
        // a cookie-specific round-trip this half of the fix is unguarded: serialisation alone would
        // still look correct while the value could not be read back.
        Cookies original = new Cookies(cookie(string("!c", false), string("1")));

        Cookies reread = mapper.readValue(writer.writeValueAsString(original), Cookies.class);

        assertThat("the re-read cookie name must still be the literal '!c'",
            reread.getEntries().get(0).getName().getValue(), is("!c"));
        assertThat("and must NOT have become a negation",
            reread.getEntries().get(0).getName().isNot(), is(false));
        assertThat(reread.getEntries().get(0).getValue().getValue(), is("1"));
    }

    @Test
    public void shouldRoundTripMultipleCookiesWhenOneNameIsAmbiguous() throws IOException {
        // guards the array-item boundary: the reader tracks the current field name across items,
        // so a stale name would make the second item's opening brace read as an object-form value
        Cookies original = new Cookies(
            cookie(string("!c", false), string("1")),
            cookie(string("plain"), string("2"))
        );

        Cookies reread = mapper.readValue(writer.writeValueAsString(original), Cookies.class);

        assertThat(reread.getEntries().size(), is(2));
    }

    @Test
    public void shouldRoundTripAGenuineNegationUnchanged() throws IOException {
        Headers original = new Headers(header(org.mockserver.model.NottableString.not("foo"), string("bar")));

        Headers reread = mapper.readValue(writer.writeValueAsString(original), Headers.class);

        assertThat(reread.getEntries().get(0).getName().getValue(), is("foo"));
        assertThat(reread.getEntries().get(0).getName().isNot(), is(true));
    }

    // ---- the schema must ACCEPT what the serialiser now emits ----
    //
    // Serialisation and validation fail independently: the serialiser can emit a perfectly good
    // array form that the JSON schema then rejects at the control plane, so an expectation
    // round-trips in-process but is refused over HTTP with a 400. These assert the schema half.

    @Test
    public void schemaShouldAcceptAnObjectFormNameInAHeaderArray() {
        assertThat(validationErrorsFor("{"
            + "\"httpRequest\":{\"path\":\"/p\",\"headers\":["
            + "{\"name\":{\"not\":false,\"value\":\"!foo\"},\"values\":[\"bar\"]}]},"
            + "\"httpResponse\":{\"statusCode\":200}}"), is(""));
    }

    @Test
    public void schemaShouldAcceptAnObjectFormNameAndValueTogetherInAHeaderArray() {
        // the values-side widening: the array branch previously typed `values` as a bare string
        // while the map branch already allowed the object form, so a collection ambiguous on both
        // sides would be rejected the moment the serialiser switched shape
        assertThat(validationErrorsFor("{"
            + "\"httpRequest\":{\"path\":\"/p\",\"headers\":["
            + "{\"name\":{\"not\":false,\"value\":\"!hdr\"},\"values\":[{\"not\":false,\"value\":\"!val\"}]}]},"
            + "\"httpResponse\":{\"statusCode\":200}}"), is(""));
    }

    @Test
    public void schemaShouldAcceptAnObjectFormNameInACookieArray() {
        assertThat(validationErrorsFor("{"
            + "\"httpRequest\":{\"path\":\"/p\",\"cookies\":["
            + "{\"name\":{\"not\":false,\"value\":\"!c\"},\"value\":\"1\"}]},"
            + "\"httpResponse\":{\"statusCode\":200}}"), is(""));
    }

    @Test
    public void schemaShouldStillAcceptThePlainStringArrayForm() {
        assertThat(validationErrorsFor("{"
            + "\"httpRequest\":{\"path\":\"/p\",\"headers\":[{\"name\":\"plain\",\"values\":[\"v\"]}]},"
            + "\"httpResponse\":{\"statusCode\":200}}"), is(""));
    }

    private String validationErrorsFor(String expectationJson) {
        return org.mockserver.validator.jsonschema.JsonSchemaExpectationValidator
            .jsonSchemaExpectationValidator(new org.mockserver.logging.MockServerLogger())
            .isValid(expectationJson);
    }

    @Test
    public void shouldLeaveAnOrdinaryCollectionByteIdentical() throws IOException {
        // the guarantee that makes this safe to ship: nothing that was already correct changes
        String json = writer.writeValueAsString(new Headers(
            header(string("some_name"), Collections.singletonList(string("some_value")))
        ));

        assertThat(json, is("{" + org.mockserver.character.Character.NEW_LINE
            + "  \"some_name\" : [ \"some_value\" ]" + org.mockserver.character.Character.NEW_LINE
            + "}"));
    }
}
