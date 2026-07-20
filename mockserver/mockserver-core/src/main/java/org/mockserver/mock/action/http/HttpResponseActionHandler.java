package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.file.FileReader;
import org.mockserver.graphql.GraphQLResponseSynthesizer;
import org.mockserver.graphql.GraphQLSchemaException;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Body;
import org.mockserver.model.BodyWithContentType;
import org.mockserver.model.FileBody;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.RequestDefinition;
import org.mockserver.model.StringBody;
import org.mockserver.openapi.JsonSchemaResponseSynthesisException;
import org.mockserver.openapi.JsonSchemaResponseSynthesizer;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;
import org.mockserver.model.WasmBody;
import org.mockserver.wasm.WasmResponseShaper;
import org.mockserver.wasm.WasmStore;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.slf4j.event.Level.WARN;

/**
 * @author jamesdbloom
 */
public class HttpResponseActionHandler {

    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private VelocityTemplateEngine velocityTemplateEngine;
    private MustacheTemplateEngine mustacheTemplateEngine;

    public HttpResponseActionHandler(MockServerLogger mockServerLogger, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
    }

    public HttpResponse handle(HttpResponse httpResponse) {
        return handle(httpResponse, null, null);
    }

    public HttpResponse handle(HttpResponse httpResponse, HttpRequest httpRequest) {
        return handle(httpResponse, httpRequest, null);
    }

    /**
     * Materialises the response for a matched expectation against the incoming request.
     *
     * @param httpResponse       the response action from the matched expectation
     * @param httpRequest        the incoming request (drives templated bodies and GraphQL synthesis)
     * @param matchedRequest     the matched expectation's {@link RequestDefinition}; when it carries a
     *                           {@link GraphQLBody} with a registered schema and the response has no body,
     *                           a schema-valid GraphQL response is synthesized from the request's query
     */
    public HttpResponse handle(HttpResponse httpResponse, HttpRequest httpRequest, RequestDefinition matchedRequest) {
        HttpResponse response = httpResponse.clone();
        if (response == null) {
            return null;
        }
        BodyWithContentType body = response.getBody();
        if (httpRequest != null && body instanceof FileBody && ((FileBody) body).getTemplateType() != null) {
            response.withBody(renderTemplatedFileBody((FileBody) body, httpRequest));
        } else if (body == null) {
            // an explicit body always wins; schema/GraphQL synthesis only fills an unset body.
            // schema-valid generation from an inline JSON schema does not depend on the request, so it
            // runs even when no request is supplied (GraphQL synthesis still requires the request query).
            if (isNotBlank(response.getGenerateFromSchema())) {
                synthesizeSchemaResponse(response);
            } else if (httpRequest != null) {
                String graphQLSchema = graphQLSchemaOf(matchedRequest);
                if (graphQLSchema != null) {
                    synthesizeGraphQLResponse(response, httpRequest, graphQLSchema);
                }
            }
        }
        shapeResponseWithWasm(response, httpRequest, matchedRequest);
        return response;
    }

    /**
     * When the matched expectation matched on a {@link WasmBody} and that module exports
     * {@code shape_response} (ABI v3), hand the fully-materialised response to the module so it can
     * rewrite it (WASM-computed dynamic responses). Runs last, so the module sees the final response
     * (after any templating/schema/GraphQL synthesis). Gated on {@link Configuration#wasmEnabled()}
     * (falling back to the static store only when no {@link Configuration} is available) and fail-safe:
     * any module failure leaves the response untouched (see {@link WasmResponseShaper}).
     */
    private void shapeResponseWithWasm(HttpResponse response, HttpRequest httpRequest, RequestDefinition matchedRequest) {
        if (response == null || httpRequest == null || !(matchedRequest instanceof HttpRequest)) {
            return;
        }
        boolean wasmEnabled = configuration != null
            ? configuration.wasmEnabled()
            : ConfigurationProperties.wasmEnabled();
        Body<?> matchedBody = ((HttpRequest) matchedRequest).getBody();
        if (!(matchedBody instanceof WasmBody) || !wasmEnabled) {
            return;
        }
        String moduleName = ((WasmBody) matchedBody).getModuleName();
        byte[] wasmBytes = WasmStore.getInstance().get(moduleName);
        if (wasmBytes == null) {
            return;
        }
        new WasmResponseShaper(mockServerLogger, configuration).shape(response, httpRequest, moduleName, wasmBytes);
    }

