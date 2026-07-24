package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.FileBody;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.BinaryBody;
import org.mockserver.model.MediaType;
import org.mockserver.model.StringBody;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class HttpResponseActionHandlerTest {

    private final HttpResponseActionHandler httpResponseActionHandler = new HttpResponseActionHandler(new MockServerLogger(), new Configuration());

    @Test
    public void shouldHandleHttpRequests() {
        // given
        HttpResponse httpResponse = mock(HttpResponse.class);

        // when
        httpResponseActionHandler.handle(httpResponse);

        // then
        verify(httpResponse).clone();
    }

    @Test
    public void shouldRenderMustacheTemplatedFileBodyAgainstRequest() {
        // given - a static response whose body file is processed as a Mustache template
        HttpResponse httpResponse = response().withBody(
            new FileBody("org/mockserver/templates/sample_mustache_body.json", MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE)
        );

        // when
        HttpResponse actual = httpResponseActionHandler.handle(httpResponse, request().withMethod("PUT").withPath("/somePath"));

        // then - placeholders resolved from the request, content type preserved
        assertThat(actual.getBodyAsString(), containsString("\"method\": \"PUT\""));
        assertThat(actual.getBodyAsString(), containsString("\"path\": \"/somePath\""));
        assertThat(actual.getBody().getContentType(), containsString("application/json"));
    }

    @Test
    public void shouldServeFileContentsVerbatimWhenNoTemplateType() {
        // given - a FILE body with a filePath but NO templateType (issue #2450): the file must be READ
        // and its CONTENTS served, not the filePath string. The file deliberately contains a Mustache
        // placeholder that must survive UNPROCESSED because no template engine is configured.
        HttpResponse httpResponse = response().withBody(
            new FileBody("org/mockserver/mock/action/verbatim_file_body.xml", MediaType.parse("application/xml"))
        );

        // when
        HttpResponse actual = httpResponseActionHandler.handle(httpResponse, request().withMethod("PUT").withPath("/somePath"));

        // then - the body value is the exact file contents (not the path, not template-processed)
        assertThat(actual.getBody(), is(instanceOf(StringBody.class)));
        assertThat(actual.getBody().getValue(), is("<tag>hello{{ request.path }}</tag>"));
        assertThat(actual.getBody().getContentType(), containsString("application/xml"));
    }

    @Test
    public void shouldServeBinaryFileContentsIntactWhenNoTemplateType() {
        // given - a FILE body referencing a BINARY file with a binary content type and no templateType;
        // the raw bytes must be served intact (charset-decoding them would corrupt the file)
        HttpResponse httpResponse = response().withBody(
            new FileBody("org/mockserver/mock/action/verbatim_binary_body.png", MediaType.parse("image/png"))
        );

        // when
        HttpResponse actual = httpResponseActionHandler.handle(httpResponse, request().withMethod("GET").withPath("/image"));

        // then - served as raw bytes (BinaryBody), byte-for-byte identical to the file on disk
        assertThat(actual.getBody(), is(instanceOf(BinaryBody.class)));
        assertArrayEquals(
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, (byte) 0xFF, (byte) 0xFE, 0x01, (byte) 0x80, 0x7F, (byte) 0xC3, 0x28},
            actual.getBody().getRawBytes()
        );
        assertThat(actual.getBody().getContentType(), containsString("image/png"));
    }

    @Test
    public void shouldServeFileContentsVerbatimWhenTemplateTypeUnsupported() {
        // given - a FILE body with an UNSUPPORTED templateType for file templating (JavaScript); it must
        // serve the raw file CONTENTS rather than emitting the filePath string (the latent default-branch gap)
        HttpResponse httpResponse = response().withBody(
            new FileBody("org/mockserver/mock/action/verbatim_file_body.xml", MediaType.parse("application/xml"), HttpTemplate.TemplateType.JAVASCRIPT)
        );

        // when
        HttpResponse actual = httpResponseActionHandler.handle(httpResponse, request().withMethod("PUT").withPath("/somePath"));

        // then - contents served verbatim, not the path
        assertThat(actual.getBody(), is(instanceOf(StringBody.class)));
        assertThat(actual.getBody().getValue(), is("<tag>hello{{ request.path }}</tag>"));
    }

    @Test
    public void shouldFailConsistentlyWhenVerbatimFileMissing() {
        // given - a FILE body with no templateType pointing at a missing file; behaviour must match the
        // templated path, which surfaces a RuntimeException from FileReader for a missing file
        HttpResponse httpResponse = response().withBody(
            new FileBody("org/mockserver/mock/action/does_not_exist_" + System.nanoTime() + ".xml", MediaType.parse("application/xml"))
        );

        // when / then
        assertThrows(RuntimeException.class, () ->
            httpResponseActionHandler.handle(httpResponse, request().withMethod("GET").withPath("/missing")));
    }

    @Test
    public void shouldNotRenderTemplatedFileBodyWhenRequestUnavailable() {
        // given - the no-request overload (e.g. early/secondary paths) must not template
        HttpResponse httpResponse = response().withBody(
            new FileBody("org/mockserver/templates/sample_mustache_body.json", MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE)
        );

        // when
        HttpResponse actual = httpResponseActionHandler.handle(httpResponse);

        // then - body remains an (unrendered) FileBody
        assertThat(actual.getBody(), is(instanceOf(FileBody.class)));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void shouldSynthesizeGraphQLResponseFromRegisteredSchemaWhenResponseHasNoBody() throws Exception {
        // given - a matched GraphQL expectation whose request body carries a schema, and a response with no body
        GraphQLBody matchedRequestBody = new GraphQLBody("{ user { name email } }")
            .withSchema("type Query { user: User } type User { id: ID name: String email: String age: Int }");
        HttpResponse responseAction = response();

        // when - the incoming request carries the GraphQL query
        HttpResponse actual = httpResponseActionHandler.handle(
            responseAction,
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ user { name email } }\"}"),
            request().withBody(matchedRequestBody));

        // then - a schema-valid response is synthesized with only the requested fields
        assertThat(actual.getBody().getContentType(), containsString("application/json"));
        JsonNode data = MAPPER.readTree(actual.getBodyAsString()).get("data");
        JsonNode user = data.get("user");
        assertTrue("name requested", user.has("name"));
        assertTrue("email requested", user.has("email"));
        assertThat("age not requested", user.get("age"), is(nullValue()));
        assertTrue(user.get("name").isTextual());
    }

    @Test
    public void shouldNotSynthesizeWhenResponseAlreadyHasBody() {
        // given - the expectation has a schema but the response provides an explicit body
        GraphQLBody matchedRequestBody = new GraphQLBody("{ user { name } }")
            .withSchema("type Query { user: User } type User { name: String }");
        HttpResponse responseAction = response().withBody("{\"data\":{\"user\":{\"name\":\"Alice\"}}}");

        // when
        HttpResponse actual = httpResponseActionHandler.handle(
            responseAction,
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ user { name } }\"}"),
            request().withBody(matchedRequestBody));

        // then - the explicit body is preserved verbatim, no synthesis
        assertThat(actual.getBodyAsString(), is("{\"data\":{\"user\":{\"name\":\"Alice\"}}}"));
    }

    @Test
    public void shouldLeaveBodyUnsetWhenNoSchemaRegistered() {
        // given - a GraphQL request body without a schema, response with no body
        GraphQLBody matchedRequestBody = new GraphQLBody("{ user { name } }");
        HttpResponse responseAction = response();

        // when
        HttpResponse actual = httpResponseActionHandler.handle(
            responseAction,
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ user { name } }\"}"),
            request().withBody(matchedRequestBody));

        // then - no synthesis occurs, body remains unset
        assertThat(actual.getBody(), is(nullValue()));
    }

    @Test
    public void shouldGenerateSchemaValidResponseFromInlineJsonSchemaWithoutRequest() throws Exception {
        // given - a response configured with only an inline JSON schema (no full OpenAPI spec, no request)
        HttpResponse responseAction = response().withGenerateFromSchema(
            "{\"type\":\"object\",\"required\":[\"id\",\"status\"]," +
                "\"properties\":{" +
                "\"id\":{\"type\":\"integer\"}," +
                "\"status\":{\"type\":\"string\",\"enum\":[\"ACTIVE\",\"DISABLED\"]}," +
                "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," +
                "\"address\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}" +
                "}}");

        // when - handled with no request (schema generation does not depend on the request)
        HttpResponse actual = httpResponseActionHandler.handle(responseAction);

        // then - a schema-valid JSON body is generated honouring types, enum, array and nested object
        assertThat(actual.getBody().getContentType(), containsString("application/json"));
        JsonNode body = MAPPER.readTree(actual.getBodyAsString());
        assertTrue("id is required and integer-typed", body.get("id").isInt());
        assertThat("status uses the first enum value", body.get("status").asText(), is("ACTIVE"));
        assertTrue("tags is an array", body.get("tags").isArray());
        assertTrue("tags items are strings", body.get("tags").get(0).isTextual());
        assertTrue("address is a nested object", body.get("address").isObject());
        assertTrue("nested city is a string", body.get("address").get("city").isTextual());
    }

    @Test
    public void shouldNotGenerateFromSchemaWhenResponseAlreadyHasExplicitBody() {
        // given - an explicit body alongside an inline schema must win
        HttpResponse responseAction = response()
            .withBody("{\"explicit\":true}")
            .withGenerateFromSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}");

        // when
        HttpResponse actual = httpResponseActionHandler.handle(responseAction, request().withMethod("GET").withPath("/x"));

        // then - the explicit body is preserved verbatim
        assertThat(actual.getBodyAsString(), is("{\"explicit\":true}"));
    }

    @Test
    public void shouldLeaveBodyUnsetWhenInlineSchemaIsInvalid() {
        // given - an unparseable inline schema must not fail the request (fail-soft)
        HttpResponse responseAction = response().withGenerateFromSchema("this is not valid json schema <<<");

        // when
        HttpResponse actual = httpResponseActionHandler.handle(responseAction);

        // then - body remains unset rather than throwing
        assertThat(actual.getBody(), is(nullValue()));
    }

    @Test
    public void shouldNotFailRequestWhenInlineSchemaRefIsUnresolvable() {
        // given - a schema whose $ref points at an unreachable file (exercises the OpenAPIParser $ref-resolution
        // path); resolution must not throw out of the handler - the request stays serviceable (fail-soft)
        HttpResponse responseAction = response().withGenerateFromSchema(
            "{\"$ref\":\"file:///nonexistent/" + System.nanoTime() + "/schema.json#/Thing\"}");

        // when
        HttpResponse actual = httpResponseActionHandler.handle(responseAction);

        // then - no exception escapes; the body is simply left unset
        assertThat(actual.getBody(), is(nullValue()));
    }

    @Test
    public void shouldLeaveBodyUnsetWhenSchemaIsInvalid() {
        // given - an unparseable schema must not fail the request (fail-soft)
        GraphQLBody matchedRequestBody = new GraphQLBody("{ user { name } }")
            .withSchema("this is not valid SDL <<<");
        HttpResponse responseAction = response();

        // when
        HttpResponse actual = httpResponseActionHandler.handle(
            responseAction,
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ user { name } }\"}"),
            request().withBody(matchedRequestBody));

        // then - body remains unset rather than throwing
        assertThat(actual.getBody(), is(nullValue()));
    }

}
