package org.mockserver.llm.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange;
import org.mockserver.llm.client.LlmClient;
import org.mockserver.llm.client.LlmClientRegistry;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.ToolUse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.List;
import java.util.Optional;

/**
 * Turns captured LLM sessions (the same {@link CapturedExchange request/response
 * pairs} the optimisation report is built from) into datasets that off-the-shelf
 * eval / fine-tune tooling consumes:
 * <ul>
 *   <li>{@link DatasetFormat#OPENAI_EVALS} — OpenAI-evals JSONL: one sample per
 *       line, {@code {"input":[messages…],"ideal":"<assistant response>"}}.</li>
 *   <li>{@link DatasetFormat#FINE_TUNE} — chat fine-tune JSONL: one conversation
 *       per line, {@code {"messages":[…,{"role":"assistant","content":…}]}} (the
 *       prompt messages plus the captured assistant turn).</li>
 *   <li>{@link DatasetFormat#PROMPTFOO} — a promptfoo test-suite JSON document:
 *       {@code {"tests":[{"vars":{"messages":[…]},"assert":[{"type":"equals",
 *       "value":"<assistant response>"}]}]}}.</li>
 * </ul>
 * <p>
 * The prompt messages are decoded from the request via the provider
 * {@link ProviderCodec}; the "ideal"/expected/assistant turn is decoded from the
 * captured response via the provider {@link LlmClient}. Both the request and the
 * response are pushed through {@link FixtureRedactor} <em>before</em> any content
 * is emitted, and free-text credential shapes are additionally masked with
 * {@link LlmOptimisationBriefRenderer#maskSecrets}, so an exported dataset never
 * leaks Authorization / api-key headers, configured body fields, or a key a user
 * pasted into a prompt.
 * <p>
 * Pure and deterministic — no network, no LLM call. Lives in mockserver-core so
 * the control-plane REST endpoint and the MCP tool share one implementation.
 */
public class LlmDatasetExporter {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    /** The supported dataset export formats. */
    public enum DatasetFormat {
        OPENAI_EVALS,
        FINE_TUNE,
        PROMPTFOO;

        /**
         * Resolve a wire {@code format=} value (as sent by the REST endpoint / MCP
         * tool) to a {@link DatasetFormat}, or empty when it is not a dataset
         * format. Accepts hyphenated and collapsed spellings
         * ({@code openai-evals}/{@code openai_evals}/{@code evals},
         * {@code fine-tune}/{@code finetune}, {@code promptfoo}).
         */
        public static Optional<DatasetFormat> fromWire(String format) {
            if (format == null) {
                return Optional.empty();
            }
            String f = format.trim().toLowerCase().replace('_', '-');
            switch (f) {
                case "openai-evals":
                case "evals":
                    return Optional.of(OPENAI_EVALS);
                case "fine-tune":
                case "finetune":
                    return Optional.of(FINE_TUNE);
                case "promptfoo":
                    return Optional.of(PROMPTFOO);
                default:
                    return Optional.empty();
            }
        }
    }

    /**
     * Export the given exchanges in the requested format. Non-LLM exchanges (and
     * those whose request cannot be decoded to any message) are skipped, mirroring
     * the optimisation report's inclusion rule. A null/empty input yields an empty
     * JSONL document (for the JSONL formats) or an empty {@code {"tests":[]}}
     * document (promptfoo), so callers never have to null-check the result.
     *
     * @param exchanges the captured request/response pairs (chronological)
     * @param format    the target dataset format
     * @param redactor  the redactor applied to every request and response before emission
     */
    public String export(List<CapturedExchange> exchanges, DatasetFormat format, FixtureRedactor redactor) {
        FixtureRedactor effectiveRedactor = redactor != null ? redactor : new FixtureRedactor();
        if (format == DatasetFormat.PROMPTFOO) {
            return exportPromptfoo(exchanges, effectiveRedactor);
        }
        return exportJsonl(exchanges, format, effectiveRedactor);
    }

    private String exportJsonl(List<CapturedExchange> exchanges, DatasetFormat format, FixtureRedactor redactor) {
        StringBuilder out = new StringBuilder();
        if (exchanges != null) {
            for (CapturedExchange exchange : exchanges) {
                Sample sample = toSample(exchange, redactor);
                if (sample == null) {
                    continue;
                }
                ObjectNode line;
                if (format == DatasetFormat.FINE_TUNE) {
                    line = OBJECT_MAPPER.createObjectNode();
                    ArrayNode messages = line.putArray("messages");
                    for (ObjectNode message : sample.inputMessages) {
                        messages.add(message);
                    }
                    messages.add(sample.assistantMessage());
                } else { // OPENAI_EVALS
                    line = OBJECT_MAPPER.createObjectNode();
                    ArrayNode input = line.putArray("input");
                    for (ObjectNode message : sample.inputMessages) {
                        input.add(message);
                    }
                    line.put("ideal", sample.idealText);
                }
                try {
                    out.append(OBJECT_MAPPER.writeValueAsString(line)).append("\n");
                } catch (Exception e) {
                    // a fully in-memory ObjectNode never fails to serialise; skip defensively
                }
            }
        }
        return out.toString();
    }

