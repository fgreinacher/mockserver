package org.mockserver.mock.breakpoint;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.MatcherBuilder;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.uuid.UUIDService;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide registry of breakpoint matchers. Each entry describes a request
 * matcher and a set of phases; when a forwarded exchange matches, it is paused
 * at the corresponding phase for interactive inspection/modification.
 *
 * <p>Thread-safe via a single {@link CopyOnWriteArrayList} as the source of truth.
 * This is ideal for the access pattern here: reads ({@link #findMatch}) are frequent
 * and on the data plane (including the Netty event loop), while writes
 * ({@link #register}, {@link #remove}, {@link #clear}) are rare control-plane
 * operations. A single structure means register/remove/clear are atomic with respect
 * to a concurrent {@link #findMatch} — there is no window in which two backing
 * collections can disagree.
 *
 * <p><b>Event-loop safety:</b> {@link #findMatch} is designed to be called on the
 * Netty event loop for stream-frame phases. When the registry is empty, it returns
 * {@code null} immediately via a single {@link CopyOnWriteArrayList#isEmpty()} check
 * (zero allocation). When non-empty, it iterates a stable snapshot of the prebuilt
 * matchers without any blocking or allocation beyond the matcher's own match logic.
 */
public class BreakpointMatcherRegistry {

    private static final BreakpointMatcherRegistry INSTANCE = new BreakpointMatcherRegistry();

    private final CopyOnWriteArrayList<BreakpointMatcher> entries = new CopyOnWriteArrayList<>();

    public static BreakpointMatcherRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a new breakpoint matcher without an owner client (for tests only).
     * In production, the REST endpoint requires a clientId; this overload exists
     * for unit tests that exercise the registry directly.
     *
     * @param matcher       the request definition to match against
     * @param phases        the set of phases at which matching exchanges should break
     * @param configuration the active server configuration (passed to MatcherBuilder)
     * @param logger        the server logger (passed to MatcherBuilder)
     * @return the assigned UUID id for the registered breakpoint
     */
    public String register(RequestDefinition matcher, Set<BreakpointPhase> phases,
                           Configuration configuration, MockServerLogger logger) {
        return register(matcher, phases, null, null, configuration, logger);
    }

    /**
     * Registers a new breakpoint matcher with a required owner clientId.
     *
     * @see #register(RequestDefinition, Set, String, Integer, Configuration, MockServerLogger)
     */
    public String register(RequestDefinition matcher, Set<BreakpointPhase> phases,
                           String clientId,
                           Configuration configuration, MockServerLogger logger) {
        return register(matcher, phases, clientId, null, configuration, logger);
    }

    /**
     * Registers a new breakpoint matcher with a required owner clientId and an
     * optional skip-count for conditional (Nth-hit) breakpoints.
     *
     * <p>The {@code clientId} identifies the callback WebSocket client that owns
     * this breakpoint. Matched exchanges are dispatched over the callback WebSocket
     * to that client for interactive resolution.
     *
     * <p>The optional {@code skipCount} delays the first pause: the breakpoint
     * still matches on every hit but only pauses once it has been hit more than
     * {@code skipCount} times. {@code null} (or a non-positive value) means pause
     * on every hit (legacy behaviour).
     *
     * @param matcher       the request definition to match against
     * @param phases        the set of phases at which matching exchanges should break
     * @param clientId      the callback WS client that owns this breakpoint (required in production)
     * @param skipCount     the number of matching hits to skip before pausing, or {@code null} to pause every time
     * @param configuration the active server configuration (passed to MatcherBuilder)
     * @param logger        the server logger (passed to MatcherBuilder)
     * @return the assigned UUID id for the registered breakpoint
     */
    public String register(RequestDefinition matcher, Set<BreakpointPhase> phases,
                           String clientId, Integer skipCount,
                           Configuration configuration, MockServerLogger logger) {
        return register(matcher, phases, clientId, skipCount, null, null, null, configuration, logger);
    }

    /**
     * Registers a new breakpoint matcher with a required owner clientId, an optional
     * skip-count for conditional (Nth-hit) breakpoints, and optional
     * response-content conditions that gate whether a RESPONSE-phase breakpoint pauses.
     *
     * <p>The response conditions ({@code responseStatusCodeMin}/{@code responseStatusCodeMax}
     * for an inclusive status-code range, and {@code responseBodyContains} for a regex
     * searched within the response body) are only evaluated at the response phase (the
     * response is not known at the request phase). When set, the breakpoint pauses only
     * when the response satisfies all configured conditions. When all are {@code null}
     * (the default) the breakpoint pauses regardless of response content (legacy
     * behaviour).
     *
     * @param matcher               the request definition to match against
     * @param phases                the set of phases at which matching exchanges should break
     * @param clientId              the callback WS client that owns this breakpoint (required in production)
     * @param skipCount             the number of matching hits to skip before pausing, or {@code null} to pause every time
     * @param responseStatusCodeMin inclusive lower bound of the response status-code condition, or {@code null}
     * @param responseStatusCodeMax inclusive upper bound of the response status-code condition, or {@code null}
     * @param responseBodyContains  a regex searched (find semantics) within the response body, or {@code null}
     * @param configuration         the active server configuration (passed to MatcherBuilder)
     * @param logger                the server logger (passed to MatcherBuilder)
     * @return the assigned UUID id for the registered breakpoint
     */
    public String register(RequestDefinition matcher, Set<BreakpointPhase> phases,
                           String clientId, Integer skipCount,
                           Integer responseStatusCodeMin, Integer responseStatusCodeMax, String responseBodyContains,
                           Configuration configuration, MockServerLogger logger) {
        return register(matcher, phases, clientId, skipCount,
            responseStatusCodeMin, responseStatusCodeMax, responseBodyContains, null, configuration, logger);
    }

    /**
     * As {@link #register(RequestDefinition, Set, String, Integer, Integer, Integer, String, Configuration, MockServerLogger)}
     * but with an optional {@code maxHits} one-shot / bounded budget.
     *
     * <p>The optional {@code maxHits} bounds how many times the breakpoint may pause
     * before it auto-deregisters from this registry. Each actual pause counts against
     * the budget (hits skipped by a {@code skipCount} window do not); once the budget is
     * spent the entry is {@linkplain BreakpointMatcher#isExhausted() exhausted} and is
     * removed by {@link #findMatch}/{@link #findResponseMatch} so subsequent matching
     * requests are not intercepted. {@code maxHits = 1} is a one-shot breakpoint.
     * {@code null} (or a non-positive value) means the breakpoint never auto-deregisters
     * (legacy behaviour).
     *
     * @param maxHits the maximum number of pauses before auto-deregistration, or {@code null} for unbounded
     */
    public String register(RequestDefinition matcher, Set<BreakpointPhase> phases,
                           String clientId, Integer skipCount,
                           Integer responseStatusCodeMin, Integer responseStatusCodeMax, String responseBodyContains,
                           Integer maxHits,
                           Configuration configuration, MockServerLogger logger) {
        String id = UUIDService.getUUID();
        HttpRequestMatcher prebuilt = new MatcherBuilder(configuration, logger).transformsToMatcher(matcher);
        BreakpointMatcher entry = new BreakpointMatcher(id, matcher, phases, prebuilt, clientId, skipCount,
            responseStatusCodeMin, responseStatusCodeMax, responseBodyContains, maxHits);
        entries.add(entry);
        return id;
    }

    /**
     * Finds the first registered breakpoint whose phases contain the given phase
     * AND whose prebuilt matcher matches the given request AND which should pause
     * for this hit.
     *
     * <p>Returns {@code null} if the registry is empty or no matcher matches.
     * This method is allocation-light and safe to call on the Netty event loop.
     *
     * <p><b>Conditional (skip-count) breakpoints:</b> when a matcher matches, its
     * per-matcher hit counter is incremented (atomically, via
     * {@link BreakpointMatcher#shouldPause()}). If the matcher is configured with a
     * {@code skipCount} and this hit still falls within the skip window, the hit is
     * recorded but the matcher does NOT pause — {@code findMatch} treats it as the
     * winning match and returns {@code null} (do not pause) rather than falling
     * through to a later matcher. This preserves first-match semantics: the first
     * matcher to match a request "owns" the decision for that hit. A matcher with no
     * {@code skipCount} always pauses (legacy behaviour).
     *
     * @param request the inbound request to match against
     * @param phase   the phase to check
     * @return the first matching {@link BreakpointMatcher} that should pause, or {@code null}
     */
    public BreakpointMatcher findMatch(RequestDefinition request, BreakpointPhase phase) {
        if (entries.isEmpty()) {
            return null;
        }
        for (BreakpointMatcher entry : entries) {
            if (entry.getPhases().contains(phase) && entry.getPrebuiltMatcher().matches(request)) {
                // First matcher to match owns the decision for this hit. Record the
                // hit and pause only if it falls outside any configured skip window.
                boolean pause = entry.shouldPause();
                if (entry.isExhausted()) {
                    // maxHits budget spent: auto-deregister so later requests are not intercepted.
                    entries.remove(entry);
                }
                return pause ? entry : null;
            }
        }
        return null;
    }

    /**
     * Response-phase variant of {@link #findMatch} that additionally evaluates each
     * matcher's optional response-content conditions (status-code range / body regex)
     * against the actual {@code response} about to be written.
     *
     * <p>Selection semantics:
     * <ul>
     *   <li>A matcher is considered only if its phases contain {@code phase} and its
     *       prebuilt request matcher matches {@code request}.</li>
     *   <li>If the matcher has a response condition that the {@code response} does NOT
     *       satisfy, it is treated as not applicable and iteration <em>falls through</em>
     *       to later matchers (a response condition is part of what the matcher selects,
     *       so a non-matching response means this matcher simply does not want this
     *       exchange).</li>
     *   <li>The first matcher that matches the request AND satisfies its response
     *       condition (or has none) owns the decision: its per-matcher hit counter is
     *       incremented and it pauses only if it falls outside any configured skip
     *       window — identical to {@link #findMatch}.</li>
     * </ul>
     *
     * <p>Matchers with no response condition are handled identically to the request-only path:
     * the matcher owns the decision on first match, its per-matcher hit counter is incremented,
     * and it pauses only if it falls outside any configured skip window.
     * This method is safe to call on the data plane (no allocation beyond the matcher's
     * own logic and the condition evaluation).
     *
     * @param request  the inbound request to match against
     * @param response the response about to be written to the downstream client
     * @param phase    the phase to check (typically {@link BreakpointPhase#RESPONSE})
     * @return the first matching {@link BreakpointMatcher} that should pause, or {@code null}
     */
    public BreakpointMatcher findResponseMatch(RequestDefinition request, HttpResponse response, BreakpointPhase phase) {
        if (entries.isEmpty()) {
            return null;
        }
        for (BreakpointMatcher entry : entries) {
            if (entry.getPhases().contains(phase) && entry.getPrebuiltMatcher().matches(request)) {
                if (entry.hasResponseCondition() && !entry.responseConditionMatches(response)) {
                    // response does not satisfy this matcher's condition — try later matchers
                    continue;
                }
                boolean pause = entry.shouldPause();
                if (entry.isExhausted()) {
                    // maxHits budget spent: auto-deregister so later requests are not intercepted.
                    entries.remove(entry);
                }
                return pause ? entry : null;
            }
        }
        return null;
    }

    /**
     * Removes a breakpoint by id.
     *
     * @return true if the breakpoint was found and removed
     */
    public boolean remove(String id) {
        return entries.removeIf(entry -> entry.getId().equals(id));
    }

    /**
     * Removes all breakpoints owned by the given callback client.
     * Called when a WebSocket client disconnects so its breakpoints are cleaned up.
     *
     * @param clientId the client id whose breakpoints should be removed
     * @return the number of breakpoints removed
     */
    public int removeByClientId(String clientId) {
        if (clientId == null) {
            return 0;
        }
        int sizeBefore = entries.size();
        entries.removeIf(e -> clientId.equals(e.getClientId()));
        return sizeBefore - entries.size();
    }

    /**
     * Clears all registered breakpoints.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Returns a snapshot of all registered breakpoints in registration order.
     */
    public List<BreakpointMatcher> entries() {
        return new ArrayList<>(entries);
    }

    /**
     * Number of currently registered breakpoints.
     */
    public int size() {
        return entries.size();
    }
}
