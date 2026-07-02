package org.mockserver.metrics;

import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;

public class OtelMetricsExporterTest {

    // Serialise against MetricsTest and the other classes that mutate the process-global
    // PrometheusRegistry / static Metrics state. Under Surefire parallel=classes (the default-test
    // phase a developer hits when running -Dtest=MetricsTest,OtelMetricsExporterTest), two such
    // classes would otherwise run concurrently and race on registry clear()/re-registration,
    // producing order-dependent "metric absent / reads 0" flakes. This class already runs in the
    // sequential CI phase; the class-rule lock additionally makes ad-hoc parallel runs safe.
    @ClassRule
    public static final MetricsLock metricsLock = new MetricsLock();

    @Test
    public void resolvesDeltaTemporalityTrimmedAndCaseInsensitiveElseCumulative() {
        // delta opts in regardless of surrounding whitespace or case
        assertThat(OtelMetricsExporter.isDeltaTemporality("delta"), is(true));
        assertThat(OtelMetricsExporter.isDeltaTemporality("  delta  "), is(true));
        assertThat(OtelMetricsExporter.isDeltaTemporality("DELTA"), is(true));
        assertThat(OtelMetricsExporter.isDeltaTemporality(" Delta "), is(true));
        // everything else (default, unknown, blank, null) stays cumulative — fail-safe
        assertThat(OtelMetricsExporter.isDeltaTemporality("cumulative"), is(false));
        assertThat(OtelMetricsExporter.isDeltaTemporality("deltas"), is(false));
        assertThat(OtelMetricsExporter.isDeltaTemporality(""), is(false));
        assertThat(OtelMetricsExporter.isDeltaTemporality("   "), is(false));
        assertThat(OtelMetricsExporter.isDeltaTemporality(null), is(false));
    }

    @Test
    public void exportsExplicitMockServerMetricsAsObservableGauges() {
        // given — a known value in an explicitly-defined MockServer metric
        Metrics.clear(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        Metrics enabled = new Metrics(configuration().metricsEnabled(true));
        enabled.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        int expected = Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT);

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            // when — OTel collects (triggers the observable-gauge callbacks)
            Collection<MetricData> collected = reader.collectAllMetrics();

            // then — there is a gauge per Metrics.Name, and the requests gauge reads
            // the same value the Prometheus metric holds
            assertThat(collected.size(), greaterThanOrEqualTo(Metrics.Name.values().length));
            MetricData requests = collected.stream()
                .filter(m -> m.getName().equals("requests_received_count"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("requests_received_count gauge not exported"));
            long value = requests.getLongGaugeData().getPoints().iterator().next().getValue();
            assertThat((int) value, is(expected));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void exportsActiveServiceChaosGauge() {
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance()
            .put("upstream.svc", org.mockserver.model.HttpChaosProfile.httpChaosProfile().withErrorStatus(503));

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            Collection<MetricData> collected = reader.collectAllMetrics();
            Set<String> names = collected.stream().map(MetricData::getName).collect(Collectors.toSet());

            assertThat(names, hasItem("mock_server_active_service_chaos"));

            MetricData gauge = collected.stream()
                .filter(m -> m.getName().equals("mock_server_active_service_chaos"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mock_server_active_service_chaos not exported"));
            // labeled by fault_type: the registered profile injects 'error'
            long errorCount = gauge.getLongGaugeData().getPoints().stream()
                .filter(p -> "error".equals(p.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("fault_type"))))
                .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                .findFirst()
                .orElse(-1);
            assertThat(errorCount, is(1L));
            long dropCount = gauge.getLongGaugeData().getPoints().stream()
                .filter(p -> "drop".equals(p.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("fault_type"))))
                .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                .findFirst()
                .orElse(-1);
            assertThat(dropCount, is(0L));
        } finally {
            exporter.stop();
            org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        }
    }

    @Test
    public void exportsJvmAndSlowRequestMetrics() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            Collection<MetricData> collected = reader.collectAllMetrics();
            Set<String> names = collected.stream().map(MetricData::getName).collect(Collectors.toSet());

            // JVM memory metrics
            assertThat(names, hasItem("jvm_memory_used_bytes"));
            assertThat(names, hasItem("jvm_memory_committed_bytes"));
            assertThat(names, hasItem("jvm_memory_max_bytes"));

            // JVM thread metrics
            assertThat(names, hasItem("jvm_threads_current"));
            assertThat(names, hasItem("jvm_threads_daemon"));

            // JVM GC metrics
            assertThat(names, hasItem("jvm_gc_collection_count"));
            assertThat(names, hasItem("jvm_gc_collection_seconds_sum"));

            // Slow-request counter mirror
            assertThat(names, hasItem("mock_server_slow_requests_total"));

            // JVM memory values must be positive (heap is always allocated)
            MetricData heapUsed = collected.stream()
                .filter(m -> m.getName().equals("jvm_memory_used_bytes"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("jvm_memory_used_bytes not exported"));
            long heapUsedValue = heapUsed.getLongGaugeData().getPoints().stream()
                .filter(p -> "heap".equals(p.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("area"))))  // "nonheap" is the other label value
                .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("heap area point not found in jvm_memory_used_bytes"));
            assertThat(heapUsedValue > 0, is(true));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void exportsChaosCounterByFaultType() {
        // given — increment chaos counters (requires metrics enabled)
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementHttpChaosInjected("error");
        Metrics.incrementHttpChaosInjected("error");
        Metrics.incrementHttpChaosInjected("latency");

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            Collection<MetricData> collected = reader.collectAllMetrics();
            Set<String> names = collected.stream().map(MetricData::getName).collect(Collectors.toSet());

            assertThat(names, hasItem("mock_server_http_chaos_injected_total"));

            MetricData chaosMetric = collected.stream()
                .filter(m -> m.getName().equals("mock_server_http_chaos_injected_total"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mock_server_http_chaos_injected_total not exported"));

            // it is now an observable (monotonic) Sum, not a Gauge, so delta temporality can apply
            assertThat(chaosMetric.getType(), is(io.opentelemetry.sdk.metrics.data.MetricDataType.LONG_SUM));

            // verify error count = 2
            long errorCount = chaosMetric.getLongSumData().getPoints().stream()
                .filter(p -> "error".equals(p.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("fault_type"))))
                .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                .findFirst()
                .orElse(-1);
            assertThat(errorCount, is(2L));

            // verify latency count = 1
            long latencyCount = chaosMetric.getLongSumData().getPoints().stream()
                .filter(p -> "latency".equals(p.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("fault_type"))))
                .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                .findFirst()
                .orElse(-1);
            assertThat(latencyCount, is(1L));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void exportsLlmOptimisationGauges() {
        // given — a known optimisation snapshot (as a built report would push)
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(configuration().metricsEnabled(true));
        Metrics.updateLlmOptimisationSnapshot(1.42, 0.5, 0.6667);

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            Collection<MetricData> collected = reader.collectAllMetrics();
            Set<String> names = collected.stream().map(MetricData::getName).collect(Collectors.toSet());

            assertThat(names, hasItem("mock_server_llm_estimated_waste_usd"));
            assertThat(names, hasItem("mock_server_llm_cache_hit_ratio"));
            assertThat(names, hasItem("mock_server_llm_one_shot_rate"));

            assertThat(doubleGaugeValue(collected, "mock_server_llm_estimated_waste_usd"), is(1.42));
            assertThat(doubleGaugeValue(collected, "mock_server_llm_cache_hit_ratio"), is(0.5));
            assertThat(doubleGaugeValue(collected, "mock_server_llm_one_shot_rate"), is(0.6667));
        } finally {
            exporter.stop();
            Metrics.clear();
        }
    }

