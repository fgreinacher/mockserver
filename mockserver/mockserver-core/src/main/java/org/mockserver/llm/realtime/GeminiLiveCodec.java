package org.mockserver.llm.realtime;

import org.mockserver.llm.JsonEscape;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure event codec for the <b>Google Gemini Live API</b> ({@code BidiGenerateContent} over WebSocket). It
 * hand-assembles the provider-correct server message JSON frames a client receives, so an application using the
 * Gemini Live SDK can be tested fully offline. Every user-supplied string is routed through
 * {@link JsonEscape#escape(String)} before embedding.
 *
 * <p><b>Message coverage.</b> The codec produces the {@code setupComplete} handshake acknowledgement (returned in
 * response to the client's {@code setup} message) and, for one client {@code clientContent} turn, a stream of
 * {@code serverContent} messages: per token a {@code modelTurn} chunk ({@code parts[].text} in text modality;
 * {@code parts[].inlineData} audio + {@code outputTranscription} in audio modality), then a
 * {@code generationComplete} marker, and finally a {@code turnComplete} message carrying {@code usageMetadata}.
 *
 * <p><b>Deferred protocol corners</b> (documented rather than half-implemented, see
 * {@code docs/code/ai-protocol-mocking.md}): {@code realtimeInput} / {@code realtimeInputAcknowledgement}
 * (streamed audio/video input), {@code toolCall} / {@code toolCallCancellation} / {@code toolResponse},
 * {@code goAway} and {@code sessionResumptionUpdate}, and interruption ({@code interrupted}) signalling. The
 * audio bytes are opaque silence placeholders — the fidelity target is the event protocol, not audio DSP.
 */
public final class GeminiLiveCodec {

    private GeminiLiveCodec() {
    }

    private static String esc(String value) {
        return JsonEscape.escape(value);
    }

    /** The {@code setupComplete} frame returned in response to the client's {@code setup} message. */
    public static String setupComplete() {
        return "{\"setupComplete\":{}}";
    }

    /**
     * The full ordered {@code serverContent} message stream emitted for one client {@code clientContent} turn.
     *
     * @param turn     the scripted assistant response
     * @param modality {@link RealtimeModality#AUDIO} (inlineData audio + outputTranscription) or {@link RealtimeModality#TEXT}
     * @param physics  streaming timing (per-delta delay, time-to-first-token)
     * @return ordered message frames with per-frame delays
     */
    public static List<RealtimeEvent> serverContentSequence(RealtimeTurn turn, RealtimeModality modality, RealtimeStreamingPhysics physics) {
        if (physics == null) {
            physics = RealtimeStreamingPhysics.defaults();
        }
        boolean audio = modality != RealtimeModality.TEXT;
        String content = audio ? turn.resolvedTranscript() : turn.getText();
        List<String> tokens = RealtimeText.tokenize(content);

        int outputTokens = turn.getOutputTokens() != null ? turn.getOutputTokens() : tokens.size();
        int inputTokens = turn.getInputTokens() != null ? turn.getInputTokens() : 0;

        List<RealtimeEvent> events = new ArrayList<>();
        long perToken = physics.perTokenDelayMillis();
        long ttft = physics.getTimeToFirstTokenMillis();
        boolean hasAudioPayload = turn.getAudioBase64() != null;

        for (int i = 0; i < tokens.size(); i++) {
            long delay = perToken + (i == 0 ? ttft : 0L);
            String token = tokens.get(i);
            if (audio) {
                String data = hasAudioPayload ? esc(turn.getAudioBase64()) : RealtimeText.SILENCE_CHUNK_BASE64;
                events.add(RealtimeEvent.delayed(
                    "{\"serverContent\":{\"modelTurn\":{\"role\":\"model\",\"parts\":[{\"inlineData\":{" +
                        "\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"" + data + "\"}}]}," +
                        "\"outputTranscription\":{\"text\":\"" + esc(token) + "\"}}}", delay));
            } else {
                events.add(RealtimeEvent.delayed(
                    "{\"serverContent\":{\"modelTurn\":{\"role\":\"model\",\"parts\":[{\"text\":\"" +
                        esc(token) + "\"}]}}}", delay));
            }
        }

        events.add(RealtimeEvent.immediate("{\"serverContent\":{\"generationComplete\":true}}"));

        int totalTokens = inputTokens + outputTokens;
        events.add(RealtimeEvent.immediate(
            "{\"serverContent\":{\"turnComplete\":true}," +
                "\"usageMetadata\":{\"promptTokenCount\":" + inputTokens +
                ",\"responseTokenCount\":" + outputTokens +
                ",\"totalTokenCount\":" + totalTokens + "}}"));

        return events;
    }
}
