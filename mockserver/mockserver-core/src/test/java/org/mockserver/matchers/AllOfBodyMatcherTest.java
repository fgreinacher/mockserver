package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.AllOfBody.allOf;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.JsonPathBody.jsonPath;
import static org.mockserver.model.JsonSchemaBody.jsonSchema;
import static org.mockserver.model.Not.not;
import static org.mockserver.model.RegexBody.regex;
import static org.mockserver.model.StringBody.subString;

/**
 * Focused tests for the composite {@code allOf} request-body matcher: every component body matcher
 * must match the same request body.
 *
 * @author jamesdbloom
 */
public class AllOfBodyMatcherTest {

    private static final String JSON_SCHEMA = "{" +
        "\"type\":\"object\"," +
        "\"properties\":{\"age\":{\"type\":\"integer\"}}," +
        "\"required\":[\"age\"]" +
        "}";

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(AllOfBodyMatcherTest.class);

    private HttpRequestPropertiesMatcher matcher(RequestDefinition requestDefinition) {
        HttpRequestPropertiesMatcher httpRequestPropertiesMatcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        httpRequestPropertiesMatcher.update(new Expectation(requestDefinition));
        return httpRequestPropertiesMatcher;
    }

    private HttpRequest requestWithBody(String body) {
        return request().withBody(body);
    }

    @Test
    public void shouldMatchWhenAllComponentsMatch() {
        assertThat(matcher(request().withBody(allOf(
            jsonPath("$.name"),
            jsonSchema(JSON_SCHEMA),
            regex(".*value.*")
        ))).matches(null, requestWithBody("{\"name\":\"value\",\"age\":5}")), is(true));
    }

    @Test
    public void shouldNotMatchWhenJsonSchemaComponentFails() {
        // age is a string, so the jsonSchema (age:integer) component fails even though jsonPath/regex match
        assertThat(matcher(request().withBody(allOf(
            jsonPath("$.name"),
            jsonSchema(JSON_SCHEMA),
            regex(".*value.*")
        ))).matches(null, requestWithBody("{\"name\":\"value\",\"age\":\"five\"}")), is(false));
    }

    @Test
    public void shouldNotMatchWhenJsonPathComponentFails() {
        assertThat(matcher(request().withBody(allOf(
            jsonPath("$.missing"),
            regex(".*value.*")
        ))).matches(null, requestWithBody("{\"name\":\"value\",\"age\":5}")), is(false));
    }

    @Test
    public void shouldNotMatchWhenRegexComponentFails() {
        assertThat(matcher(request().withBody(allOf(
            jsonPath("$.name"),
            regex(".*absent.*")
        ))).matches(null, requestWithBody("{\"name\":\"value\",\"age\":5}")), is(false));
    }

    @Test
    public void shouldComposeSubStringAndRegexOverPlainText() {
        assertThat(matcher(request().withBody(allOf(
            subString("hello"),
            regex(".*world.*")
        ))).matches(null, requestWithBody("hello brave new world")), is(true));
    }

    @Test
    public void shouldHonourNegationOnComponent() {
        // body must contain "value" but the age must NOT be 5
        assertThat(matcher(request().withBody(allOf(
            jsonPath("$.name"),
            not(regex(".*\"age\":5.*"))
        ))).matches(null, requestWithBody("{\"name\":\"value\",\"age\":7}")), is(true));
    }

    @Test
    public void shouldNotMatchWhenNegatedComponentMatches() {
        assertThat(matcher(request().withBody(allOf(
            jsonPath("$.name"),
            not(regex(".*\"age\":5.*"))
        ))).matches(null, requestWithBody("{\"name\":\"value\",\"age\":5}")), is(false));
    }

    @Test
    public void shouldNegateWholeConjunction() {
        // NOT(all match): here both components match so the negated composite does not match
        assertThat(matcher(request().withBody(not(allOf(
            jsonPath("$.name"),
            regex(".*value.*")
        )))).matches(null, requestWithBody("{\"name\":\"value\",\"age\":5}")), is(false));
    }

    @Test
    public void shouldNegateWholeConjunctionWhenAComponentFails() {
        // NOT(all match): one component fails so the conjunction is false and the negation is true
        assertThat(matcher(request().withBody(not(allOf(
            jsonPath("$.missing"),
            regex(".*value.*")
        )))).matches(null, requestWithBody("{\"name\":\"value\",\"age\":5}")), is(true));
    }

    @Test
    public void shouldMatchEmptyCompositeAgainstAnyBody() {
        assertThat(matcher(request().withBody(allOf()))
            .matches(null, requestWithBody("anything")), is(true));
    }
}
