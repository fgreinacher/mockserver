package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.NottableString;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;

public class GrpcBidiResponseDTOTest {

    /**
     * The DTO used to collapse the rule's NottableString matcher to a bare String, dropping the
     * negation flag. buildObject() re-parsed a leading '!' to recover it, but a matchJson value is
     * JSON and never starts with '!', so a negated rule silently inverted into its opposite on any
     * control-plane round-trip — and {@code GrpcBidiRuleMatcher} depends on that flag.
     */
    @Test
    public void shouldPreserveMatchJsonNegationThroughDTORoundTrip() {
        GrpcBidiResponse original = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule()
                .withMatchJson(NottableString.string(".*Alice.*", true))
                .withResponse("{\"greeting\": \"not Alice\"}"));

        GrpcBidiResponse rebuilt = new GrpcBidiResponseDTO(original).buildObject();

        assertThat(rebuilt.getRules().get(0).getMatchJson().getValue(), is(".*Alice.*"));
        assertThat(rebuilt.getRules().get(0).getMatchJson().isNot(), is(true));
    }

    /** The same negation must survive the actual JSON wire form, not just the in-memory DTO hop. */
    @Test
    public void shouldPreserveMatchJsonNegationThroughJsonWire() throws Exception {
        GrpcBidiResponse original = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule()
                .withMatchJson(NottableString.string(".*Alice.*", true))
                .withResponse("{\"greeting\": \"not Alice\"}"));

        String json = ObjectMapperFactory.createObjectMapper()
            .writeValueAsString(new GrpcBidiResponseDTO(original));
        GrpcBidiResponse rebuilt = ObjectMapperFactory.createObjectMapper()
            .readValue(json, GrpcBidiResponseDTO.class).buildObject();

        assertThat("negation lost in wire form " + json,
            rebuilt.getRules().get(0).getMatchJson().isNot(), is(true));
        assertThat(rebuilt.getRules().get(0).getMatchJson().getValue(), is(".*Alice.*"));
    }

    @Test
    public void shouldBuildObjectFromDTO() {
        GrpcBidiResponse original = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK")
            .withStatusMessage("success")
            .withMessage("{\"greeting\": \"eager hello\"}")
            .withMessage(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"eager world\"}")
                .withDelay(new Delay(TimeUnit.MILLISECONDS, 50)))
            .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                .withResponse("{\"greeting\": \"Hi Alice\"}")
                .withResponse(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Hello Alice again\"}")
                    .withDelay(new Delay(TimeUnit.MILLISECONDS, 100))))
            .withRule(GrpcBidiRule.grpcBidiRule(".*Bob.*")
                .withResponse("{\"greeting\": \"Hi Bob\"}"))
            .withCloseConnection(false);

        GrpcBidiResponseDTO dto = new GrpcBidiResponseDTO(original);
        GrpcBidiResponse rebuilt = dto.buildObject();

        assertThat(rebuilt.getStatusName(), is("OK"));
        assertThat(rebuilt.getStatusMessage(), is("success"));
        assertThat(rebuilt.getMessages().size(), is(2));
        assertThat(rebuilt.getMessages().get(0).getJson(), is("{\"greeting\": \"eager hello\"}"));
        assertThat(rebuilt.getMessages().get(1).getJson(), is("{\"greeting\": \"eager world\"}"));
        assertThat(rebuilt.getMessages().get(1).getDelay(), notNullValue());
        assertThat(rebuilt.getMessages().get(1).getDelay().getTimeUnit(), is(TimeUnit.MILLISECONDS));
        assertThat(rebuilt.getMessages().get(1).getDelay().getValue(), is(50L));
        assertThat(rebuilt.getRules().size(), is(2));
        assertThat(rebuilt.getRules().get(0).getMatchJson().getValue(), is(".*Alice.*"));
        assertThat(rebuilt.getRules().get(0).getResponses().size(), is(2));
        assertThat(rebuilt.getRules().get(0).getResponses().get(0).getJson(), is("{\"greeting\": \"Hi Alice\"}"));
        assertThat(rebuilt.getRules().get(0).getResponses().get(1).getJson(), is("{\"greeting\": \"Hello Alice again\"}"));
        assertThat(rebuilt.getRules().get(0).getResponses().get(1).getDelay(), notNullValue());
        assertThat(rebuilt.getRules().get(0).getResponses().get(1).getDelay().getTimeUnit(), is(TimeUnit.MILLISECONDS));
        assertThat(rebuilt.getRules().get(0).getResponses().get(1).getDelay().getValue(), is(100L));
        assertThat(rebuilt.getRules().get(1).getMatchJson().getValue(), is(".*Bob.*"));
        assertThat(rebuilt.getRules().get(1).getResponses().size(), is(1));
        assertThat(rebuilt.getCloseConnection(), is(false));
    }

    @Test
    public void shouldHandleNullInput() {
        GrpcBidiResponseDTO dto = new GrpcBidiResponseDTO(null);
        GrpcBidiResponse rebuilt = dto.buildObject();

        assertThat(rebuilt.getStatusName(), nullValue());
        assertThat(rebuilt.getMessages(), nullValue());
        assertThat(rebuilt.getRules(), nullValue());
    }

    @Test
    public void shouldRoundTripThroughJson() throws Exception {
        GrpcBidiResponse original = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("NOT_FOUND")
            .withStatusMessage("resource not found")
            .withMessage("{\"error\": \"not found\"}")
            .withRule(GrpcBidiRule.grpcBidiRule(".*search.*")
                .withResponse("{\"result\": \"none\"}"))
            .withCloseConnection(true);

        GrpcBidiResponseDTO dto = new GrpcBidiResponseDTO(original);
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(dto);
        GrpcBidiResponseDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, GrpcBidiResponseDTO.class);
        GrpcBidiResponse rebuilt = deserialized.buildObject();

        assertThat(rebuilt.getStatusName(), is("NOT_FOUND"));
        assertThat(rebuilt.getStatusMessage(), is("resource not found"));
        assertThat(rebuilt.getMessages().size(), is(1));
        assertThat(rebuilt.getRules().size(), is(1));
        assertThat(rebuilt.getRules().get(0).getMatchJson().getValue(), is(".*search.*"));
        assertThat(rebuilt.getRules().get(0).getResponses().size(), is(1));
        assertThat(rebuilt.getCloseConnection(), is(true));
    }

    @Test
    public void shouldRoundTripEmptyMessages() throws Exception {
        GrpcBidiResponse original = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK");

        GrpcBidiResponseDTO dto = new GrpcBidiResponseDTO(original);
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(dto);
        GrpcBidiResponseDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, GrpcBidiResponseDTO.class);
        GrpcBidiResponse rebuilt = deserialized.buildObject();

        assertThat(rebuilt.getStatusName(), is("OK"));
        assertThat(rebuilt.getMessages(), nullValue());
        assertThat(rebuilt.getRules(), nullValue());
    }

    @Test
    public void shouldRoundTripWithEagerMessagesOnly() throws Exception {
        GrpcBidiResponse original = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK")
            .withMessage("{\"greeting\": \"hello\"}")
            .withMessage("{\"greeting\": \"world\"}");

        GrpcBidiResponseDTO dto = new GrpcBidiResponseDTO(original);
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(dto);
        GrpcBidiResponseDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, GrpcBidiResponseDTO.class);
        GrpcBidiResponse rebuilt = deserialized.buildObject();

        assertThat(rebuilt.getStatusName(), is("OK"));
        assertThat(rebuilt.getMessages().size(), is(2));
        assertThat(rebuilt.getRules(), nullValue());
    }

    @Test
    public void shouldRoundTripWithRulesOnly() throws Exception {
        GrpcBidiResponse original = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                .withResponse("{\"greeting\": \"Hi Alice\"}"))
            .withRule(GrpcBidiRule.grpcBidiRule(".*Bob.*")
                .withResponse("{\"greeting\": \"Hi Bob\"}"));

        GrpcBidiResponseDTO dto = new GrpcBidiResponseDTO(original);
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(dto);
        GrpcBidiResponseDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, GrpcBidiResponseDTO.class);
        GrpcBidiResponse rebuilt = deserialized.buildObject();

        assertThat(rebuilt.getMessages(), nullValue());
        assertThat(rebuilt.getRules().size(), is(2));
        assertThat(rebuilt.getRules().get(0).getMatchJson().getValue(), is(".*Alice.*"));
        assertThat(rebuilt.getRules().get(1).getMatchJson().getValue(), is(".*Bob.*"));
    }

    @Test
    public void shouldPreservePrimaryFlag() throws Exception {
        GrpcBidiResponse original = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK")
            .withPrimary(true);

        GrpcBidiResponseDTO dto = new GrpcBidiResponseDTO(original);
        assertThat(dto.isPrimary(), is(true));

        GrpcBidiResponse rebuilt = dto.buildObject();
        assertThat(rebuilt.isPrimary(), is(true));
    }

    @Test
    public void shouldRoundTripTemplateTypeOnResponseMessage() throws Exception {
        // WS2.6: a templated bidi response message preserves its templateType through serialization,
        // while a sibling static message keeps a null templateType (opt-in, no default applied).
        GrpcBidiResponse original = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK")
            .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                .withResponse(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Hi $jsonPath(\\\"$.name\\\")\"}")
                    .withTemplateType(HttpTemplate.TemplateType.VELOCITY))
                .withResponse("{\"greeting\": \"static reply\"}"));

        GrpcBidiResponseDTO dto = new GrpcBidiResponseDTO(original);
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(dto);
        GrpcBidiResponseDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, GrpcBidiResponseDTO.class);
        GrpcBidiResponse rebuilt = deserialized.buildObject();

        GrpcStreamMessage templated = rebuilt.getRules().get(0).getResponses().get(0);
        GrpcStreamMessage staticMessage = rebuilt.getRules().get(0).getResponses().get(1);
        assertThat(templated.getTemplateType(), is(HttpTemplate.TemplateType.VELOCITY));
        assertThat(staticMessage.getTemplateType(), nullValue());
        // round-tripped JSON serializes templateType only for the templated message
        assertThat(json, org.hamcrest.Matchers.containsString("VELOCITY"));
    }

    @Test
    public void shouldRoundTripExpectationWithGrpcBidiResponse() throws Exception {
        // Verify the full ExpectationDTO round-trip with grpcBidiResponse
        String json = "{\n" +
            "  \"httpRequest\": {\n" +
            "    \"method\": \"POST\",\n" +
            "    \"path\": \"/com.example.grpc.GreetingService/Chat\"\n" +
            "  },\n" +
            "  \"grpcBidiResponse\": {\n" +
            "    \"statusName\": \"OK\",\n" +
            "    \"messages\": [\n" +
            "      {\"json\": \"{\\\"greeting\\\": \\\"welcome\\\"}\"}\n" +
            "    ],\n" +
            "    \"rules\": [\n" +
            "      {\n" +
            "        \"matchJson\": \".*Alice.*\",\n" +
            "        \"responses\": [\n" +
            "          {\"json\": \"{\\\"greeting\\\": \\\"Hi Alice\\\"}\"}\n" +
            "        ]\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";

        ExpectationDTO dto = ObjectMapperFactory.createObjectMapper().readValue(json, ExpectationDTO.class);
        assertThat(dto.getGrpcBidiResponse(), notNullValue());
        assertThat(dto.getGrpcBidiResponse().getStatusName(), is("OK"));
        assertThat(dto.getGrpcBidiResponse().getMessages().size(), is(1));
        assertThat(dto.getGrpcBidiResponse().getRules().size(), is(1));

        org.mockserver.mock.Expectation expectation = dto.buildObject();
        assertThat(expectation.getGrpcBidiResponse(), notNullValue());
        assertThat(expectation.getGrpcBidiResponse().getStatusName(), is("OK"));
        assertThat(expectation.getGrpcBidiResponse().getMessages().size(), is(1));
        assertThat(expectation.getGrpcBidiResponse().getRules().size(), is(1));
        assertThat(expectation.getGrpcBidiResponse().getRules().get(0).getMatchJson().getValue(), is(".*Alice.*"));
    }
}
