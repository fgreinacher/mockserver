package org.mockserver.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;
import org.mockserver.model.Provider;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Pins the LLM provider list in the two <em>consumer-facing</em> published contracts to
 * {@link Provider} — the authority. A provider MockServer implements is only actually usable by an
 * outside developer if the contract they generate their tooling from also names it.
 *
 * <p><strong>The gap this closes.</strong> {@link ProviderSchemaEnumParityTest} already pins the
 * <em>internal</em> {@code httpLlmResponse.json} schema (the artefact the server validates incoming
 * expectations against). But two things that ship to users were left ungated:</p>
 *
 * <ul>
 *   <li>the published OpenAPI specification —
 *       {@code jekyll-www.mock-server.com/mockserver-openapi.yaml} — from which SwaggerHub, the
 *       Postman/Bruno collections, and generated clients are produced; and</li>
 *   <li>the Node client's hand-maintained type declarations —
 *       {@code mockserver-client-node/mockServer.d.ts} — whose {@code LlmProvider} union is what a
 *       TypeScript user is allowed to type.</li>
 * </ul>
 *
 * <p>A provider added to {@link Provider} and to every server-side registration point, but not to
 * these two files, is fully functional on the server and simultaneously absent from the published
 * contract, and <em>nothing failed</em>: the internal-schema test above does not read these files,
 * and the Node drift test ({@code mockserver-client-node/test/no_proxy/generated_types_drift_test.js})
 * compares the {@code .d.ts} only against the OpenAPI spec — so when both drifted from the server
 * enum together, they still agreed with each other and the gate stayed green. This test removes that
 * blind spot by driving both consumer artefacts from the one authority, the compiled {@link Provider}
 * enum.</p>
 *
 * <p><strong>Why the {@code .d.ts} is pinned here, in Java, rather than in the Node suite.</strong>
 * The authority is the compiled {@link Provider} enum, which only a JVM test can read directly.
 * Pinning the {@code .d.ts} against anything else — in particular against the OpenAPI spec, which is
 * what the existing Node test does — reproduces the exact hole this test exists to close: two
 * consumer copies that can drift from the server in lock-step while still matching each other. So
 * both consumer artefacts are checked against the enum in one place. The reads are filesystem reads
 * of files outside this module, located by walking up to the repository root exactly as
 * {@code org.mockserver.openapi.OpenApiSpecSyncTest} does, so the test is independent of the working
 * directory Surefire launches with (reactor root or module directory).</p>
 *
 * <p>Both directions are asserted for each file, as in {@link ProviderSchemaEnumParityTest}: a
 * provider the contract omits is a supported feature users cannot discover; a provider only the
 * contract names is a promise the server cannot keep.</p>
 */
public class ProviderConsumerContractEnumParityTest {

    /**
     * The published OpenAPI specification. Also the marker used to locate the repository root. This
     * is the copy the documentation site publishes and the Postman/Bruno collections are generated
     * from; it is kept byte-identical to the jar-embedded copy by
     * {@code org.mockserver.openapi.OpenApiSpecSyncTest}.
     */
    private static final String WEBSITE_SPEC = "jekyll-www.mock-server.com/mockserver-openapi.yaml";

    /** The other, byte-identical copy — named in fix instructions because both must be kept in step. */
    private static final String EMBEDDED_SPEC =
        "mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml";

    /** The Node client's hand-maintained server-model type declarations. */
    private static final String NODE_DTS = "mockserver-client-node/mockServer.d.ts";

    // ----------------------------------------------------------------------------------------------
    // Published OpenAPI spec: components.schemas.HttpLlmResponse.properties.provider.enum
    // ----------------------------------------------------------------------------------------------

    @Test
    public void everyProviderTheServerSupportsIsInThePublishedOpenApiEnum() throws Exception {
        Set<String> specProviders = openApiProviderEnum();

        List<String> missingFromSpec = Arrays.stream(Provider.values())
            .map(Enum::name)
            .filter(name -> !specProviders.contains(name))
            .collect(Collectors.toList());

        assertThat(
            "Provider constant(s) missing from the published OpenAPI provider enum: " + missingFromSpec + ".\n"
                + "  File:   " + WEBSITE_SPEC + "\n"
                + "  Where:  components.schemas.HttpLlmResponse.properties.provider.enum\n"
                + "  Why it matters: this spec is what SwaggerHub, the Postman/Bruno collections and the\n"
                + "                  generated clients are built from, so a provider absent from it is\n"
                + "                  invisible to every consumer even though the server supports it.\n"
                + "  Fix:    add each listed value to that enum in BOTH byte-identical copies of the spec -\n"
                + "          " + WEBSITE_SPEC + "\n"
                + "          and " + EMBEDDED_SPEC + " (see OpenApiSpecSyncTest).",
            missingFromSpec, is(empty())
        );
    }

    @Test
    public void thePublishedOpenApiEnumNamesNoProviderTheServerDoesNotSupport() throws Exception {
        Set<String> providerNames = Arrays.stream(Provider.values()).map(Enum::name).collect(Collectors.toSet());

        List<String> unknownToTheServer = new ArrayList<>(openApiProviderEnum());
        unknownToTheServer.removeIf(providerNames::contains);

        assertThat(
            "Value(s) in the published OpenAPI provider enum with no matching Provider constant: "
                + unknownToTheServer + ".\n"
                + "  File:   " + WEBSITE_SPEC + " (components.schemas.HttpLlmResponse.properties.provider.enum)\n"
                + "  Why it matters: the spec promises a provider the server cannot serve; an expectation\n"
                + "                  naming it is rejected at runtime.\n"
                + "  Fix:    remove each listed value from that enum in BOTH copies of the spec (" + WEBSITE_SPEC
                + " and " + EMBEDDED_SPEC + "), or add it as a constant to org.mockserver.model.Provider.",
            unknownToTheServer, is(empty())
        );
    }

