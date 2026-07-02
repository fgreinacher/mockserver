package org.mockserver.openapi;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

public class OpenAPIRequestValidatorTest {

    private static final String SPEC = "org/mockserver/openapi/openapi_petstore_example.json";
    private final MockServerLogger logger = new MockServerLogger();

    @Test
    public void shouldPassForValidGetRequest() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets").withMethod("GET"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFailForUnknownPathAndMethod() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/unknown/path").withMethod("DELETE"),
            logger
        );
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("no operation found"));
    }

    @Test
    public void shouldFailForMissingRequiredBody() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets").withMethod("POST"),
            logger
        );
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("request body is required but was empty"));
    }

    @Test
    public void shouldPassForValidPostBody() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets")
                .withMethod("POST")
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": 1, \"name\": \"Fido\"}"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFailForInvalidPostBody() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets")
                .withMethod("POST")
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": \"not_a_number\", \"name\": \"Fido\"}"),
            logger
        );
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("request body validation error"));
    }

    @Test
    public void shouldMatchPathWithTemplateParameters() {
        // showPetById declares a required X-Request-ID header parameter; supply it so the request is
        // valid and this test continues to assert template-path matching (not parameter validation)
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets/123")
                .withMethod("GET")
                .withHeader("X-Request-ID", "3fa85f64-5717-4562-b3fc-2c963f66afa6"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFallbackToApplicationJsonContentType() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets")
                .withMethod("POST")
                .withBody("{\"id\": 1, \"name\": \"Fido\"}"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldHandleContentTypeWithCharset() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets")
                .withMethod("POST")
                .withHeader("content-type", "application/json; charset=utf-8")
                .withBody("{\"id\": 1, \"name\": \"Fido\"}"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    private static final String CONCRETE_BEFORE_TEMPLATED = "org/mockserver/openapi/openapi_concrete_before_templated.yaml";

    @Test
    public void shouldPreferConcretePathOverTemplatedPath() {
        // given - spec declares "/users/{id}" BEFORE the concrete "/users/me"; declaration-order
        // matching would wrongly select the templated operation. The concrete path must win.
        // A body valid only for the concrete "/users/me" schema (requires "me") and invalid for the
        // templated "/users/{id}" schema (requires "byId", additionalProperties:false) proves which
        // operation was selected.
        List<String> errors = OpenAPIRequestValidator.validate(CONCRETE_BEFORE_TEMPLATED,
            request("/users/me")
                .withMethod("GET"),
            logger
        );

        // then - the concrete /users/me operation is selected (it has no request body), so no errors
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldStillMatchTemplatedPathWhenNoConcretePathMatches() {
        // given - "/users/123" only matches the templated "/users/{id}" operation
        List<String> errors = OpenAPIRequestValidator.validate(CONCRETE_BEFORE_TEMPLATED,
            request("/users/123").withMethod("GET"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    private static final String WEBHOOKS_ONLY = "org/mockserver/openapi/openapi_31_webhooks_only.yaml";

    @Test
    public void shouldNotNpeForWebhooksOnlySpec() {
        // given - a valid OAS 3.1 spec with webhooks and NO paths; getPaths() returns null
        List<String> errors = OpenAPIRequestValidator.validate(WEBHOOKS_ONLY,
            request("/pets").withMethod("GET"),
            logger
        );

        // then - no NPE / no "OpenAPI request validation error"; just an ordinary unmatched-operation result
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("no operation found"));
    }

    @Test
    public void shouldHandleInvalidSpec() {
        List<String> errors = OpenAPIRequestValidator.validate("not_a_valid_spec",
            request("/pets").withMethod("GET"),
            logger
        );
        assertThat(errors, hasSize(1));
        // the message names what was being validated and the exception type, and never echoes a bare "null"
        assertThat(errors.get(0), containsString("OpenAPI request validation failed"));
        assertThat(errors.get(0), not(endsWith(": null")));
    }

    // ---- parameter validation (path / query / header / cookie) ----

    private static final String PARAMS = "org/mockserver/openapi/openapi_parameters_example.yaml";

    @Test
    public void shouldPassWhenAllRequiredParametersPresentAndValid() {
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/items/42")
                .withMethod("GET")
                .withQueryStringParameter("filter", "active")
                .withHeader("X-Trace-Id", "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                .withCookie("session", "abc123"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFailForMissingRequiredQueryParameter() {
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/items/42")
                .withMethod("GET")
                .withHeader("X-Trace-Id", "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                .withCookie("session", "abc123"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("required query parameter"), containsString("filter"), containsString("missing"))));
    }

    @Test
    public void shouldFailForMissingRequiredHeaderParameter() {
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/items/42")
                .withMethod("GET")
                .withQueryStringParameter("filter", "active")
                .withCookie("session", "abc123"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("required header parameter"), containsString("X-Trace-Id"), containsString("missing"))));
    }

    @Test
    public void shouldFailForMissingRequiredCookieParameter() {
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/items/42")
                .withMethod("GET")
                .withQueryStringParameter("filter", "active")
                .withHeader("X-Trace-Id", "3fa85f64-5717-4562-b3fc-2c963f66afa6"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("required cookie parameter"), containsString("session"), containsString("missing"))));
    }

    @Test
    public void shouldFailForSchemaInvalidPathParameter() {
        // itemId schema is integer; a non-numeric path value must fail schema validation
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/items/not-a-number")
                .withMethod("GET")
                .withQueryStringParameter("filter", "active")
                .withHeader("X-Trace-Id", "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                .withCookie("session", "abc123"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("path parameter"), containsString("itemId"), containsString("validation error"))));
    }

    @Test
    public void shouldFailForSchemaInvalidQueryParameter() {
        // page schema is integer with minimum 1; a non-numeric value must fail schema validation
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/items/42")
                .withMethod("GET")
                .withQueryStringParameter("filter", "active")
                .withQueryStringParameter("page", "not-a-number")
                .withHeader("X-Trace-Id", "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                .withCookie("session", "abc123"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("query parameter"), containsString("page"), containsString("validation error"))));
    }

    @Test
    public void shouldPassWhenOptionalParameterAbsent() {
        // 'page' is optional; omitting it is valid as long as required params are present
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/items/42")
                .withMethod("GET")
                .withQueryStringParameter("filter", "active")
                .withHeader("X-Trace-Id", "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                .withCookie("session", "abc123"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldNotFailForArrayQueryParameterSerialisedInNonJsonStyle() {
        // status is an array parameter; supplied comma-delimited; decoded per style/explode rules and
        // validated (passes because the item schema is an unconstrained string)
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/search")
                .withMethod("GET")
                .withQueryStringParameter("status", "available,pending")
                .withHeader("X-Tags", "red,green"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldNotFailForArrayHeaderParameterSerialisedInNonJsonStyle() {
        // X-Tags is an array header parameter supplied as a comma-delimited (simple style) value
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/search")
                .withMethod("GET")
                .withQueryStringParameter("status", "available")
                .withHeader("X-Tags", "red,green,blue"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldStillEnforceRequiredPresenceForArrayParameter() {
        // skipping the schema check for non-JSON-shaped array values must NOT skip required-presence:
        // omitting the required 'status' array query parameter is still an error
        List<String> errors = OpenAPIRequestValidator.validate(PARAMS,
            request("/search")
                .withMethod("GET")
                .withHeader("X-Tags", "red"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("required query parameter"), containsString("status"), containsString("missing"))));
    }

    @Test
    public void shouldFailForMissingRequiredPathParameterViaPetstoreHeader() {
        // petstore showPetById declares a required X-Request-ID header; omitting it is an error
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets/123").withMethod("GET"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("required header parameter"), containsString("X-Request-ID"), containsString("missing"))));
    }

    // ---- style/explode-serialised array/object parameter validation ----

    private static final String STYLED = "org/mockserver/openapi/openapi_styled_parameters_example.yaml";

    @Test
    public void shouldPassForValidFormArrayQueryParameter() {
        List<String> errors = OpenAPIRequestValidator.validate(STYLED,
            request("/arr").withMethod("GET").withQueryStringParameter("ids", "1,2,3"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFailForFormArrayQueryParameterWithNonIntegerElement() {
        // form array explode:false is now split and each element validated against items:integer
        List<String> errors = OpenAPIRequestValidator.validate(STYLED,
            request("/arr").withMethod("GET").withQueryStringParameter("ids", "1,not-a-number,3"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("query parameter"), containsString("ids"), containsString("validation error"))));
    }

    @Test
    public void shouldStillEnforceRequiredPresenceForStyledArray() {
        List<String> errors = OpenAPIRequestValidator.validate(STYLED,
            request("/arr").withMethod("GET"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("required query parameter"), containsString("ids"), containsString("missing"))));
    }

    @Test
    public void shouldPassForValidSimplePathArrayParameter() {
        List<String> errors = OpenAPIRequestValidator.validate(STYLED,
            request("/pathArr/1,2,3").withMethod("GET"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFailForSimplePathArrayWithNonIntegerElement() {
        List<String> errors = OpenAPIRequestValidator.validate(STYLED,
            request("/pathArr/1,foo,3").withMethod("GET"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("path parameter"), containsString("ids"), containsString("validation error"))));
    }

    @Test
    public void shouldFailForSimpleHeaderArrayWithNonIntegerElement() {
        List<String> errors = OpenAPIRequestValidator.validate(STYLED,
            request("/headerArr").withMethod("GET").withHeader("X-Ids", "1,bad,3"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("header parameter"), containsString("X-Ids"), containsString("validation error"))));
    }

    @Test
    public void shouldPassForValidDeepObjectQueryParameter() {
        List<String> errors = OpenAPIRequestValidator.validate(STYLED,
            request("/obj").withMethod("GET")
                .withQueryStringParameter("filter[min]", "1")
                .withQueryStringParameter("filter[max]", "5"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFailForDeepObjectQueryParameterWithNonIntegerProperty() {
        List<String> errors = OpenAPIRequestValidator.validate(STYLED,
            request("/obj").withMethod("GET")
                .withQueryStringParameter("filter[min]", "not-a-number")
                .withQueryStringParameter("filter[max]", "5"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("query parameter"), containsString("filter"), containsString("validation error"))));
    }

    @Test
    public void shouldFailForMissingRequiredDeepObjectParameter() {
        List<String> errors = OpenAPIRequestValidator.validate(STYLED,
            request("/obj").withMethod("GET"),
            logger
        );
        assertThat(errors, hasItem(allOf(containsString("required query parameter"), containsString("filter"), containsString("missing"))));
    }

    @Test
    public void shouldProduceMeaningfulErrorWhenExceptionMessageIsNull() {
        // given - an exception with no message (getMessage() == null), e.g. a bare NPE
        Throwable nullMessageThrowable = new NullPointerException();

        // when - the same helper the validator uses turns it into a caller-facing error string
        String error = OpenAPIValidationErrors.unexpectedError("OpenAPI request validation", nullMessageThrowable, logger);

        // then - the context AND the exception type are present, and there is no literal "null"
        assertThat(error, containsString("OpenAPI request validation"));
        assertThat(error, containsString("NullPointerException"));
        assertThat(error, not(containsString("null")));
    }
}
