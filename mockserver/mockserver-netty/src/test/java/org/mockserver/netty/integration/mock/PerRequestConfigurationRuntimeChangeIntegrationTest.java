package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.Metrics;
import org.mockserver.netty.MockServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.OpenAPIDefinition.openAPI;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * The per-request reads of metricsEnabled, dataPlaneAuthenticationRequired, otelPropagateTraceContext,
 * validateRequestsAgainstOpenApiSpec and defaultResponseHeaders are memoised in {@code Configuration}.
 * A running server whose configuration falls through to the JVM-wide defaults must still apply a runtime
 * change of each one to the very next request, and keep applying every later change.
 */
public class PerRequestConfigurationRuntimeChangeIntegrationTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    // The mock matcher checks a multipart body only for the presence of its required fields, while request
    // validation also checks their values - so "id=abc" matches the expectation but fails validation.
    private static final String SPEC = "{\n" +
        "  \"openapi\": \"3.0.0\",\n" +
        "  \"info\": {\"title\": \"runtime-change\", \"version\": \"1\"},\n" +
        "  \"paths\": {\n" +
        "    \"/items\": {\n" +
        "      \"post\": {\n" +
        "        \"operationId\": \"createItem\",\n" +
        "        \"requestBody\": {\"required\": true, \"content\": {\"multipart/form-data\": {\"schema\": {\n" +
        "          \"type\": \"object\", \"required\": [\"id\"], \"properties\": {\"id\": {\"type\": \"integer\"}}\n" +
        "        }}}},\n" +
        "        \"responses\": {\"201\": {\"description\": \"created\"}}\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "}";
    private static final String BOUNDARY = "runtimeChangeBoundary";

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private HttpClient httpClient;

    private boolean originalMetricsEnabled;
    private boolean originalDataPlaneAuthenticationRequired;
    private String originalDataPlaneBearerAuthenticationToken;
    private boolean originalOtelPropagateTraceContext;
    private boolean originalOtelGenerateTraceId;
    private boolean originalValidateRequestsAgainstOpenApiSpec;
    private String originalDefaultResponseHeaders;

    @Before
    public void startServer() {
        originalMetricsEnabled = ConfigurationProperties.metricsEnabled();
        originalDataPlaneAuthenticationRequired = ConfigurationProperties.dataPlaneAuthenticationRequired();
        originalDataPlaneBearerAuthenticationToken = ConfigurationProperties.dataPlaneBearerAuthenticationToken();
        originalOtelPropagateTraceContext = ConfigurationProperties.otelPropagateTraceContext();
        originalOtelGenerateTraceId = ConfigurationProperties.otelGenerateTraceId();
        originalValidateRequestsAgainstOpenApiSpec = ConfigurationProperties.validateRequestsAgainstOpenApiSpec();
        originalDefaultResponseHeaders = ConfigurationProperties.defaultResponseHeaders();

        ConfigurationProperties.metricsEnabled(true);
        ConfigurationProperties.dataPlaneAuthenticationRequired(false);
        ConfigurationProperties.dataPlaneBearerAuthenticationToken("runtime-token");
        ConfigurationProperties.otelPropagateTraceContext(false);
        ConfigurationProperties.otelGenerateTraceId(false);
        ConfigurationProperties.validateRequestsAgainstOpenApiSpec(false);
        ConfigurationProperties.defaultResponseHeaders("");

        // every per-request property is left null on the instance, so each one falls through to the
        // JVM-wide value that the tests change at runtime
        mockServer = new MockServer(configuration().useNativeTransport(false), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient
            .when(request().withPath("/runtime"))
            .respond(response().withStatusCode(200).withBody("ok"));
        mockServerClient
            .when(openAPI(SPEC, "createItem"))
            .respond(response().withStatusCode(201).withBody("created"));
        httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        ConfigurationProperties.metricsEnabled(originalMetricsEnabled);
        ConfigurationProperties.dataPlaneAuthenticationRequired(originalDataPlaneAuthenticationRequired);
        ConfigurationProperties.dataPlaneBearerAuthenticationToken(originalDataPlaneBearerAuthenticationToken);
        ConfigurationProperties.otelPropagateTraceContext(originalOtelPropagateTraceContext);
        ConfigurationProperties.otelGenerateTraceId(originalOtelGenerateTraceId);
        ConfigurationProperties.validateRequestsAgainstOpenApiSpec(originalValidateRequestsAgainstOpenApiSpec);
        ConfigurationProperties.defaultResponseHeaders(originalDefaultResponseHeaders);
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + mockServer.getLocalPort() + path))
            .timeout(Duration.ofSeconds(15))
            .GET();
        if (headers.length > 0) {
            builder.headers(headers);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> createItem(String id) throws Exception {
        String body = "--" + BOUNDARY + "\r\n" +
            "Content-Disposition: form-data; name=\"id\"\r\n" +
            "\r\n" +
            id + "\r\n" +
            "--" + BOUNDARY + "--\r\n";
        return httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + mockServer.getLocalPort() + "/items"))
                .timeout(Duration.ofSeconds(15))
                .header("content-type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test(timeout = 60000)
    public void shouldApplyEachRuntimeChangeOfDefaultResponseHeadersToTheNextRequest() throws Exception {
        assertThat(get("/runtime").headers().firstValue("X-Runtime"), is(Optional.empty()));

        ConfigurationProperties.defaultResponseHeaders("X-Runtime=first");
        assertThat(get("/runtime").headers().firstValue("X-Runtime"), is(Optional.of("first")));

        ConfigurationProperties.defaultResponseHeaders("X-Runtime=second");
        assertThat(get("/runtime").headers().firstValue("X-Runtime"), is(Optional.of("second")));

        ConfigurationProperties.defaultResponseHeaders("");
        assertThat(get("/runtime").headers().firstValue("X-Runtime"), is(Optional.empty()));
    }

    @Test(timeout = 60000)
    public void shouldApplyEachRuntimeChangeOfDataPlaneAuthenticationRequiredToTheNextRequest() throws Exception {
        assertThat(get("/runtime").statusCode(), is(200));

        ConfigurationProperties.dataPlaneAuthenticationRequired(true);
        assertThat(get("/runtime").statusCode(), is(401));
        assertThat(get("/runtime", "Authorization", "Bearer runtime-token").statusCode(), is(200));

        ConfigurationProperties.dataPlaneAuthenticationRequired(false);
        assertThat(get("/runtime").statusCode(), is(200));

        ConfigurationProperties.dataPlaneAuthenticationRequired(true);
        assertThat(get("/runtime").statusCode(), is(401));
    }

    @Test(timeout = 60000)
    public void shouldApplyEachRuntimeChangeOfOtelPropagateTraceContextToTheNextRequest() throws Exception {
        assertThat(get("/runtime", "traceparent", TRACEPARENT).headers().firstValue("traceparent"), is(Optional.empty()));

        ConfigurationProperties.otelPropagateTraceContext(true);
        assertThat(get("/runtime", "traceparent", TRACEPARENT).headers().firstValue("traceparent"), is(Optional.of(TRACEPARENT)));

        ConfigurationProperties.otelPropagateTraceContext(false);
        assertThat(get("/runtime", "traceparent", TRACEPARENT).headers().firstValue("traceparent"), is(Optional.empty()));

        ConfigurationProperties.otelPropagateTraceContext(true);
        assertThat(get("/runtime", "traceparent", TRACEPARENT).headers().firstValue("traceparent"), is(Optional.of(TRACEPARENT)));
    }

    @Test(timeout = 60000)
    public void shouldApplyEachRuntimeChangeOfValidateRequestsAgainstOpenApiSpecToTheNextRequest() throws Exception {
        String invalid = "abc";
        assertThat(createItem(invalid).statusCode(), is(201));

        ConfigurationProperties.validateRequestsAgainstOpenApiSpec(true);
        HttpResponse<String> rejected = createItem(invalid);
        assertThat(rejected.statusCode(), is(400));
        assertThat(rejected.body(), containsString("OpenAPI request validation failed"));

        ConfigurationProperties.validateRequestsAgainstOpenApiSpec(false);
        assertThat(createItem(invalid).statusCode(), is(201));

        ConfigurationProperties.validateRequestsAgainstOpenApiSpec(true);
        assertThat(createItem(invalid).statusCode(), is(400));
    }

    @Test(timeout = 60000)
    public void shouldApplyEachRuntimeChangeOfMetricsEnabledToTheNextRequest() throws Exception {
        // the server's Metrics instance registered its gauges at start (metricsEnabled was true); the
        // per-request increment is additionally gated on the live configuration value
        int before = Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        get("/runtime");
        int afterEnabled = Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        assertThat(afterEnabled - before, is(1));

        ConfigurationProperties.metricsEnabled(false);
        get("/runtime");
        assertThat(Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT), is(afterEnabled));

        ConfigurationProperties.metricsEnabled(true);
        get("/runtime");
        assertThat(Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT), is(afterEnabled + 1));
    }
}
