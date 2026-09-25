package org.mockserver.mock.pact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.imports.ImportRedaction;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.log.model.LogEntry;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Imports a <a href="https://docs.pact.io/">Pact</a> v3 consumer contract (JSON) and generates one
 * MockServer {@link Expectation} per interaction, so a contract published by a consumer (or exported
 * via {@link PactExporter}) can be replayed by MockServer as a stub provider.
 *
 * <p>This is the inverse of {@link PactVerifier}: rather than checking that existing expectations
 * satisfy a contract, it <em>creates</em> expectations directly from the contract's interactions.
 * For each interaction it builds:
 * <ul>
 *   <li>an {@link HttpRequest} matcher from {@code request} ({@code method}, {@code path},
 *       {@code query}, {@code headers}, {@code body}), and</li>
 *   <li>an {@link HttpResponse} from {@code response} ({@code status}, {@code headers},
 *       {@code body}).</li>
 * </ul>
 *
 * <h2>matchingRules mapping</h2>
 * Pact v3 carries match instructions in an interaction-level {@code matchingRules.request} object
 * keyed by category ({@code path}, {@code query}, {@code header}, {@code body}). MockServer's
 * {@code RegexStringMatcher} already treats a matcher value as a regular expression (falling back to
 * exact-string equality), so the importer maps Pact rules onto plain matcher values:
 * <table border="1">
 *   <caption>Pact matchingRule to MockServer matcher</caption>
 *   <tr><th>Pact rule</th><th>MockServer matcher value</th></tr>
 *   <tr><td>{@code regex}</td><td>the supplied {@code regex} pattern (matched as a regex)</td></tr>
 *   <tr><td>{@code include}</td><td>{@code .*<value>.*} substring regex</td></tr>
 *   <tr><td>{@code type} / {@code number} / {@code integer} / {@code decimal} / {@code boolean}</td>
 *       <td>relaxed to a value-presence regex {@code .+} (any non-empty value of that field)</td></tr>
 *   <tr><td>anything else / no rule</td><td>the concrete example value (exact match)</td></tr>
 * </table>
 * Unmapped rule types fall back to exact matching and are logged at DEBUG.
 *
 * <p>Redaction (see {@link ImportRedaction}) is applied to the generated expectations exactly as for
 * HAR/Postman imports, so credentials carried in a contract never land verbatim in the expectation
 * store. Redaction is on by default; pass {@link ImportRedaction.Options#disabled()} to keep values
 * verbatim.
 */
public class PactImporter {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    private final MockServerLogger mockServerLogger;

    public PactImporter() {
        this(new MockServerLogger(PactImporter.class));
    }

    public PactImporter(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * Parses a Pact v3 contract and returns one redacted expectation per interaction.
     *
     * @param pactJson the Pact v3 contract as a JSON string
     * @return the generated (redacted) expectations
     * @throws IllegalArgumentException if the JSON is null, blank, malformed, or has no interactions
     */
    public List<Expectation> importExpectations(String pactJson) {
        return importExpectations(pactJson, ImportRedaction.Options.enabled());
    }

    /**
     * Parses a Pact v3 contract and returns one expectation per interaction, applying the supplied
     * redaction options before the expectations are returned.
     *
     * @param pactJson         the Pact v3 contract as a JSON string
     * @param redactionOptions controls whether/how sensitive data is masked; pass
     *                         {@link ImportRedaction.Options#disabled()} to keep values verbatim
     * @return the generated expectations
     * @throws IllegalArgumentException if the JSON is null, blank, malformed, or has no interactions
     */
    public List<Expectation> importExpectations(String pactJson, ImportRedaction.Options redactionOptions) {
        if (isBlank(pactJson)) {
            throw new IllegalArgumentException("Pact contract JSON body is required");
        }

        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(pactJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse Pact JSON: " + e.getMessage(), e);
        }

        final JsonNode interactions = root.path("interactions");
        if (interactions.isMissingNode() || !interactions.isArray() || interactions.isEmpty()) {
            throw new IllegalArgumentException("not a valid Pact contract — missing or empty 'interactions' array");
        }

        final List<Expectation> expectations = new ArrayList<>();
        int index = 0;
        for (final JsonNode interaction : interactions) {
            final Expectation expectation = buildExpectation(interaction, index);
            if (expectation != null) {
                expectations.add(expectation);
            }
            index++;
        }
        return ImportRedaction.redact(expectations, redactionOptions);
    }

    private Expectation buildExpectation(JsonNode interaction, int index) {
        final JsonNode requestNode = interaction.path("request");
        final JsonNode responseNode = interaction.path("response");
        if (requestNode.isMissingNode()) {
            return null;
        }

        final JsonNode requestRules = interaction.path("matchingRules").path("request");

        final HttpRequest httpRequest = buildRequest(requestNode, requestRules);
        final HttpResponse httpResponse = buildResponse(responseNode);

        final String description = interaction.path("description").asText("");
        final String id = isBlank(description) ? "pact-" + index : description;

        final Expectation expectation = new Expectation(httpRequest).withId(id).thenRespond(httpResponse);

        // Preserve the interaction's provider state(s) (the "given ..." precondition). The gating
        // state name is mapped onto MockServer's scenario-state mechanism so the generated
        // expectation only matches once that provider state has been activated. Stateless
        // interactions leave scenarioName/scenarioState null and always match.
        final String providerState = PactProviderStates.gatingStateOf(interaction);
        if (providerState != null) {
            expectation
                .withScenarioName(PactProviderStates.SCENARIO_NAME)
                .withScenarioState(providerState);
        }

        return expectation;
    }

    private HttpRequest buildRequest(JsonNode requestNode, JsonNode requestRules) {
        final HttpRequest httpRequest = request();

        final String method = textOrNull(requestNode, "method");
        if (method != null) {
            httpRequest.withMethod(method.toUpperCase(Locale.ROOT));
        }

        final String path = textOrNull(requestNode, "path");
        if (path != null) {
            httpRequest.withPath(matchValue(path, ruleFor(requestRules.path("path"), "path", null)));
        }

        final JsonNode query = requestNode.path("query");
        if (query.isObject()) {
            final JsonNode queryRules = requestRules.path("query");
            final Iterator<Map.Entry<String, JsonNode>> fields = query.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> entry = fields.next();
                final String name = entry.getKey();
                for (final String value : valuesOf(entry.getValue())) {
                    httpRequest.withQueryStringParameter(name, matchValue(value, ruleFor(queryRules, "query", name)));
                }
            }
        }

        final JsonNode headers = requestNode.path("headers");
        if (headers.isObject()) {
            final JsonNode headerRules = requestRules.path("header");
            final Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> entry = fields.next();
                final String name = entry.getKey();
                for (final String value : valuesOf(entry.getValue())) {
                    httpRequest.withHeader(new Header(name, matchValue(value, ruleFor(headerRules, "header", name))));
                }
            }
        }

        final JsonNode body = requestNode.path("body");
        if (!body.isMissingNode() && !body.isNull()) {
            if (body.isObject() || body.isArray()) {
                // structured JSON request bodies are matched semantically (key order /
                // whitespace insensitive) rather than by exact string equality
                httpRequest.withBody(org.mockserver.model.JsonBody.json(body.toString()));
            } else {
                httpRequest.withBody(bodyToString(body));
            }
        }

        return httpRequest;
    }

    private HttpResponse buildResponse(JsonNode responseNode) {
        final HttpResponse httpResponse = response();
        if (responseNode.isMissingNode()) {
            return httpResponse.withStatusCode(200);
        }

        httpResponse.withStatusCode(responseNode.path("status").asInt(200));

        final JsonNode headers = responseNode.path("headers");
        if (headers.isObject()) {
            final List<Header> responseHeaders = new ArrayList<>();
            final Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> entry = fields.next();
                responseHeaders.add(new Header(entry.getKey(), valuesOf(entry.getValue())));
            }
            if (!responseHeaders.isEmpty()) {
                httpResponse.withHeaders(responseHeaders);
            }
        }

        final JsonNode body = responseNode.path("body");
        if (!body.isMissingNode() && !body.isNull()) {
            httpResponse.withBody(bodyToString(body));
        }

        return httpResponse;
    }

    /**
     * Resolves the first matchingRule for a Pact category/key, returning the rule node or null. Pact
     * v3 keys body/header/query rules with a JSON-path-ish expression (e.g. {@code $['Accept'][0]} or
     * {@code $.page[0]}); the path category is keyed directly. This resolver matches a rule whose key
     * contains the field name (or, for {@code path}, the lone {@code path}/{@code $.path} entry).
     */
    private JsonNode ruleFor(JsonNode categoryRules, String category, String fieldName) {
        if (categoryRules == null || categoryRules.isMissingNode() || !categoryRules.isObject()) {
            return null;
        }
        // path category: rules live directly under matchingRules.request.path
        if ("path".equals(category)) {
            return firstMatcher(categoryRules);
        }
        final Iterator<Map.Entry<String, JsonNode>> fields = categoryRules.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> entry = fields.next();
            final String key = entry.getKey();
            if (fieldName == null || keyMatchesField(key, fieldName)) {
                return firstMatcher(entry.getValue());
            }
        }
        return null;
    }

    /**
     * Tests whether a Pact rule key (e.g. {@code $['Accept'][0]}, {@code $.page[0]}, {@code page}) refers
     * to the given field name. The field must appear quoted ({@code 'field'}) or as a dotted segment
     * bounded by a following {@code [}/{@code ]} (or end-of-string), so a short name like {@code q} or
     * {@code id} does not false-match a longer key such as {@code $.query[0]} or {@code $.identity[0]}.
     */
    private static boolean keyMatchesField(String key, String fieldName) {
        if (key.equals(fieldName) || key.contains("'" + fieldName + "'")) {
            return true;
        }
        final int dotIdx = key.indexOf("." + fieldName);
        if (dotIdx < 0) {
            return false;
        }
        final int afterIdx = dotIdx + 1 + fieldName.length();
        if (afterIdx >= key.length()) {
            return true;
        }
        final char next = key.charAt(afterIdx);
        return next == '[' || next == ']';
    }

    private JsonNode firstMatcher(JsonNode ruleNode) {
        if (ruleNode == null || ruleNode.isMissingNode()) {
            return null;
        }
        final JsonNode matchers = ruleNode.path("matchers");
        if (matchers.isArray() && !matchers.isEmpty()) {
            return matchers.get(0);
        }
        // some producers inline the rule (e.g. {"match":"regex","regex":"..."})
        return ruleNode.has("match") ? ruleNode : null;
    }

    /**
     * Translates a Pact example value plus its (optional) matchingRule into the concrete matcher value
     * MockServer stores. MockServer's RegexStringMatcher treats the value as a regex with exact-string
     * fallback, so a regex pattern, a substring regex, or the literal example all work as plain values.
     */
    private String matchValue(String exampleValue, JsonNode rule) {
        if (rule == null) {
            return exampleValue;
        }
        final String match = rule.path("match").asText(rule.path("type").asText(""));
        switch (match) {
            case "regex":
                final String regex = rule.path("regex").asText(null);
                return regex != null ? regex : exampleValue;
            case "include":
                final String included = rule.path("value").asText(exampleValue);
                return ".*" + java.util.regex.Pattern.quote(included) + ".*";
            case "type":
            case "number":
            case "integer":
            case "decimal":
            case "boolean":
                // relax to "any non-empty value" — MockServer has no first-class type matcher for
                // a header/query/path string, so a presence regex is the closest faithful mapping
                return ".+";
            default:
                if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.DEBUG)
                            .setMessageFormat("Pact matchingRule '{}' is not mapped — falling back to exact matching for value:{}")
                            .setArguments(match, exampleValue)
                    );
                }
                return exampleValue;
        }
    }

    private static List<String> valuesOf(JsonNode node) {
        final List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (final JsonNode value : node) {
                values.add(value.asText());
            }
        } else {
            values.add(node.asText());
        }
        return values;
    }

    /** Serializes a structured JSON body back to its string form; passes plain strings through. */
    private static String bodyToString(JsonNode body) {
        return body.isTextual() ? body.asText() : body.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        final JsonNode child = node.path(field);
        return (child.isMissingNode() || child.isNull()) ? null : child.asText();
    }
}
