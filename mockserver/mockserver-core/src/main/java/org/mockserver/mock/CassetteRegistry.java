package org.mockserver.mock;

import org.mockserver.time.TimeService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-wide registry of known <em>cassettes</em> — fixture files of MockServer expectations
 * recorded with {@code record_llm_fixtures} or loaded with {@code load_expectations_from_file}.
 *
 * <p>The dashboard's Cassettes tab keeps a per-browser list in {@code localStorage}; this registry
 * is the server-side counterpart so cassettes are also discoverable across page reloads, across
 * browsers, and from automation (e.g. the demo data loader). It holds only lightweight metadata —
 * the cassette's file path, name, expectation count and how it was created — never the file
 * contents themselves. State is held in a {@link ConcurrentHashMap} keyed by file path and is
 * cleared on server reset (see {@code HttpState.reset()}).
 *
 * <p>Exposed over the control plane as {@code GET/PUT/DELETE /mockserver/cassettes}.
 *
 * <p><strong>Registration policy (settled — do not re-open).</strong> A cassette enters this registry
 * from exactly three places, and <em>loading and recording register automatically</em>:
 * <ul>
 *     <li>the {@code record_llm_fixtures} MCP tool, when it writes a fixture file (origin {@code "recorded"});</li>
 *     <li>the {@code load_expectations_from_file} MCP tool, when it loads a fixture file (origin {@code "loaded"});</li>
 *     <li>an explicit {@code PUT /mockserver/cassettes}, to track a cassette produced some other way.</li>
 * </ul>
 * The two MCP tools each own the file path and expectation count at the point the file is written/loaded,
 * so they call {@link #register} directly rather than routing through a shared load/record method — there is
 * no such method: recording writes bytes and loading reads them, and they converge only on this registry.
 * A manual {@code PUT} is therefore <em>not</em> required after a load or a record. Every path upserts by
 * file path (see {@link #register}), so re-loading or re-recording the same file updates the existing entry
 * in place rather than duplicating it.
 */
public class CassetteRegistry {

    private static final CassetteRegistry INSTANCE = new CassetteRegistry(TimeService::currentTimeMillis);

    private final ConcurrentHashMap<String, Entry> byPath = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public CassetteRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public static CassetteRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register (or update) a cassette, stamping its {@code lastUsed} with the current time. When a
     * cassette with the same {@code path} already exists it is updated in place rather than
     * duplicated. No-op if {@code path} is blank.
     *
     * @param origin how the cassette was created — {@code "recorded"} or {@code "loaded"}
     * @return the stored entry, or {@code null} if {@code path} was blank
     */
    public Entry register(String path, String filename, int expectationCount, String origin) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String key = path.trim();
        String name = (filename == null || filename.trim().isEmpty()) ? extractFilename(key) : filename.trim();
        String safeOrigin = "recorded".equals(origin) ? "recorded" : "loaded";
        Entry entry = new Entry(key, name, expectationCount, safeOrigin, clock.getAsLong());
        byPath.put(key, entry);
        return entry;
    }

    /** Remove a cassette by path. Returns true if one was removed. */
    public boolean remove(String path) {
        return path != null && byPath.remove(path.trim()) != null;
    }

    /** Snapshot of all registered cassettes, most-recently-used first. */
    public List<Entry> list() {
        List<Entry> result = new ArrayList<>(byPath.values());
        result.sort(Comparator.comparingLong((Entry e) -> e.lastUsedEpochMillis).reversed());
        return result;
    }

    /** Clear all cassettes. Called on server reset and for test isolation. */
    public void reset() {
        byPath.clear();
    }

    private static String extractFilename(String path) {
        String[] parts = path.split("[/\\\\]");
        return parts.length > 0 ? parts[parts.length - 1] : path;
    }

    public static final class Entry {
        public final String path;
        public final String filename;
        public final int expectationCount;
        public final String origin;
        public final long lastUsedEpochMillis;

        private Entry(String path, String filename, int expectationCount, String origin, long lastUsedEpochMillis) {
            this.path = path;
            this.filename = filename;
            this.expectationCount = expectationCount;
            this.origin = origin;
            this.lastUsedEpochMillis = lastUsedEpochMillis;
        }
    }
}
