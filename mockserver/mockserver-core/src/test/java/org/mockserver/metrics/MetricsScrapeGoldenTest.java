package org.mockserver.metrics;

import io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.model.Action;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Pins the exposition of the legacy {@code *_count} gauges and the dual-published {@code *_total}
 * counters byte for byte, in both the Prometheus text and OpenMetrics formats, after a fixed sequence
 * of increments, decrements, sets and clears. The golden files were produced by the implementation that
 * looked each metric up by name per call, so a change to how {@link Metrics} resolves its handles must
 * reproduce them exactly. OpenMetrics {@code _created} lines are wall-clock timestamps and are dropped.
 */
public class MetricsScrapeGoldenTest {

    @ClassRule
    public static final MetricsLock metricsLock = new MetricsLock();

    private static final Set<String> PINNED_METRIC_NAMES = new HashSet<>();

    static {
        Arrays.stream(Metrics.Name.values()).forEach(name -> PINNED_METRIC_NAMES.add(name.name().toLowerCase()));
        PINNED_METRIC_NAMES.addAll(Metrics.MONOTONIC_TOTAL_COUNTER_NAMES.values());
    }

    @Before
    public void resetStaticState() {
        Metrics.resetAdditionalMetricsForTesting();
    }

    @After
    public void clearStaticState() {
        Metrics.clear();
        Metrics.resetAdditionalMetricsForTesting();
    }

    @Test
    public void shouldScrapeCountGaugesAndTotalCountersExactlyAsBefore() throws Exception {
        Metrics metrics = new Metrics(configuration().metricsEnabled(true));
        Metrics.Name[] names = Metrics.Name.values();
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j <= i % 5; j++) {
                metrics.increment(names[i]);
            }
            if (i % 3 == 0) {
                metrics.decrement(names[i]);
            }
        }
        metrics.increment(Action.Type.RESPONSE);
        metrics.increment(Action.Type.FORWARD);
        metrics.decrement(Action.Type.FORWARD);
        metrics.set(Metrics.Name.WEBSOCKET_CALLBACK_CLIENTS_COUNT, 7);
        Metrics.clear(Metrics.Name.ERROR_ACTIONS_COUNT);

        assertGolden("org/mockserver/metrics/golden_metrics_scrape_after_updates");
    }

    @Test
    public void shouldZeroGaugesButNotTotalCountersOnClear() throws Exception {
        Metrics metrics = new Metrics(configuration().metricsEnabled(true));
        for (Metrics.Name name : Metrics.Name.values()) {
            metrics.increment(name);
            metrics.increment(name);
        }
        Metrics.clear();
        metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        Metrics.clearRequestAndExpectationMetrics();
        metrics.increment(Metrics.Name.RESPONSE_EXPECTATIONS_MATCHED_COUNT);

        assertGolden("org/mockserver/metrics/golden_metrics_scrape_after_clear");
        assertThat(Metrics.get(Metrics.Name.RESPONSE_EXPECTATIONS_MATCHED_COUNT), equalTo(1));
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.RESPONSE_EXPECTATIONS_MATCHED_COUNT), equalTo(3L));
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.REQUESTS_RECEIVED_COUNT), equalTo(3L));
    }

    @Test
    public void shouldNotRecordAnythingWhenMetricsDisabled() throws Exception {
        Metrics metrics = new Metrics(configuration().metricsEnabled(false));
        metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        metrics.decrement(Metrics.Name.FORWARD_ACTIONS_COUNT);
        metrics.set(Metrics.Name.WEBSOCKET_CALLBACK_CLIENTS_COUNT, 3);

        assertThat(scrape(PrometheusTextFormatWriter.create()), equalTo(""));
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.REQUESTS_RECEIVED_COUNT), equalTo(0L));
    }

    private void assertGolden(String resourceBase) throws Exception {
        assertThat(scrape(PrometheusTextFormatWriter.create()),
            equalTo(FileReader.readFileFromClassPathOrPath(resourceBase + ".txt")));
        assertThat(scrape(OpenMetricsTextFormatWriter.create()),
            equalTo(FileReader.readFileFromClassPathOrPath(resourceBase + ".openmetrics.txt")));
    }

    private static String scrape(Object writer) throws Exception {
        MetricSnapshots pinned = new MetricSnapshots(PrometheusRegistry.defaultRegistry.scrape().stream()
            .filter(snapshot -> PINNED_METRIC_NAMES.contains(snapshot.getMetadata().getName()))
            .toArray(MetricSnapshot[]::new));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (writer instanceof PrometheusTextFormatWriter) {
            ((PrometheusTextFormatWriter) writer).write(out, pinned);
        } else {
            ((OpenMetricsTextFormatWriter) writer).write(out, pinned);
        }
        String text = out.toString(StandardCharsets.UTF_8);
        return Arrays.stream(text.split("\n", -1))
            .filter(line -> !line.contains("_created"))
            .collect(Collectors.joining("\n"));
    }
}
