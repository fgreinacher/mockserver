package org.mockserver.mock.breakpoint;

import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A registered breakpoint: a request matcher + set of phases at which matching
 * forwarded exchanges should be paused for interactive inspection/modification.
 *
 * <p>The prebuilt {@link HttpRequestMatcher} is created once at registration time
 * (via {@link org.mockserver.matchers.MatcherBuilder#transformsToMatcher(RequestDefinition)})
 * and reused for every {@code findMatch} call — no allocation on the hot path.
 *
 * <p>The {@link #clientId} identifies the owning callback WebSocket client.
 * It is required -- matched exchanges are always dispatched over the callback
 * WebSocket to the owning client for interactive resolution.
 *
 * <p><b>Conditional (Nth-hit / skip-count) breakpoints:</b> an optional
 * {@link #skipCount} delays the first pause. The matcher still <em>matches</em>
 * normally on every hit, but only <em>pauses</em> once it has been hit more than
 * {@code skipCount} times. With {@code skipCount = 2} the breakpoint does NOT
 * pause on hits 1 and 2 and DOES pause from hit 3 onward. When {@code skipCount}
 * is {@code null} (the default) the breakpoint pauses on every hit (legacy
 * behaviour). The per-matcher hit counter ({@link #hitCount}) is incremented
 * atomically by {@link #shouldPause()}, so the decision is thread-safe under
 * concurrent matching.
 *
 * <p><b>Response-content conditional breakpoints:</b> optional response conditions
 * ({@link #responseStatusCodeMin}/{@link #responseStatusCodeMax} for an inclusive
 * status-code range, and {@link #responseBodyContains} for a regex searched within
 * the response body) gate whether a RESPONSE-phase breakpoint pauses. They are only
 * evaluated at the response phase (the response is not yet known at the request
 * phase). When set, the breakpoint pauses only when the response satisfies <em>all</em>
 * configured conditions (in addition to the request matcher and any skip-count). When
 * all are {@code null} (the default) the breakpoint pauses regardless of response
 * content (legacy behaviour). The {@code responseBodyContains} pattern is compiled
 * once at registration (find semantics — pauses if the body contains a match).
 *
 * <p><b>One-shot / bounded (maxHits) breakpoints:</b> an optional {@link #maxHits}
 * bounds how many times the breakpoint may pause before it auto-deregisters. Each
 * actual pause increments the {@link #pauseCount}; once it reaches {@code maxHits}
 * the breakpoint is {@linkplain #isExhausted() exhausted} and is removed from the
 * {@link BreakpointMatcherRegistry} — subsequent matching requests are no longer
 * intercepted. {@code maxHits = 1} is the common "pause once then stop" one-shot
 * breakpoint. When {@code maxHits} is {@code null} (the default) the breakpoint
 * never auto-deregisters (legacy behaviour). Skipped hits (within a {@code skipCount}
 * window) do NOT count against {@code maxHits} — only real pauses do.
 *
 * <p>Value-equality is on {@link #id} only (UUID assigned at registration).
 */
public class BreakpointMatcher {

    private final String id;
    private final RequestDefinition requestMatcher;
    private final Set<BreakpointPhase> phases;
    private final transient HttpRequestMatcher prebuiltMatcher;
    private final String clientId;
    private final Integer skipCount;
    private final Integer responseStatusCodeMin;
    private final Integer responseStatusCodeMax;
    private final String responseBodyContains;
    private final Integer maxHits;
    private final transient Pattern responseBodyPattern;
    private final transient AtomicLong hitCount = new AtomicLong(0L);
    private final transient AtomicLong pauseCount = new AtomicLong(0L);

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher) {
        this(id, requestMatcher, phases, prebuiltMatcher, null, null);
    }

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher,
                             String clientId) {
        this(id, requestMatcher, phases, prebuiltMatcher, clientId, null);
    }

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher,
                             String clientId, Integer skipCount) {
        this(id, requestMatcher, phases, prebuiltMatcher, clientId, skipCount, null, null, null);
    }

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher,
                             String clientId, Integer skipCount,
                             Integer responseStatusCodeMin, Integer responseStatusCodeMax,
                             String responseBodyContains) {
        this(id, requestMatcher, phases, prebuiltMatcher, clientId, skipCount,
            responseStatusCodeMin, responseStatusCodeMax, responseBodyContains, null);
    }

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher,
                             String clientId, Integer skipCount,
                             Integer responseStatusCodeMin, Integer responseStatusCodeMax,
                             String responseBodyContains, Integer maxHits) {
        this.id = id;
        this.requestMatcher = requestMatcher;
        this.phases = Collections.unmodifiableSet(EnumSet.copyOf(phases));
        this.prebuiltMatcher = prebuiltMatcher;
        this.clientId = clientId;
        this.skipCount = (skipCount != null && skipCount > 0) ? skipCount : null;
        this.maxHits = (maxHits != null && maxHits > 0) ? maxHits : null;
        this.responseStatusCodeMin = responseStatusCodeMin;
        this.responseStatusCodeMax = responseStatusCodeMax;
        this.responseBodyContains = (responseBodyContains != null && !responseBodyContains.isEmpty())
            ? responseBodyContains : null;
        if (this.responseBodyContains != null) {
            try {
                this.responseBodyPattern = Pattern.compile(this.responseBodyContains);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("'responseBodyContains' is not a valid regular expression: " + e.getMessage(), e);
            }
        } else {
            this.responseBodyPattern = null;
        }
    }

    public String getId() {
        return id;
    }

    public RequestDefinition getRequestMatcher() {
        return requestMatcher;
    }

    public Set<BreakpointPhase> getPhases() {
        return phases;
    }

    public HttpRequestMatcher getPrebuiltMatcher() {
        return prebuiltMatcher;
    }

    /**
     * The callback WebSocket client id that owns this breakpoint (required).
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * The optional skip-count: the number of matching hits to skip before the
     * breakpoint starts pausing. {@code null} (the default) means pause on every
     * hit. A value of {@code n} means do not pause on the first {@code n} hits and
     * pause from hit {@code n + 1} onward.
     */
    public Integer getSkipCount() {
        return skipCount;
    }

    /**
     * Lower bound (inclusive) of the optional response status-code condition, or
     * {@code null} if no lower bound is configured.
     */
    public Integer getResponseStatusCodeMin() {
        return responseStatusCodeMin;
    }

    /**
     * Upper bound (inclusive) of the optional response status-code condition, or
     * {@code null} if no upper bound is configured.
     */
    public Integer getResponseStatusCodeMax() {
        return responseStatusCodeMax;
    }

    /**
     * The optional regular expression searched (find semantics) within the response
     * body, or {@code null} if no body condition is configured.
     */
    public String getResponseBodyContains() {
        return responseBodyContains;
    }

    /**
     * Whether this breakpoint has any response-content condition (status-code range
     * or body pattern) that must be satisfied for it to pause at the RESPONSE phase.
     */
    public boolean hasResponseCondition() {
        return responseStatusCodeMin != null || responseStatusCodeMax != null || responseBodyPattern != null;
    }

    /**
     * Evaluates the optional response-content conditions against the given response.
     *
     * <p>Returns {@code true} when the response satisfies <em>all</em> configured
     * conditions (status-code within the inclusive {@code [min, max]} range, and the
     * body matches the {@code responseBodyContains} regex via find semantics). When no
     * conditions are configured this returns {@code true} (the legacy behaviour: pause
     * regardless of response content). A {@code null} response or {@code null} status
     * code fails any configured status-code condition; a {@code null}/empty body fails
     * any configured body condition.
     *
     * @param response the response about to be written to the downstream client
     * @return {@code true} if the response satisfies all configured conditions
     */
    public boolean responseConditionMatches(HttpResponse response) {
        if (!hasResponseCondition()) {
            return true;
        }
        if (response == null) {
            return false;
        }
        if (responseStatusCodeMin != null || responseStatusCodeMax != null) {
            Integer statusCode = response.getStatusCode();
            if (statusCode == null) {
                return false;
            }
            if (responseStatusCodeMin != null && statusCode < responseStatusCodeMin) {
                return false;
            }
            if (responseStatusCodeMax != null && statusCode > responseStatusCodeMax) {
                return false;
            }
        }
        if (responseBodyPattern != null) {
            String body = response.getBodyAsString();
            if (body == null || !responseBodyPattern.matcher(body).find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The optional maximum number of times this breakpoint may pause before it
     * auto-deregisters, or {@code null} if the breakpoint never auto-deregisters.
     * A value of {@code 1} is a one-shot breakpoint.
     */
    public Integer getMaxHits() {
        return maxHits;
    }

    /**
     * The number of times this breakpoint has matched (for diagnostics / tests).
     */
    public long getHitCount() {
        return hitCount.get();
    }

    /**
     * The number of pauses attempted past the skip window (as opposed to merely
     * matched). Differs from {@link #getHitCount()} when a {@code skipCount} window
     * suppressed some early pauses. Used to evaluate {@link #isExhausted()}.
     *
     * <p>Note: this counts pause <em>attempts</em>, not pauses that returned
     * {@code true}. Under concurrent access it may exceed {@link #maxHits} — a caller
     * that races past the budget still increments this counter but {@link #shouldPause()}
     * returns {@code false} for it, so at most {@code maxHits} exchanges actually pause.
     */
    public long getPauseCount() {
        return pauseCount.get();
    }

    /**
     * Whether this breakpoint has reached its {@link #maxHits} pause budget and should
     * be removed from the registry. Always {@code false} when {@code maxHits} is
     * {@code null} (the breakpoint never auto-deregisters).
     */
    public boolean isExhausted() {
        return maxHits != null && pauseCount.get() >= maxHits;
    }

    /**
     * Records a matching hit and decides whether this hit should actually pause.
     *
     * <p>Called exactly once per matching exchange/phase (from
     * {@link BreakpointMatcherRegistry#findMatch}). Increments the per-matcher
     * counter atomically and returns {@code true} if the breakpoint should pause
     * for this hit, {@code false} if the hit falls within the configured
     * skip-count window. When {@link #skipCount} is {@code null} this always
     * returns {@code true} (pause every time).
     *
     * <p>When a {@link #maxHits} budget is configured, only the first {@code maxHits}
     * real pauses return {@code true}; once the budget is spent this returns
     * {@code false} (and {@link #isExhausted()} becomes {@code true}, so the registry
     * removes the breakpoint). Skipped hits do not consume the budget.
     *
     * @return {@code true} to pause this hit, {@code false} to skip it
     */
    public boolean shouldPause() {
        long hit = hitCount.incrementAndGet();
        if (skipCount != null && hit <= skipCount) {
            return false;
        }
        long pauses = pauseCount.incrementAndGet();
        return maxHits == null || pauses <= maxHits;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BreakpointMatcher that = (BreakpointMatcher) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "BreakpointMatcher{id='" + id + "'"
            + ", phases=" + phases
            + (clientId != null ? ", clientId='" + clientId + "'" : "")
            + (skipCount != null ? ", skipCount=" + skipCount : "")
            + (maxHits != null ? ", maxHits=" + maxHits : "")
            + (responseStatusCodeMin != null ? ", responseStatusCodeMin=" + responseStatusCodeMin : "")
            + (responseStatusCodeMax != null ? ", responseStatusCodeMax=" + responseStatusCodeMax : "")
            + (responseBodyContains != null ? ", responseBodyContains='" + responseBodyContains + "'" : "")
            + ", matcher=" + requestMatcher + "}";
    }
}
