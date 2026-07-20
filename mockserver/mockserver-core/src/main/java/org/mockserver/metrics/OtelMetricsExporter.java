package org.mockserver.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import org.mockserver.configuration.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Optional exporter that publishes MockServer's <em>explicitly-defined</em>
 * metrics (the same {@link Metrics.Name} gauges exposed for Prometheus) via
 * OpenTelemetry OTLP. Off unless {@code mockserver.otelMetricsEnabled} is set.
 * <p>
 * Deliberately metrics-only: it registers one OTel observable gauge per
 * {@link Metrics.Name} whose callback reads the current {@link Metrics#get}
 * value at each collection. No spans, no auto-instrumentation. The OTel SDK is
 * self-configured here (MockServer usually runs standalone), using the OTLP
 * HTTP/protobuf exporter with the JDK HttpClient sender.
 */
public class OtelMetricsExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtelMetricsExporter.class);

    private final SdkMeterProvider meterProvider;

    private OtelMetricsExporter(SdkMeterProvider meterProvider) {
        this.meterProvider = meterProvider;
    }

    /**
     * Start the exporter if enabled in configuration, returning the running
     * instance, or {@code null} if disabled or startup failed (fail-soft —
     * telemetry must never prevent the server from running).
     */
    public static OtelMetricsExporter startIfEnabled() {
        return startIfEnabled(null);
    }

    /**
     * As {@link #startIfEnabled()} but preferring the values carried on the supplied
     * {@link org.mockserver.configuration.Configuration} instance, falling back to the static
     * {@link ConfigurationProperties} store for each value the instance leaves unset. This is what
     * makes OTLP metric export settings applied programmatically or over
     * {@code PUT /mockserver/configuration} actually take effect, rather than round-tripping
     * through the DTO and being silently ignored here.
     *
     * @param configuration the live server configuration, or {@code null} to read the static store
     */
    public static OtelMetricsExporter startIfEnabled(org.mockserver.configuration.Configuration configuration) {
        boolean enabled = configuration != null
            ? configuration.otelMetricsEnabled()
            : ConfigurationProperties.otelMetricsEnabled();
        if (!enabled) {
            return null;
        }
        try {
            String configuredEndpoint = configuration != null
                ? configuration.otelEndpoint()
                : ConfigurationProperties.otelEndpoint();
            String endpoint = org.mockserver.telemetry.OtelEndpoints.metrics(configuredEndpoint);
            // Resolve aggregation temporality — fail-safe: only the explicit "delta" opts in, any
            // unknown/blank value keeps the previous cumulative behaviour. deltaPreferred() yields
            // delta for counters/histograms and keeps gauges cumulative (OTLP-only concept).
            String temporality = configuration != null
                ? configuration.otelMetricsTemporality()
                : ConfigurationProperties.otelMetricsTemporality();
            boolean delta = isDeltaTemporality(temporality);
            long intervalSeconds = configuration != null
                ? configuration.otelMetricsExportIntervalSeconds()
                : ConfigurationProperties.otelMetricsExportIntervalSeconds();
            OtlpHttpMetricExporterBuilder builder = endpoint != null
                ? OtlpHttpMetricExporter.builder().setEndpoint(endpoint)
                : OtlpHttpMetricExporter.builder();
            if (delta) {
                builder.setAggregationTemporalitySelector(io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector.deltaPreferred());
            }
            OtlpHttpMetricExporter otlpExporter = builder.build();
            MetricReader reader = PeriodicMetricReader.builder(otlpExporter)
                .setInterval(Duration.ofSeconds(intervalSeconds))
                .build();
            OtelMetricsExporter exporter = startWithReader(reader);
            LOGGER.info("OpenTelemetry metrics export enabled (endpoint {}, interval {}s, temporality {})",
                endpoint == null || endpoint.isEmpty() ? "default" : endpoint,
                intervalSeconds,
                delta ? "delta" : "cumulative");
            return exporter;
        } catch (Exception e) {
            LOGGER.warn("failed to start OpenTelemetry metrics export ({}); continuing without it", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve whether the configured temporality value selects delta. Fail-safe: only the literal
     * {@code delta} (trimmed, case-insensitive, locale-independent) opts in; {@code null}, blank,
     * {@code cumulative}, and any unknown value keep cumulative. Package-private for unit testing.
     */
    static boolean isDeltaTemporality(String value) {
        return value != null && "delta".equals(value.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Build a meter provider with the given reader and register observable
     * instruments:
     * <ul>
     *   <li>One gauge per {@link Metrics.Name} (existing behaviour).</li>
     *   <li>JVM memory, thread, and GC gauges (same data as
     *       {@link JvmMetricsCollector} on the Prometheus side).</li>
     *   <li>A gauge mirroring the slow-request counter so OTLP-only consumers
     *       can observe it without a Prometheus scrape.</li>
     *   <li>A gauge mirroring the per-fault-type chaos-injection counter.</li>
     *   <li>A gauge mirroring the active service-scoped chaos count.</li>
     *   <li>A histogram for request-handling duration (seconds), fed from the
     *       same observation path as the Prometheus histogram.</li>
     * </ul>
     * Visible for testing (a test can pass an in-memory reader instead of the
     * OTLP periodic reader).
     */
    public static OtelMetricsExporter startWithReader(MetricReader reader) {
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .setResource(Resource.getDefault().merge(
                Resource.create(Attributes.of(stringKey("service.name"), "mockserver"))))
            .registerMetricReader(reader)
            .build();
        Meter meter = provider.get("org.mockserver");
        for (Metrics.Name name : Metrics.Name.values()) {
            meter.gaugeBuilder(name.name().toLowerCase())
                .setDescription(name.description)
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(Metrics.get(name)));
        }
        registerJvmMetrics(meter);
        registerMonotonicTotalCounters(meter);
        registerSlowRequestCounter(meter);
        registerChaosCounter(meter);
        registerActiveServiceChaosGauge(meter);
        registerRequestDurationHistogram(meter);
        registerLoadMetrics(meter);
        registerLlmOptimisationGauges(meter);
        return new OtelMetricsExporter(provider);
    }

    private static void registerJvmMetrics(Meter meter) {
        // area label values match JvmMetricsCollector ("heap" / "nonheap" — no hyphen)
        Attributes heapAttr = Attributes.of(AttributeKey.stringKey("area"), "heap");
        Attributes nonHeapAttr = Attributes.of(AttributeKey.stringKey("area"), "nonheap");

        meter.gaugeBuilder("jvm_memory_used_bytes")
            .setDescription("JVM memory used bytes, labeled by area (heap/nonheap)")
            .setUnit("By")
            .ofLongs()
            .buildWithCallback(m -> {
                MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
                m.record(mem.getHeapMemoryUsage().getUsed(), heapAttr);
                m.record(mem.getNonHeapMemoryUsage().getUsed(), nonHeapAttr);
            });
        meter.gaugeBuilder("jvm_memory_committed_bytes")
            .setDescription("JVM memory committed bytes, labeled by area (heap/nonheap)")
            .setUnit("By")
            .ofLongs()
            .buildWithCallback(m -> {
                MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
                m.record(mem.getHeapMemoryUsage().getCommitted(), heapAttr);
                m.record(mem.getNonHeapMemoryUsage().getCommitted(), nonHeapAttr);
            });
        meter.gaugeBuilder("jvm_memory_max_bytes")
            .setDescription("JVM memory max bytes, labeled by area (-1 if undefined)")
            .setUnit("By")
            .ofLongs()
            .buildWithCallback(m -> {
                MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
                m.record(mem.getHeapMemoryUsage().getMax(), heapAttr);
                m.record(mem.getNonHeapMemoryUsage().getMax(), nonHeapAttr);
            });

        meter.gaugeBuilder("jvm_threads_current")
            .setDescription("Current JVM thread count")
            .ofLongs()
            .buildWithCallback(m ->
                m.record(ManagementFactory.getThreadMXBean().getThreadCount()));
        meter.gaugeBuilder("jvm_threads_daemon")
            .setDescription("Daemon JVM thread count")
            .ofLongs()
            .buildWithCallback(m ->
                m.record(ManagementFactory.getThreadMXBean().getDaemonThreadCount()));

        // Per-collector breakdown (richer than the Prometheus-side aggregate).
        // Guard the -1 sentinel: JDK spec allows -1 when a collector exposes no stats.
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        meter.gaugeBuilder("jvm_gc_collection_count")
            .setDescription("Total GC collection count, labeled by collector name")
            .ofLongs()
            .buildWithCallback(m -> {
                for (GarbageCollectorMXBean gc : gcBeans) {
                    long count = gc.getCollectionCount();
                    if (count >= 0) {
                        m.record(count, Attributes.of(AttributeKey.stringKey("gc"), gc.getName()));
                    }
                }
            });
        meter.gaugeBuilder("jvm_gc_collection_seconds_sum")
            .setDescription("Total GC collection time in seconds, labeled by collector name")
            .setUnit("s")
            .buildWithCallback(m -> {
                for (GarbageCollectorMXBean gc : gcBeans) {
                    long ms = gc.getCollectionTime();
                    if (ms >= 0) {
                        m.record(ms / 1000.0, Attributes.of(AttributeKey.stringKey("gc"), gc.getName()));
                    }
                }
            });
    }

    /**
     * Mirror the five dual-published {@code _total} monotonic counters to OTLP as observable
     * counters (delta temporality applies), so OTLP-only consumers get the proper monotonic totals
     * without a Prometheus scrape. Reads the single source of truth on {@link Metrics} so the OTLP
     * series can never drift from the Prometheus registration. The per-{@link Metrics.Name} gauge
     * loop above still mirrors the legacy {@code *_count} gauges unchanged; these add the {@code
     * _total} series alongside them.
     */
    private static void registerMonotonicTotalCounters(Meter meter) {
        Metrics.MONOTONIC_TOTAL_COUNTER_NAMES.forEach((name, counterName) ->
            meter.counterBuilder(counterName + "_total")
                .setDescription(name.description + " (monotonic counter; mirrors Prometheus counter)")
                .buildWithCallback(m -> m.record(Metrics.getMonotonicTotalCount(name))));
    }

    private static void registerSlowRequestCounter(Meter meter) {
        // Observable *counter* (not gauge): it mirrors a monotonic _total, so delta temporality applies.
        meter.counterBuilder("mock_server_slow_requests_total")
            .setDescription("Total forwarded requests that exceeded the slow-request threshold (mirrors Prometheus counter)")
            .buildWithCallback(m -> m.record(Metrics.getSlowRequestCount()));
    }

    private static void registerChaosCounter(Meter meter) {
        // Observable *counter* (not gauge): it mirrors a monotonic _total, so delta temporality applies.
        meter.counterBuilder("mock_server_http_chaos_injected_total")
            .setDescription("Total HTTP chaos faults injected by type (mirrors Prometheus counter)")
            .buildWithCallback(m -> {
                // Mirror the full documented fault-type set (the single source of truth on Metrics),
                // not a hardcoded subset, so no fault type is silently dropped from OTLP export.
                for (String faultType : Metrics.CHAOS_FAULT_TYPES) {
                    m.record(Metrics.getHttpChaosInjectedCount(faultType),
                        Attributes.of(AttributeKey.stringKey("fault_type"), faultType));
                }
            });
    }

    private static void registerActiveServiceChaosGauge(Meter meter) {
        meter.gaugeBuilder("mock_server_active_service_chaos")
            .setDescription("Number of active service-scoped chaos profiles configured with each fault type (mirrors Prometheus gauge)")
            .ofLongs()
            .buildWithCallback(m ->
                Metrics.getActiveServiceChaosCountByFaultType().forEach((faultType, count) ->
                    m.record(count, Attributes.of(AttributeKey.stringKey("fault_type"), faultType))));
    }

    private static void registerRequestDurationHistogram(Meter meter) {
        io.opentelemetry.api.metrics.DoubleHistogram requestDuration = meter.histogramBuilder("mock_server_request_duration_seconds")
            .setDescription("MockServer request handling duration in seconds")
            .setUnit("s")
            .build();
        Metrics.registerOtelRequestDurationHistogram(requestDuration);
    }

    /**
     * Mirror the entire {@code mock_server_load_*} family to OTLP: histogram→Histogram,
     * counters→Counter, the two live gauges→ObservableGauge. The per-measurement attributes (the
     * structured labels plus arbitrary custom labels) are attached by {@link Metrics} at record
     * time. First-class — gated only by the existing OTEL enablement, not an extra flag.
     */
    private static void registerLoadMetrics(Meter meter) {
        io.opentelemetry.api.metrics.DoubleHistogram duration = meter.histogramBuilder("mock_server_load_request_duration_seconds")
            .setDescription("Load-scenario request duration in seconds")
            .setUnit("s")
            .build();
        io.opentelemetry.api.metrics.LongCounter requests = meter.counterBuilder("mock_server_load_requests")
            .setDescription("Total load-scenario requests")
            .build();
        io.opentelemetry.api.metrics.LongCounter requestBytes = meter.counterBuilder("mock_server_load_request_bytes")
            .setDescription("Total load-scenario request body bytes")
            .setUnit("By")
            .build();
        io.opentelemetry.api.metrics.LongCounter responseBytes = meter.counterBuilder("mock_server_load_response_bytes")
            .setDescription("Total load-scenario response body bytes")
            .setUnit("By")
            .build();
        io.opentelemetry.api.metrics.LongCounter iterations = meter.counterBuilder("mock_server_load_iterations")
            .setDescription("Total completed load-scenario iterations")
            .build();
        io.opentelemetry.api.metrics.LongCounter throttled = meter.counterBuilder("mock_server_load_throttled")
            .setDescription("Total load-scenario dispatches throttled by the self-load guard")
            .build();
        io.opentelemetry.api.metrics.LongCounter errors = meter.counterBuilder("mock_server_load_errors")
            .setDescription("Total load-scenario request errors by kind")
            .build();
        io.opentelemetry.api.metrics.LongCounter checks = meter.counterBuilder("mock_server_load_checks")
            .setDescription("Total per-step load-scenario response checks by outcome")
            .build();
        Metrics.registerOtelLoadInstruments(duration, requests, requestBytes, responseBytes, iterations, throttled, errors, checks);

        meter.gaugeBuilder("mock_server_load_active_vus")
            .setDescription("Number of active virtual users in the running load scenario")
            .ofLongs()
            .buildWithCallback(m ->
                Metrics.getLoadActiveVus().forEach((key, count) ->
                    m.record(count, Attributes.of(
                        AttributeKey.stringKey("scenario"), key.scenario,
                        AttributeKey.stringKey("run_id"), key.runId))));
        meter.gaugeBuilder("mock_server_load_inflight_requests")
            .setDescription("Number of in-flight load-scenario requests")
            .ofLongs()
            .buildWithCallback(m ->
                Metrics.getLoadInflightRequests().forEach((key, count) ->
                    m.record(count, Attributes.of(
                        AttributeKey.stringKey("scenario"), key.scenario,
                        AttributeKey.stringKey("run_id"), key.runId))));
    }

    /**
     * Mirror the three single global LLM-optimisation gauges to OTLP (matching the Prometheus-side
     * GaugeWithCallback registration), so OTLP-only consumers see the latest optimisation verdict
     * without a Prometheus scrape. Each reads the cached snapshot at collection time. No labels —
     * single global series — so there is no per-model cardinality.
     */
    private static void registerLlmOptimisationGauges(Meter meter) {
        meter.gaugeBuilder("mock_server_llm_estimated_waste_usd")
            .setDescription("Estimated recoverable LLM spend (USD) from the latest optimisation report")
            .buildWithCallback(m -> m.record(Metrics.getLlmEstimatedWasteUsd()));
        meter.gaugeBuilder("mock_server_llm_cache_hit_ratio")
            .setDescription("Cache-hit ratio (0..1) from the latest LLM optimisation report")
            .buildWithCallback(m -> m.record(Metrics.getLlmCacheHitRatio()));
        meter.gaugeBuilder("mock_server_llm_one_shot_rate")
            .setDescription("One-shot rate (0..1) from the latest LLM optimisation report")
            .buildWithCallback(m -> m.record(Metrics.getLlmOneShotRate()));
    }

    /**
     * Stop exporting and release resources. Safe to call once.
     */
    public void stop() {
        Metrics.registerOtelRequestDurationHistogram(null);
        Metrics.registerOtelLoadInstruments(null, null, null, null, null, null, null, null);
        try {
            meterProvider.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.debug("error shutting down OpenTelemetry metrics export: {}", e.getMessage());
        }
    }
}
