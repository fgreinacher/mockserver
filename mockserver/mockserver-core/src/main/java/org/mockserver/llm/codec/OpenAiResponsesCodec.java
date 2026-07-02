package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.JsonEscape;
import org.mockserver.llm.OpenAiResponsesStore;
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
 * Codec for OpenAI Responses API (version 2025-03).
 * The Responses API uses an {@code output} array of blocks (text, function_call)
 * instead of the Chat Completions {@code choices} array.
 * <p>
 * Streaming uses named SSE events (e.g. {@code response.created},
 * {@code response.output_text.delta}, {@code response.completed}).
 */
public class OpenAiResponsesCodec implements ProviderCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Provider provider() {
        return Provider.OPENAI_RESPONSES;
    }

    @Override
    public String apiVersion() {
        return "2025-03";
    }

    @Override
    public HttpResponse encode(Completion completion, String model) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        String responseId = "resp_" + randomId(24);
        root.put("id", responseId);
        root.put("object", "response");
        root.put("created_at", System.currentTimeMillis() / 1000);
        root.put("model", model != null ? model : "unknown");
        root.put("status", "completed");

        ArrayNode output = root.putArray("output");

        // Reasoning output item (prepended before the text message) when reasoning is set.
        // The Responses API surfaces extended-thinking as a leading
        // {"type":"reasoning","summary":[{"type":"summary_text","text":"..."}]} output item.
        // Additive — absent unless Completion.reasoningText is set.
        String reasoningText = completion.getReasoningText();
        if (reasoningText != null && !reasoningText.isEmpty()) {
            ObjectNode reasoningItem = output.addObject();
            reasoningItem.put("type", "reasoning");
            reasoningItem.put("id", "rs_" + randomId(24));
            ArrayNode summary = reasoningItem.putArray("summary");
            ObjectNode summaryPart = summary.addObject();
            summaryPart.put("type", "summary_text");
            summaryPart.put("text", reasoningText);
        }

        // Text message output item
        String text = completion.getText();
        boolean hasText = text != null && !text.isEmpty();
        if (hasText) {
            ObjectNode msgItem = output.addObject();
            msgItem.put("type", "message");
            msgItem.put("id", "msg_" + randomId(24));
            msgItem.put("role", "assistant");
            ArrayNode content = msgItem.putArray("content");
            ObjectNode textBlock = content.addObject();
            textBlock.put("type", "output_text");
            textBlock.put("text", text);
        }

        // Function call output items
        List<ToolUse> toolCalls = completion.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        if (hasToolCalls) {
            for (ToolUse toolCall : toolCalls) {
                ObjectNode fcItem = output.addObject();
                fcItem.put("type", "function_call");
                fcItem.put("id", "fc_" + randomId(24));
                fcItem.put("name", toolCall.getName());
                fcItem.put("arguments", toolCall.getArguments() != null ? toolCall.getArguments() : "{}");
            }
        }

        // usage
        ObjectNode usage = root.putObject("usage");
        Usage completionUsage = completion.getUsage();
        int inputTokens = completionUsage != null && completionUsage.getInputTokens() != null ? completionUsage.getInputTokens() : 0;
        int outputTokens = completionUsage != null && completionUsage.getOutputTokens() != null ? completionUsage.getOutputTokens() : 0;
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("total_tokens", inputTokens + outputTokens);
        // Cached-input and reasoning token details — the Responses API nests these under
        // input_tokens_details / output_tokens_details (distinct from Chat Completions'
        // prompt_/completion_ names). Emitted only when set so existing fixtures stay byte-identical.
        if (completionUsage != null) {
            Integer cachedInputTokens = completionUsage.getCachedInputTokens();
            if (cachedInputTokens != null && cachedInputTokens != 0) {
                usage.putObject("input_tokens_details").put("cached_tokens", cachedInputTokens);
            }
            Integer reasoningTokens = completionUsage.getReasoningTokens();
            if (reasoningTokens != null && reasoningTokens != 0) {
                usage.putObject("output_tokens_details").put("reasoning_tokens", reasoningTokens);
            }
        }

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode OpenAI Responses response", e);
        }
    }

    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        List<SseEvent> events = new ArrayList<>();
        String responseId = "resp_" + randomId(24);
        String modelName = model != null ? model : "unknown";
        long createdAt = System.currentTimeMillis() / 1000;

        Usage completionUsage = completion.getUsage();
        int inputTokens = completionUsage != null && completionUsage.getInputTokens() != null ? completionUsage.getInputTokens() : 0;
        int outputTokens = completionUsage != null && completionUsage.getOutputTokens() != null ? completionUsage.getOutputTokens() : 0;

        // 1. response.created
        String createdData = "{\"type\":\"response.created\",\"response\":{\"id\":\"" + responseId +
            "\",\"object\":\"response\",\"status\":\"in_progress\",\"model\":\"" + escapeJson(modelName) +
            "\",\"created_at\":" + createdAt + ",\"output\":[]}}";
        events.add(sseEvent().withEvent("response.created").withData(createdData));

        // 2. response.in_progress
        String inProgressData = "{\"type\":\"response.in_progress\",\"response\":{\"id\":\"" + responseId +
            "\",\"object\":\"response\",\"status\":\"in_progress\"}}";
        events.add(sseEvent().withEvent("response.in_progress").withData(inProgressData));

        int outputIndex = 0;

        // 2b. Reasoning output item (before text) when reasoning is set. Mirrors the non-streaming
        // reasoning item via the Responses reasoning-summary streaming events. Additive.
        String reasoningText = completion.getReasoningText();
        if (reasoningText != null && !reasoningText.isEmpty()) {
            String rsId = "rs_" + randomId(24);

            String rsAddedData = "{\"type\":\"response.output_item.added\",\"output_index\":" + outputIndex +
                ",\"item\":{\"type\":\"reasoning\",\"id\":\"" + rsId + "\",\"summary\":[]}}";
            events.add(sseEvent().withEvent("response.output_item.added").withData(rsAddedData));

            String partAddedData = "{\"type\":\"response.reasoning_summary_part.added\",\"item_id\":\"" + rsId +
                "\",\"output_index\":" + outputIndex + ",\"summary_index\":0,\"part\":{\"type\":\"summary_text\",\"text\":\"\"}}";
            events.add(sseEvent().withEvent("response.reasoning_summary_part.added").withData(partAddedData));

            String summaryDeltaData = "{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"" + rsId +
                "\",\"output_index\":" + outputIndex + ",\"summary_index\":0,\"delta\":\"" + escapeJson(reasoningText) + "\"}";
            events.add(sseEvent().withEvent("response.reasoning_summary_text.delta").withData(summaryDeltaData));

            String summaryDoneData = "{\"type\":\"response.reasoning_summary_text.done\",\"item_id\":\"" + rsId +
                "\",\"output_index\":" + outputIndex + ",\"summary_index\":0,\"text\":\"" + escapeJson(reasoningText) + "\"}";
            events.add(sseEvent().withEvent("response.reasoning_summary_text.done").withData(summaryDoneData));

            String partDoneData = "{\"type\":\"response.reasoning_summary_part.done\",\"item_id\":\"" + rsId +
                "\",\"output_index\":" + outputIndex + ",\"summary_index\":0,\"part\":{\"type\":\"summary_text\",\"text\":\"" +
                escapeJson(reasoningText) + "\"}}";
            events.add(sseEvent().withEvent("response.reasoning_summary_part.done").withData(partDoneData));

            String rsDoneData = "{\"type\":\"response.output_item.done\",\"output_index\":" + outputIndex +
                ",\"item\":{\"type\":\"reasoning\",\"id\":\"" + rsId + "\"}}";
            events.add(sseEvent().withEvent("response.output_item.done").withData(rsDoneData));

            outputIndex++;
        }

        // 3. Text content
        String text = completion.getText();
        if (text != null && !text.isEmpty()) {
            String msgId = "msg_" + randomId(24);

            // output_item.added
            String itemAddedData = "{\"type\":\"response.output_item.added\",\"output_index\":" + outputIndex +
                ",\"item\":{\"type\":\"message\",\"id\":\"" + msgId +
                "\",\"role\":\"assistant\",\"content\":[]}}";
            events.add(sseEvent().withEvent("response.output_item.added").withData(itemAddedData));

            // text deltas
            List<String> tokens = TokenCounter.streamingTextTokens(text, physics);
            for (String token : tokens) {
                if (!token.isEmpty()) {
                    String deltaData = "{\"type\":\"response.output_text.delta\",\"item_id\":\"" + msgId +
                        "\",\"output_index\":" + outputIndex + ",\"content_index\":0,\"delta\":\"" + escapeJson(token) + "\"}";
                    events.add(sseEvent().withEvent("response.output_text.delta").withData(deltaData));
                }
            }

            // output_text.done
            String textDoneData = "{\"type\":\"response.output_text.done\",\"item_id\":\"" + msgId +
                "\",\"output_index\":" + outputIndex + ",\"content_index\":0,\"text\":\"" + escapeJson(text) + "\"}";
            events.add(sseEvent().withEvent("response.output_text.done").withData(textDoneData));

            // output_item.done
            String itemDoneData = "{\"type\":\"response.output_item.done\",\"output_index\":" + outputIndex +
                ",\"item\":{\"type\":\"message\",\"id\":\"" + msgId + "\",\"role\":\"assistant\"}}";
            events.add(sseEvent().withEvent("response.output_item.done").withData(itemDoneData));

            outputIndex++;
        }

        // 4. Tool calls
        List<ToolUse> toolCalls = completion.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            for (ToolUse toolCall : toolCalls) {
                String fcId = "fc_" + randomId(24);
                String args = toolCall.getArguments() != null ? toolCall.getArguments() : "{}";

                // output_item.added
                String fcAddedData = "{\"type\":\"response.output_item.added\",\"output_index\":" + outputIndex +
                    ",\"item\":{\"type\":\"function_call\",\"id\":\"" + fcId +
                    "\",\"name\":\"" + escapeJson(toolCall.getName()) + "\",\"arguments\":\"\"}}";
                events.add(sseEvent().withEvent("response.output_item.added").withData(fcAddedData));

                // output_item.done — the Responses API serialises function_call
                // arguments as a JSON-encoded string (escaped + quoted), matching the
                // non-streaming encode() path which writes the arguments string verbatim
                // via fcItem.put("arguments", ...).
                String fcDoneData = "{\"type\":\"response.output_item.done\",\"output_index\":" + outputIndex +
                    ",\"item\":{\"type\":\"function_call\",\"id\":\"" + fcId +
                    "\",\"name\":\"" + escapeJson(toolCall.getName()) +
                    "\",\"arguments\":\"" + escapeJson(args) + "\"}}";
                events.add(sseEvent().withEvent("response.output_item.done").withData(fcDoneData));

                outputIndex++;
            }
        }

        // 5. response.completed — mirror cached/reasoning token details into the completed usage
        // when set (non-null, non-zero), matching the non-streaming encode(). Omitted when unset so
        // existing streaming fixtures stay byte-identical.
        StringBuilder completedUsage = new StringBuilder();
        completedUsage.append("{\"input_tokens\":").append(inputTokens)
            .append(",\"output_tokens\":").append(outputTokens)
            .append(",\"total_tokens\":").append(inputTokens + outputTokens);
        if (completionUsage != null) {
            Integer cachedInputTokens = completionUsage.getCachedInputTokens();
            if (cachedInputTokens != null && cachedInputTokens != 0) {
                completedUsage.append(",\"input_tokens_details\":{\"cached_tokens\":").append(cachedInputTokens).append("}");
            }
            Integer reasoningTokens = completionUsage.getReasoningTokens();
            if (reasoningTokens != null && reasoningTokens != 0) {
                completedUsage.append(",\"output_tokens_details\":{\"reasoning_tokens\":").append(reasoningTokens).append("}");
            }
        }
        completedUsage.append("}");
        String completedData = "{\"type\":\"response.completed\",\"response\":{\"id\":\"" + responseId +
            "\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"" + escapeJson(modelName) +
            "\",\"usage\":" + completedUsage + "}}";
        events.add(sseEvent().withEvent("response.completed").withData(completedData));

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

            JsonNode inputNode = root.get("input");
            if (inputNode == null) {
                return ParsedConversation.empty();
            }

            List<ParsedMessage> parsed = new ArrayList<>();

            if (inputNode.isTextual()) {
                // Single string input treated as user message
                parsed.add(new ParsedMessage(
                    ParsedMessage.Role.USER,
                    inputNode.asText(""),
                    null,
                    null
                ));
            } else if (inputNode.isArray()) {
                for (JsonNode item : inputNode) {
                    String type = item.has("type") ? item.get("type").asText("") : "";

                    if ("function_call_output".equals(type)) {
                        // Tool result
                        String callId = item.has("call_id") ? item.get("call_id").asText("") : "";
                        String output = item.has("output") ? item.get("output").asText("") : "";
                        Map<String, String> toolResults = new LinkedHashMap<>();
                        toolResults.put(callId, output);
                        parsed.add(new ParsedMessage(
                            ParsedMessage.Role.TOOL,
                            output,
                            null,
                            toolResults
                        ));
                    } else if ("function_call".equals(type)) {
                        // Tool call from prior response (assistant turn)
                        String fcId = item.has("id") ? item.get("id").asText("") : null;
                        String name = item.has("name") ? item.get("name").asText("") : "";
                        String arguments = item.has("arguments") ? item.get("arguments").asText("") : "{}";
                        ToolUse tu = ToolUse.toolUse(name).withArguments(arguments);
                        if (fcId != null && !fcId.isEmpty()) {
                            tu.withId(fcId);
                        }
                        List<ToolUse> toolCalls = new ArrayList<>();
                        toolCalls.add(tu);
                        parsed.add(new ParsedMessage(
                            ParsedMessage.Role.ASSISTANT,
                            "",
                            toolCalls,
                            null
                        ));
                    } else {
                        // Standard message
                        String rawRole = item.has("role") ? item.get("role").asText("") : "user";
                        String textContent = "";
                        JsonNode contentNode = item.get("content");
                        if (contentNode != null) {
                            if (contentNode.isTextual()) {
                                textContent = contentNode.asText("");
                            } else if (contentNode.isArray()) {
                                StringBuilder sb = new StringBuilder();
                                for (JsonNode part : contentNode) {
                                    String partType = part.has("type") ? part.get("type").asText("") : "";
                                    if ("output_text".equals(partType) || "input_text".equals(partType) || "text".equals(partType)) {
                                        sb.append(part.has("text") ? part.get("text").asText("") : "");
                                    }
                                }
                                textContent = sb.toString();
                            }
                        }

                        ParsedMessage.Role role = mapRole(rawRole);
                        parsed.add(new ParsedMessage(role, textContent, null, null));
                    }
                }
            } else {
                return ParsedConversation.empty();
            }

            // Server-side state chaining: when the request carries a previous_response_id,
            // prepend the stored prior conversation so matchers and usage inference see the
            // full dialogue (the Responses API sends only the new turn's input plus the id).
            // No-op when there is no previous_response_id or the id is unknown/not stored.
            List<ParsedMessage> priorMessages =
                OpenAiResponsesStore.getInstance().priorMessagesFor(body);
            if (!priorMessages.isEmpty()) {
                List<ParsedMessage> chained = new ArrayList<>(priorMessages);
                chained.addAll(parsed);
                return ParsedConversation.of(chained);
            }

            return ParsedConversation.of(parsed);
        } catch (Exception e) {
            return ParsedConversation.empty();
        }
    }

    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        throw new UnsupportedOperationException("OpenAI Responses API does not expose an embeddings endpoint");
    }

    private static ParsedMessage.Role mapRole(String rawRole) {
        if (rawRole == null) {
            return ParsedMessage.Role.USER;
        }
        switch (rawRole.toLowerCase()) {
            case "assistant":
                return ParsedMessage.Role.ASSISTANT;
            case "user":
                return ParsedMessage.Role.USER;
            case "system":
                return ParsedMessage.Role.SYSTEM;
            case "tool":
                return ParsedMessage.Role.TOOL;
            default:
                return ParsedMessage.Role.USER;
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
