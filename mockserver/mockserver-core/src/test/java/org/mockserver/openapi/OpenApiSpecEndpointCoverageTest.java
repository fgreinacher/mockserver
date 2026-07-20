package org.mockserver.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

/**
 * Drift guard asserting that every control-plane route the server dispatches is described by the
 * OpenAPI specification.
 *
 * <p><strong>Why.</strong> Sixteen implemented endpoints were absent from the specification, and
 * because {@code scripts/collections/generate_collections.py} treats the specification as the single
 * source of truth, they were absent from the published Postman and Bruno collections too, and from
 * any client generated from the spec. Nothing detected this, because nothing compared the routes to
 * the document. {@link OpenApiSpecSyncTest} compares one copy of the spec to the other and
 * {@link OpenApiSpecExpectationSchemaTest} compares the {@code Expectation} schema to the DTO;
 * neither can see an endpoint that exists in Java and nowhere in the spec.
 *
 * <p><strong>How, and the honest limits of it.</strong> MockServer dispatches the control plane
 * through a long {@code if / else if} chain rather than a route registry, so there is no structured
 * runtime object to enumerate. This test therefore reads the two dispatch sources as text and
 * extracts the route literals from the canonical call shape
 * {@code request.matches("METHOD", PATH_PREFIX + "/path", "/path")}. Source-text extraction is a
 * weaker instrument than reflection, and the mitigation for that is
 * {@link #theRouteExtractionStillFindsTheDispatchTable()}: it asserts a floor on the number of routes
 * found, so a refactor that changes the call shape fails loudly instead of silently extracting zero
 * routes and passing vacuously. A guard that can quietly stop guarding is the exact failure this
 * whole class of test exists to prevent.
 *
 * <p><strong>What it does not cover.</strong> Three routes reach the client by a different dispatch
 * mechanism and are deliberately out of scope rather than approximated:
 * <ul>
 *   <li>{@code GET /mockserver/metrics} is matched with {@code String.matches} (a regex) rather than
 *       the shared helper — the only route in the codebase that is;</li>
 *   <li>{@code /mockserver/mcp} is routed by {@code McpRequestProcessor.isMcpPath}, which accepts the
 *       exact path, a query string, and any sub-path;</li>
 *   <li>the four templated routes ({@code /scenario/{name}}, {@code /loadScenario/{name}},
 *       {@code /chaosExperiment/profiles/{name}}, {@code /chaosExperiment/apply/{name}}) build their
 *       path from a prefix plus a caller-supplied segment.</li>
 * </ul>
 * All are documented in the specification; they are simply not machine-checked here. Extending the
 * check to them would mean hand-maintaining a second list of routes, which reintroduces exactly the
 * hand-maintenance this guard removes. The durable fix is a route registry the dispatcher and this
 * test can both read — see the note in the changelog.
 */
public class OpenApiSpecEndpointCoverageTest {

    private static final String WEBSITE_SPEC =
        "jekyll-www.mock-server.com/mockserver-openapi.yaml";

    private static final String EMBEDDED_SPEC =
        "mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml";

    /**
     * The two files that dispatch the control plane.
     */
    private static final String[] DISPATCH_SOURCES = {
        "mockserver/mockserver-core/src/main/java/org/mockserver/mock/HttpState.java",
        "mockserver/mockserver-netty/src/main/java/org/mockserver/netty/HttpRequestHandler.java",
    };

    /**
     * The canonical dispatch shape: {@code request.matches("PUT", PATH_PREFIX + "/expectation", ...)}.
     * Only the prefixed literal is captured; the bare alias in the same call is the same route.
     */
    private static final Pattern ROUTE = Pattern.compile(
        "request\\.matches\\(\\s*\"([A-Z]+)\"\\s*,\\s*PATH_PREFIX\\s*\\+\\s*\"([^\"]+)\"");

    /**
     * Floor for the number of routes the extraction must find. Set well below the count at the time
     * of writing (95), so ordinary additions do not trip it but a refactor that breaks the pattern —
     * and would otherwise make this whole test vacuously green — does.
     */
    private static final int MINIMUM_EXPECTED_ROUTES = 85;

