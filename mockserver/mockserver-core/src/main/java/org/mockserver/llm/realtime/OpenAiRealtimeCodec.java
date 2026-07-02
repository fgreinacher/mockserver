package org.mockserver.llm.realtime;

import org.mockserver.llm.JsonEscape;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure event codec for the <b>OpenAI Realtime API</b> (GA 2025 event protocol), spoken over a WebSocket at
 * {@code wss://api.openai.com/v1/realtime}. It hand-assembles the provider-correct server event JSON frames a
 * client receives, so an application using the Realtime SDK can be tested fully offline. Every user-supplied
 * string is routed through {@link JsonEscape#escape(String)} before embedding.
 *
 * <p><b>Event coverage.</b> The codec produces, for one {@code response.create}, the GA event lifecycle:
 * {@code response.created} → {@code response.output_item.added} → {@code response.content_part.added} →
 * (per token) {@code response.output_audio_transcript.delta} + {@code response.output_audio.delta} (audio
 * modality) or {@code response.output_text.delta} (text modality) →
 * {@code response.output_audio_transcript.done}/{@code response.output_text.done} →
 * {@code response.output_audio.done} (audio) → {@code response.content_part.done} →
 * {@code response.output_item.done} → {@code response.done} (with usage). It also produces the connect-time
 * {@code session.created}, and the {@code session.updated} / {@code conversation.item.created} acknowledgements.
 *
 * <p><b>Deferred protocol corners</b> (documented rather than half-implemented, see
 * {@code docs/code/ai-protocol-mocking.md}): server-side VAD / input-audio-buffer events
 * ({@code input_audio_buffer.speech_started} etc.), input-audio transcription events, function-call output
 * items, {@code rate_limits.updated}, and {@code error} frames. The audio bytes are opaque silence
 * placeholders — the fidelity target is the event protocol, not audio DSP.
 */
public final class OpenAiRealtimeCodec {

    private OpenAiRealtimeCodec() {
    }

    private static String eventId() {
        return "event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String esc(String value) {
        return JsonEscape.escape(value);
    }

    /**
     * The {@code session.created} frame a Realtime server sends immediately after the WebSocket handshake.
     */
    public static String sessionCreated(String model, String sessionId) {
        String id = sessionId != null ? sessionId : "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return "{\"type\":\"session.created\",\"event_id\":\"" + eventId() + "\"," +
            "\"session\":{\"id\":\"" + esc(id) + "\",\"object\":\"realtime.session\",\"type\":\"realtime\"," +
            "\"model\":\"" + esc(model != null ? model : "gpt-realtime") + "\"," +
            "\"output_modalities\":[\"audio\",\"text\"]}}";
    }

    /**
     * The {@code session.updated} acknowledgement returned in response to a client {@code session.update}.
     */
    public static String sessionUpdated(String model, String sessionId) {
        String id = sessionId != null ? sessionId : "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return "{\"type\":\"session.updated\",\"event_id\":\"" + eventId() + "\"," +
            "\"session\":{\"id\":\"" + esc(id) + "\",\"object\":\"realtime.session\",\"type\":\"realtime\"," +
            "\"model\":\"" + esc(model != null ? model : "gpt-realtime") + "\"," +
            "\"output_modalities\":[\"audio\",\"text\"]}}";
    }

    /**
     * The {@code conversation.item.created} acknowledgement returned in response to a client
     * {@code conversation.item.create}.
     */
    public static String conversationItemCreated(String itemId) {
        String id = itemId != null ? itemId : "item_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return "{\"type\":\"conversation.item.created\",\"event_id\":\"" + eventId() + "\"," +
            "\"previous_item_id\":null," +
            "\"item\":{\"id\":\"" + esc(id) + "\",\"object\":\"realtime.item\",\"type\":\"message\"," +
            "\"status\":\"completed\",\"role\":\"user\"}}";
    }

    /**
     * The full ordered server event sequence emitted for a single {@code response.create}.
     *
     * @param turn     the scripted assistant response
     * @param modality {@link RealtimeModality#AUDIO} (transcript + audio deltas) or {@link RealtimeModality#TEXT}
     * @param physics  streaming timing (per-delta delay, time-to-first-token)
     * @return ordered event frames with per-frame delays
     */
    public static List<RealtimeEvent> responseSequence(RealtimeTurn turn, RealtimeModality modality, RealtimeStreamingPhysics physics) {
        if (physics == null) {
            physics = RealtimeStreamingPhysics.defaults();
        }
        boolean audio = modality != RealtimeModality.TEXT;
        String responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String itemId = "item_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        String content = audio ? turn.resolvedTranscript() : turn.getText();
        List<String> tokens = RealtimeText.tokenize(content);

        int outputTokens = turn.getOutputTokens() != null ? turn.getOutputTokens() : tokens.size();
        int inputTokens = turn.getInputTokens() != null ? turn.getInputTokens() : 0;

        List<RealtimeEvent> events = new ArrayList<>();

        // --- lifecycle: created / item added / content part added ---
        events.add(RealtimeEvent.immediate(
            "{\"type\":\"response.created\",\"event_id\":\"" + eventId() + "\"," +
                "\"response\":{\"id\":\"" + responseId + "\",\"object\":\"realtime.response\"," +
                "\"status\":\"in_progress\",\"output\":[]}}"));

        events.add(RealtimeEvent.immediate(
            "{\"type\":\"response.output_item.added\",\"event_id\":\"" + eventId() + "\"," +
                "\"response_id\":\"" + responseId + "\",\"output_index\":0," +
                "\"item\":{\"id\":\"" + itemId + "\",\"object\":\"realtime.item\",\"type\":\"message\"," +
                "\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}"));

        String partOpen = audio ? "\"type\":\"audio\",\"transcript\":\"\"" : "\"type\":\"text\",\"text\":\"\"";
        events.add(RealtimeEvent.immediate(
            "{\"type\":\"response.content_part.added\",\"event_id\":\"" + eventId() + "\"," +
                "\"response_id\":\"" + responseId + "\",\"item_id\":\"" + itemId + "\"," +
                "\"output_index\":0,\"content_index\":0,\"part\":{" + partOpen + "}}"));

        // --- content deltas ---
        long perToken = physics.perTokenDelayMillis();
        long ttft = physics.getTimeToFirstTokenMillis();
        boolean hasAudioPayload = turn.getAudioBase64() != null;
        for (int i = 0; i < tokens.size(); i++) {
            long delay = perToken + (i == 0 ? ttft : 0L);
            String token = tokens.get(i);
            if (audio) {
                events.add(RealtimeEvent.delayed(
                    "{\"type\":\"response.output_audio_transcript.delta\",\"event_id\":\"" + eventId() + "\"," +
                        "\"response_id\":\"" + responseId + "\",\"item_id\":\"" + itemId + "\"," +
                        "\"output_index\":0,\"content_index\":0,\"delta\":\"" + esc(token) + "\"}", delay));
                // Paired audio byte delta (silence placeholder unless an explicit payload is supplied, sent once).
                if (!hasAudioPayload) {
                    events.add(RealtimeEvent.immediate(
                        "{\"type\":\"response.output_audio.delta\",\"event_id\":\"" + eventId() + "\"," +
                            "\"response_id\":\"" + responseId + "\",\"item_id\":\"" + itemId + "\"," +
                            "\"output_index\":0,\"content_index\":0,\"delta\":\"" + RealtimeText.SILENCE_CHUNK_BASE64 + "\"}"));
                }
            } else {
                events.add(RealtimeEvent.delayed(
                    "{\"type\":\"response.output_text.delta\",\"event_id\":\"" + eventId() + "\"," +
                        "\"response_id\":\"" + responseId + "\",\"item_id\":\"" + itemId + "\"," +
                        "\"output_index\":0,\"content_index\":0,\"delta\":\"" + esc(token) + "\"}", delay));
            }
        }
        if (audio && hasAudioPayload) {
            events.add(RealtimeEvent.immediate(
                "{\"type\":\"response.output_audio.delta\",\"event_id\":\"" + eventId() + "\"," +
                    "\"response_id\":\"" + responseId + "\",\"item_id\":\"" + itemId + "\"," +
                    "\"output_index\":0,\"content_index\":0,\"delta\":\"" + esc(turn.getAudioBase64()) + "\"}"));
        }

        // --- done markers ---
        if (audio) {
            events.add(RealtimeEvent.immediate(
                "{\"type\":\"response.output_audio_transcript.done\",\"event_id\":\"" + eventId() + "\"," +
                    "\"response_id\":\"" + responseId + "\",\"item_id\":\"" + itemId + "\"," +
                    "\"output_index\":0,\"content_index\":0,\"transcript\":\"" + esc(content) + "\"}"));
            events.add(RealtimeEvent.immediate(
                "{\"type\":\"response.output_audio.done\",\"event_id\":\"" + eventId() + "\"," +
                    "\"response_id\":\"" + responseId + "\",\"item_id\":\"" + itemId + "\"," +
                    "\"output_index\":0,\"content_index\":0}"));
        } else {
            events.add(RealtimeEvent.immediate(
                "{\"type\":\"response.output_text.done\",\"event_id\":\"" + eventId() + "\"," +
                    "\"response_id\":\"" + responseId + "\",\"item_id\":\"" + itemId + "\"," +
                    "\"output_index\":0,\"content_index\":0,\"text\":\"" + esc(content) + "\"}"));
        }

        String partClosed = audio
            ? "\"type\":\"audio\",\"transcript\":\"" + esc(content) + "\""
            : "\"type\":\"text\",\"text\":\"" + esc(content) + "\"";
        events.add(RealtimeEvent.immediate(
            "{\"type\":\"response.content_part.done\",\"event_id\":\"" + eventId() + "\"," +
                "\"response_id\":\"" + responseId + "\",\"item_id\":\"" + itemId + "\"," +
                "\"output_index\":0,\"content_index\":0,\"part\":{" + partClosed + "}}"));

        events.add(RealtimeEvent.immediate(
            "{\"type\":\"response.output_item.done\",\"event_id\":\"" + eventId() + "\"," +
                "\"response_id\":\"" + responseId + "\",\"output_index\":0," +
                "\"item\":{\"id\":\"" + itemId + "\",\"object\":\"realtime.item\",\"type\":\"message\"," +
                "\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{" + partClosed + "}]}}"));

        int totalTokens = inputTokens + outputTokens;
        events.add(RealtimeEvent.immediate(
            "{\"type\":\"response.done\",\"event_id\":\"" + eventId() + "\"," +
                "\"response\":{\"id\":\"" + responseId + "\",\"object\":\"realtime.response\"," +
                "\"status\":\"completed\"," +
                "\"output\":[{\"id\":\"" + itemId + "\",\"object\":\"realtime.item\",\"type\":\"message\"," +
                "\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{" + partClosed + "}]}]," +
                "\"usage\":{\"total_tokens\":" + totalTokens + ",\"input_tokens\":" + inputTokens +
                ",\"output_tokens\":" + outputTokens +
                ",\"input_token_details\":{\"text_tokens\":" + inputTokens + ",\"audio_tokens\":0}," +
                "\"output_token_details\":{\"text_tokens\":" + (audio ? 0 : outputTokens) +
                ",\"audio_tokens\":" + (audio ? outputTokens : 0) + "}}}}"));

        return events;
    }
}
