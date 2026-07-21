package org.mockserver.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Redaction for credentials that live <em>inside</em> a structured configuration value rather than
 * being the whole value.
 *
 * <p>Whole-value credentials are masked by property name — see
 * {@link ConfigurationProperties#isSensitivePropertyName(String)}. That name-keyed mask cannot reach a
 * secret nested in a structured value, of which there are two shapes:
 * <ul>
 *   <li>a {@code k=v,k2=v2} header list ({@code prometheusRemoteWriteHeaders}), which typically carries
 *       an {@code Authorization} or {@code Api-Key} header;</li>
 *   <li>a JSON document ({@code llmBackendsConfig}, whose documented shape is a <strong>path</strong> to
 *       a backends JSON file — see {@code LlmBackendResolver#namedBackends()} — so in its documented
 *       shape it holds no secret at all; a value that is itself a JSON document is nevertheless redacted
 *       per-field as defence-in-depth).</li>
 * </ul>
 *
 * <p>This is applied by <strong>every</strong> surface that discloses a configuration value —
 * {@code GET /mockserver/configuration} (via {@code ConfigurationDTO}), {@code GET /mockserver/config}
 * and {@code --print-config} (via {@link ConfigurationProperties#effectiveConfiguration()}), and the
 * startup property-file log dump — all routed through
 * {@link ConfigurationProperties#redactSensitiveValue(String, String)} so there is ONE notion of what a
 * credential is rather than one per endpoint.
 *
 * <p>Two properties are preserved throughout:
 * <ol>
 *   <li><strong>Byte-identical when there is nothing to redact.</strong> Every method returns the
 *       argument itself unless a secret was actually found, so a header list or a file path is
 *       unaffected — masking must not reformat ordinary configuration.</li>
 *   <li><strong>A masked value is never written back.</strong> {@code restore*} rebuilds the real
 *       value for each masked field from the value already held by the target, so a {@code GET}-then-
 *       {@code PUT} round trip cannot destroy a working credential — the same guarantee whole-value
 *       masking gives, applied per field. Where no prior value exists the masked field is dropped
 *       rather than written, so the literal mask can never become a credential.</li>
 * </ol>
 *
 * <p>Unrecognised input fails closed: a sensitively-named JSON field is masked whatever its type
 * (string, number, array, object), and an unparseable JSON <em>document</em> is masked whole. Nothing
 * here throws.
 *
 * <h2>Known limits of the name-keyed approach</h2>
 * <ul>
 *   <li><strong>A credential inline in a URL is not redacted.</strong> This masks by <em>name</em>
 *       (header name, JSON field name), so a secret carried inside an otherwise non-credential value —
 *       classically {@code "baseUrl":"https://user:p455w0rd@host/v1"} — is returned in clear. Any new
 *       property embedding a secret in a third shape needs its own per-shape treatment; the guard test
 *       is name-keyed and will not catch it automatically.</li>
 *   <li><strong>A header value containing a comma cannot be represented</strong> by the
 *       {@code k=v,k2=v2} format (there is no escape), and the consumer
 *       ({@code PrometheusRemoteWriteExporter#parseHeaders}) drops the tail after the comma. An entry
 *       with no {@code =} is therefore read here as the tail of the preceding value and masked with it,
 *       which is the fail-closed reading but means such an entry is not shown separately.</li>
 * </ul>
 *
 * <p>A plain {@link ObjectMapper} is used deliberately — this walks an anonymous JSON tree and must not
 * depend on the MockServer serializer graph, which itself binds {@code ConfigurationDTO} and would
 * invert the module layering of this package.
 */
final class EmbeddedCredentialRedaction {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MASK = ConfigurationProperties.REDACTED_VALUE;

    private EmbeddedCredentialRedaction() {
    }

    /**
     * Report a masked value that could not be resolved against the value held, and return the
     * "leave the held value untouched" signal. Failing closed is right, but failing SILENTLY is not:
     * the control plane answers {@code 200 OK} for the enclosing {@code PUT}, so without this the
     * operator believes a value was written that was in fact discarded.
     *
     * <p>The value itself is never logged — it carries the very credential this class exists to keep
     * out of readable output. The logger is resolved on demand rather than held in a static field:
     * this class is initialised during {@code ConfigurationProperties.<clinit>} (the startup
     * property-file dump redacts through it), and a static logger would drag logging initialisation
     * into that window — the #2338 failure shape.
     */
    private static String dropUnmergeableValue(String propertyName, String reason) {
        LoggerFactory.getLogger(EmbeddedCredentialRedaction.class).warn(
            "ignoring the value supplied for {}: it carries the {} redaction mask but {}, so it cannot be "
                + "resolved against the value currently held — the existing value is left unchanged",
            propertyName, MASK, reason);
        return null;
    }

    /**
     * True when a header name or JSON field name indicates its value is a credential.
     *
     * <p>Reuses {@link ConfigurationProperties#isSensitivePropertyName(String)} — the same
     * substring/{@code *key}-suffix shape rule the property store uses — after normalising away
     * {@code -} and {@code _} so {@code Api-Key} and {@code api_key} reach the {@code apikey} rule.
     * {@code Authorization} and {@code Cookie} are added because they are credential-bearing header
     * names that match none of those substrings.
     */
    static boolean isSensitiveName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalised = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return normalised.contains("authorization")
            || normalised.contains("cookie")
            || ConfigurationProperties.isSensitivePropertyName(normalised);
    }

    // ---------------------------------------------------------------------------------------------
    // k=v,k2=v2 header list
    // ---------------------------------------------------------------------------------------------

    /**
     * Redact the value of every credential-bearing header, leaving every other header — and the
     * ordering, spacing and any malformed entries — exactly as supplied.
     */
    static String redactHeaderList(String value) {
        if (value == null || value.isEmpty() || value.indexOf('=') < 0) {
            return value;
        }
        List<String> entries = headerEntries(value);
        boolean redacted = false;
        for (int i = 0; i < entries.size(); i++) {
            int equals = entries.get(i).indexOf('=');
            if (equals <= 0) {
                // no '=' or an empty name — malformed, and skipped by the exporter's parser too
                continue;
            }
            String name = entries.get(i).substring(0, equals);
            String headerValue = entries.get(i).substring(equals + 1);
            if (isSensitiveName(name.trim()) && !headerValue.trim().isEmpty()) {
                entries.set(i, name + "=" + MASK);
                redacted = true;
            }
        }
        return redacted ? String.join(",", entries) : value;
    }

    /**
     * Resolve an incoming header list against the one already held, replacing each masked header
     * value with the real value previously held under that header name.
     *
     * @return the header list to write, or {@code null} to leave the existing value untouched
     */
    static String restoreHeaderList(String propertyName, String incoming, String existing) {
        if (incoming == null || incoming.isEmpty() || !incoming.contains(MASK)) {
            return incoming;
        }
        if (MASK.equals(incoming.trim())) {
            // the whole value masked — treated exactly as a whole-value credential is
            return dropUnmergeableValue(propertyName, "the entire value is masked");
        }
        if (incoming.equals(redactHeaderList(existing))) {
            // an unedited GET-then-PUT: keep the held value verbatim, including its formatting
            return existing;
        }
        Map<String, String> held = parseHeaderList(existing);
        List<String> restored = new ArrayList<>();
        for (String entry : headerEntries(incoming)) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                restored.add(entry);
                continue;
            }
            String name = entry.substring(0, equals);
            String value = entry.substring(equals + 1);
            if (!MASK.equals(value.trim())) {
                restored.add(entry);
                continue;
            }
            String heldValue = held.get(name.trim());
            if (heldValue != null) {
                restored.add(name + "=" + heldValue);
            }
            // else: nothing held under this name, so drop the header rather than send the mask as a
            // credential — the same "a mask is never a value" rule whole-value masking applies
        }
        String merged = String.join(",", restored);
        if (merged.isEmpty()) {
            // nothing survived: writing "" would PIN an empty value on the Configuration instance,
            // which its getter then prefers over the static store, silently suppressing a
            // property-file or environment value. Leave the held value untouched instead — the same
            // contract restoreJsonDocument has.
            return dropUnmergeableValue(propertyName, "no header in it could be resolved");
        }
        return merged;
    }

    /**
     * Split a {@code k=v,k2=v2} list into entries, re-attaching any {@code =}-less entry to the one
     * before it. The format cannot escape a comma, so such an entry is the tail of the preceding
     * value; keeping the two together is what lets a comma-containing secret be masked whole instead
     * of leaking its tail.
     */
    private static List<String> headerEntries(String value) {
        List<String> entries = new ArrayList<>();
        for (String entry : value.split(",", -1)) {
            int last = entries.size() - 1;
            if (entry.indexOf('=') <= 0 && last >= 0 && entries.get(last).indexOf('=') > 0) {
                entries.set(last, entries.get(last) + "," + entry);
            } else {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Header name (trimmed) to raw, untrimmed header value, preserving entry order. A repeated header
     * name resolves LAST-wins, mirroring the consumer
     * ({@code PrometheusRemoteWriteExporter#parseHeaders}) so the value restored here is the one that
     * would actually have been sent.
     */
    private static Map<String, String> parseHeaderList(String value) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) {
            return parsed;
        }
        for (String entry : headerEntries(value)) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = entry.substring(0, equals).trim();
            if (!name.isEmpty()) {
                parsed.put(name, entry.substring(equals + 1));
            }
        }
        return parsed;
    }

    // ---------------------------------------------------------------------------------------------
    // JSON document (or, in its documented shape, a file path)
    // ---------------------------------------------------------------------------------------------

    /**
     * Redact every credential-bearing field of a JSON document, at any depth and whatever the field's
     * type. A value that is not a JSON document — the documented shape of {@code llmBackendsConfig} is
     * a file path — is returned unchanged; an unparseable document is redacted whole rather than
     * disclosed.
     */
    static String redactJsonDocument(String value) {
        if (!looksLikeJsonDocument(value)) {
            return value;
        }
        JsonNode root = parseOrNull(value);
        if (root == null) {
            return MASK;
        }
        if (!redactSecretFields(root)) {
            return value;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception writeFailure) {
            return MASK;
        }
    }

    /**
     * Resolve an incoming JSON document against the one already held, replacing each masked field
     * with the real value previously held at the same place in the document.
     *
     * <p>Array elements are matched by their {@code name} field when both sides carry one (so
     * reordering or inserting a backend cannot transplant one backend's key onto another), and by
     * index otherwise.
     *
     * @return the document to write, or {@code null} to leave the existing value untouched
     */
    static String restoreJsonDocument(String propertyName, String incoming, String existing) {
        if (incoming == null || incoming.isEmpty() || !incoming.contains(MASK)) {
            return incoming;
        }
        if (MASK.equals(incoming.trim())) {
            // the whole value masked (an unparseable document, or a masked GET of one)
            return dropUnmergeableValue(propertyName, "the entire value is masked");
        }
        if (!looksLikeJsonDocument(incoming)) {
            // not a document, so nothing was ever masked inside it — the token is part of the value
            return incoming;
        }
        if (incoming.equals(redactJsonDocument(existing))) {
            // an unedited GET-then-PUT: keep the held document verbatim, including its formatting
            return existing;
        }
        JsonNode incomingRoot = parseOrNull(incoming);
        if (incomingRoot == null) {
            // carries a mask but cannot be merged — writing it would make "***REDACTED***" the key
            return dropUnmergeableValue(propertyName, "it is not a parseable JSON document");
        }
        restoreSecretFields(incomingRoot, parseOrNull(existing));
        try {
            return OBJECT_MAPPER.writeValueAsString(incomingRoot);
        } catch (Exception writeFailure) {
            return dropUnmergeableValue(propertyName, "the merged document could not be written");
        }
    }

    private static boolean looksLikeJsonDocument(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("[") || trimmed.startsWith("{");
    }

    private static JsonNode parseOrNull(String value) {
        if (!looksLikeJsonDocument(value)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(value.trim());
        } catch (Exception parseFailure) {
            return null;
        }
    }

    /**
     * Whether a sensitively-named field actually carries something worth masking. Anything other than
     * absent/null/empty counts — a secret is not always a string, and an array of tokens or a numeric
     * key must be masked whole rather than recursed past.
     */
    private static boolean isRedactableValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        if (node.isContainerNode()) {
            return node.size() > 0;
        }
        return true;
    }

    private static boolean redactSecretFields(JsonNode node) {
        boolean redacted = false;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (String name : fieldNames(object)) {
                JsonNode child = object.get(name);
                if (isSensitiveName(name)) {
                    // mask the whole subtree: never recurse past a credential-named field, or an
                    // array/object/number secret would pass through unmasked
                    if (isRedactableValue(child)) {
                        object.put(name, MASK);
                        redacted = true;
                    }
                } else {
                    redacted |= redactSecretFields(child);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                redacted |= redactSecretFields(child);
            }
        }
        return redacted;
    }

    private static void restoreSecretFields(JsonNode incoming, JsonNode held) {
        if (incoming.isObject()) {
            ObjectNode object = (ObjectNode) incoming;
            for (String name : fieldNames(object)) {
                JsonNode child = object.get(name);
                JsonNode heldChild = held != null && held.isObject() ? held.get(name) : null;
                if (isSensitiveName(name)) {
                    if (child.isTextual() && MASK.equals(child.asText())) {
                        if (isRedactableValue(heldChild)) {
                            // whatever its type — the mask stands in for the whole subtree
                            object.set(name, heldChild.deepCopy());
                        } else {
                            object.remove(name);
                        }
                    }
                    // else: a real value supplied by the client — written as given, not recursed into
                } else {
                    restoreSecretFields(child, heldChild);
                }
            }
        } else if (incoming.isArray()) {
            ArrayNode array = (ArrayNode) incoming;
            for (int index = 0; index < array.size(); index++) {
                restoreSecretFields(array.get(index), heldElement(array.get(index), held, index));
            }
        }
    }

    private static JsonNode heldElement(JsonNode element, JsonNode heldArray, int index) {
        if (heldArray == null || !heldArray.isArray()) {
            return null;
        }
        JsonNode name = element.isObject() ? element.get("name") : null;
        if (name != null && name.isTextual()) {
            for (JsonNode candidate : heldArray) {
                JsonNode candidateName = candidate.isObject() ? candidate.get("name") : null;
                if (candidateName != null && candidateName.isTextual()
                    && candidateName.asText().equals(name.asText())) {
                    return candidate;
                }
            }
            // a named entry with no counterpart is new, so it has no prior secret to restore
            return null;
        }
        return index < heldArray.size() ? heldArray.get(index) : null;
    }

    private static List<String> fieldNames(ObjectNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
