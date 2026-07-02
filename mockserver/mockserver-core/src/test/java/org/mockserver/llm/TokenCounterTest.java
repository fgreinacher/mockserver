package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.ToolUse;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for the approximate {@link TokenCounter}. The counts are an
 * estimate, so the tests assert stability (same input → same count), correct
 * null/empty handling, and that the estimate lands in a sane ballpark — not an
 * exact tokenizer value.
 */
public class TokenCounterTest {

    // ---- null / empty handling ----

    @Test
    public void nullTextIsZeroTokens() {
        assertThat(TokenCounter.estimateTokens(null), is(0));
    }

    @Test
    public void emptyTextIsZeroTokens() {
        assertThat(TokenCounter.estimateTokens(""), is(0));
    }

    @Test
    public void singleCharIsAtLeastOneToken() {
        assertThat(TokenCounter.estimateTokens("a"), is(greaterThanOrEqualTo(1)));
    }

    @Test
    public void whitespaceOnlyIsAtLeastOneToken() {
        // non-empty but no words — still a real (if tiny) cost
        assertThat(TokenCounter.estimateTokens("   "), is(greaterThanOrEqualTo(1)));
    }

    // ---- determinism / stability ----

    @Test
    public void estimateIsStableForKnownString() {
        String text = "The quick brown fox jumps over the lazy dog.";
        int first = TokenCounter.estimateTokens(text);
        int second = TokenCounter.estimateTokens(text);
        assertThat(first, is(second));
        // ballpark: 9 words / ~44 chars → roughly a dozen tokens
        assertThat(first, is(both(greaterThan(7)).and(lessThan(20))));
    }

    @Test
    public void estimateIsStableAcrossManyCalls() {
        String text = "Repeated determinism check across invocations.";
        int expected = TokenCounter.estimateTokens(text);
        for (int i = 0; i < 50; i++) {
            assertThat(TokenCounter.estimateTokens(text), is(expected));
        }
    }

    @Test
    public void longerTextHasMoreTokens() {
        int shortCount = TokenCounter.estimateTokens("hello");
        int longCount = TokenCounter.estimateTokens("hello hello hello hello hello hello hello hello");
        assertThat(longCount, is(greaterThan(shortCount)));
    }

    @Test
    public void roughlyFourCharsPerTokenForProse() {
        // 100 chars of plain prose should land in the same order of magnitude as chars/4
        String text = "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore.";
        int count = TokenCounter.estimateTokens(text);
        int charsOverFour = text.length() / 4;
        assertThat(count, is(both(greaterThan(charsOverFour / 2)).and(lessThan(charsOverFour * 2 + 10))));
    }

    // ---- accuracy against real GPT-4 (cl100k_base) reference counts ----

    /**
     * Reference token counts computed with OpenAI's tiktoken cl100k_base (GPT-4)
     * tokenizer. The BPE-approximating estimate must land within ±15% for
     * ordinary English prose. CJK and code are the documented "further off"
     * cases and are checked with looser bounds below.
     */
    private void assertWithinFifteenPercent(String text, int referenceTokens) {
        int estimate = TokenCounter.estimateTokens(text);
        double lower = referenceTokens * 0.85;
        double upper = referenceTokens * 1.15;
        assertThat("estimate " + estimate + " for \"" + text + "\" not within ±15% of " + referenceTokens,
            (double) estimate, is(both(greaterThanOrEqualTo(lower)).and(lessThanOrEqualTo(upper))));
    }

    @Test
    public void englishProseIsWithinFifteenPercentOfRealTokenizer() {
        assertWithinFifteenPercent("Hello world", 2);
        assertWithinFifteenPercent("The quick brown fox jumps over the lazy dog.", 10);
        assertWithinFifteenPercent("The mitochondria is the powerhouse of the cell.", 10);
        assertWithinFifteenPercent("ChatGPT is a large language model.", 9);
        assertWithinFifteenPercent("tokenization", 2);
        assertWithinFifteenPercent(
            "MockServer is an open-source tool for mocking HTTP and HTTPS APIs during "
                + "automated testing. It can act as both a mock server and a proxy, letting "
                + "developers simulate third-party services and verify how their code behaves "
                + "in the presence of unavailable or slow dependencies.",
            50);
    }

    @Test
    public void cjkTextIsDenserThanLatinAndInBallpark() {
        // "你好世界" is 4 Han chars, cl100k_base counts 5 tokens (>1 token/char)
        int han = TokenCounter.estimateTokens("你好世界");
        assertThat(han, is(both(greaterThanOrEqualTo(4)).and(lessThanOrEqualTo(7))));
        // denser than the same character count of Latin letters
        assertThat(han, is(greaterThan(TokenCounter.estimateTokens("abcd"))));
    }

    @Test
    public void codeIsInTheRightBallpark() {
        // real cl100k_base = 11 tokens; symbol-dense, so a looser ±40% bound
        int estimate = TokenCounter.estimateTokens("def add(a, b):\n    return a + b");
        assertThat(estimate, is(both(greaterThan(6)).and(lessThan(16))));
    }

    // ---- subword streaming segmentation ----

