package org.mockserver.templates.engine.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link LinkedHashMap} of HTTP header name -&gt; values whose lookups ({@code get},
 * {@code containsKey}, {@code getOrDefault}) are case-insensitive on the header <em>name</em>, while
 * iteration order and the original key casing are preserved exactly as inserted.
 *
 * <p>HTTP field names are case-insensitive (RFC 9110 &sect;5.1) and MockServer's matchers treat them
 * so, but response templates read request headers through plain {@link Map} semantics — Velocity's
 * Uberspector maps {@code $request.headers.host} to {@code get("host")} and Mustache's fetcher calls
 * {@code containsKey} then {@code get}. With a plain {@code LinkedHashMap} a template that asked for
 * {@code $request.headers.Host} could therefore miss a header the client sent as {@code host} (or
 * vice-versa), and the mismatch was protocol-dependent because HTTP/2 lowercases field names on the
 * wire while HTTP/1.1 preserves the client's casing (issue #2575).
 *
 * <p>This map is deliberately <b>additive, not normalising</b>:
 * <ul>
 *   <li>An exact-case hit is returned unchanged (fast path); only a <em>miss</em> falls back to a
 *       case-insensitive scan. So every casing combination resolves —
 *       {@code Host}&rarr;{@code host}, {@code host}&rarr;{@code Host}, and the two same-case
 *       combinations that already worked — and keys are never lowercased, so
 *       {@code $request.headers.Host} keeps working when the client sent {@code Host}.</li>
 *   <li>Extending {@link LinkedHashMap} keeps iteration (via {@code keySet}/{@code values}/
 *       {@code entrySet}, used both by loop-over-headers templates and by Jackson serialisation) in
 *       the original insertion order with the original wire casing — unlike a
 *       {@code TreeMap<>(String.CASE_INSENSITIVE_ORDER)}, which would reorder alphabetically.</li>
 *   <li>If two differently-cased spellings of the same name arrive as separate fields (e.g.
 *       {@code Host} and {@code host}), {@link #put} <b>merges</b> their values into the single
 *       first-seen entry rather than one silently overwriting the other, matching HTTP's rule that
 *       same-name fields combine. The first-seen casing is kept as the canonical key.</li>
 * </ul>
 *
 * <p>The case-insensitive resolution is a <b>scan of the live entries</b> rather than a maintained
 * lower-case index, deliberately: {@link java.util.HashMap} routes its bulk and default methods
 * ({@code putAll}, {@code putIfAbsent}, {@code merge}, {@code compute*}) straight at its internal
 * storage without ever calling the overridden {@link #put}, and the {@code keySet}/{@code entrySet}
 * views mutate it directly too, so any side index silently goes stale — and this map is public and
 * reachable from template expressions, where Velocity's Uberspector will invoke exactly those public
 * methods. Deriving from the entries instead cannot go stale by construction. Header counts are
 * small (tens of entries) and the scan runs only on a miss, so the cost is immaterial.
 *
 * <p>Only the response/forward templates package uses this. It is intentionally NOT the codebase's
 * general header type: {@code org.mockserver.model.Headers} is a
 * {@code Multimap<NottableString, NottableString>} that serialises as a DTO array and offers no
 * {@code Map.get} semantics, so it cannot be consumed by the template engines here.
 *
 * <p>Header name lookup only — this type is not used for cookies, query-string parameters or path
 * parameters, whose names are case-sensitive per their respective specifications.
 */
public class CaseInsensitiveHeaderMap extends LinkedHashMap<String, List<String>> {

    /**
     * The key actually stored in this map that case-insensitively matches {@code key}, or null when
     * there is none. Only ever called after an exact-case lookup has already missed.
     */
    private String canonicalKeyFor(Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        String candidate = (String) key;
        for (String storedKey : super.keySet()) {
            if (storedKey != null && storedKey.equalsIgnoreCase(candidate)) {
                return storedKey;
            }
        }
        return null;
    }

    @Override
    public List<String> put(String key, List<String> value) {
        if (super.containsKey(key)) {
            return super.put(key, value);
        }
        String canonicalKey = canonicalKeyFor(key);
        if (canonicalKey == null) {
            return super.put(key, value);
        }
        // A differently-cased spelling of a header name already present: merge the values into the
        // first-seen entry (keeping its casing and position) rather than overwriting it, matching
        // HTTP's same-name-field combination rule. Note this returns the canonical entry's previous
        // value, where Map.put's contract would say null (the argument key itself had no mapping) —
        // the merged-into value is the more useful answer and no caller here reads the return, but it
        // is a deliberate deviation rather than an oversight.
        List<String> existing = super.get(canonicalKey);
        List<String> merged = new ArrayList<>();
        if (existing != null) {
            merged.addAll(existing);
        }
        if (value != null) {
            merged.addAll(value);
        }
        return super.put(canonicalKey, merged);
    }

    @Override
    public List<String> get(Object key) {
        if (super.containsKey(key)) {
            return super.get(key);
        }
        String canonicalKey = canonicalKeyFor(key);
        return canonicalKey == null ? null : super.get(canonicalKey);
    }

    @Override
    public boolean containsKey(Object key) {
        return super.containsKey(key) || canonicalKeyFor(key) != null;
    }

    @Override
    public List<String> getOrDefault(Object key, List<String> defaultValue) {
        return containsKey(key) ? get(key) : defaultValue;
    }

    @Override
    public List<String> remove(Object key) {
        if (super.containsKey(key)) {
            return super.remove(key);
        }
        String canonicalKey = canonicalKeyFor(key);
        return canonicalKey == null ? null : super.remove(canonicalKey);
    }
}
