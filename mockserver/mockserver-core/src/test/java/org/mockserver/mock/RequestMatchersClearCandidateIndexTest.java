package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.Times;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.model.Parameter;
import org.mockserver.scheduler.Scheduler;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.HttpResponse.response;

/**
 * Correctness tests for the {@code clear(RequestDefinition)} candidate-index fast path (G9).
 *
 * <p>The defining guarantee is that consulting the index removes EXACTLY the same expectations as
 * the full linear scan — never fewer. The failure mode designed against is <b>silent
 * under-removal</b>: an expectation that should have been cleared quietly surviving (the same
 * silent-state-loss shape as G1). Every assertion here is able to fail — each was degrade-tested by
 * deliberately breaking {@code CandidateIndex.clearCandidates}/{@code RequestMatchers.clear} and
 * confirming it went red.
 *
 * <p>The index is forced to engage by injecting a low threshold via the parallel-safe seam
 * {@link RequestMatchers#withCandidateIndexThreshold(int)}; no global/system state is mutated
 * (matchExactCase is set per-{@link Configuration} instance), so these stay in the parallel phase.
 *
 * <p>The headline tests are DIFFERENTIAL: an indexed matcher (low threshold) must end in exactly
 * the same state after a clear as an independent un-indexed matcher (very high threshold) fed the
 * identical expectations and clear.
 */
public class RequestMatchersClearCandidateIndexTest {

    private static final int INDEX_ON = 2;        // engage the index at >= 2 expectations
    private static final int INDEX_OFF = 1_000_000; // never engage — full linear scan

    private RequestMatchers newMatchers(int threshold, Configuration configuration) {
        return new RequestMatchers(
            configuration,
            new MockServerLogger(),
            mock(Scheduler.class),
            mock(WebSocketClientRegistry.class)
        ).withCandidateIndexThreshold(threshold);
    }

    private static Expectation literal(String method, String path, String id) {
        return new Expectation(request().withMethod(method).withPath(path))
            .withId(id)
            .thenRespond(response().withBody(id));
    }

    private static Expectation withPath(HttpRequest req, String id) {
        return new Expectation(req).withId(id).thenRespond(response().withBody(id));
    }

