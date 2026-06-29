package org.mockserver.llm.cost;

import org.junit.Test;
import org.mockserver.model.Provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LlmPricingTest {

    private static final double EPS = 1e-9;

    @Test
    public void shouldPriceAnthropicSonnet() {
        // 1M input @ $3, 1M output @ $15 = $18
        Double cost = LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-sonnet-4-20250514", 1_000_000, 1_000_000);
        assertThat(cost, is(closeTo(18.0, EPS)));
    }

    @Test
    public void shouldPriceAnthropicOpusAndHaiku() {
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-opus-4-1", 1_000_000, 0),
            is(closeTo(15.0, EPS)));
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-haiku-4", 0, 1_000_000),
            is(closeTo(4.0, EPS)));
    }

    @Test
    public void shouldDistinguishGpt4oMiniFromGpt4o() {
        // ordering matters: gpt-4o-mini must NOT bill at gpt-4o's rate
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "gpt-4o-mini", 1_000_000, 0),
            is(closeTo(0.15, EPS)));
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "gpt-4o", 1_000_000, 0),
            is(closeTo(2.5, EPS)));
    }

    @Test
    public void shouldPriceO3() {
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "o3", 0, 1_000_000),
            is(closeTo(60.0, EPS)));
    }

    @Test
    public void shouldPriceGemini() {
        assertThat(LlmPricing.estimateCostUsd(Provider.GEMINI, "gemini-2.0-flash", 1_000_000, 1_000_000),
            is(closeTo(0.5, EPS)));
    }

    @Test
    public void shouldPriceOllamaAsFree() {
        assertThat(LlmPricing.estimateCostUsd(Provider.OLLAMA, "llama3", 5_000_000, 5_000_000),
            is(closeTo(0.0, EPS)));
    }

    @Test
    public void shouldPriceBedrockViaAnthropicAfterStrippingPrefix() {
        assertThat(LlmPricing.estimateCostUsd(Provider.BEDROCK, "anthropic.claude-sonnet-4-20250514", 1_000_000, 0),
            is(closeTo(3.0, EPS)));
    }

    @Test
    public void shouldBeCaseInsensitive() {
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "CLAUDE-SONNET-4", 1_000_000, 0),
            is(closeTo(3.0, EPS)));
    }

    @Test
    public void shouldReturnZeroForZeroTokensOnKnownModel() {
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-sonnet-4", 0, 0),
            is(closeTo(0.0, EPS)));
    }

    @Test
    public void shouldPriceCurrentAnthropicModels() {
        // claude-opus-4-8: $5 input / $25 output per 1M
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-opus-4-8", 1_000_000, 1_000_000),
            is(closeTo(30.0, EPS)));
        // claude-haiku-4-5: $1 input / $5 output per 1M
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-haiku-4-5", 1_000_000, 0),
            is(closeTo(1.0, EPS)));
        // claude-fable-5: $10 input / $50 output per 1M
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-fable-5", 0, 1_000_000),
            is(closeTo(50.0, EPS)));
    }

    @Test
    public void shouldPriceNewerModelsAheadOfGenericPrefix() {
        // the more-specific claude-opus-4-8 ($5) must win over the generic
        // claude-opus-4 ($15) entry that follows it in the table
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-opus-4-8-20260101", 1_000_000, 0),
            is(closeTo(5.0, EPS)));
        // an original claude-opus-4-0 still falls through to the generic $15 tier
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-opus-4-0", 1_000_000, 0),
            is(closeTo(15.0, EPS)));
    }

    @Test
    public void shouldPriceCurrentOpenAiAndGeminiModels() {
        // gpt-4.1: $2 input / $8 output per 1M
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "gpt-4.1", 1_000_000, 0),
            is(closeTo(2.0, EPS)));
        // gpt-4.1-mini must win over gpt-4.1 in the prefix walk
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "gpt-4.1-mini", 1_000_000, 0),
            is(closeTo(0.4, EPS)));
        // gemini-2.5-pro: $1.25 input / $10 output per 1M
        assertThat(LlmPricing.estimateCostUsd(Provider.GEMINI, "gemini-2.5-pro", 1_000_000, 1_000_000),
            is(closeTo(11.25, EPS)));
    }

    @Test
    public void shouldResolveApproximatedGpt5ModelsToNonNull() {
        // gpt-5* prices are approximated placeholders — they must still resolve
        // to SOMETHING (not null) so a recognised model is priced, not dropped.
        assertThat(LlmPricing.getPricing(Provider.OPENAI, "gpt-5"), is(notNullValue()));
        assertThat(LlmPricing.getPricing(Provider.OPENAI, "gpt-5-mini"), is(notNullValue()));
    }

    @Test
    public void shouldFlagGpt5PlaceholderRatesAsApproximate() {
        // gpt-5* are admitted placeholders — flagged approximate so callers present any derived
        // cost as an estimate, not fact.
        assertTrue(LlmPricing.isApproximateRate(Provider.OPENAI, "gpt-5"));
        assertTrue(LlmPricing.isApproximateRate(Provider.OPENAI, "gpt-5-mini"));
        assertTrue(LlmPricing.getPricing(Provider.OPENAI, "gpt-5").isApproximate());
    }

    @Test
    public void shouldNotFlagVerifiedRatesAsApproximate() {
        assertFalse(LlmPricing.isApproximateRate(Provider.OPENAI, "gpt-4o"));
        assertFalse(LlmPricing.isApproximateRate(Provider.ANTHROPIC, "claude-sonnet-4-5"));
        // an unpriced model is not "approximate" — it is simply unknown
        assertFalse(LlmPricing.isApproximateRate(Provider.OPENAI, "some-future-model"));
        assertFalse(LlmPricing.isApproximateRate(Provider.AZURE_OPENAI, "my-deployment"));
    }

    @Test
    public void shouldReturnNullForUnknownModel() {
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "some-future-model", 100, 100), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForAzureDeploymentName() {
        // Azure deployments are user-defined names, not canonical model ids
        assertThat(LlmPricing.estimateCostUsd(Provider.AZURE_OPENAI, "my-gpt4o-prod", 100, 100), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForNullModel() {
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, null, 100, 100), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForNullProvider() {
        assertThat(LlmPricing.estimateCostUsd(null, "claude-sonnet-4", 100, 100), is(nullValue()));
    }

    // --- blendedPerMillion ---

    @Test
    public void blendedPerMillionSumsInputAndOutputRates() {
        // gpt-4o: 2.5 + 10.0 = 12.5
        assertThat(LlmPricing.blendedPerMillion(Provider.OPENAI, "gpt-4o"), is(closeTo(12.5, EPS)));
        // claude-sonnet-4: 3.0 + 15.0 = 18.0
        assertThat(LlmPricing.blendedPerMillion(Provider.ANTHROPIC, "claude-sonnet-4"), is(closeTo(18.0, EPS)));
    }

    @Test
    public void blendedPerMillionNullForUnknownModel() {
        assertThat(LlmPricing.blendedPerMillion(Provider.OPENAI, "some-future-model"), is(nullValue()));
        assertThat(LlmPricing.blendedPerMillion(null, "gpt-4o"), is(nullValue()));
    }

    // --- cheapestModel ---

    @Test
    public void cheapestModelForOpenAiIsTheLowestBlendedEntry() {
        // gpt-4.1-nano (0.1 + 0.4 = 0.5) is the cheapest OpenAI entry.
        LlmPricing.ModelOption cheapest = LlmPricing.cheapestModel(Provider.OPENAI);
        assertThat(cheapest, is(notNullValue()));
        assertThat(cheapest.getLabel(), is("gpt-4.1-nano"));
        assertThat(cheapest.getBlendedPerMillion(), is(closeTo(0.5, EPS)));
    }

    @Test
    public void cheapestModelForAnthropic() {
        // claude-haiku-4 (0.8 + 4.0 = 4.8) is the cheapest Anthropic entry.
        LlmPricing.ModelOption cheapest = LlmPricing.cheapestModel(Provider.ANTHROPIC);
        assertThat(cheapest, is(notNullValue()));
        assertThat(cheapest.getLabel(), is("claude-haiku-4"));
        assertThat(cheapest.getBlendedPerMillion(), is(closeTo(4.8, EPS)));
    }

    @Test
    public void cheapestModelForGemini() {
        // gemini-2.0-flash-lite (0.075 + 0.3 = 0.375) is the cheapest Gemini entry.
        LlmPricing.ModelOption cheapest = LlmPricing.cheapestModel(Provider.GEMINI);
        assertThat(cheapest, is(notNullValue()));
        assertThat(cheapest.getLabel(), is("gemini-2.0-flash-lite"));
        assertThat(cheapest.getBlendedPerMillion(), is(closeTo(0.375, EPS)));
    }

    @Test
    public void cheapestModelNullForOllamaAndUnknownAndNull() {
        assertThat(LlmPricing.cheapestModel(Provider.OLLAMA), is(nullValue()));
        assertThat(LlmPricing.cheapestModel(Provider.AZURE_OPENAI), is(nullValue()));
        assertThat(LlmPricing.cheapestModel(null), is(nullValue()));
    }
}
