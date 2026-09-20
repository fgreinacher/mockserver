package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.MatcherBuilder;
import org.mockserver.matchers.Times;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * T1-C1 correctness tests for the candidate index for expectation matching.
 *
 * <p>The defining guarantee is ZERO behavioural change: above the size threshold the
 * narrowed candidate scan must return the byte-for-byte identical expectation (same id,
 * same match order, same not-found behaviour) as the full linear scan. These tests force
 * the index to engage by injecting a low threshold via the parallel-safe test seam
 * {@link RequestMatchers#withCandidateIndexThreshold(int)} (no global/system state is
 * mutated, so the test stays in the parallel surefire phase).
 *
 * <p>The headline test is a differential one: for a large mixed expectation set (literal,
 * regex, notted, blank, case-variant, path-parameter, priority-ordered) it asserts that the
 * indexed matcher returns exactly the same expectation as an independent un-indexed (very
 * high threshold) matcher for a broad slice of requests — including the cases that prove
 * global-order evaluation (a higher-priority fallthrough beating a lower-priority bucketed
 * expectation) and fallthrough-only matches.
 */
public class RequestMatchersCandidateIndexTest {

    private RequestMatchers newMatchers(int threshold) {
        return newMatchers(threshold, configuration().maxExpectations());
    }

    private RequestMatchers newMatchers(int threshold, int maxExpectations) {
        return newMatchers(threshold, configuration().maxExpectations(maxExpectations));
    }

    private RequestMatchers newMatchers(int threshold, Configuration configuration) {
        return new RequestMatchers(
            configuration,
            new MockServerLogger(),
            mock(Scheduler.class),
            mock(WebSocketClientRegistry.class)
        ).withCandidateIndexThreshold(threshold);
    }

    private static Expectation literal(String method, String path, String body) {
        return new Expectation(request().withMethod(method).withPath(path)).thenRespond(response().withBody(body));
    }

    private static Expectation literal(String method, String path, String body, int priority) {
        return new Expectation(request().withMethod(method).withPath(path), Times.unlimited(),
            TimeToLive.unlimited(), priority).thenRespond(response().withBody(body));
    }

    // ---------- bucketed literal match ----------

