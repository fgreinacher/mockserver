package org.mockserver.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.ToolUse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockserver.model.HttpResponse.response;

/**
 * Process-wide server-side state for the OpenAI Responses API
 * ({@code POST /v1/responses}). The Responses API is stateful in a way the Chat
 * Completions and Anthropic Messages APIs are not: a client may send only the new
 * turn's input plus a {@code previous_response_id}, and the server reconstructs the
 * full conversation from the stored prior turn. This registry models that so agents
 * that chain turns via {@code previous_response_id} can run against the mock.
 *
 * <p>Three behaviours, all opt-in-by-data and back-compatible (a request with no
 * {@code previous_response_id} and the default {@code store:true} behaves exactly as
 * before, only additionally recording the issued response for later chaining/retrieval):
 * <ul>
 *   <li><b>Chaining</b> — {@code OpenAiResponsesCodec.decode} prepends the stored prior
 *       conversation when the inbound request carries a {@code previous_response_id}, so
 *       conversation matchers and usage inference see the full dialogue.</li>
 *   <li><b>Store flag</b> — honours the {@code store} field (default {@code true}); a
 *       {@code store:false} response is neither retrievable nor chainable.</li>
 *   <li><b>Retrieval</b> — {@code GET /v1/responses/{id}} returns the stored response
 *       body verbatim.</li>
 * </ul>
 *
 * <p>Bounded (LRU) so a long-running proxy cannot grow it without limit, thread-safe,
 * and cleared on {@link org.mockserver.mock.HttpState#reset()} for test isolation.
 * Mirrors the singleton shape of {@link LlmQuotaRegistry}.
 */
