package org.mockserver.metrics.remotewrite;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.Test;
import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertArrayEquals;

/**
 * Verifies {@link RemoteWriteV2Encoder} produces a well-formed Remote-Write 2.0
 * {@code io.prometheus.write.v2.Request} by decoding the bytes with an independent,
 * hand-written {@link CodedInputStream} reader (NOT the encoder): it reads the
 * {@code symbols} table, resolves each {@code TimeSeries}'s packed {@code labels_refs}
 * back to (name,value) pairs against that table, and asserts the expected
 * series/samples/metadata are present with the spec-required label ordering.
 */
public class RemoteWriteV2EncoderTest {

    private static final long TIMESTAMP = 1_700_000_000_123L;

    // io.prometheus.write.v2.Metadata.MetricType
    private static final int TYPE_COUNTER = 1;
    private static final int TYPE_GAUGE = 2;
    private static final int TYPE_HISTOGRAM = 3;

    @Test
    public void encodesCounterGaugeAndClassicHistogramWithSymbolTable() throws IOException {
        // given — a fresh registry (not the default) with known metrics/values/labels
        PrometheusRegistry registry = new PrometheusRegistry();

        Counter.builder()
            .name("test_counter")
            .help("a test counter")
            .labelNames("method")
            .register(registry)
            .labelValues("GET").inc(5);

        Gauge.builder()
            .name("test_gauge")
            .help("a test gauge")
            .labelNames("state")
            .register(registry)
            .labelValues("active").set(42);

        // 'path' sorts AFTER 'le' (0x70 > 0x6C), so an "append le last" encoder would mis-order.
        Histogram histogram = Histogram.builder()
            .name("test_histogram")
            .help("a test histogram")
            .classicOnly()
            .classicUpperBounds(1.0, 2.5)
            .labelNames("path")
            .register(registry);
        histogram.labelValues("/x").observe(0.5); // -> le=1.0
        histogram.labelValues("/x").observe(2.0); // -> le=2.5
        histogram.labelValues("/x").observe(5.0); // -> le=+Inf

        MetricSnapshots snapshots = registry.scrape();

        // when
        byte[] encoded = new RemoteWriteV2Encoder().encode(snapshots, TIMESTAMP);

        // snappy round-trips the body losslessly (exercises SnappyBlock too)
        byte[] roundTripped = Snappy.uncompress(SnappyBlock.compress(encoded));
        assertArrayEquals(encoded, roundTripped);

        Decoded decoded = decodeRequest(roundTripped);

        // symbols[0] MUST be the empty string ("no value" reference).
        assertThat(decoded.symbols.get(0), is(""));

        Map<String, Series> series = decoded.series;

        // counter emits <name>_total with the value, labels and __name__
        Series counter = series.get(key("__name__", "test_counter_total", "method", "GET"));
        assertThat("counter series present", counter != null, is(true));
        assertThat(counter.value, is(5.0));
        assertThat(counter.timestamp, is(TIMESTAMP));
        assertThat(counter.metricType, is(TYPE_COUNTER));

        // gauge emits <name>
        Series gauge = series.get(key("__name__", "test_gauge", "state", "active"));
        assertThat("gauge series present", gauge != null, is(true));
        assertThat(gauge.value, is(42.0));
        assertThat(gauge.metricType, is(TYPE_GAUGE));

        // histogram cumulative buckets, including le="+Inf" == total count
        Series bucket1 = series.get(key("__name__", "test_histogram_bucket", "path", "/x", "le", "1.0"));
        Series bucket25 = series.get(key("__name__", "test_histogram_bucket", "path", "/x", "le", "2.5"));
        Series bucketInf = series.get(key("__name__", "test_histogram_bucket", "path", "/x", "le", "+Inf"));
        assertThat(bucket1.value, is(1.0));
        assertThat(bucket25.value, is(2.0));
        assertThat(bucketInf.value, is(3.0));
        assertThat(bucketInf.metricType, is(TYPE_HISTOGRAM));

        // _count and _sum
        assertThat(series.get(key("__name__", "test_histogram_count", "path", "/x")).value, is(3.0));
        assertThat(series.get(key("__name__", "test_histogram_sum", "path", "/x")).value, is(7.5));

        // sanity: the __name__ label is always present, so a series with no labels never appears
        assertThat(series, not(hasKey("{}")));

        // Remote-Write requires the label set sorted lexicographically by name. Assert the
        // ON-THE-WIRE (resolved) order for series whose own label sorts AFTER the added le label —
        // the exact case an "append le last" encoder gets wrong. __name__ (0x5F) < le (0x6C) < path (0x70):
        assertThat(bucket1.labelOrder, is(Arrays.asList("__name__", "le", "path")));
        assertThat(bucketInf.labelOrder, is(Arrays.asList("__name__", "le", "path")));
        // __name__ < method (0x6D):
        assertThat(counter.labelOrder, is(Arrays.asList("__name__", "method")));

        // symbol interning dedups: the histogram label value "/x" is used by 5 series but interned once.
        assertThat(Collections.frequency(decoded.symbols, "/x"), is(1));
        // __name__ is interned once despite appearing on every series.
        assertThat(Collections.frequency(decoded.symbols, "__name__"), is(1));
        // the counter's help string is interned into the symbol table (metadata help_ref).
        assertThat(decoded.symbols.contains("a test counter"), is(true));
    }

