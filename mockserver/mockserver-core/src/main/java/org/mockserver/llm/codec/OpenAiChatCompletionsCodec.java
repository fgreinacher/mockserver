package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.JsonEscape;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.StreamingPhysicsExpander;
import org.mockserver.llm.TokenCounter;
import org.mockserver.model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.SseEvent.sseEvent;

/**
 * Codec for OpenAI Chat Completions API (version 2025-01).
 * Encodes MockServer Completion objects into OpenAI-format HTTP responses
 * for both non-streaming and streaming (SSE) paths.
 * Also handles OpenAI Embeddings API responses.
 */
public class OpenAiChatCompletionsCodec implements ProviderCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Provider provider() {
        return Provider.OPENAI;
    }

    @Override
    public String apiVersion() {
        return "2025-01";
    }

    @Override
    public HttpResponse encode(Completion completion, String model) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("id", "chatcmpl-" + randomId(29));
        root.put("object", "chat.completion");
        root.put("created", System.currentTimeMillis() / 1000);
        root.put("model", model != null ? model : "unknown");

        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);

        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");

        String text = completion.getText();
        List<ToolUse> toolCalls = completion.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();

        // content: null if text is null AND there are tool calls; otherwise use text
        if (text == null && hasToolCalls) {
            message.putNull("content");
        } else {
            message.put("content", text != null ? text : "");
        }

        // tool_calls: omitted entirely when no tool calls; never emit empty array
        if (hasToolCalls) {
            ArrayNode toolCallsArray = message.putArray("tool_calls");
            for (ToolUse toolCall : toolCalls) {
                ObjectNode tc = toolCallsArray.addObject();
                tc.put("id", "call_" + randomId(24));
                tc.put("type", "function");
                ObjectNode function = tc.putObject("function");
                function.put("name", toolCall.getName());
                // arguments is always a JSON-as-string (OpenAI's quirk)
                function.put("arguments", toolCall.getArguments() != null ? toolCall.getArguments() : "{}");
            }
        }

        // finish_reason mapping
        choice.put("finish_reason", mapFinishReason(completion.getStopReason(), hasToolCalls, completion.getToolChoice()));

        // usage
        ObjectNode usage = root.putObject("usage");
        Usage completionUsage = completion.getUsage();
        int promptTokens = completionUsage != null && completionUsage.getInputTokens() != null ? completionUsage.getInputTokens() : 0;
        int completionTokens = completionUsage != null && completionUsage.getOutputTokens() != null ? completionUsage.getOutputTokens() : 0;
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        // Cached-input and reasoning token details are emitted only when set (non-null, non-zero),
        // under OpenAI's native nested-details keys, so existing fixtures stay byte-identical when
        // these optional fields are unset.
        if (completionUsage != null) {
            Integer cachedInputTokens = completionUsage.getCachedInputTokens();
            if (cachedInputTokens != null && cachedInputTokens != 0) {
                usage.putObject("prompt_tokens_details").put("cached_tokens", cachedInputTokens);
            }
            Integer reasoningTokens = completionUsage.getReasoningTokens();
            if (reasoningTokens != null && reasoningTokens != 0) {
                usage.putObject("completion_tokens_details").put("reasoning_tokens", reasoningTokens);
            }
        }

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode OpenAI response", e);
        }
    }

    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        List<SseEvent> events = new ArrayList<>();
        String id = "chatcmpl-" + randomId(29);
        long created = System.currentTimeMillis() / 1000;
        String modelName = model != null ? model : "unknown";

        // 1. First chunk: role-only
        events.add(sseEvent().withData(buildChunk(id, created, modelName,
            "{\"role\":\"assistant\",\"content\":\"\"}", null)));

        // 2. Text content chunks
        String text = completion.getText();
        if (text != null && !text.isEmpty()) {
            List<String> tokens = TokenCounter.streamingTextTokens(text, physics);
            for (String token : tokens) {
                if (!token.isEmpty()) {
                    events.add(sseEvent().withData(buildChunk(id, created, modelName,
                        "{\"content\":\"" + escapeJson(token) + "\"}", null)));
                }
            }
        }

        // 3. Tool call chunks
        List<ToolUse> toolCalls = completion.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        if (hasToolCalls) {
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolUse toolCall = toolCalls.get(i);
                String toolCallId = "call_" + randomId(24);
                String args = toolCall.getArguments() != null ? toolCall.getArguments() : "{}";
                String delta = "{\"tool_calls\":[{\"index\":" + i + ",\"id\":\"" + toolCallId +
                    "\",\"type\":\"function\",\"function\":{\"name\":\"" + escapeJson(toolCall.getName()) +
                    "\",\"arguments\":\"" + escapeJson(args) + "\"}}]}";
                events.add(sseEvent().withData(buildChunk(id, created, modelName, delta, null)));
            }
        }

        // 4. Final chunk with finish_reason
        String finishReason = mapFinishReason(completion.getStopReason(), hasToolCalls, completion.getToolChoice());
        events.add(sseEvent().withData(buildChunk(id, created, modelName, "{}", finishReason)));

        // 5. [DONE] sentinel
        events.add(sseEvent().withData("[DONE]"));

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
                JsonNode contentNode = msgNode.get("content");

                String textContent = "";
                List<ToolUse> toolCalls = new ArrayList<>();
                Map<String, String> toolResults = new LinkedHashMap<>();
                List<ParsedMessage.ImagePart> images = new ArrayList<>();
                List<ParsedMessage.AudioPart> audio = new ArrayList<>();

                // Parse text content
                if (contentNode != null) {
                    if (contentNode.isTextual()) {
                        textContent = contentNode.asText("");
                    } else if (contentNode.isArray()) {
                        StringBuilder textBuilder = new StringBuilder();
                        for (JsonNode part : contentNode) {
                            String partType = part.has("type") ? part.get("type").asText("") : "";
                            if ("text".equals(partType)) {
                                String text = part.has("text") ? part.get("text").asText("") : "";
                                textBuilder.append(text);
                            } else if ("image_url".equals(partType)) {
                                // OpenAI image part: {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}
                                images.add(new ParsedMessage.ImagePart(mediaTypeFromDataUrl(part.path("image_url").path("url").asText(""))));
                            } else if ("input_audio".equals(partType)) {
                                // OpenAI audio part: {"type":"input_audio","input_audio":{"data":"<base64>","format":"wav"}}
                                String format = part.path("input_audio").path("format").asText("");
                                audio.add(new ParsedMessage.AudioPart(format.isEmpty() ? null : format));
                            }
                        }
                        textContent = textBuilder.toString();
                    } else if (contentNode.isNull()) {
                        textContent = "";
                    }
                }

                // Parse tool_calls on assistant messages
                JsonNode toolCallsNode = msgNode.get("tool_calls");
                if (toolCallsNode != null && toolCallsNode.isArray()) {
                    for (JsonNode tcNode : toolCallsNode) {
                        String callId = tcNode.has("id") ? tcNode.get("id").asText("") : null;
                        JsonNode functionNode = tcNode.get("function");
                        if (functionNode != null) {
                            String name = functionNode.has("name") ? functionNode.get("name").asText("") : "";
                            String arguments = functionNode.has("arguments") ? functionNode.get("arguments").asText("") : "{}";
                            ToolUse tu = ToolUse.toolUse(name).withArguments(arguments);
                            if (callId != null && !callId.isEmpty()) {
                                tu.withId(callId);
                            }
                            toolCalls.add(tu);
                        }
                    }
                }

                // Parse tool role messages with tool_call_id
                if ("tool".equalsIgnoreCase(rawRole)) {
                    String toolCallId = msgNode.has("tool_call_id") ? msgNode.get("tool_call_id").asText("") : "";
                    String toolContent = textContent;
                    if (!toolCallId.isEmpty()) {
                        toolResults.put(toolCallId, toolContent);
                    }
                }

                // Map role
                ParsedMessage.Role role = mapOpenAiRole(rawRole);

                parsed.add(new ParsedMessage(
                    role,
                    textContent,
                    toolCalls.isEmpty() ? null : toolCalls,
                    toolResults.isEmpty() ? null : toolResults,
                    images.isEmpty() ? null : images,
                    audio.isEmpty() ? null : audio
                ));
            }

            return ParsedConversation.of(parsed);
        } catch (Exception e) {
            return ParsedConversation.empty();
        }
    }

    /**
     * Extract the media type from an OpenAI image data URL
     * ({@code data:image/png;base64,...}), or {@code null} for a remote https URL.
     */
    private static String mediaTypeFromDataUrl(String url) {
        if (url != null && url.startsWith("data:")) {
            int semi = url.indexOf(';');
            int comma = url.indexOf(',');
            int end = semi >= 0 ? semi : (comma >= 0 ? comma : -1);
            if (end > 5) {
                return url.substring(5, end);
            }
        }
        return null;
    }

    private static ParsedMessage.Role mapOpenAiRole(String rawRole) {
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

    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        double[] vector = EmbeddingVectors.build(embedding, input, 1536);

        // Build response
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("object", "list");

        ArrayNode data = root.putArray("data");
        ObjectNode embeddingObj = data.addObject();
        embeddingObj.put("object", "embedding");
        embeddingObj.put("index", 0);
        ArrayNode embeddingArray = embeddingObj.putArray("embedding");
        for (double v : vector) {
            embeddingArray.add(v);
        }

        root.put("model", "text-embedding-3-small");

        // approximate token count from input
        int approxTokens = EmbeddingVectors.approximateTokens(input);
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", approxTokens);
        usage.put("total_tokens", approxTokens);

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode OpenAI embedding response", e);
        }
    }

    static double[] generateDeterministicVector(String input, int dimensions, long seed) {
        return EmbeddingVectors.generateDeterministicVector(input, dimensions, seed);
    }

    static void normalizeL2(double[] vector) {
        EmbeddingVectors.normalizeL2(vector);
    }

    private String buildChunk(String id, long created, String model, String deltaJson, String finishReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(id).append("\",");
        sb.append("\"object\":\"chat.completion.chunk\",");
        sb.append("\"created\":").append(created).append(",");
        sb.append("\"model\":\"").append(escapeJson(model)).append("\",");
        sb.append("\"choices\":[{\"index\":0,\"delta\":").append(deltaJson).append(",");
        if (finishReason != null) {
            sb.append("\"finish_reason\":\"").append(escapeJson(finishReason)).append("\"");
        } else {
            sb.append("\"finish_reason\":null");
        }
        sb.append("}]}");
        return sb.toString();
    }

    private static String mapFinishReason(String stopReason, boolean hasToolCalls, String toolChoice) {
        // When the request forces a tool call (tool_choice=required) and a tool is available,
        // OpenAI returns finish_reason "tool_calls" regardless of any configured stop reason.
        if (hasToolCalls && "required".equalsIgnoreCase(toolChoice)) {
            return "tool_calls";
        }
        if (stopReason == null) {
            return hasToolCalls ? "tool_calls" : "stop";
        }
        switch (stopReason) {
            case "end_turn":
                return "stop";
            case "tool_use":
                return "tool_calls";
            case "max_tokens":
                return "length";
            case "stop":
            case "length":
            case "tool_calls":
            case "content_filter":
                return stopReason;
            default:
                return stopReason;
        }
    }

    private static String randomId(int length) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        while (uuid.length() < length) {
            uuid = uuid + UUID.randomUUID().toString().replace("-", "");
        }
        return uuid.substring(0, length);
    }

    private static String escapeJson(String value) {
        return JsonEscape.escape(value);
    }
}
