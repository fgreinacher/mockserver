package org.mockserver.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.ExpectationDTO;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Drift guard between the published OpenAPI specification's {@code Expectation} schema and the Java
 * type that actually defines the wire format, {@link ExpectationDTO}.
 *
 * <p><strong>Why this exists.</strong> {@link OpenApiSpecSyncTest} asserts the two copies of the
 * specification are byte-identical, which makes them one document — but it compares a copy to a
 * copy, so it is blind to both copies being wrong together. That is exactly what happened: eleven
 * properties that {@code ExpectationDTO} serialises and accepts — the LLM, gRPC streaming, gRPC
 * bidi, binary and DNS actions, forward validation and fallback, before/after actions, multi-step
 * expectations and capture rules — were declared in neither copy. The published spec, the
 * SwaggerHub upload and the generated Postman and Bruno collections therefore described an API
 * substantially smaller than the one MockServer implements, and a client generated from it could
 * not express those actions at all.
 *
 * <p><strong>What this does not mean.</strong> The OpenAPI document is <em>documentation</em>. It
 * is served verbatim by {@code OpenAPISpecHandler} and is never parsed at runtime, so a property
 * missing from it is not rejected on the wire. Incoming expectation JSON is validated against a
 * different artefact — {@code org/mockserver/model/schema/expectation.json}, loaded by
 * {@code JsonSchemaExpectationValidator} — which does declare all eleven. The cost of this drift is
 * therefore borne by everyone consuming the spec (documentation readers, generated clients,
 * external validators, the collections), not by the server.
 *
 * <p><strong>Which direction this guards, and why that is the important one.</strong> The check is
 * driven from the DTO: every Jackson-serialisable property of {@link ExpectationDTO} must appear in
 * the schema. It deliberately does <em>not</em> enumerate the schema and look for a matching Java
 * field, because a test whose cases come from the artefact it polices cannot detect an omission in
 * that artefact — enumerate the schema and a property missing from the schema simply never
 * generates a case, and the test stays green while the gap widens. Deriving from the other side of
 * the contract is what makes this able to fail. The reverse direction (a schema property with no
 * Java field) is asserted too, as a cheap catch for a documented-but-unimplemented field: the repo
 * has shipped exactly that before, when {@code HttpChaosProfile.connectionDrop} was documented,
 * existed nowhere in Java, and propagated into the Go client, where users set a property the server
 * silently ignored.
 *
 * <p>Reads the website copy; {@link OpenApiSpecSyncTest} guarantees the embedded copy is identical,
 * so checking one checks both.
 */
public class OpenApiSpecExpectationSchemaTest {

    private static final String WEBSITE_SPEC =
        "jekyll-www.mock-server.com/mockserver-openapi.yaml";

    /**
     * Marker used to locate the repository root — see {@link OpenApiSpecSyncTest}.
     */
    private static final String EMBEDDED_SPEC =
        "mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml";

    /**
     * Properties {@link ExpectationDTO} exposes to Jackson that the specification intentionally does
     * not declare. Every entry needs a reason; an entry without one is drift wearing an exemption.
     *
     * <p>Empty today. It exists so that a future genuinely-internal property has somewhere to go
     * other than silently weakening the assertion.
     */
    private static final Set<String> NOT_DOCUMENTED_BY_DESIGN = new TreeSet<>();

    /**
     * Schema properties with no {@link ExpectationDTO} field. Empty today, and the intent is that it
     * stays empty: a documented property the server does not implement is a defect, not an
     * exemption.
     */
    private static final Set<String> DOCUMENTED_WITHOUT_A_JAVA_FIELD = new TreeSet<>();

    @Test
    public void everyExpectationPropertyTheServerAcceptsIsDeclaredInTheOpenApiSpec() throws Exception {
        Set<String> javaProperties = jacksonPropertiesOf(ExpectationDTO.class);
        Set<String> schemaProperties = expectationSchemaProperties();

        Set<String> undocumented = new TreeSet<>(javaProperties);
        undocumented.removeAll(schemaProperties);
        undocumented.removeAll(NOT_DOCUMENTED_BY_DESIGN);

        assertThat(
            "ExpectationDTO serialises properties the OpenAPI spec does not declare, so the published"
                + " spec, the SwaggerHub upload and the generated Postman/Bruno collections describe a"
                + " smaller API than MockServer implements, and a generated client cannot express these"
                + " actions:\n  " + undocumented
                + "\n\nDeclare each under components.schemas.Expectation.properties in "
                + WEBSITE_SPEC + ", copy the file over " + EMBEDDED_SPEC
                + " so OpenApiSpecSyncTest still passes, then regenerate the collections with"
                + " `python3 scripts/collections/generate_collections.py`."
                + "\n\n  ExpectationDTO properties (" + javaProperties.size() + "): " + new TreeSet<>(javaProperties)
                + "\n  spec properties (" + schemaProperties.size() + "): " + new TreeSet<>(schemaProperties),
            undocumented, is(java.util.Collections.<String>emptySet()));
    }

    @Test
    public void everyExpectationPropertyTheOpenApiSpecDeclaresExistsInJava() throws Exception {
        Set<String> javaProperties = jacksonPropertiesOf(ExpectationDTO.class);
        Set<String> schemaProperties = expectationSchemaProperties();

        Set<String> unimplemented = new TreeSet<>(schemaProperties);
        unimplemented.removeAll(javaProperties);
        unimplemented.removeAll(DOCUMENTED_WITHOUT_A_JAVA_FIELD);

        assertThat(
            "the OpenAPI spec declares Expectation properties that ExpectationDTO has no field for, so"
                + " a user following the documentation — or a client generated from it — sets a property"
                + " the server silently ignores:\n  " + unimplemented
                + "\n\nEither implement them or remove them from components.schemas.Expectation in "
                + WEBSITE_SPEC + " (and the embedded copy).",
            unimplemented, is(java.util.Collections.<String>emptySet()));
    }

    /**
     * The property names Jackson will read and write for the given type, taken from the very
     * {@link ObjectMapper} MockServer serialises expectations with, so annotations that rename or
     * hide a property ({@code @JsonIgnore}, {@code @JsonProperty}) are honoured exactly as they are
     * at runtime rather than approximated by reflecting over declared fields.
     */
    private static Set<String> jacksonPropertiesOf(Class<?> type) {
        ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        Set<String> names = new LinkedHashSet<>();
        for (BeanPropertyDefinition property : objectMapper
            .getSerializationConfig()
            .introspect(objectMapper.getSerializationConfig().constructType(type))
            .findProperties()) {
            names.add(property.getName());
        }
        return names;
    }

    private static Set<String> expectationSchemaProperties() throws Exception {
        Path spec = locateRepositoryRoot().toPath().resolve(WEBSITE_SPEC);
        assertThat("OpenAPI spec is missing - expected at " + spec, Files.exists(spec), is(true));

        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(Files.readAllBytes(spec));
        JsonNode properties = root.path("components").path("schemas").path("Expectation").path("properties");
        assertThat(
            "components.schemas.Expectation.properties is missing from " + spec
                + " - the spec no longer describes expectations at all, or the schema was renamed",
            properties.isObject() && properties.size() > 0, is(true));

        Set<String> names = new LinkedHashSet<>();
        for (Iterator<String> iterator = properties.fieldNames(); iterator.hasNext(); ) {
            names.add(iterator.next());
        }
        return names;
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
