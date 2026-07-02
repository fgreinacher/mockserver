package org.mockserver.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.Parameter.StyleEnum;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Decodes {@code style}/{@code explode}-serialised OpenAPI {@code array}/{@code object} parameters into
 * a JSON literal so they can be validated against the parameter {@code schema}, closing the
 * "full style/explode splitting is a deferred follow-up" gap noted in {@link OpenAPIRequestValidator}.
 * <p>
 * The decoder is deliberately <em>fail-open</em>: whenever a value cannot be soundly decoded (an
 * unsupported style/schema combination, a non-primitive array item / object property that cannot be
 * type-coerced, or an ambiguous reconstruction) it returns {@code json == null} so the caller skips the
 * schema check rather than risk false-positiving a valid request. It only ever produces a JSON literal
 * that reconstructs, type-aware, exactly what the client sent — so a request that was valid before this
 * decoding stays valid, and only a value the spec genuinely rejects (wrong element/property type, failed
 * array/object constraint) now fails.
 * <p>
 * Defaults follow the OpenAPI specification: query/cookie default to {@code form}; path/header default to
 * {@code simple}; {@code explode} defaults to {@code true} for {@code form} and {@code false} otherwise.
 */
@SuppressWarnings("rawtypes")
class OpenApiStyleParameterDeserializer {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    private static final Pattern NUMERIC = Pattern.compile("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    /**
     * The outcome of attempting to decode a styled array/object parameter.
     * <ul>
     *   <li>{@code present} — whether the parameter was supplied in any of its style-serialised forms
     *       (drives the {@code required} presence check).</li>
     *   <li>{@code json} — the decoded JSON literal to validate, or {@code null} to skip the schema
     *       check (fail-open) while still honouring {@code present}.</li>
     * </ul>
     */
    static final class Result {
        final boolean present;
        final String json;

        Result(boolean present, String json) {
            this.present = present;
            this.json = json;
        }
    }

    /**
     * Decodes an {@code array}/{@code object}-typed parameter. {@code primaryType} is the resolved schema
     * type ({@code "array"} or {@code "object"}).
     */
    static Result deserialize(Parameter parameter, String in, String name, Schema schema, String primaryType,
                             HttpRequest request, Map<String, String> pathValues) {
        StyleEnum style = effectiveStyle(parameter, in);
        boolean explode = effectiveExplode(parameter, style);
        if ("array".equals(primaryType)) {
            return deserializeArray(in, name, schema, style, explode, request, pathValues);
        }
        return deserializeObject(in, name, schema, style, explode, request, pathValues);
    }

    static StyleEnum effectiveStyle(Parameter parameter, String in) {
        StyleEnum declared = parameter != null ? parameter.getStyle() : null;
        if (declared != null) {
            return declared;
        }
        switch (in) {
            case "path":
            case "header":
                return StyleEnum.SIMPLE;
            case "query":
            case "cookie":
            default:
                return StyleEnum.FORM;
        }
    }

    static boolean effectiveExplode(Parameter parameter, StyleEnum style) {
        Boolean declared = parameter != null ? parameter.getExplode() : null;
        if (declared != null) {
            return declared;
        }
        // per the OpenAPI spec the default for explode is true only when style == form
        return style == StyleEnum.FORM;
    }

    // ---- array ----

    private static Result deserializeArray(String in, String name, Schema schema, StyleEnum style, boolean explode,
                                           HttpRequest request, Map<String, String> pathValues) {
        List<String> tokens = gatherArrayTokens(in, name, style, explode, request, pathValues);
        boolean present = tokens != null && !tokens.isEmpty();
        if (!present) {
            return new Result(false, null);
        }
        Schema items = schema.getItems();
        String itemType = primaryType(items);
        if (items == null || !isPrimitiveType(itemType)) {
            // non-primitive item schema cannot be soundly coerced from a flat token — present, skip check
            return new Result(true, null);
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append(coerceScalarToken(itemType, tokens.get(i)));
        }
        json.append("]");
        return new Result(true, json.toString());
    }

    private static List<String> gatherArrayTokens(String in, String name, StyleEnum style, boolean explode,
                                                  HttpRequest request, Map<String, String> pathValues) {
        switch (in) {
            case "query": {
                if (style == StyleEnum.DEEPOBJECT) {
                    return null; // deepObject is object-only
                }
                if (explode) {
                    // form/spaceDelimited/pipeDelimited exploded arrays repeat the parameter; each value is an element
                    return allQueryValues(request, name);
                }
                String raw = request.getFirstQueryStringParameter(name);
                if (!hasQueryStringParameter(request, name)) {
                    return null;
                }
                return splitByDelimiter(raw, queryDelimiter(style));
            }
            case "path": {
                String raw = pathValues.get(name);
                if (raw == null) {
                    return null;
                }
                return splitPathArray(raw, name, style, explode);
            }
            case "header": {
                if (!hasHeader(request, name)) {
                    return null;
                }
                return splitByDelimiter(firstHeader(request, name), ",");
            }
            case "cookie": {
                String raw = cookieValue(request, name);
                if (raw == null) {
                    return null;
                }
                return splitByDelimiter(raw, ",");
            }
            default:
                return null;
        }
    }

    private static List<String> splitPathArray(String raw, String name, StyleEnum style, boolean explode) {
        switch (style) {
            case LABEL: {
                String content = raw.startsWith(".") ? raw.substring(1) : raw;
                return splitByDelimiter(content, explode ? "\\." : ",");
            }
            case MATRIX: {
                String prefix = ";" + name + "=";
                if (!raw.startsWith(";")) {
                    return null;
                }
                String content = raw.startsWith(prefix) ? raw.substring(prefix.length()) : raw.substring(1);
                if (explode) {
                    // ;name=a;name=b -> split on the repeated ";name="
                    return splitByDelimiter(content, ";" + Pattern.quote(name) + "=");
                }
                return splitByDelimiter(content, ",");
            }
            case SIMPLE:
            default:
                // simple arrays are comma-delimited for both explode values
                return splitByDelimiter(raw, ",");
        }
    }

    private static String queryDelimiter(StyleEnum style) {
        switch (style) {
            case SPACEDELIMITED:
                return "\\s+";
            case PIPEDELIMITED:
                return "\\|";
            case FORM:
            default:
                return ",";
        }
    }

    // ---- object ----

    private static Result deserializeObject(String in, String name, Schema schema, StyleEnum style, boolean explode,
                                            HttpRequest request, Map<String, String> pathValues) {
        LinkedHashMap<String, String> pairs = gatherObjectPairs(in, name, schema, style, explode, request, pathValues);
        if (pairs == null || pairs.isEmpty()) {
            return new Result(false, null);
        }
        Map<String, Schema> properties = schema.getProperties();
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : pairs.entrySet()) {
            Schema propSchema = properties != null ? properties.get(entry.getKey()) : null;
            String propType = propSchema != null ? primaryType(propSchema) : null;
            if (propSchema != null && !isPrimitiveType(propType)) {
                // a declared non-primitive property cannot be soundly coerced from a flat token — present, skip
                return new Result(true, null);
            }
            if (!first) {
                json.append(",");
            }
            json.append(jsonString(entry.getKey())).append(":").append(coerceScalarToken(propType, entry.getValue()));
            first = false;
        }
        json.append("}");
        return new Result(true, json.toString());
    }

