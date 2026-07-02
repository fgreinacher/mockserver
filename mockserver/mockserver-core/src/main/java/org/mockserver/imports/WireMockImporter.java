package org.mockserver.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Header;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.JsonBody;
import org.mockserver.model.JsonPathBody;
import org.mockserver.model.NottableString;
import org.mockserver.model.Parameter;
import org.mockserver.model.RegexBody;
import org.mockserver.model.StringBody;
import org.mockserver.matchers.MatchType;
import org.mockserver.serialization.ObjectMapperFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Imports MockServer expectations from <a href="https://wiremock.org/">WireMock</a> stub-mapping
 * JSON.
 *
 * <p>Accepts three shapes, matching WireMock's own on-disk / API formats:
 * <ul>
 *     <li>a single stub-mapping object ({@code { "request": {...}, "response": {...} }});</li>
 *     <li>a {@code mappings} wrapper ({@code { "mappings": [ {stub}, ... ] }}) — the
 *         {@code GET /__admin/mappings} export and {@code mappings/} directory format;</li>
 *     <li>a bare JSON array of stub-mappings.</li>
 * </ul>
 *
 * <h2>Mapping</h2>
 * <table>
 *   <tr><th>WireMock request</th><th>MockServer matcher</th></tr>
 *   <tr><td>{@code method}</td><td>method (dropped when {@code ANY})</td></tr>
 *   <tr><td>{@code urlPath}</td><td>exact path</td></tr>
 *   <tr><td>{@code urlPathPattern} / {@code urlPattern}</td><td>regex path</td></tr>
 *   <tr><td>{@code url}</td><td>exact path + query parameters</td></tr>
 *   <tr><td>{@code queryParameters}, {@code headers}</td><td>parameter / header matchers
 *       ({@code equalTo} exact, {@code matches} regex, {@code contains} regex, {@code absent})</td></tr>
 *   <tr><td>{@code bodyPatterns}</td><td>body matcher ({@code equalToJson}, {@code matchesJsonPath},
 *       {@code contains}, {@code matches}/{@code equalTo})</td></tr>
 * </table>
 *
 * <p>The {@code response} maps to an {@link HttpResponse} (status/headers/body/{@code base64Body}/
 * {@code jsonBody}, {@code fixedDelayMilliseconds} → delay); {@code fault} maps to an
 * {@link HttpError}; a {@code proxyBaseUrl} response maps to an {@link HttpForward}. WireMock
 * scenarios ({@code scenarioName}/{@code requiredScenarioState}/{@code newScenarioState}) map
 * directly onto MockServer scenarios, and {@code priority} maps to MockServer priority {@code 5 - p}
 * (WireMock 1 = highest and an unspecified WireMock priority defaults to 5, so the mapping keeps an
 * explicit priority sitting relative to MockServer's unspecified default of 0 the same way it sits
 * relative to WireMock's default of 5).
 *
 * <p>Constructs with no faithful MockServer equivalent (e.g. {@code matchesXPath},
 * {@code delayDistribution}, response {@code transformers}) are reported as {@link ImportWarning}s
 * rather than silently dropped.
 */
public class WireMockImporter {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    public ImportResult importExpectations(String wireMockJson) {
        return importExpectations(wireMockJson, ImportRedaction.Options.enabled());
    }

    public ImportResult importExpectations(String wireMockJson, ImportRedaction.Options redactionOptions) {
        if (wireMockJson == null || wireMockJson.trim().isEmpty()) {
            throw new IllegalArgumentException("WireMock stub JSON body is required");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(wireMockJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse WireMock stub JSON: " + e.getMessage(), e);
        }

        List<JsonNode> stubs = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(stubs::add);
        } else if (root.has("mappings") && root.path("mappings").isArray()) {
            root.path("mappings").forEach(stubs::add);
        } else if (root.has("request") || root.has("response")) {
            stubs.add(root);
        } else {
            throw new IllegalArgumentException(
                "not a valid WireMock stub document — expected a stub object with 'request'/'response', a 'mappings' array, or a JSON array of stubs");
        }

        List<Expectation> expectations = new ArrayList<>();
        List<ImportWarning> warnings = new ArrayList<>();
        int index = 0;
        for (JsonNode stub : stubs) {
            String locator = "stub[" + index + "]";
            Expectation expectation = buildExpectation(stub, index, locator, warnings);
            if (expectation != null) {
                expectations.add(expectation);
            }
            index++;
        }

        return new ImportResult(ImportRedaction.redactPreservingActions(expectations, redactionOptions), warnings);
    }

    private Expectation buildExpectation(JsonNode stub, int index, String locator, List<ImportWarning> warnings) {
        JsonNode requestNode = stub.path("request");
        JsonNode responseNode = stub.path("response");
        if (requestNode.isMissingNode() && responseNode.isMissingNode()) {
            warnings.add(new ImportWarning(locator, "stub", "skipped — neither 'request' nor 'response' present"));
            return null;
        }

        HttpRequest httpRequest = buildRequest(requestNode, locator, warnings);

        Expectation expectation = new Expectation(httpRequest).withId("wiremock-" + index + idSuffix(stub));

        // Scenario mapping — WireMock scenarios line up 1:1 with MockServer scenarios
        // ("Started" is the default start state in both).
        String scenarioName = textOrNull(stub, "scenarioName");
        if (scenarioName != null) {
            expectation.withScenarioName(scenarioName);
            String requiredState = textOrNull(stub, "requiredScenarioState");
            if (requiredState != null) {
                expectation.withScenarioState(requiredState);
            }
            String newState = textOrNull(stub, "newScenarioState");
            if (newState != null) {
                expectation.withNewScenarioState(newState);
            }
        }

        // Priority — WireMock 1 = highest precedence and an unspecified WireMock priority defaults
        // to 5; MockServer matches higher numbers first with an unspecified default of 0. Map
        // WireMock priority p to MockServer (5 - p) so an explicit priority ranks against
        // MockServer's default of 0 the way it ranked against WireMock's default of 5:
        // WM 1 -> MS 4 (beats an unspecified catch-all at 0), WM 10 -> MS -5 (loses to it).
        // A plain `-priority` inversion instead put WM 1 at MS -1, losing to that catch-all.
        JsonNode priorityNode = stub.path("priority");
        if (priorityNode.isInt()) {
            expectation.withPriority(5 - priorityNode.asInt());
        }

        applyResponse(expectation, responseNode, locator, warnings);
        return expectation;
    }

    private HttpRequest buildRequest(JsonNode requestNode, String locator, List<ImportWarning> warnings) {
        HttpRequest httpRequest = request();
        if (requestNode.isMissingNode()) {
            return httpRequest;
        }

        String method = textOrNull(requestNode, "method");
        if (method != null && !"ANY".equalsIgnoreCase(method)) {
            httpRequest.withMethod(method.toUpperCase(Locale.ROOT));
        }

        // Path resolution — precedence mirrors WireMock's own (urlPath, urlPathPattern,
        // urlPattern, url). Only one is normally present.
        String urlPath = textOrNull(requestNode, "urlPath");
        String urlPathPattern = textOrNull(requestNode, "urlPathPattern");
        String urlPattern = textOrNull(requestNode, "urlPattern");
        String url = textOrNull(requestNode, "url");
        List<Parameter> queryParams = new ArrayList<>();

        if (urlPath != null) {
            httpRequest.withPath(urlPath);
        } else if (urlPathPattern != null) {
            httpRequest.withPath(urlPathPattern);
        } else if (urlPattern != null) {
            // urlPattern matches path AND query as one regex; MockServer matches the path only,
            // so strip a trailing query fragment and warn if one was present.
            int q = urlPattern.indexOf("\\?");
            if (q < 0) {
                q = urlPattern.indexOf('?');
            }
            if (q >= 0) {
                warnings.add(new ImportWarning(locator, "request.urlPattern",
                    "regex covered the query string; MockServer matches the path regex only — query portion dropped"));
                httpRequest.withPath(urlPattern.substring(0, q));
            } else {
                httpRequest.withPath(urlPattern);
            }
        } else if (url != null) {
            try {
                URI uri = new URI(url);
                String path = uri.getPath();
                httpRequest.withPath(path == null || path.isEmpty() ? "/" : path);
                queryParams.addAll(parseQueryString(uri.getRawQuery()));
            } catch (Exception e) {
                httpRequest.withPath(url.startsWith("/") ? url : "/");
            }
        }

        // queryParameters
        JsonNode queryParameters = requestNode.path("queryParameters");
        if (queryParameters.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = queryParameters.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                NottableString value = matchPredicate(field.getValue(), locator, "queryParameters." + field.getKey(), warnings);
                if (value != null) {
                    queryParams.add(Parameter.param(NottableString.string(field.getKey()), value));
                }
            }
        }
        if (!queryParams.isEmpty()) {
            httpRequest.withQueryStringParameters(queryParams);
        }

        // headers
        JsonNode headers = requestNode.path("headers");
        if (headers.isObject()) {
            List<Header> headerList = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = headers.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                NottableString value = matchPredicate(field.getValue(), locator, "headers." + field.getKey(), warnings);
                if (value != null) {
                    headerList.add(Header.header(NottableString.string(field.getKey()), value));
                }
            }
            if (!headerList.isEmpty()) {
                httpRequest.withHeaders(headerList);
            }
        }

        // cookies
        JsonNode cookies = requestNode.path("cookies");
        if (cookies.isObject() && !cookies.isEmpty()) {
            warnings.add(new ImportWarning(locator, "request.cookies",
                "cookie predicates are not mapped; add explicit cookie matchers if needed"));
        }

        // bodyPatterns
        JsonNode bodyPatterns = requestNode.path("bodyPatterns");
        if (bodyPatterns.isArray() && !bodyPatterns.isEmpty()) {
            applyBodyPattern(httpRequest, bodyPatterns, locator, warnings);
        }

        // basicAuthCredentials / multipartPatterns — not mapped
        if (requestNode.has("basicAuthCredentials")) {
            warnings.add(new ImportWarning(locator, "request.basicAuthCredentials",
                "basic-auth credential matching is not mapped; add an Authorization header matcher if needed"));
        }
        if (requestNode.has("multipartPatterns")) {
            warnings.add(new ImportWarning(locator, "request.multipartPatterns",
                "multipart body-part matching is not mapped"));
        }

        return httpRequest;
    }

    /**
     * Convert a WireMock match predicate object ({@code {"equalTo":"x"}}, {@code {"matches":"re"}},
     * {@code {"contains":"y"}}, {@code {"absent":true}}) into a {@link NottableString} suitable for a
     * header / query-parameter matcher. Returns {@code null} when the whole entry should be omitted
     * (an {@code absent} predicate has no MockServer analogue on a single named key).
     */
    private NottableString matchPredicate(JsonNode predicate, String locator, String field, List<ImportWarning> warnings) {
        if (predicate.isTextual()) {
            return NottableString.string(predicate.asText());
        }
        if (!predicate.isObject()) {
            return NottableString.string(predicate.asText());
        }
        if (predicate.has("equalTo")) {
            boolean caseInsensitive = predicate.path("caseInsensitive").asBoolean(false);
            String value = predicate.path("equalTo").asText();
            if (caseInsensitive) {
                return NottableString.string("(?i)" + java.util.regex.Pattern.quote(value));
            }
            return NottableString.string(value);
        }
        if (predicate.has("matches")) {
            return NottableString.string(predicate.path("matches").asText());
        }
        if (predicate.has("contains")) {
            return NottableString.string(".*" + java.util.regex.Pattern.quote(predicate.path("contains").asText()) + ".*");
        }
        if (predicate.has("doesNotMatch")) {
            return NottableString.not(predicate.path("doesNotMatch").asText());
        }
        if (predicate.path("absent").asBoolean(false)) {
            warnings.add(new ImportWarning(locator, field,
                "'absent' predicate has no direct MockServer analogue on a named key — omitted (a request lacking the key already matches)"));
            return null;
        }
        warnings.add(new ImportWarning(locator, field,
            "unsupported match predicate " + fieldNames(predicate) + " — omitted"));
        return null;
    }

    private void applyBodyPattern(HttpRequest httpRequest, JsonNode bodyPatterns, String locator, List<ImportWarning> warnings) {
        // Use the first mappable pattern for the body matcher; warn on any extras/unsupported ones
        // (MockServer matches a single Body per request).
        boolean bodySet = false;
        int i = 0;
        for (JsonNode pattern : bodyPatterns) {
            String patternLocator = locator + " bodyPatterns[" + i + "]";
            i++;
            if (pattern.has("equalToJson")) {
                if (bodySet) {
                    warnings.add(new ImportWarning(patternLocator, "bodyPatterns",
                        "multiple body patterns; only the first mappable one is applied"));
                    continue;
                }
                boolean ignoreExtra = pattern.path("ignoreExtraElements").asBoolean(false);
                MatchType matchType = ignoreExtra ? MatchType.ONLY_MATCHING_FIELDS : MatchType.STRICT;
                JsonNode json = pattern.path("equalToJson");
                String jsonText = json.isTextual() ? json.asText() : json.toString();
                httpRequest.withBody(JsonBody.json(jsonText, matchType));
                bodySet = true;
                if (pattern.path("ignoreArrayOrder").asBoolean(false) && !ignoreExtra) {
                    warnings.add(new ImportWarning(patternLocator, "bodyPatterns.ignoreArrayOrder",
                        "ignoreArrayOrder is approximated by MockServer's JSON matching and not separately configurable"));
                }
            } else if (pattern.has("matchesJsonPath")) {
                if (bodySet) {
                    warnings.add(new ImportWarning(patternLocator, "bodyPatterns",
                        "multiple body patterns; only the first mappable one is applied"));
                    continue;
                }
                JsonNode jsonPath = pattern.path("matchesJsonPath");
                String expression = jsonPath.isTextual() ? jsonPath.asText() : textOrNull(jsonPath, "expression");
                if (expression != null) {
                    httpRequest.withBody(JsonPathBody.jsonPath(expression));
                    bodySet = true;
                } else {
                    warnings.add(new ImportWarning(patternLocator, "bodyPatterns.matchesJsonPath",
                        "matchesJsonPath object form has no 'expression' field — body matcher omitted"));
                }
            } else if (pattern.has("equalTo")) {
                if (bodySet) {
                    warnings.add(new ImportWarning(patternLocator, "bodyPatterns",
                        "multiple body patterns; only the first mappable one is applied"));
                    continue;
                }
                httpRequest.withBody(StringBody.exact(pattern.path("equalTo").asText()));
                bodySet = true;
            } else if (pattern.has("contains")) {
                if (bodySet) {
                    warnings.add(new ImportWarning(patternLocator, "bodyPatterns",
                        "multiple body patterns; only the first mappable one is applied"));
                    continue;
                }
                httpRequest.withBody(StringBody.subString(pattern.path("contains").asText()));
                bodySet = true;
            } else if (pattern.has("matches")) {
                if (bodySet) {
                    warnings.add(new ImportWarning(patternLocator, "bodyPatterns",
                        "multiple body patterns; only the first mappable one is applied"));
                    continue;
                }
                httpRequest.withBody(RegexBody.regex(pattern.path("matches").asText()));
                bodySet = true;
            } else if (pattern.has("equalToXml") || pattern.has("matchesXPath")) {
                warnings.add(new ImportWarning(patternLocator, "bodyPatterns." + (pattern.has("equalToXml") ? "equalToXml" : "matchesXPath"),
                    "XML/XPath body matching is not mapped by this importer"));
            } else {
                warnings.add(new ImportWarning(patternLocator, "bodyPatterns",
                    "unsupported body pattern " + fieldNames(pattern) + " — omitted"));
            }
        }
    }

    private void applyResponse(Expectation expectation, JsonNode responseNode, String locator, List<ImportWarning> warnings) {
        if (responseNode.isMissingNode()) {
            expectation.thenRespond(response().withStatusCode(200));
            return;
        }

        // fault → HttpError (connection-level failure). Any fault takes precedence over a body.
        String fault = textOrNull(responseNode, "fault");
        if (fault != null) {
            expectation.thenError(mapFault(fault, locator, warnings));
            return;
        }

        // proxyBaseUrl → forward
        String proxyBaseUrl = textOrNull(responseNode, "proxyBaseUrl");
        if (proxyBaseUrl != null) {
            expectation.thenForward(mapForward(proxyBaseUrl, locator, warnings));
            return;
        }

        HttpResponse httpResponse = response();
        int status = responseNode.path("status").asInt(200);
        httpResponse.withStatusCode(status);

        JsonNode headers = responseNode.path("headers");
        if (headers.isObject()) {
            List<Header> headerList = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = headers.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                JsonNode v = field.getValue();
                if (v.isArray()) {
                    List<String> values = new ArrayList<>();
                    v.forEach(n -> values.add(n.asText()));
                    headerList.add(Header.header(field.getKey(), values));
                } else {
                    headerList.add(Header.header(field.getKey(), v.asText()));
                }
            }
            if (!headerList.isEmpty()) {
                httpResponse.withHeaders(headerList);
            }
        }

        // Body precedence: jsonBody (structured) > body (string) > base64Body
        JsonNode jsonBody = responseNode.path("jsonBody");
        String body = textOrNull(responseNode, "body");
        String base64Body = textOrNull(responseNode, "base64Body");
        if (!jsonBody.isMissingNode() && !jsonBody.isNull()) {
            httpResponse.withBody(jsonBody.toString(), org.mockserver.model.MediaType.APPLICATION_JSON);
        } else if (body != null && !body.isEmpty()) {
            httpResponse.withBody(body);
        } else if (base64Body != null && !base64Body.isEmpty()) {
            try {
                // Decode to a text body (mirrors HarImporter) so the generated expectation carries
                // readable content; a binary body would re-encode to Base64 on serialization.
                httpResponse.withBody(new String(Base64.getDecoder().decode(base64Body), java.nio.charset.StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                warnings.add(new ImportWarning(locator, "response.base64Body", "invalid Base64 — response body omitted"));
            }
        }

        // fixedDelayMilliseconds → delay
        JsonNode delay = responseNode.path("fixedDelayMilliseconds");
        if (delay.isInt() || delay.isLong()) {
            httpResponse.withDelay(java.util.concurrent.TimeUnit.MILLISECONDS, delay.asLong());
        }
        if (responseNode.has("delayDistribution")) {
            warnings.add(new ImportWarning(locator, "response.delayDistribution",
                "random delay distribution is not mapped; only fixedDelayMilliseconds is applied"));
        }
        if (responseNode.has("transformers") || responseNode.has("transformerParameters")) {
            warnings.add(new ImportWarning(locator, "response.transformers",
                "response templating/transformers are not mapped; the response body is used verbatim"));
        }
        if (responseNode.has("bodyFileName")) {
            warnings.add(new ImportWarning(locator, "response.bodyFileName",
                "bodyFileName references an external file that is not resolved on import; inline the body instead"));
        }

        expectation.thenRespond(httpResponse);
    }

    private HttpError mapFault(String fault, String locator, List<ImportWarning> warnings) {
        // All WireMock faults are connection-level failures with no MockServer analogue beyond
        // dropping the connection; the exact on-the-wire garbage of MALFORMED_RESPONSE_CHUNK /
        // RANDOM_DATA_THEN_CLOSE is approximated by an immediate connection drop.
        switch (fault.toUpperCase(Locale.ROOT)) {
            case "EMPTY_RESPONSE":
            case "CONNECTION_RESET_BY_PEER":
                return HttpError.error().withDropConnection(true);
            case "MALFORMED_RESPONSE_CHUNK":
            case "RANDOM_DATA_THEN_CLOSE":
                warnings.add(new ImportWarning(locator, "response.fault." + fault,
                    "approximated as an immediate connection drop; the exact malformed/random bytes are not reproduced"));
                return HttpError.error().withDropConnection(true);
            default:
                warnings.add(new ImportWarning(locator, "response.fault." + fault,
                    "unknown fault type — approximated as a dropped connection"));
                return HttpError.error().withDropConnection(true);
        }
    }

    private HttpForward mapForward(String proxyBaseUrl, String locator, List<ImportWarning> warnings) {
        HttpForward forward = HttpForward.forward();
        try {
            URI uri = new URI(proxyBaseUrl);
            forward.withHost(uri.getHost());
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            forward.withScheme(https ? HttpForward.Scheme.HTTPS : HttpForward.Scheme.HTTP);
            int port = uri.getPort();
            forward.withPort(port > 0 ? port : (https ? 443 : 80));
            if (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
                warnings.add(new ImportWarning(locator, "response.proxyBaseUrl",
                    "proxyBaseUrl path prefix '" + uri.getPath() + "' is not applied; MockServer forwards to host/port only"));
            }
        } catch (Exception e) {
            warnings.add(new ImportWarning(locator, "response.proxyBaseUrl",
                "could not parse proxyBaseUrl '" + proxyBaseUrl + "' — forward target may be incomplete"));
        }
        return forward;
    }

    private List<Parameter> parseQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return List.of();
        }
        List<Parameter> params = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                params.add(Parameter.param(decodeComponent(pair.substring(0, eq)), decodeComponent(pair.substring(eq + 1))));
            } else {
                params.add(Parameter.param(decodeComponent(pair), ""));
            }
        }
        return params;
    }

    private String idSuffix(JsonNode stub) {
        String id = textOrNull(stub, "id");
        if (id == null) {
            id = textOrNull(stub, "uuid");
        }
        if (id == null) {
            String name = textOrNull(stub, "name");
            id = name != null ? name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "") : null;
        }
        return id != null && !id.isEmpty() ? "-" + id : "";
    }

    private static String fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull() || !child.isValueNode()) {
            return null;
        }
        return child.asText();
    }

    private static String decodeComponent(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
