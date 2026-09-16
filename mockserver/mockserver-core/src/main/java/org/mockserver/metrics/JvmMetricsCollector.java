package org.mockserver.metrics;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot.GaugeDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Exposes JVM runtime metrics (heap / non-heap memory, threads, GC) as
 * Prometheus gauges so the dashboard Metrics view and Grafana can chart
 * MockServer's process health. Registered once alongside
 * {@link BuildInfoCollector} when metrics are enabled.
 * <p>
 * Reads only JDK {@code java.lang.management} MX beans — no extra dependency —
 * and is read-only/allocation-light, so scraping it has negligible overhead.
 * Values are sampled fresh on each {@link #collect()} (i.e. each scrape).
 */
public class JvmMetricsCollector implements MultiCollector {

    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private static final String MEMORY_USED = "jvm_memory_used_bytes";
    private static final String MEMORY_COMMITTED = "jvm_memory_committed_bytes";
    private static final String MEMORY_MAX = "jvm_memory_max_bytes";
    private static final String THREADS_CURRENT = "jvm_threads_current";
    private static final String THREADS_DAEMON = "jvm_threads_daemon";
    private static final String GC_COUNT = "jvm_gc_collection_count";
    private static final String GC_SECONDS = "jvm_gc_collection_seconds_sum";
    // Info-style gauge (constant 1, meaning carried by labels) exposing what the
    // RUNNING JVM actually is — the JDK build and the garbage collector(s) in use.
    // Neither is derivable from any other metric here, yet both are exactly the
    // configuration a stored perf run must record about itself (a run measured
    // under ZGC+8GB must be distinguishable from one under G1). Resolving them from
    // the live process (MX beans + system properties) is the only faithful source:
    // a second JVM launched from the same image can pick a different ergonomic GC
    // because it does not inherit the entrypoint's heap sizing. Mirrors
    // {@link BuildInfoCollector}'s label-carried-value pattern.
    private static final String RUNTIME_INFO = "jvm_runtime_info";

    @Override
    public MetricSnapshots collect() {
        List<MetricSnapshot> snapshots = new ArrayList<>();

        MemoryUsage heap = MEMORY.getHeapMemoryUsage();
        MemoryUsage nonHeap = MEMORY.getNonHeapMemoryUsage();
        snapshots.add(areaGauge(MEMORY_USED, "JVM memory used in bytes", heap.getUsed(), nonHeap.getUsed()));
        snapshots.add(areaGauge(MEMORY_COMMITTED, "JVM memory committed in bytes", heap.getCommitted(), nonHeap.getCommitted()));
        snapshots.add(areaGauge(MEMORY_MAX, "JVM memory max in bytes (-1 if undefined)", heap.getMax(), nonHeap.getMax()));

        snapshots.add(simpleGauge(THREADS_CURRENT, "Current live thread count", THREADS.getThreadCount()));
        snapshots.add(simpleGauge(THREADS_DAEMON, "Daemon thread count", THREADS.getDaemonThreadCount()));

        long gcCount = 0;
        long gcTimeMillis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count > 0) {
                gcCount += count;
            }
            if (time > 0) {
                gcTimeMillis += time;
            }
        }
        snapshots.add(simpleGauge(GC_COUNT, "Total number of GC collections across all collectors", gcCount));
        snapshots.add(simpleGauge(GC_SECONDS, "Total GC time across all collectors in seconds", gcTimeMillis / 1000.0));

        snapshots.add(runtimeInfoGauge());

        return new MetricSnapshots(snapshots);
    }

    private static GaugeSnapshot runtimeInfoGauge() {
        String gc = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .collect(Collectors.joining(","));
        return GaugeSnapshot.builder()
            .name(RUNTIME_INFO)
            .help("Running JVM build and garbage-collector information (value always 1; meaning is in the labels)")
            .dataPoint(GaugeDataPointSnapshot.builder()
                .value(1)
                .labels(Labels.of(
                    "gc", label(gc),
                    "java_runtime_version", label(System.getProperty("java.runtime.version")),
                    "java_vendor", label(System.getProperty("java.vendor")),
                    "java_version", label(System.getProperty("java.version")),
                    "vm_name", label(System.getProperty("java.vm.name"))
                ))
                .build())
            .build();
    }

    private static String label(String value) {
        return (value != null && !value.isEmpty()) ? value : "unknown";
    }

    private static GaugeSnapshot areaGauge(String name, String help, long heapValue, long nonHeapValue) {
        return GaugeSnapshot.builder()
            .name(name)
            .help(help)
            .dataPoint(GaugeDataPointSnapshot.builder().value(heapValue).labels(Labels.of("area", "heap")).build())
            .dataPoint(GaugeDataPointSnapshot.builder().value(nonHeapValue).labels(Labels.of("area", "nonheap")).build())
            .build();
    }

    private static GaugeSnapshot simpleGauge(String name, String help, double value) {
        return GaugeSnapshot.builder()
            .name(name)
            .help(help)
            .dataPoint(GaugeDataPointSnapshot.builder().value(value).build())
            .build();
    }

    @Override
    public List<String> getPrometheusNames() {
        return Arrays.asList(
            MEMORY_USED, MEMORY_COMMITTED, MEMORY_MAX,
            THREADS_CURRENT, THREADS_DAEMON,
            GC_COUNT, GC_SECONDS, RUNTIME_INFO
        );
    }
}