    // ---------------------------------------------------------------------
    // Independent hand-written protobuf decoder (does not use the encoder).
    // ---------------------------------------------------------------------

    private static final class Series {
        final double value;
        final long timestamp;
        final int metricType;
        final List<String> labelOrder;

        Series(double value, long timestamp, int metricType, List<String> labelOrder) {
            this.value = value;
            this.timestamp = timestamp;
            this.metricType = metricType;
            this.labelOrder = labelOrder;
        }
    }

    private static final class Decoded {
        final List<String> symbols = new ArrayList<>();
        final Map<String, Series> series = new HashMap<>();
    }

    /**
     * Decode a v2 Request: field 4 = symbols (repeated string), field 5 = timeseries.
     * The symbols are read FIRST from the stream position, but since field order on the
     * wire is symbols-before-timeseries (as the encoder writes them) a single pass works.
     */
    private static Decoded decodeRequest(byte[] bytes) throws IOException {
        Decoded result = new Decoded();
        List<byte[]> timeSeriesBytes = new ArrayList<>();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = in.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 4) {
                result.symbols.add(in.readString());
            } else if (field == 5) {
                timeSeriesBytes.add(in.readByteArray());
            } else {
                in.skipField(tag);
            }
        }
        for (byte[] ts : timeSeriesBytes) {
            decodeTimeSeries(ts, result);
        }
        return result;
    }

    private static void decodeTimeSeries(byte[] bytes, Decoded result) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        List<Integer> labelRefs = new ArrayList<>();
        double value = 0;
        long timestamp = 0;
        int metricType = -1;
        int tag;
        while ((tag = in.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1) {
                // packed repeated uint32 — read the length-delimited block then drain varints
                byte[] packed = in.readByteArray();
                CodedInputStream refs = CodedInputStream.newInstance(packed);
                while (!refs.isAtEnd()) {
                    labelRefs.add(refs.readUInt32());
                }
            } else if (field == 2) {
                CodedInputStream sample = CodedInputStream.newInstance(in.readByteArray());
                int sTag;
                while ((sTag = sample.readTag()) != 0) {
                    int sField = WireFormat.getTagFieldNumber(sTag);
                    if (sField == 1) {
                        value = sample.readDouble();
                    } else if (sField == 2) {
                        timestamp = sample.readInt64();
                    } else {
                        sample.skipField(sTag);
                    }
                }
            } else if (field == 5) {
                CodedInputStream meta = CodedInputStream.newInstance(in.readByteArray());
                int mTag;
                while ((mTag = meta.readTag()) != 0) {
                    int mField = WireFormat.getTagFieldNumber(mTag);
                    if (mField == 1) {
                        metricType = meta.readInt32();
                    } else {
                        meta.skipField(mTag);
                    }
                }
            } else {
                in.skipField(tag);
            }
        }

        // resolve packed (nameRef, valueRef) pairs against the symbol table
        TreeMap<String, String> labels = new TreeMap<>();
        List<String> wireOrder = new ArrayList<>();
        for (int i = 0; i + 1 < labelRefs.size(); i += 2) {
            String name = result.symbols.get(labelRefs.get(i));
            String labelValue = result.symbols.get(labelRefs.get(i + 1));
            labels.put(name, labelValue);
            wireOrder.add(name);
        }
        result.series.put(labels.toString(), new Series(value, timestamp, metricType, wireOrder));
    }

    /**
     * Build the same key the decoder uses: a {@link TreeMap#toString()} over the
     * (sorted) label name/value pairs.
     */
    private static String key(String... nameValuePairs) {
        TreeMap<String, String> labels = new TreeMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            labels.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return labels.toString();
    }
}
