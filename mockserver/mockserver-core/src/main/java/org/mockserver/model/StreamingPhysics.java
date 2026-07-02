package org.mockserver.model;

import java.util.Objects;

public class StreamingPhysics extends ObjectWithJsonToString {
    private int hashCode;
    private Delay timeToFirstToken;
    private Integer tokensPerSecond;
    private Double jitter;
    private Long seed;
    private Boolean subwordStreaming;

    public static StreamingPhysics streamingPhysics() {
        return new StreamingPhysics();
    }

    public StreamingPhysics withTimeToFirstToken(Delay timeToFirstToken) {
        this.timeToFirstToken = timeToFirstToken;
        this.hashCode = 0;
        return this;
    }

    public Delay getTimeToFirstToken() {
        return timeToFirstToken;
    }

    public StreamingPhysics withTokensPerSecond(Integer tokensPerSecond) {
        if (tokensPerSecond != null && (tokensPerSecond < 1 || tokensPerSecond > 10000)) {
            throw new IllegalArgumentException("tokensPerSecond must be between 1 and 10000");
        }
        this.tokensPerSecond = tokensPerSecond;
        this.hashCode = 0;
        return this;
    }

    public Integer getTokensPerSecond() {
        return tokensPerSecond;
    }

    public StreamingPhysics withJitter(Double jitter) {
        if (jitter != null && (jitter < 0.0 || jitter > 1.0)) {
            throw new IllegalArgumentException("jitter must be between 0.0 and 1.0");
        }
        this.jitter = jitter;
        this.hashCode = 0;
        return this;
    }

    public Double getJitter() {
        return jitter;
    }

    public StreamingPhysics withSeed(Long seed) {
        this.seed = seed;
        this.hashCode = 0;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    /**
     * When {@code true}, streaming responses emit finer, subword-sized deltas
     * (a lightweight BPE approximation, see {@code org.mockserver.llm.TokenCounter})
     * instead of the default whole-word splitting. Off (or {@code null}) preserves
     * the long-standing whitespace-boundary deltas. The per-event timing math is
     * unchanged — each delta is still one physics event — so subword mode simply
     * produces more, smaller events, closer to a real provider's per-token stream.
     */
    public StreamingPhysics withSubwordStreaming(Boolean subwordStreaming) {
        this.subwordStreaming = subwordStreaming;
        this.hashCode = 0;
        return this;
    }

    public Boolean getSubwordStreaming() {
        return subwordStreaming;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        StreamingPhysics that = (StreamingPhysics) o;
        return Objects.equals(timeToFirstToken, that.timeToFirstToken) &&
            Objects.equals(tokensPerSecond, that.tokensPerSecond) &&
            Objects.equals(jitter, that.jitter) &&
            Objects.equals(seed, that.seed) &&
            Objects.equals(subwordStreaming, that.subwordStreaming);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(timeToFirstToken, tokensPerSecond, jitter, seed, subwordStreaming);
        }
        return hashCode;
    }
}
