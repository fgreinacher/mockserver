package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.llm.codec.BedrockEventStreamEncoder;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.mockserver.client.LlmConversationBuilder.conversation;
import static org.mockserver.client.LlmMockBuilder.llmMock;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.Provider.ANTHROPIC;
import static org.mockserver.model.Provider.AZURE_OPENAI;
import static org.mockserver.model.Provider.BEDROCK;
import static org.mockserver.model.Provider.GEMINI;
import static org.mockserver.model.Provider.OLLAMA;
import static org.mockserver.model.Provider.OPENAI;
import static org.mockserver.model.Provider.OPENAI_RESPONSES;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end agent loop test for all 7 providers. For each provider:
 * <ul>
 *   <li>Turn 1: register a response that returns a tool_use / function_call</li>
 *   <li>Turn 2: after the client sends a tool_result, return the final answer</li>
 * </ul>
 * Non-streaming only. Each test exercises the full codec encode path,
 * the scenario state machine, and the conversation-aware matcher pipeline.
 */
public class LlmAgentLoopE2eTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static int mockServerPort;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    // ---- Anthropic ----

    @Test
    public void shouldCompleteAgentLoopForAnthropic() throws Exception {
        conversation()
            .withPath("/v1/messages")
            .withProvider(ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withText("Let me search for that.")
                    .withToolCall(toolUse("search").withArguments("{\"q\":\"weather paris\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("search")
                .respondingWith(completion()
                    .withText("It is 18C and sunny in Paris.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        // Turn 1
        String turn1Body = "{\"model\":\"claude-sonnet-4-20250514\",\"messages\":[{\"role\":\"user\",\"content\":\"What is the weather in Paris?\"}]}";
        String turn1Response = sendPost("/v1/messages", turn1Body);
        assertThat(turn1Response, containsString("200"));
        JsonNode turn1 = OBJECT_MAPPER.readTree(extractJsonBody(turn1Response));
        assertThat(turn1.get("type").asText(), is("message"));
        assertThat(turn1.get("stop_reason").asText(), is("tool_use"));
        assertToolUsePresent(turn1, "search");

        // Turn 2
        String turn2Body = "{\"model\":\"claude-sonnet-4-20250514\",\"messages\":["
            + "{\"role\":\"user\",\"content\":\"What is the weather in Paris?\"},"
            + "{\"role\":\"assistant\",\"content\":["
            + "{\"type\":\"text\",\"text\":\"Let me search for that.\"},"
            + "{\"type\":\"tool_use\",\"id\":\"toolu_123\",\"name\":\"search\",\"input\":{\"q\":\"weather paris\"}}"
            + "]},"
            + "{\"role\":\"user\",\"content\":["
            + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_123\",\"content\":\"18C and sunny\"}"
            + "]}"
            + "]}";
        String turn2Response = sendPost("/v1/messages", turn2Body);
        assertThat(turn2Response, containsString("200"));
        JsonNode turn2 = OBJECT_MAPPER.readTree(extractJsonBody(turn2Response));
        assertThat(turn2.get("type").asText(), is("message"));
        assertThat(turn2.get("stop_reason").asText(), is("end_turn"));
        assertTextBlockContains(turn2, "18C and sunny");
    }

    // ---- OpenAI Chat Completions ----

    @Test
    public void shouldCompleteAgentLoopForOpenAi() throws Exception {
        conversation()
            .withPath("/v1/chat/completions")
            .withProvider(OPENAI)
            .withModel("gpt-4o")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("get_weather")
                .respondingWith(completion()
                    .withText("The weather in Paris is 18C and sunny.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        // Turn 1
        String turn1Body = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"What is the weather in Paris?\"}]}";
        String turn1Response = sendPost("/v1/chat/completions", turn1Body);
        assertThat(turn1Response, containsString("200"));
        JsonNode turn1 = OBJECT_MAPPER.readTree(extractJsonBody(turn1Response));
        assertThat(turn1.get("object").asText(), is("chat.completion"));
        assertThat(turn1.path("choices").get(0).get("finish_reason").asText(), is("tool_calls"));
        assertOpenAiToolCallPresent(turn1, "get_weather");

        // Turn 2 with tool result
        String callId = turn1.path("choices").get(0).path("message").path("tool_calls").get(0).path("id").asText();
        String turn2Body = "{\"model\":\"gpt-4o\",\"messages\":["
            + "{\"role\":\"user\",\"content\":\"What is the weather in Paris?\"},"
            + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
            + "{\"id\":\"" + callId + "\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}"
            + "]},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"" + callId + "\",\"content\":\"18C and sunny\"}"
            + "]}";
        String turn2Response = sendPost("/v1/chat/completions", turn2Body);
        assertThat(turn2Response, containsString("200"));
        JsonNode turn2 = OBJECT_MAPPER.readTree(extractJsonBody(turn2Response));
        assertThat(turn2.path("choices").get(0).get("finish_reason").asText(), is("stop"));
        assertThat(turn2.path("choices").get(0).path("message").get("content").asText(), containsString("18C and sunny"));
    }

    // ---- OpenAI Responses ----

    @Test
    public void shouldCompleteAgentLoopForOpenAiResponses() throws Exception {
        conversation()
            .withPath("/v1/responses")
            .withProvider(OPENAI_RESPONSES)
            .withModel("gpt-4o")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("get_weather")
                .respondingWith(completion()
                    .withText("It is 18C and sunny in Paris.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        // Turn 1: Responses API uses "input" array instead of "messages"
        String turn1Body = "{\"model\":\"gpt-4o\",\"input\":[{\"role\":\"user\",\"content\":\"What is the weather in Paris?\"}]}";
        String turn1Response = sendPost("/v1/responses", turn1Body);
        assertThat(turn1Response, containsString("200"));
        JsonNode turn1 = OBJECT_MAPPER.readTree(extractJsonBody(turn1Response));
        assertThat(turn1.get("object").asText(), is("response"));
        // Status is the field Responses-API clients check to know a response is finished.
        assertThat(turn1.get("status").asText(), is("completed"));
        // Verify function_call in output
        boolean hasFunctionCall = false;
        for (JsonNode item : turn1.get("output")) {
            if ("function_call".equals(item.get("type").asText())) {
                assertThat(item.get("name").asText(), is("get_weather"));
                hasFunctionCall = true;
            }
        }
        assertThat("Turn 1 should have function_call output", hasFunctionCall, is(true));

        // Turn 2: send function_call_output
        String fcId = "";
        for (JsonNode item : turn1.get("output")) {
            if ("function_call".equals(item.get("type").asText())) {
                fcId = item.get("id").asText();
            }
        }
        String turn2Body = "{\"model\":\"gpt-4o\",\"input\":["
            + "{\"role\":\"user\",\"content\":\"What is the weather in Paris?\"},"
            + "{\"type\":\"function_call\",\"id\":\"" + fcId + "\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"},"
            + "{\"type\":\"function_call_output\",\"call_id\":\"" + fcId + "\",\"output\":\"18C and sunny\"}"
            + "]}";
        String turn2Response = sendPost("/v1/responses", turn2Body);
        assertThat(turn2Response, containsString("200"));
        JsonNode turn2 = OBJECT_MAPPER.readTree(extractJsonBody(turn2Response));
        assertThat(turn2.get("object").asText(), is("response"));
        assertThat(turn2.get("status").asText(), is("completed"));
        // Verify text in output
        boolean hasText = false;
        for (JsonNode item : turn2.get("output")) {
            if ("message".equals(item.get("type").asText())) {
                for (JsonNode content : item.get("content")) {
                    if ("output_text".equals(content.get("type").asText())) {
                        assertThat(content.get("text").asText(), containsString("18C and sunny"));
                        hasText = true;
                    }
                }
            }
        }
        assertThat("Turn 2 should have text output", hasText, is(true));
    }

    // ---- Gemini ----

    @Test
    public void shouldCompleteAgentLoopForGemini() throws Exception {
        // Gemini uses "contents" and "parts" instead of "messages".
        // Turn ordering is handled by the scenario state machine; conversation
        // predicates are verified separately in LlmConversationMatcherTest.
        conversation()
            .withPath("/v1beta/models/gemini-2.0-flash/generateContent")
            .withProvider(GEMINI)
            .withModel("gemini-2.0-flash")
            .turn()
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
                    .withStopReason("end_turn"))
            .andThen()
            .turn()
                .respondingWith(completion()
                    .withText("It is 18C and sunny in Paris.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        // Turn 1: Gemini uses "contents" array with "parts"
        String turn1Body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"What is the weather in Paris?\"}]}]}";
        String turn1Response = sendPost("/v1beta/models/gemini-2.0-flash/generateContent", turn1Body);
        assertThat(turn1Response, containsString("200"));
        JsonNode turn1 = OBJECT_MAPPER.readTree(extractJsonBody(turn1Response));
        assertThat(turn1.has("candidates"), is(true));
        // Gemini collapses every stop reason without a dedicated tool-call category
        // into STOP (see GeminiCodec.mapFinishReason); pin the wire contract.
        assertThat(turn1.path("candidates").get(0).get("finishReason").asText(), is("STOP"));
        // Verify functionCall in parts
        boolean hasFc = false;
        for (JsonNode part : turn1.path("candidates").get(0).path("content").path("parts")) {
            if (part.has("functionCall")) {
                assertThat(part.path("functionCall").get("name").asText(), is("get_weather"));
                hasFc = true;
            }
        }
        assertThat("Turn 1 should have functionCall part", hasFc, is(true));

        // Turn 2: send functionResponse
        String turn2Body = "{\"contents\":["
            + "{\"role\":\"user\",\"parts\":[{\"text\":\"What is the weather in Paris?\"}]},"
            + "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"Paris\"}}}]},"
            + "{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":\"get_weather\",\"response\":\"18C and sunny\"}}]}"
            + "]}";
        String turn2Response = sendPost("/v1beta/models/gemini-2.0-flash/generateContent", turn2Body);
        assertThat(turn2Response, containsString("200"));
        JsonNode turn2 = OBJECT_MAPPER.readTree(extractJsonBody(turn2Response));
        assertThat(turn2.has("candidates"), is(true));
        assertThat(turn2.path("candidates").get(0).get("finishReason").asText(), is("STOP"));
        boolean hasTextPart = false;
        for (JsonNode part : turn2.path("candidates").get(0).path("content").path("parts")) {
            if (part.has("text")) {
                assertThat(part.get("text").asText(), containsString("18C and sunny"));
                hasTextPart = true;
            }
        }
        assertThat("Turn 2 should have text part", hasTextPart, is(true));
    }

    @Test
    public void shouldMatchContainsToolResultForGeminiEndToEnd() throws Exception {
        // Reproduction for the documented E2E false-negative: drive turn 2 via the
        // containsToolResultFor predicate (not scenario ordering) for Gemini.
        conversation()
            .withPath("/v1beta/models/gemini-2.0-flash/generateContent")
            .withProvider(GEMINI)
            .withModel("gemini-2.0-flash")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
                    .withStopReason("end_turn"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("get_weather")
                .respondingWith(completion()
                    .withText("It is 18C and sunny in Paris.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        String turn1Body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"What is the weather in Paris?\"}]}]}";
        sendPost("/v1beta/models/gemini-2.0-flash/generateContent", turn1Body);

        String turn2Body = "{\"contents\":["
            + "{\"role\":\"user\",\"parts\":[{\"text\":\"What is the weather in Paris?\"}]},"
            + "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"Paris\"}}}]},"
            + "{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":\"get_weather\",\"response\":\"18C and sunny\"}}]}"
            + "]}";
        String turn2Response = sendPost("/v1beta/models/gemini-2.0-flash/generateContent", turn2Body);
        JsonNode turn2 = OBJECT_MAPPER.readTree(extractJsonBody(turn2Response));
        boolean hasTextPart = false;
        for (JsonNode part : turn2.path("candidates").get(0).path("content").path("parts")) {
            if (part.has("text") && part.get("text").asText().contains("18C and sunny")) {
                hasTextPart = true;
            }
        }
        assertThat("Turn 2 (containsToolResultFor) should return the final answer", hasTextPart, is(true));
    }

    // ---- AWS Bedrock ----

    @Test
    public void shouldCompleteAgentLoopForBedrock() throws Exception {
        conversation()
            .withPath("/model/anthropic.claude-3-5-sonnet-20241022-v2:0/invoke")
            .withProvider(BEDROCK)
            .withModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withText("Let me search.")
                    .withToolCall(toolUse("search").withArguments("{\"q\":\"weather\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("search")
                .respondingWith(completion()
                    .withText("It is 18C and sunny in Paris.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        // Bedrock uses the same Anthropic Messages body format
        String turn1Body = "{\"messages\":[{\"role\":\"user\",\"content\":\"What is the weather?\"}]}";
        String turn1Response = sendPost("/model/anthropic.claude-3-5-sonnet-20241022-v2:0/invoke", turn1Body);
        assertThat(turn1Response, containsString("200"));
        JsonNode turn1 = OBJECT_MAPPER.readTree(extractJsonBody(turn1Response));
        assertThat(turn1.get("type").asText(), is("message"));
        assertThat(turn1.get("stop_reason").asText(), is("tool_use"));
        assertToolUsePresent(turn1, "search");

        // Turn 2
        String turn2Body = "{\"messages\":["
            + "{\"role\":\"user\",\"content\":\"What is the weather?\"},"
            + "{\"role\":\"assistant\",\"content\":["
            + "{\"type\":\"text\",\"text\":\"Let me search.\"},"
            + "{\"type\":\"tool_use\",\"id\":\"toolu_abc\",\"name\":\"search\",\"input\":{\"q\":\"weather\"}}"
            + "]},"
            + "{\"role\":\"user\",\"content\":["
            + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_abc\",\"content\":\"18C and sunny\"}"
            + "]}"
            + "]}";
        String turn2Response = sendPost("/model/anthropic.claude-3-5-sonnet-20241022-v2:0/invoke", turn2Body);
        assertThat(turn2Response, containsString("200"));
        JsonNode turn2 = OBJECT_MAPPER.readTree(extractJsonBody(turn2Response));
        assertThat(turn2.get("stop_reason").asText(), is("end_turn"));
        assertTextBlockContains(turn2, "18C and sunny");
    }

    // ---- Azure OpenAI ----

    @Test
    public void shouldCompleteAgentLoopForAzureOpenAi() throws Exception {
        conversation()
            .withPath("/openai/deployments/my-deployment/chat/completions")
            .withProvider(AZURE_OPENAI)
            .withModel("gpt-4o")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("get_weather")
                .respondingWith(completion()
                    .withText("18C and sunny in Paris.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        // Azure OpenAI uses the same request format as OpenAI Chat Completions
        String turn1Body = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"What is the weather?\"}]}";
        String turn1Response = sendPost("/openai/deployments/my-deployment/chat/completions", turn1Body);
        assertThat(turn1Response, containsString("200"));
        JsonNode turn1 = OBJECT_MAPPER.readTree(extractJsonBody(turn1Response));
        assertThat(turn1.get("object").asText(), is("chat.completion"));
        assertThat(turn1.path("choices").get(0).get("finish_reason").asText(), is("tool_calls"));
        assertOpenAiToolCallPresent(turn1, "get_weather");

        // Turn 2
        String callId = turn1.path("choices").get(0).path("message").path("tool_calls").get(0).path("id").asText();
        String turn2Body = "{\"model\":\"gpt-4o\",\"messages\":["
            + "{\"role\":\"user\",\"content\":\"What is the weather?\"},"
            + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
            + "{\"id\":\"" + callId + "\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}"
            + "]},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"" + callId + "\",\"content\":\"18C and sunny\"}"
            + "]}";
        String turn2Response = sendPost("/openai/deployments/my-deployment/chat/completions", turn2Body);
        assertThat(turn2Response, containsString("200"));
        JsonNode turn2 = OBJECT_MAPPER.readTree(extractJsonBody(turn2Response));
        assertThat(turn2.path("choices").get(0).get("finish_reason").asText(), is("stop"));
        assertThat(turn2.path("choices").get(0).path("message").get("content").asText(), containsString("18C and sunny"));
    }

    // ---- Ollama ----

    @Test
    public void shouldCompleteAgentLoopForOllama() throws Exception {
        conversation()
            .withPath("/api/chat")
            .withProvider(OLLAMA)
            .withModel("llama3.2")
            .turn()
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .respondingWith(completion()
                    .withText("It is 18C and sunny in Paris.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        // Turn 1: Ollama uses "messages" array like OpenAI
        String turn1Body = "{\"model\":\"llama3.2\",\"messages\":[{\"role\":\"user\",\"content\":\"What is the weather?\"}]}";
        String turn1Response = sendPost("/api/chat", turn1Body);
        assertThat(turn1Response, containsString("200"));
        JsonNode turn1 = OBJECT_MAPPER.readTree(extractJsonBody(turn1Response));
        assertThat(turn1.get("done").asBoolean(), is(true));
        // Verify tool_calls in message
        boolean hasToolCall = false;
        for (JsonNode tc : turn1.path("message").path("tool_calls")) {
            if ("get_weather".equals(tc.path("function").path("name").asText())) {
                hasToolCall = true;
            }
        }
        assertThat("Ollama turn 1 should have tool_calls", hasToolCall, is(true));

        // Turn 2: Ollama tool results sent as role=tool messages
        String turn2Body = "{\"model\":\"llama3.2\",\"messages\":["
            + "{\"role\":\"user\",\"content\":\"What is the weather?\"},"
            + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
            + "{\"function\":{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Paris\"}}}"
            + "]},"
            + "{\"role\":\"tool\",\"content\":\"18C and sunny\"}"
            + "]}";
        String turn2Response = sendPost("/api/chat", turn2Body);
        assertThat(turn2Response, containsString("200"));
        JsonNode turn2 = OBJECT_MAPPER.readTree(extractJsonBody(turn2Response));
        assertThat(turn2.get("done").asBoolean(), is(true));
        assertThat(turn2.path("message").get("content").asText(), containsString("18C and sunny"));
    }

    @Test
    public void shouldMatchContainsToolResultForOllamaEndToEnd() throws Exception {
        // Reproduction for the documented E2E false-negative: drive turn 2 via the
        // containsToolResultFor predicate (not scenario ordering) for Ollama.
        conversation()
            .withPath("/api/chat")
            .withProvider(OLLAMA)
            .withModel("llama3.2")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("get_weather")
                .respondingWith(completion()
                    .withText("It is 18C and sunny in Paris.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        String turn1Body = "{\"model\":\"llama3.2\",\"messages\":[{\"role\":\"user\",\"content\":\"What is the weather?\"}]}";
        sendPost("/api/chat", turn1Body);

        String turn2Body = "{\"model\":\"llama3.2\",\"messages\":["
            + "{\"role\":\"user\",\"content\":\"What is the weather?\"},"
            + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
            + "{\"function\":{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Paris\"}}}"
            + "]},"
            + "{\"role\":\"tool\",\"content\":\"18C and sunny\"}"
            + "]}";
        String turn2Response = sendPost("/api/chat", turn2Body);
        JsonNode turn2 = OBJECT_MAPPER.readTree(extractJsonBody(turn2Response));
        assertThat("Turn 2 (containsToolResultFor) should return the final answer",
            turn2.path("message").get("content").asText(), containsString("18C and sunny"));
    }

    // ---- Streaming E2E ----

    @Test
    public void shouldStreamAnthropicResponseThroughNettyPipeline() throws Exception {
        // Gap 5: verify streaming reaches the client correctly via the Netty
        // pipeline, not just the codec unit tests
        llmMock("/v1/messages/stream")
            .withProvider(ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .respondingWith(completion()
                .withText("Hello streaming world")
                .withStreaming(true))
            .applyTo(mockServerClient);

        String body = "{\"model\":\"claude-sonnet-4-20250514\",\"stream\":true,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        String rawResponse = sendPost("/v1/messages/stream", body);

        // Should get SSE events, not a regular JSON body
        assertThat(rawResponse, containsString("200"));
        assertThat(rawResponse, containsString("text/event-stream"));
        assertThat(rawResponse, containsString("event: message_start"));
        assertThat(rawResponse, containsString("event: content_block_delta"));
        assertThat(rawResponse, containsString("event: message_stop"));

        // The streamed text arrives as (subword-sized by default) text_delta fragments,
        // so it is not present as contiguous substrings on the raw SSE stream. Reconstruct
        // it from the delta events before asserting on the content.
        StringBuilder reconstructed = new StringBuilder();
        java.util.regex.Matcher deltaText =
            java.util.regex.Pattern.compile("\"text\":\"([^\"]*)\"").matcher(rawResponse);
        while (deltaText.find()) {
            reconstructed.append(deltaText.group(1));
        }
        assertThat(reconstructed.toString(), containsString("Hello"));
        assertThat(reconstructed.toString(), containsString("streaming"));
        assertThat(reconstructed.toString(), containsString("world"));
    }

    @Test
    public void shouldStreamOpenAiResponseThroughNettyPipeline() throws Exception {
        llmMock("/v1/chat/completions/stream")
            .withProvider(OPENAI)
            .withModel("gpt-4o")
            .respondingWith(completion()
                .withText("Hello from streaming OpenAI")
                .withStreaming(true))
            .applyTo(mockServerClient);

        String body = "{\"model\":\"gpt-4o\",\"stream\":true,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        String rawResponse = sendPost("/v1/chat/completions/stream", body);

        // OpenAI streaming uses data: lines with chat.completion.chunk objects
        assertThat(rawResponse, containsString("200"));
        assertThat(rawResponse, containsString("text/event-stream"));
        assertThat(rawResponse, containsString("chat.completion.chunk"));
        assertThat(rawResponse, containsString("[DONE]"));
    }

    @Test
    public void shouldStreamGeminiResponseThroughNettyPipeline() throws Exception {
        // Gap 56: Gemini streaming physics over the wire — SSE framing with the
        // native candidates/parts delta shape (not just codec unit + golden JSONL).
        String completionText = "Hello from streaming Gemini";
        llmMock("/v1beta/models/gemini-1.5-pro:streamGenerateContent")
            .withProvider(GEMINI)
            .withModel("gemini-1.5-pro")
            .respondingWith(completion()
                .withText(completionText)
                .withStreaming(true))
            .applyTo(mockServerClient);

        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Hi\"}]}]}";
        String rawResponse = sendPost("/v1beta/models/gemini-1.5-pro:streamGenerateContent", body);

        // (a) Gemini streams as Server-Sent Events (text/event-stream).
        assertThat(rawResponse, containsString("200"));
        assertThat(rawResponse, containsString("text/event-stream"));
        assertThat(rawResponse, containsString("\"candidates\""));

        // (b) The completion text arrives split across candidates[].content.parts[].text
        // deltas (subword-sized by default). Concatenating them must reconstruct the
        // completion text exactly.
        assertThat(reconstructFromJsonStringField(rawResponse, "text"), is(completionText));
    }

    @Test
    public void shouldStreamOllamaResponseThroughNettyPipeline() throws Exception {
        // Gap 56: Ollama streaming physics over the wire — NDJSON framing
        // (application/x-ndjson), one JSON object per line, message.content deltas.
        String completionText = "Hello from streaming Ollama";
        llmMock("/api/chat/stream")
            .withProvider(OLLAMA)
            .withModel("llama3.1")
            .respondingWith(completion()
                .withText(completionText)
                .withStreaming(true))
            .applyTo(mockServerClient);

        String body = "{\"model\":\"llama3.1\",\"stream\":true,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        String rawResponse = sendPost("/api/chat/stream", body);

        // (a) Ollama streams as newline-delimited JSON, not SSE.
        assertThat(rawResponse, containsString("200"));
        assertThat(rawResponse, containsString("application/x-ndjson"));
        assertThat(rawResponse, containsString("\"done\":true"));

        // (b) Each line carries a message.content delta; concatenating them reconstructs
        // the completion text exactly (the final done:true chunk contributes an empty content).
        assertThat(reconstructFromJsonStringField(rawResponse, "content"), is(completionText));
    }

    @Test
    public void shouldStreamBedrockResponseThroughNettyPipeline() throws Exception {
        // Gap 56: Bedrock streaming physics over the wire — AWS event-stream binary
        // framing (application/vnd.amazon.eventstream). Each chunk is a binary message
        // whose payload is {"bytes":"<base64(anthropicChunkJson)>"}.
        String completionText = "Hello from streaming Bedrock";
        llmMock("/model/anthropic.claude-sonnet-4-20250514-v1:0/invoke-with-response-stream")
            .withProvider(BEDROCK)
            .withModel("anthropic.claude-sonnet-4-20250514-v1:0")
            .respondingWith(completion()
                .withText(completionText)
                .withStreaming(true))
            .applyTo(mockServerClient);

        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        byte[] rawBytes = sendPostRaw("/model/anthropic.claude-sonnet-4-20250514-v1:0/invoke-with-response-stream", body);

        int headerEnd = indexOfSequence(rawBytes, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0);
        assertThat("response must have a header/body boundary", headerEnd, greaterThanOrEqualTo(0));
        String headerText = new String(rawBytes, 0, headerEnd, StandardCharsets.US_ASCII);

        // (a) Bedrock streams as AWS event-stream binary framing.
        assertThat(headerText, containsString("200"));
        assertThat(headerText.toLowerCase(), containsString("application/vnd.amazon.eventstream"));

        // The body is HTTP/1.1 chunked; de-chunk it, then decode the AWS event-stream
        // binary messages (CRC32-validated by the decoder).
        byte[] body2 = new byte[rawBytes.length - (headerEnd + 4)];
        System.arraycopy(rawBytes, headerEnd + 4, body2, 0, body2.length);
        byte[] eventStream = deChunkHttpBody(body2);

        // (b) Each event-stream message wraps a base64 Anthropic chunk; the
        // content_block_delta text_delta fragments reconstruct the completion text exactly.
        StringBuilder reconstructed = new StringBuilder();
        for (BedrockEventStreamEncoder.DecodedMessage message : BedrockEventStreamEncoder.decode(eventStream)) {
            JsonNode wrapper = OBJECT_MAPPER.readTree(message.getPayloadAsString());
            JsonNode bytesNode = wrapper.get("bytes");
            if (bytesNode == null) {
                continue;
            }
            String chunkJson = new String(Base64.getDecoder().decode(bytesNode.asText()), StandardCharsets.UTF_8);
            JsonNode chunk = OBJECT_MAPPER.readTree(chunkJson);
            JsonNode delta = chunk.path("delta");
            if ("text_delta".equals(delta.path("type").asText())) {
                reconstructed.append(delta.path("text").asText());
            }
        }
        assertThat(reconstructed.toString(), is(completionText));
    }

    // ---- Helpers ----

    /**
     * Reconstructs streamed text by concatenating every {@code "<field>":"<value>"}
     * occurrence in a raw SSE / NDJSON response. Used for text-based streaming formats
     * whose per-token deltas carry the text under a single JSON string field
     * ({@code text} for Gemini parts, {@code content} for Ollama messages).
     */
    private String reconstructFromJsonStringField(String rawResponse, String field) {
        StringBuilder reconstructed = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"" + field + "\":\"([^\"]*)\"")
            .matcher(rawResponse);
        while (matcher.find()) {
            reconstructed.append(matcher.group(1));
        }
        return reconstructed.toString();
    }

    /**
     * De-frames an HTTP/1.1 chunked message body into its raw payload bytes.
     * Each chunk is {@code <hex-length>CRLF<data>CRLF}, terminated by a zero-length chunk.
     */
    private static byte[] deChunkHttpBody(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < body.length) {
            int lineEnd = indexOfSequence(body, "\r\n".getBytes(StandardCharsets.US_ASCII), pos);
            if (lineEnd < 0) {
                break;
            }
            String sizeLine = new String(body, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim();
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) {
                sizeLine = sizeLine.substring(0, semi).trim();
            }
            if (sizeLine.isEmpty()) {
                break;
            }
            int chunkSize = Integer.parseInt(sizeLine, 16);
            pos = lineEnd + 2;
            if (chunkSize == 0) {
                break;
            }
            if (pos + chunkSize > body.length) {
                out.write(body, pos, body.length - pos);
                break;
            }
            out.write(body, pos, chunkSize);
            pos += chunkSize + 2; // skip data and its trailing CRLF
        }
        return out.toByteArray();
    }

    /** Returns the index of the first occurrence of {@code needle} in {@code haystack} at or after {@code from}, or -1. */
    private static int indexOfSequence(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private byte[] sendPostRaw(String path, String body) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            StringBuilder request = new StringBuilder();
            request.append("POST ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost:").append(mockServerPort).append("\r\n");
            request.append("Content-Type: application/json\r\n");
            request.append("Connection: close\r\n");
            request.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                output.write(bodyBytes);
            }
            output.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = socket.getInputStream().read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private void assertToolUsePresent(JsonNode anthropicResponse, String toolName) {
        boolean found = false;
        for (JsonNode block : anthropicResponse.get("content")) {
            if ("tool_use".equals(block.get("type").asText())) {
                if (toolName.equals(block.get("name").asText())) {
                    found = true;
                }
            }
        }
        assertThat("Expected tool_use block for " + toolName, found, is(true));
    }

    private void assertTextBlockContains(JsonNode anthropicResponse, String expected) {
        boolean found = false;
        for (JsonNode block : anthropicResponse.get("content")) {
            if ("text".equals(block.get("type").asText())) {
                if (block.get("text").asText().contains(expected)) {
                    found = true;
                }
            }
        }
        assertThat("Expected text block containing '" + expected + "'", found, is(true));
    }

    private void assertOpenAiToolCallPresent(JsonNode chatCompletion, String funcName) {
        JsonNode toolCalls = chatCompletion.path("choices").get(0).path("message").path("tool_calls");
        assertThat("Expected tool_calls array", toolCalls.isArray(), is(true));
        boolean found = false;
        for (JsonNode tc : toolCalls) {
            if (funcName.equals(tc.path("function").get("name").asText())) {
                found = true;
            }
        }
        assertThat("Expected tool call for " + funcName, found, is(true));
    }

    private String sendPost(String path, String body) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            StringBuilder request = new StringBuilder();
            request.append("POST ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost:").append(mockServerPort).append("\r\n");
            request.append("Content-Type: application/json\r\n");
            request.append("Connection: close\r\n");
            request.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                output.write(bodyBytes);
            }
            output.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = socket.getInputStream().read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String extractJsonBody(String httpResponse) {
        int bodyStart = httpResponse.indexOf("\r\n\r\n");
        if (bodyStart < 0) {
            bodyStart = httpResponse.indexOf("\n\n");
            if (bodyStart < 0) {
                return httpResponse;
            }
            return httpResponse.substring(bodyStart + 2);
        }
        return httpResponse.substring(bodyStart + 4);
    }
}
