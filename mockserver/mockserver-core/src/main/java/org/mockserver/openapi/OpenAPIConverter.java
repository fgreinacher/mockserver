package org.mockserver.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.AfterAction;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.openapi.examples.ExampleBuilder;
import org.mockserver.openapi.examples.GenerationOptions;
import org.mockserver.openapi.examples.JsonNodeExampleSerializer;
import org.mockserver.openapi.examples.XmlExampleSerializer;
import org.mockserver.openapi.examples.models.StringExample;
import org.mockserver.serialization.ObjectMapperFactory;

import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.OpenAPIDefinition.openAPI;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.slf4j.event.Level.ERROR;
import static org.slf4j.event.Level.WARN;

public class OpenAPIConverter {

    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(new JsonNodeExampleSerializer()).writerWithDefaultPrettyPrinter();
    private static final int MAX_REF_DEPTH = 100;
    private static final int MAX_STRUCTURE_DEPTH = 1000;
    private final MockServerLogger mockServerLogger;
    // nullable — when null, example generation resolves generateRealisticExampleValues from the static
    // ConfigurationProperties store (the historic behaviour). Supplied by callers that hold a
    // Configuration instance so a value set on the instance (e.g. via PUT /mockserver/configuration)
    // is actually honoured rather than silently ignored.
    private final Configuration configuration;

    public OpenAPIConverter(MockServerLogger mockServerLogger) {
        this(mockServerLogger, null);
    }

    public OpenAPIConverter(MockServerLogger mockServerLogger, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
    }

    public List<Expectation> buildExpectations(String specUrlOrPayload, Map<String, Object> operationsAndResponses) {
        return buildExpectations(specUrlOrPayload, operationsAndResponses, null);
    }

    /**
     * Builds a concrete example {@link org.mockserver.model.HttpRequest} for each path operation in
     * the spec, populating example {@code path}, {@code query}, {@code header}, and {@code cookie}
     * parameter values (and a request body where the operation declares one) via the shared
     * {@link OpenApiParameterExamples} helper.
     * <p>
     * This is additive and independent of {@link #buildExpectations}: that method emits an
     * {@link OpenAPIDefinition} matcher (which carries no concrete parameter values), whereas this
     * method produces realised example requests for documentation, preview, replay seeding, and
     * client/contract-test scaffolding. Webhook operations are excluded because they are not directly
     * callable as path requests.
     *
     * @return a map of {@code operationId} to its example {@link org.mockserver.model.HttpRequest}
     */
    public Map<String, org.mockserver.model.HttpRequest> buildExampleRequests(String specUrlOrPayload) {
        return buildExampleRequests(specUrlOrPayload, null);
    }

