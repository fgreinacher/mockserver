package org.mockserver.metrics.remotewrite;

import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Snappy compression in the raw <em>block</em> format that the Prometheus
 * remote-write protocol requires (NOT the framed/streaming format). Thin
 * wrapper over {@link Snappy#compress(byte[])} so the exporter and tests share
 * one canonical implementation.
 */
public final class SnappyBlock {

    private SnappyBlock() {
    }

    /**
     * Compress {@code raw} into the Snappy block format. Wraps the checked
     * {@link IOException} thrown by snappy-java (compression of an in-memory
     * byte array does not do real I/O) in an {@link UncheckedIOException} so
     * callers can treat it as a normal runtime failure; the exporter catches
     * it and fails soft.
     */
    public static byte[] compress(byte[] raw) {
        try {
            return Snappy.compress(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to snappy-compress remote-write body", e);
        }
    }
}
