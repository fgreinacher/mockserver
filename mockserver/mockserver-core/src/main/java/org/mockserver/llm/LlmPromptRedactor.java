package org.mockserver.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.imports.ImportRedaction;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts credentials out of the text of a prompt before it is sent to an external
 * LLM backend (the {@code /generateExpectation} stub-generation path and the
 * semantic drift enrichment path).
 * <p>
 * Redaction here is <b>always on</b> and independent of {@code redactSecretsInLog}:
 * a prompt leaves the process to a third-party service, so credentials in the
 * unmatched request or context expectations must never be included. It operates on
 * copies / strings only — the served request, the event log and the returned
 * expectations are never mutated.
 * <p>
 * Reuses {@link FixtureRedactor} for header, query-string and JSON-body-field
 * masking (and its {@link FixtureRedactor#REDACTED_PLACEHOLDER}); adds four checks
 * the fixture path does not need because a prompt is free text rather than a
 * structured expectation: JWT-shaped tokens anywhere in a body, credentials embedded
 * in URL userinfo ({@code scheme://user:pass@host}), {@code key=value} pairs in
 * non-JSON (form / plain-text) bodies, and credentials hidden inside a stringified
 * JSON value carried by a non-sensitive field (e.g. {@code {"data":"{\"password\":\"x\"}"}}).
 */
public class LlmPromptRedactor {

    /** Substituted for a JSON-looking body that cannot be parsed to locate its fields (fail closed). */
    public static final String BODY_OMITTED_UNPARSEABLE = "[body omitted: could not be parsed for redaction]";

    private static final int MAX_BODY_LENGTH = 2000;

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    // Sensitive request headers redacted for prompts on top of FixtureRedactor's
    // defaults. Kept here, not in FixtureRedactor, so other features' fixture
    // redaction is unchanged.
    private static final List<String> EXTRA_SENSITIVE_HEADERS = List.of(
        "X-Auth-Token", "X-Access-Token", "X-Amz-Security-Token", "X-Csrf-Token"
    );

    // A JWT is three base64url segments separated by dots. Anchoring on the "eyJ"
    // header prefix (base64url of the opening {" of a JSON header) matches real
    // tokens while avoiding false positives on ordinary dotted strings such as
    // version numbers or hostnames.
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");

    // scheme://user:pass@host (or scheme://user@host) — mask the whole userinfo.
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)([^/?#\\s@]+)@");

    private final FixtureRedactor fixtureRedactor;
    private final Set<String> sensitiveBodyFields;
    private final Pattern keyValueCredential;

    /**
     * @param additionalSensitiveHeaderName the configured data-plane API-key header
     *                                       name to redact in addition to the default
     *                                       set, or {@code null}/blank when none is configured
     */
    public LlmPromptRedactor(String additionalSensitiveHeaderName) {
        Set<String> headers = new LinkedHashSet<>(FixtureRedactor.defaultSensitiveHeaders());
        headers.addAll(EXTRA_SENSITIVE_HEADERS);
        if (additionalSensitiveHeaderName != null && !additionalSensitiveHeaderName.isBlank()) {
            headers.add(additionalSensitiveHeaderName);
        }
        this.sensitiveBodyFields = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        this.sensitiveBodyFields.addAll(ImportRedaction.DEFAULT_SENSITIVE_BODY_FIELDS);
        this.sensitiveBodyFields.add("id_token");
        this.sensitiveBodyFields.add("idToken");
        this.fixtureRedactor = new FixtureRedactor(headers, this.sensitiveBodyFields);
        this.keyValueCredential = compileKeyValuePattern(this.sensitiveBodyFields);
    }

    private static Pattern compileKeyValuePattern(Set<String> fields) {
        StringBuilder alternation = new StringBuilder();
        for (String field : fields) {
            if (alternation.length() > 0) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(field));
        }
        // Left boundary: the field must NOT be preceded by an ASCII letter or digit, so a
        // separated prefix (my_password=, x-api_key=, a.token=) still matches while an
        // unrelated word is not split. Right boundary: a trailing \b keeps the field a
        // whole word, so "tokenizer=on" (token + izer) is not treated as "token".
        return Pattern.compile("(?i)(?<![A-Za-z0-9])((?:" + alternation + ")\\b\\s*=\\s*)([^&\\s]+)");
    }

    /**
     * Redact sensitive headers and query-string parameters in a request, returning a
     * clone. The original is never mutated. The clone's body is not used by callers
     * (bodies are handled by {@link #redactBodyForPrompt}), so only the header/query
     * masking of the returned clone is relied upon.
     */
    public HttpRequest redactHeadersAndQuery(HttpRequest request) {
        RequestDefinition redacted = fixtureRedactor.redactRequestDefinition(request);
        return redacted instanceof HttpRequest ? (HttpRequest) redacted : request;
    }

    /**
     * Redact any credentials embedded in URL userinfo within a path / host / URL
     * string (e.g. an absolute-URI proxy path {@code http://user:pass@host/v1}).
     */
    public String maskUrlUserInfo(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return URL_USERINFO.matcher(value).replaceAll("$1" + Matcher.quoteReplacement(FixtureRedactor.REDACTED_PLACEHOLDER) + "@");
    }

    /**
     * Produce the request-body text to include in the prompt, redacted and truncated
     * to {@value #MAX_BODY_LENGTH} characters. Returns {@link Optional#empty()} when
     * there is no body to include (absent, empty, or binary — binary bodies are
     * omitted rather than sent).
     */
    public Optional<String> redactBodyForPrompt(HttpRequest request) {
        Body<?> body = request.getBody();
        if (body != null && body.getType() == Body.Type.BINARY) {
            return Optional.empty();
        }
        String bodyString = request.getBodyAsString();
        if (bodyString == null || bodyString.isEmpty()) {
            return Optional.empty();
        }
        boolean looksLikeJson = body != null && body.getType() == Body.Type.JSON;
        return Optional.of(truncate(redactBodyText(bodyString, looksLikeJson)));
    }

    /**
     * Redact a free-text body (JSON field values, JWTs, URL userinfo and, for non-JSON
     * text, {@code key=value} credential pairs). Used by the drift enrichment path,
     * which only ever holds string bodies. Does not truncate — the caller applies its
     * own body-length cap.
     *
     * @param body the body text (may be {@code null})
     * @return the redacted text, {@link #BODY_OMITTED_UNPARSEABLE} for a JSON-looking
     * but unparseable body, or {@code null} when {@code body} is null
     */
    public String redactText(String body) {
        if (body == null) {
            return null;
        }
        return redactBodyText(body, false);
    }

    private String redactBodyText(String body, boolean declaredJson) {
        String trimmed = body.trim();
        boolean looksLikeJson = declaredJson || trimmed.startsWith("{") || trimmed.startsWith("[");
        if (looksLikeJson) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(body);
                if (root != null && (root.isObject() || root.isArray())) {
                    redactJsonTree(root);
                    return finishText(OBJECT_MAPPER.writeValueAsString(root));
                }
                // a scalar JSON value carries no field — treat as plain text below
            } catch (Exception unparseableJson) {
                // Fail closed: a body that declares or looks like JSON but cannot be
                // parsed might hide a credential we cannot mask structurally, so drop it.
                return BODY_OMITTED_UNPARSEABLE;
            }
        }
        return finishText(maskKeyValue(body));
    }

    /**
     * Recursively mask credentials in a parsed JSON tree: the value of any field whose
     * name is credential-like (at any depth) becomes the placeholder; every string leaf
     * is additionally passed through {@link #redactStringLeaf} so a credential hidden in
     * a stringified-JSON value or in a {@code key=value} pair inside a string is masked.
     */
    private void redactJsonTree(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<String> names = object.fieldNames();
            List<String> stringLeaves = new ArrayList<>();
            List<String> redactWhole = new ArrayList<>();
            while (names.hasNext()) {
                String name = names.next();
                if (sensitiveBodyFields.contains(name)) {
                    redactWhole.add(name);
                } else {
                    JsonNode child = object.get(name);
                    if (child.isTextual()) {
                        stringLeaves.add(name);
                    } else {
                        redactJsonTree(child);
                    }
                }
            }
            for (String name : redactWhole) {
                object.put(name, FixtureRedactor.REDACTED_PLACEHOLDER);
            }
            for (String name : stringLeaves) {
                object.put(name, redactStringLeaf(object.get(name).asText()));
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual()) {
                    array.set(i, TextNode.valueOf(redactStringLeaf(child.asText())));
                } else {
                    redactJsonTree(child);
                }
            }
        }
    }

    /**
     * Mask credentials in a single JSON string leaf. If the leaf is itself a JSON
     * object/array (a stringified JSON payload), it is parsed and redacted recursively;
     * otherwise {@code key=value} credential pairs and JWTs within the string are masked.
     */
    private String redactStringLeaf(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode nested = OBJECT_MAPPER.readTree(value);
                if (nested != null && (nested.isObject() || nested.isArray())) {
                    redactJsonTree(nested);
                    return OBJECT_MAPPER.writeValueAsString(nested);
                }
            } catch (Exception notNestedJson) {
                // fall through: not a stringified JSON document, mask as plain text
            }
        }
        return maskJwts(maskKeyValue(value));
    }

    private String maskKeyValue(String value) {
        return keyValueCredential.matcher(value).replaceAll("$1" + Matcher.quoteReplacement(FixtureRedactor.REDACTED_PLACEHOLDER));
    }

    private String maskJwts(String value) {
        return JWT.matcher(value).replaceAll(Matcher.quoteReplacement(FixtureRedactor.REDACTED_PLACEHOLDER));
    }

    private String finishText(String value) {
        return maskUrlUserInfo(maskJwts(value));
    }

    /**
     * Mask a single drift-record value when its field name is credential-like or the
     * value is itself a JWT / carries URL userinfo — the drift prompt lists field
     * names with their expected/actual values verbatim.
     */
    public String maskDriftValue(String field, String value) {
        if (value == null) {
            return null;
        }
        if (field != null && sensitiveBodyFields.contains(field)) {
            return FixtureRedactor.REDACTED_PLACEHOLDER;
        }
        return finishText(value);
    }

    private static String truncate(String body) {
        if (body.length() > MAX_BODY_LENGTH) {
            return body.substring(0, MAX_BODY_LENGTH) + "...[truncated]";
        }
        return body;
    }
}