    private static LinkedHashMap<String, String> gatherObjectPairs(String in, String name, Schema schema, StyleEnum style,
                                                                   boolean explode, HttpRequest request, Map<String, String> pathValues) {
        switch (in) {
            case "query": {
                if (style == StyleEnum.DEEPOBJECT) {
                    return deepObjectPairs(request, name);
                }
                if (style == StyleEnum.FORM) {
                    if (explode) {
                        return formExplodedObjectPairs(request, schema);
                    }
                    if (!hasQueryStringParameter(request, name)) {
                        return null;
                    }
                    return alternating(splitByDelimiter(request.getFirstQueryStringParameter(name), ","));
                }
                return null; // spaceDelimited/pipeDelimited objects are not commonly used — fail open
            }
            case "path": {
                String raw = pathValues.get(name);
                if (raw == null) {
                    return null;
                }
                switch (style) {
                    case LABEL: {
                        String content = raw.startsWith(".") ? raw.substring(1) : raw;
                        return explode ? explodedPairs(splitByDelimiter(content, "\\.")) : alternating(splitByDelimiter(content, ","));
                    }
                    case MATRIX: {
                        String prefix = ";" + name + "=";
                        String content = raw.startsWith(prefix) ? raw.substring(prefix.length()) : (raw.startsWith(";") ? raw.substring(1) : raw);
                        return explode ? null : alternating(splitByDelimiter(content, ","));
                    }
                    case SIMPLE:
                    default:
                        return explode ? explodedPairs(splitByDelimiter(raw, ",")) : alternating(splitByDelimiter(raw, ","));
                }
            }
            case "header": {
                if (!hasHeader(request, name)) {
                    return null;
                }
                String raw = firstHeader(request, name);
                return explode ? explodedPairs(splitByDelimiter(raw, ",")) : alternating(splitByDelimiter(raw, ","));
            }
            default:
                return null; // cookie objects are rare — fail open
        }
    }

    private static LinkedHashMap<String, String> deepObjectPairs(HttpRequest request, String name) {
        Pattern pattern = Pattern.compile("^" + Pattern.quote(name) + "\\[([^\\]]+)]$");
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        if (request.getQueryStringParameterList() != null) {
            for (org.mockserver.model.Parameter parameter : request.getQueryStringParameterList()) {
                if (parameter.getName() == null || parameter.getName().getValue() == null) {
                    continue;
                }
                Matcher matcher = pattern.matcher(parameter.getName().getValue());
                if (matcher.matches() && !parameter.getValues().isEmpty()) {
                    pairs.put(matcher.group(1), parameter.getValues().get(0).getValue());
                }
            }
        }
        return pairs.isEmpty() ? null : pairs;
    }

