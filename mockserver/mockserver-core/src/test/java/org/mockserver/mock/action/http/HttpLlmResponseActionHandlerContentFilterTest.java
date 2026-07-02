package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LlmChaosProfile;
import org.mockserver.model.LlmContentFilter;
import org.mockserver.model.ModerationResponse;
import org.mockserver.model.Provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;

public class HttpLlmResponseActionHandlerContentFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());

    // --- Moderation endpoint ---

    @Test
    public void moderationResponseReturnsFlaggedVerdict() throws Exception {
        HttpResponse response = handler.handle(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withModeration(ModerationResponse.moderationResponse().withFlaggedCategory("hate")),
            request().withPath("/v1/moderations"));

        assertThat(response.getStatusCode(), is(200));
        JsonNode root = MAPPER.readTree(response.getBodyAsString());
        JsonNode result = root.get("results").get(0);
        assertThat(result.get("flagged").asBoolean(), is(true));
        assertThat(result.get("categories").get("hate").asBoolean(), is(true));
    }

    @Test
    public void moderationResponseReturnsNotFlaggedVerdict() throws Exception {
        HttpResponse response = handler.handle(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withModeration(ModerationResponse.moderationResponse()),
            request().withPath("/v1/moderations"));

        JsonNode result = MAPPER.readTree(response.getBodyAsString()).get("results").get(0);
        assertThat(result.get("flagged").asBoolean(), is(false));
    }

    // --- Azure content-filter annotations ---

    @Test
    public void azureResponseCarriesContentFilterAnnotations() throws Exception {
        HttpResponse response = handler.handle(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.AZURE_OPENAI)
                .withModel("gpt-4o")
                .withCompletion(Completion.completion().withText("hello"))
                .withContentFilter(LlmContentFilter.llmContentFilter().withHate("high").withViolence("safe")),
            request().withPath("/openai/deployments/gpt-4o/chat/completions"));

        JsonNode root = MAPPER.readTree(response.getBodyAsString());
        // per-choice content_filter_results
        JsonNode choiceFilter = root.get("choices").get(0).get("content_filter_results");
        assertThat(choiceFilter, is(notNullValue()));
        assertThat(choiceFilter.get("hate").get("filtered").asBoolean(), is(true));
        assertThat(choiceFilter.get("hate").get("severity").asText(), is("high"));
        assertThat(choiceFilter.get("violence").get("filtered").asBoolean(), is(false));
        // top-level prompt_filter_results
        JsonNode promptFilter = root.get("prompt_filter_results").get(0);
        assertThat(promptFilter.get("prompt_index").asInt(), is(0));
        assertThat(promptFilter.get("content_filter_results").get("hate").get("severity").asText(), is("high"));
    }

    @Test
    public void nonAzureResponseHasNoAnnotations() throws Exception {
        HttpResponse response = handler.handle(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withModel("gpt-4o")
                .withCompletion(Completion.completion().withText("hello"))
                .withContentFilter(LlmContentFilter.llmContentFilter().withHate("high")),
            request().withPath("/v1/chat/completions"));

        JsonNode root = MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("prompt_filter_results"), is(nullValue()));
        assertThat(root.get("choices").get(0).get("content_filter_results"), is(nullValue()));
    }

    // --- Chaos content-filter block (provider-correct) ---

    private HttpResponse block(Provider provider, LlmContentFilter filter) {
        return handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(provider)
                .withContentFilter(filter)
                .withCompletion(Completion.completion().withText("hi"))
                .withChaos(LlmChaosProfile.llmChaosProfile().withContentFilterBlockProbability(1.0)));
    }

    @Test
    public void chaosBlockForOpenAiIs400ContentFilter() throws Exception {
        HttpResponse response = block(Provider.OPENAI, null);
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(400));
        assertThat(MAPPER.readTree(response.getBodyAsString()).get("error").get("code").asText(), is("content_filter"));
    }

    @Test
    public void chaosBlockForAzureIsFilteredInnererror() throws Exception {
        HttpResponse response = block(Provider.AZURE_OPENAI, LlmContentFilter.llmContentFilter().withHate("high"));
        assertThat(response.getStatusCode(), is(400));
        JsonNode innererror = MAPPER.readTree(response.getBodyAsString()).get("error").get("innererror");
        assertThat(innererror.get("content_filter_result").get("hate").get("filtered").asBoolean(), is(true));
    }

    @Test
    public void chaosBlockForAnthropicIsRefusal() throws Exception {
        HttpResponse response = block(Provider.ANTHROPIC, null);
        assertThat(response.getStatusCode(), is(200));
        assertThat(MAPPER.readTree(response.getBodyAsString()).get("stop_reason").asText(), is("refusal"));
    }

    @Test
    public void zeroContentFilterProbabilityDoesNotBlock() {
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withChaos(LlmChaosProfile.llmChaosProfile().withContentFilterBlockProbability(0.0)));
        assertThat(response, is(nullValue()));
    }

    @Test
    public void contentFilterBlockTakesPriorityOverGenericError() throws Exception {
        // both a generic error and a content-filter block configured → the block wins
        HttpResponse response = handler.chaosErrorResponseOrNull(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.OPENAI)
                .withChaos(LlmChaosProfile.llmChaosProfile()
                    .withErrorStatus(500)
                    .withContentFilterBlockProbability(1.0)));
        assertThat(response.getStatusCode(), is(400));
        assertThat(MAPPER.readTree(response.getBodyAsString()).get("error").get("code").asText(), is("content_filter"));
    }
}