    private static Set<String> remainingIds(RequestMatchers matchers) {
        return matchers.httpRequestMatchers.toSortedList().stream()
            .map(m -> m.getExpectation().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---------- the four current-semantics rows (re-established here, not read off the matcher) ----------

    @Test
    public void row1_pathOnlyLiteralClear_removesOnlyThatPath() {
        for (int threshold : new int[]{INDEX_ON, INDEX_OFF}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(literal("GET", "/a1", "a1"), API);
            matchers.add(literal("GET", "/a2", "a2"), API);
            matchers.add(literal("GET", "/b1", "b1"), API);

            matchers.clear(request().withPath("/a1"));

            assertThat("threshold=" + threshold, remainingIds(matchers), containsInAnyOrder("a2", "b1"));
        }
    }

    @Test
    public void row2_regexPathClear_removesEveryLiteralBucketItSpans() {
        for (int threshold : new int[]{INDEX_ON, INDEX_OFF}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(literal("GET", "/a1", "a1"), API);
            matchers.add(literal("GET", "/a2", "a2"), API);
            matchers.add(literal("GET", "/b1", "b1"), API);

            // A regex path cannot be narrowed by a literal bucket lookup -> full scan; must still
            // remove BOTH /a1 and /a2, which live in DIFFERENT literal buckets.
            matchers.clear(request().withPath("/a.*"));

            assertThat("threshold=" + threshold, remainingIds(matchers), contains("b1"));
        }
    }

    @Test
    public void row3_pathOnlyClear_matchesMethodBearingExpectation() {
        for (int threshold : new int[]{INDEX_ON, INDEX_OFF}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(literal("GET", "/a1", "a1"), API);
            matchers.add(literal("GET", "/a2", "a2"), API);
            matchers.add(literal("GET", "/b1", "b1"), API);

            matchers.clear(request().withPath("/a2"));

            assertThat("threshold=" + threshold, remainingIds(matchers), containsInAnyOrder("a1", "b1"));
        }
    }

    @Test
    public void row4_literalClearAlsoRemovesRegexExpectationCoveringIt() {
        for (int threshold : new int[]{INDEX_ON, INDEX_OFF}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            // /a.* is a REGEX-path expectation -> it lives in the path fallthrough and MUST be a
            // candidate for a literal clear, because the control-plane reverse match is bidirectional.
            matchers.add(withPath(request().withPath("/a.*"), "regex"), API);
            matchers.add(literal("GET", "/a1", "a1"), API);

            matchers.clear(request().withPath("/a1"));

            assertThat("threshold=" + threshold, remainingIds(matchers), is(empty()));
        }
    }

    // ---------- the (method, path) fast path (tighter bucket) ----------

    @Test
    public void methodAndPathClear_removesOnlyTheMatchingMethodBucket() {
        for (int threshold : new int[]{INDEX_ON, INDEX_OFF}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(literal("GET", "/a1", "getA1"), API);
            matchers.add(literal("POST", "/a1", "postA1"), API);
            matchers.add(literal("GET", "/a2", "getA2"), API);

            matchers.clear(request().withMethod("GET").withPath("/a1"));

            assertThat("threshold=" + threshold, remainingIds(matchers), containsInAnyOrder("postA1", "getA2"));
        }
    }

    @Test
    public void methodAndPathClear_extraHeaderConstraintStaysSelective() {
        // Extra clear constraints only make the clear MORE selective; they must not cause
        // under-removal and the indexed result must equal the un-indexed result.
        for (int threshold : new int[]{INDEX_ON, INDEX_OFF}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(withPath(request().withMethod("GET").withPath("/a1").withHeader("X", "Y"), "withHeader"), API);
            matchers.add(literal("GET", "/a1", "noHeader"), API);

            matchers.clear(request().withMethod("GET").withPath("/a1").withHeader(header("X", "Y")));

            // only the expectation whose request carries X:Y matches the clear
            assertThat("threshold=" + threshold, remainingIds(matchers), contains("noHeader"));
        }
    }

    @Test
    public void methodAndPathClear_alsoRemovesRegexMethodExpectationViaFallthrough() {
        // A regex-METHOD, literal-PATH expectation is (method,path)-fallthrough (bucketKeyFor needs a
        // literal method) yet a (method,path) literal clear can still match it via the bidirectional
        // control-plane match. The (method,path) fast path MUST union in the fallthrough, or this
        // silently survives.
        for (int threshold : new int[]{INDEX_ON, INDEX_OFF}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(literal("GET", "/x", "litX"), API);
            matchers.add(withPath(request().withMethod("G.*").withPath("/x"), "regexMethodX"), API);

            matchers.clear(request().withMethod("GET").withPath("/x"));

            assertThat("threshold=" + threshold, remainingIds(matchers), is(empty()));
        }
    }

    @Test
    public void matchExactCaseOn_clearDoesNotCorruptDataPlaneCaseMode() {
        // With matchExactCase ON the data-plane index is case-SENSITIVE. A clear (always case-
        // insensitive) must NOT be allowed to flip the maintained index to case-insensitive, or the
        // data plane would start matching across case. The fast path's matchExactCase-on fallback
        // (return null) is what prevents that.
        RequestMatchers matchers = newMatchers(INDEX_ON, configuration().matchExactCase(true));
        matchers.add(literal("GET", "/Case", "exact"), API);
        matchers.add(literal("GET", "/other", "other"), API);

        // case-sensitive data plane: lower-case path must NOT match the upper-case expectation
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/case")), is(nullValue()));

        // a clear that engages clearCandidates under matchExactCase ON
        matchers.clear(request().withPath("/nomatch"));

        // data plane is STILL case-sensitive after the clear
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/case")), is(nullValue()));
        assertThat(matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/Case")).getId(), is("exact"));
    }

    // ---------- matchExactCase, BOTH directions, path-only dimension ----------

    @Test
    public void caseInsensitive_pathOnlyClear_matchesLiteralDifferingOnlyInCase() {
        // default matchExactCase == false -> case-insensitive: clear "/a2" removes expectation "/A2"
        RequestMatchers indexed = newMatchers(INDEX_ON, configuration());
        RequestMatchers unindexed = newMatchers(INDEX_OFF, configuration());
        for (RequestMatchers matchers : new RequestMatchers[]{indexed, unindexed}) {
            matchers.add(literal("GET", "/A2", "upper"), API);
            matchers.add(literal("GET", "/b1", "b1"), API);
            matchers.clear(request().withPath("/a2"));
        }
        assertThat(remainingIds(indexed), contains("b1"));
        assertThat("indexed must equal un-indexed", remainingIds(indexed), is(remainingIds(unindexed)));
    }

    @Test
    public void matchExactCaseOn_clearIsStillCaseInsensitive_fullScanRemovesAcrossCase() {
        // FINDING: the control-plane clear matcher is ALWAYS case-insensitive, independent of
        // matchExactCase (HttpRequestPropertiesMatcher: caseSensitive = !controlPlaneMatcher &&
        // matchExactCase => false for a clear). So even with matchExactCase ON, clear "/a2" removes
        // "/A2". The fast path can't serve that from case-sensitive buckets, so clearCandidates
        // returns null and the full scan runs — and both indexed and un-indexed must agree.
        RequestMatchers indexed = newMatchers(INDEX_ON, configuration().matchExactCase(true));
        RequestMatchers unindexed = newMatchers(INDEX_OFF, configuration().matchExactCase(true));
        for (RequestMatchers matchers : new RequestMatchers[]{indexed, unindexed}) {
            matchers.add(literal("GET", "/A2", "upper"), API);
            matchers.add(literal("GET", "/b1", "b1"), API);
            matchers.clear(request().withPath("/a2"));
        }
        assertThat(remainingIds(indexed), contains("b1"));
        assertThat("indexed must equal un-indexed", remainingIds(indexed), is(remainingIds(unindexed)));
    }

    @Test
    public void matchExactCaseOn_methodAndPathClear_fullScanStillRemoves() {
        // Same finding for the (method,path) shape: with matchExactCase ON the (method,path) fast
        // path is also disabled (case-sensitive buckets), and the full-scan clear is case-insensitive.
        RequestMatchers indexed = newMatchers(INDEX_ON, configuration().matchExactCase(true));
        RequestMatchers unindexed = newMatchers(INDEX_OFF, configuration().matchExactCase(true));
        for (RequestMatchers matchers : new RequestMatchers[]{indexed, unindexed}) {
            matchers.add(literal("GET", "/A2", "upper"), API);
            matchers.add(literal("POST", "/A2", "post"), API);
            matchers.clear(request().withMethod("get").withPath("/a2"));
        }
        assertThat(remainingIds(indexed), contains("post"));
        assertThat(remainingIds(indexed), is(remainingIds(unindexed)));
    }

    @Test
    public void runtimeMatchExactCaseFlipToOn_disablesFastPath_fullScanRemovesAcrossCase() {
        // Build case-insensitive (fast path active), then flip matchExactCase ON at runtime. clear
        // reads the live config, so the fast path must switch off and the full (case-insensitive)
        // scan must still remove "/A2".
        Configuration liveConfig = configuration(); // matchExactCase == false
        RequestMatchers matchers = newMatchers(INDEX_ON, liveConfig);
        matchers.add(literal("GET", "/A2", "upper"), API);
        matchers.add(literal("GET", "/b1", "b1"), API);

        liveConfig.matchExactCase(true);
        matchers.clear(request().withPath("/a2"));
        assertThat(remainingIds(matchers), contains("b1"));
    }

    @Test
    public void runtimeMatchExactCaseFlipToOff_rebuildsIndexToCaseInsensitiveBeforeClear() {
        // Build case-SENSITIVE (index folds nothing), then flip matchExactCase OFF at runtime WITH
        // NO intervening data-plane read. The clear (always case-insensitive) must rebuild the index
        // to the CI fold before the bucket lookup, or it would under-remove "/A2".
        Configuration liveConfig = configuration().matchExactCase(true);
        RequestMatchers matchers = newMatchers(INDEX_ON, liveConfig);
        matchers.add(literal("GET", "/A2", "upper"), API);
        matchers.add(literal("GET", "/b1", "b1"), API);

        liveConfig.matchExactCase(false);
        matchers.clear(request().withPath("/a2"));
        assertThat(remainingIds(matchers), contains("b1"));
    }

    // ---------- clear-by-id and clear-everything (null) paths must be untouched ----------

    @Test
    public void clearEverything_withNullRequestDefinition_stillResetsAll() {
        RequestMatchers matchers = newMatchers(INDEX_ON, configuration());
        matchers.add(literal("GET", "/a1", "a1"), API);
        matchers.add(literal("POST", "/a.*", "regex"), API);

        matchers.clear((org.mockserver.model.RequestDefinition) null);

        assertThat(remainingIds(matchers), is(empty()));
    }

    @Test
    public void clearById_stillRemovesExactlyThatExpectation() {
        RequestMatchers matchers = newMatchers(INDEX_ON, configuration());
        matchers.add(literal("GET", "/a1", "a1"), API);
        matchers.add(literal("GET", "/a2", "a2"), API);

        matchers.clear(new org.mockserver.model.ExpectationId().withId("a1"), "corr");

        assertThat(remainingIds(matchers), contains("a2"));
    }

    // ---------- concurrent-mutation shape: iterate the candidate copy while removing ----------

    @Test
    public void pathOnlyClear_removesEveryExpectationSharingOnePath() {
        // Several distinct expectations share ONE literal path, so the path bucket (and the
        // candidate union list) holds several matchers. Removing while iterating that list must
        // clear ALL of them — a lazy stream over the live skip-list is NOT a snapshot, so the fast
        // path copies into a list; this proves iterate-while-removing clears every one.
        RequestMatchers matchers = newMatchers(INDEX_ON, configuration());
        for (int i = 0; i < 20; i++) {
            matchers.add(literal("GET", "/same", "same" + i), API);
        }
        matchers.add(literal("GET", "/other", "other"), API);

        matchers.clear(request().withPath("/same"));

        assertThat(remainingIds(matchers), contains("other"));
    }

    // ---------- a large differential sweep across mixed shapes ----------

    @Test
    public void largeMixedStore_indexedClearEqualsUnindexedClear() {
        Configuration config = configuration();
        RequestMatchers indexed = newMatchers(INDEX_ON, config);
        RequestMatchers unindexed = newMatchers(INDEX_OFF, configuration());

        // Identical mixed population in both.
        for (RequestMatchers m : new RequestMatchers[]{indexed, unindexed}) {
            for (int i = 0; i < 200; i++) {
                m.add(literal("GET", "/lit/" + i, "get-lit-" + i), API);
                m.add(literal("POST", "/lit/" + i, "post-lit-" + i), API);
            }
            m.add(withPath(request().withPath("/lit/.*"), "regex-covering-lit"), API);
            m.add(withPath(request().withMethod("G.*").withPath("/lit/7"), "regexMethod-lit7"), API);
            m.add(literal("GET", "/A2", "case-upper"), API);
            m.add(withPath(request().withPath("!/lit/1"), "notted"), API);
        }

        // Apply a battery of clears (path-only literal, method+path, regex path, case variant) to
        // both, then require identical remaining state after each.
        HttpRequest[] clears = {
            request().withPath("/lit/5"),                       // path-only literal
            request().withMethod("GET").withPath("/lit/9"),     // method+path
            request().withPath("/lit/.*"),                      // regex path -> full scan
            request().withMethod("POST").withPath("/lit/50"),   // method+path
            request().withPath("/a2"),                          // case-insensitive hit on /A2
            request().withPath("/lit/7"),                       // path-only, also hit by regexMethod
        };
        for (HttpRequest clear : clears) {
            indexed.clear(clear.clone());
            unindexed.clear(clear.clone());
            assertThat("after clear " + clear, remainingIds(indexed), is(remainingIds(unindexed)));
        }
    }

    // ---------- shapes that MUST route to the conservative full scan ----------
    // Each of these is narrowing-unsound, so clearCandidates returns null and the caller falls back
    // to the unindexed scan. They are asserted differentially (INDEX_ON vs INDEX_OFF must agree)
    // because the risk is not that they behave oddly, it is that a future refactor quietly starts
    // narrowing them and they begin to UNDER-REMOVE with no error anywhere.

    @Test
    public void nonAsciiPathClear_agreesWithTheUnindexedScan() {
        // A genuine differential: whatever the unindexed scan does with a non-ASCII path, the
        // indexed path must do IDENTICALLY. Asserting a hardcoded expected set here would bake in
        // my own guess about how equalsIgnoreCase folds non-ASCII; the unindexed scan is the
        // authority, so it is the oracle.
        Set<String> unindexed = null;
        for (int threshold : new int[]{INDEX_OFF, INDEX_ON}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(literal("GET", "/caf\u00e9", "cafe"), API);
            matchers.add(literal("GET", "/CAF\u00c9", "cafeUpper"), API);
            matchers.add(literal("GET", "/plain", "plain"), API);

            // A non-ASCII path is never bucketed: its case fold can diverge from the matcher's own
            // equalsIgnoreCase, so it must not be narrowed.
            matchers.clear(request().withPath("/caf\u00e9"));

            Set<String> remaining = remainingIds(matchers);
            if (unindexed == null) {
                unindexed = remaining;
                // guard the oracle itself: a scan that removed nothing, or everything, would make
                // the comparison below vacuously true
                assertThat("the unindexed scan must actually clear the literal it was given",
                    remaining.contains("cafe"), is(false));
                assertThat("the unindexed scan must not clear an unrelated path",
                    remaining.contains("plain"), is(true));
            } else {
                assertThat("indexed clear must agree with the unindexed scan", remaining, is(unindexed));
            }
        }
    }

    @Test
    public void pathParameterClear_agreesWithTheUnindexedScan() {
        for (int threshold : new int[]{INDEX_ON, INDEX_OFF}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(literal("GET", "/users/1", "u1"), API);
            matchers.add(literal("GET", "/users/2", "u2"), API);
            matchers.add(literal("GET", "/other", "other"), API);

            // Path parameters rewrite the clear's path into a regex, so it is not a literal-path
            // clear and cannot be served from a literal bucket.
            //
            // HONEST SCOPE: this pins AGREEMENT between the indexed and unindexed paths; it does
            // NOT prove the `pathParameters` early-return in clearCandidates is load-bearing.
            // Deleting that guard and re-running this class leaves all tests green, because a
            // path carrying parameters is not a plain literal anyway and `literalValue` returns
            // null a few lines later. A probe with a LITERAL path that also carries path
            // parameters was also identical with and without the guard (neither removed
            // anything). So the guard is defence-in-depth, not a proven necessity - the same
            // honest status as the matchExactCase-ON guard. Do not cite this test as evidence
            // for it.
            matchers.clear(request().withPath("/users/{id}").withPathParameters(new Parameter("id", "1")));

            assertThat("threshold=" + threshold, remainingIds(matchers), containsInAnyOrder("u2", "other"));
        }
    }

    @Test
    public void nottedMethodClear_agreesWithTheUnindexedScan() {
        for (int threshold : new int[]{INDEX_ON, INDEX_OFF}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(literal("GET", "/shared", "get"), API);
            matchers.add(literal("POST", "/shared", "post"), API);
            matchers.add(literal("GET", "/elsewhere", "elsewhere"), API);

            // A notted method is not a plain literal, so there is no method bucket to consult; the
            // path-only dimension still applies and must find BOTH /shared expectations as
            // candidates, then let the full match check decide.
            matchers.clear(request().withMethod(not("GET")).withPath("/shared"));

            assertThat("threshold=" + threshold, remainingIds(matchers), containsInAnyOrder("get", "elsewhere"));
        }
    }

    @Test
    public void nonHttpRequestDefinitionClear_agreesWithTheUnindexedScan() {
        // UNINDEXED FIRST so it is the oracle. An OpenAPIDefinition clear's semantics are not what
        // this unit changes, so this test pins AGREEMENT between the two paths rather than asserting
        // a set I would otherwise be guessing at.
        Set<String> nonHttpOracle = null;
        for (int threshold : new int[]{INDEX_OFF, INDEX_ON}) {
            RequestMatchers matchers = newMatchers(threshold, configuration());
            matchers.add(literal("GET", "/a1", "a1"), API);
            matchers.add(literal("GET", "/a2", "a2"), API);

            // Not an HttpRequest at all -> clearCandidates cannot inspect a path and must not narrow.
            matchers.clear(new OpenAPIDefinition().withOperationId("nothingMatchesThis"));

            Set<String> remaining = remainingIds(matchers);
            if (nonHttpOracle == null) {
                nonHttpOracle = remaining;
            } else {
                assertThat("indexed clear must agree with the unindexed scan for a non-HttpRequest "
                    + "definition, whatever that scan's semantics turn out to be", remaining, is(nonHttpOracle));
            }
        }
    }
}