    @Test
    public void bucketedLiteralMatchReturnsSameExpectationAboveThreshold() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/exact/path-" + i, "e" + i), API);
        }

        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/exact/path-37"));

        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("e37"));
    }

    @Test
    public void bucketMissReturnsNullAboveThreshold() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/exact/path-" + i, "e" + i), API);
        }

        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/no-such-path"));
        assertThat(matched, is(nullValue()));
    }

    // ---------- fallthrough-only match (regex / notted) ----------

    @Test
    public void requestMatchingOnlyARegexFallthroughStillMatches() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/exact/path-" + i, "e" + i), API);
        }
        matchers.add(new Expectation(request().withMethod("GET").withPath("/regex/[a-z]+"))
            .thenRespond(response().withBody("regexBody")), API);

        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/regex/abc"));

        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("regexBody"));
    }

    @Test
    public void requestMatchingOnlyANottedFallthroughStillMatches() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/exact/path-" + i, "e" + i), API);
        }
        // a notted path: matches any path that is NOT /forbidden
        matchers.add(new Expectation(request().withMethod("POST").withPath("!/forbidden"))
            .thenRespond(response().withBody("nottedBody")), API);

        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("POST").withPath("/anything"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("nottedBody"));
    }

    // ---------- global-order: priority across bucketed vs fallthrough ----------

    @Test
    public void higherPriorityFallthroughBeatsLowerPriorityBucketedExpectation() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/filler/path-" + i, "f" + i), API);
        }
        // low-priority LITERAL (bucketed)
        matchers.add(literal("GET", "/api/users", "bucketedLowPriority", 0), API);
        // HIGHER-priority REGEX (fallthrough) that also matches /api/users
        matchers.add(new Expectation(request().withMethod("GET").withPath("/api/.*"), Times.unlimited(),
            TimeToLive.unlimited(), 10).thenRespond(response().withBody("fallthroughHighPriority")), API);

        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/api/users"));

        // the higher-priority fallthrough must win (proves GLOBAL-order evaluation,
        // not bucket-first evaluation)
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("fallthroughHighPriority"));
    }

    @Test
    public void higherPriorityBucketedBeatsLowerPriorityFallthroughExpectation() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/filler/path-" + i, "f" + i), API);
        }
        // higher-priority literal (bucketed)
        matchers.add(literal("GET", "/api/users", "bucketedHighPriority", 10), API);
        // lower-priority regex (fallthrough) that also matches
        matchers.add(new Expectation(request().withMethod("GET").withPath("/api/.*"), Times.unlimited(),
            TimeToLive.unlimited(), 0).thenRespond(response().withBody("fallthroughLowPriority")), API);

        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/api/users"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("bucketedHighPriority"));
    }

    // ---------- case-insensitive matching is preserved (default matchExactCase=false) ----------

    @Test
    public void caseInsensitivePathStillMatchesViaIndex() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/exact/path-" + i, "e" + i), API);
        }
        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("get").withPath("/EXACT/PATH-3"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("e3"));
    }

    // ---------- update-in-place re-buckets (generation invalidation) ----------

    @Test
    public void updateInPlaceChangingPathReBucketsCorrectly() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/exact/path-" + i, "e" + i), API);
        }
        Expectation original = literal("GET", "/before", "v1");
        matchers.add(original, API);
        Expectation updated = new Expectation(
            request().withMethod("GET").withPath("/after"), Times.unlimited(), TimeToLive.unlimited(), 0);
        updated.withId(original.getId());
        updated.thenRespond(response().withBody("v2"));
        matchers.add(updated, API);

        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/before")), is(nullValue()));
        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/after"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("v2"));
    }

    // ---------- eviction / removal leaves no stale index entry ----------

    @Test
    public void removalLeavesNoStaleIndexEntry() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/exact/path-" + i, "e" + i), API);
        }
        Expectation removable = literal("GET", "/removable", "willGo");
        matchers.add(removable, API);
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/removable")), is(notNullValue()));

        // clear by id -> the index must no longer serve it
        matchers.clear(org.mockserver.model.ExpectationId.expectationId(removable.getId()), org.mockserver.uuid.UUIDService.getUUID());
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/removable")), is(nullValue()));
        // unrelated bucket still served
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/exact/path-1")).getHttpResponse().getBodyAsString(), is("e1"));
    }

    // ---------- mutation paths that had NO coverage (review of the G1 fix) ----------
    //
    // The incremental index is only correct if EVERY structural mutation reaches the
    // listener. Three paths were unexercised, and the live case-mode flip was where a
    // lost-update race actually hid — a path with no test could not have been caught by
    // running anything, which is precisely why it needed one.

    @Test
    public void overflowEvictionLeavesNoStaleIndexEntry() {
        // No state backend is wired here, so the node-local CircularPriorityQueue carries the
        // bound and evicts on add -- which is the eviction path this test needs to exercise.
        RequestMatchers matchers = newMatchers(4, 10);
        // Overfill: the oldest are evicted by the queue, and the index must drop them too.
        for (int i = 0; i < 30; i++) {
            matchers.add(literal("GET", "/overflow/" + i, "e" + i), API);
        }
        // The earliest entries are gone from the store, so the index must not serve them.
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/overflow/0")), is(nullValue()));
        // The most recent survive and are served through the index.
        assertThat(
            matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/overflow/29")).getHttpResponse().getBodyAsString(),
            is("e29"));
    }

    @Test
    public void resetClearsEveryIndexEntry() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 40; i++) {
            matchers.add(literal("GET", "/reset/" + i, "e" + i), API);
        }
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/reset/7")), is(notNullValue()));

        matchers.reset(API);

        // Every bucket must be empty — a stale index would still serve one of these.
        for (int i = 0; i < 40; i += 7) {
            assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/reset/" + i)), is(nullValue()));
        }
    }

    @Test
    public void caseModeFlipDoesNotLoseAConcurrentAdd() throws Exception {
        // THE REGRESSION TEST for the lost-update race: the case-mode rebuild used to read
        // the authoritative list OUTSIDE its own monitor, so an onAdded landing in that
        // window was placed into the state being replaced and vanished permanently — the
        // incremental design has no generation re-check to self-heal it.
        //
        // Driven DIRECTLY against CandidateIndex (package-private, same package) so the
        // authoritative-snapshot Supplier is under test control. That turns the real
        // thread race — which caught the degraded code only ~2 runs in 3 — into a
        // controlled interleaving that is deterministic in BOTH directions: the writer's
        // onAdded and rebuild's authoritative read are ordered by the SHARED index monitor,
        // and whether the read happens under that monitor is exactly the invariant on trial.
        MatcherBuilder matcherBuilder = new MatcherBuilder(configuration(), new MockServerLogger());

        // Maintain the index case-INsensitive; the read below flips to case-sensitive so
        // the case-mode rebuild branch is taken.
        CandidateIndex index = new CandidateIndex(true);
        List<HttpRequestMatcher> authoritative = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 8; i++) {
            HttpRequestMatcher seed = matcherBuilder.transformsToMatcher(literal("GET", "/seed/" + i, "s" + i));
            index.onAdded(seed);
            authoritative.add(seed);
        }
        HttpRequestMatcher raced = matcherBuilder.transformsToMatcher(literal("GET", "/raced", "raced"));

        CountDownLatch writerMayAdd = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                writerMayAdd.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            // The concurrent control-plane add that must survive the rebuild. onAdded is
            // synchronized on the index monitor, so under the fix it BLOCKS here until
            // rebuild (holding that monitor while it reads the snapshot) has published.
            authoritative.add(raced);
            index.onAdded(raced);
            writerDone.countDown();
        });
        writer.start();

        Supplier<List<HttpRequestMatcher>> authoritativeSnapshot = () -> {
            // Snapshot BEFORE releasing the writer, so the returned list is stale by
            // construction (never contains raced). The correctness of the rebuild rests
            // entirely on WHEN this runs relative to the monitor.
            List<HttpRequestMatcher> stale = new ArrayList<>(authoritative);
            writerMayAdd.countDown();
            try {
                // SHORT, bounded wait — this is the PASSING path, so it must not stall the
                // suite. Fixed code: this supplier runs INSIDE the monitor, the writer's
                // onAdded is blocked on it, writerDone never fires, so this expires and we
                // return the stale snapshot; the blocked add then applies to the freshly
                // published state -> raced PRESENT. Degraded code: this ran OUTSIDE the
                // monitor, the writer's onAdded already completed into the state about to be
                // replaced, writerDone fires at once -> rebuild overwrites it -> raced LOST.
                writerDone.await(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return stale;
        };

        // Flip the case mode (index is case-insensitive) so this read takes the rebuild
        // branch, passing the Supplier itself so the authoritative read is what races.
        index.candidatesInGlobalOrder(request().withMethod("GET").withPath("/seed/1"), false, authoritativeSnapshot);

        // Let the (possibly monitor-blocked) writer finish applying its add before asserting.
        writer.join(5000);

        // The raced add must still be servable from the rebuilt index. Same case mode now
        // (false), so this read performs no further rebuild and the supplier is unused.
        List<HttpRequestMatcher> candidates = index.candidatesInGlobalOrder(
            request().withMethod("GET").withPath("/raced"), false, () -> new ArrayList<>(authoritative));
        assertThat(
            "an onAdded racing a case-mode rebuild was lost from the index",
            candidates.contains(raced),
            is(true));
    }

    // ---------- below-threshold path is the untouched scan ----------

    @Test
    public void belowThresholdUsesUntouchedScan() {
        RequestMatchers matchers = newMatchers(1_000_000);
        matchers.add(literal("GET", "/a", "a"), API);
        matchers.add(new Expectation(request().withMethod("GET").withPath("/regex/[0-9]+"))
            .thenRespond(response().withBody("r")), API);
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/a")).getHttpResponse().getBodyAsString(), is("a"));
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/regex/123")).getHttpResponse().getBodyAsString(), is("r"));
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/nope")), is(nullValue()));
    }

    // ---------- DIFFERENTIAL PARITY: indexed vs un-indexed return identical results ----------

    @Test
    public void indexedAndUnindexedReturnIdenticalResultsForBroadRequestSlice() {
        // two matchers with identical expectation sets; one engages the index (low
        // threshold), the other never does (very high threshold). They must agree on
        // EVERY request (same matched body, or both null).
        RequestMatchers indexed = newMatchers(8);
        RequestMatchers scan = newMatchers(1_000_000);

        List<Expectation> expectations = buildMixedExpectationSet();
        for (Expectation expectation : expectations) {
            indexed.add(cloneExpectation(expectation), API);
            scan.add(cloneExpectation(expectation), API);
        }

        for (HttpRequest probe : buildProbeRequests()) {
            Expectation indexedResult = indexed.firstMatchingExpectation(probe.clone());
            Expectation scanResult = scan.firstMatchingExpectation(probe.clone());
            String indexedBody = indexedResult == null ? null : indexedResult.getHttpResponse().getBodyAsString();
            String scanBody = scanResult == null ? null : scanResult.getHttpResponse().getBodyAsString();
            assertThat("mismatch for request " + probe.getMethod().getValue() + " " + probe.getPath().getValue()
                + " (indexed=" + indexedBody + ", scan=" + scanBody + ")", indexedBody, is(scanBody));
        }
    }

    private static Expectation cloneExpectation(Expectation source) {
        HttpRequest req = (HttpRequest) source.getHttpRequest();
        Expectation copy = new Expectation(req.clone(), Times.unlimited(), TimeToLive.unlimited(), source.getPriority());
        copy.thenRespond(response().withBody(source.getHttpResponse().getBodyAsString()));
        return copy;
    }

    private static List<Expectation> buildMixedExpectationSet() {
        List<Expectation> list = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            list.add(literal("GET", "/api/resource-" + i, "get" + i));
        }
        for (int i = 0; i < 20; i++) {
            list.add(literal("POST", "/api/resource-" + i, "post" + i));
        }
        // regex (fallthrough)
        list.add(new Expectation(request().withMethod("GET").withPath("/api/resource-[0-9]+/sub"))
            .thenRespond(response().withBody("regexSub")));
        // notted-method (fallthrough)
        list.add(new Expectation(request().withMethod("!DELETE").withPath("/guarded"))
            .thenRespond(response().withBody("notDelete")));
        // blank-method (matches any method for that path) (fallthrough)
        list.add(new Expectation(request().withPath("/anymethod"))
            .thenRespond(response().withBody("anyMethod")));
        // high-priority regex overlapping a literal bucket (global-order proof)
        list.add(new Expectation(request().withMethod("GET").withPath("/api/resource-5"),
            Times.unlimited(), TimeToLive.unlimited(), 100)
            .thenRespond(response().withBody("highPriorityOverlap")));
        // path-parameter (fallthrough, normalised path is a regex)
        list.add(new Expectation(request().withMethod("GET").withPath("/users/{id}")
            .withPathParameter("id", "[0-9]+"))
            .thenRespond(response().withBody("pathParam")));
        // pure-ASCII literals (bucketed) used as case-fold targets for the non-ASCII probes:
        // a request can equalsIgnoreCase-match these yet fold (toLowerCase) to a different bucket key.
        list.add(literal("GET", "/api", "apiLiteral"));
        list.add(literal("GET", "/sample", "sampleLiteral"));
        return list;
    }

    private static List<HttpRequest> buildProbeRequests() {
        List<HttpRequest> probes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            probes.add(request().withMethod("GET").withPath("/api/resource-" + i));
        }
        for (int i = 0; i < 20; i++) {
            probes.add(request().withMethod("POST").withPath("/api/resource-" + i));
        }
        // case variations
        probes.add(request().withMethod("get").withPath("/API/RESOURCE-9"));
        // regex fallthrough hits and misses
        probes.add(request().withMethod("GET").withPath("/api/resource-3/sub"));
        probes.add(request().withMethod("GET").withPath("/api/resource-x/sub"));
        // notted-method
        probes.add(request().withMethod("GET").withPath("/guarded"));
        probes.add(request().withMethod("DELETE").withPath("/guarded"));
        // blank-method (any method)
        probes.add(request().withMethod("PUT").withPath("/anymethod"));
        probes.add(request().withMethod("PATCH").withPath("/anymethod"));
        // path-parameter
        probes.add(request().withMethod("GET").withPath("/users/42"));
        probes.add(request().withMethod("GET").withPath("/users/abc"));
        // pure misses
        probes.add(request().withMethod("GET").withPath("/totally/unknown"));
        probes.add(request().withMethod("OPTIONS").withPath("/api/resource-1"));
        // BLANK-METHOD / BLANK-PATH requests (review COR-05): a blank request method/path
        // passes the method/path gate of EVERY expectation, so it can match a bucketed
        // literal in any bucket — the index must bypass to the full scan and stay identical.
        probes.add(request().withPath("/api/resource-7"));                 // blank method, literal path
        probes.add(request().withMethod("GET"));                           // blank path, literal method
        probes.add(request());                                            // both blank
        probes.add(request().withPath("/anymethod"));                      // blank method against blank-method expectation
        // NON-ASCII case-insensitive probes (review CRITICAL silent-miss): these
        // equalsIgnoreCase-match the bucketed pure-ASCII literals /api and /sample, but
        // toLowerCase(ROOT) folds them to a DIFFERENT bucket key (U+0130 even changes length),
        // so a naive index would silently miss. The index must fall back to the full scan.
        probes.add(request().withMethod("GET").withPath("/Apİ"));    // U+0130 eqIgnoreCase "/api"
        probes.add(request().withMethod("GET").withPath("/ſample")); // U+017F eqIgnoreCase "/sample"
        // ASCII case variants of the same literals — the common case must STILL match via the index
        probes.add(request().withMethod("get").withPath("/API"));
        probes.add(request().withMethod("GET").withPath("/SAMPLE"));
        return probes;
    }

    // ---------- DIFFERENTIAL PARITY with detailedMatchFailures=ON (closest-match) ----------

    @Test
    public void indexedAndUnindexedAgreeWithDetailedMatchFailuresOn() {
        // detailedMatchFailures changes how closest-match is accumulated; the indexed and
        // un-indexed paths must STILL agree on the matched expectation for every probe.
        RequestMatchers indexed = new RequestMatchers(
            configuration().detailedMatchFailures(true), new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class)).withCandidateIndexThreshold(8);
        RequestMatchers scan = new RequestMatchers(
            configuration().detailedMatchFailures(true), new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class)).withCandidateIndexThreshold(1_000_000);

        for (Expectation expectation : buildMixedExpectationSet()) {
            indexed.add(cloneExpectation(expectation), API);
            scan.add(cloneExpectation(expectation), API);
        }
        for (HttpRequest probe : buildProbeRequests()) {
            Expectation indexedResult = indexed.firstMatchingExpectation(probe.clone());
            Expectation scanResult = scan.firstMatchingExpectation(probe.clone());
            String indexedBody = indexedResult == null ? null : indexedResult.getHttpResponse().getBodyAsString();
            String scanBody = scanResult == null ? null : scanResult.getHttpResponse().getBodyAsString();
            assertThat("mismatch (detailed) for " + probe.getMethod().getValue() + " " + probe.getPath().getValue(),
                indexedBody, is(scanBody));
        }
    }

    // ---------- DIFFERENTIAL PARITY across namespaces (matched result + gate) ----------

    @Test
    public void indexedAndUnindexedAgreeAcrossNamespaces() {
        // default matchNamespaceHeader = X-MockServer-Namespace. Build literal expectations
        // in two namespaces plus global ones; the indexed and un-indexed matchers must agree
        // on the matched expectation for namespaced AND non-namespaced requests (the namespace
        // gate is applied identically in candidate mode).
        RequestMatchers indexed = newMatchers(8);
        RequestMatchers scan = newMatchers(1_000_000);
        for (RequestMatchers matchers : new RequestMatchers[]{indexed, scan}) {
            for (int i = 0; i < 40; i++) {
                matchers.add(new Expectation(request().withMethod("GET").withPath("/ns/path-" + i))
                    .withNamespace("team-a").thenRespond(response().withBody("a" + i)), API);
                matchers.add(new Expectation(request().withMethod("GET").withPath("/ns/path-" + i))
                    .withNamespace("team-b").thenRespond(response().withBody("b" + i)), API);
            }
            // a global (no-namespace) literal
            matchers.add(literal("GET", "/ns/global", "global"), API);
        }

        List<HttpRequest> probes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            probes.add(request().withMethod("GET").withPath("/ns/path-" + i)
                .withHeader("X-MockServer-Namespace", "team-a"));
            probes.add(request().withMethod("GET").withPath("/ns/path-" + i)
                .withHeader("X-MockServer-Namespace", "team-b"));
            probes.add(request().withMethod("GET").withPath("/ns/path-" + i)); // no namespace -> global only
        }
        probes.add(request().withMethod("GET").withPath("/ns/global"));
        probes.add(request().withMethod("GET").withPath("/ns/global").withHeader("X-MockServer-Namespace", "team-a"));

        for (HttpRequest probe : probes) {
            Expectation indexedResult = indexed.firstMatchingExpectation(probe.clone());
            Expectation scanResult = scan.firstMatchingExpectation(probe.clone());
            String indexedBody = indexedResult == null ? null : indexedResult.getHttpResponse().getBodyAsString();
            String scanBody = scanResult == null ? null : scanResult.getHttpResponse().getBodyAsString();
            assertThat("namespace mismatch for " + probe.getPath().getValue() + " ns="
                + probe.getFirstHeader("X-MockServer-Namespace"), indexedBody, is(scanBody));
        }
    }

    // ---------- blank-method request against a bucketed literal still matches via bypass ----------

    @Test
    public void blankMethodRequestStillMatchesBucketedLiteralAboveThreshold() {
        RequestMatchers matchers = newMatchers(4);
        for (int i = 0; i < 50; i++) {
            matchers.add(literal("GET", "/exact/path-" + i, "e" + i), API);
        }
        // blank method matches the method criterion of every expectation -> must still find
        // the literal /exact/path-9 expectation (the index bypasses to the full scan here)
        Expectation matched = matchers.firstMatchingExpectation(request().withPath("/exact/path-9"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("e9"));
    }
}