    /**
     * Generates a schema-valid JSON body from the response's inline JSON Schema and sets it as a JSON
     * body. Reuses the OpenAPI example-generation engine (see {@link JsonSchemaResponseSynthesizer}).
     * Generation failures (an unparseable schema, or a schema that yields no example) are logged and
     * leave the response body unset rather than failing the request.
     */
    private void synthesizeSchemaResponse(HttpResponse response) {
        try {
            String generated = new JsonSchemaResponseSynthesizer(mockServerLogger).synthesizeResponse(response.getGenerateFromSchema());
            if (isNotBlank(generated)) {
                response.withBody(new StringBody(generated, MediaType.APPLICATION_JSON));
            }
        } catch (JsonSchemaResponseSynthesisException schemaException) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(WARN)
                    .setMessageFormat("unable to generate a schema-valid response from the inline JSON schema, leaving response body unset because:{}")
                    .setArguments(schemaException.getCause() != null ? schemaException.getCause().getMessage() : schemaException.getMessage())
                    .setThrowable(schemaException)
            );
        }
    }

    private static String graphQLSchemaOf(RequestDefinition matchedRequest) {
        if (matchedRequest instanceof HttpRequest) {
            Body<?> requestBody = ((HttpRequest) matchedRequest).getBody();
            if (requestBody instanceof GraphQLBody) {
                String schema = ((GraphQLBody) requestBody).getSchema();
                return isNotBlank(schema) ? schema : null;
            }
        }
        return null;
    }

    /**
     * Synthesizes a schema-valid {@code {"data": {...}}} GraphQL response from the registered schema and
     * the incoming request's query, and sets it as a JSON body on the response. Synthesis failures (an
     * unparseable schema, or a request whose body is not a GraphQL query) are logged and leave the response
     * body unset rather than failing the request.
     */
    private void synthesizeGraphQLResponse(HttpResponse response, HttpRequest httpRequest, String graphQLSchema) {
        String requestBody = httpRequest.getBodyAsString();
        if (!isNotBlank(requestBody)) {
            return;
        }
        try {
            String synthesized = new GraphQLResponseSynthesizer(graphQLSchema).synthesizeResponse(requestBody);
            response.withBody(new StringBody(synthesized, MediaType.APPLICATION_JSON));
        } catch (GraphQLSchemaException gqlException) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(WARN)
                    .setHttpRequest(httpRequest)
                    .setMessageFormat("unable to synthesize GraphQL response from registered schema, leaving response body unset because:{}")
                    .setArguments(gqlException.getMessage())
                    .setThrowable(gqlException)
            );
        }
    }

    /**
     * Reads the file referenced by a {@link FileBody} and renders its contents through the configured
     * template engine against the request, so an externally stored response body can contain template
     * placeholders. The content type declared on the FileBody (when any) is preserved on the result.
     */
    private BodyWithContentType renderTemplatedFileBody(FileBody fileBody, HttpRequest httpRequest) {
        TemplateEngine templateEngine;
        switch (fileBody.getTemplateType()) {
            case VELOCITY:
                templateEngine = getVelocityTemplateEngine();
                break;
            case MUSTACHE:
                templateEngine = getMustacheTemplateEngine();
                break;
            default:
                // JavaScript is not supported for file body templating (see TemplateEngine.renderTemplate);
                // fall back to the raw file contents rather than failing the response.
                return fileBody;
        }
        String fileTemplate = FileReader.readFileFromClassPathOrPath(fileBody.getFilePath());
        String rendered = templateEngine.renderTemplate(fileTemplate, httpRequest);
        String contentType = fileBody.getContentType();
        return isNotBlank(contentType)
            ? new StringBody(rendered, MediaType.parse(contentType))
            : new StringBody(rendered);
    }

    private VelocityTemplateEngine getVelocityTemplateEngine() {
        if (velocityTemplateEngine == null) {
            velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        }
        return velocityTemplateEngine;
    }

    private MustacheTemplateEngine getMustacheTemplateEngine() {
        if (mustacheTemplateEngine == null) {
            mustacheTemplateEngine = new MustacheTemplateEngine(mockServerLogger, configuration);
        }
        return mustacheTemplateEngine;
    }
}
