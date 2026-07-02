package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.model.*;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.ToolUse.toolUse;

public class AnthropicCodecStreamingTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AnthropicCodec codec = new AnthropicCodec();

    @Test
    public void shouldProduceCorrectEventSequenceForTextOnly() throws Exception {
        // given
        Completion completion = completion()
            .withText("Hello world")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(5));

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "claude-sonnet-4-20250514", null);

        // then
        // message_start, content_block_start, N content_block_delta, content_block_stop, message_delta, message_stop
        assertThat(events.size(), is(greaterThanOrEqualTo(6)));

        assertThat(events.get(0).getEvent(), is("message_start"));
        assertThat(events.get(1).getEvent(), is("content_block_start"));

        // text delta events should be in the middle
        int lastDeltaIndex = -1;
        for (int i = 2; i < events.size() - 3; i++) {
            assertThat(events.get(i).getEvent(), is("content_block_delta"));
            lastDeltaIndex = i;
        }

        assertThat(events.get(lastDeltaIndex + 1).getEvent(), is("content_block_stop"));
        assertThat(events.get(lastDeltaIndex + 2).getEvent(), is("message_delta"));
        assertThat(events.get(lastDeltaIndex + 3).getEvent(), is("message_stop"));
    }

    @Test
    public void shouldSplitTextIntoSubwordTokensByDefault() throws Exception {
        // given — subword streaming is the default: "Hello world" segments into
        // finer subword deltas ["Hell", "o", " worl", "d"]
        Completion completion = completion().withText("Hello world");

        // when — null physics selects the default (subword) split
        List<SseEvent> events = codec.encodeStreaming(completion, "test-model", null);

        // then — one content_block_delta per subword piece
        int deltaCount = 0;
        for (SseEvent event : events) {
            if ("content_block_delta".equals(event.getEvent())) {
                deltaCount++;
            }
        }
        assertThat(deltaCount, is(4));
    }

    @Test
    public void shouldSplitTextIntoWholeWordsWhenSubwordStreamingDisabled() throws Exception {
        // given — an explicit subwordStreaming=false opts back into whole-word deltas:
        // "Hello world" -> ["Hello", " ", "world"]
        Completion completion = completion().withText("Hello world");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "test-model",
            StreamingPhysics.streamingPhysics().withSubwordStreaming(false));

        // then — should have 3 content_block_delta events
        int deltaCount = 0;
        for (SseEvent event : events) {
            if ("content_block_delta".equals(event.getEvent())) {
                deltaCount++;
            }
        }
        // "Hello", " ", "world"
        assertThat(deltaCount, is(3));
    }

    @Test
    public void shouldIncludeCorrectTextInDeltas() throws Exception {
        // given
        Completion completion = completion().withText("Hello world");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "test-model", null);

        // then — verify the concatenated text matches
        StringBuilder concatenated = new StringBuilder();
        for (SseEvent event : events) {
            if ("content_block_delta".equals(event.getEvent())) {
                JsonNode data = OBJECT_MAPPER.readTree(event.getData());
                if (data.get("delta").has("text")) {
                    concatenated.append(data.get("delta").get("text").asText());
                }
            }
        }
        assertThat(concatenated.toString(), is("Hello world"));
    }

    @Test
    public void shouldProduceCorrectToolCallEvents() throws Exception {
        // given
        Completion completion = completion()
            .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"));

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "test-model", null);

        // then — structure: message_start, tool_start, tool_delta, tool_stop, message_delta, message_stop
        assertThat(events.size(), is(6));
        assertThat(events.get(0).getEvent(), is("message_start"));
        assertThat(events.get(1).getEvent(), is("content_block_start"));
        assertThat(events.get(2).getEvent(), is("content_block_delta"));
        assertThat(events.get(3).getEvent(), is("content_block_stop"));
        assertThat(events.get(4).getEvent(), is("message_delta"));
        assertThat(events.get(5).getEvent(), is("message_stop"));

        // Verify tool_use start
        JsonNode blockStart = OBJECT_MAPPER.readTree(events.get(1).getData());
        assertThat(blockStart.get("index").asInt(), is(0));
        assertThat(blockStart.get("content_block").get("type").asText(), is("tool_use"));
        assertThat(blockStart.get("content_block").get("name").asText(), is("search"));

        // Verify tool delta
        JsonNode blockDelta = OBJECT_MAPPER.readTree(events.get(2).getData());
        assertThat(blockDelta.get("delta").get("type").asText(), is("input_json_delta"));
    }

    @Test
    public void shouldProduceTextAndToolCallEvents() throws Exception {
        // given
        Completion completion = completion()
            .withText("Checking.")
            .withToolCall(toolUse("check").withArguments("{}"))
            .withStopReason("tool_use");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "test-model", null);

        // then — text block at index 0, tool block at index 1
        // Find the tool block start
        boolean foundToolBlock = false;
        for (SseEvent event : events) {
            if ("content_block_start".equals(event.getEvent())) {
                JsonNode data = OBJECT_MAPPER.readTree(event.getData());
                if (data.get("content_block").get("type").asText().equals("tool_use")) {
                    assertThat(data.get("index").asInt(), is(1));
                    foundToolBlock = true;
                }
            }
        }
        assertThat("tool block should be present", foundToolBlock, is(true));
    }

    @Test
    public void shouldIncludeUsageInMessageDelta() throws Exception {
        // given
        Completion completion = completion()
            .withText("Hello")
            .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(5));

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "test-model", null);

        // then
        SseEvent messageDelta = null;
        for (SseEvent event : events) {
            if ("message_delta".equals(event.getEvent())) {
                messageDelta = event;
            }
        }
        assertThat(messageDelta, is(notNullValue()));
        JsonNode data = OBJECT_MAPPER.readTree(messageDelta.getData());
        assertThat(data.get("usage").get("output_tokens").asInt(), is(5));
    }

    @Test
    public void shouldIncludeInputTokensInMessageStart() throws Exception {
        // given
        Completion completion = completion()
            .withText("Hello")
            .withUsage(Usage.usage().withInputTokens(42).withOutputTokens(8));

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "test-model", null);

        // then
        JsonNode startData = OBJECT_MAPPER.readTree(events.get(0).getData());
        assertThat(startData.get("message").get("usage").get("input_tokens").asInt(), is(42));
    }

    @Test
    public void shouldIncludeMessageIdInStart() throws Exception {
        // given
        Completion completion = completion().withText("test");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "test-model", null);

        // then
        JsonNode startData = OBJECT_MAPPER.readTree(events.get(0).getData());
        assertThat(startData.get("message").get("id").asText(), startsWith("msg_"));
    }

    @Test
    public void shouldProduceCorrectStopReasonInMessageDelta() throws Exception {
        // given
        Completion completion = completion()
            .withText("Hello")
            .withStopReason("max_tokens");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "test-model", null);

        // then
        SseEvent messageDelta = null;
        for (SseEvent event : events) {
            if ("message_delta".equals(event.getEvent())) {
                messageDelta = event;
            }
        }
        assertThat(messageDelta, is(notNullValue()));
        JsonNode data = OBJECT_MAPPER.readTree(messageDelta.getData());
        assertThat(data.get("delta").get("stop_reason").asText(), is("max_tokens"));
    }
}
