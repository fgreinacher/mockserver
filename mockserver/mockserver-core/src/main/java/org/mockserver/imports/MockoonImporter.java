package org.mockserver.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.ResponseMode;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Imports MockServer expectations from a <a href="https://mockoon.com/">Mockoon</a> environment
 * JSON export.
 *
 * <p>Each Mockoon {@code route} maps to one or more expectations. The route {@code method} and
 * {@code endpoint} (with {@code :param} segments converted to single-segment regex matchers) form
 * the request matcher; each route {@code response} supplies status code, headers, body and a
 * {@code latency} → delay mapping.
 *
 * <h2>Multiple responses per route</h2>
 * A Mockoon route can hold several responses selected by {@code responseMode}:
 * <ul>
 *     <li><strong>null / rules-based</strong> (the default) — each response's {@code rules} become
 *         extra request matchers, and one expectation is emitted per response with a descending
 *         priority so the array order is preserved and the {@code default} response acts as the
 *         lowest-priority catch-all.</li>
 *     <li><strong>SEQUENTIAL</strong> — one cycling multi-response expectation
 *         ({@link ResponseMode#SEQUENTIAL}); rules are ignored (with a warning if present).</li>
 *     <li><strong>RANDOM</strong> — one {@link ResponseMode#RANDOM} multi-response expectation.</li>
 *     <li><strong>DISABLE_RULES / FALLBACK</strong> — approximated (first/default response served);
 *         a warning documents the boundary.</li>
 * </ul>
 *
 * <p>Rule targets/operators without a MockServer equivalent (cookie/path/number targets, the
 * {@code null}/{@code empty_array} operators, {@code OR}-combined rules) are reported as
 * {@link ImportWarning}s rather than silently dropped.
 */
public class MockoonImporter {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    public ImportResult importExpectations(String environmentJson) {
        return importExpectations(environmentJson, ImportRedaction.Options.enabled());
    }

    public ImportResult importExpectations(String environmentJson, ImportRedaction.Options redactionOptions) {
        if (environmentJson == null || environmentJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Mockoon environment JSON body is required");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(environmentJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse Mockoon environment JSON: " + e.getMessage(), e);
        }

        JsonNode routes = root.path("routes");
        if (!routes.isArray()) {
            throw new IllegalArgumentException(
                "not a valid Mockoon environment — missing top-level 'routes' array");
        }

        List<Expectation> expectations = new ArrayList<>();
        List<ImportWarning> warnings = new ArrayList<>();
        int routeIndex = 0;
        for (JsonNode route : routes) {
            buildRoute(route, routeIndex, expectations, warnings);
            routeIndex++;
        }

        return new ImportResult(ImportRedaction.redactPreservingActions(expectations, redactionOptions), warnings);
    }

    private void buildRoute(JsonNode route, int routeIndex, List<Expectation> expectations, List<ImportWarning> warnings) {
        String type = textOrNull(route, "type");
        String method = textOrNull(route, "method");
        String endpoint = textOrNull(route, "endpoint");
        String locator = "route " + (method != null ? method.toUpperCase(Locale.ROOT) : "?") + " /" + (endpoint != null ? endpoint : "");

        if (type != null && !"http".equalsIgnoreCase(type)) {
            // Mockoon 'crud' and websocket route types have no direct MockServer mock equivalent.
            warnings.add(new ImportWarning(locator, "route.type",
                "route type '" + type + "' is not mapped — only 'http' routes are importable"));
            return;
        }

        JsonNode responses = route.path("responses");
        if (!responses.isArray() || responses.isEmpty()) {
            warnings.add(new ImportWarning(locator, "route.responses",
                "route has no responses — skipped"));
            return;
        }

        String responseMode = textOrNull(route, "responseMode");
        List<JsonNode> responseList = new ArrayList<>();
        responses.forEach(responseList::add);

        if ("SEQUENTIAL".equalsIgnoreCase(responseMode) || "RANDOM".equalsIgnoreCase(responseMode)) {
            buildCyclingRoute(route, routeIndex, method, endpoint, locator, responseList, responseMode, expectations, warnings);
            return;
        }
        if ("DISABLE_RULES".equalsIgnoreCase(responseMode)) {
            warnings.add(new ImportWarning(locator, "route.responseMode",
                "DISABLE_RULES always serves the first/default response — response rules were not converted to matchers"));
            Expectation e = new Expectation(baseRequest(method, endpoint))
                .withId(routeId(route, routeIndex) + "-0")
                .thenRespond(mapResponse(responseList.get(0), locator, warnings));
            expectations.add(e);
            return;
        }
        if ("FALLBACK".equalsIgnoreCase(responseMode)) {
            warnings.add(new ImportWarning(locator, "route.responseMode",
                "FALLBACK mode proxies unmatched requests upstream — the proxy fallback is not reproduced; rule-based responses are still mapped"));
            // fall through to rules-based mapping below
        }

        // Default: rules-based selection. One expectation per response, descending priority so the
        // route's array order is honoured and the `default` response is the lowest-priority catch-all.
        int total = responseList.size();
        for (int i = 0; i < total; i++) {
            JsonNode responseNode = responseList.get(i);
            HttpRequest httpRequest = baseRequest(method, endpoint);
            boolean isDefault = responseNode.path("default").asBoolean(false);

            JsonNode rules = responseNode.path("rules");
            boolean rulesApplied = false;
            if (rules.isArray() && !rules.isEmpty()) {
                String rulesOperator = textOrNull(responseNode, "rulesOperator");
                if (rulesOperator != null && "OR".equalsIgnoreCase(rulesOperator) && rules.size() > 1) {
                    warnings.add(new ImportWarning(locator + " response[" + i + "]", "response.rulesOperator",
                        "OR-combined rules cannot be expressed as a single AND matcher — only the first rule was applied"));
                    rulesApplied = applyRule(httpRequest, rules.get(0), locator + " response[" + i + "]", warnings);
                } else {
                    for (JsonNode rule : rules) {
                        rulesApplied |= applyRule(httpRequest, rule, locator + " response[" + i + "]", warnings);
                    }
                }
            }

            // Priority: earlier array entries win; a rule-bearing response outranks the catch-all
            // default. Default/rule-less responses sit at priority 0.
            int priority = (rulesApplied && !isDefault) ? (total - i) : 0;

            Expectation expectation = new Expectation(httpRequest)
                .withId(routeId(route, routeIndex) + "-" + i)
                .withPriority(priority)
                .thenRespond(mapResponse(responseNode, locator + " response[" + i + "]", warnings));
            expectations.add(expectation);
        }
    }

    private void buildCyclingRoute(JsonNode route, int routeIndex, String method, String endpoint, String locator,
                                   List<JsonNode> responseList, String responseMode,
                                   List<Expectation> expectations, List<ImportWarning> warnings) {
        boolean anyRules = responseList.stream().anyMatch(r -> r.path("rules").isArray() && !r.path("rules").isEmpty());
        if (anyRules) {
            warnings.add(new ImportWarning(locator, "route.responseMode",
                responseMode.toUpperCase(Locale.ROOT) + " mode ignores per-response rules — the rules were not converted to matchers"));
        }
        List<HttpResponse> httpResponses = new ArrayList<>();
        for (int i = 0; i < responseList.size(); i++) {
            httpResponses.add(mapResponse(responseList.get(i), locator + " response[" + i + "]", warnings));
        }
        Expectation expectation = new Expectation(baseRequest(method, endpoint))
            .withId(routeId(route, routeIndex))
            .thenRespond(httpResponses)
            .withResponseMode("RANDOM".equalsIgnoreCase(responseMode) ? ResponseMode.RANDOM : ResponseMode.SEQUENTIAL);
        expectations.add(expectation);
    }

    private HttpRequest baseRequest(String method, String endpoint) {
        HttpRequest httpRequest = request();
        if (method != null && !"all".equalsIgnoreCase(method)) {
            httpRequest.withMethod(method.toUpperCase(Locale.ROOT));
        }
        httpRequest.withPath(convertEndpoint(endpoint));
        return httpRequest;
    }

    // A Mockoon/Express route token: a :param segment or a '*' catch-all wildcard.
    private static final java.util.regex.Pattern ENDPOINT_TOKEN =
        java.util.regex.Pattern.compile(":[A-Za-z0-9_]+|\\*");

    /**
     * Convert a Mockoon endpoint ({@code users/:id}, no leading slash) into a MockServer path.
     * {@code :param} segments become a single-path-segment regex ({@code [^/]+}) and a {@code *}
     * catch-all becomes {@code .*}, so the whole path is matched by MockServer's default path regex
     * matcher. Because the result is treated as a regex, the literal spans between the converted
     * tokens are regex-escaped — otherwise a literal like {@code file.txt} would have its {@code .}
     * match any character (so {@code /users/1/fileXtxt} would wrongly match {@code /users/:id/file.txt}).
     */
    private String convertEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return "/";
        }
        String path = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        StringBuilder regex = new StringBuilder();
        java.util.regex.Matcher matcher = ENDPOINT_TOKEN.matcher(path);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                regex.append(escapeRegexLiteral(path.substring(last, matcher.start())));
            }
            regex.append(matcher.group().startsWith(":") ? "[^/]+" : ".*");
            last = matcher.end();
        }
        if (last < path.length()) {
            regex.append(escapeRegexLiteral(path.substring(last)));
        }
        return regex.toString();
    }

    /**
     * Backslash-escape the regex metacharacters in a literal path span so it matches verbatim once
     * the whole endpoint is compiled as a regex. Non-metacharacters (including {@code /}) are left
     * untouched so a plain segment such as {@code /users/} stays readable.
     */
    private static String escapeRegexLiteral(String literal) {
        StringBuilder sb = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if ("\\.[]{}()*+?^$|".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private boolean applyRule(HttpRequest httpRequest, JsonNode rule, String locator, List<ImportWarning> warnings) {
        String target = textOrNull(rule, "target");
        String modifier = textOrNull(rule, "modifier");
        String value = textOrNull(rule, "value");
        String operator = textOrNull(rule, "operator");
        boolean invert = rule.path("invert").asBoolean(false);

        if (target == null) {
            return false;
        }
        if (operator != null && ("null".equalsIgnoreCase(operator) || "empty_array".equalsIgnoreCase(operator))) {
            warnings.add(new ImportWarning(locator, "response.rules.operator",
                "rule operator '" + operator + "' has no MockServer analogue — omitted"));
            return false;
        }

        NottableString matcher = buildRuleMatcher(operator, value, invert);

        switch (target.toLowerCase(Locale.ROOT)) {
            case "query":
                if (modifier != null) {
                    httpRequest.withQueryStringParameter(NottableString.string(modifier), matcher);
                    return true;
                }
                warnings.add(new ImportWarning(locator, "response.rules.query",
                    "query rule has no 'modifier' (parameter name) — the rule was dropped"));
                return false;
            case "header":
                if (modifier != null) {
                    httpRequest.withHeader(NottableString.string(modifier), matcher);
                    return true;
                }
                warnings.add(new ImportWarning(locator, "response.rules.header",
                    "header rule has no 'modifier' (header name) — the rule was dropped"));
                return false;
            case "body":
                if (modifier != null && !modifier.isEmpty()) {
                    // modifier is a JSONPath/key into the body — approximate with a JsonPath matcher
                    httpRequest.withBody(org.mockserver.model.JsonPathBody.jsonPath(
                        modifier.startsWith("$") ? modifier : "$." + modifier));
                    if (value != null && !value.isEmpty()) {
                        warnings.add(new ImportWarning(locator, "response.rules.body",
                            "body rule value '" + value + "' is not compared; only the JSONPath existence is matched"));
                    }
                    return true;
                }
                if (value != null) {
                    httpRequest.withBody(org.mockserver.model.RegexBody.regex(
                        "regex".equalsIgnoreCase(operator) ? value : ".*" + java.util.regex.Pattern.quote(value) + ".*"));
                    return true;
                }
                return false;
            case "params":
                warnings.add(new ImportWarning(locator, "response.rules.params",
                    "path/route-parameter rules are not mapped; the parameter is already matched by the path segment"));
                return false;
            case "cookie":
                warnings.add(new ImportWarning(locator, "response.rules.cookie",
                    "cookie rules are not mapped — add an explicit cookie matcher if needed"));
                return false;
            case "method":
            case "path":
                warnings.add(new ImportWarning(locator, "response.rules." + target,
                    "'" + target + "' rules are not mapped (the route already fixes method/path)"));
                return false;
            default:
                warnings.add(new ImportWarning(locator, "response.rules.target",
                    "rule target '" + target + "' is not mapped — omitted"));
                return false;
        }
    }

    private NottableString buildRuleMatcher(String operator, String value, boolean invert) {
        String v = value != null ? value : "";
        String regex;
        if ("regex".equalsIgnoreCase(operator) || "regex_i".equalsIgnoreCase(operator)) {
            regex = "regex_i".equalsIgnoreCase(operator) ? "(?i)" + v : v;
        } else {
            // equals (default)
            regex = java.util.regex.Pattern.quote(v);
        }
        return invert ? NottableString.not(regex) : NottableString.string(regex);
    }

    private HttpResponse mapResponse(JsonNode responseNode, String locator, List<ImportWarning> warnings) {
        HttpResponse httpResponse = response();
        httpResponse.withStatusCode(responseNode.path("statusCode").asInt(200));

        JsonNode headers = responseNode.path("headers");
        if (headers.isArray()) {
            List<Header> headerList = new ArrayList<>();
            for (JsonNode header : headers) {
                String key = textOrNull(header, "key");
                String value = textOrNull(header, "value");
                if (key != null && value != null) {
                    headerList.add(Header.header(key, value));
                }
            }
            if (!headerList.isEmpty()) {
                httpResponse.withHeaders(headerList);
            }
        }

        String body = textOrNull(responseNode, "body");
        if (body != null && !body.isEmpty()) {
            httpResponse.withBody(body);
        }

        JsonNode latency = responseNode.path("latency");
        if ((latency.isInt() || latency.isLong()) && latency.asLong() > 0) {
            httpResponse.withDelay(TimeUnit.MILLISECONDS, latency.asLong());
        }

        if (responseNode.path("bodyType").isTextual() && "FILE".equalsIgnoreCase(responseNode.path("bodyType").asText())) {
            warnings.add(new ImportWarning(locator, "response.filePath",
                "file-backed response bodies reference an external file that is not resolved on import; inline the body instead"));
        }
        if (responseNode.has("databucketID") && responseNode.path("databucketID").isTextual()
            && !responseNode.path("databucketID").asText().isEmpty()) {
            warnings.add(new ImportWarning(locator, "response.databucketID",
                "data-bucket references are not mapped; the response body is used verbatim"));
        }
        if (body != null && (body.contains("{{") || body.contains("}}"))) {
            warnings.add(new ImportWarning(locator, "response.body",
                "the body contains Mockoon/Handlebars templating ({{...}}) which is served verbatim — MockServer templating syntax differs"));
        }
        return httpResponse;
    }

    private String routeId(JsonNode route, int routeIndex) {
        String uuid = textOrNull(route, "uuid");
        if (uuid != null && !uuid.isEmpty()) {
            return "mockoon-" + uuid;
        }
        return "mockoon-route-" + routeIndex;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull() || !child.isValueNode()) {
            return null;
        }
        return child.asText();
    }
}
