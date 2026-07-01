package org.mockserver.metrics.remotewrite;

import com.google.protobuf.CodedOutputStream;
import io.prometheus.metrics.model.snapshots.ClassicHistogramBuckets;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot.HistogramDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-encodes a {@link MetricSnapshots} into a Prometheus Remote-Write 2.0
 * {@code Request} protobuf (package {@code io.prometheus.write.v2}), matching the
 * Prometheus text-exposition naming so the pushed series line up exactly with what
 * {@code /mockserver/metrics} serves. Pure and stateless — the interned symbol
 * table is local to a single {@link #encode} call, no network, no global state —
 * so it is fully unit-testable.
 * <p>
 * Remote-Write 2.0 differs from v1 by interning every string (label names, label
 * values, and metadata help/unit) into a shared {@code symbols} table and
 * referencing them by {@code uint32} index. Message shapes (field numbers are
 * NORMATIVE and DIFFERENT from v1):
 * <pre>
 * Request    { reserved 1..3; repeated string symbols = 4; repeated TimeSeries timeseries = 5; }
 * TimeSeries { repeated uint32 labels_refs = 1 [packed]; repeated Sample samples = 2; Metadata metadata = 5; int64 created_timestamp = 6; }
 * Sample     { double value = 1; int64 timestamp = 2; }
 * Metadata   { MetricType type = 1; uint32 help_ref = 3; uint32 unit_ref = 4; }
 * </pre>
 * {@code symbols[0]} MUST be the empty string {@code ""} (a "no value" reference).
 * Each nested message is built into its own {@code byte[]} and embedded in the
 * parent via {@link CodedOutputStream#writeByteArray(int, byte[])} — a
 * length-delimited {@code bytes} field is wire-identical to an embedded message
 * (and to a packed repeated field), which sidesteps manual size arithmetic.
 */
public class RemoteWriteV2Encoder implements RemoteWriteEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteWriteV2Encoder.class);

    private static final String NAME_LABEL = "__name__";

    // MetricType enum (io.prometheus.write.v2.Metadata.MetricType)
    private static final int TYPE_UNSPECIFIED = 0;
    private static final int TYPE_COUNTER = 1;
    private static final int TYPE_GAUGE = 2;
    private static final int TYPE_HISTOGRAM = 3;
    private static final int TYPE_SUMMARY = 5;

    @Override
    public byte[] encode(MetricSnapshots snapshots, long timestampMillis) {
        // The symbol table is local to this encode() call, so the encoder stays stateless.
        // Insertion order == index; index 0 MUST be the empty string.
        Map<String, Integer> symbols = new LinkedHashMap<>();
        intern(symbols, "");

        // Build all TimeSeries child byte[]s FIRST — this populates the symbol table via interning —
        // THEN write the Request so symbols precede the timeseries that reference them by index.
        List<byte[]> timeSeries = new ArrayList<>();
        for (MetricSnapshot snapshot : snapshots) {
            encodeSnapshot(symbols, timeSeries, snapshot, timestampMillis);
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(body);
        try {
            // field 4 = symbols, in table (index) order — LinkedHashMap preserves insertion order.
            for (String symbol : symbols.keySet()) {
                out.writeString(4, symbol);
            }
            // field 5 = each TimeSeries.
            for (byte[] ts : timeSeries) {
                out.writeByteArray(5, ts);
            }
            out.flush();
        } catch (IOException e) {
            // In-memory encoding does no real I/O; wrap defensively so the caller fails soft.
            throw new UncheckedIOException("failed to encode remote-write v2 body", e);
        }
        return body.toByteArray();
    }

    private void encodeSnapshot(Map<String, Integer> symbols, List<byte[]> out, MetricSnapshot snapshot, long ts) {
        MetricMetadata metadata = snapshot.getMetadata();
        String base = metadata.getPrometheusName();
        int helpRef = refFor(symbols, metadata.getHelp());
        int unitRef = metadata.hasUnit() ? intern(symbols, metadata.getUnit().toString()) : 0;
        try {
            if (snapshot instanceof CounterSnapshot) {
                for (CounterSnapshot.CounterDataPointSnapshot dp : ((CounterSnapshot) snapshot).getDataPoints()) {
                    out.add(timeSeries(symbols, base + "_total", dp.getLabels(), null, null, dp.getValue(), ts, TYPE_COUNTER, helpRef, unitRef));
                }
            } else if (snapshot instanceof GaugeSnapshot) {
                for (GaugeSnapshot.GaugeDataPointSnapshot dp : ((GaugeSnapshot) snapshot).getDataPoints()) {
                    out.add(timeSeries(symbols, base, dp.getLabels(), null, null, dp.getValue(), ts, TYPE_GAUGE, helpRef, unitRef));
                }
            } else if (snapshot instanceof HistogramSnapshot) {
                encodeHistogram(symbols, out, base, (HistogramSnapshot) snapshot, ts, helpRef, unitRef);
            } else if (snapshot instanceof SummarySnapshot) {
                encodeSummary(symbols, out, base, (SummarySnapshot) snapshot, ts, helpRef, unitRef);
            } else {
                // Info / StateSet / Unknown / native-only histogram: no Prometheus-text sample
                // representation here — skip rather than throw, so no snapshot type can break export.
                LOGGER.debug("remote-write: skipping unsupported metric snapshot type {} for {}",
                    snapshot.getClass().getSimpleName(), base);
            }
        } catch (IOException e) {
            // In-memory encoding does no real I/O; wrap defensively so the caller fails soft.
            throw new UncheckedIOException("failed to encode remote-write v2 time series for " + base, e);
        }
    }

    private void encodeHistogram(Map<String, Integer> symbols, List<byte[]> out, String base, HistogramSnapshot snapshot, long ts, int helpRef, int unitRef) throws IOException {
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
                out.add(timeSeries(symbols, base + "_bucket", labels, "le", RemoteWriteV1Encoder.formatBucketBound(upperBound), value, ts, TYPE_HISTOGRAM, helpRef, unitRef));
                emittedPositiveInf |= isPositiveInf;
            }
            // Defensive: a well-formed classic histogram always has a +Inf bucket, but if the
            // model ever omits it, still emit le="+Inf" == total so the series is complete.
            if (!emittedPositiveInf) {
                out.add(timeSeries(symbols, base + "_bucket", labels, "le", "+Inf", total, ts, TYPE_HISTOGRAM, helpRef, unitRef));
            }
            out.add(timeSeries(symbols, base + "_count", labels, null, null, total, ts, TYPE_HISTOGRAM, helpRef, unitRef));
            if (dp.hasSum()) {
                out.add(timeSeries(symbols, base + "_sum", labels, null, null, dp.getSum(), ts, TYPE_HISTOGRAM, helpRef, unitRef));
            }
        }
    }

    private void encodeSummary(Map<String, Integer> symbols, List<byte[]> out, String base, SummarySnapshot snapshot, long ts, int helpRef, int unitRef) throws IOException {
        for (SummaryDataPointSnapshot dp : snapshot.getDataPoints()) {
            Labels labels = dp.getLabels();
            Quantiles quantiles = dp.getQuantiles();
            for (int i = 0; i < quantiles.size(); i++) {
                Quantile q = quantiles.get(i);
                out.add(timeSeries(symbols, base, labels, "quantile", RemoteWriteV1Encoder.formatBucketBound(q.getQuantile()), q.getValue(), ts, TYPE_SUMMARY, helpRef, unitRef));
            }
            if (dp.hasCount()) {
                out.add(timeSeries(symbols, base + "_count", labels, null, null, dp.getCount(), ts, TYPE_SUMMARY, helpRef, unitRef));
            }
            if (dp.hasSum()) {
                out.add(timeSeries(symbols, base + "_sum", labels, null, null, dp.getSum(), ts, TYPE_SUMMARY, helpRef, unitRef));
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
     * {@code __name__} label, the data point's own labels, an optional extra label
     * (e.g. {@code le} / {@code quantile}), a single sample, and metadata.
     * <p>
     * The complete label set is sorted lexicographically by label name (same rule
     * as v1) BEFORE interning, then {@code labels_refs} is written as a packed
     * repeated {@code uint32}: for each label, {@code nameRef} then {@code valueRef}
     * (N labels → 2N ints). Strict receivers reject unsorted series, and appending
     * {@code le}/{@code quantile} last would break the order for histograms whose own
     * labels sort after {@code "le"}.
     */
    private static byte[] timeSeries(Map<String, Integer> symbols, String name, Labels labels,
                                     String extraLabelName, String extraLabelValue, double value, long ts,
                                     int metricType, int helpRef, int unitRef) throws IOException {
        List<String[]> nameValues = new ArrayList<>();
        nameValues.add(new String[]{NAME_LABEL, name});
        for (int i = 0; i < labels.size(); i++) {
            nameValues.add(new String[]{labels.getPrometheusName(i), labels.getValue(i)});
        }
        if (extraLabelName != null) {
            nameValues.add(new String[]{extraLabelName, extraLabelValue});
        }
        nameValues.sort(Comparator.comparing((String[] nv) -> nv[0]));

        // Packed labels_refs: concatenated varint uint32s (nameRef, valueRef, …). A length-delimited
        // bytes field is wire-identical to a packed repeated field, so we write it via writeByteArray.
        ByteArrayOutputStream packedBuffer = new ByteArrayOutputStream();
        CodedOutputStream packed = CodedOutputStream.newInstance(packedBuffer);
        for (String[] nv : nameValues) {
            packed.writeUInt32NoTag(intern(symbols, nv[0]));
            packed.writeUInt32NoTag(intern(symbols, nv[1]));
        }
        packed.flush();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream c = CodedOutputStream.newInstance(buffer);
        c.writeByteArray(1, packedBuffer.toByteArray());   // labels_refs (packed uint32) = 1
        c.writeByteArray(2, sample(value, ts));            // samples = 2
        c.writeByteArray(5, metadata(metricType, helpRef, unitRef)); // metadata = 5
        // created_timestamp = 6 omitted (0)
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

    private static byte[] metadata(int metricType, int helpRef, int unitRef) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream c = CodedOutputStream.newInstance(buffer);
        c.writeInt32(1, metricType); // MetricType type = 1 (enum, varint wire type)
        if (helpRef > 0) {
            c.writeUInt32(3, helpRef); // help_ref = 3 (0 == the "" symbol, so omit)
        }
        if (unitRef > 0) {
            c.writeUInt32(4, unitRef); // unit_ref = 4
        }
        c.flush();
        return buffer.toByteArray();
    }

    /**
     * Intern a string into the symbol table, returning its {@code uint32} index.
     * A {@code null} is treated as the empty string, whose index is always 0
     * (pre-seeded), so it never allocates a new entry.
     */
    private static int intern(Map<String, Integer> symbols, String value) {
        String key = value == null ? "" : value;
        Integer index = symbols.get(key);
        if (index == null) {
            index = symbols.size();
            symbols.put(key, index);
        }
        return index;
    }

    /**
     * The symbol reference for a help/unit string: 0 (the {@code ""} symbol) when
     * null or blank, otherwise its interned index.
     */
    private static int refFor(Map<String, Integer> symbols, String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        return intern(symbols, value);
    }

    @Override
    public String contentType() {
        return "application/x-protobuf;proto=io.prometheus.write.v2.Request";
    }

    @Override
    public String protocolVersionHeader() {
        return "2.0.0";
    }
}
