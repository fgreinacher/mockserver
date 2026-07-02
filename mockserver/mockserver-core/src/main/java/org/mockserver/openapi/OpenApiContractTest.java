package org.mockserver.openapi;

import com.fasterxml.jackson.databind.ObjectWriter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.openapi.examples.ExampleBuilder;
import org.mockserver.openapi.examples.JsonNodeExampleSerializer;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.*;
import java.util.function.Function;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.mockserver.openapi.OpenAPIParser.mapOperations;

/**
 * Given an OpenAPI spec, builds representative example requests for each operation,
 * sends them via an injected HTTP-send function, and validates each response against the spec.
 * <p>
 * The class is HTTP-client-agnostic; the caller wires in the real client via the
 * {@code httpSender} function passed to {@link #runContractTests}.
 */
public class OpenApiContractTest {

    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(new JsonNodeExampleSerializer()).writerWithDefaultPrettyPrinter();
    private final MockServerLogger mockServerLogger;

    public OpenApiContractTest(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * Runs contract tests for each operation in the spec.
     *
     * @param specUrlOrPayload URL, file path, or inline JSON/YAML of the OpenAPI spec
     * @param baseUrl          base URL of the service under test (e.g. "http://localhost:8080")
     * @param operationIdFilter optional filter to test only a specific operation
     * @param httpSender       function that sends an HttpRequest and returns an HttpResponse
     * @return list of per-operation results
     */
    public List<ContractTestResult> runContractTests(
        String specUrlOrPayload,
        String baseUrl,
        String operationIdFilter,
        Function<HttpRequest, HttpResponse> httpSender
    ) {
        List<ContractTestResult> results = new ArrayList<>();
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);

        // A valid OpenAPI document may have no paths (e.g. a webhooks-only or components-only spec)
        if (openAPI.getPaths() == null) {
            return results;
        }

        // Iterate over all paths and operations
        for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String pathTemplate = pathEntry.getKey();
            io.swagger.v3.oas.models.PathItem pathItem = pathEntry.getValue();

            for (Pair<String, Operation> methodOp : mapOperations(pathItem)) {
                String method = methodOp.getLeft();
                Operation operation = methodOp.getRight();
                String operationId = operation.getOperationId();

                // Apply filter
                if (isNotBlank(operationIdFilter) && !operationIdFilter.equals(operationId)) {
                    continue;
                }

                try {
                    HttpRequest exampleRequest = buildExampleRequest(openAPI, method, pathTemplate, operation);
                    HttpResponse response = httpSender.apply(exampleRequest);

                    // Validate the response
                    List<String> responseErrors = OpenAPIResponseValidator.validate(
                        specUrlOrPayload, operationId, response, mockServerLogger
                    );

                    int statusCode = response.getStatusCode() != null ? response.getStatusCode() : 0;
                    boolean passed = responseErrors.isEmpty();

                    results.add(new ContractTestResult(
                        operationId, method, pathTemplate, exampleRequest,
                        statusCode, passed, responseErrors
                    ));
                } catch (Exception e) {
                    results.add(new ContractTestResult(
                        operationId, method, pathTemplate, null,
                        0, false,
                        Collections.singletonList(OpenAPIValidationErrors.unexpectedError(
                            "contract test for operation " + operationId + " (" + method.toUpperCase() + " " + pathTemplate + ")", e, mockServerLogger))
                    ));
                }
            }
        }
        return results;
    }

    /**
     * Builds an example HttpRequest for the given operation, including path parameters,
     * query parameters, required headers, and a request body.
     */
    HttpRequest buildExampleRequest(OpenAPI openAPI, String method, String pathTemplate, Operation operation) {
        // Resolve path parameters (shared with OpenAPIConverter via OpenApiParameterExamples)
        String resolvedPath = OpenApiParameterExamples.resolvePath(pathTemplate, operation, openAPI);

        HttpRequest httpRequest = request()
            .withMethod(method)
            .withPath(resolvedPath);

        // Add query/header/cookie example parameters
        OpenApiParameterExamples.applyExampleParameters(httpRequest, operation, openAPI, null);

        // Add request body
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody != null && requestBody.getContent() != null && !requestBody.getContent().isEmpty()) {
            // Prefer application/json
            Map.Entry<String, MediaType> contentEntry = null;
            if (requestBody.getContent().containsKey("application/json")) {
                contentEntry = new AbstractMap.SimpleEntry<>("application/json", requestBody.getContent().get("application/json"));
            } else {
                contentEntry = requestBody.getContent().entrySet().iterator().next();
            }

            httpRequest.withHeader("content-type", contentEntry.getKey());
            MediaType mediaType = contentEntry.getValue();
            if (mediaType != null && mediaType.getSchema() != null) {
                String bodyString = generateExampleBody(mediaType, openAPI);
                if (bodyString != null) {
                    httpRequest.withBody(bodyString);
                }
            }
        }

        return httpRequest;
    }

    @SuppressWarnings("rawtypes")
    private String generateExampleBody(MediaType mediaType, OpenAPI openAPI) {
        // 1. Check inline example
        if (mediaType.getExample() != null) {
            return serialise(mediaType.getExample());
        }
        // 2. Check examples map
        if (mediaType.getExamples() != null && !mediaType.getExamples().isEmpty()) {
            io.swagger.v3.oas.models.examples.Example example = mediaType.getExamples().values().iterator().next();
            if (example != null && example.getValue() != null) {
                return serialise(example.getValue());
            }
        }
        // 3. Generate from schema
        Schema schema = mediaType.getSchema();
        if (schema != null) {
            // Check schema example
            if (schema.getExample() != null) {
                return serialise(schema.getExample());
            }
            // Generate from ExampleBuilder
            Map<String, Schema> definitions = openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null;
            Example generatedExample = ExampleBuilder.fromSchema(schema, definitions, null, ExampleBuilder.Direction.REQUEST);
            if (generatedExample != null) {
                return serialise(generatedExample);
            }
        }
        return null;
    }

    private String serialise(Object example) {
        try {
            return OBJECT_WRITER.writeValueAsString(example);
        } catch (Throwable throwable) {
            return String.valueOf(example);
        }
    }

    /**
     * Structured result for a single contract test operation.
     */
    public static class ContractTestResult {
        private final String operationId;
        private final String method;
        private final String path;
        private final HttpRequest requestSent;
        private final int statusCodeReceived;
        private final boolean passed;
        private final List<String> validationErrors;

        public ContractTestResult(String operationId, String method, String path, HttpRequest requestSent,
                                  int statusCodeReceived, boolean passed, List<String> validationErrors) {
            this.operationId = operationId;
            this.method = method;
            this.path = path;
            this.requestSent = requestSent;
            this.statusCodeReceived = statusCodeReceived;
            this.passed = passed;
            this.validationErrors = validationErrors;
        }

        public String getOperationId() {
            return operationId;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public HttpRequest getRequestSent() {
            return requestSent;
        }

        public int getStatusCodeReceived() {
            return statusCodeReceived;
        }

        public boolean isPassed() {
            return passed;
        }

        public List<String> getValidationErrors() {
            return validationErrors;
        }
    }
}
