package org.mockserver.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Drift guard for the two copies of the MockServer OpenAPI specification.
 *
 * <p>The control-plane REST API is described by one specification that lives in the repository
 * twice, because two different consumers need it in two different places:
 *
 * <ul>
 *   <li><strong>{@code jekyll-www.mock-server.com/mockserver-openapi.yaml}</strong> — published on
 *       the documentation site, and named the single source of truth by
 *       {@code scripts/collections/generate_collections.py}, which derives the Postman and Bruno
 *       collections from it (gated in CI by {@code .buildkite/scripts/steps/collections-validate.sh}).</li>
 *   <li><strong>{@code mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml}</strong>
 *       — bundled into the jar and served at runtime from {@code GET /mockserver/openapi.yaml} by
 *       {@code OpenAPISpecHandler}, and uploaded to SwaggerHub by
 *       {@code scripts/release/components/swaggerhub.sh}.</li>
 * </ul>
 *
 * <p>The jekyll copy sits outside {@code mockserver/pom.xml}'s module set, so no Maven module
 * compiles, copies, or compares it, and the embedded copy is a plain classpath resource that
 * nothing regenerates. Both files declare the same {@code info.version} — {@code prepare.sh} bumps
 * both on every release — so a version comparison cannot detect divergence either. The result is
 * that the two copies drifted apart silently and in <em>both</em> directions: the jekyll copy grew
 * control-plane paths the embedded copy never received, while the embedded copy grew model
 * properties the jekyll copy never received. A running MockServer then hands out a specification
 * that omits most of its own API.
 *
 * <p>This test is the missing validation. It asserts the two files are byte-identical, which is the
 * strongest and simplest form of the invariant: there is one specification, stored twice. Byte
 * equality survives the release process unchanged, because {@code prepare.sh} rewrites the same
 * {@code info.version} into both files in the same commit.
 *
 * <p>On failure the assertion message reports the structural difference (paths and schemas present
 * in one copy but not the other) before falling back to a byte diff, so the fix is obvious rather
 * than requiring a manual diff of two 5,000-line YAML files.
 *
 * <p>Follows the same shape as {@code LegacyBackstopFileSyncTest}: plain JUnit, Docker-free, and it
 * locates the repository root by walking up from the module basedir so it is independent of the
 * working directory Surefire launches with.
 *
 * <p><strong>What this test does not guard.</strong> It compares one copy of the specification to
 * the other. It says nothing about whether either copy matches the Java model, so a property that
 * exists in {@code ExpectationDTO} but in neither copy is invisible here. That gap is real today:
 * {@code Expectation} accepts at least {@code httpLlmResponse}, {@code grpcStreamResponse},
 * {@code grpcBidiResponse}, {@code binaryResponse}, {@code dnsResponse},
 * {@code httpForwardValidateAction}, {@code httpForwardWithFallback}, {@code beforeActions},
 * {@code afterActions}, {@code steps} and {@code capture}, none of which the specification declares —
 * and since the schema is {@code additionalProperties: false}, it rejects valid LLM, gRPC, binary and
 * DNS expectations. Closing that needs a separate spec-versus-DTO test; the known direction of error
 * is omission only (no property is declared in the schema that does not exist in Java).
 */
public class OpenApiSpecSyncTest {

    /**
     * The copy bundled into the jar and served at runtime. Also the marker used to locate the
     * repository root, and the file {@code swaggerhub.sh} uploads.
     */
    private static final String EMBEDDED_SPEC =
        "mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml";

    /**
     * The copy published on the documentation site, from which the Postman and Bruno collections
     * are generated.
     */
    private static final String WEBSITE_SPEC =
        "jekyll-www.mock-server.com/mockserver-openapi.yaml";

    @Test
    public void embeddedOpenApiSpecIsByteIdenticalToThePublishedWebsiteSpec() throws Exception {
        Path repoRoot = locateRepositoryRoot().toPath();
        Path embedded = repoRoot.resolve(EMBEDDED_SPEC);
        Path website = repoRoot.resolve(WEBSITE_SPEC);

        assertThat("embedded OpenAPI spec is missing - expected at " + embedded,
            Files.exists(embedded), is(true));
        assertThat("website OpenAPI spec is missing - expected at " + website,
            Files.exists(website), is(true));

        byte[] embeddedBytes = Files.readAllBytes(embedded);
        byte[] websiteBytes = Files.readAllBytes(website);

        assertThat(
            driftDescription(embeddedBytes, websiteBytes),
            Arrays.equals(embeddedBytes, websiteBytes), is(true));
    }

    /**
     * Builds the failure message. Structural differences are reported first because they are what a
     * reader needs: a missing path means the running server does not describe an endpoint it
     * implements, and a missing schema means it does not describe a field it accepts.
     */
    private static String driftDescription(byte[] embeddedBytes, byte[] websiteBytes) throws Exception {
        StringBuilder message = new StringBuilder()
            .append("the two copies of the MockServer OpenAPI spec have drifted apart\n")
            .append("  embedded (served at GET /mockserver/openapi.yaml, uploaded to SwaggerHub): ")
            .append(EMBEDDED_SPEC).append('\n')
            .append("  website  (source for the Postman/Bruno collections):                      ")
            .append(WEBSITE_SPEC).append('\n');

        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        JsonNode embeddedSpec = yaml.readTree(embeddedBytes);
        JsonNode websiteSpec = yaml.readTree(websiteBytes);

        appendDifference(message, "paths", fieldNames(embeddedSpec.path("paths")), fieldNames(websiteSpec.path("paths")));
        appendDifference(message, "schemas",
            fieldNames(embeddedSpec.path("components").path("schemas")),
            fieldNames(websiteSpec.path("components").path("schemas")));

        return message
            .append("\nboth files must describe exactly one API. Reconcile them and copy the")
            .append(" reconciled spec to both paths, then regenerate the collections with")
            .append(" `python3 scripts/collections/generate_collections.py`.")
            .toString();
    }

    private static void appendDifference(StringBuilder message, String label, Set<String> embedded, Set<String> website) {
        Set<String> embeddedOnly = new TreeSet<>(embedded);
        embeddedOnly.removeAll(website);
        Set<String> websiteOnly = new TreeSet<>(website);
        websiteOnly.removeAll(embedded);

        if (embeddedOnly.isEmpty() && websiteOnly.isEmpty()) {
            message.append("  ").append(label).append(": same set (").append(embedded.size())
                .append(") - the difference is in their detail, not their names\n");
            return;
        }
        if (!websiteOnly.isEmpty()) {
            message.append("  ").append(label).append(" missing from the EMBEDDED spec (")
                .append(websiteOnly.size()).append(" of ").append(website.size())
                .append(" - the running server does not describe these): ").append(websiteOnly).append('\n');
        }
        if (!embeddedOnly.isEmpty()) {
            message.append("  ").append(label).append(" missing from the WEBSITE spec (")
                .append(embeddedOnly.size()).append(" of ").append(embedded.size())
                .append(" - the published collections do not cover these): ").append(embeddedOnly).append('\n');
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        for (Iterator<String> iterator = node.fieldNames(); iterator.hasNext(); ) {
            names.add(iterator.next());
        }
        return names;
    }

    /**
     * Walks up from the module basedir ({@code user.dir} when Surefire runs this module) until it
     * finds the directory containing the embedded spec, which identifies the repository root.
     */
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
