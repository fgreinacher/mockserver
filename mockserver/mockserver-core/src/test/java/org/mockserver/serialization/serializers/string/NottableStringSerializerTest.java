package org.mockserver.serialization.serializers.string;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Test;
import org.mockserver.model.NottableOptionalString;
import org.mockserver.model.NottableString;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.NottableOptionalString.optional;
import static org.mockserver.model.NottableSchemaString.schemaString;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.ParameterStyle.MATRIX;

public class NottableStringSerializerTest {

    @Test
    public void shouldSerializeObjectWithNottableString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new Object() {
                public NottableString getValue() {
                    return string("some_string");
                }
            }),
            is("{\"value\":\"some_string\"}"));
    }

    @Test
    public void shouldSerializeObjectWithStyledString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new Object() {
                public NottableString getValue() {
                    return string("some_string").withStyle(MATRIX);
                }
            }),
            is("{\"value\":{\"parameterStyle\":\"MATRIX\",\"value\":\"some_string\"}}"));
    }

    @Test
    public void shouldSerializeObjectWithNottedString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new Object() {
                public NottableString getValue() {
                    return NottableString.not("some_string");
                }
            }),
            is("{\"value\":\"!some_string\"}"));
    }

    @Test
    public void shouldSerializeObjectWithOptionalString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new Object() {
                public NottableString getValue() {
                    return optional("some_string");
                }
            }),
            is("{\"value\":\"?some_string\"}"));
    }

    @Test
    public void shouldSerializeObjectWithSchemaString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new Object() {
                public NottableString getValue() {
                    return schemaString("{\"type\":\"string\"}");
                }
            }),
            is("{\"value\":{\"schema\":{\"type\":\"string\"}}}"));
    }

    @Test
    public void shouldSerializeObjectWithNottedSchemaString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new Object() {
                public NottableString getValue() {
                    return schemaString("{\"type\":\"string\"}", true);
                }
            }),
            is("{\"value\":{\"not\":true,\"schema\":{\"type\":\"string\"}}}"));
    }

    @Test
    public void shouldSerializeNottableString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(string("some_string")),
            is("\"some_string\""));
    }

    @Test
    public void shouldSerializeNottedStringWithNot() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(string("some_string", true)),
            is("\"!some_string\""));
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(string("!some_string")),
            is("\"!some_string\""));
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(org.mockserver.model.NottableString.not("some_string")),
            is("\"!some_string\""));
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(string("!some_string", true)),
            is("\"!!some_string\""));
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(org.mockserver.model.NottableString.not("!some_string")),
            is("\"!!some_string\""));
    }

    /**
     * A NOT-negated value whose first character is the negation marker cannot be represented as a
     * bare JSON string: the receiver strips the leading '!' unconditionally
     * ({@link NottableString#string(String)}), turning "path IS !foo" into "path is NOT foo" — the
     * exact opposite of what was asked. The serialiser must fall back to the unambiguous object
     * form, which the deserialiser reads verbatim.
     */
    @Test
    public void shouldSerializeLiteralNotCharacterAsObjectWhenNotNegated() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(string("!some_string", false)),
            is("{\"not\":false,\"value\":\"!some_string\"}"));
    }

    /**
     * Same defect via the optional marker: "?some_string" as a literal value would be re-read as an
     * optional match on "some_string".
     */
    @Test
    public void shouldSerializeLiteralOptionalCharacterAsObjectWhenNotOptional() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(string("?some_string", false)),
            is("{\"not\":false,\"value\":\"?some_string\"}"));
    }

    /**
     * An optional match on a value that itself starts with '!' serialises to "?!value", which
     * re-reads as optional AND negated.
     */
    @Test
    public void shouldSerializeOptionalWithLiteralNotCharacterAsObject() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(optional("!some_string", false)),
            is("{\"not\":false,\"optional\":true,\"value\":\"!some_string\"}"));
    }

    /**
     * The discriminating test: serialise on the client side, then parse with the SAME deserialiser
     * the server uses. A test that only round-trips within one side cannot see an inversion.
     */
    @Test
    public void shouldRoundTripLiteralMarkerCharactersThroughTheWire() throws Exception {
        for (NottableString original : new NottableString[]{
            string("!some_string", false),
            string("?some_string", false),
            string("?!some_string", false),
            string("!?some_string", false),
            optional("!some_string", false),
            string("!some_string", true),
            optional("!some_string", true),
        }) {
            String wire = ObjectMapperFactory.createObjectMapper().writeValueAsString(original);
            NottableString parsed = ObjectMapperFactory.createObjectMapper().readValue(wire, NottableString.class);
            assertThat("value survived wire form " + wire, parsed.getValue(), is(original.getValue()));
            assertThat("negation survived wire form " + wire, parsed.isNot(), is(original.isNot()));
            assertThat("optionality survived wire form " + wire, parsed.isOptional(), is(original.isOptional()));
        }
    }

    @Test
    public void shouldSerializeOptionalString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(optional("some_string")),
            is("\"?some_string\""));
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(string("?some_string")),
            is("\"?some_string\""));
    }

    @Test
    public void shouldSerializeOptionalNottedString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(NottableOptionalString.optional("some_string", true)),
            is("\"?!some_string\""));
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(string("?!some_string")),
            is("\"?!some_string\""));
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(string("!?some_string")),
            is("\"?!some_string\""));
    }

    @Test
    public void shouldSerializeSchemaString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(schemaString("" +
                "{" + NEW_LINE +
                "  \"type\" : \"string\"" + NEW_LINE +
                "}")),
            is("{\"schema\":{\"type\":\"string\"}}"));
    }

    @Test
    public void shouldSerializeNottedSchemaString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(schemaString("{\"type\":\"string\"}", true)),
            is("{\"not\":true,\"schema\":{\"type\":\"string\"}}"));
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(schemaString("!{\"type\":\"string\"}")),
            is("{\"not\":true,\"schema\":{\"type\":\"string\"}}"));
    }

    @Test
    public void shouldSerializeStyledString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper()
                .writeValueAsString(
                    string("some_string")
                        .withStyle(MATRIX)
                ),
            is("{\"parameterStyle\":\"MATRIX\",\"value\":\"some_string\"}"));
    }

    @Test
    public void shouldSerializeStyledSchemaString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper()
                .writeValueAsString(
                    schemaString("" +
                        "{" + NEW_LINE +
                        "  \"type\" : \"string\"" + NEW_LINE +
                        "}")
                        .withStyle(MATRIX)
                ),
            is("{\"parameterStyle\":\"MATRIX\",\"schema\":{\"type\":\"string\"}}"));
    }

    @Test
    public void shouldSerializeNottedStyledString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper()
                .writeValueAsString(
                    string("some_string", true)
                        .withStyle(MATRIX)
                ),
            is("{\"not\":true,\"parameterStyle\":\"MATRIX\",\"value\":\"some_string\"}"));
    }

    @Test
    public void shouldSerializeOptionalNottedStyledString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper()
                .writeValueAsString(
                    NottableOptionalString.optional("some_string", true)
                        .withStyle(MATRIX)
                ),
            is("{\"not\":true,\"optional\":true,\"parameterStyle\":\"MATRIX\",\"value\":\"some_string\"}"));
        assertThat(ObjectMapperFactory.createObjectMapper()
                .writeValueAsString(
                    string("?!some_string")
                        .withStyle(MATRIX)
                ),
            is("{\"not\":true,\"optional\":true,\"parameterStyle\":\"MATRIX\",\"value\":\"some_string\"}"));
    }
}
