package org.mockserver.metrics.remotewrite;

import com.google.protobuf.CodedOutputStream;
import io.prometheus.metrics.model.snapshots.ClassicHistogramBuckets;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot.HistogramDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import io.prometheus.metrics.model.snapshots.Quantile;
import io.prometheus.metrics.model.snapshots.Quantiles;
import io.prometheus.metrics.model.snapshots.SummarySnapshot;
import io.prometheus.metrics.model.snapshots.SummarySnapshot.SummaryDataPointSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hand-encodes a {@link MetricSnapshots} into a Prometheus Remote-Write v1
 * {@code WriteRequest} protobuf, matching the Prometheus text-exposition naming
 * so the pushed series line up exactly with what {@code /mockserver/metrics}
 * serves. Pure and stateless — no network, no global state — so it is fully
 * unit-testable.
 * <p>
 * Message shapes (field numbers are normative for the remote-write protocol):
 * <pre>
 * WriteRequest { repeated TimeSeries timeseries = 1; }
 * TimeSeries   { repeated Label labels = 1; repeated Sample samples = 2; }
 * Label        { string name = 1; string value = 2; }
 * Sample       { double value = 1; int64 timestamp = 2; }
 * </pre>
 * Each nested message is built into its own {@code byte[]} and embedded in the
 * parent via {@link CodedOutputStream#writeByteArray(int, byte[])} — a
 * length-delimited {@code bytes} field is wire-identical to an embedded
 * message, which sidesteps manual size arithmetic.
 */
public class RemoteWriteV1Encoder implements RemoteWriteEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteWriteV1Encoder.class);

    private static final String NAME_LABEL = "__name__";

    @Override
    public byte[] encode(MetricSnapshots snapshots, long timestampMillis) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(body);
        try {
            for (MetricSnapshot snapshot : snapshots) {
                encodeSnapshot(out, snapshot, timestampMillis);
            }
            out.flush();
        } catch (IOException e) {
            // In-memory encoding does no real I/O; wrap defensively so the caller fails soft.
            throw new UncheckedIOException("failed to encode remote-write v1 body", e);
        }
        return body.toByteArray();
    }

    private void encodeSnapshot(CodedOutputStream out, MetricSnapshot snapshot, long ts) throws IOException {
        String base = snapshot.getMetadata().getPrometheusName();
        if (snapshot instanceof CounterSnapshot) {
            for (CounterSnapshot.CounterDataPointSnapshot dp : ((CounterSnapshot) snapshot).getDataPoints()) {
                out.writeByteArray(1, timeSeries(base + "_total", dp.getLabels(), null, null, dp.getValue(), ts));
            }
        } else if (snapshot instanceof GaugeSnapshot) {
            for (GaugeSnapshot.GaugeDataPointSnapshot dp : ((GaugeSnapshot) snapshot).getDataPoints()) {
                out.writeByteArray(1, timeSeries(base, dp.getLabels(), null, null, dp.getValue(), ts));
            }
        } else if (snapshot instanceof HistogramSnapshot) {
            encodeHistogram(out, base, (HistogramSnapshot) snapshot, ts);
        } else if (snapshot instanceof SummarySnapshot) {
            encodeSummary(out, base, (SummarySnapshot) snapshot, ts);
        } else {
            // Info / StateSet / Unknown / native-only histogram: no Prometheus-text sample
            // representation here — skip rather than throw, so no snapshot type can break export.
            LOGGER.debug("remote-write: skipping unsupported metric snapshot type {} for {}",
                snapshot.getClass().getSimpleName(), base);
        }
    }

    private void encodeHistogram(CodedOutputStream out, String base, HistogramSnapshot snapshot, long ts) throws IOException {
        for (HistogramDataPointSnapshot dp : snapshot.getDataPoints()) {
            if (!dp.hasClassicHistogramData()) {
                // Native (exponential) histograms have no classic _bucket{le} exposition — skip
                // the data point rather than emit misleading series. Never throw.
                LOGGER.debug("remote-write: skipping native-only histogram data point for {}", base);
                continue;
            }
            Labels labels = dp.getLabels();
            ClassicHistogramBuckets buckets = dp.getClassicBuckets();

            // Classic buckets store per-bucket (non-cumulative) counts sorted ascending by upper
            // bound; Prometheus _bucket{le} series are cumulative, so accumulate ascending.
            long total = dp.hasCount() ? dp.getCount() : sumBucketCounts(buckets);
            long cumulative = 0;
            boolean emittedPositiveInf = false;
            for (int i = 0; i < buckets.size(); i++) {
                cumulative += buckets.getCount(i);
                double upperBound = buckets.getUpperBound(i);
                boolean isPositiveInf = upperBound == Double.POSITIVE_INFINITY;
                // The +Inf bucket is always the total count.
                long value = isPositiveInf ? total : cumulative;
                out.writeByteArray(1, timeSeries(base + "_bucket", labels, "le", formatBucketBound(upperBound), value, ts));
                emittedPositiveInf |= isPositiveInf;
            }
            // Defensive: a well-formed classic histogram always has a +Inf bucket, but if the
            // model ever omits it, still emit le="+Inf" == total so the series is complete.
            if (!emittedPositiveInf) {
                out.writeByteArray(1, timeSeries(base + "_bucket", labels, "le", "+Inf", total, ts));
            }
            out.writeByteArray(1, timeSeries(base + "_count", labels, null, null, total, ts));
            if (dp.hasSum()) {
                out.writeByteArray(1, timeSeries(base + "_sum", labels, null, null, dp.getSum(), ts));
            }
        }
    }

    private void encodeSummary(CodedOutputStream out, String base, SummarySnapshot snapshot, long ts) throws IOException {
        for (SummaryDataPointSnapshot dp : snapshot.getDataPoints()) {
            Labels labels = dp.getLabels();
            Quantiles quantiles = dp.getQuantiles();
            for (int i = 0; i < quantiles.size(); i++) {
                Quantile q = quantiles.get(i);
                out.writeByteArray(1, timeSeries(base, labels, "quantile", formatBucketBound(q.getQuantile()), q.getValue(), ts));
            }
            if (dp.hasCount()) {
                out.writeByteArray(1, timeSeries(base + "_count", labels, null, null, dp.getCount(), ts));
            }
            if (dp.hasSum()) {
                out.writeByteArray(1, timeSeries(base + "_sum", labels, null, null, dp.getSum(), ts));
            }
        }
    }

    private static long sumBucketCounts(ClassicHistogramBuckets buckets) {
        long sum = 0;
        for (int i = 0; i < buckets.size(); i++) {
            sum += buckets.getCount(i);
        }
        return sum;
    }

    /**
     * Build one {@code TimeSeries} message (as bytes) carrying the mandatory
     * {@code __name__} label, the data point's own labels, an optional extra
     * label (e.g. {@code le} / {@code quantile}), and a single sample.
     */
    private static byte[] timeSeries(String name, Labels labels, String extraLabelName, String extraLabelValue, double value, long ts) throws IOException {
        // Remote-Write 1.0 requires the complete label set sorted lexicographically by label name.
        // Collect __name__, the data point's own labels, and any extra (le/quantile) label, then
        // sort by name before writing — strict receivers (Thanos Receive, Cortex/Mimir) reject or
        // misindex series whose labels are unsorted, and appending le/quantile last would break the
        // order for histograms whose own labels sort after "le" (method, upstream_host, scenario…).
        List<String[]> nameValues = new ArrayList<>();
        nameValues.add(new String[]{NAME_LABEL, name});
        for (int i = 0; i < labels.size(); i++) {
            nameValues.add(new String[]{labels.getPrometheusName(i), labels.getValue(i)});
        }
        if (extraLabelName != null) {
            nameValues.add(new String[]{extraLabelName, extraLabelValue});
        }
        nameValues.sort(Comparator.comparing((String[] nv) -> nv[0]));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream c = CodedOutputStream.newInstance(buffer);
        for (String[] nv : nameValues) {
            c.writeByteArray(1, label(nv[0], nv[1]));
        }
        c.writeByteArray(2, sample(value, ts));
        c.flush();
        return buffer.toByteArray();
    }

    private static byte[] label(String name, String value) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream c = CodedOutputStream.newInstance(buffer);
        c.writeString(1, name);
        c.writeString(2, value);
        c.flush();
        return buffer.toByteArray();
    }

    private static byte[] sample(double value, long ts) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream c = CodedOutputStream.newInstance(buffer);
        c.writeDouble(1, value);
        c.writeInt64(2, ts);
        c.flush();
        return buffer.toByteArray();
    }

    /**
     * Format a bucket upper bound / quantile the Prometheus way, matching the
     * client's own text-exposition {@code writeDouble}: {@code +Inf}/{@code -Inf}
     * for the infinities, otherwise {@link Double#toString(double)} (so
     * {@code 1.0} renders as {@code "1.0"}, exactly as {@code /mockserver/metrics}
     * emits it). Package-private for unit testing.
     */
    static String formatBucketBound(double bound) {
        if (bound == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (bound == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        return Double.toString(bound);
    }

    @Override
    public String contentType() {
        return "application/x-protobuf";
    }

    @Override
    public String protocolVersionHeader() {
        return "0.1.0";
    }
}
