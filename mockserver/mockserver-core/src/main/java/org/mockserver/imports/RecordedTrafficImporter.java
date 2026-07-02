package org.mockserver.imports;

import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;
import org.slf4j.event.Level;

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
 * <p><strong>Crash-tolerant.</strong> A malformed line is <em>skipped and counted</em>, not fatal —
 * the write path flushes {@code line + "\n"} per exchange, so a hard kill can leave a truncated
 * mid-JSON final line. Aborting the whole import on that one artefact would defeat the crash-recovery
 * guarantee, so this importer returns every intact exchange and reports how many lines it skipped. It
 * only throws when the input contains non-blank lines but <em>none</em> parse (i.e. the body is not a
 * recorded-traffic archive at all). Blank/empty input yields an empty result (0 imported).
 *
 * <p>Redaction is <strong>on by default</strong> (see {@link ImportRedaction}) as a defence-in-depth
 * re-mask; the persist side already masks secrets on write when {@code mockserver.redactSecretsInLog}
 * is enabled.
 */
public class RecordedTrafficImporter {

    private final MockServerLogger mockServerLogger;
    private final HttpRequestAndHttpResponseSerializer serializer;

    public RecordedTrafficImporter(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
        this.serializer = new HttpRequestAndHttpResponseSerializer(mockServerLogger);
    }

    /**
     * Outcome of a recorded-traffic re-import: the intact pairs plus a count of NDJSON lines that
     * could not be parsed (malformed or truncated) and were skipped.
     */
    public static class Result {
        private final List<HttpRequestAndHttpResponse> pairs;
        private final int skippedLineCount;

        public Result(List<HttpRequestAndHttpResponse> pairs, int skippedLineCount) {
            this.pairs = pairs;
            this.skippedLineCount = skippedLineCount;
        }

        public List<HttpRequestAndHttpResponse> getPairs() {
            return pairs;
        }

        public int getSkippedLineCount() {
            return skippedLineCount;
        }
    }

    /**
     * Parse an NDJSON recorded-traffic archive with redaction enabled (the default).
     *
     * @param ndjson the archive content — one {@link HttpRequestAndHttpResponse} JSON object per line
     * @return the parsed pairs and skipped-line count
     * @throws IllegalArgumentException only when the input has non-blank lines but none parse
     */
    public Result importRecordedTraffic(String ndjson) {
        return importRecordedTraffic(ndjson, ImportRedaction.Options.enabled());
    }

    /**
     * Parse an NDJSON recorded-traffic archive, applying the supplied redaction options. Malformed or
     * truncated lines (e.g. the final line left half-written by a crash) are skipped and counted
     * rather than aborting the whole import; see the class javadoc.
     *
     * @param ndjson           the archive content — one {@link HttpRequestAndHttpResponse} JSON object per line
     * @param redactionOptions controls whether/how sensitive data is masked; pass
     *                         {@link ImportRedaction.Options#disabled()} to keep values verbatim
     * @return the parsed pairs and skipped-line count (both zero for blank/empty input)
     * @throws IllegalArgumentException only when the input has non-blank lines but none parse
     */
    public Result importRecordedTraffic(String ndjson, ImportRedaction.Options redactionOptions) {
        // Blank/empty input is not an error: an enabled-but-never-written archive (or an empty file on
        // disk) legitimately holds zero exchanges, so return an empty result rather than throwing.
        if (ndjson == null || ndjson.trim().isEmpty()) {
            return new Result(new ArrayList<>(), 0);
        }
        List<HttpRequestAndHttpResponse> pairs = new ArrayList<>();
        int skippedLineCount = 0;
        int nonBlankLineCount = 0;
        String[] lines = ndjson.split("\\r?\\n");
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            nonBlankLineCount++;
            try {
                HttpRequestAndHttpResponse pair = serializer.deserialize(trimmed);
                if (pair != null) {
                    pairs.add(pair);
                } else {
                    skippedLineCount++;
                }
            } catch (RuntimeException e) {
                // A single malformed/truncated line must not lose the rest of the session — skip it,
                // count it, and continue. The most common cause is a crash-truncated final line.
                skippedLineCount++;
                if (mockServerLogger != null) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("skipping unparseable line " + lineNumber + " while re-importing recorded traffic archive: " + e.getMessage())
                    );
                }
            }
        }
        // Every non-blank line failed to parse — the body is not a recorded-traffic archive at all
        // (e.g. a HAR/Postman document or garbage sent with ?format=recording). Fail loudly.
        if (pairs.isEmpty() && nonBlankLineCount > 0) {
            throw new IllegalArgumentException(
                "not a recorded-traffic NDJSON archive — none of the " + nonBlankLineCount + " non-blank line(s) could be parsed as an HttpRequestAndHttpResponse");
        }
        if (skippedLineCount > 0 && mockServerLogger != null) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("re-imported " + pairs.size() + " recorded exchange(s), skipped " + skippedLineCount + " unparseable line(s)")
            );
        }
        return new Result(ImportRedaction.redactRecordedTraffic(pairs, redactionOptions), skippedLineCount);
    }
}
