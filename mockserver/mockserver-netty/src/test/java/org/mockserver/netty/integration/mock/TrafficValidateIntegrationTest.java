package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.client.MockServerClient.ContractReport;
import org.mockserver.netty.MockServer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Integration test for the traffic-validation endpoint (PUT /mockserver/trafficValidate) and the
 * matching {@link MockServerClient#trafficValidate(String)} client helper.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>recorded request/response traffic that conforms to a provided OpenAPI spec produces a report
 *       with {@code allPassed == true};</li>
 *   <li>recorded traffic that violates the spec produces a report with at least one failing result
 *       carrying response validation errors;</li>
 *   <li>the endpoint is reachable through the Java client helper and the report is parsed into the
 *       typed {@link ContractReport} model.</li>
 * </ul>
 *
 * <p>The classpath OpenAPI spec is exercised as the {@code spec} payload; recorded traffic is created
 * by registering expectations and then issuing matching data-plane requests through a second client,
 * which records EXPECTATION_RESPONSE request/response pairs in the event log.
 */
public class TrafficValidateIntegrationTest {

    private static final String PETSTORE_SPEC = "org/mockserver/openapi/openapi_petstore_example.json";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    @Test
    public void shouldReportAllPassedWhenRecordedTrafficConformsToSpec() {
        // given - an expectation whose response conforms to the GET /pets 200 schema (a JSON array)
        mockServerClient
            .when(request().withMethod("GET").withPath("/pets"))
            .respond(response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[{\"id\": 1, \"name\": \"Fido\"}]"));

        // and - a matching data-plane request that records an EXPECTATION_RESPONSE request/response pair
        sendRawGet("/pets");

        // when
        ContractReport report = mockServerClient.trafficValidate(PETSTORE_SPEC);

        // then
        assertThat(report.total(), is(greaterThanOrEqualTo(1)));
        assertThat("conforming traffic should pass", report.allPassed(), is(true));
        assertThat(report.failed(), is(0));
        assertThat(report.results(), hasSize(report.total()));
        assertThat(report.results().get(0).passed(), is(true));
        assertThat(report.results().get(0).method(), is("GET"));
        assertThat(report.results().get(0).path(), is("/pets"));
        assertThat(report.results().get(0).matchedOperation(), is(notNullValue()));
        assertThat(report.results().get(0).responseErrors(), is(empty()));
    }

    @Test
    public void shouldReportFailureWhenRecordedTrafficViolatesSpec() {
        // given - an expectation whose response VIOLATES the GET /pets 200 schema (object, not array)
        mockServerClient
            .when(request().withMethod("GET").withPath("/pets"))
            .respond(response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"not\": \"an array\"}"));

        sendRawGet("/pets");

        // when
        ContractReport report = mockServerClient.trafficValidate(PETSTORE_SPEC);

        // then
        assertThat(report.total(), is(greaterThanOrEqualTo(1)));
        assertThat("non-conforming traffic should not all pass", report.allPassed(), is(false));
        assertThat(report.failed(), is(greaterThanOrEqualTo(1)));
        boolean sawResponseError = report.results().stream()
            .anyMatch(result -> !result.passed() && !result.responseErrors().isEmpty());
        assertThat("a failing result should carry response validation errors", sawResponseError, is(true));
    }

    @Test
    public void shouldReportFailureWhenRecordedRequestViolatesSpec() {
        // given - an expectation for POST /pets whose response conforms to the 201 "Null response"
        // (no body schema), so the ONLY spec violation is on the REQUEST side
        mockServerClient
            .when(request().withMethod("POST").withPath("/pets"))
            .respond(response().withStatusCode(201));

        // and - a matching data-plane POST whose body VIOLATES the createPets request schema:
        // the Pet schema requires "id" (integer) and "name" (string); this body omits both
        sendRawPost("/pets", "{\"tag\": \"stray\"}");

        // when
        ContractReport report = mockServerClient.trafficValidate(PETSTORE_SPEC);

        // then
        assertThat(report.total(), is(greaterThanOrEqualTo(1)));
        assertThat("request-violating traffic should not all pass", report.allPassed(), is(false));
        assertThat(report.failed(), is(greaterThanOrEqualTo(1)));
        boolean sawRequestError = report.results().stream()
            .anyMatch(result -> !result.passed() && !result.requestErrors().isEmpty());
        assertThat("a failing result should carry REQUEST validation errors", sawRequestError, is(true));
    }

    @Test
    public void shouldRejectBlankSpecAtClientLayer() {
        try {
            mockServerClient.trafficValidate(" ");
            throw new AssertionError("expected IllegalArgumentException for blank spec");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage(), containsString("spec"));
        }
    }

    /**
     * Issue a matching data-plane GET against the mock server over a raw socket so the matched
     * expectation's response is recorded as an EXPECTATION_RESPONSE pair in the event log.
     */
    private void sendRawGet(String path) {
        try (java.net.Socket socket = new java.net.Socket("localhost", mockServer.getLocalPort())) {
            socket.setSoTimeout(5000);
            String httpRequest = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServer.getLocalPort() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            socket.getOutputStream().write(httpRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            // drain the response so the exchange completes and is logged
            org.apache.commons.io.IOUtils.toString(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("failed to send data-plane request to " + path, e);
        }
    }

    /**
     * Issue a matching data-plane POST with a JSON body against the mock server over a raw socket so the
     * matched expectation's request/response is recorded as an EXPECTATION_RESPONSE pair in the event log,
     * carrying the request body for request-schema validation.
     */
    private void sendRawPost(String path, String jsonBody) {
        try (java.net.Socket socket = new java.net.Socket("localhost", mockServer.getLocalPort())) {
            socket.setSoTimeout(5000);
            byte[] bodyBytes = jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String httpRequest = "POST " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServer.getLocalPort() + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                jsonBody;
            socket.getOutputStream().write(httpRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            // drain the response so the exchange completes and is logged
            org.apache.commons.io.IOUtils.toString(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("failed to send data-plane POST to " + path, e);
        }
    }
}
