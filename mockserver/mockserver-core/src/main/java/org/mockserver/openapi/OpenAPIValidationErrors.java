package org.mockserver.openapi;

import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.slf4j.event.Level.WARN;

/**
 * Builds meaningful, bounded, single-line error strings for the OpenAPI validators
 * ({@link OpenAPIRequestValidator}, {@link OpenAPIResponseValidator}, {@link OpenApiContractTest},
 * {@link OpenApiTrafficValidator}).
 * <p>
 * The validators surface their error strings to the control-plane caller. Echoing a raw
 * {@code throwable.getMessage()} verbatim is unhelpful (it is {@code "null"} for exceptions with no
 * message, e.g. {@link NullPointerException}) and unsafe (untrusted exception text reaches the
 * caller unbounded). This helper instead:
 * <ul>
 *     <li>states <em>what</em> was being validated (the supplied context — request vs response, the
 *     operation/path+method, and which aspect such as body schema or status code);</li>
 *     <li>names the exception <em>type</em> (its simple class name) so the message is never
 *     {@code "...: null"};</li>
 *     <li>includes a <em>bounded</em>, single-line summary of the cause message (capped length,
 *     control characters and newlines collapsed) so an oversized or odd exception string cannot
 *     bloat the result; and</li>
 *     <li>logs the full throwable (with stack trace) at WARN via {@link MockServerLogger} so the
 *     detail is not lost.</li>
 * </ul>
 */
final class OpenAPIValidationErrors {

    /**
     * Maximum number of characters of the underlying cause message included in the returned summary.
     * Keeps a huge or malicious exception string from bloating the control-plane result.
     */
    static final int MAX_CAUSE_LENGTH = 280;

    private OpenAPIValidationErrors() {
    }

    /**
     * Builds a bounded, single-line error string for an unexpected throwable caught while validating,
     * and logs the full throwable at WARN.
     *
     * @param context human-readable description of what was being validated when the throwable was
     *                caught, e.g. {@code "OpenAPI request validation"} or
     *                {@code "validating response body against schema for operation listPets"}
     * @param throwable the caught throwable (full detail is logged; a bounded summary is returned)
     * @param logger    used to log the full throwable; may be {@code null} (then no logging occurs)
     * @return a meaningful, bounded, single-line error string — never ending in a bare {@code "null"}
     */
    static String unexpectedError(String context, Throwable throwable, MockServerLogger logger) {
        if (logger != null) {
            if (logger.isEnabledForInstance(WARN)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat("exception while " + context)
                        .setThrowable(throwable)
                );
            }
        }
        return context + " failed: " + describe(throwable);
    }

    /**
     * Produces a bounded, single-line "{ExceptionType}: {bounded cause message}" description of a
     * throwable. When the throwable has no message, only its simple class name is used (so the result
     * is never {@code "null"}).
     */
    static String describe(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String type = throwable.getClass().getSimpleName();
        if (isBlank(type)) {
            // anonymous throwable subclasses have an empty simple name — fall back to the full name
            type = throwable.getClass().getName();
        }
        String message = throwable.getMessage();
        if (isBlank(message)) {
            return type;
        }
        return type + ": " + boundedSingleLine(message);
    }

    /**
     * Collapses any run of whitespace (including newlines/tabs) and other control characters to a
     * single space, trims, and truncates to {@link #MAX_CAUSE_LENGTH} characters with a trailing
     * ellipsis. Guarantees a single-line, length-bounded string.
     */
    static String boundedSingleLine(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (collapsed.length() > MAX_CAUSE_LENGTH) {
            return collapsed.substring(0, MAX_CAUSE_LENGTH) + "...";
        }
        return collapsed;
    }
}
