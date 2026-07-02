package org.mockserver.matchers;

import org.mockserver.codec.JsonSchemaBodyDecoder;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.function.Supplier;

import static org.mockserver.matchers.MatchDifference.Field.BODY;
import static org.mockserver.model.NottableString.string;

/**
 * Shared body-matching dispatch used by BOTH request matching
 * ({@link HttpRequestPropertiesMatcher}) and response matching ({@link HttpResponseMatcher}).
 * <p>
 * This is the single source of truth for "given a compiled {@link BodyMatcher} and an actual
 * message body, does the body match?". It owns the full matcher dispatch the request matcher
 * historically had inline — and which the response matcher previously lacked:
 * <ul>
 *   <li><strong>optional-body short-circuit</strong> — an optional template body matches a message
 *       with no body at all;</li>
 *   <li><strong>multipart</strong> — a {@link MultipartMatcher} decodes the raw body bytes using the
 *       {@code Content-Type} boundary;</li>
 *   <li><strong>binary original/compressed dual-match</strong> — a {@link BinaryMatcher} accepts a
 *       match against either the decompressed bytes or the original (e.g. still-compressed) bytes;</li>
 *   <li><strong>JSON / JSON schema / JSON path</strong> — the actual body is first run through
 *       {@link JsonSchemaBodyDecoder#convertToJson(BodySource, BodyMatcher)} so an XML or form actual
 *       body is converted to JSON before matching, and an absent body is tolerated (a clean
 *       non-match, never an internal NPE);</li>
 *   <li><strong>XML / XML schema / GraphQL / JSON-RPC / string</strong> — matched against the body
 *       string.</li>
 * </ul>
 * <p>
 * The dispatch is parameterised over a {@link BodySource} (the small set of accessors the dispatch
 * needs) and an optional {@link MatchDifference} {@code context}. The request path passes a non-null
 * context so its diagnostics are unchanged; the response path may pass {@code null} (response
 * diagnostics are a later addition). The request-only control-plane {@code BodyDTO} re-parse fallback
 * is intentionally NOT part of this helper — it stays on the request path.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class BodyMatching {

    private BodyMatching() {
    }

    /**
     * Adapts an {@link HttpRequest} to the {@link BodySource} abstraction, exposing the request's
     * per-request XML→JSON cache ({@link HttpRequest#getOrComputeConvertedBody}) so the conversion
     * is memoised exactly as on the historical request path, and carrying the request itself for the
     * JSON-conversion failure log.
     */
    public static BodySource of(HttpRequest request) {
        return new BodySource() {
            @Override
            public String getBodyAsString() {
                return request.getBodyAsString();
            }

            @Override
            public byte[] getBodyAsRawBytes() {
                return request.getBodyAsRawBytes();
            }

            @Override
            public byte[] getOriginalBody() {
                return request.getOriginalBody();
            }

            @Override
            public String getFirstHeader(String name) {
                return request.getFirstHeader(name);
            }

            @Override
            public String getOrComputeConvertedBody(Supplier<String> supplier) {
                return request.getOrComputeConvertedBody(HttpRequest.ConvertedBodyType.XML_TO_JSON, supplier);
            }

            @Override
            public HttpRequest requestForLogging() {
                return request;
            }
        };
    }

    /**
     * Adapts an {@link HttpResponse} to the {@link BodySource} abstraction. A response carries no
     * compressed-original body and no per-message conversion cache, so {@link #getOriginalBody()}
     * is {@code null} (the binary matcher falls through to the single-representation branch, exactly
     * as for an uncompressed request) and {@code getOrComputeConvertedBody} simply computes — a
     * single response is matched by a single body matcher, so there is no repeated conversion to
     * memoise.
     */
    public static BodySource of(HttpResponse response) {
        return new BodySource() {
            @Override
            public String getBodyAsString() {
                return response.getBodyAsString();
            }

            @Override
            public byte[] getBodyAsRawBytes() {
                return response.getBodyAsRawBytes();
            }

            @Override
            public byte[] getOriginalBody() {
                return null;
            }

            @Override
            public String getFirstHeader(String name) {
                return response.getFirstHeader(name);
            }

            @Override
            public String getOrComputeConvertedBody(Supplier<String> supplier) {
                return supplier.get();
            }
        };
    }

    /**
     * @param bodyMatcher          the compiled body matcher (must be non-null)
     * @param templateBodyOptional the template body's {@code getOptional()} flag (nullable)
     * @param actual               the actual message body source
     * @param context              optional {@link MatchDifference} for diagnostics (nullable)
     * @param jsonSchemaBodyParser parser used to convert XML/form actual bodies to JSON for the JSON
     *                             family of matchers (must be non-null when the matcher is in that
     *                             family)
     * @param mockServerLogger     logger used only to record a JSON conversion failure on a non-null
     *                             {@code context}
     * @return {@code true} when the actual body matches the matcher
     */
    static boolean bodyMatches(
        BodyMatcher bodyMatcher,
        Boolean templateBodyOptional,
        BodySource actual,
        MatchDifference context,
        JsonSchemaBodyDecoder jsonSchemaBodyParser,
        MockServerLogger mockServerLogger
    ) {
        boolean bodyMatches;
        // An absent actual body is modelled exactly as the request path did: getBodyAsString() is
        // null iff the message has no body (an empty-but-present body returns "").
        boolean actualBodyAbsent = actual.getBodyAsString() == null;
        if (templateBodyOptional != null && templateBodyOptional && actualBodyAbsent) {
            bodyMatches = true;
        } else if (bodyMatcher instanceof AllOfBodyMatcher allOf) {
            // composite: every component body matcher must match the same body. Each component is
            // dispatched recursively through this same helper so it sees the body representation and
            // JSON decoder it needs. The whole conjunction honours the composite's own "not" flag.
            boolean allMatch = true;
            for (BodyMatcher child : allOf.getMatchers()) {
                if (child == null) {
                    continue;
                }
                // suppress per-component diagnostics when the conjunction is negated (a failing
                // component is the expected/passing case, so recording it would be misleading)
                boolean childMatches = bodyMatches(child, null, actual, allOf.not ? null : context, jsonSchemaBodyParser, mockServerLogger);
                if (!childMatches) {
                    allMatch = false;
                    break;
                }
            }
            bodyMatches = allOf.not != allMatch;
        } else if (bodyMatcher instanceof MultipartMatcher) {
            // multipart/form-data field-level matcher: needs both the raw body bytes and the
            // Content-Type header (which carries the boundary) to decode the parts
            bodyMatches = matches(context, bodyMatcher, new MultipartMatcher.MultipartInput(
                actual.getFirstHeader("Content-Type"),
                actual.getBodyAsRawBytes()
            ));
        } else if (bodyMatcher instanceof BinaryMatcher) {
            if (actual.getOriginalBody() != null) {
                // the message was compressed: a binary matcher may target either the decompressed
                // body or the original compressed bytes, so accept a match against either
                // representation
                bodyMatches = matches(null, bodyMatcher, actual.getBodyAsRawBytes())
                    || matches(null, bodyMatcher, actual.getOriginalBody());
                if (!bodyMatches && context != null) {
                    // record the difference against the primary (decompressed) representation
                    matches(context, bodyMatcher, actual.getBodyAsRawBytes());
                }
            } else {
                bodyMatches = matches(context, bodyMatcher, actual.getBodyAsRawBytes());
            }
        } else {
            if (bodyMatcher instanceof ExactStringMatcher ||
                bodyMatcher instanceof SubStringMatcher ||
                bodyMatcher instanceof RegexStringMatcher) {
                // string body matcher
                bodyMatches = matches(context, bodyMatcher, string(actual.getBodyAsString()));
            } else if (bodyMatcher instanceof XmlStringMatcher ||
                bodyMatcher instanceof XmlSchemaMatcher
            ) {
                // xml body matcher
                bodyMatches = matches(context, bodyMatcher, actual.getBodyAsString());
            } else if (bodyMatcher instanceof JsonRpcMatcher) {
                bodyMatches = matches(context, bodyMatcher, actual.getBodyAsString());
            } else if (bodyMatcher instanceof GraphQLMatcher) {
                bodyMatches = matches(context, bodyMatcher, actual.getBodyAsString());
            } else if (bodyMatcher instanceof JsonStringMatcher ||
                bodyMatcher instanceof JsonSchemaMatcher ||
                bodyMatcher instanceof JsonPathMatcher
            ) {
                // json body matcher — convert XML/form actual bodies to JSON first; convertToJson
                // tolerates an absent body (returns null) so the matcher sees a clean non-match
                // rather than throwing an internal NPE
                try {
                    bodyMatches = matches(context, bodyMatcher, jsonSchemaBodyParser.convertToJson(actual, bodyMatcher));
                } catch (IllegalArgumentException iae) {
                    if (context != null) {
                        context.addDifference(mockServerLogger, iae, iae.getMessage());
                    }
                    bodyMatches = matches(context, bodyMatcher, actual.getBodyAsString());
                }
            } else {
                bodyMatches = matches(context, bodyMatcher, actual.getBodyAsString());
            }
        }
        return bodyMatches;
    }

    private static <T> boolean matches(MatchDifference context, BodyMatcher matcher, T t) {
        if (context != null) {
            context.currentField(BODY);
        }
        if (matcher == null) {
            return true;
        }
        return matcher.matches(context, t);
    }
}
