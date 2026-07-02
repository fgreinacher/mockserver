package org.mockserver.load;

import org.mockserver.model.HttpResponse;
import org.mockserver.model.ObjectWithJsonToString;

import java.util.regex.Pattern;

/**
 * A per-step response assertion for a {@link LoadStep} (the load-injection equivalent of a k6
 * {@code check}). After a step's response returns, each check extracts a value from that response and
 * compares it against an expected {@link #value} with a {@link Comparator}. Every evaluated check is
 * recorded as a pass or a fail — surfacing correctness problems (e.g. a {@code 200} carrying the wrong
 * body) that latency/error metrics alone hide, because a load run only ever flags 5xx/connection
 * errors otherwise.
 *
 * <p>Checks are <b>observational</b>: a failing check never fails an individual request or stops
 * dispatch. Instead the pass/fail counts feed the {@code mock_server_load_checks} metric and the
 * end-of-run report, and can trip a {@link LoadThreshold.Metric#CHECK_FAILURE_RATE} threshold (which,
 * with {@code abortOnFail}, can abort the run and drive a non-zero CI exit code).
 *
 * <p>Extraction reuses the shared {@link LoadResponseExtractor} primitives (the same code
 * {@link LoadCapture} correlation uses). A default (no checks) leaves behaviour unchanged.
 *
 * <ul>
 *   <li>{@link #getSource()} — where to read the observed value from ({@code STATUS}, {@code HEADER},
 *       {@code BODY_JSONPATH})</li>
 *   <li>{@link #getHeaderName()} — the header name (required for {@code HEADER})</li>
 *   <li>{@link #getJsonPath()} — the JSONPath (required for {@code BODY_JSONPATH})</li>
 *   <li>{@link #getComparator()} — how the observed value is compared to {@link #getValue()}</li>
 *   <li>{@link #getValue()} — the expected value / comparand</li>
 * </ul>
 */
public class LoadCheck extends ObjectWithJsonToString {

    /** Where in the step's response a {@link LoadCheck} reads its observed value from. */
    public enum Source {
        /** The response status code, as a string (e.g. {@code "200"}). */
        STATUS,
        /** The first value of the response header named by {@link #getHeaderName()}. */
        HEADER,
        /** The JSONPath {@link #getJsonPath()} evaluated over the response body. */
        BODY_JSONPATH
    }

    /**
     * How the observed value is compared to the expected {@link #getValue()}. String comparators
     * ({@code EQUALS}/{@code NOT_EQUALS}/{@code CONTAINS}/{@code MATCHES}) operate on the raw extracted
     * string; numeric comparators ({@code GT}/{@code LT}/{@code GTE}/{@code LTE}) parse both sides as
     * doubles and fail the check when either side is not a number.
     */
    public enum Comparator {
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
        MATCHES,
        GT,
        LT,
        GTE,
        LTE;

        /**
         * @return true when {@code observed} satisfies this comparator against {@code expected}. Numeric
         * comparators return false when either operand is absent or not parseable as a double; MATCHES
         * treats {@code expected} as a full-match regex (via {@link Pattern#matches}).
         */
        public boolean matches(String observed, String expected) {
            switch (this) {
                case EQUALS:
                    return observed != null && observed.equals(expected);
                case NOT_EQUALS:
                    return observed == null ? expected != null : !observed.equals(expected);
                case CONTAINS:
                    return observed != null && expected != null && observed.contains(expected);
                case MATCHES:
                    if (observed == null || expected == null) {
                        return false;
                    }
                    try {
                        return Pattern.matches(expected, observed);
                    } catch (RuntimeException e) {
                        return false;
                    }
                case GT:
                case LT:
                case GTE:
                case LTE:
                    return compareNumeric(observed, expected);
                default:
                    return false;
            }
        }

        private boolean compareNumeric(String observed, String expected) {
            Double left = parseDouble(observed);
            Double right = parseDouble(expected);
            if (left == null || right == null) {
                return false;
            }
            int cmp = Double.compare(left, right);
            switch (this) {
                case GT:
                    return cmp > 0;
                case LT:
                    return cmp < 0;
                case GTE:
                    return cmp >= 0;
                case LTE:
                    return cmp <= 0;
                default:
                    return false;
            }
        }

        private static Double parseDouble(String value) {
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private Source source;
    private String headerName;
    private String jsonPath;
    private Comparator comparator;
    private String value;

    public static LoadCheck loadCheck() {
        return new LoadCheck();
    }

    public Source getSource() {
        return source;
    }

    public LoadCheck withSource(Source source) {
        this.source = source;
        return this;
    }

    public String getHeaderName() {
        return headerName;
    }

    public LoadCheck withHeaderName(String headerName) {
        this.headerName = headerName;
        return this;
    }

    public String getJsonPath() {
        return jsonPath;
    }

    public LoadCheck withJsonPath(String jsonPath) {
        this.jsonPath = jsonPath;
        return this;
    }

    public Comparator getComparator() {
        return comparator;
    }

    public LoadCheck withComparator(Comparator comparator) {
        this.comparator = comparator;
        return this;
    }

    public String getValue() {
        return value;
    }

    public LoadCheck withValue(String value) {
        this.value = value;
        return this;
    }

    /**
     * Extract the observed value from {@code response} per this check's {@link #source}, using the
     * shared {@link LoadResponseExtractor} primitives. Returns {@code null} when the source is unset or
     * nothing matches. May throw for a malformed JSONPath — callers evaluate best-effort.
     */
    public String extract(HttpResponse response) {
        if (source == null) {
            return null;
        }
        switch (source) {
            case STATUS:
                return LoadResponseExtractor.status(response);
            case HEADER:
                return LoadResponseExtractor.header(response, headerName);
            case BODY_JSONPATH:
                return LoadResponseExtractor.jsonPath(response, jsonPath);
            default:
                return null;
        }
    }

    /**
     * Evaluate this check against {@code response}: extract the observed value and compare it to
     * {@link #value} with the {@link #comparator}. A null comparator (nothing to assert) passes. May
     * throw for a malformed JSONPath/regex — the orchestrator runs this inside a {@code try/catch} and
     * treats a throw as a fail.
     */
    public boolean evaluate(HttpResponse response) {
        if (comparator == null) {
            return true;
        }
        return comparator.matches(extract(response), value);
    }

    /** True when this check is complete enough to evaluate (a source and comparator are set). */
    public boolean isValid() {
        return source != null && comparator != null;
    }
}
