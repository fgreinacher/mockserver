package org.mockserver.imports;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.ResponseMode;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MockoonImporterTest {

    private final MockoonImporter importer = new MockoonImporter();

    private static final String MOCKOON = """
        {
          "uuid": "env-1",
          "lastMigration": 33,
          "name": "Demo API",
          "port": 3000,
          "routes": [
            {
              "uuid": "route-user",
              "type": "http",
              "method": "get",
              "endpoint": "users/:id",
              "responses": [
                {
                  "uuid": "r1",
                  "statusCode": 200,
                  "label": "ok",
                  "headers": [ { "key": "Content-Type", "value": "application/json" } ],
                  "body": "{\\"id\\":1}",
                  "latency": 150,
                  "default": true,
                  "rules": [],
                  "rulesOperator": "OR"
                }
              ]
            },
            {
              "uuid": "route-search",
              "type": "http",
              "method": "get",
              "endpoint": "search",
              "responseMode": null,
              "responses": [
                {
                  "uuid": "s1",
                  "statusCode": 200,
                  "body": "filtered",
                  "rules": [ { "target": "query", "modifier": "q", "value": "book", "operator": "equals", "invert": false } ],
                  "rulesOperator": "AND",
                  "default": false
                },
                {
                  "uuid": "s2",
                  "statusCode": 404,
                  "body": "not found",
                  "rules": [],
                  "default": true
                }
              ]
            },
            {
              "uuid": "route-seq",
              "type": "http",
              "method": "post",
              "endpoint": "events",
              "responseMode": "SEQUENTIAL",
              "responses": [
                { "uuid": "q1", "statusCode": 201, "body": "one" },
                { "uuid": "q2", "statusCode": 202, "body": "two" }
              ]
            },
            {
              "uuid": "route-rand",
              "type": "http",
              "method": "get",
              "endpoint": "dice",
              "responseMode": "RANDOM",
              "responses": [
                { "uuid": "d1", "statusCode": 200, "body": "1" },
                { "uuid": "d2", "statusCode": 200, "body": "6" }
              ]
            },
            {
              "uuid": "route-warn",
              "type": "http",
              "method": "get",
              "endpoint": "warn",
              "responses": [
                {
                  "uuid": "w1",
                  "statusCode": 200,
                  "body": "x",
                  "rules": [
                    { "target": "cookie", "modifier": "sid", "value": "1", "operator": "equals" },
                    { "target": "body", "modifier": "", "value": "y", "operator": "null" }
                  ],
                  "default": true
                }
              ]
            },
            {
              "uuid": "route-crud",
              "type": "crud",
              "method": "get",
              "endpoint": "items",
              "responses": [ { "uuid": "c1", "statusCode": 200 } ]
            }
          ]
        }
        """;

    @Test
    public void mapsSingleResponseRouteWithParamPathHeadersBodyLatency() {
        Expectation e = importer.importExpectations(MOCKOON).getExpectations().get(0);
        HttpRequest request = (HttpRequest) e.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        // :id converted to a single-segment regex
        assertThat(request.getPath().getValue(), is("/users/[^/]+"));

        HttpResponse response = e.getHttpResponse();
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("\"id\""));
        assertThat(response.getDelay().getValue(), is(150L));
        assertThat(response.toString(), containsString("application/json"));
    }

    @Test
    public void escapesLiteralPathSegmentsSoDotDoesNotMatchAnyCharacter() {
        String env = """
            { "routes": [ { "type": "http", "method": "get", "endpoint": "users/:id/file.txt", "uuid": "rf",
              "responses": [ { "statusCode": 200, "body": "ok" } ] } ] }
            """;
        HttpRequest request = (HttpRequest) importer.importExpectations(env).getExpectations().get(0).getHttpRequest();
        String path = request.getPath().getValue();
        // :id -> [^/]+, literal '.' escaped so it matches a literal dot only
        assertThat(path, is("/users/[^/]+/file\\.txt"));

        // The converted path is compiled as a full-match regex by MockServer's path matcher; verify
        // full-match semantics directly (String.matches is a full match, like the path matcher).
        assertThat("literal dot matches a real dot", "/users/1/file.txt".matches(path), is(true));
        assertThat("literal dot must not match an arbitrary character",
            "/users/1/fileXtxt".matches(path), is(false));
    }

    @Test
    public void warnsWhenQueryRuleHasNoModifier() {
        String env = """
            { "routes": [ { "type": "http", "method": "get", "endpoint": "q", "uuid": "rq",
              "responses": [
                { "uuid": "a", "statusCode": 200, "body": "hit",
                  "rules": [ { "target": "query", "value": "x", "operator": "equals" } ], "default": false },
                { "uuid": "b", "statusCode": 404, "body": "miss", "rules": [], "default": true } ] } ] }
            """;
        ImportResult result = importer.importExpectations(env);
        assertTrue("expected a warning that the modifier-less query rule was dropped",
            result.getWarnings().stream().anyMatch(w ->
                w.getConstruct().equals("response.rules.query") && w.getDetail().contains("dropped")));
    }

    @Test
    public void rulesBasedRouteEmitsOneExpectationPerResponseWithPriority() {
        List<Expectation> expectations = importer.importExpectations(MOCKOON).getExpectations();
        // route-search produces 2 expectations: rule-bearing (priority > 0) + default catch-all (0)
        Expectation ruleResponse = expectations.stream()
            .filter(x -> "mockoon-route-search-0".equals(x.getId())).findFirst().orElseThrow();
        Expectation defaultResponse = expectations.stream()
            .filter(x -> "mockoon-route-search-1".equals(x.getId())).findFirst().orElseThrow();
        assertThat(ruleResponse.getPriority(), is(greaterThanOrEqualTo(1)));
        assertThat(defaultResponse.getPriority(), is(0));
        // the rule became a query matcher
        assertThat(ruleResponse.getHttpRequest().toString(), containsString("book"));
    }

    @Test
    public void mapsSequentialResponseMode() {
        Expectation seq = importer.importExpectations(MOCKOON).getExpectations().stream()
            .filter(x -> "mockoon-route-seq".equals(x.getId())).findFirst().orElseThrow();
        assertThat(seq.getResponseMode(), is(ResponseMode.SEQUENTIAL));
        assertThat(seq.getHttpResponses(), hasSize(2));
        assertThat(seq.getHttpResponses().get(0).getBodyAsString(), is("one"));
    }

    @Test
    public void mapsRandomResponseMode() {
        Expectation rand = importer.importExpectations(MOCKOON).getExpectations().stream()
            .filter(x -> "mockoon-route-rand".equals(x.getId())).findFirst().orElseThrow();
        assertThat(rand.getResponseMode(), is(ResponseMode.RANDOM));
        assertThat(rand.getHttpResponses(), hasSize(2));
    }

    @Test
    public void warnsOnCookieRuleNullOperatorAndCrudRoute() {
        ImportResult result = importer.importExpectations(MOCKOON);
        assertTrue("expected a cookie-rule warning",
            result.getWarnings().stream().anyMatch(w -> w.getConstruct().contains("cookie")));
        assertTrue("expected a null-operator warning",
            result.getWarnings().stream().anyMatch(w -> w.getConstruct().contains("operator")));
        assertTrue("expected a crud route-type warning",
            result.getWarnings().stream().anyMatch(w -> w.getConstruct().equals("route.type")));
    }

    @Test
    public void crudRouteProducesNoExpectation() {
        List<Expectation> expectations = importer.importExpectations(MOCKOON).getExpectations();
        assertTrue(expectations.stream().noneMatch(x -> "mockoon-route-crud".equals(x.getId())));
    }

    @Test
    public void warnsOnHandlebarsTemplateBody() {
        String env = """
            { "routes": [ { "type": "http", "method": "get", "endpoint": "t", "uuid": "rt",
              "responses": [ { "statusCode": 200, "body": "Hello {{urlParam 'id'}}" } ] } ] }
            """;
        ImportResult result = importer.importExpectations(env);
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.getDetail().contains("templating")));
    }

    @Test
    public void cleanSingleRouteHasNoWarnings() {
        String env = """
            { "routes": [ { "type": "http", "method": "get", "endpoint": "ping", "uuid": "rp",
              "responses": [ { "statusCode": 200, "body": "pong" } ] } ] }
            """;
        ImportResult result = importer.importExpectations(env);
        assertThat(result.getWarnings(), is(empty()));
        assertThat(result.getExpectations().get(0).getHttpResponse().getBodyAsString(), is("pong"));
    }

    @Test
    public void rejectsBlankAndInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> importer.importExpectations(""));
        assertThrows(IllegalArgumentException.class, () -> importer.importExpectations("{ \"name\": \"no routes\" }"));
    }
}
