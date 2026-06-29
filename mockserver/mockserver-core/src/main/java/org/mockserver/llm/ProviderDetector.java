package org.mockserver.llm;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic, read-only heuristic that infers the {@link Provider} from an
 * {@link HttpRequest}'s path (and, as a fallback, its Host header). This
 * mirrors the UI-side provider detection in {@code llmTraffic.ts} and is used
 * by the MCP analysis tools to support an {@code "AUTO"} provider value for
 * proxied traffic where the caller may not know which provider was recorded.
 * <p>
 * Detection order matches the UI: Anthropic, Azure OpenAI, Bedrock, OpenAI
 * Responses, OpenAI Chat Completions, Gemini, Ollama. The first match wins.
 */
public final class ProviderDetector {

    // Anthropic: /v1/messages
    private static final Pattern ANTHROPIC_PATH = Pattern.compile("/v1/messages");

    // Azure OpenAI: /openai/deployments/.../chat/completions
    private static final Pattern AZURE_OPENAI_PATH = Pattern.compile("/openai/deployments/.*/chat/completions");

    // Bedrock Runtime: any model id, both invoke and Converse APIs (not anchored to
    // anthropic.* — non-Anthropic models use the same path shape).
    private static final Pattern BEDROCK_PATH = Pattern.compile(
        "/model/[^/]+/(invoke|invoke-with-response-stream|converse|converse-stream)");

    // OpenAI Responses: the standard /v1/responses, and the OpenAI Codex backend
    // used by coding CLIs such as opencode (chatgpt.com/backend-api/codex/responses),
    // which serves the same Responses wire format. The codex/responses arm is anchored
    // to a path-segment boundary so /mycodex/responses cannot match.
    private static final Pattern OPENAI_RESPONSES_PATH = Pattern.compile(
        "/v1/responses|(^|/)codex/responses(?:[/?]|$)");

    // OpenAI Chat Completions: /chat/completions (but not Azure pattern)
    private static final Pattern OPENAI_PATH = Pattern.compile("/chat/completions");

    // Gemini: /v1beta/models/...:(generateContent|streamGenerateContent),
    //      or /v1/models/gemini-...:(generateContent|streamGenerateContent),
    //      or Vertex AI /publishers/google/models/...:(generateContent|streamGenerateContent).
    // Matched case-sensitively (model name and method token are case-sensitive).
    private static final Pattern GEMINI_PATH = Pattern.compile(
        "/v1beta/models/[^/]+:(generateContent|streamGenerateContent)"
            + "|/v1/models/gemini-[^/]+:(generateContent|streamGenerateContent)"
            + "|/publishers/google/models/[^/]+:(generateContent|streamGenerateContent)");

    // Cohere: /v1/rerank, or /v1/chat as a terminal segment (NOT /v1/chat/completions).
    // /v1/rerank is shared with Voyage; on path alone it defaults to Cohere.
    private static final Pattern COHERE_PATH = Pattern.compile("/v1/rerank|/v1/chat(/?$|\\?)");

    // Ollama: /api/chat (as a complete path segment)
    private static final Pattern OLLAMA_PATH = Pattern.compile("(^|/)api/chat(/|$|\\?)");

    private ProviderDetector() {
    }

    /**
     * Attempt to detect the LLM provider from an HTTP request's path.
     * Returns empty if no provider can be inferred.
     */
    public static Optional<Provider> detect(HttpRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String path = request.getPath() != null ? request.getPath().getValue() : null;
        if (path != null) {
            Optional<Provider> fromPath = detectFromPath(path);
            if (fromPath.isPresent()) {
                return fromPath;
            }
        }
        return Optional.empty();
    }

    /**
     * Detect the provider from a request path string alone.
     */
    public static Optional<Provider> detectFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }

        // Path keywords are matched case-insensitively (like the sniffer's sniffByPath),
        // EXCEPT the Gemini pattern, whose model name and method token are case-sensitive
        // and so are matched against the original path.
        String lower = path.toLowerCase();

        // Order matches UI detection (most specific first)
        if (ANTHROPIC_PATH.matcher(lower).find()) {
            return Optional.of(Provider.ANTHROPIC);
        }
        if (AZURE_OPENAI_PATH.matcher(lower).find()) {
            return Optional.of(Provider.AZURE_OPENAI);
        }
        if (BEDROCK_PATH.matcher(lower).find()) {
            return Optional.of(Provider.BEDROCK);
        }
        if (OPENAI_RESPONSES_PATH.matcher(lower).find()) {
            return Optional.of(Provider.OPENAI_RESPONSES);
        }
        if (OPENAI_PATH.matcher(lower).find()) {
            return Optional.of(Provider.OPENAI);
        }
        if (GEMINI_PATH.matcher(path).find()) {
            return Optional.of(Provider.GEMINI);
        }
        if (COHERE_PATH.matcher(lower).find()) {
            return Optional.of(Provider.COHERE);
        }
        if (OLLAMA_PATH.matcher(lower).find()) {
            return Optional.of(Provider.OLLAMA);
        }

        return Optional.empty();
    }

    /**
     * Auto-detect the provider from a list of recorded requests by scanning
     * their paths. Returns the first detected provider, or empty if none can
     * be inferred.
     */
    public static Optional<Provider> detectFromRequests(java.util.List<HttpRequest> requests) {
        if (requests == null) {
            return Optional.empty();
        }
        for (HttpRequest request : requests) {
            Optional<Provider> detected = detect(request);
            if (detected.isPresent()) {
                return detected;
            }
        }
        return Optional.empty();
    }
}
