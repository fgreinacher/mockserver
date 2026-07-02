package org.mockserver.llm.analysis;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NormalizationOptions;
import org.mockserver.model.Provider;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;

public class AgentRunDiffTest {

    private final AgentRunDiff diff = new AgentRunDiff();

    private static HttpRequest chat(String messagesJson) {
        return request().withMethod("POST").withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")
            .withBody("{\"model\":\"gpt-4o-2024-08-06\",\"messages\":" + messagesJson + "}");
    }

    private static AgentRunDiff.RunSide side(HttpRequest request) {
        return new AgentRunDiff.RunSide(Collections.singletonList(request), Provider.OPENAI);
    }

    @Test
    public void surfacesChangedPromptMessage() {
        AgentRunDiff.RunSide before = side(chat(
            "[{\"role\":\"system\",\"content\":\"sys\"},{\"role\":\"user\",\"content\":\"hello\"}]"));
        AgentRunDiff.RunSide after = side(chat(
            "[{\"role\":\"system\",\"content\":\"sys\"},{\"role\":\"user\",\"content\":\"hello world\"}]"));

        AgentRunDiff.RunDiffResult result = diff.diff(before, after, null);

        assertTrue("prompt should be reported as changed", result.isPromptChanged());
        boolean userChanged = result.getMessageDiffs().stream().anyMatch(d ->
            d.getChangeType() == AgentRunDiff.ChangeType.CHANGED
                && "USER".equals(d.getRole())
                && "hello".equals(d.getBeforeText())
                && "hello world".equals(d.getAfterText()));
        assertTrue("expected the USER message to be reported CHANGED", userChanged);
        // the unchanged system message is not reported as a change
        assertTrue(result.getMessageDiffs().stream().anyMatch(d ->
            d.getChangeType() == AgentRunDiff.ChangeType.UNCHANGED && "SYSTEM".equals(d.getRole())));
    }

    @Test
    public void identicalRunsReportNoPromptChange() {
        String messages = "[{\"role\":\"user\",\"content\":\"do the thing\"}]";
        AgentRunDiff.RunDiffResult result = diff.diff(side(chat(messages)), side(chat(messages)), null);

        assertFalse(result.isPromptChanged());
        assertTrue(result.getToolCallsAdded().isEmpty());
        assertTrue(result.getToolCallsRemoved().isEmpty());
        assertNull("no totals supplied means no token delta", result.getTokenDelta());
    }

    @Test
    public void surfacesAddedToolCall() {
        AgentRunDiff.RunSide before = side(chat(
            "[{\"role\":\"user\",\"content\":\"weather?\"}]"));
        AgentRunDiff.RunSide after = side(chat(
            "[{\"role\":\"user\",\"content\":\"weather?\"},"
                + "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"london\\\"}\"}}]}]"));

        AgentRunDiff.RunDiffResult result = diff.diff(before, after, null);

        assertTrue(result.getToolCallsAdded().stream().anyMatch(fp -> fp.startsWith("get_weather(")));
        assertTrue(result.getToolCallsRemoved().isEmpty());
        assertTrue(result.isPromptChanged());
    }

    @Test
    public void surfacesRemovedToolCall() {
        AgentRunDiff.RunSide before = side(chat(
            "[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}]"));
        AgentRunDiff.RunSide after = side(chat(
            "[{\"role\":\"user\",\"content\":\"done\"}]"));

        AgentRunDiff.RunDiffResult result = diff.diff(before, after, null);
        assertTrue(result.getToolCallsRemoved().stream().anyMatch(fp -> fp.startsWith("lookup(")));
    }

    @Test
    public void computesTokenAndCostDeltaWhenBothSidesHaveTotals() {
        String messages = "[{\"role\":\"user\",\"content\":\"hi\"}]";
        AgentRunDiff.RunSide before = new AgentRunDiff.RunSide(
            Collections.singletonList(chat(messages)), Provider.OPENAI, 100L, 10L, 0.01);
        AgentRunDiff.RunSide after = new AgentRunDiff.RunSide(
            Collections.singletonList(chat(messages)), Provider.OPENAI, 200L, 25L, 0.03);

        AgentRunDiff.RunDiffResult result = diff.diff(before, after, null);
        AgentRunDiff.TokenDelta delta = result.getTokenDelta();
        assertNotNull(delta);
        assertEquals(100L, delta.getInputTokensDelta());
        assertEquals(15L, delta.getOutputTokensDelta());
        assertThat(delta.getCostUsdDelta(), closeTo(0.02, 1e-9));
    }

    @Test
    public void normalisationSuppressesCosmeticWhitespaceChange() {
        // collapseWhitespace is a default-on normalisation option, so the two prompts
        // differing only in whitespace must NOT be reported as a change.
        AgentRunDiff.RunSide before = side(chat(
            "[{\"role\":\"user\",\"content\":\"hello   world\"}]"));
        AgentRunDiff.RunSide after = side(chat(
            "[{\"role\":\"user\",\"content\":\"hello world\"}]"));

        AgentRunDiff.RunDiffResult result = diff.diff(before, after, NormalizationOptions.normalizationOptions());
        assertFalse("whitespace-only difference must be normalised away", result.isPromptChanged());
    }

    @Test
    public void handlesEmptyRunsGracefully() {
        AgentRunDiff.RunSide empty = new AgentRunDiff.RunSide(Collections.emptyList(), Provider.OPENAI);
        AgentRunDiff.RunDiffResult result = diff.diff(empty, empty, null);
        assertFalse(result.isPromptChanged());
        assertEquals(0, result.getMessageCountBefore());
        assertEquals(0, result.getMessageCountAfter());
    }
}
