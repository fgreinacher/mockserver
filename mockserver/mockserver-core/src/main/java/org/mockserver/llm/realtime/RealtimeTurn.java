package org.mockserver.llm.realtime;

import org.mockserver.model.ObjectWithJsonToString;

import java.util.Objects;

/**
 * A provider-neutral description of one scripted assistant response in a realtime voice session — the realtime
 * analogue of {@link org.mockserver.model.Completion}. The codecs turn it into the provider-correct ordered
 * WebSocket event sequence.
 *
 * <ul>
 *     <li>{@link #text} — the visible/spoken text. In {@link RealtimeModality#TEXT} it is streamed as text
 *     deltas; in {@link RealtimeModality#AUDIO} it is streamed as the spoken-transcript delta stream.</li>
 *     <li>{@link #audioTranscript} — an explicit spoken transcript; when {@code null} the transcript is
 *     {@link #text}. Only used in {@link RealtimeModality#AUDIO}.</li>
 *     <li>{@link #audioBase64} — synthetic audio bytes (base64) to emit as the single audio payload. When
 *     {@code null} the codec emits a short silence placeholder per transcript token. The fidelity target is
 *     the event protocol, not audio DSP — these bytes are opaque placeholders.</li>
 *     <li>{@link #inputTokens} / {@link #outputTokens} — usage reported on the final {@code response.done}
 *     (OpenAI) / {@code usageMetadata} (Gemini). When {@code outputTokens} is {@code null} the codec reports
 *     the streamed transcript token count.</li>
 * </ul>
 */
public class RealtimeTurn extends ObjectWithJsonToString {

    private String text;
    private String audioTranscript;
    private String audioBase64;
    private Integer inputTokens;
    private Integer outputTokens;

    public static RealtimeTurn realtimeTurn() {
        return new RealtimeTurn();
    }

    public static RealtimeTurn realtimeTurn(String text) {
        return new RealtimeTurn().withText(text);
    }

    public RealtimeTurn withText(String text) {
        this.text = text;
        return this;
    }

    public String getText() {
        return text;
    }

    public RealtimeTurn withAudioTranscript(String audioTranscript) {
        this.audioTranscript = audioTranscript;
        return this;
    }

    public String getAudioTranscript() {
        return audioTranscript;
    }

    /** The transcript to stream in audio modality — the explicit {@link #audioTranscript} or, failing that, {@link #text}. */
    public String resolvedTranscript() {
        return audioTranscript != null ? audioTranscript : text;
    }

    public RealtimeTurn withAudioBase64(String audioBase64) {
        this.audioBase64 = audioBase64;
        return this;
    }

    public String getAudioBase64() {
        return audioBase64;
    }

    public RealtimeTurn withInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
        return this;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public RealtimeTurn withOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
        return this;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RealtimeTurn that = (RealtimeTurn) o;
        return Objects.equals(text, that.text) &&
            Objects.equals(audioTranscript, that.audioTranscript) &&
            Objects.equals(audioBase64, that.audioBase64) &&
            Objects.equals(inputTokens, that.inputTokens) &&
            Objects.equals(outputTokens, that.outputTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, audioTranscript, audioBase64, inputTokens, outputTokens);
    }
}
