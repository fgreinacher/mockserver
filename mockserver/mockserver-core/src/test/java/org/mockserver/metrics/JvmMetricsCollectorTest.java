package org.mockserver.metrics;

import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

public class JvmMetricsCollectorTest {

    @Test
    public void exposesHeapThreadsAndGcGauges() {
        MetricSnapshots snapshots = new JvmMetricsCollector().collect();

        GaugeSnapshot used = gauge(snapshots, "jvm_memory_used_bytes");
        assertThat(used, notNullValue());
        // one data point per area: heap + nonheap
        assertThat(used.getDataPoints().size(), is(2));
        double heapUsed = used.getDataPoints().stream()
            .filter(point -> "heap".equals(point.getLabels().get("area")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no heap data point"))
            .getValue();
        assertThat(heapUsed, greaterThan(0.0));

        assertThat(gauge(snapshots, "jvm_threads_current").getDataPoints().get(0).getValue(), greaterThan(0.0));
        assertThat(gauge(snapshots, "jvm_gc_collection_count"), notNullValue());
        assertThat(gauge(snapshots, "jvm_gc_collection_seconds_sum"), notNullValue());
    }

    @Test
    public void exposesCumulativeThreadAllocationCounter() {
        // HotSpot (the JVM these tests run on) implements com.sun.management
        // ThreadMXBean, so the allocation counter is present and positive — this
        // JVM has already allocated. On a JVM without the extension the collector
        // suppresses the metric entirely (never a fabricated zero); this test
        // asserts the supported path, which is the one the perf run measures under.
        MetricSnapshots snapshots = new JvmMetricsCollector().collect();

        GaugeSnapshot allocated = gauge(snapshots, "jvm_memory_allocated_bytes");
        assertThat(allocated, notNullValue());
        assertThat(allocated.getDataPoints().size(), is(1));
        assertThat(allocated.getDataPoints().get(0).getValue(), greaterThan(0.0));
    }

    @Test
    public void exposesRuntimeInfoWithGcAndJdkLabels() {
        MetricSnapshots snapshots = new JvmMetricsCollector().collect();

        GaugeSnapshot info = gauge(snapshots, "jvm_runtime_info");
        assertThat(info, notNullValue());
        // info-style gauge: a single data point whose value is a constant 1, with
        // the meaning carried entirely by the labels (matches BuildInfoCollector).
        assertThat(info.getDataPoints().size(), is(1));
        assertThat(info.getDataPoints().get(0).getValue(), is(1.0));

        // gc names the collector(s) actually in use (e.g. "G1 Young Generation,..."),
        // java_runtime_version is the JDK build — both non-empty for the running JVM.
        assertThat(info.getDataPoints().get(0).getLabels().get("gc"), notNullValue());
        assertThat(info.getDataPoints().get(0).getLabels().get("gc").isEmpty(), is(false));
        assertThat(info.getDataPoints().get(0).getLabels().get("java_runtime_version"), notNullValue());
        assertThat(info.getDataPoints().get(0).getLabels().get("java_runtime_version").isEmpty(), is(false));
        assertThat(info.getDataPoints().get(0).getLabels().get("java_version"), notNullValue());
        assertThat(info.getDataPoints().get(0).getLabels().get("vm_name"), notNullValue());
    }

    @Test
    public void listsItsPrometheusNames() {
        assertThat(new JvmMetricsCollector().getPrometheusNames(), hasItems(
            "jvm_memory_used_bytes", "jvm_memory_allocated_bytes", "jvm_threads_current", "jvm_gc_collection_count", "jvm_runtime_info"));
    }

    private static GaugeSnapshot gauge(MetricSnapshots snapshots, String name) {
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name)) {
                return (GaugeSnapshot) snapshot;
            }
        }
        return null;
    }
}