    @Test
    public void streamingDefaultReproducesWhitespaceSplit() {
        // null physics and subwordStreaming-off must reproduce the legacy split
        // (each word and each whitespace run its own delta), so goldens are stable
        List<String> viaNull = TokenCounter.streamingTextTokens("Hello world", null);
        assertThat(viaNull, contains("Hello", " ", "world"));
        List<String> viaOff = TokenCounter.streamingTextTokens(
            "Hello world", org.mockserver.model.StreamingPhysics.streamingPhysics());
        assertThat(viaOff, contains("Hello", " ", "world"));
    }

    @Test
    public void streamingSubwordEmitsFinerConcatExactDeltas() {
        String text = "MockServer streams tokens";
        List<String> subword = TokenCounter.streamingTextTokens(
            text, org.mockserver.model.StreamingPhysics.streamingPhysics().withSubwordStreaming(true));
        List<String> whole = TokenCounter.streamingTextTokens(text, null);
        // finer granularity
        assertThat(subword.size(), is(greaterThan(whole.size())));
        // every piece non-empty
        for (String piece : subword) {
            assertThat(piece.isEmpty(), is(false));
        }
        // concatenation is exact
        assertThat(String.join("", subword), is(text));
    }

    @Test
    public void streamingSegmentationPreservesAllWhitespaceExactly() {
        String text = "  leading and   inner\ttabs  ";
        List<String> subword = TokenCounter.segmentForStreaming(text);
        assertThat(String.join("", subword), is(text));
    }

    @Test
    public void streamingEmptyTextIsNoDeltas() {
        assertThat(TokenCounter.streamingTextTokens(null, null).isEmpty(), is(true));
        assertThat(TokenCounter.streamingTextTokens("", null).isEmpty(), is(true));
    }

    // ---- prompt-token estimation over a conversation ----

    @Test
    public void promptTokensNullConversationIsZero() {
        assertThat(TokenCounter.estimatePromptTokens(null), is(0));
    }

    @Test
    public void promptTokensEmptyConversationIsZero() {
        assertThat(TokenCounter.estimatePromptTokens(ParsedConversation.empty()), is(0));
    }

    @Test
    public void promptTokensSumsMessagesWithOverhead() {
        ParsedMessage system = new ParsedMessage(ParsedMessage.Role.SYSTEM, "You are a helpful assistant.", null, null);
        ParsedMessage user = new ParsedMessage(ParsedMessage.Role.USER, "What is the capital of France?", null, null);
        ParsedConversation conversation = ParsedConversation.of(Arrays.asList(system, user));

        int total = TokenCounter.estimatePromptTokens(conversation);
        int perMessageSum = TokenCounter.estimateTokens("You are a helpful assistant.")
            + TokenCounter.estimateTokens("What is the capital of France?");
        // includes per-message overhead so the total exceeds the bare text sum
        assertThat(total, is(greaterThan(perMessageSum)));
    }

    @Test
    public void promptTokensIncludeToolCallArgumentsAndResults() {
        ToolUse toolCall = ToolUse.toolUse("get_weather").withArguments("{\"city\":\"Paris\"}");
        ParsedMessage assistant = new ParsedMessage(ParsedMessage.Role.ASSISTANT, "", Collections.singletonList(toolCall), null);

        Map<String, String> results = new LinkedHashMap<>();
        results.put("call_1", "{\"temp\":\"21C\"}");
        ParsedMessage tool = new ParsedMessage(ParsedMessage.Role.TOOL, "", null, results);

        int withToolContent = TokenCounter.estimatePromptTokens(ParsedConversation.of(Arrays.asList(assistant, tool)));
        // two messages of overhead alone would be 6; the tool name/args/results push it higher
        assertThat(withToolContent, is(greaterThan(6)));
    }

    @Test
    public void promptTokensToleratesNullMessageEntries() {
        ParsedConversation conversation = ParsedConversation.of(Arrays.asList(
            null,
            new ParsedMessage(ParsedMessage.Role.USER, "hello world", null, null)));
        assertThat(TokenCounter.estimatePromptTokens(conversation), is(greaterThan(0)));
    }

    // ---- completion-token estimation ----

    @Test
    public void completionTokensTextOnly() {
        int count = TokenCounter.estimateCompletionTokens("The answer is 42.", null);
        assertThat(count, is(greaterThan(0)));
        assertThat(count, is(TokenCounter.estimateTokens("The answer is 42.")));
    }

    @Test
    public void completionTokensIncludeToolCalls() {
        List<ToolUse> toolCalls = Collections.singletonList(
            ToolUse.toolUse("lookup").withArguments("{\"id\":123}"));
        int textOnly = TokenCounter.estimateCompletionTokens("done", null);
        int withTools = TokenCounter.estimateCompletionTokens("done", toolCalls);
        assertThat(withTools, is(greaterThan(textOnly)));
    }

    @Test
    public void completionTokensEmptyOutputIsZero() {
        assertThat(TokenCounter.estimateCompletionTokens(null, null), is(0));
        assertThat(TokenCounter.estimateCompletionTokens("", Collections.emptyList()), is(0));
    }
}