    // ----------------------------------------------------------------------------------------------
    // Node client type declarations: export type LlmProvider = "..." | "..." ;
    // ----------------------------------------------------------------------------------------------

    @Test
    public void everyProviderTheServerSupportsIsInTheNodeClientUnion() throws Exception {
        Set<String> unionMembers = nodeLlmProviderUnion();

        List<String> missingFromDts = Arrays.stream(Provider.values())
            .map(Enum::name)
            .filter(name -> !unionMembers.contains(name))
            .collect(Collectors.toList());

        assertThat(
            "Provider constant(s) missing from the Node client's LlmProvider union: " + missingFromDts + ".\n"
                + "  File:   " + NODE_DTS + "\n"
                + "  Where:  export type LlmProvider = \"...\" | ...\n"
                + "  Why it matters: a TypeScript user cannot name this provider without a compile error,\n"
                + "                  so a provider the server supports is unreachable from the Node client.\n"
                + "  Fix:    add \"<PROVIDER>\" as a member of the LlmProvider union in " + NODE_DTS + ".",
            missingFromDts, is(empty())
        );
    }

    @Test
    public void theNodeClientUnionNamesNoProviderTheServerDoesNotSupport() throws Exception {
        Set<String> providerNames = Arrays.stream(Provider.values()).map(Enum::name).collect(Collectors.toSet());

        List<String> unknownToTheServer = new ArrayList<>(nodeLlmProviderUnion());
        unknownToTheServer.removeIf(providerNames::contains);

        assertThat(
            "Member(s) of the Node client's LlmProvider union with no matching Provider constant: "
                + unknownToTheServer + ".\n"
                + "  File:   " + NODE_DTS + " (export type LlmProvider)\n"
                + "  Why it matters: the type lets a user typecheck an expectation the server then rejects.\n"
                + "  Fix:    remove each listed member from the LlmProvider union in " + NODE_DTS + ", or add\n"
                + "          it as a constant to org.mockserver.model.Provider.",
            unknownToTheServer, is(empty())
        );
    }

    // ----------------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------------

    private static Set<String> openApiProviderEnum() throws Exception {
        Path spec = repositoryRoot().resolve(WEBSITE_SPEC);
        assertThat("published OpenAPI spec is missing - expected at " + spec, Files.exists(spec), is(true));

        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(spec.toFile());
        JsonNode enumNode = root
            .path("components").path("schemas").path("HttpLlmResponse")
            .path("properties").path("provider").path("enum");
        assertThat(
            "components.schemas.HttpLlmResponse.properties.provider.enum not found in " + WEBSITE_SPEC
                + " - the spec structure changed and this parity test can no longer find the provider enum",
            enumNode.isArray() ? enumNode : null, notNullValue());

        Set<String> values = new LinkedHashSet<>();
        enumNode.forEach(value -> values.add(value.asText()));
        // Symmetric with nodeLlmProviderUnion()'s empty check. An extraction that
        // silently yields nothing is the classic way a parity test passes while
        // comparing two empty sets; the paired direction-A test would still catch
        // it, but a parity gate should not depend on its sibling to fail closed.
        assertThat(
            "no provider values parsed from " + WEBSITE_SPEC
                + " - the enum is present but empty, so this parity test is comparing against nothing",
            values.isEmpty(), is(false));
        return values;
    }

    private static final Pattern LLM_PROVIDER_UNION =
        Pattern.compile("export\\s+type\\s+LlmProvider\\s*=([^;]*);");

    private static Set<String> nodeLlmProviderUnion() throws Exception {
        Path dts = repositoryRoot().resolve(NODE_DTS);
        assertThat("Node client type declarations are missing - expected at " + dts, Files.exists(dts), is(true));

        String source = new String(Files.readAllBytes(dts), java.nio.charset.StandardCharsets.UTF_8);
        Matcher union = LLM_PROVIDER_UNION.matcher(source);
        assertThat("could not locate 'export type LlmProvider = ...;' in " + NODE_DTS, union.find(), is(true));

        Set<String> members = new LinkedHashSet<>();
        Matcher literal = Pattern.compile("\"([^\"]+)\"").matcher(union.group(1));
        while (literal.find()) {
            members.add(literal.group(1));
        }
        assertThat("the LlmProvider union in " + NODE_DTS + " parsed as empty", members.isEmpty(), is(false));
        return members;
    }

    /**
     * Locates the repository root by walking up from the module basedir ({@code user.dir} when
     * Surefire runs this module) until it finds the directory containing the published spec. Mirrors
     * {@code org.mockserver.openapi.OpenApiSpecSyncTest#locateRepositoryRoot()} so the test resolves
     * the same way whether Maven is invoked from the reactor root or the module directory.
     */
    private static Path repositoryRoot() {
        File current = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (current != null) {
            if (new File(current, WEBSITE_SPEC).isFile()) {
                return current.toPath();
            }
            current = current.getParentFile();
        }
        throw new IllegalStateException(
            "could not locate the repository root by walking up from user.dir="
                + System.getProperty("user.dir") + " looking for marker '" + WEBSITE_SPEC + "'");
    }
}
