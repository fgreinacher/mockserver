package org.mockserver.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.llm.codec.AnthropicCodec;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

public class LlmRefusalPresetsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void anthropicRefusalCompletionHasRefusalStopReason() {
        Completion completion = LlmRefusalPresets.anthropicRefusal();
        assertThat(completion.getStopReason(), is("refusal"));
        assertThat(completion.getText(), is(nullValue()));
    }

    @Test
    public void anthropicRefusalEncodesToRefusalStopReasonAndEmptyContent() throws Exception {
        HttpResponse encoded = new AnthropicCodec().encode(LlmRefusalPresets.anthropicRefusal(), "claude-sonnet-4-20250514");
        assertThat(encoded.getStatusCode(), is(200));
        JsonNode root = MAPPER.readTree(encoded.getBodyAsString());
        assertThat(root.get("stop_reason").asText(), is("refusal"));
        // Anthropic refusal carries no text content
        assertThat(root.get("content").size(), is(0));
    }

    @Test
    public void anthropicRefusalWithMessageCarriesText() throws Exception {
        HttpResponse encoded = new AnthropicCodec().encode(
            LlmRefusalPresets.anthropicRefusal("I can't help with that."), "claude-sonnet-4-20250514");
        JsonNode root = MAPPER.readTree(encoded.getBodyAsString());
        assertThat(root.get("stop_reason").asText(), is("refusal"));
        assertThat(root.get("content").get(0).get("type").asText(), is("text"));
        assertThat(root.get("content").get(0).get("text").asText(), is("I can't help with that."));
    }
}
