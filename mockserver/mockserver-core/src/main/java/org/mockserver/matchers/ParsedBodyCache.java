package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.spi.json.JsonProvider;
import org.mockserver.serialization.ObjectMapperFactory;

/**
 * A request body parsed once for one candidate scan and shared by that scan's JSON and JSONPath body
 * matchers, instead of each candidate re-parsing the same body.
 *
 * <p>It lives only as long as the scan: {@link org.mockserver.model.HttpRequest#openParsedBodyCache()}
 * opens the scope and {@link org.mockserver.model.HttpRequest#closeParsedBodyCache()} detaches and
 * empties the cache, so nothing parsed survives the scan (a per-thread cache used to pin the last body
 * and its tree on every matching thread for the life of the JVM). It is bound to the thread that
 * opened the scope; any other thread matching the same request parses for itself. Entries are keyed
 * on the body text, so a different body is simply parsed and replaces the entry.
 *
 * <p>Internal to request matching; not a supported client API.
 *
 * <p>A cached tree is handed to many matchers, so only readers may use it: a JSONPath that can write
 * to the document (see {@link JsonPathMatcher}) must parse its own copy.
 */
public final class ParsedBodyCache {

    /**
     * Stores nothing: every lookup parses. Used where no scan is open so there is one parse path.
     */
    static final ParsedBodyCache NONE = new ParsedBodyCache(null);

    private final Thread owner;
    private String jsonTreeKey;
    private JsonNode jsonTree;
    private String jsonPathDocumentKey;
    private Class<?> jsonPathDocumentProvider;
    private Object jsonPathDocument;
    private int parses;

    private ParsedBodyCache(Thread owner) {
        this.owner = owner;
    }

    /**
     * A cache owned by the calling thread.
     */
    public static ParsedBodyCache forCurrentThread() {
        return new ParsedBodyCache(Thread.currentThread());
    }

    @JsonIgnore
    public boolean isOwnedByCurrentThread() {
        return owner == Thread.currentThread();
    }

    /**
     * The Jackson tree for {@code matched}. The lookup dereferences {@code matched} first, so an absent
     * body fails with the same {@link NullPointerException} the JSON matcher has always reported for it.
     */
    JsonNode jsonTree(String matched) throws JsonProcessingException {
        if (matched.equals(jsonTreeKey)) {
            return jsonTree;
        }
        JsonNode tree = ObjectMapperFactory.createObjectMapper().readTree(matched);
        if (owner != null) {
            parses++;
            jsonTreeKey = matched;
            jsonTree = tree;
        }
        return tree;
    }

    /**
     * The document {@code provider} parses {@code matched} into, for a JSONPath read that does not
     * write to it.
     */
    Object jsonPathDocument(String matched, JsonProvider provider) {
        // keyed on the provider's class: the default configuration builds a new provider per call
        if (matched.equals(jsonPathDocumentKey) && provider.getClass() == jsonPathDocumentProvider) {
            return jsonPathDocument;
        }
        Object document = provider.parse(matched);
        if (owner != null) {
            parses++;
            jsonPathDocumentKey = matched;
            jsonPathDocumentProvider = provider.getClass();
            jsonPathDocument = document;
        }
        return document;
    }

    /**
     * The number of parses this cache has performed; visible for tests.
     */
    int parses() {
        return parses;
    }

    /**
     * Whether the cache holds no body; visible for tests.
     */
    boolean isEmpty() {
        return jsonTreeKey == null && jsonTree == null && jsonPathDocumentKey == null && jsonPathDocument == null && jsonPathDocumentProvider == null;
    }

    /**
     * Empties the cache.
     */
    public void clear() {
        jsonTreeKey = null;
        jsonTree = null;
        jsonPathDocumentKey = null;
        jsonPathDocumentProvider = null;
        jsonPathDocument = null;
    }
}
