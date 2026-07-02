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

public class OpenAiChatCompletionsCodecStreamingTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OpenAiChatCompletionsCodec codec = new OpenAiChatCompletionsCodec();

    @Test
    public void shouldProduceCorrectChunkSequenceForTextOnly() throws Exception {
        // given
        Completion completion = completion().withText("Hello world");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then — first chunk (role), N text chunks, final chunk (finish_reason), [DONE]
        assertThat(events.size(), is(greaterThanOrEqualTo(5)));

        // First chunk: role-only
        JsonNode first = OBJECT_MAPPER.readTree(events.get(0).getData());
        assertThat(first.get("object").asText(), is("chat.completion.chunk"));
        JsonNode delta0 = first.get("choices").get(0).get("delta");
        assertThat(delta0.get("role").asText(), is("assistant"));
        assertThat(first.get("choices").get(0).get("finish_reason").isNull(), is(true));

        // Last event: [DONE]
        SseEvent lastEvent = events.get(events.size() - 1);
        assertThat(lastEvent.getData(), is("[DONE]"));
        assertThat(lastEvent.getEvent(), is(nullValue()));

        // Second-to-last event: final chunk with finish_reason
        JsonNode finalChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 2).getData());
        assertThat(finalChunk.get("choices").get(0).get("finish_reason").asText(), is("stop"));
    }

    @Test
    public void shouldProduceSubwordContentDeltaChunksByDefault() throws Exception {
        // given — subword streaming is the default: "Hello world" segments into
        // finer subword deltas ["Hell", "o", " worl", "d"]
        Completion completion = completion().withText("Hello world");

        // when — null physics selects the default (subword) split
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then — 4 content chunks between first (role) and final (finish_reason) and [DONE]
        int contentDeltaCount = 0;
        StringBuilder concatenated = new StringBuilder();
        for (int i = 1; i < events.size() - 2; i++) {
            JsonNode chunk = OBJECT_MAPPER.readTree(events.get(i).getData());
            JsonNode delta = chunk.get("choices").get(0).get("delta");
            if (delta.has("content")) {
                contentDeltaCount++;
                concatenated.append(delta.get("content").asText());
            }
        }
        assertThat(contentDeltaCount, is(4));
        assertThat(concatenated.toString(), is("Hello world"));
    }

    @Test
    public void shouldProduceWholeWordContentDeltaChunksWhenSubwordStreamingDisabled() throws Exception {
        // given — an explicit subwordStreaming=false opts back into whole-word deltas:
        // "Hello world" -> ["Hello", " ", "world"]
        Completion completion = completion().withText("Hello world");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o",
            StreamingPhysics.streamingPhysics().withSubwordStreaming(false));

        // then — 3 content chunks between first (role) and final (finish_reason) and [DONE]
        int contentDeltaCount = 0;
        StringBuilder concatenated = new StringBuilder();
        for (int i = 1; i < events.size() - 2; i++) {
            JsonNode chunk = OBJECT_MAPPER.readTree(events.get(i).getData());
            JsonNode delta = chunk.get("choices").get(0).get("delta");
            if (delta.has("content")) {
                contentDeltaCount++;
                concatenated.append(delta.get("content").asText());
            }
        }
        assertThat(contentDeltaCount, is(3));
        assertThat(concatenated.toString(), is("Hello world"));
    }

    @Test
    public void shouldProduceToolCallDelta() throws Exception {
        // given
        Completion completion = completion()
            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"));

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then — first chunk (role), tool call chunk, final chunk (finish_reason), [DONE]
        assertThat(events.size(), is(4));

        JsonNode toolChunk = OBJECT_MAPPER.readTree(events.get(1).getData());
        JsonNode delta = toolChunk.get("choices").get(0).get("delta");
        assertThat(delta.has("tool_calls"), is(true));
        JsonNode toolCall = delta.get("tool_calls").get(0);
        assertThat(toolCall.get("id").asText(), startsWith("call_"));
        assertThat(toolCall.get("type").asText(), is("function"));
        assertThat(toolCall.get("function").get("name").asText(), is("get_weather"));
        assertThat(toolCall.get("function").get("arguments").asText(), is("{\"city\":\"Paris\"}"));

        // finish_reason should be "tool_calls"
        JsonNode finalChunk = OBJECT_MAPPER.readTree(events.get(2).getData());
        assertThat(finalChunk.get("choices").get(0).get("finish_reason").asText(), is("tool_calls"));
    }

    @Test
    public void shouldUseConsistentIdAndCreatedAcrossChunks() throws Exception {
        // given
        Completion completion = completion().withText("test tokens here");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then — all JSON chunks share the same id and created
        String expectedId = null;
        Long expectedCreated = null;
        for (SseEvent event : events) {
            if ("[DONE]".equals(event.getData())) {
                continue;
            }
            JsonNode chunk = OBJECT_MAPPER.readTree(event.getData());
            String id = chunk.get("id").asText();
            long created = chunk.get("created").asLong();
            if (expectedId == null) {
                expectedId = id;
                expectedCreated = created;
            }
            assertThat(id, is(expectedId));
            assertThat(created, is(expectedCreated));
        }
    }

    @Test
    public void shouldProduceDoneEvent() throws Exception {
        // given
        Completion completion = completion().withText("test");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then
        SseEvent lastEvent = events.get(events.size() - 1);
        assertThat(lastEvent.getData(), is("[DONE]"));
        // [DONE] has no event name
        assertThat(lastEvent.getEvent(), is(nullValue()));
    }

    @Test
    public void shouldMapFinishReasonInStreamingChunks() throws Exception {
        // given
        Completion completion = completion()
            .withText("test")
            .withStopReason("max_tokens");

        // when
        List<SseEvent> events = codec.encodeStreaming(completion, "gpt-4o", null);

        // then
        JsonNode finalChunk = OBJECT_MAPPER.readTree(events.get(events.size() - 2).getData());
        assertThat(finalChunk.get("choices").get(0).get("finish_reason").asText(), is("length"));
    }
}
