package org.mockserver.llm.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class LlmDatasetExporterTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    private final LlmDatasetExporter exporter = new LlmDatasetExporter();

    @After
    public void resetConfig() {
        ConfigurationProperties.fixtureBodyRedactFields("");
    }

    private static CapturedExchange openAiExchange(String userContent, String assistantContent) {
        HttpRequest req = request().withMethod("POST").withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")
            .withHeader("Authorization", "Bearer sk-secret-abcdefghijklmnop")
            .withBody("{\"model\":\"gpt-4o-2024-08-06\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"you are helpful\"},"
                + "{\"role\":\"user\",\"content\":\"" + userContent + "\"}]}");
        HttpResponse resp = response().withStatusCode(200)
            .withBody("{\"model\":\"gpt-4o-2024-08-06\",\"choices\":[{\"message\":{\"content\":\""
                + assistantContent + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}");
        return new CapturedExchange(req, resp, null);
    }

    private static List<JsonNode> parseJsonl(String jsonl) throws Exception {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : jsonl.split("\n")) {
            if (!line.trim().isEmpty()) {
                lines.add(OBJECT_MAPPER.readTree(line));
            }
        }
        return lines;
    }

    @Test
    public void openAiEvalsJsonlRoundTripsOneSamplePerLine() throws Exception {
        List<CapturedExchange> exchanges = Arrays.asList(
            openAiExchange("hello", "hi there"),
            openAiExchange("bye", "goodbye"));

        String jsonl = exporter.export(exchanges, LlmDatasetExporter.DatasetFormat.OPENAI_EVALS, new FixtureRedactor());

        List<JsonNode> lines = parseJsonl(jsonl);
        assertEquals(2, lines.size());
        JsonNode first = lines.get(0);
        assertTrue(first.get("input").isArray());
        // system + user prompt messages are the eval input
        assertEquals(2, first.get("input").size());
        assertEquals("system", first.get("input").get(0).get("role").asText());
        assertEquals("user", first.get("input").get(1).get("role").asText());
        assertEquals("hello", first.get("input").get(1).get("content").asText());
        // the captured assistant response is the ideal/expected output
        assertEquals("hi there", first.get("ideal").asText());
        assertEquals("goodbye", lines.get(1).get("ideal").asText());
    }

    @Test
    public void fineTuneJsonlAppendsAssistantTurn() throws Exception {
        String jsonl = exporter.export(
            Collections.singletonList(openAiExchange("hello", "hi there")),
            LlmDatasetExporter.DatasetFormat.FINE_TUNE, new FixtureRedactor());

        List<JsonNode> lines = parseJsonl(jsonl);
        assertEquals(1, lines.size());
        JsonNode messages = lines.get(0).get("messages");
        assertTrue(messages.isArray());
        // system + user (prompt) + assistant (captured response)
        assertEquals(3, messages.size());
        JsonNode assistant = messages.get(2);
        assertEquals("assistant", assistant.get("role").asText());
        assertEquals("hi there", assistant.get("content").asText());
    }

    @Test
    public void promptfooProducesTestSuiteJson() throws Exception {
        String json = exporter.export(
            Collections.singletonList(openAiExchange("hello", "hi there")),
            LlmDatasetExporter.DatasetFormat.PROMPTFOO, new FixtureRedactor());

        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode tests = root.get("tests");
        assertTrue(tests.isArray());
        assertEquals(1, tests.size());
        JsonNode test = tests.get(0);
        assertTrue(test.get("vars").get("messages").isArray());
        assertEquals(2, test.get("vars").get("messages").size());
        JsonNode assertion = test.get("assert").get(0);
        assertEquals("equals", assertion.get("type").asText());
        assertEquals("hi there", assertion.get("value").asText());
    }

    @Test
    public void redactionMasksSecretInPromptContent() throws Exception {
        // a credential a user pasted into the prompt must be masked in the dataset
        String secret = "sk-ABCDEFGHIJKLMNOP0123456789";
        String jsonl = exporter.export(
            Collections.singletonList(openAiExchange("my key is " + secret, "ok")),
            LlmDatasetExporter.DatasetFormat.OPENAI_EVALS, new FixtureRedactor());

        assertThat(jsonl, not(containsString(secret)));
        assertThat(jsonl, containsString("***"));
        // still valid JSONL
        assertThat(parseJsonl(jsonl).size(), greaterThanOrEqualTo(1));
    }

    @Test
    public void redactionMasksConfiguredBodyFieldInPrompt() throws Exception {
        ConfigurationProperties.fixtureBodyRedactFields("content");
        FixtureRedactor redactor = new FixtureRedactor(
            FixtureRedactor.defaultSensitiveHeaders(), Collections.singletonList("content"));

        String jsonl = exporter.export(
            Collections.singletonList(openAiExchange("super secret prompt", "ok")),
            LlmDatasetExporter.DatasetFormat.OPENAI_EVALS, redactor);

        assertThat(jsonl, not(containsString("super secret prompt")));
        assertThat(jsonl, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
    }

    @Test
    public void emptyCaptureYieldsEmptyJsonlAndEmptyPromptfoo() throws Exception {
        assertEquals("", exporter.export(Collections.emptyList(),
            LlmDatasetExporter.DatasetFormat.OPENAI_EVALS, new FixtureRedactor()));
        JsonNode root = OBJECT_MAPPER.readTree(exporter.export(Collections.emptyList(),
            LlmDatasetExporter.DatasetFormat.PROMPTFOO, new FixtureRedactor()));
        assertEquals(0, root.get("tests").size());
    }

    @Test
    public void nonLlmTrafficIsExcluded() throws Exception {
        CapturedExchange notLlm = new CapturedExchange(
            request().withMethod("GET").withPath("/api/users").withHeader("Host", "example.com"),
            response().withBody("[]"), null);
        String jsonl = exporter.export(Collections.singletonList(notLlm),
            LlmDatasetExporter.DatasetFormat.OPENAI_EVALS, new FixtureRedactor());
        assertEquals("", jsonl);
    }

    @Test
    public void wireFormatMappingResolvesDatasetFormats() {
        assertEquals(LlmDatasetExporter.DatasetFormat.OPENAI_EVALS,
            LlmDatasetExporter.DatasetFormat.fromWire("openai-evals").orElseThrow(AssertionError::new));
        assertEquals(LlmDatasetExporter.DatasetFormat.FINE_TUNE,
            LlmDatasetExporter.DatasetFormat.fromWire("finetune").orElseThrow(AssertionError::new));
        assertEquals(LlmDatasetExporter.DatasetFormat.PROMPTFOO,
            LlmDatasetExporter.DatasetFormat.fromWire("promptfoo").orElseThrow(AssertionError::new));
        assertThat(LlmDatasetExporter.DatasetFormat.fromWire("json").isPresent(), equalTo(false));
        assertThat(LlmDatasetExporter.DatasetFormat.fromWire(null).isPresent(), equalTo(false));
    }
}
