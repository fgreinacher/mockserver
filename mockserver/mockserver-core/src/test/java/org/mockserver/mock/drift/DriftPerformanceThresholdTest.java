package org.mockserver.mock.drift;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural coverage for the instance-set {@code responseTimeThresholdMs} flag on
 * {@link DriftAnalyzer#checkPerformanceDrift}: the flag governs whether observed p95 latencies
 * straddling the threshold raise a {@link DriftType#PERFORMANCE} flag in the {@link DriftStore},
 * and the webhook gate ({@link DriftAlertNotifier}) keys off that stored flag.
 *
 * <p>Assertions are on the production analyzer outcome (the {@link DriftRecord}s the real
 * {@link PercentileTracker} + {@link DriftAnalyzer} produce), never on a re-implemented threshold.
 */
public class DriftPerformanceThresholdTest {

    private static final long THRESHOLD_MS = 100L;
    private static final long NOW = 5000L;

    /** Synchronous fake sender capturing every outbound webhook request. */
    private static final class CapturingSender implements Function<HttpRequest, CompletableFuture<HttpResponse>> {
        final List<HttpRequest> captured = new ArrayList<>();

        @Override
        public CompletableFuture<HttpResponse> apply(HttpRequest request) {
            captured.add(request);
            return CompletableFuture.completedFuture(response().withStatusCode(200));
        }
    }

    @Before
    public void resetSingletons() {
        PercentileTracker.getInstance().clear();
        DriftAlertNotifier.getInstance().reset();
        DriftAlertNotifier.getInstance().setSender(null);
        DriftAlertNotifier.getInstance().setClock(System::currentTimeMillis);
        DriftAlertNotifier.getInstance().configure(false, "", SemanticSeverity.BREAKING, 60000);
    }

    @After
    public void cleanSingletons() {
        PercentileTracker.getInstance().clear();
        DriftAlertNotifier.getInstance().reset();
        DriftAlertNotifier.getInstance().setSender(null);
        DriftAlertNotifier.getInstance().setClock(System::currentTimeMillis);
        DriftAlertNotifier.getInstance().configure(false, "", SemanticSeverity.BREAKING, 60000);
    }

    /** Record {@code n} identical observations for the given expectation into the real tracker. */
    private static void recordSamples(String expectationId, long responseTimeMs, int n) {
        for (int i = 0; i < n; i++) {
            PercentileTracker.getInstance().record(expectationId, responseTimeMs);
        }
    }

    @Test
    public void latencyUnderThresholdRaisesNoPerformanceFlag() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        analyzer.setResponseTimeThresholdMs(THRESHOLD_MS);

        String expectationId = UUID.randomUUID().toString();
        // Twenty samples all at 50ms -> p95 == 50ms, comfortably under the 100ms threshold.
        recordSamples(expectationId, 50L, 20);
        assertThat(PercentileTracker.getInstance().p95(expectationId), is(50L));

        analyzer.checkPerformanceDrift(expectationId, 50L, NOW);

        assertThat("under-threshold p95 must NOT flag", store.getByExpectationId(expectationId), is(empty()));
    }

    @Test
    public void latencyOverThresholdRaisesPerformanceFlag() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        analyzer.setResponseTimeThresholdMs(THRESHOLD_MS);

        String expectationId = UUID.randomUUID().toString();
        // Twenty samples all at 300ms -> p95 == 300ms, over the 100ms threshold.
        recordSamples(expectationId, 300L, 20);
        assertThat(PercentileTracker.getInstance().p95(expectationId), is(300L));

        analyzer.checkPerformanceDrift(expectationId, 300L, NOW);

        List<DriftRecord> records = store.getByExpectationId(expectationId);
        assertThat("over-threshold p95 must flag exactly one PERFORMANCE record", records, hasSize(1));
        DriftRecord record = records.get(0);
        assertThat(record.getDriftType(), is(DriftType.PERFORMANCE));
        assertThat(record.getField(), is("p95_response_time_ms"));
        assertThat(record.getExpectedValue(), is("<=" + THRESHOLD_MS));
        assertThat(record.getActualValue(), is("300"));
        assertThat(record.getEpochTimeMs(), is(NOW));
    }

    @Test
    public void flaggedSetChangesAsLatencyCrossesTheSameThreshold() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        analyzer.setResponseTimeThresholdMs(THRESHOLD_MS);

        // One expectation stays under; a sibling with identical config goes over. The flagged
        // SET must contain only the over-threshold expectation, proving the flag tracks the
        // threshold and is not a blanket "always flag".
        String underId = UUID.randomUUID().toString();
        String overId = UUID.randomUUID().toString();
        recordSamples(underId, 80L, 20);   // p95 = 80  < 100
        recordSamples(overId, 250L, 20);   // p95 = 250 > 100

        analyzer.checkPerformanceDrift(underId, 80L, NOW);
        analyzer.checkPerformanceDrift(overId, 250L, NOW);

        assertThat(store.getByExpectationId(underId), is(empty()));
        List<DriftRecord> flagged = store.getByExpectationId(overId);
        assertThat(flagged, hasSize(1));
        assertThat(flagged.get(0).getActualValue(), is("250"));
    }

    @Test
    public void tailLatencyPushingP95OverThresholdFlips() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        analyzer.setResponseTimeThresholdMs(THRESHOLD_MS);

        String expectationId = UUID.randomUUID().toString();
        // Bulk fast (50ms) with a slow tail. p95 index over 100 samples lands in the slow tail,
        // so the p95 crosses the threshold even though the median is well under it.
        recordSamples(expectationId, 50L, 90);   // indices 0..89 when sorted
        recordSamples(expectationId, 500L, 10);  // indices 90..99 -> p95 lands here
        assertThat(PercentileTracker.getInstance().p50(expectationId), is(50L));
        assertThat(PercentileTracker.getInstance().p95(expectationId), is(500L));

        analyzer.checkPerformanceDrift(expectationId, 500L, NOW);

        List<DriftRecord> records = store.getByExpectationId(expectationId);
        assertThat(records, hasSize(1));
        assertThat(records.get(0).getDriftType(), is(DriftType.PERFORMANCE));
        assertThat(records.get(0).getActualValue(), is("500"));
    }

    @Test
    public void thresholdDisabledSuppressesFlagEvenForHighLatency() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        // Zero disables the performance check entirely (the gate is off).
        analyzer.setResponseTimeThresholdMs(0L);

        String expectationId = UUID.randomUUID().toString();
        recordSamples(expectationId, 9999L, 20);
        assertThat(PercentileTracker.getInstance().p95(expectationId), is(9999L));

        analyzer.checkPerformanceDrift(expectationId, 9999L, NOW);

        assertThat("disabled threshold must never flag", store.getByExpectationId(expectationId), is(empty()));
    }

    @Test
    public void webhookGateFiresOnlyWhenThePerformanceFlagIsRaised() {
        // The webhook gate keys off the STORED performance flag: checkPerformanceDrift only calls
        // DriftAlertNotifier.onDriftStored when it stores a record. PERFORMANCE -> WARNING severity,
        // so a WARNING-threshold notifier fires for the over-threshold case and stays silent otherwise.
        CapturingSender sender = new CapturingSender();
        DriftAlertNotifier.getInstance().setSender(sender);
        DriftAlertNotifier.getInstance().setClock(() -> NOW);
        DriftAlertNotifier.getInstance().configure(true, "http://drift-hook/endpoint", SemanticSeverity.WARNING, 60000);

        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        analyzer.setResponseTimeThresholdMs(THRESHOLD_MS);

        // Under threshold -> no flag stored -> webhook gate stays closed.
        String underId = UUID.randomUUID().toString();
        recordSamples(underId, 40L, 20);
        analyzer.checkPerformanceDrift(underId, 40L, NOW);
        assertThat("no flag -> no webhook", sender.captured, is(empty()));

        // Over threshold -> flag stored -> webhook gate opens exactly once.
        String overId = UUID.randomUUID().toString();
        recordSamples(overId, 400L, 20);
        analyzer.checkPerformanceDrift(overId, 400L, NOW);

        assertThat("flag raised -> webhook fires once", sender.captured, hasSize(1));
        String body = sender.captured.get(0).getBodyAsString();
        assertThat(body.contains("\"severity\":\"WARNING\""), is(true));
        assertThat(body.contains("\"driftType\":\"PERFORMANCE\""), is(true));
    }

    @Test
    public void webhookGateStaysClosedWhenSeverityBelowNotifierThreshold() {
        // A BREAKING-threshold notifier must NOT fire for a PERFORMANCE flag (WARNING severity),
        // proving the webhook keys off the flag's severity rather than merely "a record exists".
        CapturingSender sender = new CapturingSender();
        DriftAlertNotifier.getInstance().setSender(sender);
        DriftAlertNotifier.getInstance().setClock(() -> NOW);
        DriftAlertNotifier.getInstance().configure(true, "http://drift-hook/endpoint", SemanticSeverity.BREAKING, 60000);

        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        analyzer.setResponseTimeThresholdMs(THRESHOLD_MS);

        String overId = UUID.randomUUID().toString();
        recordSamples(overId, 400L, 20);
        analyzer.checkPerformanceDrift(overId, 400L, NOW);

        // The performance flag IS stored ...
        List<DriftRecord> flagged = store.getByExpectationId(overId);
        assertThat(flagged, hasSize(1));
        assertThat(flagged, contains(driftOfType(DriftType.PERFORMANCE)));
        // ... but the WARNING-severity flag is below the BREAKING webhook threshold, so no send.
        assertThat(sender.captured, is(empty()));
    }

    private static org.hamcrest.Matcher<DriftRecord> driftOfType(DriftType type) {
        return new org.hamcrest.TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(DriftRecord item) {
                return item != null && item.getDriftType() == type;
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("DriftRecord of type ").appendValue(type);
            }
        };
    }
}
