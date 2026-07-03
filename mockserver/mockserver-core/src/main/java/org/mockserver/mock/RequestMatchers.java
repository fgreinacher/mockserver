package org.mockserver.mock;

import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.collections.CircularHashMap;
import org.mockserver.collections.CircularPriorityQueue;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.llm.IsolationSource;
import org.mockserver.llm.LlmScenarioNames;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.LlmConversationMatcher;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.MatcherBuilder;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.model.*;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.ExpectationEntry;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.mockserver.state.Versioned;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntryMessages.*;
import static org.mockserver.metrics.Metrics.Name.*;
import static org.mockserver.mock.SortableExpectationId.EXPECTATION_SORTABLE_PRIORITY_COMPARATOR;
import static org.mockserver.mock.SortableExpectationId.NULL;
import static org.slf4j.event.Level.TRACE;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class RequestMatchers extends MockServerMatcherNotifier {

    // Node-local cache of compiled HttpRequestMatchers, kept in sync with the
    // backend KeyValueStore<ExpectationEntry>. Tests access this field directly
    // (package-private) so it must remain a functioning CPQ with identical
    // ordering semantics.
    final CircularPriorityQueue<String, HttpRequestMatcher, SortableExpectationId> httpRequestMatchers;
    final CircularHashMap<String, RequestDefinition> expectationRequestDefinitions;
    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private final Scheduler scheduler;
    private WebSocketClientRegistry webSocketClientRegistry;
    private MatcherBuilder matcherBuilder;
    private Metrics metrics;
    private final ScenarioManager scenarioManager = new ScenarioManager();
    // G10 phase 2b: the backend's expectation KV store is the SOURCE OF TRUTH
    // for expectation definitions, ordering, and eviction. The node-local
    // httpRequestMatchers CPQ is a derived cache of compiled matchers.
    private volatile StateBackend stateBackend;
    private volatile KeyValueStore<ExpectationEntry> expectationBackend;
    // Fast id-to-matcher lookup for the node-local cache. Kept in sync with
    // httpRequestMatchers; used to avoid O(n) scans during reconciliation.
    private final ConcurrentHashMap<String, HttpRequestMatcher> matcherCacheById = new ConcurrentHashMap<>();
    // Tracks the backend version that was last reconciled for each expectation
    // id. Used by reconcileFromBackend() to detect remote updates that changed
    // only non-sort fields (e.g. response body) — without this, such updates
    // would leave a stale matcher serving the old behaviour.
    private final ConcurrentHashMap<String, Long> lastReconciledVersion = new ConcurrentHashMap<>();
    // T1-C1: candidate index for expectation matching above a size threshold. Below
    // the threshold firstMatchingExpectation runs the EXACT existing linear scan
    // (byte-for-byte, zero added per-request cost); above it the scan is narrowed to
    // the request's (method, exact-path) bucket plus an always-checked fallthrough
    // list, evaluated in the SAME global sorted order. See CandidateIndex.
    private final CandidateIndex candidateIndex = new CandidateIndex();
    // Monotonic control-plane modification counter. Incremented on every structural
    // mutation of httpRequestMatchers (add / remove / update-in-place / reconcile /
    // eviction / reset). The CandidateIndex is rebuilt lazily whenever its built
    // generation differs from this counter — the same invalidate-on-mutation /
    // rebuild-on-read contract CircularPriorityQueue.sortedCache uses, so it is
    // correct for ALL mutation kinds (including update-in-place, where a matcher
    // keeps identity but its method/path bucket changes) without per-site hooks.
    private final java.util.concurrent.atomic.AtomicLong matchersModificationCount = new java.util.concurrent.atomic.AtomicLong(0);
    // The match fields a plain HTTP request can actually exercise. Used as the
    // denominator of the closest-expectation "matched X/Y fields" diagnostic so
    // it is not inflated by the DNS/binary/OpenAPI/operation enum constants that
    // an HttpRequest never touches. DNS and binary requests report against their
    // own field sets (see applicableFieldCount).
    private static final MatchDifference.Field[] HTTP_APPLICABLE_FIELDS = {
        MatchDifference.Field.METHOD,
        MatchDifference.Field.PATH,
        MatchDifference.Field.PATH_PARAMETERS,
        MatchDifference.Field.QUERY_PARAMETERS,
        MatchDifference.Field.COOKIES,
        MatchDifference.Field.HEADERS,
        MatchDifference.Field.BODY,
        MatchDifference.Field.SECURE,
        MatchDifference.Field.PROTOCOL,
        MatchDifference.Field.KEEP_ALIVE,
    };
    private static final MatchDifference.Field[] DNS_APPLICABLE_FIELDS = {
        MatchDifference.Field.DNS_NAME,
        MatchDifference.Field.DNS_TYPE,
        MatchDifference.Field.DNS_CLASS,
    };
    private static final MatchDifference.Field[] BINARY_APPLICABLE_FIELDS = {
        MatchDifference.Field.BINARY_BODY,
    };
    // A non-fail-fast matcher builder used exclusively by the cold-path closest-match
    // diagnostic to obtain a non-collapsed field-difference count. Lazily created
    // (never on the hot serving path). Note: countMatchedApplicableFields builds the
    // one-off matcher via transformsToMatcher(Expectation), which always compiles a
    // fresh matcher (the MatcherBuilder LRU cache is keyed on RequestDefinition and is
    // not consulted for the Expectation overload), so each cold-path diagnostic
    // allocates a matcher — acceptable on this already-gated, no-match cold path.
    private volatile MatcherBuilder nonFailFastMatcherBuilder;

    // T1-C1: candidate-index engagement threshold. Below this many expectations
    // firstMatchingExpectation runs the UNTOUCHED linear scan so small scenarios are
    // never slowed to benefit large ones (the cleanest guarantee of "no regression at
    // small n" — the same code path runs as today, the index object is never touched
    // except for a single int comparison). At or above it the candidate index engages.
    //
    // Empirically determined from CandidateIndexBenchmark (avgt, us/op; 99.9% error bars).
    // For a bucketable (literal method+path) set the index clearly wins from n=100 (HIT
    // 6.0→0.47us ≈ 13x, MISS 10.5→0.25us ≈ 43x) and far more at 1k/5k. At small n (≤~5)
    // the index and the linear scan are a statistical tie; the smallest n from which there
    // is NO measured regression (both hit and miss) upward is ≈16. A regex-heavy
    // (all-fallthrough) set degenerates the candidate set to the full fallthrough, so the
    // index neither helps nor hurts (≈1.0x at every n). 64 is set a safe margin above the
    // measured ≈16 no-regression floor and at the n=100 clear-win point, so small/common
    // scenarios stay byte-for-byte on the linear scan (never slower at any size) while large
    // ones get the full speedup. Overridable for tuning via the system property
    // {@code mockserver.candidateIndexThreshold}; values < 2 are clamped to 2 (a
    // 0/1-expectation store can never benefit and must stay on the scan).
    static final int DEFAULT_CANDIDATE_INDEX_THRESHOLD = 64;
    // Non-final so tests can inject a low threshold via withCandidateIndexThreshold()
    // WITHOUT mutating any global/system state (keeping them parallel-safe). Production
    // code never reassigns it after construction.
    private int candidateIndexThreshold = resolveCandidateIndexThreshold();

    private static int resolveCandidateIndexThreshold() {
        try {
            String override = System.getProperty("mockserver.candidateIndexThreshold");
            if (override != null && !override.trim().isEmpty()) {
                return Math.max(2, Integer.parseInt(override.trim()));
            }
        } catch (NumberFormatException ignore) {
            // fall through to the default on a malformed override
        }
        return DEFAULT_CANDIDATE_INDEX_THRESHOLD;
    }

    /**
     * Sets the candidate-index engagement threshold on this instance without mutating any
     * global/system state (so tests stay parallel-safe). Below this many expectations
     * {@link #firstMatchingExpectation} runs the untouched linear scan; at or above it the
     * candidate index engages. Used by the matcher's own tests and the matching benchmark
     * to exercise both paths deterministically; production code relies on the
     * constructor-resolved value (default {@link #DEFAULT_CANDIDATE_INDEX_THRESHOLD},
     * overridable via {@code -Dmockserver.candidateIndexThreshold}) and need not call this.
     * Values below 2 are clamped to 2 (a 0/1-expectation store can never benefit).
     */
    public RequestMatchers withCandidateIndexThreshold(int threshold) {
        this.candidateIndexThreshold = Math.max(2, threshold);
        return this;
    }

    public RequestMatchers(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, WebSocketClientRegistry webSocketClientRegistry) {
        super(scheduler);
        this.configuration = configuration;
        this.scheduler = scheduler;
        this.matcherBuilder = new MatcherBuilder(configuration, mockServerLogger);
        this.mockServerLogger = mockServerLogger;
        this.webSocketClientRegistry = webSocketClientRegistry;
        this.metrics = new Metrics(configuration);
        httpRequestMatchers = new CircularPriorityQueue<>(
            configuration.maxExpectations(),
            EXPECTATION_SORTABLE_PRIORITY_COMPARATOR,
            httpRequestMatcher -> httpRequestMatcher.getExpectation() != null ? httpRequestMatcher.getExpectation().getSortableId() : NULL,
            httpRequestMatcher -> httpRequestMatcher.getExpectation() != null ? httpRequestMatcher.getExpectation().getId() : ""
        );
        expectationRequestDefinitions = new CircularHashMap<>(configuration.maxExpectations());
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("expectation circular priority queue created, with size " + configuration.maxExpectations())
            );
        }
    }

    /**
     * Sets the state backend reference and wires the expectation KV store
     * as the source of truth. Called by {@code HttpState} after construction.
     * The node-local httpRequestMatchers CPQ becomes a derived cache; all
     * mutations route through the backend first.
     * <p>
     * When a backend is wired, the node-local CPQ's maxSize is raised to
     * {@code Integer.MAX_VALUE} so that eviction is controlled exclusively
     * by the backend (avoiding insertion-order divergence between two CPQs
     * on update-in-place vs re-insert). {@link #reconcileEvictions()} trims
     * the node-local cache after each backend mutation.
     * <p>
     * <b>Threading contract:</b> all control-plane mutations (add, update,
     * remove, clear, reset, setStateBackend) are assumed to be externally
     * serialized — i.e. a single writer at a time. This is satisfied today
     * because {@code HttpState} serializes control-plane calls on the Netty
     * event loop or holds the action lock.
     * <p>
     * TODO(jamesdbloom): phase 2c — when remote invalidation events arrive
     * concurrently from a clustered backend, this single-writer assumption
     * must be revisited — likely by introducing an internal lock or
     * event-queue around the node-local cache reconciliation.
     */
    public void setStateBackend(StateBackend stateBackend) {
        this.stateBackend = stateBackend;
        this.expectationBackend = stateBackend != null ? stateBackend.expectations() : null;
        if (this.expectationBackend != null) {
            // Disable node-local eviction — backend is the eviction authority
            httpRequestMatchers.setMaxSize(Integer.MAX_VALUE);
        } else {
            // Restore original eviction when backend is removed
            httpRequestMatchers.setMaxSize(configuration.maxExpectations());
        }
        // Wire scenario states through the backend's replicated KV store
        // so scenario transitions are shared across cluster nodes. For the
        // default InMemoryStateBackend this wraps a ConcurrentHashMap —
        // identical single-node behaviour with no overhead.
        if (stateBackend != null) {
            scenarioManager.setScenarioStates(stateBackend.scenarioStates());
        } else {
            // Backend removed — reset to a fresh in-memory store so the
            // ScenarioManager doesn't keep operating on a removed/closed
            // backend's store.
            scenarioManager.setScenarioStates(new org.mockserver.state.InMemoryKeyValueStore<>());
        }
    }

    /**
     * Returns the state backend, or {@code null} if none has been set.
     */
    public StateBackend getStateBackend() {
        return stateBackend;
    }

    public Expectation add(Expectation expectation, Cause cause) {
        Expectation upsertedExpectation = null;
        if (expectation != null) {
            validateRespondBeforeBody(expectation);
            expectationRequestDefinitions.put(expectation.getId(), expectation.getHttpRequest());

            // CPX-04: Propagate created time from existing backend entry (if
            // updating) to preserve ordering — must happen before backend put.
            // This is intentionally redundant with the node-local created
            // propagation below (line ~155): the backend propagation covers
            // the case where the node-local CPQ does not yet have the matcher
            // (e.g. after a cold-start rebuild from backend state), while the
            // node-local propagation covers the no-backend fallback path.
            if (expectationBackend != null) {
                expectationBackend.get(expectation.getId()).ifPresent(existing -> {
                    expectation.withCreated(existing.getValue().getExpectation().getCreated());
                });
            }

            upsertedExpectation = httpRequestMatchers
                .getByKey(expectation.getId())
                .map(httpRequestMatcher -> {
                    if (httpRequestMatcher.getExpectation() != null && httpRequestMatcher.getExpectation().getAction() != null) {
                        metrics.decrement(httpRequestMatcher.getExpectation().getAction().getType());
                    }
                    if (httpRequestMatcher.getExpectation() != null) {
                        // propagate created time from previous entry to avoid re-ordering on update
                        expectation.withCreated(httpRequestMatcher.getExpectation().getCreated());
                    }
                    httpRequestMatchers.removePriorityKey(httpRequestMatcher);
                    if (httpRequestMatcher.update(expectation)) {
                        httpRequestMatchers.addPriorityKey(httpRequestMatcher);
                        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(UPDATED_EXPECTATION)
                                    .setLogLevel(Level.INFO)
                                    .setHttpRequest(expectation.getHttpRequest())
                                    .setMessageFormat(UPDATED_EXPECTATION_MESSAGE_FORMAT)
                                    .setArguments(expectation.clone(), expectation.getId())
                            );
                        }
                        if (expectation.getAction() != null) {
                            metrics.increment(expectation.getAction().getType());
                        }
                    } else {
                        httpRequestMatchers.addPriorityKey(httpRequestMatcher);
                    }
                    matcherCacheById.put(expectation.getId(), httpRequestMatcher);
                    return httpRequestMatcher;
                })
                .orElseGet(() -> addPrioritisedExpectation(expectation, cause))
                .getExpectation();

            // Put into backend KV (source of truth) — this may trigger
            // maxExpectations eviction inside the backend's CPQ.
            if (expectationBackend != null) {
                long newVersion = expectationBackend.put(expectation.getId(), new ExpectationEntry(expectation));
                lastReconciledVersion.put(expectation.getId(), newVersion);
                reconcileEvictions();
            }

            // Invalidate the candidate index: this add may have created a new matcher
            // or updated an existing one in place (changing its method/path bucket).
            markMatchersModified();
            notifyListeners(this, cause);
        }
        return upsertedExpectation;
    }

    public void update(Expectation[] expectations, Cause cause) {
        AtomicInteger numberOfChanges = new AtomicInteger(0);
        if (expectations != null) {
            Map<String, HttpRequestMatcher> httpRequestMatchersByKey = httpRequestMatchers.keyMap();
            Set<String> existingKeysForCause = httpRequestMatchersByKey
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue().getSource().equals(cause))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
            Set<String> addedIds = new HashSet<>();
            Arrays
                .stream(expectations)
                .forEach(expectation -> {
                    // ensure duplicate ids are skipped in input array
                    if (!addedIds.contains(expectation.getId())) {
                        validateRespondBeforeBody(expectation);
                        addedIds.add(expectation.getId());
                        expectationRequestDefinitions.put(expectation.getId(), expectation.getHttpRequest());
                        existingKeysForCause.remove(expectation.getId());

                        // Propagate created time from backend if present
                        if (expectationBackend != null) {
                            expectationBackend.get(expectation.getId()).ifPresent(existing -> {
                                expectation.withCreated(existing.getValue().getExpectation().getCreated());
                            });
                        }

                        if (httpRequestMatchersByKey.containsKey(expectation.getId())) {
                            HttpRequestMatcher httpRequestMatcher = httpRequestMatchersByKey.get(expectation.getId());
                            // update source to new cause
                            httpRequestMatcher.withSource(cause);
                            if (httpRequestMatcher.getExpectation() != null && httpRequestMatcher.getExpectation().getAction() != null) {
                                metrics.decrement(httpRequestMatcher.getExpectation().getAction().getType());
                            }
                            if (httpRequestMatcher.getExpectation() != null) {
                                // propagate created time from previous entry to avoid re-ordering on update
                                expectation.withCreated(httpRequestMatcher.getExpectation().getCreated());
                            }
                            httpRequestMatchers.removePriorityKey(httpRequestMatcher);
                            if (httpRequestMatcher.update(expectation)) {
                                httpRequestMatchers.addPriorityKey(httpRequestMatcher);
                                numberOfChanges.getAndIncrement();
                                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                    mockServerLogger.logEvent(
                                        new LogEntry()
                                            .setType(UPDATED_EXPECTATION)
                                            .setLogLevel(Level.INFO)
                                            .setHttpRequest(expectation.getHttpRequest())
                                            .setMessageFormat(UPDATED_EXPECTATION_MESSAGE_FORMAT)
                                            .setArguments(expectation.clone(), expectation.getId())
                                    );
                                }
                                if (expectation.getAction() != null) {
                                    metrics.increment(expectation.getAction().getType());
                                }
                            } else {
                                httpRequestMatchers.addPriorityKey(httpRequestMatcher);
                            }
                            matcherCacheById.put(expectation.getId(), httpRequestMatcher);
                        } else {
                            addPrioritisedExpectation(expectation, cause);
                            numberOfChanges.getAndIncrement();
                        }

                        // Put into backend KV (source of truth)
                        if (expectationBackend != null) {
                            long newVersion = expectationBackend.put(expectation.getId(), new ExpectationEntry(expectation));
                            lastReconciledVersion.put(expectation.getId(), newVersion);
                        }
                    }
                });
            existingKeysForCause
                .forEach(key -> {
                    numberOfChanges.getAndIncrement();
                    HttpRequestMatcher httpRequestMatcher = httpRequestMatchersByKey.get(key);
                    removeHttpRequestMatcher(httpRequestMatcher, cause, false, UUIDService.getUUID());
                    if (httpRequestMatcher.getExpectation() != null && httpRequestMatcher.getExpectation().getAction() != null) {
                        metrics.decrement(httpRequestMatcher.getExpectation().getAction().getType());
                    }
                });

            // Reconcile evictions after batch update
            if (expectationBackend != null) {
                reconcileEvictions();
            }

            if (numberOfChanges.get() > 0) {
                // Invalidate the candidate index after a batch of adds/updates/removes.
                markMatchersModified();
                notifyListeners(this, cause);
            }
        }
    }

    private void validateRespondBeforeBody(Expectation expectation) {
        if (!(expectation.getHttpRequest() instanceof HttpRequest)) {
            return;
        }
        HttpRequest request = (HttpRequest) expectation.getHttpRequest();
        if (!Boolean.TRUE.equals(request.getRespondBeforeBody())) {
            return;
        }
        if (request.getBody() != null) {
            throw new IllegalArgumentException("respondBeforeBody=true cannot be combined with a body matcher: the body has not yet been received when matching occurs");
        }
        if (expectation.getAction() == null) {
            throw new IllegalArgumentException("respondBeforeBody=true requires a RESPONSE or ERROR action");
        }
        Action.Type actionType = expectation.getAction().getType();
        if (actionType != Action.Type.RESPONSE && actionType != Action.Type.ERROR) {
            throw new IllegalArgumentException("respondBeforeBody=true only supports action types RESPONSE and ERROR, was: " + actionType);
        }
    }

    private HttpRequestMatcher addPrioritisedExpectation(Expectation expectation, Cause cause) {
        HttpRequestMatcher httpRequestMatcher = matcherBuilder.transformsToMatcher(expectation);
        httpRequestMatchers.add(httpRequestMatcher);
        httpRequestMatcher.withSource(cause);
        matcherCacheById.put(expectation.getId(), httpRequestMatcher);
        if (expectation.getAction() != null) {
            metrics.increment(expectation.getAction().getType());
        }
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(CREATED_EXPECTATION)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(expectation.getHttpRequest())
                    .setMessageFormat(CREATED_EXPECTATION_MESSAGE_FORMAT)
                    .setArguments(expectation.clone(), expectation.getId())
            );
        }
        return httpRequestMatcher;
    }

    /**
     * Signals that the node-local httpRequestMatchers store changed structurally
     * (an add, remove, update-in-place, reconcile, eviction or reset). Bumps the
     * monotonic modification counter so the {@link CandidateIndex} rebuilds lazily on
     * the next above-threshold match. Cheap (a single atomic increment) and only ever
     * called on the control plane, never on the match hot path.
     */
    private void markMatchersModified() {
        matchersModificationCount.incrementAndGet();
    }

    /**
     * Test-only accessor for the control-plane modification counter, used by the
     * generation guard test to assert that every CPQ-mutating operation bumps it (so a
     * future mutation site that forgets {@link #markMatchersModified()} fails the build).
     */
    long matchersModificationCountForTesting() {
        return matchersModificationCount.get();
    }

    public int size() {
        // The node-local cache is kept in sync with the backend, so
        // either source gives the same answer; prefer the CPQ as it
        // is what tests assert against.
        return httpRequestMatchers.size();
    }

    public void reset(Cause cause) {
        httpRequestMatchers.stream().forEach(httpRequestMatcher -> removeHttpRequestMatcher(httpRequestMatcher, cause, false, UUIDService.getUUID()));
        expectationRequestDefinitions.clear();
        matcherCacheById.clear();
        lastReconciledVersion.clear();
        if (expectationBackend != null) {
            expectationBackend.clear();
        }
        // Invalidate the candidate index — the store is now empty.
        markMatchersModified();
        scenarioManager.reset();
        Metrics.clearActionMetrics();
        Metrics.clearRequestAndExpectationMetrics();
        notifyListeners(this, cause);
    }

    public void reset() {
        reset(Cause.API);
    }

    public Expectation firstMatchingExpectation(RequestDefinition requestDefinition) {
        Expectation matchedExpectation = null;
        Expectation closestMatchExpectation = null;
        HttpRequestMatcher closestMatchMatcher = null;
        int closestMatchFailures = Integer.MAX_VALUE;
        String requestNamespace = extractRequestNamespace(requestDefinition);
        // Count expectations skipped purely by the namespace gate, but only when
        // the request actually carries a namespace — the common no-namespace hot
        // path does no extra work (the counter stays 0 and is never read).
        int namespaceSkipped = 0;

        // Allocation optimisation: with detailedMatchFailures OFF (the default), a
        // MatchDifference records NOTHING — addDifference(Field,...) is gated entirely
        // on the flag, so its lazily-allocated differences map is never created and
        // getAllDifferences() always returns the empty map. The only per-match mutable
        // state, the currentField marker, is overwritten at the start of every field
        // match and is never read by this loop, so a SINGLE reusable instance is
        // behaviourally identical across the whole scan (and never escapes this method).
        // This removes the per-candidate MatchDifference shell that a large no-match scan
        // would otherwise allocate (e.g. 1000 throwaway objects for a 1000-expectation
        // miss). When the flag is ON, each matcher still gets its OWN MatchDifference so
        // the recorded per-field differences (and the closest-match diagnostic that reads
        // getAllDifferences().size()) are exactly as before.
        final boolean detailedMatchFailures = configuration.detailedMatchFailures();
        final MatchDifference sharedMatchDifference = detailedMatchFailures
            ? null
            : new MatchDifference(false, requestDefinition);

        // T1-C1: choose the scan set. Below the threshold use the UNTOUCHED full sorted
        // list (byte-for-byte the existing behaviour, zero added per-request cost beyond
        // one size read). At/above it, narrow to the request's (method, exact-path) bucket
        // plus the always-checked fallthrough list, evaluated in the SAME global sorted
        // order — so the first match is provably identical to the full scan (any expectation
        // outside the candidate set is in a different literal bucket and cannot match this
        // request). The candidate set is a SUBSET, so the in-loop closest-match accumulation
        // could differ; when no candidate matches and the closest-match diagnostic is needed,
        // it is recomputed over the FULL sorted list below to keep diagnostics identical.
        //
        // SAFETY BYPASS: a request whose method OR path is BLANK matches the method/path
        // criterion of EVERY expectation (HttpRequestPropertiesMatcher short-circuits the
        // gate to true when the REQUEST value is blank), so it could match a bucketed literal
        // in ANY bucket — narrowing to a single (method,path) bucket would be unsound. Such a
        // request (only constructible programmatically — every Netty data-plane entry point
        // supplies a concrete method+path) falls back to the full scan. This keeps the index
        // engaged only when the request's literal (method,path) can be soundly bucketed.
        final boolean useCandidateIndex = httpRequestMatchers.size() >= candidateIndexThreshold
            && requestHasConcreteMethodAndPath(requestDefinition);
        final List<HttpRequestMatcher> scanList = useCandidateIndex
            ? candidateIndex.candidatesInGlobalOrder(
                requestDefinition,
                matchersModificationCount.get(),
                !configuration.matchExactCase(),
                httpRequestMatchers::toSortedList)
            : httpRequestMatchers.toSortedList();

        for (HttpRequestMatcher httpRequestMatcher : scanList) {
            // Namespace (multi-tenancy) gate: skip expectations belonging to a
            // different namespace than the request's. Global (null-namespace)
            // expectations always pass; a request with no namespace sees only
            // global expectations. Applied before matching so a foreign-namespace
            // expectation never participates (and never pollutes closest-match).
            if (!matchesNamespace(httpRequestMatcher.getExpectation(), requestNamespace)) {
                if (requestNamespace != null) {
                    namespaceSkipped++;
                }
                continue;
            }
            MatchDifference matchDifference = detailedMatchFailures
                ? new MatchDifference(true, requestDefinition)
                : sharedMatchDifference;
            if (httpRequestMatcher.matches(matchDifference, requestDefinition)) {
                Expectation expectation = httpRequestMatcher.getExpectation();

                // Check LLM conversation matcher if present
                HttpLlmResponse llmResponse = expectation.getHttpLlmResponse();
                if (llmResponse != null) {
                    LlmConversationMatcher convMatcher = llmResponse.getConversationMatcher();
                    if (convMatcher != null && convMatcher.hasPredicates()) {
                        if (requestDefinition instanceof HttpRequest) {
                            if (!convMatcher.matches((HttpRequest) requestDefinition)) {
                                continue;
                            }
                        }
                    }
                }

                // Extract isolation key for scenario state management
                String isolationKey = extractIsolationKey(expectation, requestDefinition);

                // Scenario gate: check the required state WITHOUT transitioning.
                // The transition is a side-effect that must only happen once the
                // expectation is actually committed (after percentage AND Times
                // consumption succeed) — otherwise a skipped expectation would
                // advance the scenario without ever being served (consume-then-skip).
                if (expectation.getScenarioName() != null && expectation.getScenarioState() != null) {
                    if (!scenarioManager.matchesState(expectation.getScenarioName(), isolationKey, expectation.getScenarioState())) {
                        continue;
                    }
                }
                if (!expectation.matchesByPercentage()) {
                    continue;
                }
                httpRequestMatcher.setResponseInProgress(true);
                // Clustered shared-Times CAS: when a clustered backend is active
                // and the expectation has limited Times, atomically decrement the
                // SHARED remaining-times counter via backend CAS BEFORE serving.
                // If the CAS fails (another node exhausted the allotment), this
                // node falls through to the next expectation. Unlimited Times
                // always takes the node-local fast path (no grid call).
                if (isClusteredLimitedTimes(expectation)) {
                    ConsumeTimesResult casResult = consumeTimesViaBackendCas(expectation);
                    if (!casResult.success) {
                        httpRequestMatcher.setResponseInProgress(false);
                        if (casResult.exhausted) {
                            // Expectation is exhausted fleet-wide — schedule
                            // removal so it goes inactive on this node too
                            scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
                        }
                        continue;
                    }
                    // CAS succeeded: record the match locally (without
                    // decrementing node-local Times — the backend is authoritative)
                    expectation.consumeMatchLocally(Expectation.parseForcedResponseIndex(requestDefinition));
                } else {
                    // Default single-node fast path: identical to pre-clustering.
                    // The forced-variant index (x-mockserver-response-index) is passed so a forced
                    // request advances Times/matchCount but NOT the response-sequence rotation.
                    if (!expectation.consumeMatch(Expectation.parseForcedResponseIndex(requestDefinition))) {
                        httpRequestMatcher.setResponseInProgress(false);
                        continue;
                    }
                }
                // COMMIT POINT: the expectation is now definitely being served.
                // Apply the scenario transition here (not at the gate above) so it
                // only advances when the expectation is actually consumed. Guarded
                // by getNewScenarioState() != null so non-transitioning expectations
                // are unaffected.
                //
                // For an expectation with a REQUIRED scenario state, transition
                // ATOMICALLY via matchesAndTransition (a CAS on the scenario-state
                // KV store) rather than an unconditional put. This re-checks the
                // required state at the commit point and advances it in a single
                // step, preserving the documented cross-node "exactly one winner"
                // guarantee (docs/code/clustered-state.md): when two nodes race the
                // same step (both passed the pure matchesState gate above, both read
                // "Started"), exactly one CAS succeeds. matchesAndTransition is the
                // correct primitive for BOTH the local in-memory backend (where it
                // is a single-writer ConcurrentHashMap.compute — always succeeds, no
                // single-node regression) AND clustered backends, so it is used
                // unconditionally with no isClustered() branch.
                //
                // Times interaction: this CAS runs AFTER consumeMatch() succeeded.
                // For the dominant unlimited-Times scenario case consumeMatch is a
                // no-op success, so a lost scenario CAS loses nothing. For the rare
                // limited-Times + scenario case, losing the CAS means a Times unit
                // was already consumed on THIS node — an accepted tradeoff that is
                // strictly better than double-serving the same scenario step.
                if (expectation.getScenarioName() != null && expectation.getScenarioState() != null && expectation.getNewScenarioState() != null) {
                    if (!scenarioManager.matchesAndTransition(expectation.getScenarioName(), isolationKey, expectation.getScenarioState(), expectation.getNewScenarioState())) {
                        // Lost the cross-node race: another node already advanced the
                        // scenario past the required state. This node must NOT serve —
                        // fall through to the next expectation (mirrors the Times-CAS
                        // lost-race handling above).
                        httpRequestMatcher.setResponseInProgress(false);
                        continue;
                    }
                }
                if (expectation.getScenarioName() != null && expectation.getScenarioState() == null && expectation.getNewScenarioState() != null) {
                    // Entry-state expectation (no required state to CAS against) —
                    // an unconditional transition is correct here.
                    scenarioManager.transitionState(expectation.getScenarioName(), isolationKey, expectation.getNewScenarioState());
                }
                boolean remainingMatchesDecremented = expectation.getTimes() != null && !expectation.getTimes().isUnlimited();
                if (remainingMatchesDecremented) {
                    notifyListeners(this, Cause.API);
                }
                matchedExpectation = expectation;
                break;
            } else {
                if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                    scheduleLazyRemoval(httpRequestMatcher);
                }
                int failures = matchDifference.getAllDifferences().size();
                if (failures < closestMatchFailures && httpRequestMatcher.getExpectation() != null) {
                    closestMatchFailures = failures;
                    closestMatchExpectation = httpRequestMatcher.getExpectation();
                    closestMatchMatcher = httpRequestMatcher;
                }
            }
        }

        // T1-C1 cold-path reconciliation: when the candidate index was used and nothing
        // matched, the in-loop accumulation above only saw the candidate subset, so the
        // closest-match diagnostic and the namespace-skip count could differ from the full
        // scan. Recompute them over the FULL sorted list here so the EMITTED diagnostics are
        // byte-for-byte identical to the un-indexed behaviour.
        //
        // GATED on the diagnostics actually being emittable: the closest-match "matched X/Y"
        // entry fires only at INFO, the namespace-silence entry only at DEBUG. When neither is
        // enabled (the perf-tuned deployment the index targets) this O(n) reconciliation is
        // skipped entirely — preserving the index's miss-path speedup — because its only other
        // effect, the best-effort lazy removal of inactive non-candidate matchers, is redundant
        // (it is also driven by retrieveActiveExpectations / postProcess / any later scan that
        // observes the matcher). When INFO/DEBUG IS enabled the full reconciliation runs, so the
        // diagnostics (and the lazy removal that the un-indexed scan would have performed) are
        // identical. Net: zero change to any EMITTED diagnostic; the only difference when logging
        // is off is the timing of a best-effort cleanup, never a matching result.
        boolean diagnosticsEmittable = mockServerLogger.isEnabledForInstance(Level.INFO)
            || mockServerLogger.isEnabledForInstance(Level.DEBUG);
        if (useCandidateIndex && matchedExpectation == null && diagnosticsEmittable) {
            ClosestMatchAccumulator accumulator = fullScanClosestMatchAndLazyRemoval(requestDefinition, detailedMatchFailures, sharedMatchDifference);
            closestMatchExpectation = accumulator.closestMatchExpectation;
            closestMatchMatcher = accumulator.closestMatchMatcher;
            closestMatchFailures = accumulator.closestMatchFailures;
            // Reconcile the namespace-skip count over the FULL list so the namespaced-
            // silence DEBUG diagnostic below is identical to the un-indexed scan (the
            // narrowed candidate scan above only saw the candidate subset's skips).
            namespaceSkipped = accumulator.namespaceSkipped;
        }

        if (matchedExpectation == null && closestMatchExpectation != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            // Cold path only (no match AND INFO logging on): compute a MEANINGFUL
            // matched/total ratio. The denominator is the number of match fields
            // APPLICABLE to this request's protocol (an HttpRequest never exercises
            // the DNS/binary/OpenAPI/operation fields, so counting all 16 enum
            // constants inflated it). The numerator is computed by re-evaluating the
            // closest matcher WITHOUT fail-fast so the count reflects every applicable
            // field — under the default fail-fast the hot-path MatchDifference collapses
            // to at most one recorded failure, which would report "matched N-1/N" for
            // almost any mismatch. This extra evaluation is intentionally confined to
            // this already-gated cold path; the hot serving scan above is untouched and
            // keeps fail-fast.
            int totalFields = applicableFieldCount(requestDefinition);
            int matchedFields = countMatchedApplicableFields(closestMatchMatcher, requestDefinition, totalFields, closestMatchFailures);
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_NOT_MATCHED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(requestDefinition.getLogCorrelationId())
                    .setHttpRequest(requestDefinition)
                    .setExpectation(closestMatchExpectation)
                    .setMessageFormat("closest expectation:{}matched " + matchedFields + "/" + totalFields + " fields for request:{}")
                    .setArguments(closestMatchExpectation.clone(), requestDefinition)
            );
        }

        // Namespace-gated silence diagnostic: when a namespaced request matched
        // nothing AND at least one expectation was excluded SOLELY by the namespace
        // gate, surface a DEBUG entry so the "no expectation, no closest match"
        // silence is explained. DEBUG-only so default-level behaviour is unchanged
        // and there is no noise on the common no-namespace path (namespaceSkipped
        // stays 0 unless the request carried a namespace).
        if (matchedExpectation == null && namespaceSkipped > 0 && mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_NOT_MATCHED)
                    .setLogLevel(Level.DEBUG)
                    .setCorrelationId(requestDefinition.getLogCorrelationId())
                    .setHttpRequest(requestDefinition)
                    .setMessageFormat("request in namespace:{}did not match any expectation; " + namespaceSkipped + " expectation(s) skipped due to namespace mismatch")
                    .setArguments(requestNamespace)
            );
        }

        if (configuration.metricsEnabled()) {
            if (matchedExpectation == null || matchedExpectation.getAction() == null) {
                metrics.increment(EXPECTATIONS_NOT_MATCHED_COUNT);
            } else if (matchedExpectation.getAction().getType().direction == Action.Direction.FORWARD) {
                metrics.increment(FORWARD_EXPECTATIONS_MATCHED_COUNT);
            } else {
                metrics.increment(RESPONSE_EXPECTATIONS_MATCHED_COUNT);
            }
            if (matchedExpectation != null && matchedExpectation.getAction() != null) {
                // Opt-in per-expectation counter (perExpectationMetricsEnabled).
                // No-op unless the counter is registered; labeled by the stable
                // expectation id to bound Prometheus cardinality.
                Metrics.incrementExpectationMatched(matchedExpectation.getId());
            }
        }
        return matchedExpectation;
    }

    /**
     * Whether the candidate index may be soundly used for this request. True for any
     * non-{@link HttpRequest} request definition (DNS/binary/OpenAPI — their candidate
     * set is the fallthrough only, which is correct since no such expectation is ever
     * bucketed). For an {@link HttpRequest} it is true only when BOTH the request method
     * and path are non-blank: a blank request method/path passes the method/path gate of
     * EVERY expectation (the matcher short-circuits on a blank REQUEST value), so it could
     * match a bucketed literal in any bucket and must not be narrowed to one bucket.
     */
    private static boolean requestHasConcreteMethodAndPath(RequestDefinition requestDefinition) {
        if (!(requestDefinition instanceof HttpRequest)) {
            return true;
        }
        HttpRequest request = (HttpRequest) requestDefinition;
        return request.getMethod() != null && !request.getMethod().isBlank()
            && request.getPath() != null && !request.getPath().isBlank();
    }

    /**
     * Holder for the result of {@link #fullScanClosestMatchAndLazyRemoval}.
     */
    private static final class ClosestMatchAccumulator {
        Expectation closestMatchExpectation = null;
        HttpRequestMatcher closestMatchMatcher = null;
        int closestMatchFailures = Integer.MAX_VALUE;
        // Number of expectations skipped purely by the namespace gate during the full
        // scan — reconciled back so the namespaced-silence DEBUG diagnostic matches the
        // un-indexed scan (only counted when the request carries a namespace).
        int namespaceSkipped = 0;
    }

    /**
     * Full-scan reconciliation used ONLY on the cold no-match path when the candidate
     * index narrowed the serving scan. Re-walks the ENTIRE sorted matcher list (the same
     * order the un-indexed scan uses) and reproduces, byte-for-byte, the two side-effects
     * the narrowed scan could not have produced for non-candidate matchers:
     * <ol>
     *   <li>the closest-match accumulation (fewest field differences, first wins on a tie,
     *       namespace-gated), and</li>
     *   <li>the lazy removal scheduling of inactive non-matching matchers.</li>
     * </ol>
     * This mirrors the non-match {@code else} branch of {@link #firstMatchingExpectation}
     * exactly (namespace gate, per-matcher MatchDifference handling, closest selection,
     * lazy removal), so the diagnostic and cleanup behaviour is identical to the full scan.
     * It does NOT re-run the match commit logic (Times/scenario/metrics) — by definition no
     * expectation matched, so there is nothing to commit. Runs only above the threshold and
     * only on a miss (already a cold path).
     */
    private ClosestMatchAccumulator fullScanClosestMatchAndLazyRemoval(RequestDefinition requestDefinition, boolean detailedMatchFailures, MatchDifference sharedMatchDifference) {
        ClosestMatchAccumulator accumulator = new ClosestMatchAccumulator();
        String requestNamespace = extractRequestNamespace(requestDefinition);
        for (HttpRequestMatcher httpRequestMatcher : httpRequestMatchers.toSortedList()) {
            if (!matchesNamespace(httpRequestMatcher.getExpectation(), requestNamespace)) {
                if (requestNamespace != null) {
                    accumulator.namespaceSkipped++;
                }
                continue;
            }
            MatchDifference matchDifference = detailedMatchFailures
                ? new MatchDifference(true, requestDefinition)
                : sharedMatchDifference;
            if (!httpRequestMatcher.matches(matchDifference, requestDefinition)) {
                if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                    scheduleLazyRemoval(httpRequestMatcher);
                }
                int failures = matchDifference.getAllDifferences().size();
                if (failures < accumulator.closestMatchFailures && httpRequestMatcher.getExpectation() != null) {
                    accumulator.closestMatchFailures = failures;
                    accumulator.closestMatchExpectation = httpRequestMatcher.getExpectation();
                    accumulator.closestMatchMatcher = httpRequestMatcher;
                }
            }
        }
        return accumulator;
    }

    public Expectation firstMatchingEarlyExpectation(HttpRequest headersOnlyRequest) {
        // Control-plane requests (path under HttpState.PATH_PREFIX, e.g. /mockserver/reset,
        // /mockserver/status) must never be answered by a data-plane early (respondBeforeBody)
        // expectation. EarlyMatchingHandler runs before the control-plane dispatch in
        // HttpState.handle(), so without this guard a catch-all respondBeforeBody expectation
        // (e.g. seeded via an initialization file) would hijack the server's own management API.
        if (headersOnlyRequest != null
            && headersOnlyRequest.getPath() != null
            && headersOnlyRequest.getPath().getValue() != null
            && headersOnlyRequest.getPath().getValue().startsWith(HttpState.PATH_PREFIX)) {
            return null;
        }
        String requestNamespace = extractRequestNamespace(headersOnlyRequest);
        for (HttpRequestMatcher httpRequestMatcher : httpRequestMatchers.toSortedList()) {
            Expectation expectation = httpRequestMatcher.getExpectation();
            if (expectation == null || !(expectation.getHttpRequest() instanceof HttpRequest)) {
                continue;
            }
            if (!matchesNamespace(expectation, requestNamespace)) {
                continue;
            }
            HttpRequest expectationRequest = (HttpRequest) expectation.getHttpRequest();
            if (!Boolean.TRUE.equals(expectationRequest.getRespondBeforeBody())) {
                continue;
            }
            if (httpRequestMatcher instanceof org.mockserver.matchers.HttpRequestPropertiesMatcher
                && ((org.mockserver.matchers.HttpRequestPropertiesMatcher) httpRequestMatcher).hasBodyMatcher()) {
                continue;
            }
            if (httpRequestMatcher.matches(null, headersOnlyRequest)) {
                String isolationKey = extractIsolationKey(expectation, headersOnlyRequest);
                // Scenario gate: check the required state WITHOUT transitioning.
                // The transition happens only at the commit point below, once the
                // expectation is actually consumed — see firstMatchingExpectation
                // for the consume-then-skip rationale.
                if (expectation.getScenarioName() != null && expectation.getScenarioState() != null) {
                    if (!scenarioManager.matchesState(expectation.getScenarioName(), isolationKey, expectation.getScenarioState())) {
                        continue;
                    }
                }
                if (!expectation.matchesByPercentage()) {
                    continue;
                }
                httpRequestMatcher.setResponseInProgress(true);
                // Clustered shared-Times CAS (early/respondBeforeBody path)
                if (isClusteredLimitedTimes(expectation)) {
                    ConsumeTimesResult casResult = consumeTimesViaBackendCas(expectation);
                    if (!casResult.success) {
                        httpRequestMatcher.setResponseInProgress(false);
                        if (casResult.exhausted) {
                            scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
                        }
                        continue;
                    }
                    expectation.consumeMatchLocally(Expectation.parseForcedResponseIndex(headersOnlyRequest));
                } else {
                    if (!expectation.consumeMatch(Expectation.parseForcedResponseIndex(headersOnlyRequest))) {
                        httpRequestMatcher.setResponseInProgress(false);
                        continue;
                    }
                }
                // COMMIT POINT: apply the scenario transition only now that the
                // expectation is definitely being served (post-consume).
                //
                // For an expectation with a REQUIRED scenario state, transition
                // ATOMICALLY via matchesAndTransition (a CAS) — see the detailed
                // rationale on the firstMatchingExpectation commit point. This keeps
                // the cross-node "exactly one winner" guarantee on clustered backends
                // while being a no-op-equivalent single-writer compute on the local
                // backend (no single-node regression). The same accepted limited-
                // Times tradeoff applies: a lost CAS means a Times unit was already
                // consumed on this node, which is strictly better than double-serving.
                if (expectation.getScenarioName() != null && expectation.getScenarioState() != null && expectation.getNewScenarioState() != null) {
                    if (!scenarioManager.matchesAndTransition(expectation.getScenarioName(), isolationKey, expectation.getScenarioState(), expectation.getNewScenarioState())) {
                        // Lost the cross-node race — do not serve, fall through.
                        httpRequestMatcher.setResponseInProgress(false);
                        continue;
                    }
                }
                if (expectation.getScenarioName() != null && expectation.getScenarioState() == null && expectation.getNewScenarioState() != null) {
                    // Entry-state expectation (no required state to CAS against).
                    scenarioManager.transitionState(expectation.getScenarioName(), isolationKey, expectation.getNewScenarioState());
                }
                if (expectation.getTimes() != null && !expectation.getTimes().isUnlimited()) {
                    notifyListeners(this, Cause.API);
                }
                if (configuration.metricsEnabled() && expectation.getAction() != null) {
                    // Opt-in per-expectation counter (perExpectationMetricsEnabled).
                    // No-op unless the counter is registered; count early-matched
                    // (respondBeforeBody) expectations consistently with the normal path.
                    Metrics.incrementExpectationMatched(expectation.getId());
                }
                return expectation;
            }
        }
        return null;
    }

    public void clear(RequestDefinition requestDefinition) {
        if (requestDefinition != null) {
            HttpRequestMatcher clearHttpRequestMatcher = matcherBuilder.transformsToMatcher(requestDefinition);
            getHttpRequestMatchersCopy().forEach(httpRequestMatcher -> {
                RequestDefinition request = httpRequestMatcher
                    .getExpectation()
                    .getHttpRequest();
                if (isNotBlank(requestDefinition.getLogCorrelationId())) {
                    request = request
                        .shallowClone()
                        .withLogCorrelationId(requestDefinition.getLogCorrelationId());
                }
                if (clearHttpRequestMatcher.matches(request)) {
                    removeHttpRequestMatcher(httpRequestMatcher, requestDefinition.getLogCorrelationId());
                }
            });
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(CLEARED)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(requestDefinition.getLogCorrelationId())
                        .setHttpRequest(requestDefinition)
                        .setMessageFormat("cleared expectations that match:{}")
                        .setArguments(requestDefinition)
                );
            }
        } else {
            reset();
        }
    }

    /**
     * Clears only the expectations belonging to the given namespace (tenant),
     * leaving expectations in other namespaces (and global expectations) intact.
     * This lets a tenant clean up after itself on a shared MockServer instance.
     * <p>
     * When {@code namespace} is blank this is a no-op (use {@link #reset()} or a
     * request-matcher clear for a full clear) so that a blank namespace filter
     * never accidentally clears global expectations.
     *
     * @param namespace        the namespace whose expectations to clear
     * @param logCorrelationId correlation id for the resulting CLEARED log entry
     */
    public void clearByNamespace(String namespace, String logCorrelationId) {
        if (isBlank(namespace)) {
            return;
        }
        AtomicBoolean removedAny = new AtomicBoolean(false);
        getHttpRequestMatchersCopy().forEach(httpRequestMatcher -> {
            Expectation expectation = httpRequestMatcher.getExpectation();
            if (expectation != null && namespace.equals(expectation.getNamespace())) {
                removeHttpRequestMatcher(httpRequestMatcher, logCorrelationId);
                removedAny.set(true);
            }
        });
        // Only emit a CLEARED event when at least one expectation was actually
        // removed; an idempotent CI teardown clearing an empty namespace stays quiet.
        if (removedAny.get() && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(CLEARED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("cleared expectations in namespace:{}")
                    .setArguments(namespace)
            );
        }
    }

    public void clear(ExpectationId expectationId, String logCorrelationId) {
        if (expectationId != null) {
            httpRequestMatchers
                .getByKey(expectationId.getId())
                .ifPresent(httpRequestMatcher -> removeHttpRequestMatcher(httpRequestMatcher, logCorrelationId));
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(CLEARED)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("cleared expectations that have id:{}")
                        .setArguments(expectationId.getId())
                );
            }
        } else {
            reset();
        }
    }

    /**
     * Reconciles the node-local HttpRequestMatcher cache against the backend
     * KeyValueStore. This handles three cases:
     * <ol>
     *   <li><b>Eviction:</b> cached matchers whose id is no longer in the
     *       backend are removed (mirrors maxExpectations eviction).</li>
     *   <li><b>Remote add:</b> backend entries with no local matcher get a
     *       new compiled HttpRequestMatcher (enables cross-node visibility
     *       under clustering).</li>
     *   <li><b>Remote update:</b> backend entries whose version is newer
     *       than the locally cached version get their matcher rebuilt.</li>
     * </ol>
     * <p>
     * In single-node / no-backend mode this method is a no-op. When the
     * backend is LOCAL (non-clustered), only eviction applies because all
     * mutations originate locally and the CPQ is already in sync.
     * <p>
     * <b>Threading contract:</b> serialized via {@code synchronized} so
     * that concurrent remote invalidation events (from a clustered backend)
     * do not corrupt the node-local CPQ. Local mutations are still
     * single-writer (Netty event loop / action lock); the lock is
     * reentrant-safe for local callers because Java's {@code synchronized}
     * is reentrant.
     * <p>
     * <b>Concurrent matching (data-plane) note:</b> this method applies
     * incremental per-entry mutations (add/update/remove) to the CPQ — the
     * same granularity as normal control-plane add/remove. The CPQ's
     * {@code toSortedList()} provides an eventually-consistent sorted
     * snapshot via {@code ConcurrentSkipListSet + volatile sortedCache +
     * filter(nonNull)}. A matching thread calling {@code toSortedList()}
     * during a reconcile may see a snapshot that lags by one mutation, but
     * will never see a torn/empty view. This matches the pre-existing
     * control-plane / data-plane concurrency contract.
     */
    public synchronized void reconcileFromBackend() {
        if (expectationBackend == null) {
            return;
        }
        // Fast path: for the non-clustered (in-memory default) backend, all
        // mutations originate locally and the node-local CPQ is already in sync
        // except for backend eviction. The full reconcile below snapshots the
        // ENTIRE backend into a HashMap and walks every entry on every add/update
        // — and InMemoryExpectationKeyValueStore.put() also fires an invalidation
        // listener that calls back into this method, so an unconditional full
        // reconcile costs TWO O(n) passes per mutation → O(n^2) registration.
        // A cheap eviction-only trim is all that is needed here; the expensive
        // remote-add/remote-update reconciliation is required only for a
        // clustered backend (where entries can appear/change on other nodes).
        if (stateBackend == null || !stateBackend.isClustered()) {
            trimEvictedFromBackend();
            return;
        }
        reconcileClusteredScan();
    }

    /**
     * The expensive clustered reconcile scan: snapshots the entire backend and
     * diffs it against the node-local matcher cache (evict / remote-add /
     * remote-update). Only ever invoked from {@link #reconcileFromBackend()}
     * under its {@code synchronized} monitor.
     */
    private void reconcileClusteredScan() {
        // Snapshot backend state
        Map<String, KeyValueStore.Entry<ExpectationEntry>> backendEntries = new HashMap<>();
        expectationBackend.entries().forEach(e -> backendEntries.put(e.getKey(), e));

        Set<String> backendIds = backendEntries.keySet();

        // 1. Remove evicted matchers (id no longer in backend)
        List<String> evictedIds = new ArrayList<>();
        for (String cachedId : matcherCacheById.keySet()) {
            if (!backendIds.contains(cachedId)) {
                evictedIds.add(cachedId);
            }
        }
        for (String evictedId : evictedIds) {
            HttpRequestMatcher evictedMatcher = matcherCacheById.remove(evictedId);
            if (evictedMatcher != null) {
                httpRequestMatchers.remove(evictedMatcher);
            }
            expectationRequestDefinitions.remove(evictedId);
            lastReconciledVersion.remove(evictedId);
        }

        // 2. Add new entries and update stale entries (remote writes)
        for (Map.Entry<String, KeyValueStore.Entry<ExpectationEntry>> entry : backendEntries.entrySet()) {
            String id = entry.getKey();
            long backendVersion = entry.getValue().getVersion();
            ExpectationEntry backendEntry = entry.getValue().getValue();
            Expectation expectation = backendEntry.getExpectation();

            HttpRequestMatcher existing = matcherCacheById.get(id);
            if (existing == null) {
                // New entry from remote node — build matcher locally
                HttpRequestMatcher newMatcher = matcherBuilder.transformsToMatcher(expectation);
                httpRequestMatchers.add(newMatcher);
                newMatcher.withSource(Cause.API);
                matcherCacheById.put(id, newMatcher);
                expectationRequestDefinitions.put(id, expectation.getHttpRequest());
                lastReconciledVersion.put(id, backendVersion);
                if (expectation.getAction() != null) {
                    metrics.increment(expectation.getAction().getType());
                }
            } else if (existing.getExpectation() != null) {
                // Check if backend version is strictly newer than the last
                // version we reconciled for this id. This catches ALL remote
                // updates — not just sort-field changes (id/priority/created)
                // but also response body, request pattern, or action changes.
                Long lastVersion = lastReconciledVersion.get(id);
                if (lastVersion == null || backendVersion > lastVersion) {
                    // Update the matcher preserving runtime state (Times,
                    // responseInProgress). Re-insert priority key if sort
                    // fields changed.
                    httpRequestMatchers.removePriorityKey(existing);
                    existing.update(expectation);
                    httpRequestMatchers.addPriorityKey(existing);
                    matcherCacheById.put(id, existing);
                    expectationRequestDefinitions.put(id, expectation.getHttpRequest());
                    lastReconciledVersion.put(id, backendVersion);
                }
            }
        }
        // Clustered reconcile may have added/updated/removed matchers — invalidate
        // the candidate index unconditionally (a single atomic increment).
        markMatchersModified();
    }

    /**
     * Cheap eviction-only trim for the non-clustered (in-memory default)
     * backend. The node-local cache is already in sync with the backend for
     * every add/update (those mutations are mirrored synchronously into the
     * CPQ), so the ONLY divergence to reconcile after a local mutation is
     * backend-side eviction: when the backend's own CPQ self-evicts the oldest
     * entry past {@code maxExpectations}, the node-local cache (whose CPQ is at
     * {@code Integer.MAX_VALUE} — see {@link #setStateBackend}) still holds that
     * now-evicted id and must drop it.
     * <p>
     * Common-case fast exit: if the node-local cache holds no more ids than the
     * backend, nothing was evicted and this returns immediately without
     * iterating. Only when {@code matcherCacheById.size() > backend.size()} —
     * i.e. an eviction actually happened — does it walk the cached ids and drop
     * those no longer present in the backend. This keeps registration O(n)
     * overall instead of the O(n^2) caused by a full snapshot-and-walk reconcile
     * on every add.
     */
    private void trimEvictedFromBackend() {
        if (expectationBackend == null) {
            return;
        }
        if (matcherCacheById.size() <= expectationBackend.size()) {
            // Nothing was evicted — the common case. No iteration needed.
            return;
        }
        // An eviction happened: build the set of ids still present in the
        // backend, then drop any cached id no longer present.
        Set<String> backendIds = new HashSet<>();
        expectationBackend.entries().forEach(e -> backendIds.add(e.getKey()));
        List<String> evictedIds = new ArrayList<>();
        for (String cachedId : matcherCacheById.keySet()) {
            if (!backendIds.contains(cachedId)) {
                evictedIds.add(cachedId);
            }
        }
        for (String evictedId : evictedIds) {
            HttpRequestMatcher evictedMatcher = matcherCacheById.remove(evictedId);
            if (evictedMatcher != null) {
                httpRequestMatchers.remove(evictedMatcher);
            }
            expectationRequestDefinitions.remove(evictedId);
            lastReconciledVersion.remove(evictedId);
        }
        // Backend eviction dropped matchers from the node-local store — invalidate
        // the candidate index so it no longer holds the evicted entries.
        if (!evictedIds.isEmpty()) {
            markMatchersModified();
        }
    }

    /**
     * Backward-compatible alias: reconciles evictions only. Called after
     * local mutations where the node-local CPQ is already up-to-date
     * except for backend eviction. Delegates to the full reconcile.
     */
    private void reconcileEvictions() {
        reconcileFromBackend();
    }

    public Expectation postProcess(Expectation expectation) {
        if (expectation != null) {
            // O(1) fast path: the id-keyed cache is kept in sync on add/update/remove. Guard with a
            // reference-equality check so semantics exactly match the original O(n) scan — if the
            // cached matcher's expectation is a different instance (e.g. updated under the same id
            // since this request matched), fall back to the scan, which finds nothing in that case.
            HttpRequestMatcher cachedMatcher = matcherCacheById.get(expectation.getId());
            if (cachedMatcher != null && cachedMatcher.getExpectation() == expectation) {
                if (!expectation.isActive()) {
                    removeHttpRequestMatcher(cachedMatcher, UUIDService.getUUID());
                }
                cachedMatcher.setResponseInProgress(false);
            } else {
                getHttpRequestMatchersCopy()
                    .filter(httpRequestMatcher -> httpRequestMatcher.getExpectation() == expectation)
                    .findFirst()
                    .ifPresent(httpRequestMatcher -> {
                        if (!expectation.isActive()) {
                            removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID());
                        }
                        httpRequestMatcher.setResponseInProgress(false);
                    });
            }
        }
        return expectation;
    }

    private void removeHttpRequestMatcher(HttpRequestMatcher httpRequestMatcher, String logCorrelationId) {
        removeHttpRequestMatcher(httpRequestMatcher, Cause.API, true, logCorrelationId);
    }

    /**
     * Schedules the lazy async removal of an inactive matcher AT MOST ONCE.
     * Several data-plane scans independently observe a matcher as
     * {@code !responseInProgress && !active} and would each submit a removal task
     * for it. The removal itself is idempotent, but the duplicate submissions add
     * needless scheduler and backend load under churn. {@link HttpRequestMatcher}
     * (via {@code AbstractHttpRequestMatcher}) CAS-guards the claim so only the
     * first observer actually schedules the task. A matcher that is not an
     * {@code AbstractHttpRequestMatcher} (none in practice) falls back to scheduling
     * unconditionally so removal is never lost.
     */
    private void scheduleLazyRemoval(HttpRequestMatcher httpRequestMatcher) {
        boolean shouldSchedule = !(httpRequestMatcher instanceof org.mockserver.matchers.AbstractHttpRequestMatcher)
            || ((org.mockserver.matchers.AbstractHttpRequestMatcher) httpRequestMatcher).tryScheduleRemoval();
        if (shouldSchedule) {
            scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
        }
    }

    @SuppressWarnings("rawtypes")
    private void removeHttpRequestMatcher(HttpRequestMatcher httpRequestMatcher, Cause cause, boolean notifyAndUpdateMetrics, String logCorrelationId) {
        if (httpRequestMatchers.remove(httpRequestMatcher)) {
            // Invalidate the candidate index — a matcher was removed from the store.
            markMatchersModified();
            // Remove from backend KV and node-local cache
            if (httpRequestMatcher.getExpectation() != null) {
                String id = httpRequestMatcher.getExpectation().getId();
                matcherCacheById.remove(id);
                lastReconciledVersion.remove(id);
                if (expectationBackend != null) {
                    expectationBackend.remove(id);
                }
            }
            if (httpRequestMatcher.getExpectation() != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                Expectation expectation = httpRequestMatcher.getExpectation().clone();
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(REMOVED_EXPECTATION)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setHttpRequest(httpRequestMatcher.getExpectation().getHttpRequest())
                        .setMessageFormat(REMOVED_EXPECTATION_MESSAGE_FORMAT)
                        .setArguments(expectation, expectation.getId())
                );
            }
            if (httpRequestMatcher.getExpectation() != null) {
                clearOrphanedScenarioState(httpRequestMatcher.getExpectation());
                final Action action = httpRequestMatcher.getExpectation().getAction();
                if (action instanceof HttpObjectCallback) {
                    webSocketClientRegistry.unregisterClient(((HttpObjectCallback) action).getClientId());
                }
                if (notifyAndUpdateMetrics && action != null) {
                    metrics.decrement(action.getType());
                }
            }
            if (notifyAndUpdateMetrics) {
                notifyListeners(this, cause);
            }
        }
    }

    private void clearOrphanedScenarioState(Expectation removed) {
        String scenarioName = removed.getScenarioName();
        if (isBlank(scenarioName)) {
            return;
        }
        boolean hasRemaining = httpRequestMatchers.stream()
            .anyMatch(m -> m.getExpectation() != null
                && m.getExpectation() != removed
                && scenarioName.equals(m.getExpectation().getScenarioName()));
        if (!hasRemaining) {
            scenarioManager.clear(scenarioName);
        }
    }

    public Stream<RequestDefinition> retrieveRequestDefinitions(List<ExpectationId> expectationIds) {
        return expectationIds
            .stream()
            .map(expectationId -> {
                if (isBlank(expectationId.getId())) {
                    throw new IllegalArgumentException("No expectation id specified found \"" + expectationId.getId() + "\"");
                }
                if (expectationRequestDefinitions.containsKey(expectationId.getId())) {
                    return expectationRequestDefinitions.get(expectationId.getId());
                } else if (expectationBackend != null) {
                    // Fall back to backend KV as source of truth
                    return expectationBackend.get(expectationId.getId())
                        .map(v -> v.getValue().getExpectation().getHttpRequest())
                        .orElseThrow(() -> new IllegalArgumentException("No expectation found with id " + expectationId.getId()));
                } else {
                    throw new IllegalArgumentException("No expectation found with id " + expectationId.getId());
                }
            })
            .filter(Objects::nonNull);
    }

    public List<Expectation> retrieveActiveExpectations(RequestDefinition requestDefinition) {
        if (requestDefinition == null) {
            return httpRequestMatchers.stream()
                .filter(httpRequestMatcher -> {
                    if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                        scheduleLazyRemoval(httpRequestMatcher);
                        return false;
                    }
                    return true;
                })
                .map(HttpRequestMatcher::getExpectation)
                .collect(Collectors.toList());
        } else {
            List<Expectation> expectations = new ArrayList<>();
            HttpRequestMatcher requestMatcher = matcherBuilder.transformsToMatcher(requestDefinition);
            getHttpRequestMatchersCopy().forEach(httpRequestMatcher -> {
                if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                    scheduleLazyRemoval(httpRequestMatcher);
                } else {
                    RequestDefinition expectationDefinition = httpRequestMatcher.getExpectation().getHttpRequest();
                    if (notFilterableByRequest(requestDefinition, expectationDefinition) || requestMatcher.matches(expectationDefinition)) {
                        expectations.add(httpRequestMatcher.getExpectation());
                    }
                }
            });
            return expectations;
        }
    }

    /**
     * Determines whether an expectation should bypass reverse-match filtering because the
     * supplied filter cannot describe it.
     *
     * <p>The dashboard UI and the {@code PUT /mockserver/retrieve} endpoint filter active
     * expectations using an HTTP-shaped {@link RequestDefinition} — and when no filter is
     * supplied they send an <em>empty</em> {@link HttpRequest} (matches everything) rather
     * than {@code null}. An HTTP/OpenAPI filter has no vocabulary to express non-HTTP
     * protocol expectations such as {@link DnsRequestDefinition} or
     * {@link BinaryRequestDefinition}: reverse-matching it against them always fails (see
     * {@code HttpRequestPropertiesMatcher#matches}). Filtering on that basis would silently
     * hide every DNS and binary mock from "active expectations" listings (e.g. the Mocks
     * page), even with no filter applied. Such expectations therefore bypass the filter and
     * are always listed whenever the filter itself is HTTP/OpenAPI shaped. A filter of the
     * matching protocol (e.g. a {@link DnsRequestDefinition} filter) still narrows normally.
     */
    private boolean notFilterableByRequest(RequestDefinition filter, RequestDefinition expectationDefinition) {
        boolean httpStyleFilter = filter instanceof HttpRequest || filter instanceof OpenAPIDefinition;
        boolean nonHttpExpectation = !(expectationDefinition instanceof HttpRequest)
            && !(expectationDefinition instanceof OpenAPIDefinition);
        return httpStyleFilter && nonHttpExpectation;
    }

    /**
     * Returns every active expectation whose request matcher matches the given concrete
     * incoming request, using <em>forward</em> matching (the same direction used when
     * serving — "does this expectation match this request?"). This differs from
     * {@link #retrieveActiveExpectations(RequestDefinition)}, which treats its argument
     * as a filter and reverse-matches it against each expectation's definition.
     *
     * <p>Used by drift analysis on the proxy-forward path: a forwarded request needs the
     * set of <em>other</em> matching stubs (e.g. a lower-priority response-type baseline)
     * to diff the real upstream response against. Reverse/filter matching cannot be used
     * there because the concrete request carries headers/cookies that bare stub
     * definitions do not, so it would never match.
     */
    /**
     * Side-effect-free probe: returns the first active expectation whose matcher matches the
     * given request, WITHOUT consuming the match. Specifically, this method avoids:
     * <ul>
     *   <li>Times decrement ({@code consumeMatch()})</li>
     *   <li>Scenario state transition</li>
     *   <li>{@code responseInProgress} flag</li>
     *   <li>Metrics increment</li>
     * </ul>
     * <p>
     * <strong>Note on logging:</strong> the underlying {@code HttpRequestMatcher.matches()} call
     * may still emit {@code INFO}-level {@code EXPECTATION_MATCHED} or {@code EXPECTATION_NOT_MATCHED}
     * log entries as a side-effect of the match evaluation. This method does not suppress those
     * match-diagnostic logs. It is the Times/scenario/responseInProgress/metrics side-effects
     * that are avoided.
     * <p>
     * Used by the gRPC bidi router to decide the routing path before committing to a handler.
     * Callers that need to actually consume the match (decrement Times, transition scenarios,
     * emit logs) must still call {@link #firstMatchingExpectation(RequestDefinition)} separately
     * on the committed path.
     */
    public Expectation peekFirstMatchingExpectation(RequestDefinition requestDefinition) {
        if (requestDefinition == null) {
            return null;
        }
        for (HttpRequestMatcher httpRequestMatcher : httpRequestMatchers.toSortedList()) {
            if ((httpRequestMatcher.isResponseInProgress() || httpRequestMatcher.isActive())
                && httpRequestMatcher.matches(requestDefinition)) {
                return httpRequestMatcher.getExpectation();
            }
        }
        return null;
    }

    public List<Expectation> retrieveExpectationsMatchingRequest(RequestDefinition requestDefinition) {
        List<Expectation> expectations = new ArrayList<>();
        if (requestDefinition == null) {
            return expectations;
        }
        getHttpRequestMatchersCopy().forEach(httpRequestMatcher -> {
            if ((httpRequestMatcher.isResponseInProgress() || httpRequestMatcher.isActive())
                && httpRequestMatcher.matches(requestDefinition)) {
                expectations.add(httpRequestMatcher.getExpectation());
            }
        });
        return expectations;
    }

    public List<HttpRequestMatcher> retrieveRequestMatchers(RequestDefinition requestDefinition) {
        if (requestDefinition == null) {
            return httpRequestMatchers.stream()
                .filter(httpRequestMatcher -> {
                    if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                        scheduleLazyRemoval(httpRequestMatcher);
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        } else {
            List<HttpRequestMatcher> httpRequestMatchers = new ArrayList<>();
            HttpRequestMatcher requestMatcher = matcherBuilder.transformsToMatcher(requestDefinition);
            getHttpRequestMatchersCopy().forEach(httpRequestMatcher -> {
                if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                    scheduleLazyRemoval(httpRequestMatcher);
                } else {
                    RequestDefinition expectationDefinition = httpRequestMatcher.getExpectation().getHttpRequest();
                    if (notFilterableByRequest(requestDefinition, expectationDefinition) || requestMatcher.matches(expectationDefinition)) {
                        httpRequestMatchers.add(httpRequestMatcher);
                    }
                }
            });
            return httpRequestMatchers;
        }
    }

    /**
     * Number of match fields APPLICABLE to the given request's protocol — the
     * correct denominator for the closest-expectation "matched X/Y fields"
     * diagnostic. An {@link HttpRequest} (the common case) exercises the ten HTTP
     * fields only; a {@link DnsRequestDefinition} exercises the three DNS fields;
     * a {@link BinaryRequestDefinition} exercises the single binary-body field.
     * Anything else (e.g. OpenAPI) falls back to the full enum size.
     */
    private static int applicableFieldCount(RequestDefinition requestDefinition) {
        MatchDifference.Field[] applicable = applicableFields(requestDefinition);
        return applicable != null ? applicable.length : MatchDifference.Field.values().length;
    }

    /**
     * Computes how many of the request's applicable fields the closest expectation
     * actually matched, for the cold-path "matched X/Y fields" diagnostic only.
     * <p>
     * The hot-path {@link MatchDifference} that produced {@code closestMatchFailures}
     * was evaluated with the default fail-fast matching, which stops at the first
     * failing field and therefore records at most one difference — so a naive
     * {@code applicable - closestMatchFailures} would report an almost-perfect match
     * for nearly any mismatch. To get a real count this re-evaluates the closest
     * matcher with fail-fast DISABLED (via {@link #nonFailFastMatcherBuilder}) and a
     * detailed {@link MatchDifference}, then counts the applicable fields that appear
     * in the difference map as failures. This runs ONLY in the already-gated cold
     * path (no match AND INFO logging on); the hot serving scan is untouched and keeps
     * fail-fast.
     * <p>
     * For request/expectation protocols where a non-fail-fast rebuild is not safe or
     * not meaningful (e.g. OpenAPI, whose matcher also consults context-path config),
     * this falls back to the fail-fast-collapsed count, clamped to be non-negative.
     */
    private int countMatchedApplicableFields(HttpRequestMatcher closestMatchMatcher, RequestDefinition requestDefinition, int applicableFields, int collapsedFailures) {
        MatchDifference.Field[] applicable = applicableFields(requestDefinition);
        if (applicable != null && closestMatchMatcher != null && closestMatchMatcher.getExpectation() != null) {
            RequestDefinition expectationDefinition = closestMatchMatcher.getExpectation().getHttpRequest();
            // Only re-evaluate the protocols whose matchers do not depend on extra
            // configuration beyond fail-fast (HTTP, DNS, binary). OpenAPI expectations
            // compile against context-path config, so a fresh non-fail-fast builder
            // could diverge — those take the conservative fallback below.
            boolean safeToRebuild = expectationDefinition instanceof HttpRequest
                || expectationDefinition instanceof DnsRequestDefinition
                || expectationDefinition instanceof BinaryRequestDefinition;
            if (safeToRebuild) {
                try {
                    HttpRequestMatcher nonFailFastMatcher = nonFailFastMatcherBuilder().transformsToMatcher(closestMatchMatcher.getExpectation());
                    // suppressMatchResultLogging: this re-evaluation is diagnostic-only — it must
                    // NOT write EXPECTATION_MATCHED / EXPECTATION_NOT_MATCHED events into the event
                    // log (those would be uncorrelated duplicates of the not-matched scan above).
                    MatchDifference detailed = new MatchDifference(true, requestDefinition).suppressMatchResultLogging();
                    nonFailFastMatcher.matches(detailed, requestDefinition);
                    Map<MatchDifference.Field, List<String>> differences = detailed.getAllDifferences();
                    int failed = 0;
                    for (MatchDifference.Field field : applicable) {
                        if (differences.containsKey(field)) {
                            failed++;
                        }
                    }
                    return Math.max(0, applicableFields - failed);
                } catch (Throwable throwable) {
                    if (mockServerLogger.isEnabledForInstance(TRACE)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(TRACE)
                                .setMessageFormat("exception computing non-fail-fast closest-match field count:{}")
                                .setArguments(throwable.getMessage())
                                .setThrowable(throwable)
                        );
                    }
                    // fall through to the conservative fail-fast-collapsed estimate
                }
            }
        }
        return Math.max(0, applicableFields - collapsedFailures);
    }

    private static MatchDifference.Field[] applicableFields(RequestDefinition requestDefinition) {
        if (requestDefinition instanceof HttpRequest) {
            return HTTP_APPLICABLE_FIELDS;
        }
        if (requestDefinition instanceof DnsRequestDefinition) {
            return DNS_APPLICABLE_FIELDS;
        }
        if (requestDefinition instanceof BinaryRequestDefinition) {
            return BINARY_APPLICABLE_FIELDS;
        }
        return null;
    }

    private MatcherBuilder nonFailFastMatcherBuilder() {
        MatcherBuilder builder = nonFailFastMatcherBuilder;
        if (builder == null) {
            synchronized (this) {
                builder = nonFailFastMatcherBuilder;
                if (builder == null) {
                    // A configuration identical to the operator's for matching purposes,
                    // except fail-fast is OFF so all fields are evaluated for the count.
                    // HTTP/DNS/binary property matchers consult no other configuration at
                    // match time, so a fresh default config differing only in fail-fast is
                    // semantically equivalent for the protocols this builder is used for.
                    Configuration nonFailFastConfiguration = Configuration.configuration()
                        .matchersFailFast(false)
                        .detailedMatchFailures(true);
                    builder = new MatcherBuilder(nonFailFastConfiguration, mockServerLogger);
                    nonFailFastMatcherBuilder = builder;
                }
            }
        }
        return builder;
    }

    public Map<MatchDifference.Field, List<String>> findClosestMatchDiff(HttpRequest httpRequest) {
        ClosestMatchHint hint = findClosestMatchHint(httpRequest);
        return hint == null ? null : hint.getDifferences();
    }

    /**
     * Compact closest-match diagnostic used by the unmatched-404 hint header
     * ({@code closestMatchHintEnabled}). Holds the id of the closest non-matching
     * expectation and the field differences that kept it from matching, so the
     * caller can render a short, safe one-line hint (expectation id + first
     * differing field + reason) without serialising the whole expectation.
     */
    public static final class ClosestMatchHint {
        private final String expectationId;
        private final Map<MatchDifference.Field, List<String>> differences;

        public ClosestMatchHint(String expectationId, Map<MatchDifference.Field, List<String>> differences) {
            this.expectationId = expectationId;
            this.differences = differences;
        }

        public String getExpectationId() {
            return expectationId;
        }

        public Map<MatchDifference.Field, List<String>> getDifferences() {
            return differences;
        }
    }

    /**
     * Find the expectation that came closest to matching the request (fewest field
     * differences) together with its id and field diff. Cold-path only — invoked when
     * a request matched nothing and a diagnostic is being produced. Returns {@code null}
     * when there are no expectations (or none produced a usable diff).
     */
    public ClosestMatchHint findClosestMatchHint(HttpRequest httpRequest) {
        int closestMatchFailures = Integer.MAX_VALUE;
        Map<MatchDifference.Field, List<String>> closestDifferences = null;
        String closestExpectationId = null;

        for (HttpRequestMatcher httpRequestMatcher : httpRequestMatchers.toSortedList()) {
            // suppressMatchResultLogging: this is a read-only diagnostic re-evaluation (it runs
            // AFTER the request already failed to match), so it must NOT emit a second round of
            // EXPECTATION_NOT_MATCHED events into the event log — those would be uncorrelated and
            // would shift/duplicate the recorded log sequence. Especially important now the hint
            // header is on by default, so this scan runs on the common no-match path.
            MatchDifference matchDifference = new MatchDifference(true, httpRequest).suppressMatchResultLogging();
            if (!httpRequestMatcher.matches(matchDifference, httpRequest)) {
                Map<MatchDifference.Field, List<String>> differences = matchDifference.getAllDifferences();
                int failures = differences.size();
                if (failures < closestMatchFailures && httpRequestMatcher.getExpectation() != null) {
                    closestMatchFailures = failures;
                    closestDifferences = differences;
                    closestExpectationId = httpRequestMatcher.getExpectation().getId();
                }
            }
        }
        if (closestDifferences == null) {
            return null;
        }
        return new ClosestMatchHint(closestExpectationId, closestDifferences);
    }

    public boolean isEmpty() {
        return httpRequestMatchers.isEmpty();
    }

    public ScenarioManager getScenarioManager() {
        return scenarioManager;
    }

    protected void notifyListeners(final RequestMatchers notifier, Cause cause) {
        super.notifyListeners(notifier, cause);
    }

    private Stream<HttpRequestMatcher> getHttpRequestMatchersCopy() {
        return httpRequestMatchers.stream();
    }

    /**
     * Extract the isolation key from the request based on the expectation's scenario name.
     * Returns null if no isolation is configured (legacy single-key behaviour).
     */
    private String extractIsolationKey(Expectation expectation, RequestDefinition requestDefinition) {
        String scenarioName = expectation.getScenarioName();
        if (scenarioName == null) {
            return null;
        }
        IsolationSource isoSource = LlmScenarioNames.decodeIsolationSource(scenarioName);
        if (isoSource == null) {
            return null;
        }
        if (!(requestDefinition instanceof HttpRequest)) {
            return null;
        }
        HttpRequest request = (HttpRequest) requestDefinition;
        String value = "";
        switch (isoSource.getKind()) {
            case HEADER:
                value = request.getFirstHeader(isoSource.getName());
                break;
            case QUERY_PARAMETER:
                value = request.getFirstQueryStringParameter(isoSource.getName());
                break;
            case COOKIE:
                if (request.getCookies() != null) {
                    for (Cookie cookie : request.getCookieList()) {
                        if (isoSource.getName().equals(cookie.getName().getValue())) {
                            value = cookie.getValue().getValue();
                            break;
                        }
                    }
                }
                break;
        }
        // When the configured attribute is absent, fall back to shared key (null)
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value;
    }

    // --- Namespace (multi-tenancy) helpers ---

    /**
     * Extracts the namespace (tenant) a request belongs to from the configured
     * {@code matchNamespaceHeader} header. Only HTTP requests carry a namespace
     * header; non-HTTP request definitions (binary, DNS) always resolve to the
     * global namespace (null).
     *
     * @return the request namespace, or null when no namespace header is present
     * (which scopes matching to global expectations only)
     */
    String extractRequestNamespace(RequestDefinition requestDefinition) {
        if (!(requestDefinition instanceof HttpRequest)) {
            return null;
        }
        String headerName = configuration.matchNamespaceHeader();
        if (isBlank(headerName)) {
            return null;
        }
        String value = ((HttpRequest) requestDefinition).getFirstHeader(headerName);
        return isBlank(value) ? null : value;
    }

    /**
     * Namespace isolation rule: an expectation matches a request's namespace when
     * the expectation is global (null namespace) OR its namespace equals the
     * request's namespace. A request with no namespace ({@code requestNamespace ==
     * null}) therefore matches only global expectations — true tenant isolation.
     */
    private static boolean matchesNamespace(Expectation expectation, String requestNamespace) {
        if (expectation == null) {
            return true;
        }
        String expectationNamespace = expectation.getNamespace();
        if (isBlank(expectationNamespace)) {
            return true;
        }
        return expectationNamespace.equals(requestNamespace);
    }

    // --- Clustered shared-Times CAS helpers ---

    /**
     * Returns {@code true} when the expectation has LIMITED Times AND a
     * clustered backend is active AND cluster-wide shared-Times enforcement
     * is enabled (the default). Only in this case does the match consume
     * path go through the shared backend CAS.
     * <p>
     * Unlimited Times, no-backend (in-memory default), or non-clustered
     * backends all return {@code false}, keeping the fast path identical
     * to the pre-clustering single-node behaviour.
     * <p>
     * <b>Event-loop blocking trade-off.</b> When this returns {@code true},
     * {@link #consumeTimesViaBackendCas(Expectation)} runs on the Netty
     * request-worker thread and performs up to {@code MAX_CAS_RETRIES}
     * synchronous backend writes (a clustered Infinispan {@code REPL_SYNC}
     * {@code compareAndSet} is a network round-trip that waits for
     * replication acks from all members). The backend {@code get()} reads
     * from the node-local replica (no network), but each retried CAS
     * <i>write</i> blocks the worker until replication completes. The
     * worst-case bound is {@code MAX_CAS_RETRIES} (10) replicated writes
     * under extreme cross-node contention on the SAME expectation; in
     * practice limited-Times expectations are low-count and contention is
     * rare, so the common case is a single CAS write. Latency-sensitive
     * clustered deployments that can tolerate approximate (per-node) Times
     * may disable this via
     * {@code Configuration.clusterSharedTimesEnabled(false)} /
     * {@code -Dmockserver.clusterSharedTimesEnabled=false}, which restores
     * the node-local fast path with no backend round-trip on the worker.
     * See docs/code/clustered-state.md ("Clustered Times Counters").
     */
    private boolean isClusteredLimitedTimes(Expectation expectation) {
        if (stateBackend == null || !stateBackend.isClustered()) {
            return false;
        }
        if (!configuration.clusterSharedTimesEnabled()) {
            // Opt-out: fall back to node-local Times enforcement (no
            // synchronous backend CAS on the request worker thread).
            return false;
        }
        return expectation.getTimes() != null && !expectation.getTimes().isUnlimited();
    }

    /**
     * Maximum number of CAS retry attempts for clustered shared-Times
     * consumption. Bounds the worst-case number of synchronous replicated
     * writes performed on the request-worker thread per match (see
     * {@link #consumeTimesViaBackendCas(Expectation)}).
     */
    private static final int MAX_CAS_RETRIES = 10;

    /**
     * Result of a shared-Times CAS attempt on the backend.
     */
    static final class ConsumeTimesResult {
        /** CAS succeeded: this node may serve the response. */
        final boolean success;
        /** The shared counter has reached zero: the expectation is exhausted fleet-wide. */
        final boolean exhausted;

        ConsumeTimesResult(boolean success, boolean exhausted) {
            this.success = success;
            this.exhausted = exhausted;
        }
    }

    /**
     * Atomically decrements the shared remaining-times counter in the
     * backend via compare-and-set (CAS). This is the correctness-critical
     * distributed path that ensures a {@code Times.exactly(N)} expectation
     * is served exactly N times across the whole fleet.
     * <p>
     * <b>Algorithm:</b>
     * <ol>
     *   <li>Read the current {@link ExpectationEntry} and its version
     *       from the backend.</li>
     *   <li>If the entry is absent or its {@code remainingTimes} is
     *       already {@code <= 0}, return failure + exhausted.</li>
     *   <li>Build a new entry with {@code remainingTimes - 1} and attempt
     *       {@link KeyValueStore#compareAndSet} with the read version.</li>
     *   <li>If CAS fails (concurrent write from another node), retry
     *       from step 1 (bounded to {@code MAX_CAS_RETRIES}).</li>
     *   <li>If CAS succeeds, return success.</li>
     * </ol>
     * <p>
     * <b>Bounded retries:</b> if the CAS loop exhausts all retries
     * without succeeding (extreme contention), the method returns failure.
     * This is a conservative choice: the expectation is not served rather
     * than risking a double-serve. In practice, CAS contention is rare
     * because limited-Times expectations are low-count by nature.
     * <p>
     * <b>Runs on the request-worker (event-loop) thread.</b> Each
     * {@code compareAndSet} on a clustered {@code REPL_SYNC} backend is a
     * synchronous replicated write (a network round-trip awaiting acks from
     * all cluster members). The {@code get()} reads from the local replica
     * and does not hit the network. The worst-case blocking on the worker
     * is therefore {@code MAX_CAS_RETRIES} (10)
     * replicated writes; the common (uncontended) case is a single write.
     * This path is gated by {@link #isClusteredLimitedTimes(Expectation)},
     * which can be disabled via {@code clusterSharedTimesEnabled=false} for
     * latency-sensitive deployments (see that method's javadoc).
     *
     * @param expectation the expectation whose shared Times to consume
     * @return the CAS result indicating success or failure/exhaustion
     */
    ConsumeTimesResult consumeTimesViaBackendCas(Expectation expectation) {
        final String id = expectation.getId();

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            Optional<Versioned<ExpectationEntry>> current = expectationBackend.get(id);
            if (!current.isPresent()) {
                // Entry gone (removed by another node or evicted)
                return new ConsumeTimesResult(false, true);
            }
            Versioned<ExpectationEntry> versioned = current.get();
            ExpectationEntry entry = versioned.getValue();
            long version = versioned.getVersion();

            if (entry.getRemainingTimes() <= 0) {
                // Already exhausted across the fleet
                return new ConsumeTimesResult(false, true);
            }

            // Build a decremented copy
            int newRemaining = entry.getRemainingTimes() - 1;
            ExpectationEntry decremented = new ExpectationEntry(entry, newRemaining);

            if (expectationBackend.compareAndSet(id, version, decremented)) {
                // CAS succeeded — this node wins this match slot
                return new ConsumeTimesResult(true, false);
            }
            // CAS failed — another node wrote concurrently; retry with fresh read
        }

        // Exhausted retries without success — conservative failure
        return new ConsumeTimesResult(false, false);
    }
}
