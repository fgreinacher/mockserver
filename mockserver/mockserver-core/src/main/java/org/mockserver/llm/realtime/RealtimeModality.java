package org.mockserver.llm.realtime;

/**
 * The response modality a scripted realtime turn is rendered as.
 *
 * <ul>
 *     <li>{@link #AUDIO} — the model "speaks": the codec emits audio byte deltas plus a spoken-transcript
 *     delta stream (OpenAI {@code response.output_audio.delta} + {@code response.output_audio_transcript.delta};
 *     Gemini {@code inlineData} audio parts + {@code outputTranscription}). This is the realtime default.</li>
 *     <li>{@link #TEXT} — the model writes text only: the codec emits text deltas
 *     (OpenAI {@code response.output_text.delta}; Gemini {@code modelTurn} text parts).</li>
 * </ul>
 */
public enum RealtimeModality {
    AUDIO,
    TEXT
}
