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
 * Verifies {@link RemoteWriteV1Encoder} produces a well-formed Remote-Write v1
 * {@code WriteRequest} by decoding the bytes with an independent, hand-written
 * {@link CodedInputStream} reader (NOT the encoder) and asserting the expected
 * series/samples are present.
 */
public class RemoteWriteV1EncoderTest {

    private static final long TIMESTAMP = 1_700_000_000_123L;

    @Test
    public void encodesCounterGaugeAndClassicHistogram() throws IOException {
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
        byte[] encoded = new RemoteWriteV1Encoder().encode(snapshots, TIMESTAMP);

        // snappy round-trips the body losslessly (exercises SnappyBlock too)
        byte[] roundTripped = Snappy.uncompress(SnappyBlock.compress(encoded));
        assertArrayEquals(encoded, roundTripped);

        Decoded decoded = decodeWriteRequest(roundTripped);
        Map<String, Sample> series = decoded.samples;

        // then — counter emits <name>_total with the value, labels and __name__
        Sample counter = series.get(key("__name__", "test_counter_total", "method", "GET"));
        assertThat("counter series present", counter != null, is(true));
        assertThat(counter.value, is(5.0));
        assertThat(counter.timestamp, is(TIMESTAMP));

        // gauge emits <name>
        Sample gauge = series.get(key("__name__", "test_gauge", "state", "active"));
        assertThat("gauge series present", gauge != null, is(true));
        assertThat(gauge.value, is(42.0));

        // histogram cumulative buckets, including le="+Inf" == total count
        assertThat(series.get(key("__name__", "test_histogram_bucket", "path", "/x", "le", "1.0")).value, is(1.0));
        assertThat(series.get(key("__name__", "test_histogram_bucket", "path", "/x", "le", "2.5")).value, is(2.0));
        assertThat(series.get(key("__name__", "test_histogram_bucket", "path", "/x", "le", "+Inf")).value, is(3.0));

        // _count and _sum
        assertThat(series.get(key("__name__", "test_histogram_count", "path", "/x")).value, is(3.0));
        assertThat(series.get(key("__name__", "test_histogram_sum", "path", "/x")).value, is(7.5));

        // sanity: the __name__ label is always present, so a series with no __name__ never appears
        assertThat(series, not(hasKey("{}")));

        // Remote-Write 1.0 requires the label set sorted lexicographically by name. Assert the
        // ON-THE-WIRE order (not a re-sorted view) for series whose own label sorts AFTER the added
        // le/quantile label — the exact case that an "append le last" encoder would get wrong.
        // __name__ (0x5F) < le (0x6C) < path (0x70):
        assertThat(decoded.order.get(key("__name__", "test_histogram_bucket", "path", "/x", "le", "1.0")),
            is(Arrays.asList("__name__", "le", "path")));
        assertThat(decoded.order.get(key("__name__", "test_histogram_bucket", "path", "/x", "le", "+Inf")),
            is(Arrays.asList("__name__", "le", "path")));
        // __name__ < method (0x6D):
        assertThat(decoded.order.get(key("__name__", "test_counter_total", "method", "GET")),
            is(Arrays.asList("__name__", "method")));
    }

    // ---------------------------------------------------------------------
    // Independent hand-written protobuf decoder (does not use the encoder).
    // ---------------------------------------------------------------------

    private static final class Sample {
        final double value;
        final long timestamp;

        Sample(double value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    /** Decoded WriteRequest: sample values keyed by sorted-label string, plus the on-the-wire label order per series. */
    private static final class Decoded {
        final Map<String, Sample> samples = new HashMap<>();
        final Map<String, List<String>> order = new HashMap<>();
    }

    /**
     * Decode a WriteRequest, keying each series by its sorted label set (including
     * {@code __name__}) and separately recording the labels' on-the-wire order so the
     * spec-required lexicographic ordering can be asserted.
     */
    private static Decoded decodeWriteRequest(byte[] bytes) throws IOException {
        Decoded result = new Decoded();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = in.readTag()) != 0) {
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                decodeTimeSeries(in.readByteArray(), result);
            } else {
                in.skipField(tag);
            }
        }
        return result;
    }

    private static void decodeTimeSeries(byte[] bytes, Decoded result) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        TreeMap<String, String> labels = new TreeMap<>();
        List<String> wireOrder = new ArrayList<>();
        Sample sample = null;
        int tag;
        while ((tag = in.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1) {
                String[] label = decodeLabel(in.readByteArray());
                labels.put(label[0], label[1]);
                wireOrder.add(label[0]);
            } else if (field == 2) {
                sample = decodeSample(in.readByteArray());
            } else {
                in.skipField(tag);
            }
        }
        result.samples.put(labels.toString(), sample);
        result.order.put(labels.toString(), wireOrder);
    }

    private static String[] decodeLabel(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        String name = null;
        String value = null;
        int tag;
        while ((tag = in.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1) {
                name = in.readString();
            } else if (field == 2) {
                value = in.readString();
            } else {
                in.skipField(tag);
            }
        }
        return new String[]{name, value};
    }

    private static Sample decodeSample(byte[] bytes) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        double value = 0;
        long timestamp = 0;
        int tag;
        while ((tag = in.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1) {
                value = in.readDouble();
            } else if (field == 2) {
                timestamp = in.readInt64();
            } else {
                in.skipField(tag);
            }
        }
        return new Sample(value, timestamp);
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
