package org.mockserver.load;

import org.mockserver.model.HttpResponse;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared response-extraction primitives used by both {@link LoadCapture} (cross-step correlation) and
 * {@link LoadCheck} (per-step response assertions). Factored out so the two features share ONE
 * extraction implementation (JSONPath / header / body-regex / status) rather than each carrying its
 * own copy.
 *
 * <p>Every method is null-safe and returns {@code null} on "nothing to extract" (null response, blank
 * expression, empty body, no match). Callers decide what a {@code null} means (a capture falls back to
 * its default; a check treats it as the observed value for its comparator). A malformed JSONPath or
 * regex may still throw — the caller is expected to run these best-effort inside a {@code try/catch} so
 * a bad expression never breaks the load dispatch path.
 */
public final class LoadResponseExtractor {

    private LoadResponseExtractor() {
    }

    /**
     * Evaluate {@code expression} as a Jayway JSONPath over the response body and render the result as
     * a plain string (see {@link #stringifyJsonPath(Object)}). Returns {@code null} when the response,
     * expression or body is blank.
     */
    public static String jsonPath(HttpResponse response, String expression) {
        if (response == null || isBlank(expression)) {
            return null;
        }
        String body = response.getBodyAsString();
        if (isBlank(body)) {
            return null;
        }
        Object result = com.jayway.jsonpath.JsonPath.compile(expression).read(body);
        return stringifyJsonPath(result);
    }

    /**
     * Return the first value of the named response header, or {@code null} when the response, name or
     * header value is blank.
     */
    public static String header(HttpResponse response, String name) {
        if (response == null || isBlank(name)) {
            return null;
        }
        String header = response.getFirstHeader(name);
        return isBlank(header) ? null : header;
    }

    /**
     * Match {@code pattern} as a regex over the response body string and return capture group 1, or
     * {@code null} when there is no match (or no group 1).
     */
    public static String regexGroup1(HttpResponse response, String pattern) {
        if (response == null || isBlank(pattern)) {
            return null;
        }
        String body = response.getBodyAsString();
        if (isBlank(body)) {
            return null;
        }
        Matcher matcher = Pattern.compile(pattern).matcher(body);
        if (matcher.find() && matcher.groupCount() >= 1) {
            return matcher.group(1);
        }
        return null;
    }

    /** Return the response status code as a string, or {@code null} when absent. */
    public static String status(HttpResponse response) {
        if (response == null || response.getStatusCode() == null) {
            return null;
        }
        return String.valueOf(response.getStatusCode());
    }

    /**
     * Render a JSONPath result as a plain string: a scalar becomes its {@code String.valueOf}; a
     * single-element collection (the common definite-path-returning-a-list case) is unwrapped to its
     * element; an empty collection is treated as no match. Mirrors {@code CaptureProcessor}.
     */
    public static String stringifyJsonPath(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof Collection) {
            Collection<?> collection = (Collection<?>) result;
            if (collection.isEmpty()) {
                return null;
            }
            if (collection.size() == 1) {
                Object only = collection.iterator().next();
                return only != null ? String.valueOf(only) : null;
            }
        }
        return String.valueOf(result);
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