    private static LinkedHashMap<String, String> formExplodedObjectPairs(HttpRequest request, Schema schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return null; // without declared properties a form-exploded object is indistinguishable from scalars
        }
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        for (String property : properties.keySet()) {
            if (hasQueryStringParameter(request, property)) {
                pairs.put(property, request.getFirstQueryStringParameter(property));
            }
        }
        return pairs.isEmpty() ? null : pairs;
    }

    /** Reconstructs an object from a flat list of alternating {@code key,value,key,value} tokens. */
    private static LinkedHashMap<String, String> alternating(List<String> tokens) {
        if (tokens == null || tokens.isEmpty() || tokens.size() % 2 != 0) {
            return null;
        }
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i += 2) {
            pairs.put(tokens.get(i), tokens.get(i + 1));
        }
        return pairs;
    }

    /** Reconstructs an object from exploded {@code key=value} tokens. */
    private static LinkedHashMap<String, String> explodedPairs(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        for (String token : tokens) {
            int equalsIndex = token.indexOf('=');
            if (equalsIndex <= 0) {
                return null;
            }
            pairs.put(token.substring(0, equalsIndex), token.substring(equalsIndex + 1));
        }
        return pairs;
    }

    // ---- shared coercion ----

    /**
     * Coerces a single string token into the JSON literal its scalar schema type expects, mirroring the
     * primitive handling in {@link OpenAPIRequestValidator}: a numeric/boolean token that parses as its
     * declared type is emitted verbatim (so a mismatch such as {@code "abc"} for an integer is emitted as
     * a JSON string and correctly fails type validation); everything else is emitted as a JSON string.
     */
    static String coerceScalarToken(String type, String token) {
        if (("integer".equals(type) || "number".equals(type)) && NUMERIC.matcher(token).matches()) {
            return token;
        }
        if ("boolean".equals(type) && ("true".equals(token) || "false".equals(token))) {
            return token;
        }
        return jsonString(token);
    }

    private static String jsonString(String value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            // a plain string never fails Jackson serialisation; fall back to a manual quote just in case
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    static boolean isPrimitiveType(String type) {
        return "string".equals(type) || "integer".equals(type) || "number".equals(type) || "boolean".equals(type);
    }

    static String primaryType(Schema schema) {
        if (schema == null) {
            return null;
        }
        if (schema.getType() != null) {
            return schema.getType();
        }
        Set<String> types = schema.getTypes();
        if (types != null) {
            for (String type : types) {
                if (!"null".equals(type)) {
                    return type;
                }
            }
        }
        return null;
    }

    private static List<String> splitByDelimiter(String value, String regex) {
        if (value == null) {
            return null;
        }
        // split(-1) keeps trailing empty tokens so alternating key/value reconstruction stays aligned
        return new ArrayList<>(Arrays.asList(value.split(regex, -1)));
    }

    private static List<String> allQueryValues(HttpRequest request, String name) {
        List<String> values = new ArrayList<>();
        if (request.getQueryStringParameterList() != null) {
            for (org.mockserver.model.Parameter parameter : request.getQueryStringParameterList()) {
                if (parameter.getName() != null && name.equals(parameter.getName().getValue())) {
                    for (org.mockserver.model.NottableString value : parameter.getValues()) {
                        values.add(value.getValue());
                    }
                }
            }
        }
        return values;
    }

    private static boolean hasQueryStringParameter(HttpRequest request, String name) {
        if (request.getQueryStringParameterList() == null) {
            return false;
        }
        return request.getQueryStringParameterList().stream()
            .anyMatch(parameter -> parameter.getName() != null && name.equals(parameter.getName().getValue()));
    }

    private static boolean hasHeader(HttpRequest request, String name) {
        if (request.getHeaderList() == null) {
            return false;
        }
        return request.getHeaderList().stream()
            .anyMatch(header -> header.getName() != null && name.equalsIgnoreCase(header.getName().getValue()));
    }

    private static String firstHeader(HttpRequest request, String name) {
        String value = request.getFirstHeader(name);
        return isNotBlank(value) || value != null ? value : null;
    }

    private static String cookieValue(HttpRequest request, String name) {
        if (request.getCookieList() == null) {
            return null;
        }
        for (org.mockserver.model.Cookie cookie : request.getCookieList()) {
            if (cookie.getName() != null && name.equals(cookie.getName().getValue())) {
                return cookie.getValue() != null ? cookie.getValue().getValue() : "";
            }
        }
        return null;
    }
}
