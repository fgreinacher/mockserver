package org.mockserver.load;

import com.fasterxml.jackson.databind.ObjectWriter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.openapi.OpenAPISerialiser;
import org.mockserver.openapi.examples.JsonNodeExampleSerializer;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.openapi.examples.models.StringExample;
import org.mockserver.serialization.ObjectMapperFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.openapi.OpenAPIConverter.isJsonContentType;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;

/**
 * Seeds an editable, runnable {@link LoadScenario} from an OpenAPI specification.
 *
 * <p>One {@link LoadStep} is emitted per operation (in the stable, path-then-method order produced
 * by {@link OpenAPISerialiser#retrieveOperations(OpenAPI, String, String)}), each carrying a concrete
 * {@link HttpRequest} with the operation's method and (server-prefixed) path, a representative request
 * body example (built from the request-body schema via {@link org.mockserver.openapi.examples.ExampleBuilder},
 * the same engine the OpenAPI expectation converter uses) and a {@code Content-Type} header.
 *
 * <p><b>Target precedence</b> for where each generated request is sent (carried as the request's
 * {@code Host} header and {@code secure} flag, the same routing surface every other load step uses):
 * <ol>
 *   <li>an explicit {@link Target} supplied by the caller, else</li>
 *   <li>the spec's {@code servers[0]} URL (host, port and {@code https} scheme), else</li>
 *   <li>nothing — the request is left path-only and the operator edits in a target before running.</li>
 * </ol>
 *
 * <p>When the caller supplies no {@link LoadProfile} a conservative default is applied — a single
 * short constant-VU stage — so the generated scenario is immediately runnable yet safe; the operator
 * is expected to edit the profile afterward. Steps are plain and ordered (no weighting in v1).
 *
 * <p>This is a pure generator: it produces a {@link LoadScenario} object and drives no traffic.
 */
public class LoadScenarioFromOpenAPI {

    /** Default virtual users for the generated profile when the caller supplies none — small and safe. */
    static final int DEFAULT_VUS = 5;
    /** Default stage duration (30s) for the generated profile when the caller supplies none. */
    static final long DEFAULT_DURATION_MILLIS = 30_000L;

    private static final ObjectWriter OBJECT_WRITER =
        ObjectMapperFactory.createObjectMapper(new JsonNodeExampleSerializer()).writerWithDefaultPrettyPrinter();

    /**
     * An explicit network target for the generated steps. Any field may be null/0; null host means
     * "fall back to the spec's servers[0]", and a 0/absent port means the scheme default.
     */
    public static class Target {
        private final String host;
        private final Integer port;
        private final String scheme;

        public Target(String host, Integer port, String scheme) {
            this.host = host;
            this.port = port;
            this.scheme = scheme;
        }

        public String getHost() {
            return host;
        }

        public Integer getPort() {
            return port;
        }

        public String getScheme() {
            return scheme;
        }

        public boolean hasHost() {
            return isNotBlank(host);
        }
    }

    /**
     * Generates a {@link LoadScenario} from an OpenAPI spec.
     *
     * @param name            the generated scenario name
     * @param specUrlOrPayload the OpenAPI spec as an inline JSON/YAML payload, URL or file/classpath reference
     *                         (accepted identically to {@code PUT /mockserver/openapi/expectation})
     * @param target          explicit network target (may be null — see class-level precedence)
     * @param profile         explicit load profile (may be null — a conservative default is applied)
     * @param mockServerLogger logger used by the shared OpenAPI parse
     * @return the generated, editable {@link LoadScenario}
     * @throws IllegalArgumentException if the spec cannot be parsed or contains no operations
     */
    public static LoadScenario generate(String name, String specUrlOrPayload, Target target, LoadProfile profile, MockServerLogger mockServerLogger) {
        // Reuse the shared OpenAPI parse (the same buildOpenAPI the expectation converter uses) — do not
        // parse OpenAPI here. retrieveOperations resolves per-operation server-path prefixes and yields a
        // stable path-then-method ordering.
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        Map<String, List<Pair<String, Operation>>> operations = serialiser.retrieveOperations(openAPI, null);

        Target effectiveTarget = resolveTarget(target, openAPI);

        LoadScenario scenario = LoadScenario.loadScenario(name);
        Map<String, Schema> componentSchemas =
            openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null;

        operations.forEach((path, methodOperations) ->
            methodOperations.forEach(methodOperationPair -> {
                String method = methodOperationPair.getKey();
                Operation operation = methodOperationPair.getValue();
                HttpRequest httpRequest = request()
                    .withMethod(method)
                    .withPath(path);
                applyTarget(httpRequest, effectiveTarget);
                applyRequestBody(httpRequest, operation, componentSchemas);
                LoadStep step = LoadStep.loadStep(httpRequest);
                if (isNotBlank(operation.getOperationId())) {
                    step.withName(operation.getOperationId());
                }
                scenario.withSteps(step);
            })
        );

        if (scenario.getSteps().isEmpty()) {
            throw new IllegalArgumentException("OpenAPI specification contains no operations to generate load steps from");
        }

        scenario.withProfile(profile != null ? profile : defaultProfile());
        return scenario;
    }