public final class OpenAiResponsesStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Cap retained responses so an unbounded proxy stream cannot leak memory. LRU by
    // access order; the oldest entry is evicted past the cap. 10k mirrors the quota
    // registry's window cap and is far more than any realistic single agent run.
    static final int MAX_RESPONSES = 10_000;

    // GET /v1/responses/{id} (and the codex-backend equivalent .../responses/{id}). The
    // id segment must be non-empty and not itself contain a slash/query/fragment.
    private static final Pattern RETRIEVAL_PATH = Pattern.compile("(?:^|/)responses/([^/?#]+)$");

    private static final OpenAiResponsesStore INSTANCE = new OpenAiResponsesStore();

    private final Map<String, StoredResponse> responses = Collections.synchronizedMap(
        new LinkedHashMap<String, StoredResponse>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, StoredResponse> eldest) {
                return size() > MAX_RESPONSES;
            }
        });

    private OpenAiResponsesStore() {
    }

    public static OpenAiResponsesStore getInstance() {
        return INSTANCE;
    }

    /** An issued, stored Responses-API response: its id, the full conversation as of this turn, and the raw response body. */
    public static final class StoredResponse {
        private final String id;
        private final ParsedConversation conversation;
        private final String responseBodyJson;

        public StoredResponse(String id, ParsedConversation conversation, String responseBodyJson) {
            this.id = id;
            this.conversation = conversation;
            this.responseBodyJson = responseBodyJson;
        }

        public String getId() {
            return id;
        }

        /** The full conversation (prior chained turns + this turn's input + this turn's assistant output). */
        public ParsedConversation getConversation() {
            return conversation;
        }

        public String getResponseBodyJson() {
            return responseBodyJson;
        }
    }

    public void put(StoredResponse record) {
        if (record != null && record.getId() != null && !record.getId().isEmpty()) {
            responses.put(record.getId(), record);
        }
    }

    public Optional<StoredResponse> get(String id) {
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(responses.get(id));
    }

    /** Clear all stored responses. Called on server reset and for test isolation. */
    public void reset() {
        responses.clear();
    }

    // --- chaining ------------------------------------------------------------

    /**
     * If {@code requestBody} carries a {@code previous_response_id} whose response is
     * stored, return the prior conversation's messages so the codec can prepend them to
     * the current turn's messages; otherwise an empty list. Fail-soft: an unparseable
     * body or unknown id yields an empty list (no chaining), never an error.
     */
    public List<ParsedMessage> priorMessagesFor(String requestBody) {
        String previousId = extractPreviousResponseId(requestBody);
        if (previousId == null) {
            return Collections.emptyList();
        }
        return get(previousId)
            .map(stored -> new ArrayList<>(stored.getConversation().getMessages()))
            .orElseGet(ArrayList::new);
    }

    private String extractPreviousResponseId(String requestBody) {
        if (requestBody == null || requestBody.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(requestBody);
            if (root != null && root.hasNonNull("previous_response_id")) {
                String id = root.get("previous_response_id").asText("");
                return id.isEmpty() ? null : id;
            }
        } catch (Exception ignored) {
            // fail-soft: not parseable → no chaining
        }
        return null;
    }

    // --- registration --------------------------------------------------------

    /**
     * Record the response just issued for a {@code POST /v1/responses} turn so it can be
     * chained (via {@code previous_response_id}) or retrieved (via {@code GET
     * /v1/responses/{id}}). No-op unless the request opts in to storage
     * ({@code store} defaults to {@code true}; {@code store:false} skips it).
     *
     * <p>The stored conversation is the fully-chained decode of this request (which, when
     * the request carried a {@code previous_response_id}, already includes the prior
     * turns) plus this turn's assistant output — so a subsequent turn referencing this
     * id reconstructs the entire dialogue. Fail-soft: any parsing error simply skips
     * registration and never affects the served response.
     *
     * @param chainedConversation the codec's decode of this request (already chained)
     * @param completion          the assistant completion that was encoded
     * @param encodedBody         the response body that was returned to the client
     */
    public void recordIfStored(String requestBody, ParsedConversation chainedConversation,
                               Completion completion, String encodedBody) {
        try {
            if (!isStoreEnabled(requestBody)) {
                return;
            }
            String id = extractResponseId(encodedBody);
            if (id == null) {
                return;
            }
            List<ParsedMessage> messages = new ArrayList<>();
            if (chainedConversation != null) {
                messages.addAll(chainedConversation.getMessages());
            }
            messages.add(assistantMessage(completion));
            put(new StoredResponse(id, ParsedConversation.of(messages), encodedBody));
        } catch (Exception ignored) {
            // fail-soft: state recording must never affect the served response
        }
    }

    /** The {@code store} flag from the request body — default {@code true} when absent/unparseable. */
    boolean isStoreEnabled(String requestBody) {
        if (requestBody == null || requestBody.isEmpty()) {
            return true;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(requestBody);
            if (root != null && root.has("store") && root.get("store").isBoolean()) {
                return root.get("store").asBoolean();
            }
        } catch (Exception ignored) {
            // fail-soft: default to storing
        }
        return true;
    }

    private String extractResponseId(String encodedBody) {
        if (encodedBody == null || encodedBody.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(encodedBody);
            if (root != null && root.hasNonNull("id")) {
                String id = root.get("id").asText("");
                return id.isEmpty() ? null : id;
            }
        } catch (Exception ignored) {
            // fail-soft
        }
        return null;
    }

    private static ParsedMessage assistantMessage(Completion completion) {
        String text = completion != null && completion.getText() != null ? completion.getText() : "";
        List<ToolUse> toolCalls = completion != null ? completion.getToolCalls() : null;
        return new ParsedMessage(ParsedMessage.Role.ASSISTANT, text, toolCalls, null);
    }

    // --- retrieval -----------------------------------------------------------

    /**
     * If {@code request} is a {@code GET /v1/responses/{id}} whose id is stored, return
     * the stored response body as a {@code 200 application/json} response; otherwise
     * {@code null} so the caller falls through to normal handling. Returning {@code null}
     * for an unknown id keeps the mock non-intrusive — a user-configured expectation or
     * the standard 404 path still applies.
     */
    public HttpResponse retrievalResponseOrNull(HttpRequest request) {
        if (request == null || request.getMethod() == null) {
            return null;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod().getValue())) {
            return null;
        }
        String path = request.getPath() != null ? request.getPath().getValue() : null;
        if (path == null) {
            return null;
        }
        Matcher matcher = RETRIEVAL_PATH.matcher(path);
        if (!matcher.find()) {
            return null;
        }
        String id = matcher.group(1);
        Optional<StoredResponse> stored = get(id);
        if (stored.isEmpty()) {
            return null;
        }
        return response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody(stored.get().getResponseBodyJson());
    }
}
