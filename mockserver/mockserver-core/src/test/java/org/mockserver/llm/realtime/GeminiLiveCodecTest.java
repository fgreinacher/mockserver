package org.mockserver.llm.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GeminiLiveCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    @Test
    public void shouldEncodeSetupComplete() throws Exception {
        JsonNode node = parse(GeminiLiveCodec.setupComplete());
        assertThat(node.has("setupComplete"), is(true));
        assertThat(node.path("setupComplete").isObject(), is(true));
    }

    @Test
    public void shouldStreamTextPartsThenGenerationAndTurnComplete() throws Exception {
        String text = "Hi there friend";
        List<RealtimeEvent> events = GeminiLiveCodec.serverContentSequence(
            RealtimeTurn.realtimeTurn(text).withInputTokens(5).withOutputTokens(3),
            RealtimeModality.TEXT, RealtimeStreamingPhysics.defaults());

        StringBuilder reconstructed = new StringBuilder();
        boolean sawGenerationComplete = false;
        for (int i = 0; i < events.size(); i++) {
            JsonNode sc = parse(events.get(i).json()).path("serverContent");
            if (sc.path("modelTurn").has("parts")) {
                reconstructed.append(sc.path("modelTurn").path("parts").get(0).path("text").asText());
            }
            if (sc.path("generationComplete").asBoolean(false)) {
                sawGenerationComplete = true;
            }
        }
        assertThat(reconstructed.toString(), is(text));
        assertThat(sawGenerationComplete, is(true));

        // last message carries turnComplete + usageMetadata
        JsonNode last = parse(events.get(events.size() - 1).json());
        assertThat(last.path("serverContent").path("turnComplete").asBoolean(), is(true));
        assertThat(last.path("usageMetadata").path("promptTokenCount").asInt(), is(5));
        assertThat(last.path("usageMetadata").path("responseTokenCount").asInt(), is(3));
        assertThat(last.path("usageMetadata").path("totalTokenCount").asInt(), is(8));
    }

    @Test
    public void shouldEncodeAudioModalityWithInlineDataAndTranscription() throws Exception {
        List<RealtimeEvent> events = GeminiLiveCodec.serverContentSequence(
            RealtimeTurn.realtimeTurn("alpha beta"), RealtimeModality.AUDIO, RealtimeStreamingPhysics.defaults());

        boolean sawInlineData = false;
        StringBuilder transcript = new StringBuilder();
        for (RealtimeEvent event : events) {
            JsonNode sc = parse(event.json()).path("serverContent");
            JsonNode parts = sc.path("modelTurn").path("parts");
            if (parts.isArray() && parts.size() > 0 && parts.get(0).has("inlineData")) {
                sawInlineData = true;
                assertThat(parts.get(0).path("inlineData").path("mimeType").asText(), containsString("audio/pcm"));
            }
            if (sc.has("outputTranscription")) {
                transcript.append(sc.path("outputTranscription").path("text").asText());
            }
        }
        assertThat(sawInlineData, is(true));
        assertThat(transcript.toString(), is("alpha beta"));
    }

    @Test
    public void shouldDefaultResponseTokensToStreamedTokenCount() throws Exception {
        List<RealtimeEvent> events = GeminiLiveCodec.serverContentSequence(
            RealtimeTurn.realtimeTurn("one two three four"), RealtimeModality.TEXT, RealtimeStreamingPhysics.defaults());
        JsonNode last = parse(events.get(events.size() - 1).json());
        assertThat(last.path("usageMetadata").path("responseTokenCount").asInt(), is(4));
    }

    @Test
    public void shouldApplyTimeToFirstTokenToFirstDeltaOnly() throws Exception {
        RealtimeStreamingPhysics physics = new RealtimeStreamingPhysics(100, 200L); // perToken = 10ms
        List<RealtimeEvent> events = GeminiLiveCodec.serverContentSequence(
            RealtimeTurn.realtimeTurn("aa bb"), RealtimeModality.TEXT, physics);
        assertThat(events.get(0).delayMillis(), is(210L)); // 10 + 200
        assertThat(events.get(1).delayMillis(), is(10L));
    }

    @Test
    public void shouldProduceValidJsonWhenTextContainsQuotes() throws Exception {
        List<RealtimeEvent> events = GeminiLiveCodec.serverContentSequence(
            RealtimeTurn.realtimeTurn("say \"hi\" now"), RealtimeModality.TEXT, RealtimeStreamingPhysics.defaults());
        for (RealtimeEvent event : events) {
            parse(event.json()); // must not throw
        }
    }
}
