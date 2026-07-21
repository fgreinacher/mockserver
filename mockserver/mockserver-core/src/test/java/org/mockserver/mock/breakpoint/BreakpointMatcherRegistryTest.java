package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests the matcher-based breakpoint registry: registration, per-phase matching,
 * empty-registry fast path, remove, clear, entries, and size.
 */
public class BreakpointMatcherRegistryTest {

    private final Configuration configuration = Configuration.configuration();
    private final MockServerLogger logger = new MockServerLogger();

    @Before
    public void setup() {
        resetAllBreakpointSingletons();
    }

    @After
    public void cleanup() {
        resetAllBreakpointSingletons();
    }

    private void resetAllBreakpointSingletons() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

    // --- registration and findMatch ---

    @Test
    public void shouldRegisterAndFindMatchForRequestPhase() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id = registry.register(
            request().withPath("/api/test"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );
        assertThat(id, is(notNullValue()));
        assertThat(registry.size(), is(1));

        // matching request and phase
        BreakpointMatcher match = registry.findMatch(
            request().withMethod("GET").withPath("/api/test"),
            BreakpointPhase.REQUEST
        );
        assertThat(match, is(notNullValue()));
        assertThat(match.getId(), is(id));

        // correct phase, non-matching request
        BreakpointMatcher noMatch = registry.findMatch(
            request().withPath("/other"),
            BreakpointPhase.REQUEST
        );
        assertThat(noMatch, is(nullValue()));
    }

    @Test
    public void shouldNotMatchWrongPhase() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        registry.register(
            request().withPath("/api/test"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        // matching request but wrong phase
        BreakpointMatcher match = registry.findMatch(
            request().withPath("/api/test"),
            BreakpointPhase.RESPONSE
        );
        assertThat("should not match wrong phase", match, is(nullValue()));
    }

    @Test
    public void shouldMatchMultiplePhases() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id = registry.register(
            request().withPath("/api/multi"),
            EnumSet.of(BreakpointPhase.REQUEST, BreakpointPhase.RESPONSE),
            configuration, logger
        );

        // should match both phases
        BreakpointMatcher reqMatch = registry.findMatch(
            request().withPath("/api/multi"),
            BreakpointPhase.REQUEST
        );
        assertThat(reqMatch, is(notNullValue()));
        assertThat(reqMatch.getId(), is(id));

        BreakpointMatcher respMatch = registry.findMatch(
            request().withPath("/api/multi"),
            BreakpointPhase.RESPONSE
        );
        assertThat(respMatch, is(notNullValue()));
        assertThat(respMatch.getId(), is(id));

        // should NOT match phases not in the set
        assertThat(registry.findMatch(request().withPath("/api/multi"),
            BreakpointPhase.RESPONSE_STREAM), is(nullValue()));
        assertThat(registry.findMatch(request().withPath("/api/multi"),
            BreakpointPhase.INBOUND_STREAM), is(nullValue()));
    }

    @Test
    public void shouldMatchStreamPhases() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id = registry.register(
            request().withPath("/api/stream"),
            EnumSet.of(BreakpointPhase.RESPONSE_STREAM, BreakpointPhase.INBOUND_STREAM),
            configuration, logger
        );