    /**
     * Resolves the effective target by the documented precedence: an explicit target with a host wins;
     * otherwise the spec's {@code servers[0]} URL is used; otherwise null (path-only requests).
     */
    private static Target resolveTarget(Target explicit, OpenAPI openAPI) {
        if (explicit != null && explicit.hasHost()) {
            return explicit;
        }
        return fromFirstServer(openAPI);
    }

    private static Target fromFirstServer(OpenAPI openAPI) {
        if (openAPI.getServers() == null || openAPI.getServers().isEmpty()) {
            return null;
        }
        Server server = openAPI.getServers().get(0);
        if (server == null || isBlank(server.getUrl())) {
            return null;
        }
        try {
            URI uri = new URI(server.getUrl());
            if (isBlank(uri.getHost())) {
                // a relative server URL (e.g. "/v1") has no host — nothing to target with
                return null;
            }
            int port = uri.getPort();
            return new Target(uri.getHost(), port > 0 ? port : null, uri.getScheme());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Applies the target to the request as a {@code Host} header (host[:port]) and {@code secure} flag —
     * the routing surface the load orchestrator reads. A null target leaves the request path-only.
     */
    private static void applyTarget(HttpRequest httpRequest, Target target) {
        if (target == null || !target.hasHost()) {
            return;
        }
        boolean secure = "https".equalsIgnoreCase(target.getScheme());
        httpRequest.withSecure(secure);
        String hostHeader = target.getHost();
        if (target.getPort() != null && target.getPort() > 0) {
            hostHeader = hostHeader + ":" + target.getPort();
        }
        httpRequest.withHeader("Host", hostHeader);
    }

    /**
     * Builds a representative request body example from the first request-body content type using the
     * shared {@link org.mockserver.openapi.examples.ExampleBuilder}, and sets the matching
     * {@code Content-Type} header. Operations without a request body are left body-free.
     */
    private static void applyRequestBody(HttpRequest httpRequest, Operation operation, Map<String, Schema> componentSchemas) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return;
        }
        operation.getRequestBody().getContent().entrySet().stream().findFirst().ifPresent(contentEntry -> {
            String contentType = contentEntry.getKey();
            httpRequest.withHeader("Content-Type", contentType);
            MediaType mediaType = contentEntry.getValue();
            if (mediaType == null || mediaType.getSchema() == null) {
                return;
            }
            Example example = org.mockserver.openapi.examples.ExampleBuilder.fromSchema(mediaType.getSchema(), componentSchemas, null, org.mockserver.openapi.examples.ExampleBuilder.Direction.REQUEST);
            if (example instanceof StringExample) {
                httpRequest.withBody(((StringExample) example).getValue());
            } else if (example != null) {
                String body = serialise(example);
                if (isNotBlank(body)) {
                    if (isJsonContentType(contentType)) {
                        httpRequest.withBody(org.mockserver.model.JsonBody.json(body));
                    } else {
                        httpRequest.withBody(body);
                    }
                }
            }
        });
    }

    /** A conservative default profile: one short constant-VU stage — runnable but safe; edit before scaling up. */
    static LoadProfile defaultProfile() {
        return LoadProfile.constant(DEFAULT_VUS, DEFAULT_DURATION_MILLIS);
    }

    private static String serialise(Example example) {
        try {
            return OBJECT_WRITER.writeValueAsString(example);
        } catch (Exception e) {
            return "";
        }
    }
}