    private static double doubleGaugeValue(Collection<MetricData> collected, String name) {
        MetricData metric = collected.stream()
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError(name + " not exported"));
        return metric.getDoubleGaugeData().getPoints().iterator().next().getValue();
    }

    @Test
    public void exportsRequestDurationHistogram() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            // when — observe a duration (routed through the OTel histogram registered by the exporter)
            Metrics.observeRequestDurationSeconds(0.1);
            Metrics.observeRequestDurationSeconds(0.25);

            Collection<MetricData> collected = reader.collectAllMetrics();
            Set<String> names = collected.stream().map(MetricData::getName).collect(Collectors.toSet());

            // then — histogram is present
            assertThat(names, hasItem("mock_server_request_duration_seconds"));

            MetricData histogramMetric = collected.stream()
                .filter(m -> m.getName().equals("mock_server_request_duration_seconds"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mock_server_request_duration_seconds histogram not exported"));

            // verify the histogram has data points with count >= 2
            long totalCount = histogramMetric.getHistogramData().getPoints().stream()
                .mapToLong(io.opentelemetry.sdk.metrics.data.HistogramPointData::getCount)
                .sum();
            assertThat(totalCount, greaterThanOrEqualTo(2L));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void stopClearsOtelHistogramReference() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);

        // record something before stop
        Metrics.observeRequestDurationSeconds(0.05);

        exporter.stop();

        // after stop, observing should not throw (OTel histogram is null)
        Metrics.observeRequestDurationSeconds(0.1);
    }

    @Test
    public void counterMirrorsSurfaceAsMonotonicSumsNotGauges() {
        // the two _total mirrors were reclassified from observable gauges to observable counters
        // so OTLP delta temporality can apply; a default (cumulative) reader must see them as Sums
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            Collection<MetricData> collected = reader.collectAllMetrics();

            MetricData slow = metricByName(collected, "mock_server_slow_requests_total");
            assertThat(slow.getType(), is(io.opentelemetry.sdk.metrics.data.MetricDataType.LONG_SUM));
            assertThat(slow.getLongSumData().isMonotonic(), is(true));
            assertThat(slow.getLongSumData().getAggregationTemporality(),
                is(io.opentelemetry.sdk.metrics.data.AggregationTemporality.CUMULATIVE));

            MetricData chaos = metricByName(collected, "mock_server_http_chaos_injected_total");
            assertThat(chaos.getType(), is(io.opentelemetry.sdk.metrics.data.MetricDataType.LONG_SUM));
            assertThat(chaos.getLongSumData().isMonotonic(), is(true));

            // a genuine gauge is still a Gauge (not reclassified)
            MetricData activeChaos = metricByName(collected, "mock_server_active_service_chaos");
            assertThat(activeChaos.getType(), is(io.opentelemetry.sdk.metrics.data.MetricDataType.LONG_GAUGE));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void deltaReaderReportsDeltaTemporalityForCountersAndHistogramButGaugesStayGauges() {
        // a delta in-memory reader mirrors what deltaPreferred() on the OTLP exporter produces:
        // counters/histograms become delta, gauges are unaffected
        InMemoryMetricReader reader = InMemoryMetricReader.createDelta();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            Metrics.observeRequestDurationSeconds(0.1);
            Collection<MetricData> collected = reader.collectAllMetrics();

            MetricData slow = metricByName(collected, "mock_server_slow_requests_total");
            assertThat(slow.getLongSumData().getAggregationTemporality(),
                is(io.opentelemetry.sdk.metrics.data.AggregationTemporality.DELTA));

            MetricData chaos = metricByName(collected, "mock_server_http_chaos_injected_total");
            assertThat(chaos.getLongSumData().getAggregationTemporality(),
                is(io.opentelemetry.sdk.metrics.data.AggregationTemporality.DELTA));

            MetricData histogram = metricByName(collected, "mock_server_request_duration_seconds");
            assertThat(histogram.getHistogramData().getAggregationTemporality(),
                is(io.opentelemetry.sdk.metrics.data.AggregationTemporality.DELTA));

            // a genuine gauge is still a Gauge regardless of the delta reader
            MetricData activeChaos = metricByName(collected, "mock_server_active_service_chaos");
            assertThat(activeChaos.getType(), is(io.opentelemetry.sdk.metrics.data.MetricDataType.LONG_GAUGE));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void exportsMonotonicTotalCountersAsMonotonicSums() {
        // the five dual-published _total counters mirror to OTLP as observable monotonic Sums
        // (not gauges), alongside the legacy per-Name _count gauges which stay Gauges
        Metrics.resetAdditionalMetricsForTesting();
        Metrics enabled = new Metrics(configuration().metricsEnabled(true));
        enabled.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        enabled.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        enabled.increment(Metrics.Name.FORWARD_EXPECTATIONS_MATCHED_COUNT);

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            Collection<MetricData> collected = reader.collectAllMetrics();
            Set<String> names = collected.stream().map(MetricData::getName).collect(Collectors.toSet());

            // all five _total counters are exported
            assertThat(names, hasItem("mock_server_requests_received_total"));
            assertThat(names, hasItem("mock_server_expectations_not_matched_total"));
            assertThat(names, hasItem("mock_server_response_expectations_matched_total"));
            assertThat(names, hasItem("mock_server_forward_expectations_matched_total"));
            assertThat(names, hasItem("mock_server_llm_chaos_injected_total"));

            // the legacy _count gauge is still exported as a gauge (coexists)
            assertThat(names, hasItem("requests_received_count"));

            MetricData requests = metricByName(collected, "mock_server_requests_received_total");
            assertThat(requests.getType(), is(io.opentelemetry.sdk.metrics.data.MetricDataType.LONG_SUM));
            assertThat(requests.getLongSumData().isMonotonic(), is(true));
            long value = requests.getLongSumData().getPoints().iterator().next().getValue();
            assertThat(value, is(2L));

            MetricData legacyGauge = metricByName(collected, "requests_received_count");
            assertThat(legacyGauge.getType(), is(io.opentelemetry.sdk.metrics.data.MetricDataType.LONG_GAUGE));
        } finally {
            exporter.stop();
        }
    }

    private static MetricData metricByName(Collection<MetricData> collected, String name) {
        return collected.stream()
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError(name + " not exported"));
    }

    @Test
    public void disabledByDefaultReturnsNull() {
        // off unless configured — startIfEnabled reads mockserver.otelMetricsEnabled (default false)
        assertThat(OtelMetricsExporter.startIfEnabled() == null, is(true));
    }
}
