package org.mockserver.llm.realtime;

/**
 * Deterministic streaming-timing model for realtime WebSocket event codecs — the WebSocket-transport analogue
 * of the SSE {@link org.mockserver.model.StreamingPhysics} used by the HTTP LLM codecs.
 *
 * <p>It is intentionally simpler (and jitter-free) than the SSE physics so that emitted event sequences are
 * byte-for-byte reproducible in tests: every content delta is spaced by a uniform {@code 1000 / tokensPerSecond}
 * milliseconds, and the very first content delta additionally waits {@code timeToFirstTokenMillis}. Structural
 * events (session/response lifecycle, done markers) are emitted with no delay.
 */
public final class RealtimeStreamingPhysics {

    /** Default emission rate (tokens per second) when none is configured. */
    public static final int DEFAULT_TOKENS_PER_SECOND = 50;

    private final int tokensPerSecond;
    private final long timeToFirstTokenMillis;

    public RealtimeStreamingPhysics(int tokensPerSecond, long timeToFirstTokenMillis) {
        this.tokensPerSecond = tokensPerSecond >= 1 ? tokensPerSecond : DEFAULT_TOKENS_PER_SECOND;
        this.timeToFirstTokenMillis = Math.max(0L, timeToFirstTokenMillis);
    }

    /** Physics with the default rate and no time-to-first-token delay. */
    public static RealtimeStreamingPhysics defaults() {
        return new RealtimeStreamingPhysics(DEFAULT_TOKENS_PER_SECOND, 0L);
    }

    public int getTokensPerSecond() {
        return tokensPerSecond;
    }

    public long getTimeToFirstTokenMillis() {
        return timeToFirstTokenMillis;
    }

    /** Uniform per-delta delay in milliseconds, derived from the rate. */
    public long perTokenDelayMillis() {
        return Math.max(0L, Math.round(1000.0 / tokensPerSecond));
    }
}
