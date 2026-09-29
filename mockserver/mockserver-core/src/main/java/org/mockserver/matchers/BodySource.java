package org.mockserver.matchers;

import org.mockserver.model.HttpRequest;

import java.util.function.Supplier;

/**
 * Minimal abstraction over the pieces of an HTTP message that the shared body-dispatch logic in
 * {@link BodyMatching} needs to match an actual message body against a template body matcher.
 * <p>
 * Both {@link org.mockserver.model.HttpRequest} (request matching, via
 * {@link HttpRequestPropertiesMatcher}) and {@link org.mockserver.model.HttpResponse} (response
 * matching, via {@link HttpResponseMatcher}) are adapted to this interface so a single
 * dispatch implementation drives both — giving the response body matcher the same JSON/XML/form
 * conversion, optional-body short-circuit, multipart decoding and binary original/compressed
 * dual-match the request body matcher already has.
 * <p>
 * The methods mirror the existing message accessors exactly:
 * <ul>
 *   <li>{@link #getBodyAsString()} — the decoded body as a string, or {@code null} when absent.</li>
 *   <li>{@link #getBodyAsRawBytes()} — the decoded raw body bytes (never {@code null}; empty array
 *       when absent).</li>
 *   <li>{@link #getOriginalBody()} — the on-the-wire (e.g. still-compressed) bytes when the message
 *       arrived compressed and was decompressed, otherwise {@code null}.</li>
 *   <li>{@link #getFirstHeader(String)} — the first value of a header (the {@code Content-Type}
 *       header carries the multipart boundary and selects the JSON/XML/form conversion);
 *       {@code ""} when absent.</li>
 *   <li>{@link #getOrComputeConvertedBody(Supplier)} — memoises the expensive XML/form-to-JSON
 *       conversion per message so the parse runs once per message rather than once per candidate
 *       matcher. A message without a cache simply computes on every call.</li>
 * </ul>
 */
public interface BodySource {

    String getBodyAsString();

    byte[] getBodyAsRawBytes();

    byte[] getOriginalBody();

    String getFirstHeader(String name);

    /**
     * Returns the memoised result of the (potentially expensive) XML/form-to-JSON conversion,
     * computing and caching it on first call when the underlying message supports caching.
     * Messages without a cache (e.g. responses) invoke the supplier on every call — the supplier
     * is a pure function of the immutable body so the result is identical either way.
     */
    String getOrComputeConvertedBody(Supplier<String> supplier);

    /**
     * The originating {@link HttpRequest} when this source wraps a request, otherwise {@code null}.
     * Used solely so a JSON-conversion failure log on the request path reports the actual request
     * exactly as before; the response path has no request to report and returns {@code null}.
     */
    default HttpRequest requestForLogging() {
        return null;
    }

    /**
     * The body parsed once for the candidate scan in progress on this thread, or {@code null} when no
     * scan is open (each matcher then parses for itself).
     */
    default ParsedBodyCache parsedBodyCache() {
        return null;
    }
}