        assertThat(registry.findMatch(request().withPath("/api/stream"),
            BreakpointPhase.RESPONSE_STREAM), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/api/stream"),
            BreakpointPhase.INBOUND_STREAM), is(notNullValue()));

        // should NOT match non-stream phases
        assertThat(registry.findMatch(request().withPath("/api/stream"),
            BreakpointPhase.REQUEST), is(nullValue()));
    }

    // --- empty-registry fast path ---

    @Test
    public void shouldReturnNullImmediatelyWhenRegistryIsEmpty() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        assertThat(registry.size(), is(0));

        BreakpointMatcher match = registry.findMatch(
            request().withPath("/anything"),
            BreakpointPhase.REQUEST
        );
        assertThat("empty registry should return null immediately", match, is(nullValue()));
    }

    // --- first-match semantics ---

    @Test
    public void shouldReturnFirstMatchInRegistrationOrder() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id1 = registry.register(
            request().withPath("/api/.*"),  // catch-all path pattern
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        String id2 = registry.register(
            request().withPath("/api/specific"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        // both match /api/specific, but the first registered should win
        BreakpointMatcher match = registry.findMatch(
            request().withPath("/api/specific"),
            BreakpointPhase.REQUEST
        );
        assertThat(match, is(notNullValue()));
        assertThat(match.getId(), is(id1));
    }

    // --- remove ---

    @Test
    public void shouldRemoveById() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id1 = registry.register(request().withPath("/a"),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);
        String id2 = registry.register(request().withPath("/b"),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);

        assertThat(registry.size(), is(2));

        boolean removed = registry.remove(id1);
        assertThat(removed, is(true));
        assertThat(registry.size(), is(1));

        // should no longer match removed entry
        assertThat(registry.findMatch(request().withPath("/a"), BreakpointPhase.REQUEST), is(nullValue()));
        // remaining entry should still match
        assertThat(registry.findMatch(request().withPath("/b"), BreakpointPhase.REQUEST), is(notNullValue()));
    }

    @Test
    public void shouldReturnFalseWhenRemovingNonExistentId() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        boolean removed = registry.remove("non-existent-id");
        assertThat(removed, is(false));
    }

    // --- clear ---

    @Test
    public void shouldClearAllEntries() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        registry.register(request().withPath("/a"),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);
        registry.register(request().withPath("/b"),
            EnumSet.of(BreakpointPhase.RESPONSE), configuration, logger);
        registry.register(request().withPath("/c"),
            EnumSet.of(BreakpointPhase.RESPONSE_STREAM), configuration, logger);

        assertThat(registry.size(), is(3));

        registry.clear();
        assertThat(registry.size(), is(0));
        assertThat(registry.entries(), is(empty()));

        // nothing should match
        assertThat(registry.findMatch(request().withPath("/a"), BreakpointPhase.REQUEST), is(nullValue()));
    }

    // --- entries ---

    @Test
    public void shouldReturnSnapshotOfEntries() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id1 = registry.register(request().withPath("/x"),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);
        String id2 = registry.register(request().withPath("/y"),
            EnumSet.of(BreakpointPhase.RESPONSE, BreakpointPhase.INBOUND_STREAM), configuration, logger);

        List<BreakpointMatcher> entries = registry.entries();
        assertThat(entries, hasSize(2));
        assertThat(entries.get(0).getId(), is(id1));
        assertThat(entries.get(1).getId(), is(id2));

        // verify phases are preserved
        assertThat(entries.get(0).getPhases(), is(EnumSet.of(BreakpointPhase.REQUEST)));
        assertThat(entries.get(1).getPhases(),
            is(EnumSet.of(BreakpointPhase.RESPONSE, BreakpointPhase.INBOUND_STREAM)));
    }

    // --- size ---

    @Test
    public void shouldReportCorrectSize() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        assertThat(registry.size(), is(0));

        String id = registry.register(request(),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);
        assertThat(registry.size(), is(1));

        registry.remove(id);
        assertThat(registry.size(), is(0));
    }

    // --- catch-all matcher ---

    @Test
    public void shouldMatchCatchAllMatcher() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // register a matcher with no path/method constraints (matches everything)
        String id = registry.register(
            request(),  // matches all requests
            EnumSet.of(BreakpointPhase.REQUEST, BreakpointPhase.RESPONSE,
                BreakpointPhase.RESPONSE_STREAM, BreakpointPhase.INBOUND_STREAM),
            configuration, logger
        );

        for (BreakpointPhase phase : BreakpointPhase.values()) {
            BreakpointMatcher match = registry.findMatch(
                request().withMethod("POST").withPath("/any/path"),
                phase
            );
            assertThat("catch-all should match phase " + phase, match, is(notNullValue()));
            assertThat(match.getId(), is(id));
        }
    }

    // --- method matcher ---

    @Test
    public void shouldMatchByMethod() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        registry.register(
            request().withMethod("POST"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        assertThat(registry.findMatch(request().withMethod("POST").withPath("/test"),
            BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withMethod("GET").withPath("/test"),
            BreakpointPhase.REQUEST), is(nullValue()));
    }

    // --- conditional (Nth-hit / skip-count) breakpoints ---

    @Test
    public void shouldNotPauseWithinSkipWindowAndPauseAfter() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // skipCount = 2 => do NOT pause on hits 1 and 2, DO pause from hit 3
        registry.register(
            request().withPath("/api/skip"),
            EnumSet.of(BreakpointPhase.REQUEST),
            "client-1", 2,
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/skip");

        // hit 1 — matches but skipped
        assertThat("hit 1 should not pause",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(nullValue()));
        // hit 2 — matches but skipped
        assertThat("hit 2 should not pause",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(nullValue()));
        // hit 3 — pauses
        assertThat("hit 3 should pause",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(notNullValue()));
        // hit 4 — still pauses
        assertThat("hit 4 should pause",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(notNullValue()));
    }

    @Test
    public void shouldPauseEveryTimeWhenSkipCountAbsent() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // no skipCount => legacy behaviour: pause on every hit
        registry.register(
            request().withPath("/api/always"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/always");
        for (int i = 1; i <= 5; i++) {
            assertThat("hit " + i + " should pause",
                registry.findMatch(matching, BreakpointPhase.REQUEST), is(notNullValue()));
        }
    }

    @Test
    public void shouldTreatNullAndZeroAndNegativeSkipCountAsPauseEveryTime() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // skipCount = 0 and a negative value both mean "pause every time"
        registry.register(request().withPath("/zero"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", 0, configuration, logger);
        registry.register(request().withPath("/neg"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", -3, configuration, logger);

        assertThat(registry.findMatch(request().withPath("/zero"), BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/zero"), BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/neg"), BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/neg"), BreakpointPhase.REQUEST), is(notNullValue()));

        // skipCount normalised to null on the entry
        for (BreakpointMatcher entry : registry.entries()) {
            assertThat(entry.getSkipCount(), is(nullValue()));
        }
    }

    // --- one-shot / bounded (maxHits) breakpoints ---

    @Test
    public void shouldAutoDeregisterOneShotBreakpointAfterFirstPause() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // maxHits = 1 => pause once, then auto-deregister so the 2nd request is NOT intercepted
        String id = registry.register(
            request().withPath("/api/oneshot"),
            EnumSet.of(BreakpointPhase.REQUEST),
            "client-1", null, null, null, null, 1,
            configuration, logger
        );
        assertThat(registry.size(), is(1));

        HttpRequest matching = request().withPath("/api/oneshot");

        // hit 1 — pauses
        BreakpointMatcher firstHit = registry.findMatch(matching, BreakpointPhase.REQUEST);
        assertThat("hit 1 should pause", firstHit, is(notNullValue()));
        assertThat("hit 1 id", firstHit.getId(), is(id));

        // breakpoint auto-deregistered after the one-shot pause
        assertThat("breakpoint removed after maxHits reached", registry.size(), is(0));

        // hit 2 — NOT intercepted (breakpoint gone from the registry)
        assertThat("hit 2 should not pause (breakpoint deregistered)",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(nullValue()));
        assertThat("registry still empty", registry.size(), is(0));
    }

    @Test
    public void shouldAutoDeregisterBoundedBreakpointAfterMaxHitsPauses() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // maxHits = 3 => pause on hits 1..3, then auto-deregister; the 4th request is NOT intercepted
        registry.register(
            request().withPath("/api/bounded"),
            EnumSet.of(BreakpointPhase.REQUEST),
            "client-1", null, null, null, null, 3,
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/bounded");
        for (int i = 1; i <= 3; i++) {
            assertThat("hit " + i + " should pause",
                registry.findMatch(matching, BreakpointPhase.REQUEST), is(notNullValue()));
        }
        assertThat("breakpoint removed after 3 pauses", registry.size(), is(0));
        assertThat("hit 4 should not pause (breakpoint deregistered)",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(nullValue()));
    }

    @Test
    public void shouldCountOnlyRealPausesAgainstMaxHitsWithSkipCount() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // skipCount = 2, maxHits = 1 => skip hits 1,2 (no pause, no budget spent),
        // pause once on hit 3, then auto-deregister; hit 4 is NOT intercepted
        registry.register(
            request().withPath("/api/skipmax"),
            EnumSet.of(BreakpointPhase.REQUEST),
            "client-1", 2, null, null, null, 1,
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/skipmax");
        assertThat("hit 1 skipped", registry.findMatch(matching, BreakpointPhase.REQUEST), is(nullValue()));
        assertThat("hit 2 skipped", registry.findMatch(matching, BreakpointPhase.REQUEST), is(nullValue()));
        assertThat("registry retains skipped breakpoint", registry.size(), is(1));
        assertThat("hit 3 pauses", registry.findMatch(matching, BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat("breakpoint removed after its single pause", registry.size(), is(0));
        assertThat("hit 4 not intercepted", registry.findMatch(matching, BreakpointPhase.REQUEST), is(nullValue()));
    }

    @Test
    public void shouldNeverAutoDeregisterWhenMaxHitsAbsent() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        registry.register(
            request().withPath("/api/unbounded"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/unbounded");
        for (int i = 1; i <= 5; i++) {
            assertThat("hit " + i + " should pause",
                registry.findMatch(matching, BreakpointPhase.REQUEST), is(notNullValue()));
        }
        assertThat("breakpoint never auto-deregisters", registry.size(), is(1));
        for (BreakpointMatcher entry : registry.entries()) {
            assertThat(entry.getMaxHits(), is(nullValue()));
            assertThat(entry.isExhausted(), is(false));
        }
    }

    @Test
    public void shouldAutoDeregisterOneShotBreakpointViaFindResponseMatch() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // maxHits = 1 on a RESPONSE-phase breakpoint with no response condition:
        // pauses once via findResponseMatch, then auto-deregisters
        registry.register(
            request().withPath("/api/resp"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            "client-1", null, null, null, null, 1,
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/resp");
        HttpResponse anyResponse = response().withStatusCode(200);

        assertThat("first response hit should pause",
            registry.findResponseMatch(matching, anyResponse, BreakpointPhase.RESPONSE), is(notNullValue()));
        assertThat("breakpoint removed after its single pause", registry.size(), is(0));
        assertThat("second response hit not intercepted",
            registry.findResponseMatch(matching, anyResponse, BreakpointPhase.RESPONSE), is(nullValue()));
    }

    @Test
    public void shouldNotConsumeMaxHitsWhenResponseConditionFailsInFindResponseMatch() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // maxHits = 1 gated on a 500-599 response: a 200 must NOT consume the budget or
        // remove the breakpoint (the condition falls through), a 500 pauses once then removes it
        registry.register(
            request().withPath("/api/cond"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            "client-1", null, 500, 599, null, 1,
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/cond");

        // response 200: condition fails => no pause, budget untouched, breakpoint retained
        assertThat("200 does not pause",
            registry.findResponseMatch(matching, response().withStatusCode(200), BreakpointPhase.RESPONSE), is(nullValue()));
        assertThat("breakpoint retained after non-matching response", registry.size(), is(1));

        // response 500: condition matches => pause once, then auto-deregister
        assertThat("500 pauses",
            registry.findResponseMatch(matching, response().withStatusCode(500), BreakpointPhase.RESPONSE), is(notNullValue()));
        assertThat("breakpoint removed after its single qualifying pause", registry.size(), is(0));
        assertThat("subsequent 500 not intercepted",
            registry.findResponseMatch(matching, response().withStatusCode(500), BreakpointPhase.RESPONSE), is(nullValue()));
    }

    @Test
    public void shouldTreatZeroAndNegativeMaxHitsAsUnbounded() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        registry.register(request().withPath("/zeromax"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", null, null, null, null, 0, configuration, logger);
        registry.register(request().withPath("/negmax"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", null, null, null, null, -3, configuration, logger);

        for (BreakpointMatcher entry : registry.entries()) {
            assertThat(entry.getMaxHits(), is(nullValue()));
        }
        // both still pause repeatedly (never auto-deregister)
        assertThat(registry.findMatch(request().withPath("/zeromax"), BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/zeromax"), BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.size(), is(2));
    }

    @Test
    public void shouldKeepSkipCounterPerBreakpoint() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // two independent breakpoints with their own counters
        registry.register(request().withPath("/a"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", 1, configuration, logger);
        registry.register(request().withPath("/b"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", 1, configuration, logger);

        HttpRequest a = request().withPath("/a");
        HttpRequest b = request().withPath("/b");

        // hit /a once (skipped) — should not advance /b's counter
        assertThat(registry.findMatch(a, BreakpointPhase.REQUEST), is(nullValue()));
        // /b's first hit is still skipped (its own counter is independent)
        assertThat(registry.findMatch(b, BreakpointPhase.REQUEST), is(nullValue()));
        // /a second hit pauses
        assertThat(registry.findMatch(a, BreakpointPhase.REQUEST), is(notNullValue()));
        // /b second hit pauses
        assertThat(registry.findMatch(b, BreakpointPhase.REQUEST), is(notNullValue()));
    }

    @Test
    public void shouldExposeSkipCountAndHitCountOnEntry() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id = registry.register(request().withPath("/hits"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", 2, configuration, logger);

        BreakpointMatcher entry = registry.entries().get(0);
        assertThat(entry.getId(), is(id));
        assertThat(entry.getSkipCount(), is(2));
        assertThat(entry.getHitCount(), is(0L));

        registry.findMatch(request().withPath("/hits"), BreakpointPhase.REQUEST);
        registry.findMatch(request().withPath("/hits"), BreakpointPhase.REQUEST);
        assertThat(entry.getHitCount(), is(2L));
    }

    @Test
    public void shouldIncrementSkipCounterAtomicallyUnderConcurrency() throws Exception {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        final int skip = 100;
        final int threads = 8;
        final int hitsPerThread = 50; // 400 total hits, skip 100 => 300 pauses
        registry.register(request().withPath("/conc"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", skip, configuration, logger);

        final java.util.concurrent.atomic.AtomicInteger pauses = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < hitsPerThread; i++) {
                    if (registry.findMatch(request().withPath("/conc"), BreakpointPhase.REQUEST) != null) {
                        pauses.incrementAndGet();
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        int totalHits = threads * hitsPerThread;
        // exactly `skip` hits are skipped, the rest pause — no lost/double counts
        assertThat(pauses.get(), is(totalHits - skip));
        assertThat(registry.entries().get(0).getHitCount(), is((long) totalHits));
    }

    // --- response-content conditional breakpoints ---

    @Test
    public void shouldPauseOnlyWhenResponseStatusCodeWithinRange() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // break only on server errors (status >= 500)
        String id = registry.register(
            request().withPath("/api/.*"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            "client-1", null,
            500, null, null,
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/widgets");

        // 200 — does NOT match the response condition => no pause
        assertThat("200 should not pause",
            registry.findResponseMatch(matching, response().withStatusCode(200), BreakpointPhase.RESPONSE), is(nullValue()));
        // 500 — matches => pause
        BreakpointMatcher m500 = registry.findResponseMatch(matching, response().withStatusCode(500), BreakpointPhase.RESPONSE);
        assertThat("500 should pause", m500, is(notNullValue()));
        assertThat(m500.getId(), is(id));
        // 503 — matches => pause
        assertThat("503 should pause",
            registry.findResponseMatch(matching, response().withStatusCode(503), BreakpointPhase.RESPONSE), is(notNullValue()));
    }

    @Test
    public void shouldPauseOnlyWhenResponseStatusCodeInInclusiveBoundedRange() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // break only on 4xx (400..499)
        registry.register(
            request(),
            EnumSet.of(BreakpointPhase.RESPONSE),
            "c", null,
            400, 499, null,
            configuration, logger
        );

        HttpRequest matching = request().withPath("/x");
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(399), BreakpointPhase.RESPONSE), is(nullValue()));
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(400), BreakpointPhase.RESPONSE), is(notNullValue()));
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(499), BreakpointPhase.RESPONSE), is(notNullValue()));
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(500), BreakpointPhase.RESPONSE), is(nullValue()));
    }

    @Test
    public void shouldPauseOnlyWhenResponseBodyMatchesRegex() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // break only when the response body contains a quota error
        registry.register(
            request(),
            EnumSet.of(BreakpointPhase.RESPONSE),
            "c", null,
            null, null, "quota.*exceeded",
            configuration, logger
        );

        HttpRequest matching = request().withPath("/x");
        assertThat("non-matching body should not pause",
            registry.findResponseMatch(matching, response().withBody("{\"ok\":true}"), BreakpointPhase.RESPONSE), is(nullValue()));
        assertThat("matching body should pause",
            registry.findResponseMatch(matching, response().withBody("error: quota was exceeded for tenant"), BreakpointPhase.RESPONSE), is(notNullValue()));
        // a null body should not pause when a body condition is set
        assertThat("null body should not pause",
            registry.findResponseMatch(matching, response().withStatusCode(200), BreakpointPhase.RESPONSE), is(nullValue()));
    }

    @Test
    public void shouldRequireAllResponseConditionsToMatch() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // break only on a 500 whose body mentions "timeout"
        registry.register(
            request(),
            EnumSet.of(BreakpointPhase.RESPONSE),
            "c", null,
            500, 599, "timeout",
            configuration, logger
        );

        HttpRequest matching = request().withPath("/x");
        // status matches but body does not
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(500).withBody("ok"), BreakpointPhase.RESPONSE), is(nullValue()));
        // body matches but status does not
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(200).withBody("timeout"), BreakpointPhase.RESPONSE), is(nullValue()));
        // both match
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(503).withBody("upstream timeout"), BreakpointPhase.RESPONSE), is(notNullValue()));
    }

    @Test
    public void shouldPauseRegardlessOfResponseWhenNoResponseCondition() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // no response condition => legacy behaviour: pause on any response
        registry.register(
            request().withPath("/legacy"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            configuration, logger
        );

        HttpRequest matching = request().withPath("/legacy");
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(200), BreakpointPhase.RESPONSE), is(notNullValue()));
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(500), BreakpointPhase.RESPONSE), is(notNullValue()));
        // findResponseMatch with a null response still pauses when no condition is set
        assertThat(registry.findResponseMatch(matching, null, BreakpointPhase.RESPONSE), is(notNullValue()));
    }

    @Test
    public void shouldFallThroughToLaterMatcherWhenResponseConditionFails() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // first matcher only wants 500s; second (catch-all) wants any response
        String idErrors = registry.register(
            request().withPath("/api/.*"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            "c", null,
            500, null, null,
            configuration, logger
        );
        String idAny = registry.register(
            request().withPath("/api/.*"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/widgets");
        // 200 — first matcher's condition fails, so the second (catch-all) owns it
        BreakpointMatcher m200 = registry.findResponseMatch(matching, response().withStatusCode(200), BreakpointPhase.RESPONSE);
        assertThat(m200, is(notNullValue()));
        assertThat(m200.getId(), is(idAny));
        // 500 — first matcher's condition matches, so it owns it
        BreakpointMatcher m500 = registry.findResponseMatch(matching, response().withStatusCode(500), BreakpointPhase.RESPONSE);
        assertThat(m500, is(notNullValue()));
        assertThat(m500.getId(), is(idErrors));
    }

    @Test
    public void shouldNotAdvanceSkipCounterWhenResponseConditionFails() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // response condition (500) + skipCount 1: only responses that satisfy the
        // condition count toward the skip window
        registry.register(
            request().withPath("/x"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            "c", 1,
            500, null, null,
            configuration, logger
        );

        HttpRequest matching = request().withPath("/x");
        // two non-matching responses — condition fails, counter must NOT advance
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(200), BreakpointPhase.RESPONSE), is(nullValue()));
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(200), BreakpointPhase.RESPONSE), is(nullValue()));
        assertThat(registry.entries().get(0).getHitCount(), is(0L));
        // first matching response — counted but within the skip window (skipCount 1) => no pause
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(500), BreakpointPhase.RESPONSE), is(nullValue()));
        // second matching response — pauses
        assertThat(registry.findResponseMatch(matching, response().withStatusCode(500), BreakpointPhase.RESPONSE), is(notNullValue()));
        assertThat(registry.entries().get(0).getHitCount(), is(2L));
    }

    @Test
    public void shouldExposeResponseConditionFieldsOnEntry() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        registry.register(
            request().withPath("/x"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            "c", null,
            500, 599, "boom",
            configuration, logger
        );

        BreakpointMatcher entry = registry.entries().get(0);
        assertThat(entry.getResponseStatusCodeMin(), is(500));
        assertThat(entry.getResponseStatusCodeMax(), is(599));
        assertThat(entry.getResponseBodyContains(), is("boom"));
        assertThat(entry.hasResponseCondition(), is(true));
    }

    @Test
    public void shouldRejectInvalidResponseBodyRegexAtConstruction() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();
        try {
            registry.register(
                request(),
                EnumSet.of(BreakpointPhase.RESPONSE),
                "c", null,
                null, null, "[unclosed",
                configuration, logger
            );
            org.junit.Assert.fail("expected IllegalArgumentException for invalid regex");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage(), containsString("responseBodyContains"));
        }
    }

    @Test
    public void plainFindMatchIgnoresResponseConditionsForRequestPhase() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // a matcher with a response condition still pauses at REQUEST phase via the
        // request-only findMatch (the response is not known at the request phase)
        String id = registry.register(
            request().withPath("/api/.*"),
            EnumSet.of(BreakpointPhase.REQUEST, BreakpointPhase.RESPONSE),
            "c", null,
            500, null, null,
            configuration, logger
        );

        BreakpointMatcher reqMatch = registry.findMatch(request().withPath("/api/x"), BreakpointPhase.REQUEST);
        assertThat(reqMatch, is(notNullValue()));
        assertThat(reqMatch.getId(), is(id));
    }
}
