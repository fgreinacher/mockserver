package org.mockserver.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.validator.jsonschema.JsonSchemaValidator;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.mockserver.openapi.OpenAPIParser.mapOperations;

@SuppressWarnings("rawtypes")
public class OpenAPIRequestValidator {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    public static List<String> validate(String specUrlOrPayload, HttpRequest request, MockServerLogger logger) {
        return validate(specUrlOrPayload, request, logger, null);
    }

    /**
     * Validates a request against an OpenAPI spec. When {@code matchedPathTemplate} is supplied (e.g.
     * threaded through from {@link OpenApiTrafficValidator#findMatchingOperation}), it is used to map
     * the concrete request path back onto the matched path template for {@code path} parameter
     * extraction; otherwise the template is resolved here.
     */
    public static List<String> validate(String specUrlOrPayload, HttpRequest request, MockServerLogger logger, String matchedPathTemplate) {
        List<String> errors = new ArrayList<>();
        try {
            OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, logger);
            String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
            String requestMethod = request.getMethod() != null ? request.getMethod().getValue().toLowerCase() : "get";

            Optional<Pair<String, Operation>> matchedOperation = findMatchingOperation(openAPI, requestPath, requestMethod);
            if (!matchedOperation.isPresent()) {
                errors.add("no operation found matching " + requestMethod.toUpperCase() + " " + requestPath + " in OpenAPI spec");
                return errors;
            }

            String pathTemplate = isNotBlank(matchedPathTemplate) ? matchedPathTemplate : matchedOperation.get().getLeft();
            Operation operation = matchedOperation.get().getRight();
            validateParameters(operation, request, pathTemplate, logger, errors);
            validateRequestBody(operation, request, logger, errors);
        } catch (Throwable throwable) {
            errors.add(OpenAPIValidationErrors.unexpectedError("OpenAPI request validation", throwable, logger));
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static Optional<Pair<String, Operation>> findMatchingOperation(OpenAPI openAPI, String requestPath, String requestMethod) {
        // Search paths first. A valid OAS 3.1 document may omit paths entirely, so getPaths() can be
        // null — treat it as empty rather than NPE-ing. Among the path templates that match the
        // request, prefer the most specific (fewest "{...}" placeholders) so a concrete path such as
        // "/users/me" wins over a templated "/users/{id}", per the OpenAPI path-precedence rules.
        Optional<Pair<String, Operation>> result = Optional.empty();
        if (openAPI.getPaths() != null) {
            result = openAPI
                .getPaths()
                .entrySet()
                .stream()
                .filter(entry -> pathMatches(entry.getKey(), requestPath))
                .sorted(Comparator.comparingInt((Map.Entry<String, io.swagger.v3.oas.models.PathItem> entry) -> placeholderCount(entry.getKey())))
                .flatMap(entry -> mapOperations(entry.getValue()).stream()
                    .filter(pair -> pair.getLeft().equalsIgnoreCase(requestMethod))
                    .map(pair -> Pair.of(entry.getKey(), pair.getRight())))
                .findFirst();
        }
        // Then search webhooks (OAS 3.1) — use webhook name as pseudo-path
        if (result.isEmpty() && openAPI.getWebhooks() != null) {
            result = openAPI.getWebhooks()
                .entrySet()
                .stream()
                .filter(entry -> pathMatches("/webhook:" + entry.getKey(), requestPath))
                .flatMap(entry -> mapOperations(entry.getValue()).stream()
                    .filter(pair -> pair.getLeft().equalsIgnoreCase(requestMethod))
                    .map(pair -> Pair.of("/webhook:" + entry.getKey(), pair.getRight())))
                .findFirst();
        }
        return result;
    }

    /**
     * Counts the number of {@code {...}} placeholder segments in a path template. Used to rank
     * matching templates so the most specific (fewest placeholders) is selected first — i.e. a
     * concrete path beats a templated one per OpenAPI path-precedence rules.
     */
    static int placeholderCount(String templatePath) {
        int count = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{[^}]+}").matcher(templatePath);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean pathMatches(String templatePath, String actualPath) {
        StringBuilder regex = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{[^}]+}").matcher(templatePath);
        int lastEnd = 0;
        while (matcher.find()) {
            regex.append(java.util.regex.Pattern.quote(templatePath.substring(lastEnd, matcher.start())));
            regex.append("[^/]+");
            lastEnd = matcher.end();
        }
        regex.append(java.util.regex.Pattern.quote(templatePath.substring(lastEnd)));
        return actualPath.matches(regex.toString());
    }

    /**
     * Validates the operation's declared path/query/header/cookie parameters against the request:
     * enforces {@code required} presence in the matching {@code in} location, and validates each
     * supplied value against the parameter's {@code schema} using the same {@link JsonSchemaValidator}
     * mechanism the body path uses. Additive — accumulates human-readable errors and never mutates
     * the request or body-validation behaviour.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void validateParameters(Operation operation, HttpRequest request, String pathTemplate, MockServerLogger logger, List<String> errors) {
        List<Parameter> parameters = operation.getParameters();
        if (parameters == null || parameters.isEmpty()) {
            return;
        }
        Map<String, String> pathValues = extractPathParameterValues(pathTemplate, request);
        for (Parameter parameter : parameters) {
            if (parameter == null || parameter.getName() == null || parameter.getIn() == null) {
                continue;
            }
            String name = parameter.getName();
            String in = parameter.getIn();
            boolean required = "path".equals(in) || Boolean.TRUE.equals(parameter.getRequired());
            Schema schema = parameter.getSchema();
            String primaryType = OpenApiStyleParameterDeserializer.primaryType(schema);

            // array/object parameters are style/explode-serialised — decode them into a JSON literal so
            // they can be schema-validated, instead of being skipped. The decoder is fail-open: when the
            // value cannot be soundly reconstructed it returns json == null and the schema check is skipped
            // (preserving the historic "don't false-positive a valid request" behaviour), while still
            // reporting presence so required parameters are enforced.
            if (schema != null && ("array".equals(primaryType) || "object".equals(primaryType))) {
                OpenApiStyleParameterDeserializer.Result result =
                    OpenApiStyleParameterDeserializer.deserialize(parameter, in, name, schema, primaryType, request, pathValues);
                boolean present = result.present || parameterValue(in, name, request, pathValues) != null;
                if (!present) {
                    if (required) {
                        errors.add("required " + in + " parameter '" + name + "' is missing");
                    }
                    continue;
                }
                if (result.json != null) {
                    try {
                        String schemaJson = OBJECT_MAPPER.writeValueAsString(schema);
                        JsonSchemaValidator validator = JsonSchemaValidator.cachedJsonSchemaValidator(logger, schemaJson);
                        String validationResult = validator.isValid(result.json, false);
                        if (isNotBlank(validationResult)) {
                            errors.add(in + " parameter '" + name + "' validation error: " + validationResult);
                        }
                    } catch (Throwable throwable) {
                        errors.add(OpenAPIValidationErrors.unexpectedError("validating " + in + " parameter '" + name + "' against schema", throwable, logger));
                    }
                }
                continue;
            }

            String value = parameterValue(in, name, request, pathValues);
            boolean present = value != null;

            if (!present) {
                if (required) {
                    errors.add("required " + in + " parameter '" + name + "' is missing");
                }
                continue;
            }

            if (schema == null) {
                continue;
            }
            try {
                String coercedValue = coerceParameterValue(schema, value);
                // coercedValue == null means the value cannot be soundly validated against this schema
                // (an untyped/composite schema) — skip the schema check rather than false-positive a
                // valid request. Required-presence has already been enforced above.
                if (coercedValue != null) {
                    String schemaJson = OBJECT_MAPPER.writeValueAsString(schema);
                    JsonSchemaValidator validator = JsonSchemaValidator.cachedJsonSchemaValidator(logger, schemaJson);
                    String validationResult = validator.isValid(coercedValue, false);
                    if (isNotBlank(validationResult)) {
                        errors.add(in + " parameter '" + name + "' validation error: " + validationResult);
                    }
                }
            } catch (Throwable throwable) {
                errors.add(OpenAPIValidationErrors.unexpectedError("validating " + in + " parameter '" + name + "' against schema", throwable, logger));
            }
        }
    }

    private static String parameterValue(String in, String name, HttpRequest request, Map<String, String> pathValues) {
        switch (in) {
            case "query":
                return hasQueryStringParameter(request, name) ? request.getFirstQueryStringParameter(name) : null;
            case "header":
                return hasHeader(request, name) ? request.getFirstHeader(name) : null;
            case "cookie":
                return cookieValue(request, name);
            case "path":
                return pathValues.get(name);
            default:
                return null;
        }
    }

    private static boolean hasQueryStringParameter(HttpRequest request, String name) {
        if (request.getQueryStringParameterList() == null) {
            return false;
        }
        return request.getQueryStringParameterList().stream()
            .anyMatch(p -> p.getName() != null && name.equals(p.getName().getValue()));
    }

    private static boolean hasHeader(HttpRequest request, String name) {
        if (request.getHeaderList() == null) {
            return false;
        }
        return request.getHeaderList().stream()
            .anyMatch(h -> h.getName() != null && name.equalsIgnoreCase(h.getName().getValue()));
    }

    private static String cookieValue(HttpRequest request, String name) {
        if (request.getCookieList() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookieList()) {
            if (cookie.getName() != null && name.equals(cookie.getName().getValue())) {
                return cookie.getValue() != null ? cookie.getValue().getValue() : "";
            }
        }
        return null;
    }

    /**
     * Maps the concrete request path back onto the matched path template, returning the value of each
     * {@code {placeholder}} segment. A template segment that has no corresponding concrete segment
     * (e.g. the request path omitted the parameter entirely) is left absent from the result so the
     * required-presence check reports it as missing.
     */
    private static Map<String, String> extractPathParameterValues(String pathTemplate, HttpRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        if (isBlank(pathTemplate) || !pathTemplate.contains("{")) {
            return values;
        }
        String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
        String[] templateSegments = pathTemplate.split("/", -1);
        String[] requestSegments = requestPath.split("/", -1);
        if (templateSegments.length != requestSegments.length) {
            // path shape does not line up (e.g. a parameter segment omitted) — leave declared
            // placeholders absent so required path params are reported as missing
            return values;
        }
        for (int i = 0; i < templateSegments.length; i++) {
            String templateSegment = templateSegments[i];
            if (templateSegment.startsWith("{") && templateSegment.endsWith("}") && templateSegment.length() > 2) {
                String name = templateSegment.substring(1, templateSegment.length() - 1);
                String value = requestSegments[i];
                if (isNotBlank(value)) {
                    values.put(name, value);
                }
            }
        }
        return values;
    }

    /**
     * Converts a primitive parameter's string value into the JSON literal the schema validator expects
     * for the parameter's type, or {@code null} when the value cannot be soundly validated and the schema
     * check should be skipped. {@code array}/{@code object} parameters are handled ahead of this method by
     * {@link OpenApiStyleParameterDeserializer} (style/explode decoding), so only primitive and
     * untyped/composite schemas reach here.
     * <p>
     * Numeric/boolean schemas validate the raw token when it parses as that type, so a mismatched value
     * (e.g. {@code "abc"} for an integer) is wrapped as a JSON string and correctly fails type
     * validation. Every other type validates as a JSON string.
     */
    @SuppressWarnings("rawtypes")
    private static String coerceParameterValue(Schema schema, String value) throws Exception {
        String type = schema.getType();
        if ("integer".equals(type) || "number".equals(type)) {
            if (value.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
                return value;
            }
        } else if ("boolean".equals(type)) {
            if ("true".equals(value) || "false".equals(value)) {
                return value;
            }
        } else if (type == null) {
            // composite/untyped schema (OAS 3.1 anyOf/allOf/$ref with no top-level type): the raw string
            // value cannot be soundly validated here, so skip the schema check rather than wrapping it as a
            // JSON string and risking a false positive on a valid request (presence is still enforced)
            return null;
        }
        // default: validate as a JSON string literal (also the correct mismatch path for typed schemas)
        return OBJECT_MAPPER.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private static void validateRequestBody(Operation operation, HttpRequest request, MockServerLogger logger, List<String> errors) {
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody == null) {
            return;
        }

        String bodyString = request.getBodyAsString();
        if (isBlank(bodyString)) {
            if (requestBody.getRequired() != null && requestBody.getRequired()) {
                errors.add("request body is required but was empty");
            }
            return;
        }

        if (requestBody.getContent() == null || requestBody.getContent().isEmpty()) {
            return;
        }

        String contentType = request.getFirstHeader("content-type");
        MediaType mediaType = null;
        if (isNotBlank(contentType)) {
            String baseContentType = contentType.contains(";") ? contentType.substring(0, contentType.indexOf(";")).trim() : contentType.trim();
            mediaType = requestBody.getContent().get(baseContentType);
        }
        if (mediaType == null) {
            mediaType = requestBody.getContent().get("application/json");
        }
        if (mediaType == null) {
            mediaType = requestBody.getContent().get("*/*");
        }
        if (mediaType == null && !requestBody.getContent().isEmpty()) {
            mediaType = requestBody.getContent().values().iterator().next();
        }

        if (mediaType == null) {
            return;
        }

        Schema schema = mediaType.getSchema();
        if (schema == null) {
            return;
        }

        try {
            String schemaJson = OBJECT_MAPPER.writeValueAsString(schema);
            JsonSchemaValidator validator = JsonSchemaValidator.cachedJsonSchemaValidator(logger, schemaJson);
            String validationResult = validator.isValid(bodyString, false);
            if (isNotBlank(validationResult)) {
                errors.add("request body validation error: " + validationResult);
            }
        } catch (Throwable throwable) {
            errors.add(OpenAPIValidationErrors.unexpectedError("validating request body against schema", throwable, logger));
        }
    }
}
