package org.mockserver.imports;

import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-imports recorded request/response pairs from the append-only NDJSON archive written by
 * {@link org.mockserver.persistence.RecordedRequestsFileSystemPersistence}.
 *
 * <p>The archive is newline-delimited JSON — one serialized {@link HttpRequestAndHttpResponse} per
 * line (see {@code persistRecordedRequestsToDisk}). This importer parses each non-blank line back
 * into an {@link HttpRequestAndHttpResponse} using the same {@link HttpRequestAndHttpResponseSerializer}
 * that produced it, so the format round-trips. Unlike {@link HarImporter} /
 * {@link PostmanCollectionImporter} (which build expectations), the imported pairs are re-injected
 * into the event log as recorded exchanges and become retrievable exactly like in-memory recordings.
 *
 * <p>Redaction is <strong>on by default</strong> (see {@link ImportRedaction}) as a defence-in-depth
 * re-mask; the persist side already masks secrets on write when {@code mockserver.redactSecretsInLog}
 * is enabled.
 */
public class RecordedTrafficImporter {

    private final HttpRequestAndHttpResponseSerializer serializer;

    public RecordedTrafficImporter(MockServerLogger mockServerLogger) {
        this.serializer = new HttpRequestAndHttpResponseSerializer(mockServerLogger);
    }

    /**
     * Parse an NDJSON recorded-traffic archive with redaction enabled (the default).
     *
     * @param ndjson the archive content — one {@link HttpRequestAndHttpResponse} JSON object per line
     * @return the recorded request/response pairs (may be empty)
     * @throws IllegalArgumentException if the content is null/blank or a line is not valid JSON
     */
    public List<HttpRequestAndHttpResponse> importRecordedTraffic(String ndjson) {
        return importRecordedTraffic(ndjson, ImportRedaction.Options.enabled());
    }

    /**
     * Parse an NDJSON recorded-traffic archive, applying the supplied redaction options.
     *
     * @param ndjson           the archive content — one {@link HttpRequestAndHttpResponse} JSON object per line
     * @param redactionOptions controls whether/how sensitive data is masked; pass
     *                         {@link ImportRedaction.Options#disabled()} to keep values verbatim
     * @return the recorded request/response pairs (may be empty)
     * @throws IllegalArgumentException if the content is null/blank or a line is not valid JSON
     */
    public List<HttpRequestAndHttpResponse> importRecordedTraffic(String ndjson, ImportRedaction.Options redactionOptions) {
        if (ndjson == null || ndjson.trim().isEmpty()) {
            throw new IllegalArgumentException("recorded traffic NDJSON body is required");
        }
        List<HttpRequestAndHttpResponse> pairs = new ArrayList<>();
        String[] lines = ndjson.split("\\r?\\n");
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                HttpRequestAndHttpResponse pair = serializer.deserialize(trimmed);
                if (pair != null) {
                    pairs.add(pair);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("failed to parse recorded traffic archive at line " + lineNumber + ": " + e.getMessage(), e);
            }
        }
        return ImportRedaction.redactRecordedTraffic(pairs, redactionOptions);
    }
}
