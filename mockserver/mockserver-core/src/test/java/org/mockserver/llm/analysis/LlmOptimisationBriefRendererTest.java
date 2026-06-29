package org.mockserver.llm.analysis;

import org.junit.Test;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class LlmOptimisationBriefRendererTest {

    private final LlmOptimisationBriefRenderer renderer = new LlmOptimisationBriefRenderer();
    private final LlmOptimisationReportBuilder builder = new LlmOptimisationReportBuilder();

    private static HttpRequest openAiRequest(String model, String systemPrompt, String userText) {
        return request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")
            .withHeader("Authorization", "Bearer sk-secret-key-1234567890")
            .withBody("{\"model\":\"" + model + "\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + systemPrompt + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + userText + "\"}],"
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"description\":\"Get weather\"}}]}");
    }

    private static HttpResponse usageResponse(String model, int in, int out, String finish) {
        return response().withStatusCode(200)
            .withBody("{\"model\":\"" + model + "\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + "}}");
    }

    private List<CapturedExchange> sampleExchanges() {
        return java.util.Arrays.asList(
            new CapturedExchange(openAiRequest("gpt-4o-2024-08-06", "You are a helpful assistant with a long static brief.", "What is the weather in Paris?"),
                usageResponse("gpt-4o-2024-08-06", 8120, 540, "tool_calls"), 2300L),
            new CapturedExchange(openAiRequest("gpt-4o-2024-08-06", "You are a helpful assistant with a long static brief.", "And in London?"),
                usageResponse("gpt-4o-2024-08-06", 8200, 480, "stop"), 1900L));
    }

    private LlmOptimisationReport sampleReport(List<CapturedExchange> exchanges) {
        return builder.build(exchanges, "host:api.openai.com",
            LlmOptimisationReport.GroupingBasis.PROXY_HOST,
            java.util.Arrays.asList("authorization", "x-api-key"), Collections.emptyList());
    }

    @Test
    public void emptyReportRendersNoTrafficBrief() {
        LlmOptimisationReport report = builder.build(Collections.emptyList(), "host:none",
            LlmOptimisationReport.GroupingBasis.PROXY_HOST, Collections.emptyList(), Collections.emptyList());
        String md = renderer.render(report, Collections.emptyList(), new FixtureRedactor());
        assertThat(md, containsString(LlmOptimisationBriefRenderer.FRAMING_PREAMBLE));
        assertThat(md, containsString("No LLM traffic captured"));
    }

    @Test
    public void sectionsAppearInFrozenOrder() {
        List<CapturedExchange> exchanges = sampleExchanges();
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());

        int preamble = md.indexOf(LlmOptimisationBriefRenderer.FRAMING_PREAMBLE);
        int summary = md.indexOf("## Run summary");
        int table = md.indexOf("## Per-call breakdown");
        int opportunities = md.indexOf("## Detected opportunities");
        int appendix = md.indexOf("## Conversations & tool definitions (appendix)");

        assertThat(preamble, lessThan(summary));
        assertThat(summary, lessThan(table));
        assertThat(table, lessThan(opportunities));
        assertThat(opportunities, lessThan(appendix));
    }

    @Test
    public void briefNeverLeaksSecrets() {
        List<CapturedExchange> exchanges = sampleExchanges();
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());
        assertThat(md, not(containsString("sk-secret-key-1234567890")));
        assertThat(md, not(containsString("Bearer sk-")));
    }

    @Test
    public void briefMasksSecretsPastedIntoMessageText() {
        // A secret a user pasted into a PROMPT (not an HTTP header — those are already redacted)
        // must be masked in the appendix, which prints message text verbatim and is framed
        // "paste into any LLM".
        HttpRequest leaky = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")
            .withBody("{\"model\":\"gpt-4o-2024-08-06\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"You are helpful.\"},"
                + "{\"role\":\"user\",\"content\":\"Use my key sk-abcdefghijklmnop1234567890 and "
                + "AKIAIOSFODNN7EXAMPLE and Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123 and "
                + "token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345 please\"}]}");
        List<CapturedExchange> exchanges = java.util.Collections.singletonList(
            new CapturedExchange(leaky, usageResponse("gpt-4o-2024-08-06", 100, 10, "stop"), 100L));
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());

        assertThat(md, not(containsString("sk-abcdefghijklmnop1234567890")));
        assertThat(md, not(containsString("AKIAIOSFODNN7EXAMPLE")));
        assertThat(md, not(containsString("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345")));
        assertThat(md, not(containsString("Bearer abcdefghijklmnopqrstuvwxyz0123")));
        assertThat(md, containsString("***"));
        // ordinary prose around the secret is preserved
        assertThat(md, containsString("Use my key"));
        assertThat(md, containsString("please"));
    }

    @Test
    public void maskSecretsLeavesNormalProseUntouched() {
        String prose = "The quick brown fox jumps over the lazy dog. Cost was $3.14 and id 42.";
        assertEquals(prose, LlmOptimisationBriefRenderer.maskSecrets(prose));
    }

    @Test
    public void unpricedCallRendersNaNotConfidentZero() {
        HttpRequest unpriced = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")
            .withBody("{\"model\":\"my-azure-deployment\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"hi\"}]}");
        List<CapturedExchange> exchanges = java.util.Collections.singletonList(
            new CapturedExchange(unpriced, usageResponse("my-azure-deployment", 100, 10, "stop"), 100L));
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());
        assertThat(md, containsString("| n/a |"));
        assertThat(md, not(containsString("| $0.0000 |")));
    }

    @Test
    public void briefIncludesPerCallTableHeaderAndToolDefinitions() {
        List<CapturedExchange> exchanges = sampleExchanges();
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());
        assertThat(md, containsString("| # | model | in tok | out tok | cost | latency | tools | finish |"));
        assertThat(md, containsString("get_weather"));
        assertThat(md, containsString("**Tool definitions:**"));
    }

    @Test
    public void matchesGoldenFile() throws IOException {
        List<CapturedExchange> exchanges = sampleExchanges();
        String md = renderer.render(sampleReport(exchanges), exchanges, new FixtureRedactor());
        String golden = readResource("/org/mockserver/llm/analysis/optimisation-brief.golden.md");
        assertEquals(normalise(golden), normalise(md));
    }

    private static String normalise(String s) {
        return s.replace("\r\n", "\n").trim();
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = LlmOptimisationBriefRendererTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing golden resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
