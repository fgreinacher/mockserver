package org.mockserver.openapi;

import com.fasterxml.jackson.databind.ObjectWriter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;
import org.mockserver.openapi.examples.ExampleBuilder;
import org.mockserver.openapi.examples.GenerationOptions;
import org.mockserver.openapi.examples.JsonNodeExampleSerializer;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.openapi.examples.models.StringExample;
import org.mockserver.serialization.ObjectMapperFactory;

/**
 * Shared, side-effect-free helper for deriving concrete example parameter values from an OpenAPI
 * {@link Operation} and applying them to an example {@link HttpRequest}.
 * <p>
 * The value-resolution order mirrors the OpenAPI specification's example precedence:
 * <ol>
 *     <li>the parameter's explicit {@code example};</li>
 *     <li>the first entry in the parameter's {@code examples} map;</li>
 *     <li>the schema's {@code default};</li>
 *     <li>the first {@code enum} value;</li>
 *     <li>a value generated from the schema via {@link ExampleBuilder#fromSchema};</li>
 *     <li>for required parameters only, the literal {@code "example"} so the request is well-formed.</li>
 * </ol>
 * Used by {@link OpenApiContractTest}, {@link OpenApiResiliencyTest} (indirectly via the contract
 * test), and {@link OpenAPIConverter} so the logic lives in exactly one place.
 */
public final class OpenApiParameterExamples {

    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory
        .createObjectMapper(new JsonNodeExampleSerializer())
        .writerWithDefaultPrettyPrinter();

    private OpenApiParameterExamples() {
    }

    /**
     * Resolves an example string value for a single parameter, or {@code null} when no value can be
     * derived and the parameter is not required.
     */
    public static String getParameterExampleValue(Parameter param, OpenAPI openAPI) {
        return getParameterExampleValue(param, openAPI, null);
    }

    public static String getParameterExampleValue(Parameter param, OpenAPI openAPI, GenerationOptions generationOptions) {
        return getParameterExampleValue(param, openAPI, generationOptions, null);
    }

    /**
     * As {@link #getParameterExampleValue(Parameter, OpenAPI, GenerationOptions)} but resolving the
     * {@code generateRealisticExampleValues} default from a caller-supplied {@link Configuration}
     * instance. A {@code null} {@code configuration} keeps the static-store behaviour.
     */
    @SuppressWarnings("rawtypes")
    public static String getParameterExampleValue(Parameter param, OpenAPI openAPI, GenerationOptions generationOptions, Configuration configuration) {
        if (param == null) {
            return null;
        }
        // 1. explicit example on the parameter
        if (param.getExample() != null) {
            return String.valueOf(param.getExample());
        }
        // 2. examples map
        if (param.getExamples() != null && !param.getExamples().isEmpty()) {
            io.swagger.v3.oas.models.examples.Example example = param.getExamples().values().iterator().next();
            if (example != null && example.getValue() != null) {
                return String.valueOf(example.getValue());
            }
        }
        // 3. generate from schema
        if (param.getSchema() != null) {
            Schema schema = param.getSchema();
            // schema default
            if (schema.getDefault() != null) {
                return String.valueOf(schema.getDefault());
            }
            // schema enum
            if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
                return String.valueOf(schema.getEnum().get(0));
            }
            // generate from type
            Example generatedExample = ExampleBuilder.fromSchema(
                schema,
                openAPI != null && openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null,
                generationOptions,
                ExampleBuilder.Direction.REQUEST,
                configuration
            );
            if (generatedExample instanceof StringExample) {
                return ((StringExample) generatedExample).getValue();
            } else if (generatedExample != null) {
                return serialise(generatedExample);
            }
        }
        // 4. only emit a value for required parameters
        if (Boolean.TRUE.equals(param.getRequired())) {
            return "example";
        }
        return null;
    }

    /**
     * Substitutes example values for any {@code path} parameters declared on the operation into the
     * path template. Any path placeholder left unresolved (declared implicitly or with no derivable
     * value) is replaced with the literal {@code "example"} so the resulting path is well-formed.
     */
    public static String resolvePath(String pathTemplate, Operation operation, OpenAPI openAPI) {
        return resolvePath(pathTemplate, operation, openAPI, null);
    }

    public static String resolvePath(String pathTemplate, Operation operation, OpenAPI openAPI, GenerationOptions generationOptions) {
        return resolvePath(pathTemplate, operation, openAPI, generationOptions, null);
    }

    public static String resolvePath(String pathTemplate, Operation operation, OpenAPI openAPI, GenerationOptions generationOptions, Configuration configuration) {
        String resolved = pathTemplate;
        if (operation != null && operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                if ("path".equals(param.getIn())) {
                    String exampleValue = getParameterExampleValue(param, openAPI, generationOptions, configuration);
                    if (exampleValue == null) {
                        exampleValue = "example";
                    }
                    resolved = resolved.replace("{" + param.getName() + "}", exampleValue);
                }
            }
        }
        // any remaining unresolved path placeholders
        resolved = resolved.replaceAll("\\{[^}]+}", "example");
        return resolved;
    }

    /**
     * Applies example {@code query}, {@code header}, and {@code cookie} parameter values declared on
     * the operation to the given request. Path parameters are resolved separately via
     * {@link #resolvePath}. Mutates and returns {@code request} for fluent chaining.
     */
    public static HttpRequest applyExampleParameters(HttpRequest request, Operation operation, OpenAPI openAPI, GenerationOptions generationOptions) {
        return applyExampleParameters(request, operation, openAPI, generationOptions, null);
    }

    public static HttpRequest applyExampleParameters(HttpRequest request, Operation operation, OpenAPI openAPI, GenerationOptions generationOptions, Configuration configuration) {
        if (request == null || operation == null || operation.getParameters() == null) {
            return request;
        }
        for (Parameter param : operation.getParameters()) {
            String in = param.getIn();
            if (in == null) {
                continue;
            }
            switch (in) {
                case "query": {
                    String exampleValue = getParameterExampleValue(param, openAPI, generationOptions, configuration);
                    if (exampleValue != null) {
                        request.withQueryStringParameter(param.getName(), exampleValue);
                    }
                    break;
                }
                case "header": {
                    String exampleValue = getParameterExampleValue(param, openAPI, generationOptions, configuration);
                    if (exampleValue != null) {
                        request.withHeader(param.getName(), exampleValue);
                    }
                    break;
                }
                case "cookie": {
                    String exampleValue = getParameterExampleValue(param, openAPI, generationOptions, configuration);
                    if (exampleValue != null) {
                        request.withCookie(param.getName(), exampleValue);
                    }
                    break;
                }
                default:
                    // "path" handled by resolvePath; ignore any unknown location
                    break;
            }
        }
        return request;
    }

    @SuppressWarnings("rawtypes")
    private static String serialise(Object example) {
        try {
            return OBJECT_WRITER.writeValueAsString(example);
        } catch (Throwable throwable) {
            return String.valueOf(example);
        }
    }
}
