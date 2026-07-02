package org.mockserver.imports;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.SortableExpectationId;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WireMockImporterTest {

    private final WireMockImporter importer = new WireMockImporter();

    // A representative WireMock mappings export exercising the mappable constructs plus a
    // couple that must produce warnings (matchesXPath body, response transformers).
    private static final String WIREMOCK_MAPPINGS = """
        {
          "mappings": [
            {
              "id": "get-user",
              "priority": 1,
              "scenarioName": "user-flow",
              "requiredScenarioState": "Started",
              "newScenarioState": "user-fetched",
              "request": {
                "method": "GET",
                "urlPath": "/api/users/123",
                "queryParameters": {
                  "expand": { "equalTo": "profile" },
                  "fields": { "matches": "^[a-z,]+$" }
                },
                "headers": {
                  "Accept": { "equalTo": "application/json" },
                  "X-Trace": { "contains": "abc" }
                }
              },
              "response": {
                "status": 200,
                "headers": { "Content-Type": "application/json" },
                "jsonBody": { "id": 123, "name": "Alice" },
                "fixedDelayMilliseconds": 250
              }
            },
            {
              "request": {
                "method": "POST",
                "urlPattern": "/api/orders/[0-9]+\\\\?draft=true",
                "bodyPatterns": [
                  { "equalToJson": "{\\"item\\":\\"book\\"}", "ignoreExtraElements": true }
                ]
              },
              "response": {
                "status": 201,
                "body": "created"
              }
            },
            {
              "request": { "method": "GET", "urlPath": "/api/matchjsonpath" },
              "request2": {},
              "response": { "status": 200, "body": "ok" }
            },
            {
              "request": { "method": "GET", "urlPath": "/api/flaky" },
              "response": { "fault": "CONNECTION_RESET_BY_PEER" }
            },
            {
              "request": { "method": "GET", "urlPath": "/api/legacy" },
              "response": { "proxyBaseUrl": "https://origin.example.com:8443" }
            },
            {
              "request": {
                "method": "GET",
                "urlPath": "/api/xml",
                "bodyPatterns": [ { "matchesXPath": "//book/title" } ]
              },
              "response": {
                "status": 200,
                "body": "xml-ok",
                "transformers": [ "response-template" ]
              }
            }
          ]
        }
        """;

    @Test
    public void importsExactPathMethodHeadersQueryAndJsonBody() {
        ImportResult result = importer.importExpectations(WIREMOCK_MAPPINGS);

        Expectation getUser = result.getExpectations().get(0);
        HttpRequest request = (HttpRequest) getUser.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        assertThat(request.getPath().getValue(), is("/api/users/123"));
        // query + header matchers serialised into the request JSON
        String requestJson = request.toString();
        assertThat(requestJson, containsString("expand"));
        assertThat(requestJson, containsString("profile"));
        assertThat(requestJson, containsString("^[a-z,]+$"));
        assertThat(requestJson, containsString("Accept"));

        HttpResponse response = getUser.getHttpResponse();
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("\"name\""));
        assertThat(response.getBodyAsString(), containsString("Alice"));
        assertThat(response.getDelay().getValue(), is(250L));
    }

    @Test
    public void mapsScenarioAndInvertedPriority() {
        Expectation getUser = importer.importExpectations(WIREMOCK_MAPPINGS).getExpectations().get(0);
        assertThat(getUser.getScenarioName(), is("user-flow"));
        assertThat(getUser.getScenarioState(), is("Started"));
        assertThat(getUser.getNewScenarioState(), is("user-fetched"));
        // WireMock priority 1 (highest, WireMock default 5) maps to MockServer 5-1=4, which beats
        // an unspecified-priority MockServer catch-all at the default of 0.
        assertThat(getUser.getPriority(), is(4));
    }

    @Test
    public void priorityOneStubOutranksUnspecifiedPriorityStub() {
        // A WireMock priority:1 stub must win over an unspecified-priority (catch-all) stub. In
        // MockServer higher priority ints win (EXPECTATION_SORTABLE_PRIORITY_COMPARATOR), and an
        // unspecified priority defaults to 0 — so the mapped priority:1 stub must be > 0.
        String stubs = """
            [ { "request": { "method": "GET", "urlPath": "/x" },
                "priority": 1,
                "response": { "status": 200, "body": "explicit" } },
              { "request": { "method": "GET", "urlPathPattern": "/.*" },
                "response": { "status": 200, "body": "catch-all" } } ]
            """;
        List<Expectation> expectations = importer.importExpectations(stubs).getExpectations();
        Expectation explicit = expectations.get(0);
        Expectation catchAll = expectations.get(1);
        assertThat(explicit.getPriority(), is(4));
        assertThat(catchAll.getPriority(), is(0));
        assertTrue("priority:1 stub must outrank an unspecified-priority catch-all",
            explicit.getPriority() > catchAll.getPriority());

        // Confirm against the real matcher ordering comparator: sorting the two SortableExpectationIds
        // by EXPECTATION_SORTABLE_PRIORITY_COMPARATOR must place the explicit stub first.
        SortableExpectationId explicitId = new SortableExpectationId("explicit", explicit.getPriority(), 1L);
        SortableExpectationId catchAllId = new SortableExpectationId("catch-all", catchAll.getPriority(), 2L);
        List<SortableExpectationId> ids = new ArrayList<>(List.of(catchAllId, explicitId));
        ids.sort(SortableExpectationId.EXPECTATION_SORTABLE_PRIORITY_COMPARATOR);
        assertThat(ids.get(0), is(explicitId));
    }

    @Test
    public void mapsUrlPatternToPathRegexDroppingQueryPortionWithWarning() {
        ImportResult result = importer.importExpectations(WIREMOCK_MAPPINGS);
        HttpRequest request = (HttpRequest) result.getExpectations().get(1).getHttpRequest();
        assertThat(request.getPath().getValue(), is("/api/orders/[0-9]+"));
        assertThat(request.getBody().toString(), containsString("item"));
        assertTrue("expected a urlPattern query-drop warning",
            result.getWarnings().stream().anyMatch(w -> w.getConstruct().contains("urlPattern")));
    }

    @Test
    public void mapsFaultToConnectionDrop() {
        Expectation flaky = importer.importExpectations(WIREMOCK_MAPPINGS).getExpectations().get(3);
        HttpError error = flaky.getHttpError();
        assertThat(error, is(not(nullValue())));
        assertThat(error.getDropConnection(), is(true));
    }

    @Test
    public void mapsProxyBaseUrlToForward() {
        Expectation legacy = importer.importExpectations(WIREMOCK_MAPPINGS).getExpectations().get(4);
        HttpForward forward = legacy.getHttpForward();
        assertThat(forward, is(not(nullValue())));
        assertThat(forward.getHost(), is("origin.example.com"));
        assertThat(forward.getPort(), is(8443));
        assertThat(forward.getScheme(), is(HttpForward.Scheme.HTTPS));
    }

    @Test
    public void warnsOnMatchesXPathBodyAndResponseTransformers() {
        ImportResult result = importer.importExpectations(WIREMOCK_MAPPINGS);
        assertTrue("expected an XPath body warning",
            result.getWarnings().stream().anyMatch(w -> w.getConstruct().contains("matchesXPath")));
        assertTrue("expected a transformers warning",
            result.getWarnings().stream().anyMatch(w -> w.getConstruct().contains("transformers")));
    }

    @Test
    public void supportsSingleStubForm() {
        String single = """
            { "request": { "method": "DELETE", "urlPath": "/thing" },
              "response": { "status": 204 } }
            """;
        ImportResult result = importer.importExpectations(single);
        assertThat(result.getExpectations(), hasSize(1));
        HttpRequest request = (HttpRequest) result.getExpectations().get(0).getHttpRequest();
        assertThat(request.getMethod().getValue(), is("DELETE"));
        assertThat(result.getExpectations().get(0).getHttpResponse().getStatusCode(), is(204));
    }

    @Test
    public void supportsBareArrayForm() {
        String array = """
            [ { "request": { "urlPath": "/a" }, "response": { "status": 200 } },
              { "request": { "urlPath": "/b" }, "response": { "status": 201 } } ]
            """;
        assertThat(importer.importExpectations(array).getExpectations(), hasSize(2));
    }

    @Test
    public void decodesBase64Body() {
        // base64 of "hello world"
        String stub = """
            { "request": { "urlPath": "/b64" },
              "response": { "status": 200, "base64Body": "aGVsbG8gd29ybGQ=" } }
            """;
        HttpResponse response = importer.importExpectations(stub).getExpectations().get(0).getHttpResponse();
        assertThat(response.getBodyAsString(), is("hello world"));
    }

    @Test
    public void dropsAnyMethodMatcher() {
        String stub = """
            { "request": { "method": "ANY", "urlPath": "/any" }, "response": { "status": 200 } }
            """;
        HttpRequest request = (HttpRequest) importer.importExpectations(stub).getExpectations().get(0).getHttpRequest();
        assertThat(request.getMethod().getValue(), is(""));
    }

    @Test
    public void cleanImportHasNoWarnings() {
        String stub = """
            { "request": { "method": "GET", "urlPath": "/ok" }, "response": { "status": 200, "body": "ok" } }
            """;
        assertThat(importer.importExpectations(stub).getWarnings(), is(empty()));
    }

    @Test
    public void rejectsBlankAndInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> importer.importExpectations(""));
        assertThrows(IllegalArgumentException.class, () -> importer.importExpectations("{ \"nonsense\": true }"));
        assertThrows(IllegalArgumentException.class, () -> importer.importExpectations("not json"));
    }
}