    @Test
    public void theRouteExtractionStillFindsTheDispatchTable() throws Exception {
        Set<String> routes = dispatchedRoutes();
        assertThat(
            "the route extraction found " + routes.size() + " routes, fewer than the " + MINIMUM_EXPECTED_ROUTES
                + " it must find. Either the control-plane dispatch was refactored away from the"
                + " `request.matches(\"METHOD\", PATH_PREFIX + \"/path\", \"/path\")` shape this test reads,"
                + " or it moved out of " + String.join(" and ", DISPATCH_SOURCES) + "."
                + " Update this test to match - do NOT lower the floor, because a low floor turns"
                + " everyEndpointTheServerDispatchesIsDescribedByTheOpenApiSpec into a test that passes"
                + " without checking anything.",
            routes.size(), greaterThanOrEqualTo(MINIMUM_EXPECTED_ROUTES));
    }

    @Test
    public void everyEndpointTheServerDispatchesIsDescribedByTheOpenApiSpec() throws Exception {
        JsonNode paths = specPaths();

        Set<String> undocumented = new TreeSet<>();
        for (String route : dispatchedRoutes()) {
            String method = route.substring(0, route.indexOf(' '));
            String path = route.substring(route.indexOf(' ') + 1);
            JsonNode operations = paths.path(path);
            if (!operations.isObject() || !operations.has(method.toLowerCase())) {
                undocumented.add(route);
            }
        }

        assertThat(
            "the server dispatches control-plane routes the OpenAPI spec does not describe, so they are"
                + " missing from the published spec, from the SwaggerHub upload, and from the generated"
                + " Postman and Bruno collections (which are derived from the spec):\n  " + undocumented
                + "\n\nDocument each under `paths` in " + WEBSITE_SPEC + ", copy the file over "
                + EMBEDDED_SPEC + " so OpenApiSpecSyncTest still passes, then regenerate the collections"
                + " with `python3 scripts/collections/generate_collections.py`."
                + "\n\nVerify each signature against the handler rather than transcribing it - a"
                + " signature documented WRONGLY is worse than one omitted, and this repo has shipped"
                + " exactly that before (HttpChaosProfile.connectionDrop was documented, existed nowhere"
                + " in Java, and propagated into the Go client).",
            undocumented, is(java.util.Collections.<String>emptySet()));
    }

    /**
     * Every {@code "METHOD /mockserver/path"} the dispatch sources route, as text.
     */
    private static Set<String> dispatchedRoutes() throws Exception {
        Path repoRoot = locateRepositoryRoot().toPath();
        Set<String> routes = new LinkedHashSet<>();
        for (String source : DISPATCH_SOURCES) {
            Path file = repoRoot.resolve(source);
            assertThat("control-plane dispatch source is missing - expected at " + file,
                Files.exists(file), is(true));
            Matcher matcher = ROUTE.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            while (matcher.find()) {
                routes.add(matcher.group(1) + " /mockserver" + matcher.group(2));
            }
        }
        return routes;
    }

    private static JsonNode specPaths() throws Exception {
        Path spec = locateRepositoryRoot().toPath().resolve(WEBSITE_SPEC);
        assertThat("OpenAPI spec is missing - expected at " + spec, Files.exists(spec), is(true));
        JsonNode paths = new ObjectMapper(new YAMLFactory())
            .readTree(Files.readAllBytes(spec)).path("paths");
        assertThat("`paths` is missing from " + spec + " - the spec no longer describes any endpoint",
            paths.isObject() && paths.size() > 0, is(true));
        return paths;
    }

    private static File locateRepositoryRoot() {
        File current = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (current != null) {
            if (new File(current, EMBEDDED_SPEC).isFile()) {
                return current;
            }
            current = current.getParentFile();
        }
        throw new IllegalStateException(
            "could not locate the repository root by walking up from user.dir="
                + System.getProperty("user.dir") + " looking for marker '" + EMBEDDED_SPEC + "'");
    }
}
