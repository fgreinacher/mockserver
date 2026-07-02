package org.mockserver.mock.action.http;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStage;
import org.mockserver.load.LoadStep;
import org.mockserver.metrics.Metrics;
import org.mockserver.metrics.MetricsLock;
import org.mockserver.metrics.OtelMetricsExporter;
import org.mockserver.slo.SloSampleStore;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests for the {@code mock_server_load_*} metric family wired through
 * {@link LoadScenarioOrchestrator}. Drives the orchestrator with a deterministic synchronous sender
 * (as {@code LoadScenarioOrchestratorTest} does), then asserts on the Prometheus registry and an
 * in-memory OTLP reader. Mutates the global Prometheus registry, so it is serialized by
 * {@link MetricsLock} and lives in the sequential test phase.
 */
public class LoadMetricsTest {

    @ClassRule
    public static final MetricsLock metricsLock = new MetricsLock();

    private AtomicLong clock;
    private ScheduledExecutorService scheduler;
    private LoadScenarioOrchestrator orchestrator;
    private int originalMaxInFlight;

    @Before
    public void setUp() {
        clock = new AtomicLong(10_000L);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-load-metrics-scheduler");
            t.setDaemon(true);
            return t;
        });
        orchestrator = new LoadScenarioOrchestrator(clock::get, scheduler);
        Metrics.resetAdditionalMetricsForTesting();
        SloSampleStore.getInstance().reset();
        originalMaxInFlight = ConfigurationProperties.loadGenerationMaxInFlightRequests();
        // Use the proper setter (not raw System.setProperty) so the ConfigurationProperties cache is
        // invalidated — otherwise a cached value from another test leaks the allowlist.
        ConfigurationProperties.loadGenerationMetricLabels("");
    }

    @After
    public void tearDown() {
        orchestrator.reset();
        scheduler.shutdownNow();
        Metrics.resetAdditionalMetricsForTesting();
        SloSampleStore.getInstance().reset();
        ConfigurationProperties.loadGenerationMetricLabels("");
        ConfigurationProperties.loadGenerationMaxInFlightRequests(originalMaxInFlight);
    }

    /** Synchronous fake sender that records every request and returns a 200 with a known body. */
    private static final class RecordingSender implements Function<org.mockserver.model.HttpRequest, CompletableFuture<org.mockserver.model.HttpResponse>> {
        final ConcurrentLinkedQueue<org.mockserver.model.HttpRequest> sent = new ConcurrentLinkedQueue<>();
        private final int statusCode;
        private final String responseBody;

        RecordingSender() {
            this(200, "ok-body");
        }

        RecordingSender(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public CompletableFuture<org.mockserver.model.HttpResponse> apply(org.mockserver.model.HttpRequest httpRequest) {
            sent.add(httpRequest);
            return CompletableFuture.completedFuture(response().withStatusCode(statusCode).withBody(responseBody));
        }
    }

    private boolean awaitAtLeast(RecordingSender sender, int n, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (sender.sent.size() >= n) {
                return true;
            }
            Thread.sleep(5);
        }
        return sender.sent.size() >= n;
    }

    private Metrics enableMetrics(String allowlistCsv) {
        if (allowlistCsv != null) {
            ConfigurationProperties.loadGenerationMetricLabels(allowlistCsv);
        }
        Configuration config = configuration().metricsEnabled(true);
        Metrics metrics = new Metrics(config);
        orchestrator.setConfiguration(config);
        return metrics;
    }

    @Test
    public void registersLoadMetricFamily() {
        enableMetrics(null);
        assertThat(Metrics.isLoadMetricsActive(), is(true));
        assertThat(scrapeContains("mock_server_load_request_duration_seconds"), is(true));
        assertThat(scrapeContains("mock_server_load_requests"), is(true));
        assertThat(scrapeContains("mock_server_load_request_bytes"), is(true));
        assertThat(scrapeContains("mock_server_load_response_bytes"), is(true));
        assertThat(scrapeContains("mock_server_load_iterations"), is(true));
        assertThat(scrapeContains("mock_server_load_throttled"), is(true));
        assertThat(scrapeContains("mock_server_load_errors"), is(true));
        assertThat(scrapeContains("mock_server_load_checks"), is(true));
        assertThat(scrapeContains("mock_server_load_active_vus"), is(true));
        assertThat(scrapeContains("mock_server_load_inflight_requests"), is(true));
    }

    @Test
    public void loadMetricsAreOffWhenMetricsDisabled() {
        // No new Metrics(metricsEnabled=true) — the family must not register.
        assertThat(Metrics.isLoadMetricsActive(), is(false));
        // The observe call is a safe no-op.
        Metrics.observeLoadRequest("s", "r", "0", "/api", "GET", 200, 0.01, 10, 20, null, null);
    }

    @Test
    public void requestsCounterIncrementsWithStructuredLabels() throws Exception {
        enableMetrics(null);
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("orders-load")
            .withMaxRequests(10)
            .withProfile(LoadProfile.constant(2, 60_000L))
            .withSteps(new LoadStep()
                .withRequest(request().withMethod("POST").withPath("/api/orders/12345").withHeader("Host", "target").withBody("payload-body")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 10, 5_000L), is(true));
        Thread.sleep(100); // let whenComplete callbacks flush

        String runId = orchestrator.getStatus().runId;
        // route templatised to /api/orders/{id}; step label defaults to the index "0"; status class 2xx.
        long count = Metrics.getLoadRequestCount("orders-load", runId, "0", "/api/orders/{id}", "POST", "2xx");
        assertThat(count, greaterThanOrEqualTo(10L));
    }

    @Test
    public void runIdIsStableAcrossSnapshotsAndOnMetricLabels() throws Exception {
        enableMetrics(null);
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("stable-run")
            .withMaxRequests(5)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        String runId1 = orchestrator.getStatus().runId;
        assertThat(awaitAtLeast(sender, 5, 5_000L), is(true));
        String runId2 = orchestrator.getStatus().runId;

        // run_id is a stable UUID, identical across snapshots of the same run.
        assertThat(runId1, is(notNullValue()));
        assertThat(runId1, is(runId2));
        assertThat(java.util.UUID.fromString(runId1).toString(), is(runId1));

        // It is carried on the metric labels: the requests counter has a series for this run_id.
        // The request sets no HTTP method, so the method label is "unknown".
        Thread.sleep(100);
        long count = Metrics.getLoadRequestCount("stable-run", runId1, "0", "/api", "unknown", "2xx");
        assertThat(count, greaterThanOrEqualTo(5L));
    }

    @Test
    public void stepNameOverridesStepAndRouteLabels() throws Exception {
        enableMetrics(null);
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("named-step")
            .withMaxRequests(5)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withName("create-order")
                .withRequest(request().withMethod("POST").withPath("/api/orders/999").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 5, 5_000L), is(true));
        Thread.sleep(100);

        String runId = orchestrator.getStatus().runId;
        // both step and route labels use the explicit step name.
        long count = Metrics.getLoadRequestCount("named-step", runId, "create-order", "create-order", "POST", "2xx");
        assertThat(count, greaterThanOrEqualTo(5L));
    }

    @Test
    public void throttledCounterIncrementsOnInflightCap() throws Exception {
        // Cap in-flight to 1 and never complete the future so the cap is hit, forcing inflight_cap throttles.
        ConfigurationProperties.loadGenerationMaxInFlightRequests(1);
        Configuration config = configuration().metricsEnabled(true);
        new Metrics(config);
        orchestrator.setConfiguration(config);

        // A sender that never completes — the single in-flight permit is held, every other dispatch throttles.
        Function<org.mockserver.model.HttpRequest, CompletableFuture<org.mockserver.model.HttpResponse>> blocking =
            req -> new CompletableFuture<>();
        LoadScenario scenario = new LoadScenario()
            .withName("throttle-load")
            .withProfile(LoadProfile.constant(5, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, blocking), is(nullValue()));

        String runId = orchestrator.getStatus().runId;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (Metrics.getLoadThrottledCount("throttle-load", runId, "inflight_cap") == 0
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(Metrics.getLoadThrottledCount("throttle-load", runId, "inflight_cap"), greaterThan(0L));
    }

    @Test
    public void droppedIterationsCountsRateStageVuCapShortfall() throws Exception {
        // The dominant open-model drop path: a RATE stage whose arrival rate exceeds what the VU cap
        // can sustain. With maxVus=2 and a sender that never completes (so both one-shot slots stay
        // occupied), every further owed iteration is a VU-cap shortfall — dropped, counted as a
        // rate_limit throttle AND folded into droppedIterations. Asserts the invariant holds:
        // droppedIterations == count(rate_limit) for the run (no inflight_cap drops occur here).
        Configuration config = configuration().metricsEnabled(true);
        new Metrics(config);
        orchestrator.setConfiguration(config);

        // A sender that never completes — the two VU slots are held for the whole test.
        Function<org.mockserver.model.HttpRequest, CompletableFuture<org.mockserver.model.HttpResponse>> blocking =
            req -> new CompletableFuture<>();
        LoadScenario scenario = new LoadScenario()
            .withName("rate-cap-load")
            .withProfile(LoadProfile.of(LoadStage.constantRate(100.0, 60_000L).withMaxVus(2)))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, blocking), is(nullValue()));
        String runId = orchestrator.getStatus().runId;

        // Drive the clock forward in 100ms steps so the deficit accumulator integrates 100/s; after the
        // first ~2 iterations consume the VU slots, every subsequent owed iteration is a shortfall.
        for (int i = 1; i <= 10; i++) {
            clock.set(10_000L + i * 100L);
            orchestrator.tickNow();
        }
        long deadline = System.currentTimeMillis() + 5_000L;
        while (orchestrator.getStatus().droppedIterations == 0 && System.currentTimeMillis() < deadline) {
            clock.addAndGet(100L);
            orchestrator.tickNow();
            Thread.sleep(5);
        }

        long dropped = orchestrator.getStatus().droppedIterations;
        assertThat("RATE VU-cap shortfall is surfaced as dropped iterations", dropped, greaterThan(0L));
        // Invariant: every dropped iteration here is a rate_limit throttle (no inflight_cap drops).
        assertThat("droppedIterations equals the rate_limit throttle count for the run",
            dropped, is(Metrics.getLoadThrottledCount("rate-cap-load", runId, "rate_limit")));
        assertThat("no inflight_cap drops occur in the RATE VU-cap path",
            Metrics.getLoadThrottledCount("rate-cap-load", runId, "inflight_cap"), is(0L));
    }

    @Test
    public void errorsCounterIncrementsOnHttp5xx() throws Exception {
        enableMetrics(null);
        RecordingSender sender = new RecordingSender(503, "error-body");
        LoadScenario scenario = new LoadScenario()
            .withName("errors-load")
            .withMaxRequests(5)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 5, 5_000L), is(true));

        String runId = orchestrator.getStatus().runId;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (Metrics.getLoadErrorCount("errors-load", runId, "http_5xx") < 5
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(Metrics.getLoadErrorCount("errors-load", runId, "http_5xx"), greaterThanOrEqualTo(5L));
    }

    @Test
    public void checksCounterIncrementsOnPassAndFail() throws Exception {
        enableMetrics(null);
        // 200 with body {"ok":false}: a STATUS==200 check PASSES, a BODY_JSONPATH $.ok==true check FAILS.
        RecordingSender sender = new RecordingSender(200, "{\"ok\":false}");
        LoadScenario scenario = new LoadScenario()
            .withName("checks-metric")
            .withMaxRequests(5)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep()
                .withName("assert")
                .withRequest(request().withPath("/api").withHeader("Host", "target"))
                .withCheck(new org.mockserver.load.LoadCheck()
                    .withSource(org.mockserver.load.LoadCheck.Source.STATUS)
                    .withComparator(org.mockserver.load.LoadCheck.Comparator.EQUALS).withValue("200"))
                .withCheck(new org.mockserver.load.LoadCheck()
                    .withSource(org.mockserver.load.LoadCheck.Source.BODY_JSONPATH).withJsonPath("$.ok")
                    .withComparator(org.mockserver.load.LoadCheck.Comparator.EQUALS).withValue("true")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 5, 5_000L), is(true));

        String runId = orchestrator.getStatus().runId;
        long deadline = System.currentTimeMillis() + 5_000L;
        while ((Metrics.getLoadCheckCount("checks-metric", runId, "assert", "pass") == 0
            || Metrics.getLoadCheckCount("checks-metric", runId, "assert", "fail") == 0)
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat("pass outcomes are counted",
            Metrics.getLoadCheckCount("checks-metric", runId, "assert", "pass"), greaterThan(0L));
        assertThat("fail outcomes are counted",
            Metrics.getLoadCheckCount("checks-metric", runId, "assert", "fail"), greaterThan(0L));
        assertThat(scrapeContains("mock_server_load_checks"), is(true));
        orchestrator.stop("checks-metric");
    }

    @Test
    public void errorsCounterIncrementsOnNullResponse() throws Exception {
        enableMetrics(null);
        Function<org.mockserver.model.HttpRequest, CompletableFuture<org.mockserver.model.HttpResponse>> nullSender =
            req -> CompletableFuture.completedFuture(null);
        LoadScenario scenario = new LoadScenario()
            .withName("null-load")
            .withMaxRequests(3)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, nullSender), is(nullValue()));
        String runId = orchestrator.getStatus().runId;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (Metrics.getLoadErrorCount("null-load", runId, "null_response") < 3
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(Metrics.getLoadErrorCount("null-load", runId, "null_response"), greaterThanOrEqualTo(3L));
    }

    @Test
    public void iterationsCounterIncrements() throws Exception {
        enableMetrics(null);
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("iter-load")
            .withMaxRequests(6)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 6, 5_000L), is(true));

        String runId = orchestrator.getStatus().runId;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (Metrics.getLoadIterationCount("iter-load", runId) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(Metrics.getLoadIterationCount("iter-load", runId), greaterThan(0L));
    }

    @Test
    public void customLabelAddedAsPrometheusLabelOnlyWhenAllowlisted() throws Exception {
        enableMetrics("team");
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("labelled-load")
            .withMaxRequests(5)
            .withLabel("team", "payments")
            .withLabel("env", "staging") // NOT allowlisted -> not a Prometheus label
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 5, 5_000L), is(true));
        Thread.sleep(100);

        // 'team' is an allowlisted Prometheus label on mock_server_load_requests; 'env' is not present.
        boolean foundTeamPayments = false;
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals("mock_server_load_requests")
                && snapshot instanceof CounterSnapshot counterSnapshot) {
                for (CounterSnapshot.CounterDataPointSnapshot dp : counterSnapshot.getDataPoints()) {
                    assertThat("env must not be a Prometheus label", dp.getLabels().get("env"), is(nullValue()));
                    if ("payments".equals(dp.getLabels().get("team"))) {
                        foundTeamPayments = true;
                    }
                }
            }
        }
        assertThat("allowlisted custom label 'team=payments' present on Prometheus series", foundTeamPayments, is(true));
    }

    @Test
    public void customLabelsAppearAsOtelAttributes() throws Exception {
        enableMetrics(null); // empty allowlist: Prometheus carries no custom labels, OTEL still does
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            RecordingSender sender = new RecordingSender();
            LoadScenario scenario = new LoadScenario()
                .withName("otel-labelled")
                .withMaxRequests(5)
                .withLabel("team", "payments")
                .withLabel("env", "staging")
                .withProfile(LoadProfile.constant(1, 60_000L))
                .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

            assertThat(orchestrator.start(scenario, sender), is(nullValue()));
            assertThat(awaitAtLeast(sender, 5, 5_000L), is(true));
            Thread.sleep(100);

            Collection<MetricData> collected = reader.collectAllMetrics();
            Set<String> names = collected.stream().map(MetricData::getName).collect(Collectors.toSet());
            assertThat(names, hasItem("mock_server_load_requests"));

            MetricData requests = collected.stream()
                .filter(m -> m.getName().equals("mock_server_load_requests"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mock_server_load_requests not exported to OTLP"));
            boolean foundAttrs = requests.getLongSumData().getPoints().stream().anyMatch(p ->
                "payments".equals(p.getAttributes().get(AttributeKey.stringKey("team")))
                    && "staging".equals(p.getAttributes().get(AttributeKey.stringKey("env")))
                    && "otel-labelled".equals(p.getAttributes().get(AttributeKey.stringKey("scenario"))));
            assertThat("custom labels present as OTEL attributes", foundAttrs, is(true));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void exportsLoadFamilyToOtlp() throws Exception {
        enableMetrics(null);
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            RecordingSender sender = new RecordingSender();
            LoadScenario scenario = new LoadScenario()
                .withName("otel-load")
                .withMaxRequests(8)
                .withProfile(LoadProfile.constant(2, 60_000L))
                .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target").withBody("req-body")));

            assertThat(orchestrator.start(scenario, sender), is(nullValue()));
            assertThat(awaitAtLeast(sender, 8, 5_000L), is(true));
            Thread.sleep(100);

            Collection<MetricData> collected = reader.collectAllMetrics();
            Set<String> names = collected.stream().map(MetricData::getName).collect(Collectors.toSet());
            // Durable instruments (counters/histogram) are present after the run. The observable
            // gauges (active_vus/inflight) only emit while a run is live (no points after completion),
            // which is asserted by the live-gauge test below.
            assertThat(names, hasItem("mock_server_load_request_duration_seconds"));
            assertThat(names, hasItem("mock_server_load_requests"));
            assertThat(names, hasItem("mock_server_load_request_bytes"));
            assertThat(names, hasItem("mock_server_load_response_bytes"));
            assertThat(names, hasItem("mock_server_load_iterations"));

            long requestCount = collected.stream()
                .filter(m -> m.getName().equals("mock_server_load_requests"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mock_server_load_requests not exported"))
                .getLongSumData().getPoints().stream()
                .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue).sum();
            assertThat(requestCount, greaterThanOrEqualTo(8L));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void liveGaugesReadActiveRunAndDrainWhenStopped() throws Exception {
        enableMetrics(null);
        // Never-completing sender keeps requests in flight so the gauges read live, non-zero values.
        Function<org.mockserver.model.HttpRequest, CompletableFuture<org.mockserver.model.HttpResponse>> blocking =
            req -> new CompletableFuture<>();
        LoadScenario scenario = new LoadScenario()
            .withName("gauge-load")
            .withProfile(LoadProfile.constant(3, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, blocking), is(nullValue()));
        String runId = orchestrator.getStatus().runId;

        // The Prometheus active-VU gauge reports the live population for this run while active.
        long deadline = System.currentTimeMillis() + 5_000L;
        Metrics.LoadGaugeKey key = new Metrics.LoadGaugeKey("gauge-load", runId);
        while (Metrics.getLoadActiveVus().getOrDefault(key, 0) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(Metrics.getLoadActiveVus().getOrDefault(key, 0), greaterThan(0));

        orchestrator.stop();
        // Once stopped, the readers are cleared so the gauges report nothing.
        assertThat(Metrics.getLoadActiveVus().isEmpty(), is(true));
        assertThat(Metrics.getLoadInflightRequests().isEmpty(), is(true));
    }

    @Test
    public void statusDtoPercentilesPopulateFromHistogram() throws Exception {
        enableMetrics(null);
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("pctl-load")
            .withMaxRequests(20)
            .withProfile(LoadProfile.constant(2, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 20, 5_000L), is(true));
        Thread.sleep(150);

        LoadScenarioOrchestrator.LoadScenarioStatus status = orchestrator.getStatus();
        // Percentile fields are derived from the histogram buckets; with sub-second latencies they
        // resolve to a small finite millisecond value (>= 0) and are queryable identically.
        assertThat(status.p50Millis, greaterThanOrEqualTo(0L));
        assertThat(status.p95Millis, greaterThanOrEqualTo(status.p50Millis));
        assertThat(status.p99Millis, greaterThanOrEqualTo(status.p95Millis));
        // The percentile upper bound never exceeds the largest histogram bucket (10s -> 10000ms).
        assertThat(status.p99Millis, lessThanOrEqualTo(10_000L));
    }

    @Test
    public void durationHistogramHasObservationsForRun() throws Exception {
        enableMetrics(null);
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("hist-load")
            .withMaxRequests(10)
            .withProfile(LoadProfile.constant(2, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 10, 5_000L), is(true));
        Thread.sleep(100);

        assertThat(scrapeContains("mock_server_load_request_duration_seconds"), is(true));
    }

    @Test
    public void completedRunSeriesRetainedThenEvictedWhenNextRunStarts() throws Exception {
        enableMetrics(null);

        // --- Run A to completion ---
        RecordingSender senderA = new RecordingSender();
        LoadScenario scenarioA = new LoadScenario()
            .withName("evict-load")
            .withMaxRequests(5)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenarioA, senderA), is(nullValue()));
        assertThat(awaitAtLeast(senderA, 5, 5_000L), is(true));
        // Drive a tick so the maxRequests-reached completion transition runs and the run terminates.
        long deadline = System.currentTimeMillis() + 5_000L;
        orchestrator.tickNow();
        while (org.mockserver.load.LoadScenarioState.COMPLETED != orchestrator.getStatus().state && System.currentTimeMillis() < deadline) {
            orchestrator.tickNow();
            Thread.sleep(5);
        }
        String runIdA = orchestrator.getStatus().runId;
        assertThat(orchestrator.getStatus().state, is(org.mockserver.load.LoadScenarioState.COMPLETED));

        // A's durable series remain scrapeable after completion (so the final totals survive a scrape).
        assertThat("run A series present after completion",
            runIdPresentOnLoadRequests(runIdA), is(true));

        // --- Start run B ---
        RecordingSender senderB = new RecordingSender();
        LoadScenario scenarioB = new LoadScenario()
            .withName("evict-load")
            .withMaxRequests(5)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenarioB, senderB), is(nullValue()));
        String runIdB = orchestrator.getStatus().runId;
        assertThat(runIdB, is(not(runIdA)));
        assertThat(awaitAtLeast(senderB, 5, 5_000L), is(true));
        Thread.sleep(100);

        // Starting B evicts A's series (bounded to <=1 completed run) while B's series now exist.
        assertThat("run A series evicted once run B started",
            runIdPresentOnLoadRequests(runIdA), is(false));
        assertThat("run B series present", runIdPresentOnLoadRequests(runIdB), is(true));
    }

    /** True if any {@code mock_server_load_requests} datapoint carries the given {@code run_id} label. */
    private static boolean runIdPresentOnLoadRequests(String runId) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals("mock_server_load_requests")
                && snapshot instanceof CounterSnapshot counterSnapshot) {
                for (CounterSnapshot.CounterDataPointSnapshot dp : counterSnapshot.getDataPoints()) {
                    if (runId.equals(dp.getLabels().get("run_id"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean scrapeContains(String name) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
