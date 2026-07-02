package org.mockserver.model;

import java.util.Objects;

/**
 * Declarative fault/chaos injection for an {@link HttpLlmResponse}, for testing
 * agent resilience: probabilistic provider errors (e.g. 429/529 with a
 * {@code Retry-After} header), mid-stream truncation, and malformed SSE chunks.
 * <p>
 * Determinism: with {@code errorProbability} of {@code 1.0} (always) or
 * {@code 0.0}/null (never) the error decision is fully deterministic. A
 * fractional probability draws once per response; set {@code seed} to make that
 * single draw reproducible (note: a fixed seed yields the same decision every
 * time). Truncation and malformed-SSE are deterministic. Follows the model
 * field/{@code withX}/getter convention so it round-trips without a bespoke
 * (de)serializer.
 * <p>
 * It also carries an optional <em>stateful</em> request quota (a fixed-window
 * rate limit): when {@code quotaName}, {@code quotaLimit}, and
 * {@code quotaWindowMillis} are set, requests beyond {@code quotaLimit} within
 * the window are rejected with {@code quotaErrorStatus} (default 429) and the
 * {@code retryAfter} header. Unlike the probabilistic error this is
 * deterministic and counts real requests across the process (see
 * {@link org.mockserver.llm.LlmQuotaRegistry}); expectations sharing a
 * {@code quotaName} share one counter.
 * <p>
 * A separate <em>token-based</em> quota ({@code tokenQuotaLimit} +
 * {@code tokenQuotaWindowMillis}) models TPM/TPD limits: the cumulative token
 * count of each response is charged against the window and a 429 is returned
 * when the sum exceeds the limit. Both request-count and token quotas can be
 * active simultaneously on the same profile (they use independent counters
 * within the registry, namespaced by suffix).
 */
public class LlmChaosProfile extends ObjectWithJsonToString {

    public enum TruncateMode {
        NONE,
        MID_STREAM
    }

    private int hashCode;
    private Integer errorStatus;       // e.g. 429, 529, 500
    private String retryAfter;         // value for the Retry-After header on an error (probabilistic or quota)
    private Double errorProbability;   // 0.0–1.0; null/0 = never inject an error
    private TruncateMode truncateMode; // streaming: truncate the SSE event stream
    private Double truncateAtFraction; // 0.0–1.0 fraction of events to keep before truncating
    private Boolean malformedSse;      // streaming: emit a malformed (broken-JSON) SSE chunk
    private Long seed;                 // optional, makes a fractional errorProbability reproducible
    private String quotaName;          // stateful quota: shared counter key
    private Integer quotaLimit;        // stateful quota: max requests allowed per window
    private Long quotaWindowMillis;    // stateful quota: window length in milliseconds
    private Integer quotaErrorStatus;  // stateful quota: status when exceeded (default 429)
    private Long tokenQuotaLimit;     // stateful token quota: max tokens allowed per window (TPM/TPD)
    private Long tokenQuotaWindowMillis; // stateful token quota: window length in milliseconds
    private String errorKind;          // optional: OVERLOAD | RATE_LIMIT | SERVER_ERROR -> emit the active provider's error body/status
    private Double contentFilterBlockProbability; // 0.0–1.0; null/0 = never inject a content-filter block

    public static LlmChaosProfile llmChaosProfile() {
        return new LlmChaosProfile();
    }

    public LlmChaosProfile withErrorStatus(Integer errorStatus) {
        if (errorStatus != null && (errorStatus < 100 || errorStatus > 599)) {
            throw new IllegalArgumentException("errorStatus must be between 100 and 599, got " + errorStatus);
        }
        this.errorStatus = errorStatus;
        this.hashCode = 0;
        return this;
    }

    public Integer getErrorStatus() {
        return errorStatus;
    }

