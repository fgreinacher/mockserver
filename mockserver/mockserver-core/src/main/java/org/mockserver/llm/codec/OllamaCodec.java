package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.JsonEscape;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.llm.StreamingPhysicsExpander;
import org.mockserver.llm.TokenCounter;
import org.mockserver.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.SseEvent.sseEvent;

/**
 * Codec for Ollama {@code /api/chat} endpoint (version ollama-2025).
 * <p>
 * Ollama uses a distinct JSON shape with a top-level {@code message} object
 * (not an array of choices). Token counts are exposed as {@code prompt_eval_count}
 * and {@code eval_count}.
 * <p>
 * <strong>Streaming format:</strong> Ollama's native wire format is NDJSON
 * (newline-delimited JSON, {@code application/x-ndjson}), not SSE. Each
 * streaming chunk is a single JSON object followed by a newline character
 * ({@code <json>\n}). This codec internally represents chunks as
 * {@link SseEvent} objects (reusing the existing data/delay model), but
 * declares {@link StreamingFormat#NDJSON} via {@link #streamingFormat()} so
 * that the downstream write handler emits raw NDJSON framing instead of SSE
 * {@code data:} prefixes.
 * <p>
 * Tool calls use Ollama 0.3+ format where {@code arguments} is a JSON object
 * (not a JSON-as-string like OpenAI).
 */