    public Map<String, org.mockserver.model.HttpRequest> buildExampleRequests(String specUrlOrPayload, Map<String, Object> operationsAndResponses) {
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);
        GenerationOptions generationOptions = GenerationOptions.fromOperationsMap(operationsAndResponses);
        Map<String, org.mockserver.model.HttpRequest> exampleRequests = new LinkedHashMap<>();
        if (openAPI.getPaths() == null) {
            return exampleRequests;
        }
        for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String pathTemplate = pathEntry.getKey();
            for (org.apache.commons.lang3.tuple.Pair<String, io.swagger.v3.oas.models.Operation> methodOp : org.mockserver.openapi.OpenAPIParser.mapOperations(pathEntry.getValue())) {
                String method = methodOp.getLeft();
                io.swagger.v3.oas.models.Operation operation = methodOp.getRight();
                if (operationsAndResponses != null && !operationsAndResponses.containsKey(operation.getOperationId())) {
                    continue;
                }
                try {
                    exampleRequests.put(operation.getOperationId(), buildExampleRequest(openAPI, method, pathTemplate, operation, generationOptions));
                } catch (Exception e) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setMessageFormat("failed to build example request for {} {} - {}")
                            .setArguments(method, pathTemplate, e.getMessage())
                    );
                }
            }
        }
        return exampleRequests;
    }

    private org.mockserver.model.HttpRequest buildExampleRequest(OpenAPI openAPI, String method, String pathTemplate, io.swagger.v3.oas.models.Operation operation, GenerationOptions generationOptions) {
        String resolvedPath = OpenApiParameterExamples.resolvePath(pathTemplate, operation, openAPI, generationOptions, configuration);
        org.mockserver.model.HttpRequest httpRequest = org.mockserver.model.HttpRequest.request()
            .withMethod(method)
            .withPath(resolvedPath);
        OpenApiParameterExamples.applyExampleParameters(httpRequest, operation, openAPI, generationOptions, configuration);

        io.swagger.v3.oas.models.parameters.RequestBody requestBody = operation.getRequestBody();
        if (requestBody != null && requestBody.getContent() != null && !requestBody.getContent().isEmpty()) {
            Map.Entry<String, MediaType> contentEntry = requestBody.getContent().containsKey("application/json")
                ? new AbstractMap.SimpleEntry<>("application/json", requestBody.getContent().get("application/json"))
                : requestBody.getContent().entrySet().iterator().next();
            httpRequest.withHeader("content-type", contentEntry.getKey());
            MediaType mediaType = contentEntry.getValue();
            if (mediaType != null && mediaType.getSchema() != null) {
                org.mockserver.openapi.examples.models.Example example = ExampleBuilder.fromSchema(
                    mediaType.getSchema(),
                    openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null,
                    generationOptions,
                    ExampleBuilder.Direction.REQUEST,
                    configuration
                );
                if (example != null) {
                    httpRequest.withBody(serialise(example));
                }
            }
        }
        return httpRequest;
    }

    public List<Expectation> buildExpectations(String specUrlOrPayload, Map<String, Object> operationsAndResponses, String contextPathPrefix) {
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);
        String specKey = deriveSpecKey(openAPI, specUrlOrPayload);
        // Optional per-run example-generation options (seed + per-field overrides) embedded under a
        // reserved namespaced key in operationsAndResponses; null when neither is supplied.
        GenerationOptions generationOptions = GenerationOptions.fromOperationsMap(operationsAndResponses);
        // Track how many times each operationId appears so we can disambiguate
        // when the same operationId maps to multiple expectations (e.g. multiple response codes)
        Map<String, Integer> operationIdCounts = new HashMap<>();
        // Collect all operations from both paths and webhooks (OAS 3.1).
        // A valid OAS 3.1 document may omit paths entirely (webhooks-only or components-only),
        // so getPaths() can be null — treat it as an empty stream rather than NPE-ing.
        java.util.stream.Stream<io.swagger.v3.oas.models.Operation> pathOperations = java.util.stream.Stream.empty();
        if (openAPI.getPaths() != null) {
            pathOperations = openAPI
                .getPaths()
                .values()
                .stream()
                .flatMap(pathItem -> pathItem.readOperations().stream());
        }
        java.util.stream.Stream<io.swagger.v3.oas.models.Operation> webhookOperations = java.util.stream.Stream.empty();
        if (openAPI.getWebhooks() != null) {
            webhookOperations = openAPI.getWebhooks()
                .values()
                .stream()
                .flatMap(pathItem -> pathItem.readOperations().stream());
        }
        return java.util.stream.Stream.concat(pathOperations, webhookOperations)
            .filter(operation -> operationsAndResponses == null || operationsAndResponses.containsKey(operation.getOperationId()))
            .map(operation -> {
                String apiResponseKey = null;
                String exampleName = null;
                if (operationsAndResponses != null) {
                    Object value = operationsAndResponses.get(operation.getOperationId());
                    if (value instanceof String stringValue) {
                        apiResponseKey = stringValue;
                    } else if (value instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> richValue = (Map<String, Object>) value;
                        apiResponseKey = richValue.get("statusCode") != null ? String.valueOf(richValue.get("statusCode")) : null;
                        exampleName = richValue.get("exampleName") != null ? String.valueOf(richValue.get("exampleName")) : null;
                    }
                }
                OpenAPIDefinition openAPIDefinition = openAPI(specUrlOrPayload, operation.getOperationId());
                if (isNotBlank(contextPathPrefix)) {
                    openAPIDefinition.withContextPathPrefix(contextPathPrefix);
                }
                Expectation expectation = new Expectation(openAPIDefinition)
                    .thenRespond(buildHttpResponse(openAPI, operation.getResponses(), apiResponseKey, exampleName, generationOptions));
                List<AfterAction> afterActions = buildAfterActions(openAPI, operation, generationOptions);
                if (!afterActions.isEmpty()) {
                    expectation.withAfterActions(afterActions);
                }
                // Assign stable deterministic id: openapi:<specKey>:<operationId>[:<n>]
                String operationId = operation.getOperationId();
                int count = operationIdCounts.merge(operationId, 1, Integer::sum);
                String stableId = OpenApiSyncPlanner.OPENAPI_ID_PREFIX + specKey + ":" + operationId;
                if (count > 1) {
                    stableId += ":" + count;
                }
                expectation.withId(stableId);
                return expectation;
            })
            .collect(Collectors.toList());
    }

    /**
     * Derives a stable, collision-resistant spec key from the parsed OpenAPI object
     * and its source. The key combines the sanitized title (for human readability)
     * with a short hash of the spec <em>source identity</em> (URL/file reference or
     * inline payload), so two distinct specs that share the same {@code info.title}
     * still get distinct namespaces and never prune/overwrite each other.
     *
     * <p>See {@link OpenApiSyncPlanner#deriveSpecKey(String, String)} for the exact
     * identity semantics (URL vs inline payload) and the deliberate trade-off for
     * edited inline payloads.
     */
    static String deriveSpecKey(OpenAPI openAPI, String specUrlOrPayload) {
        String title = null;
        if (openAPI.getInfo() != null) {
            title = openAPI.getInfo().getTitle();
        }
        return OpenApiSyncPlanner.deriveSpecKey(title, specUrlOrPayload);
    }

    private List<AfterAction> buildAfterActions(OpenAPI openAPI, io.swagger.v3.oas.models.Operation operation, GenerationOptions generationOptions) {
        List<AfterAction> afterActions = new ArrayList<>();
        Map<String, io.swagger.v3.oas.models.callbacks.Callback> callbacks = operation.getCallbacks();
        if (callbacks == null || callbacks.isEmpty()) {
            return afterActions;
        }
        for (Map.Entry<String, io.swagger.v3.oas.models.callbacks.Callback> callbackEntry : callbacks.entrySet()) {
            io.swagger.v3.oas.models.callbacks.Callback callback = callbackEntry.getValue();
            if (callback == null) {
                continue;
            }
            for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> pathEntry : callback.entrySet()) {
                String callbackUrl = pathEntry.getKey();
                io.swagger.v3.oas.models.PathItem pathItem = pathEntry.getValue();
                if (pathItem == null) {
                    continue;
                }
                for (Map.Entry<io.swagger.v3.oas.models.PathItem.HttpMethod, io.swagger.v3.oas.models.Operation> opEntry : pathItem.readOperationsMap().entrySet()) {
                    io.swagger.v3.oas.models.PathItem.HttpMethod method = opEntry.getKey();
                    io.swagger.v3.oas.models.Operation callbackOp = opEntry.getValue();
                    try {
                        String resolvedUrl = resolveCallbackUrl(callbackUrl);
                        org.mockserver.model.HttpRequest callbackRequest = org.mockserver.model.HttpRequest.request()
                            .withMethod(method.name());
                        if (OpenApiRuntimeExpressionResolver.containsExpression(resolvedUrl)) {
                            // URL contains runtime expressions — store verbatim for fire-time resolution
                            callbackRequest.withPath(resolvedUrl);
                        } else if (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://")) {
                            URI uri = new URI(resolvedUrl);
                            callbackRequest
                                .withPath(uri.getPath() != null ? uri.getPath() : "/")
                                .withHeader("Host", uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : ""))
                                .withSecure(resolvedUrl.startsWith("https://"));
                        } else {
                            callbackRequest.withPath(resolvedUrl);
                        }
                        if (callbackOp.getRequestBody() != null && callbackOp.getRequestBody().getContent() != null) {
                            callbackOp.getRequestBody().getContent().entrySet().stream().findFirst().ifPresent(contentEntry -> {
                                callbackRequest.withHeader("Content-Type", contentEntry.getKey());
                                MediaType mediaType = contentEntry.getValue();
                                if (mediaType != null && mediaType.getSchema() != null) {
                                    org.mockserver.openapi.examples.models.Example example = ExampleBuilder.fromSchema(
                                        mediaType.getSchema(),
                                        openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null,
                                        generationOptions,
                                        ExampleBuilder.Direction.REQUEST,
                                        configuration
                                    );
                                    if (example != null) {
                                        callbackRequest.withBody(serialise(example));
                                    }
                                }
                            });
                        }
                        afterActions.add(new AfterAction().withHttpRequest(callbackRequest));
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(WARN)
                                .setMessageFormat("failed to build callback after-action for {} {} - {}")
                                .setArguments(method, callbackUrl, e.getMessage())
                        );
                    }
                }
            }
        }
        return afterActions;
    }

    /**
     * Preserves OpenAPI runtime expressions verbatim in callback URLs.
     * Expressions like {@code {$request.body#/callbackUrl}} are kept as-is and
     * resolved at callback fire-time by {@link OpenApiRuntimeExpressionResolver}.
     */
    private String resolveCallbackUrl(String callbackUrl) {
        return callbackUrl;
    }

    private HttpResponse buildHttpResponse(OpenAPI openAPI, ApiResponses apiResponses, String apiResponseKey, String exampleName, GenerationOptions generationOptions) {
        HttpResponse response = response();
        Optional
            .ofNullable(apiResponses)
            .flatMap(notNullApiResponses -> selectApiResponse(notNullApiResponses, apiResponseKey))
            .ifPresent(apiResponse -> {
                Integer statusCode = parseResponseStatusCode(apiResponse.getKey());
                if (statusCode != null) {
                    response.withStatusCode(statusCode);
                }
                Optional
                    .ofNullable(apiResponse.getValue().getHeaders())
                    .map(Map::entrySet)
                    .map(Set::stream)
                    .ifPresent(stream -> stream
                        .forEach(entry -> {
                            Header value = entry.getValue();
                            Object headerExample = findHeaderExample(value, openAPI, exampleName);
                            if (headerExample != null) {
                                response.withHeader(entry.getKey(), String.valueOf(headerExample));
                            } else if (value.getSchema() != null) {
                                org.mockserver.openapi.examples.models.Example generatedExample = ExampleBuilder.fromSchema(value.getSchema(), openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null, generationOptions, ExampleBuilder.Direction.RESPONSE, configuration);
                                if (generatedExample instanceof StringExample stringExample) {
                                    response.withHeader(entry.getKey(), stringExample.getValue());
                                } else {
                                    response.withHeader(entry.getKey(), serialise(generatedExample));
                                }
                            }
                        })
                    );
                Optional
                    .ofNullable(apiResponse.getValue().getContent())
                    .flatMap(content -> content
                        .entrySet()
                        .stream()
                        .findFirst()
                    )
                    .ifPresent(contentType -> {
                        response.withHeader("content-type", contentType.getKey());
                        Optional
                            .ofNullable(contentType.getValue())
                            .ifPresent(mediaType -> {
                                Object example = findExample(mediaType, openAPI, exampleName);
                                if (example != null) {
                                    if (isJsonContentType(contentType.getKey())) {
                                        response.withBody(json(serialise(example)));
                                    } else {
                                        response.withBody(String.valueOf(example));
                                    }
                                } else if (mediaType.getSchema() != null) {
                                    Object schemaExample = resolveSchemaExample(mediaType.getSchema(), openAPI);
                                    if (schemaExample != null) {
                                        if (isJsonContentType(contentType.getKey())) {
                                            response.withBody(json(serialise(schemaExample)));
                                        } else {
                                            response.withBody(serialise(schemaExample));
                                        }
                                    } else {
                                        org.mockserver.openapi.examples.models.Example generatedExample = ExampleBuilder.fromSchema(mediaType.getSchema(), openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null, generationOptions, ExampleBuilder.Direction.RESPONSE, configuration);
                                        if (generatedExample instanceof StringExample stringExample) {
                                            if (isJsonContentType(contentType.getKey())) {
                                                response.withBody(json(serialise(stringExample.getValue())));
                                            } else {
                                                response.withBody(stringExample.getValue());
                                            }
                                        } else if (generatedExample != null) {
                                            if (isJsonContentType(contentType.getKey())) {
                                                response.withBody(json(serialise(generatedExample)));
                                            } else if (isXmlContentType(contentType.getKey())) {
                                                // common path: no inline example, so generate a real,
                                                // spec-correct XML body from the generated Example tree
                                                // rather than emitting a JSON-shaped string in an XML body
                                                response.withBody(new XmlExampleSerializer().serialize(generatedExample));
                                            } else {
                                                response.withBody(serialise(generatedExample));
                                            }
                                        }
                                    }
                                }
                            });
                    });
            });
        return response;
    }

    /**
     * Selects the response entry to render for the requested {@code apiResponseKey}.
     * <ul>
     *   <li>blank key: the first defined response (unchanged historical behaviour);</li>
     *   <li>key present: the matching response;</li>
     *   <li>key non-blank but absent: a WARN naming the requested key vs the available keys, then a
     *       deliberate fall back to the first defined response — never a silently empty 200.</li>
     * </ul>
     */
    private Optional<Map.Entry<String, io.swagger.v3.oas.models.responses.ApiResponse>> selectApiResponse(ApiResponses apiResponses, String apiResponseKey) {
        if (isBlank(apiResponseKey)) {
            return apiResponses.entrySet().stream().findFirst();
        }
        Optional<Map.Entry<String, io.swagger.v3.oas.models.responses.ApiResponse>> exactMatch = apiResponses
            .entrySet()
            .stream()
            .filter(entry -> entry.getKey().equals(apiResponseKey))
            .findFirst();
        if (exactMatch.isPresent()) {
            return exactMatch;
        }
        Optional<Map.Entry<String, io.swagger.v3.oas.models.responses.ApiResponse>> fallback = apiResponses.entrySet().stream().findFirst();
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(WARN)
                .setMessageFormat("requested OpenAPI response status code {} not defined for operation - available response keys are {} - falling back to {}")
                .setArguments(apiResponseKey, apiResponses.keySet(), fallback.map(Map.Entry::getKey).orElse("none"))
        );
        return fallback;
    }

    /**
     * Resolves an OpenAPI response-map key to a concrete HTTP status code.
     * <ul>
     *   <li>a literal three-digit key (e.g. {@code "200"}) becomes that status code;</li>
     *   <li>a range key (e.g. {@code "2XX"}, {@code "4xx"} — legal per OpenAPI 3.x) becomes the
     *       first code in the range, i.e. {@code firstDigit * 100} ({@code "2XX"} -> 200);</li>
     *   <li>{@code "default"} (case-insensitive) returns {@code null}, leaving the default status as-is;</li>
     *   <li>any other unparseable key is logged at WARN and returns {@code null}.</li>
     * </ul>
     * Never throws.
     */
    private Integer parseResponseStatusCode(String key) {
        if (key == null || key.equalsIgnoreCase("default")) {
            return null;
        }
        if (key.matches("\\d{3}")) {
            return Integer.parseInt(key);
        }
        if (key.matches("[1-5][xX]{2}")) {
            return (key.charAt(0) - '0') * 100;
        }
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(WARN)
                .setMessageFormat("unable to parse OpenAPI response status code key {} - leaving default status code")
                .setArguments(key)
        );
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object resolveSchemaExample(io.swagger.v3.oas.models.media.Schema schema, OpenAPI openAPI) {
        return resolveSchemaExample(schema, openAPI, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object resolveSchemaExample(io.swagger.v3.oas.models.media.Schema schema, OpenAPI openAPI, Set<io.swagger.v3.oas.models.media.Schema> activeStack) {
        if (schema == null || !activeStack.add(schema)) {
            return null;
        }
        try {
            if (schema.getExample() != null) {
                Object example = ExampleBuilder.normalizeFlattenedExample(schema.getExample(), schema);
                return resolveExampleRefs(normalizeTemporalExample(schema, example), openAPI);
            }
            if (schema instanceof ComposedSchema composedSchema) {
                if (composedSchema.getAllOf() != null) {
                    Map<String, Object> merged = new LinkedHashMap<>();
                    for (Schema<?> subSchema : composedSchema.getAllOf()) {
                        Object subExample = resolveSchemaExample(subSchema, openAPI, activeStack);
                        if (subExample instanceof Map) {
                            merged.putAll((Map<String, Object>) subExample);
                        }
                    }
                    if (composedSchema.getProperties() != null) {
                        Map<String, Schema> ownProperties = composedSchema.getProperties();
                        for (Map.Entry<String, Schema> entry : ownProperties.entrySet()) {
                            Object propExample = resolveSchemaExample(entry.getValue(), openAPI, activeStack);
                            // Returning null for the WHOLE object when a property has no inline example is
                            // load-bearing: it makes the caller fall back to ExampleBuilder.fromSchema, which
                            // generates a COMPLETE example (samples for array/enum/sample-only properties).
                            // Keeping a partial object here instead silently DROPS those properties from the
                            // response body (regression: petstore photoUrls/tags/status disappeared).
                            if (propExample == null) {
                                return null;
                            }
                            merged.put(entry.getKey(), propExample);
                        }
                    }
                    return merged.isEmpty() ? null : merged;
                }
                if (composedSchema.getAnyOf() != null) {
                    for (Schema<?> subSchema : composedSchema.getAnyOf()) {
                        Object subExample = resolveSchemaExample(subSchema, openAPI, activeStack);
                        if (subExample != null) {
                            return subExample;
                        }
                    }
                }
                if (composedSchema.getOneOf() != null) {
                    for (Schema<?> subSchema : composedSchema.getOneOf()) {
                        Object subExample = resolveSchemaExample(subSchema, openAPI, activeStack);
                        if (subExample != null) {
                            return subExample;
                        }
                    }
                }
            }
            if (schema.getProperties() != null) {
                Map<String, io.swagger.v3.oas.models.media.Schema> properties = schema.getProperties();
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<String, io.swagger.v3.oas.models.media.Schema> entry : properties.entrySet()) {
                    Object propExample = resolveSchemaExample(entry.getValue(), openAPI, activeStack);
                    // Returning null for the whole object when one property has no inline example is
                    // load-bearing: it makes the caller fall back to ExampleBuilder.fromSchema, which produces
                    // a COMPLETE example (samples for properties without an explicit example). Keeping a partial
                    // object here silently DROPS the sample-only properties from the response body.
                    if (propExample == null) {
                        return null;
                    }
                    result.put(entry.getKey(), propExample);
                }
                return result.isEmpty() ? null : result;
            }
            if (schema instanceof io.swagger.v3.oas.models.media.ArraySchema arraySchema) {
                if (arraySchema.getItems() != null) {
                    Object itemExample = resolveSchemaExample(arraySchema.getItems(), openAPI, activeStack);
                    if (itemExample != null) {
                        return Collections.singletonList(itemExample);
                    }
                }
            }
            return null;
        } finally {
            activeStack.remove(schema);
        }
    }

    /**
     * swagger-parser deserialises an inline {@code example} on a {@code format: date}/{@code date-time}
     * schema into a {@link Date}/{@link java.time.OffsetDateTime}. Serialising those raw temporal objects
     * with Jackson emits epoch-millis numbers instead of the ISO string the spec author wrote, so render
     * them back to the schema's string form here (matching {@link ExampleBuilder}'s generated-sample path).
     */
    private static Object normalizeTemporalExample(Schema<?> schema, Object example) {
        if (example instanceof Date && schema instanceof DateSchema) {
            DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format((Date) example);
        }
        if (example instanceof java.time.OffsetDateTime && schema instanceof DateTimeSchema) {
            return example.toString();
        }
        return example;
    }

    public static boolean isJsonContentType(String contentType) {
        return org.mockserver.model.MediaType.parse(contentType).isJson();
    }

    /**
     * True when the content type denotes XML — {@code application/xml}, {@code text/xml}, or any media
     * type with a {@code +xml} structured-syntax suffix (e.g. {@code application/atom+xml}). Used to route
     * a generated example through {@link XmlExampleSerializer} so an XML response yields a real XML body
     * rather than a JSON-shaped string.
     */
    public static boolean isXmlContentType(String contentType) {
        if (isBlank(contentType)) {
            return false;
        }
        org.mockserver.model.MediaType mediaType = org.mockserver.model.MediaType.parse(contentType);
        String type = mediaType.getType();
        String subtype = mediaType.getSubtype();
        if (type == null || subtype == null) {
            return false;
        }
        type = type.toLowerCase(Locale.ROOT);
        subtype = subtype.toLowerCase(Locale.ROOT);
        if ("application".equals(type) && "xml".equals(subtype)) {
            return true;
        }
        if ("text".equals(type) && "xml".equals(subtype)) {
            return true;
        }
        return subtype.endsWith("+xml");
    }

    private void warnExampleNameNotFound(String exampleName, Map<String, Example> availableExamples) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(WARN)
                .setMessageFormat("requested OpenAPI example name {} not defined - available example names are {} - falling back to the first defined example")
                .setArguments(exampleName, availableExamples != null ? availableExamples.keySet() : Collections.emptySet())
        );
    }

    private Object findHeaderExample(Header value, OpenAPI openAPI, String exampleName) {
        if (isNotBlank(exampleName) && (value.getExamples() == null || !value.getExamples().containsKey(exampleName))) {
            warnExampleNameNotFound(exampleName, value.getExamples());
        }
        if (exampleName != null && value.getExamples() != null && value.getExamples().containsKey(exampleName)) {
            Example example = value.getExamples().get(exampleName);
            if (example != null) {
                Object resolved = resolveExampleRefs(example.getValue(), openAPI);
                return resolved != null ? resolved : example.getValue();
            }
        }
        if (value.getExample() instanceof Example exampleObj) {
            Object resolved = resolveExampleRefs(exampleObj.getValue(), openAPI);
            return resolved != null ? resolved : exampleObj.getValue();
        } else if (value.getExample() != null) {
            return resolveExampleRefs(value.getExample(), openAPI);
        } else if (value.getExamples() != null && !value.getExamples().isEmpty()) {
            Example example = value.getExamples().values().stream().findFirst().orElse(null);
            if (example != null) {
                Object resolved = resolveExampleRefs(example.getValue(), openAPI);
                return resolved != null ? resolved : example.getValue();
            }
        }
        return null;
    }

    private Object findExample(MediaType mediaType, OpenAPI openAPI, String exampleName) {
        Object example = null;
        if (isNotBlank(exampleName) && (mediaType.getExamples() == null || !mediaType.getExamples().containsKey(exampleName))) {
            // a specific example was requested but is not defined - warn before falling back rather
            // than silently substituting a different (unrequested) named example or the inline example
            warnExampleNameNotFound(exampleName, mediaType.getExamples());
        }
        if (exampleName != null && mediaType.getExamples() != null && mediaType.getExamples().containsKey(exampleName)) {
            Example namedExample = mediaType.getExamples().get(exampleName);
            if (namedExample != null) {
                example = namedExample.getValue();
            }
        } else if (mediaType.getExample() != null) {
            Object raw = mediaType.getExample();
            if (raw instanceof Example rawExample) {
                example = rawExample.getValue();
            } else {
                example = raw;
            }
        } else if (mediaType.getExamples() != null && !mediaType.getExamples().isEmpty()) {
            Example namedExample = mediaType.getExamples().values().stream().findFirst().orElse(null);
            if (namedExample != null) {
                example = namedExample.getValue();
            }
        }
        if (example != null) {
            example = resolveExampleRefs(example, openAPI);
        }
        return example;
    }

    @SuppressWarnings("unchecked")
    private Object resolveExampleRefs(Object value, OpenAPI openAPI) {
        return resolveExampleRefs(value, openAPI, new HashSet<>(), 0, 0);
    }

    @SuppressWarnings("unchecked")
    private Object resolveExampleRefs(Object value, OpenAPI openAPI, Set<String> activeRefChain, int refDepth, int structureDepth) {
        // both depth guards use >= so the limit is the maximum *processed* depth (consistent with the
        // refDepth guard below); truncation is logged at WARN, matching the ref-depth and cycle guards,
        // so a silently-truncated example never goes unreported
        if (structureDepth >= MAX_STRUCTURE_DEPTH) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(WARN)
                    .setMessageFormat("example structure exceeded maximum nesting depth of {} — returning literal value")
                    .setArguments(MAX_STRUCTURE_DEPTH)
            );
            return value;
        }
        if (value instanceof ObjectNode node) {
            if (node.size() == 1 && node.has("$ref")) {
                String ref = node.get("$ref").asText();
                if (activeRefChain.contains(ref)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setMessageFormat("cyclic $ref detected for {} — returning literal value")
                            .setArguments(ref)
                    );
                    return value;
                }
                if (refDepth >= MAX_REF_DEPTH) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setMessageFormat("$ref resolution exceeded maximum depth of {} for {} — returning literal value")
                            .setArguments(MAX_REF_DEPTH, ref)
                    );
                    return value;
                }
                Object resolved = resolveRef(ref, openAPI);
                if (resolved != null) {
                    activeRefChain.add(ref);
                    Object result = resolveExampleRefs(resolved, openAPI, activeRefChain, refDepth + 1, structureDepth + 1);
                    activeRefChain.remove(ref);
                    return result;
                }
                // unresolvable internal $ref - drop it (resolveRef already logged) rather than leaking
                // the literal {"$ref": "..."} node into the generated response body
                return null;
            }
            ObjectNode resolvedNode = node.objectNode();
            node.properties().forEach(entry -> {
                Object resolvedField = resolveExampleRefs(entry.getValue(), openAPI, activeRefChain, refDepth, structureDepth + 1);
                if (resolvedField instanceof JsonNode) {
                    resolvedNode.set(entry.getKey(), (JsonNode) resolvedField);
                } else {
                    resolvedNode.putPOJO(entry.getKey(), resolvedField);
                }
            });
            return resolvedNode;
        } else if (value instanceof ArrayNode node) {
            ArrayNode resolvedNode = node.arrayNode();
            for (JsonNode item : node) {
                Object resolvedItem = resolveExampleRefs(item, openAPI, activeRefChain, refDepth, structureDepth + 1);
                if (resolvedItem instanceof JsonNode) {
                    resolvedNode.add((JsonNode) resolvedItem);
                } else {
                    resolvedNode.addPOJO(resolvedItem);
                }
            }
            return resolvedNode;
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.size() == 1 && map.containsKey("$ref")) {
                String ref = String.valueOf(map.get("$ref"));
                if (activeRefChain.contains(ref)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setMessageFormat("cyclic $ref detected for {} — returning literal value")
                            .setArguments(ref)
                    );
                    return value;
                }
                if (refDepth >= MAX_REF_DEPTH) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setMessageFormat("$ref resolution exceeded maximum depth of {} for {} — returning literal value")
                            .setArguments(MAX_REF_DEPTH, ref)
                    );
                    return value;
                }
                Object resolved = resolveRef(ref, openAPI);
                if (resolved != null) {
                    activeRefChain.add(ref);
                    Object result = resolveExampleRefs(resolved, openAPI, activeRefChain, refDepth + 1, structureDepth + 1);
                    activeRefChain.remove(ref);
                    return result;
                }
                // unresolvable internal $ref - drop it (resolveRef already logged) rather than leaking
                // the literal {"$ref": "..."} node into the generated response body
                return null;
            }
            Map<String, Object> resolvedMap = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                resolvedMap.put(entry.getKey(), resolveExampleRefs(entry.getValue(), openAPI, activeRefChain, refDepth, structureDepth + 1));
            }
            return resolvedMap;
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            List<Object> resolvedList = new ArrayList<>(list.size());
            for (Object item : list) {
                resolvedList.add(resolveExampleRefs(item, openAPI, activeRefChain, refDepth, structureDepth + 1));
            }
            return resolvedList;
        }
        return value;
    }

    /**
     * Resolves an internal {@code $ref} appearing inside an example value. Handles
     * {@code #/components/examples/<name>} and any JSON-pointer suffix beyond the example name
     * (e.g. {@code #/components/examples/<name>/value/<field>}) by navigating into the resolved
     * example value. Any {@code $ref} that cannot be resolved (unsupported target, missing component,
     * or a pointer suffix that does not navigate) is logged at WARN and returns {@code null}; the
     * caller drops the node rather than leaking the literal {@code $ref} into the response body.
     */
    private Object resolveRef(String ref, OpenAPI openAPI) {
        if (ref != null && ref.startsWith("#/components/examples/") && openAPI.getComponents() != null && openAPI.getComponents().getExamples() != null) {
            String path = ref.substring("#/components/examples/".length());
            String[] parts = path.split("/");
            if (parts.length >= 1 && isNotBlank(parts[0])) {
                Example componentExample = openAPI.getComponents().getExamples().get(parts[0]);
                if (componentExample != null) {
                    Object resolved = componentExample.getValue();
                    // navigate any JSON-pointer suffix beyond the example name (parts[1..]);
                    // a "value" segment immediately after the example name addresses the Example.value
                    // object itself, so skip it
                    int from = (parts.length >= 2 && "value".equals(parts[1])) ? 2 : 1;
                    resolved = navigatePointer(resolved, parts, from);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        }
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(WARN)
                .setMessageFormat("unable to resolve $ref {} in example — dropping unresolved reference")
                .setArguments(ref)
        );
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object navigatePointer(Object current, String[] parts, int from) {
        for (int i = from; i < parts.length && current != null; i++) {
            String segment = parts[i].replace("~1", "/").replace("~0", "~");
            if (current instanceof JsonNode jsonNode) {
                current = jsonNode.get(segment);
            } else if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(segment);
            } else if (current instanceof List) {
                try {
                    List<Object> list = (List<Object>) current;
                    int index = Integer.parseInt(segment);
                    current = (index >= 0 && index < list.size()) ? list.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    private String serialise(Object example) {
        try {
            return OBJECT_WRITER.writeValueAsString(example);
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(ERROR)
                    .setMessageFormat("exception while serialising " + example.getClass() + " {}")
                    .setArguments(example)
                    .setThrowable(throwable)
            );
            return "";
        }
    }
}
