package org.mockserver.metrics.remotewrite;

import io.prometheus.metrics.model.snapshots.MetricSnapshots;

/**
 * Encodes a snapshot of Prometheus metrics into the body of a Prometheus
 * remote-write request. Implementations are pure and stateless (no network,
 * no global state) so they can be unit-tested in isolation.
 * <p>
 * The exporter calls {@link #encode} to produce the (uncompressed) protobuf
 * body, then snappy-compresses it and POSTs it. {@link #contentType()} and
 * {@link #protocolVersionHeader()} let the exporter set the version-specific
 * request headers without knowing which protocol version it holds.
 */
public interface RemoteWriteEncoder {

    /**
     * Encode the given metric snapshots into an uncompressed remote-write
     * protobuf body. Every emitted sample carries {@code timestampMillis}
     * (unix epoch milliseconds) as its timestamp.
     */
    byte[] encode(MetricSnapshots snapshots, long timestampMillis);

    /**
     * The HTTP {@code Content-Type} for this protocol version (v1:
     * {@code application/x-protobuf}).
     */
    String contentType();

    /**
     * The value for the {@code X-Prometheus-Remote-Write-Version} header (v1:
     * {@code 0.1.0}).
     */
    String protocolVersionHeader();
}