public class OllamaCodec implements ProviderCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Provider provider() {
        return Provider.OLLAMA;
    }

    @Override
    public String apiVersion() {
        return "ollama-2025";
    }

    @Override
    public StreamingFormat streamingFormat() {
        return StreamingFormat.NDJSON;
    }

    @Override
    public HttpResponse encode(Completion completion, String model) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("model", model != null ? model : "unknown");
        root.put("created_at", Instant.now().toString());

        ObjectNode message = root.putObject("message");
        message.put("role", "assistant");

        String text = completion.getText();
        message.put("content", text != null ? text : "");

        // Reasoning ("thinking") content. Ollama 0.5+ surfaces a reasoning-capable model's chain of
        // thought as message.thinking (a sibling string to content). Emitted only when reasoningText
        // is set so existing fixtures stay byte-identical. Ollama has NO native cached/reasoning
        // *token-count* field, so the Usage.cachedInputTokens / reasoningTokens counts are not
        // encoded here — only the reasoning text is representable on the wire.
        String reasoningText = completion.getReasoningText();
        if (reasoningText != null && !reasoningText.isEmpty()) {
            message.put("thinking", reasoningText);
        }

        // Tool calls
        List<ToolUse> toolCalls = completion.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        if (hasToolCalls) {
            ArrayNode toolCallsArray = message.putArray("tool_calls");
            for (ToolUse toolCall : toolCalls) {
                ObjectNode tc = toolCallsArray.addObject();
                ObjectNode function = tc.putObject("function");
                function.put("name", toolCall.getName());
                // Ollama puts arguments as a JSON object, not a string
                String args = toolCall.getArguments();
                if (args != null) {
                    try {
                        JsonNode parsed = OBJECT_MAPPER.readTree(args);
                        if (parsed.isObject()) {
                            function.set("arguments", parsed);
                        } else {
                            ObjectNode argsObj = function.putObject("arguments");
                            argsObj.put("value", args);
                        }
                    } catch (Exception e) {
                        ObjectNode argsObj = function.putObject("arguments");
                        argsObj.put("value", args);
                    }
                } else {
                    function.putObject("arguments");
                }
            }
        }

        root.put("done", true);
        root.put("total_duration", 1234567890L);
        root.put("load_duration", 12345L);

        // Token counts
        Usage completionUsage = completion.getUsage();
        int promptEvalCount = completionUsage != null && completionUsage.getInputTokens() != null ? completionUsage.getInputTokens() : 0;
        int evalCount = completionUsage != null && completionUsage.getOutputTokens() != null ? completionUsage.getOutputTokens() : 0;
        root.put("prompt_eval_count", promptEvalCount);
        root.put("prompt_eval_duration", 67890L);
        root.put("eval_count", evalCount);
        root.put("eval_duration", 12345L);

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode Ollama response", e);
        }
    }

    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        List<SseEvent> events = new ArrayList<>();
        String modelName = model != null ? model : "unknown";

        String text = completion.getText();

        // Reasoning ("thinking") delta chunk (before content) when reasoning is set. Ollama streams
        // a reasoning model's chain of thought as message.thinking deltas ahead of the content
        // deltas. Additive — absent unless reasoningText is set.
        String reasoningText = completion.getReasoningText();
        if (reasoningText != null && !reasoningText.isEmpty()) {
            String thinkingChunk = "{\"model\":\"" + escapeJson(modelName) +
                "\",\"created_at\":\"" + Instant.now().toString() +
                "\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"" + escapeJson(reasoningText) +
                "\"},\"done\":false}";
            events.add(sseEvent().withData(thinkingChunk));
        }

        // Text delta chunks
        if (text != null && !text.isEmpty()) {
            List<String> tokens = TokenCounter.streamingTextTokens(text, physics);
            for (String token : tokens) {
                if (!token.isEmpty()) {
                    String chunkData = "{\"model\":\"" + escapeJson(modelName) +
                        "\",\"created_at\":\"" + Instant.now().toString() +
                        "\",\"message\":{\"role\":\"assistant\",\"content\":\"" + escapeJson(token) +
                        "\"},\"done\":false}";
                    events.add(sseEvent().withData(chunkData));
                }
            }
        }

        // Final chunk with done: true and token counts.
        // Ollama's native wire format sends tool calls as a single block on the
        // final chunk (no per-call deltas), so we embed them there.
        Usage completionUsage = completion.getUsage();
        int promptEvalCount = completionUsage != null && completionUsage.getInputTokens() != null ? completionUsage.getInputTokens() : 0;
        int evalCount = completionUsage != null && completionUsage.getOutputTokens() != null ? completionUsage.getOutputTokens() : 0;

        List<ToolUse> toolCalls = completion.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();

        ObjectNode finalChunk = OBJECT_MAPPER.createObjectNode();
        finalChunk.put("model", modelName);
        finalChunk.put("created_at", Instant.now().toString());
        ObjectNode message = finalChunk.putObject("message");
        message.put("role", "assistant");
        message.put("content", "");
        if (hasToolCalls) {
            ArrayNode toolCallsArray = message.putArray("tool_calls");
            for (ToolUse toolCall : toolCalls) {
                ObjectNode tc = toolCallsArray.addObject();
                ObjectNode function = tc.putObject("function");
                function.put("name", toolCall.getName());
                String args = toolCall.getArguments();
                if (args != null) {
                    try {
                        JsonNode parsed = OBJECT_MAPPER.readTree(args);
                        if (parsed != null && parsed.isObject()) {
                            function.set("arguments", parsed);
                        } else {
                            ObjectNode argsObj = function.putObject("arguments");
                            argsObj.put("value", args);
                        }
                    } catch (Exception e) {
                        ObjectNode argsObj = function.putObject("arguments");
                        argsObj.put("value", args);
                    }
                } else {
                    function.putObject("arguments");
                }
            }
        }
        finalChunk.put("done", true);
        finalChunk.put("total_duration", 1234567890L);
        finalChunk.put("load_duration", 12345L);
        finalChunk.put("prompt_eval_count", promptEvalCount);
        finalChunk.put("prompt_eval_duration", 67890L);
        finalChunk.put("eval_count", evalCount);
        finalChunk.put("eval_duration", 12345L);

        try {
            events.add(sseEvent().withData(OBJECT_MAPPER.writeValueAsString(finalChunk)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode Ollama final streaming chunk", e);
        }

        return StreamingPhysicsExpander.applyPhysics(events, physics);
    }

    @Override
    public ParsedConversation decode(HttpRequest request) {
        try {
            String body = request != null ? request.getBodyAsString() : null;
            if (body == null || body.isEmpty()) {
                return ParsedConversation.empty();
            }
            JsonNode root = OBJECT_MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return ParsedConversation.empty();
            }
            JsonNode messagesNode = root.get("messages");
            if (messagesNode == null || !messagesNode.isArray()) {
                return ParsedConversation.empty();
            }

            List<ParsedMessage> parsed = new ArrayList<>();
            for (JsonNode msgNode : messagesNode) {
                String rawRole = msgNode.has("role") ? msgNode.get("role").asText("") : "";
                String textContent = "";
                List<ToolUse> toolCalls = new ArrayList<>();
                Map<String, String> toolResults = new LinkedHashMap<>();

                // Parse content
                JsonNode contentNode = msgNode.get("content");
                if (contentNode != null && contentNode.isTextual()) {
                    textContent = contentNode.asText("");
                }

                // Parse tool_calls on assistant messages
                JsonNode toolCallsNode = msgNode.get("tool_calls");
                if (toolCallsNode != null && toolCallsNode.isArray()) {
                    for (JsonNode tcNode : toolCallsNode) {
                        JsonNode functionNode = tcNode.get("function");
                        if (functionNode != null) {
                            String name = functionNode.has("name") ? functionNode.get("name").asText("") : "";
                            String arguments = "{}";
                            if (functionNode.has("arguments")) {
                                JsonNode argsNode = functionNode.get("arguments");
                                if (argsNode.isTextual()) {
                                    arguments = argsNode.asText("");
                                } else {
                                    arguments = argsNode.toString();
                                }
                            }
                            // Ollama tool messages don't have tool_call_id — no ID to set
                            ToolUse tu = ToolUse.toolUse(name).withArguments(arguments);
                            toolCalls.add(tu);
                        }
                    }
                }

                // Tool role messages: correlation by name only
                if ("tool".equalsIgnoreCase(rawRole)) {
                    // Ollama tool messages don't have tool_call_id
                    // Use empty string as the key for correlation by content
                    toolResults.put("", textContent);
                }

                ParsedMessage.Role role = mapOllamaRole(rawRole);

                parsed.add(new ParsedMessage(
                    role,
                    textContent,
                    toolCalls.isEmpty() ? null : toolCalls,
                    toolResults.isEmpty() ? null : toolResults
                ));
            }

            return ParsedConversation.of(parsed);
        } catch (Exception e) {
            return ParsedConversation.empty();
        }
    }

    /**
     * Encodes an Ollama embedding response. Ollama exposes two endpoints:
     * <ul>
     *   <li>the newer {@code POST /api/embed} returning a batch shape
     *       ({@code {"embeddings":[[...]]}}, plus {@code model} and
     *       {@code prompt_eval_count}), and</li>
     *   <li>the legacy {@code POST /api/embeddings} returning a single-vector
     *       shape ({@code {"embedding":[...]}}).</li>
     * </ul>
     * This codec emits the {@code /api/embed} batch shape with a single embedding
     * (the modern, recommended endpoint), which also satisfies clients that read
     * the {@code embeddings} array. The Ollama default dimensionality for
     * {@code nomic-embed-text} is 768.
     */
    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        return encodeEmbedding(embedding, input, null);
    }

    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input, String model) {
        double[] vector = EmbeddingVectors.build(embedding, input, 768);

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("model", model != null ? model : "nomic-embed-text");
        ArrayNode embeddings = root.putArray("embeddings");
        ArrayNode first = embeddings.addArray();
        for (double v : vector) {
            first.add(v);
        }
        int approxTokens = EmbeddingVectors.approximateTokens(input);
        root.put("prompt_eval_count", approxTokens);

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode Ollama embedding response", e);
        }
    }

    private static ParsedMessage.Role mapOllamaRole(String rawRole) {
        if (rawRole == null) {
            return ParsedMessage.Role.USER;
        }
        switch (rawRole.toLowerCase()) {
            case "assistant":
                return ParsedMessage.Role.ASSISTANT;
            case "user":
                return ParsedMessage.Role.USER;
            case "tool":
                return ParsedMessage.Role.TOOL;
            case "system":
                return ParsedMessage.Role.SYSTEM;
            default:
                return ParsedMessage.Role.USER;
        }
    }

    private static String escapeJson(String value) {
        return JsonEscape.escape(value);
    }
}