    public LlmChaosProfile withRetryAfter(String retryAfter) {
        this.retryAfter = retryAfter;
        this.hashCode = 0;
        return this;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public LlmChaosProfile withErrorProbability(Double errorProbability) {
        if (errorProbability != null && (Double.isNaN(errorProbability) || errorProbability < 0.0 || errorProbability > 1.0)) {
            throw new IllegalArgumentException("errorProbability must be between 0.0 and 1.0, got " + errorProbability);
        }
        this.errorProbability = errorProbability;
        this.hashCode = 0;
        return this;
    }

    public Double getErrorProbability() {
        return errorProbability;
    }

    public LlmChaosProfile withTruncateMode(TruncateMode truncateMode) {
        this.truncateMode = truncateMode;
        this.hashCode = 0;
        return this;
    }

    public TruncateMode getTruncateMode() {
        return truncateMode;
    }

    public LlmChaosProfile withTruncateAtFraction(Double truncateAtFraction) {
        if (truncateAtFraction != null && (Double.isNaN(truncateAtFraction) || truncateAtFraction < 0.0 || truncateAtFraction > 1.0)) {
            throw new IllegalArgumentException("truncateAtFraction must be between 0.0 and 1.0, got " + truncateAtFraction);
        }
        this.truncateAtFraction = truncateAtFraction;
        this.hashCode = 0;
        return this;
    }

    public Double getTruncateAtFraction() {
        return truncateAtFraction;
    }

    public LlmChaosProfile withMalformedSse(Boolean malformedSse) {
        this.malformedSse = malformedSse;
        this.hashCode = 0;
        return this;
    }

    public Boolean getMalformedSse() {
        return malformedSse;
    }

    public LlmChaosProfile withSeed(Long seed) {
        this.seed = seed;
        this.hashCode = 0;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    public LlmChaosProfile withQuotaName(String quotaName) {
        this.quotaName = quotaName;
        this.hashCode = 0;
        return this;
    }

    public String getQuotaName() {
        return quotaName;
    }

    public LlmChaosProfile withQuotaLimit(Integer quotaLimit) {
        if (quotaLimit != null && quotaLimit < 1) {
            throw new IllegalArgumentException("quotaLimit must be >= 1, got " + quotaLimit);
        }
        this.quotaLimit = quotaLimit;
        this.hashCode = 0;
        return this;
    }

    public Integer getQuotaLimit() {
        return quotaLimit;
    }

    public LlmChaosProfile withQuotaWindowMillis(Long quotaWindowMillis) {
        if (quotaWindowMillis != null && quotaWindowMillis < 1) {
            throw new IllegalArgumentException("quotaWindowMillis must be >= 1, got " + quotaWindowMillis);
        }
        this.quotaWindowMillis = quotaWindowMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getQuotaWindowMillis() {
        return quotaWindowMillis;
    }

    public LlmChaosProfile withQuotaErrorStatus(Integer quotaErrorStatus) {
        if (quotaErrorStatus != null && (quotaErrorStatus < 100 || quotaErrorStatus > 599)) {
            throw new IllegalArgumentException("quotaErrorStatus must be between 100 and 599, got " + quotaErrorStatus);
        }
        this.quotaErrorStatus = quotaErrorStatus;
        this.hashCode = 0;
        return this;
    }

    public Integer getQuotaErrorStatus() {
        return quotaErrorStatus;
    }

    /**
     * Maximum tokens allowed within the token quota window (TPM or TPD depending
     * on window size). Requires {@code quotaName} and {@code tokenQuotaWindowMillis}
     * to be set. Uses the same {@code quotaErrorStatus} and {@code retryAfter} as
     * the request-count quota.
     */
    public LlmChaosProfile withTokenQuotaLimit(Long tokenQuotaLimit) {
        if (tokenQuotaLimit != null && tokenQuotaLimit < 1) {
            throw new IllegalArgumentException("tokenQuotaLimit must be >= 1, got " + tokenQuotaLimit);
        }
        this.tokenQuotaLimit = tokenQuotaLimit;
        this.hashCode = 0;
        return this;
    }

    public Long getTokenQuotaLimit() {
        return tokenQuotaLimit;
    }

    /**
     * Window length in milliseconds for the token-based quota. Requires
     * {@code quotaName} and {@code tokenQuotaLimit} to be set.
     */
    public LlmChaosProfile withTokenQuotaWindowMillis(Long tokenQuotaWindowMillis) {
        if (tokenQuotaWindowMillis != null && tokenQuotaWindowMillis < 1) {
            throw new IllegalArgumentException("tokenQuotaWindowMillis must be >= 1, got " + tokenQuotaWindowMillis);
        }
        this.tokenQuotaWindowMillis = tokenQuotaWindowMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getTokenQuotaWindowMillis() {
        return tokenQuotaWindowMillis;
    }

    /**
     * Optional provider-specific error shape. When set to {@code OVERLOAD},
     * {@code RATE_LIMIT}, or {@code SERVER_ERROR}, an injected chaos / quota error
     * emits the <em>active provider's</em> distinct error body (e.g. Anthropic's
     * {@code overloaded_error} at HTTP 529, OpenAI's {@code rate_limit_exceeded}
     * envelope), so client SDK retry/backoff logic that parses the body can be
     * tested faithfully. When unset (or the provider is unknown), the generic
     * chaos body is used and any explicit {@code errorStatus}/{@code quotaErrorStatus}
     * still applies. An explicit status, when set, overrides the provider's natural
     * status for the kind while keeping the provider-correct body. Case-insensitive;
     * an unrecognised value falls back to the generic body.
     */
    public LlmChaosProfile withErrorKind(String errorKind) {
        this.errorKind = errorKind;
        this.hashCode = 0;
        return this;
    }

    public String getErrorKind() {
        return errorKind;
    }

    /**
     * Optional probability (0.0–1.0) of injecting a <em>content-filter block</em> —
     * the provider-correct response an LLM returns when it refuses on content-policy
     * grounds. Unlike the generic {@code errorKind}/{@code errorStatus} path, the shape
     * is provider-specific: OpenAI-family → HTTP 400 with a {@code content_filter} error;
     * Azure → HTTP 400 whose {@code innererror} carries the filter result; Anthropic/Bedrock
     * → HTTP 200 with {@code stop_reason:"refusal"}; Gemini → HTTP 200 with
     * {@code finishReason:"SAFETY"}. Probabilistic and seeded-deterministic in exactly the
     * same way as {@code errorProbability} (shares {@code seed}): {@code null}/{@code 0.0}
     * never fires, {@code 1.0} always fires, a fractional value draws once. When it fires it
     * takes priority over the generic probabilistic error. The flagged severities come from
     * the response's {@code contentFilter} (Azure), defaulting to hate at {@code high}.
     */
    public LlmChaosProfile withContentFilterBlockProbability(Double contentFilterBlockProbability) {
        if (contentFilterBlockProbability != null
            && (Double.isNaN(contentFilterBlockProbability) || contentFilterBlockProbability < 0.0 || contentFilterBlockProbability > 1.0)) {
            throw new IllegalArgumentException("contentFilterBlockProbability must be between 0.0 and 1.0, got " + contentFilterBlockProbability);
        }
        this.contentFilterBlockProbability = contentFilterBlockProbability;
        this.hashCode = 0;
        return this;
    }

    public Double getContentFilterBlockProbability() {
        return contentFilterBlockProbability;
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
        LlmChaosProfile that = (LlmChaosProfile) o;
        return Objects.equals(errorStatus, that.errorStatus) &&
            Objects.equals(retryAfter, that.retryAfter) &&
            Objects.equals(errorProbability, that.errorProbability) &&
            truncateMode == that.truncateMode &&
            Objects.equals(truncateAtFraction, that.truncateAtFraction) &&
            Objects.equals(malformedSse, that.malformedSse) &&
            Objects.equals(seed, that.seed) &&
            Objects.equals(quotaName, that.quotaName) &&
            Objects.equals(quotaLimit, that.quotaLimit) &&
            Objects.equals(quotaWindowMillis, that.quotaWindowMillis) &&
            Objects.equals(quotaErrorStatus, that.quotaErrorStatus) &&
            Objects.equals(tokenQuotaLimit, that.tokenQuotaLimit) &&
            Objects.equals(tokenQuotaWindowMillis, that.tokenQuotaWindowMillis) &&
            Objects.equals(errorKind, that.errorKind) &&
            Objects.equals(contentFilterBlockProbability, that.contentFilterBlockProbability);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(errorStatus, retryAfter, errorProbability, truncateMode, truncateAtFraction, malformedSse, seed,
                quotaName, quotaLimit, quotaWindowMillis, quotaErrorStatus, tokenQuotaLimit, tokenQuotaWindowMillis, errorKind,
                contentFilterBlockProbability);
        }
        return hashCode;
    }
}
