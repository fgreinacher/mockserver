package org.mockserver.llm.realtime;

/**
 * The realtime (voice / bidirectional WebSocket) LLM protocols MockServer can mock.
 *
 * <p>These are deliberately kept separate from the HTTP-request/response {@link org.mockserver.model.Provider}
 * enum: the realtime protocols are a WebSocket <em>event</em> stream rather than a single request/response
 * body, so they have their own codecs ({@link OpenAiRealtimeCodec}, {@link GeminiLiveCodec}) and are wired
 * through the {@code httpWebSocketResponse} action rather than {@code httpLlmResponse}. Mapping these onto the
 * HTTP {@code Provider} enum (for pricing / sniffer / detector parity) is deferred — see
 * {@code docs/code/llm-mocking.md}.
 */
public enum RealtimeProvider {
    /**
     * OpenAI Realtime API (GA 2025 event protocol) — {@code wss://api.openai.com/v1/realtime}.
     */
    OPENAI_REALTIME,
    /**
     * Google Gemini Live API — {@code BidiGenerateContent} over WebSocket.
     */
    GEMINI_LIVE
}