    private String exportPromptfoo(List<CapturedExchange> exchanges, FixtureRedactor redactor) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ArrayNode tests = root.putArray("tests");
        int index = 0;
        if (exchanges != null) {
            for (CapturedExchange exchange : exchanges) {
                Sample sample = toSample(exchange, redactor);
                if (sample == null) {
                    continue;
                }
                ObjectNode test = tests.addObject();
                test.put("description", "captured sample " + index++);
                ObjectNode vars = test.putObject("vars");
                ArrayNode messages = vars.putArray("messages");
                for (ObjectNode message : sample.inputMessages) {
                    messages.add(message);
                }
                ArrayNode asserts = test.putArray("assert");
                ObjectNode expect = asserts.addObject();
                expect.put("type", "equals");
                expect.put("value", sample.idealText);
            }
        }
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"tests\":[]}";
        }
    }

    /**
     * Decode one captured exchange into a dataset sample (redacted input messages
     * plus the assistant "ideal" response), or null when the exchange is not LLM
     * traffic or the request decodes to no messages.
     */
    private Sample toSample(CapturedExchange exchange, FixtureRedactor redactor) {
        if (exchange == null || exchange.getRequest() == null || exchange.getResponse() == null) {
            return null;
        }
        Optional<Provider> providerOpt =
            LlmProviderSniffer.detectForAnalysis(exchange.getRequest(), exchange.getResponse());
        if (!providerOpt.isPresent()) {
            return null;
        }
        Provider provider = providerOpt.get();

        HttpRequest redactedRequest = redactRequest(redactor, exchange.getRequest());
        HttpResponse redactedResponse = redactor.redactResponseObject(exchange.getResponse());

        Optional<ProviderCodec> codecOpt = ProviderCodecRegistry.getInstance().lookup(provider);
        if (!codecOpt.isPresent()) {
            return null;
        }
        ParsedConversation conversation;
        try {
            conversation = codecOpt.get().decode(redactedRequest);
        } catch (Exception e) {
            return null;
        }
        if (conversation == null || conversation.getMessages().isEmpty()) {
            return null;
        }

        Sample sample = new Sample();
        for (ParsedMessage message : conversation.getMessages()) {
            sample.inputMessages.add(toMessageNode(message));
        }
        sample.idealText = LlmOptimisationBriefRenderer.maskSecrets(
            emptyToBlank(assistantText(redactedResponse, provider)));
        sample.assistantToolCalls = assistantToolCalls(redactedResponse, provider);
        return sample;
    }

    private ObjectNode toMessageNode(ParsedMessage message) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("role", message.getRole().name().toLowerCase());
        String content = message.getTextContent();
        if ((content == null || content.isEmpty()) && message.getRole() == ParsedMessage.Role.TOOL) {
            content = String.join("\n", message.getToolResults().values());
        }
        node.put("content", LlmOptimisationBriefRenderer.maskSecrets(emptyToBlank(content)));
        return node;
    }

    /** Build the assistant message appended to a fine-tune conversation. */
    private final class Sample {
        final java.util.List<ObjectNode> inputMessages = new java.util.ArrayList<>();
        String idealText = "";
        java.util.List<ToolUse> assistantToolCalls = java.util.Collections.emptyList();

        ObjectNode assistantMessage() {
            ObjectNode assistant = OBJECT_MAPPER.createObjectNode();
            assistant.put("role", "assistant");
            assistant.put("content", idealText);
            if (assistantToolCalls != null && !assistantToolCalls.isEmpty()) {
                ArrayNode toolCalls = assistant.putArray("tool_calls");
                for (ToolUse toolCall : assistantToolCalls) {
                    ObjectNode tc = toolCalls.addObject();
                    if (toolCall.getId() != null) {
                        tc.put("id", toolCall.getId());
                    }
                    tc.put("type", "function");
                    ObjectNode function = tc.putObject("function");
                    function.put("name", emptyToBlank(toolCall.getName()));
                    function.put("arguments", LlmOptimisationBriefRenderer.maskSecrets(
                        emptyToBlank(toolCall.getArguments())));
                }
            }
            return assistant;
        }
    }

    private String assistantText(HttpResponse response, Provider provider) {
        Completion completion = parseCompletion(response, provider);
        return completion != null ? completion.getText() : null;
    }

    private java.util.List<ToolUse> assistantToolCalls(HttpResponse response, Provider provider) {
        Completion completion = parseCompletion(response, provider);
        return completion != null && completion.getToolCalls() != null
            ? completion.getToolCalls() : java.util.Collections.emptyList();
    }

    private Completion parseCompletion(HttpResponse response, Provider provider) {
        if (response == null) {
            return null;
        }
        Optional<LlmClient> clientOpt = LlmClientRegistry.getInstance().lookup(provider);
        if (!clientOpt.isPresent()) {
            return null;
        }
        try {
            return clientOpt.get().parseCompletionResponse(response);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Redact the request's sensitive headers, query parameters and configured body
     * fields via {@link FixtureRedactor}, returning the redacted {@link HttpRequest}.
     */
    private static HttpRequest redactRequest(FixtureRedactor redactor, HttpRequest request) {
        org.mockserver.model.RequestDefinition def = redactor.redactRequestDefinition(request);
        return def instanceof HttpRequest ? (HttpRequest) def : request;
    }

    private static String emptyToBlank(String value) {
        return value == null ? "" : value;
    }
}
