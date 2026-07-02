package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration test for the {@code export_optimisation_report} MCP tool, which
 * reconstructs the optimisation report/brief from recorded FORWARDED_REQUEST
 * traffic in the event log.
 */
public class ExportOptimisationReportIntegrationTest {

    private McpToolRegistry toolRegistry;
    private HttpState httpState;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1080));
        when(server.isRunning()).thenReturn(true);
        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        toolRegistry = new McpToolRegistry(httpState, server);
        objectMapper = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
    }

    @After
    public void resetConfig() {
        ConfigurationProperties.fixtureBodyRedactFields("");
    }

    private void seedOpenAiForward(String userText, int inTok, int outTok) {
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/chat/completions")
                .withHeader("Host", "api.openai.com")
                .withHeader("Authorization", "Bearer sk-secret-key-98765")
                .withBody("{\"model\":\"gpt-4o-2024-08-06\",\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"You are a helpful assistant.\"},"
                    + "{\"role\":\"user\",\"content\":\"" + userText + "\"}]}"))
            .setHttpResponse(response().withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"model\":\"gpt-4o-2024-08-06\",\"choices\":[{\"message\":{\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":" + inTok + ",\"completion_tokens\":" + outTok + "}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
    }

    private boolean recordedPairsVisible() {
        try {
            JsonNode result = toolRegistry.callTool("retrieve_request_responses",
                objectMapper.createObjectNode());
            return result.path("total").asInt() >= 2;
        } catch (Exception e) {
            return false;
        }
    }

    private static void pollUntilTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    @Test
    public void exportsMarkdownBriefByDefault() throws Exception {
        seedOpenAiForward("What is the weather in Paris?", 8000, 500);
        seedOpenAiForward("And in London?", 8100, 480);
        pollUntilTrue(this::recordedPairsVisible);

        JsonNode result = toolRegistry.callTool("export_optimisation_report", objectMapper.createObjectNode());
        assertThat(result.path("format").asText(), is("markdown"));
        String brief = result.path("brief").asText();
        assertThat(brief, containsString("You are an LLM cost-optimisation expert"));
        assertThat(brief, containsString("## Run summary"));
        assertThat(brief, containsString("## Detected opportunities"));
        // secret never leaks
        assertThat(brief, not(containsString("sk-secret-key-98765")));
    }

    @Test
    public void exportsJsonBundleWhenRequested() throws Exception {
        seedOpenAiForward("hi", 100, 10);
        seedOpenAiForward("hi again", 120, 12);
        pollUntilTrue(this::recordedPairsVisible);

        com.fasterxml.jackson.databind.node.ObjectNode params = objectMapper.createObjectNode();
        params.put("format", "json");
        JsonNode result = toolRegistry.callTool("export_optimisation_report", params);
        assertThat(result.path("format").asText(), is("json"));
        JsonNode report = result.path("report");
        assertThat(report.path("schemaVersion").asInt(), is(1));
        assertThat(report.path("generatedBy").asText(), is("mockserver"));
        assertThat(report.path("totals").path("callCount").asInt(), is(2));
        assertThat(report.path("session").path("providers").get(0).asText(), is("OPENAI"));
    }

    @Test
    public void exportsCsvWhenRequested() throws Exception {
        seedOpenAiForward("hi", 100, 10);
        seedOpenAiForward("hi again", 120, 12);
        pollUntilTrue(this::recordedPairsVisible);

        com.fasterxml.jackson.databind.node.ObjectNode params = objectMapper.createObjectNode();
        params.put("format", "csv");
        JsonNode result = toolRegistry.callTool("export_optimisation_report", params);
        assertThat(result.path("format").asText(), is("csv"));
        String csv = result.path("csv").asText();
        assertThat(csv, containsString("index,provider,model,input_tokens"));
        assertThat(csv, containsString("section,metric,value"));
        assertThat(csv, containsString("totals,call_count,2"));
        // secret never leaks into the export
        assertThat(csv, not(containsString("sk-secret-key-98765")));
    }

    @Test
    public void emptyCaptureYieldsNoTrafficBrief() {
        JsonNode result = toolRegistry.callTool("export_optimisation_report", objectMapper.createObjectNode());
        assertThat(result.path("format").asText(), is("markdown"));
        assertThat(result.path("brief").asText(), containsString("No LLM traffic captured"));
    }

    @Test
    public void rejectsInvalidFormat() {
        com.fasterxml.jackson.databind.node.ObjectNode params = objectMapper.createObjectNode();
        params.put("format", "xml");
        JsonNode result = toolRegistry.callTool("export_optimisation_report", params);
        assertThat(result.path("error").asBoolean(), is(true));
    }

    @Test
    public void toolIsRegistered() {
        assertThat(toolRegistry.getTools().containsKey("export_optimisation_report"), is(true));
        assertThat(toolRegistry.getTools().containsKey("diff_agent_runs"), is(true));
    }

    @Test
    public void diffAgentRunsRedactsConfiguredBodyFieldInRunPrompts() throws Exception {
        // Regression guard for the redaction-bypass the review flagged: the diff caller
        // (diffRunSide) must redact each request through FixtureRedactor BEFORE the diff
        // decodes and surfaces prompt text, so a configured body field (fixtureBodyRedactFields)
        // holding NON-credential PII/secret text is masked in beforeText/afterText — not just
        // credential-shaped tokens caught by maskSecrets.
        ConfigurationProperties.fixtureBodyRedactFields("content");
        seedOpenAiForward("super secret non credential prompt", 100, 10);
        seedOpenAiForward("super secret non credential prompt", 120, 12);
        pollUntilTrue(this::recordedPairsVisible);

        ObjectNode params = objectMapper.createObjectNode();
        params.putObject("before").put("host", "api.openai.com");
        params.putObject("after").put("host", "api.openai.com");

        JsonNode result = toolRegistry.callTool("diff_agent_runs", params);

        // the raw configured-field value must never appear anywhere in the diff output
        assertThat(result.toString(), not(containsString("super secret non credential prompt")));
        // and the placeholder IS surfaced in the message diffs (proving the field was masked,
        // not merely absent because decoding failed)
        boolean masked = false;
        for (JsonNode md : result.path("messageDiffs")) {
            if (md.path("beforeText").asText().contains(FixtureRedactor.REDACTED_PLACEHOLDER)
                || md.path("afterText").asText().contains(FixtureRedactor.REDACTED_PLACEHOLDER)) {
                masked = true;
            }
        }
        assertThat("configured body field must be masked to the placeholder in the diff output", masked, is(true));
    }
}
