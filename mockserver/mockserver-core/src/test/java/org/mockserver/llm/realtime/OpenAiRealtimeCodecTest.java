package org.mockserver.llm.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class OpenAiRealtimeCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    private static List<String> types(List<RealtimeEvent> events) throws Exception {
        List<String> types = new ArrayList<>();
        for (RealtimeEvent event : events) {
            types.add(parse(event.json()).path("type").asText());
        }
        return types;
    }

    @Test
    public void shouldEncodeSessionCreated() throws Exception {
        JsonNode node = parse(OpenAiRealtimeCodec.sessionCreated("gpt-realtime", "sess_123"));
        assertThat(node.path("type").asText(), is("session.created"));
        assertThat(node.path("session").path("id").asText(), is("sess_123"));
        assertThat(node.path("session").path("model").asText(), is("gpt-realtime"));
        assertThat(node.path("session").path("object").asText(), is("realtime.session"));
        assertThat(node.path("event_id").asText(), not(is("")));
    }

    @Test
    public void shouldEncodeSessionUpdatedAndItemCreated() throws Exception {
        assertThat(parse(OpenAiRealtimeCodec.sessionUpdated("gpt-realtime", "sess_1")).path("type").asText(),
            is("session.updated"));
        JsonNode item = parse(OpenAiRealtimeCodec.conversationItemCreated("item_9"));
        assertThat(item.path("type").asText(), is("conversation.item.created"));
        assertThat(item.path("item").path("id").asText(), is("item_9"));
        assertThat(item.path("item").path("role").asText(), is("user"));
    }

    @Test
    public void shouldEncodeAudioResponseLifecycleInOrder() throws Exception {
        RealtimeTurn turn = RealtimeTurn.realtimeTurn("Hello there friend")
            .withInputTokens(11).withOutputTokens(3);
        List<RealtimeEvent> events = OpenAiRealtimeCodec.responseSequence(turn, RealtimeModality.AUDIO, RealtimeStreamingPhysics.defaults());
        List<String> types = types(events);

        assertThat(types.get(0), is("response.created"));
        assertThat(types.get(1), is("response.output_item.added"));
        assertThat(types.get(2), is("response.content_part.added"));
        assertThat(types, hasItem("response.output_audio_transcript.delta"));
        assertThat(types, hasItem("response.output_audio.delta"));
        assertThat(types, hasItem("response.output_audio_transcript.done"));
        assertThat(types, hasItem("response.output_audio.done"));
        assertThat(types, hasItem("response.content_part.done"));
        assertThat(types, hasItem("response.output_item.done"));
        assertThat(types.get(types.size() - 1), is("response.done"));
        // there must be no text-modality events in audio mode
        assertThat(types, not(hasItem("response.output_text.delta")));
    }

    @Test
    public void shouldStreamTranscriptDeltasThatReconstructTheText() throws Exception {
        String text = "The capital of France is Paris.";
        List<RealtimeEvent> events = OpenAiRealtimeCodec.responseSequence(
            RealtimeTurn.realtimeTurn(text), RealtimeModality.AUDIO, RealtimeStreamingPhysics.defaults());

        StringBuilder reconstructed = new StringBuilder();
        for (RealtimeEvent event : events) {
            JsonNode node = parse(event.json());
            if (node.path("type").asText().equals("response.output_audio_transcript.delta")) {
                reconstructed.append(node.path("delta").asText());
            }
        }
        assertThat(reconstructed.toString(), is(text));
    }

    @Test
    public void shouldReportUsageOnResponseDone() throws Exception {
        RealtimeTurn turn = RealtimeTurn.realtimeTurn("one two")
            .withInputTokens(42).withOutputTokens(7);
        List<RealtimeEvent> events = OpenAiRealtimeCodec.responseSequence(turn, RealtimeModality.AUDIO, RealtimeStreamingPhysics.defaults());
        JsonNode done = parse(events.get(events.size() - 1).json());
        assertThat(done.path("type").asText(), is("response.done"));
        JsonNode usage = done.path("response").path("usage");
        assertThat(usage.path("input_tokens").asInt(), is(42));
        assertThat(usage.path("output_tokens").asInt(), is(7));
        assertThat(usage.path("total_tokens").asInt(), is(49));
        assertThat(done.path("response").path("status").asText(), is("completed"));
    }

    @Test
    public void shouldDefaultOutputTokensToStreamedTokenCount() throws Exception {
        List<RealtimeEvent> events = OpenAiRealtimeCodec.responseSequence(
            RealtimeTurn.realtimeTurn("alpha beta gamma"), RealtimeModality.AUDIO, RealtimeStreamingPhysics.defaults());
        JsonNode usage = parse(events.get(events.size() - 1).json()).path("response").path("usage");
        assertThat(usage.path("output_tokens").asInt(), is(3));
    }

    @Test
    public void shouldEncodeTextModalityWithTextDeltas() throws Exception {
        List<RealtimeEvent> events = OpenAiRealtimeCodec.responseSequence(
            RealtimeTurn.realtimeTurn("hi there"), RealtimeModality.TEXT, RealtimeStreamingPhysics.defaults());
        List<String> types = types(events);
        assertThat(types, hasItem("response.output_text.delta"));
        assertThat(types, hasItem("response.output_text.done"));
        assertThat(types, not(hasItem("response.output_audio.delta")));
        assertThat(types, not(hasItem("response.output_audio_transcript.delta")));
    }

    @Test
    public void shouldApplyTimeToFirstTokenToFirstDeltaOnly() throws Exception {
        RealtimeStreamingPhysics physics = new RealtimeStreamingPhysics(100, 250L); // perToken = 10ms
        List<RealtimeEvent> events = OpenAiRealtimeCodec.responseSequence(
            RealtimeTurn.realtimeTurn("aa bb cc"), RealtimeModality.TEXT, physics);
        List<Long> deltaDelays = new ArrayList<>();
        for (RealtimeEvent event : events) {
            if (parse(event.json()).path("type").asText().equals("response.output_text.delta")) {
                deltaDelays.add(event.delayMillis());
            }
        }
        assertThat(deltaDelays.size(), is(3));
        assertThat(deltaDelays.get(0), is(260L)); // 10 + 250
        assertThat(deltaDelays.get(1), is(10L));
        assertThat(deltaDelays.get(2), is(10L));
    }

    @Test
    public void shouldEmitSingleExplicitAudioPayloadWhenProvided() throws Exception {
        RealtimeTurn turn = RealtimeTurn.realtimeTurn("hello world").withAudioBase64("QUJD");
        List<RealtimeEvent> events = OpenAiRealtimeCodec.responseSequence(turn, RealtimeModality.AUDIO, RealtimeStreamingPhysics.defaults());
        int audioDeltas = 0;
        String payload = null;
        for (RealtimeEvent event : events) {
            JsonNode node = parse(event.json());
            if (node.path("type").asText().equals("response.output_audio.delta")) {
                audioDeltas++;
                payload = node.path("delta").asText();
            }
        }
        assertThat(audioDeltas, is(1));
        assertThat(payload, is("QUJD"));
    }

    @Test
    public void shouldProduceValidJsonWhenTextContainsQuotesAndNewlines() throws Exception {
        RealtimeTurn turn = RealtimeTurn.realtimeTurn("say \"hi\"\nand bye");
        List<RealtimeEvent> events = OpenAiRealtimeCodec.responseSequence(turn, RealtimeModality.TEXT, RealtimeStreamingPhysics.defaults());
        for (RealtimeEvent event : events) {
            parse(event.json()); // must not throw
        }
        JsonNode done = parse(events.get(events.size() - 1).json());
        assertThat(done.path("type").asText(), is("response.done"));
    }

    @Test
    public void shouldHandleEmptyTurnWithoutDeltas() throws Exception {
        List<RealtimeEvent> events = OpenAiRealtimeCodec.responseSequence(
            RealtimeTurn.realtimeTurn(""), RealtimeModality.AUDIO, RealtimeStreamingPhysics.defaults());
        List<String> types = types(events);
        assertThat(types.get(0), is("response.created"));
        assertThat(types.get(types.size() - 1), is("response.done"));
        assertThat(types, not(hasItem("response.output_audio_transcript.delta")));
    }
}
