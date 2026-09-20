package org.mockserver.mock;

import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.HttpRequestPropertiesMatcher;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableOptionalString;
import org.mockserver.model.NottableSchemaString;
import org.mockserver.model.NottableString;
import org.mockserver.model.Parameters;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static org.mockserver.mock.SortableExpectationId.EXPECTATION_SORTABLE_PRIORITY_COMPARATOR;

/**
 * Candidate index for expectation matching above a size threshold.
 *
 * <p><b>Purpose.</b> {@code RequestMatchers.firstMatchingExpectation} scans every
 * registered expectation in global priority/insertion order and returns the first
 * match — pure O(n). For large expectation sets (1k–5k) this dominates the
 * request-serving hot path. This index narrows the scan to a small CANDIDATE set
 * for the request without changing which expectation is returned.
 *
 * <p><b>Hard guarantee — zero behavioural change.</b> The expectation returned for
 * any request is byte-for-byte identical to the full linear scan, including match
 * ORDER (priority + insertion order, first match wins). This holds because:
 * <ol>
 *   <li>An expectation is placed in a {@code (method, path)} BUCKET only when both
 *       its method and path are PLAIN LITERAL equality matchers (see
 *       {@link #bucketKeyFor(HttpRequestMatcher, boolean)}); such an expectation can
 *       match a request ONLY when the request's literal {@code (method, path)} equals
 *       the bucket key. Any other request provably fails the method/path criteria
 *       (which are AND-ed in {@code HttpRequestPropertiesMatcher}).</li>
 *   <li>Every expectation that is NOT safely bucketable (regex/notted/blank/optional/
 *       schema/path-parameter method or path, or a non-HTTP request definition) goes
 *       in the FALLTHROUGH list, which is checked on every request.</li>
 *   <li>The candidate set for a request is {@code bucket(requestKey) ∪ fallthrough}.
 *       Any expectation outside this set is in a different literal bucket and so
 *       cannot match the request. Therefore the first match among candidates,
 *       evaluated in the SAME GLOBAL sorted order, equals the first match of the
 *       full scan.</li>
 * </ol>
 *
 * <p><b>Global-order evaluation.</b> {@link #candidatesInGlobalOrder} returns the
 * candidate matchers sorted by exactly the comparator the backing
 * {@code CircularPriorityQueue} uses ({@link SortableExpectationId#EXPECTATION_SORTABLE_PRIORITY_COMPARATOR}
 * over each matcher's {@code expectation.getSortableId()}). This reproduces the
 * global total order, so a higher-priority fallthrough expectation still wins over
 * a lower-priority bucketed one.
 *
 * <p><b>Incremental (per-mutation) maintenance — G1.</b> The index is maintained
 * INCREMENTALLY: {@link #onAdded}/{@link #onRemoved} update one bucket in O(1) as each
 * matcher enters or leaves the store. They are driven by a mutation listener the
 * {@code RequestMatchers} wires onto the backing {@code CircularPriorityQueue}, so every
 * structural mutation (add, remove, in-place update, priority re-key, overflow eviction,
 * reset) is reflected without a full rebuild. A read NEVER rebuilds — it reads a small
 * candidate set directly. This replaces the earlier generation-driven
 * rebuild-on-read-after-any-mutation design, which under continuous churn (the serving
 * path itself schedules lazy removal of {@code once()} / limited-{@code Times} matchers)
 * forced every request to rebuild the whole index AND the full sorted list — O(n) per
 * request and, being {@code synchronized}, serialising concurrent readers so it worsened
 * with cores. The incremental index is flat under churn and touches no shared lock on the
 * read path.
 *
 * <p><b>An in-place update that re-buckets is correct.</b> {@code RequestMatchers} performs
 * an in-place update as {@code removePriorityKey(matcher)} (the matcher still holds its OLD
 * method/path) then, after {@code matcher.update(newExpectation)}, {@code addPriorityKey(matcher)}
 * (now the NEW method/path). The listener maps these to {@link #onRemoved} (removes the id from
 * its OLD placement, looked up via the {@code locator} — never recomputed) then {@link #onAdded}
 * (places the id into its NEW bucket). The id is invariant across the update, so the move is exact
 * regardless of whether the bucket key changed.
 *
 * <p><b>Case mode.</b> Bucket keys are folded with {@code toLowerCase(ROOT)} when matching is
 * case-insensitive ({@code matchExactCase} off). The fold in force is fixed for the maintained
 * index and stored on the published {@link State}. A read that observes a different case mode
 * (a live {@code matchExactCase} change) triggers a one-off {@link #rebuild} from the
 * authoritative sorted snapshot under the index monitor — rare, and it never spans the scan.
 *
 * <p><b>Threading contract.</b> Mirrors {@code RequestMatchers}: control-plane mutations are
 * single-writer, and {@link #onAdded}/{@link #onRemoved}/{@link #rebuild} serialise on the index
 * monitor so a data-plane {@link #rebuild} can never race a writer. Reads
 * ({@link #candidatesInGlobalOrder}) run lock-free on data-plane threads over the published
 * {@link State} (a {@code volatile} reference to concurrent maps) and are eventually consistent —
 * a read concurrent with an in-flight mutation may not yet reflect it, exactly as the CPQ's
 * {@code toSortedList()} snapshot is.
 */
class CandidateIndex {

    private static final char METHOD_PATH_SEPARATOR = '\n';

    /**
     * Locator sentinel meaning "this id lives in the fallthrough, not a bucket". Contains no
     * {@link #METHOD_PATH_SEPARATOR}, which every real {@code method\npath} bucket key always
     * contains, so it can never collide with one.
     */
    // NOTE the escape. This sentinel is a NUL-prefixed string; it MUST be written as
    // \u0000 and never as a raw NUL byte in the source. A raw NUL is invisible to grep,
    // diff and code review -- it reads as absent -- so a corrupted or accidentally
    // duplicated sentinel could not be seen by any of them.
    private static final String FALLTHROUGH_KEY = "\u0000FALLTHROUGH";

    /**
     * Published index state. The maps are concurrent so data-plane readers iterate them
     * lock-free while the single control-plane writer mutates them; the whole object is
     * swapped atomically (through the {@code volatile snapshot} reference) only on a case-mode
     * rebuild, so a read sees a wholly-consistent {@code (buckets, fallthrough, caseInsensitive)}
     * triple.
     */
    private static final class State {
        // bucketKey ("method\npath") -> (expectationId -> matcher). Inner maps are keyed by the
        // (unique, invariant) expectation id so an in-place update overwrites in place and a
        // bucket can legitimately hold several distinct expectations sharing one (method,path).
        final ConcurrentMap<String, ConcurrentMap<String, HttpRequestMatcher>> buckets = new ConcurrentHashMap<>();
        // expectationId -> matcher for every non-bucketable (fallthrough) expectation.
        final ConcurrentMap<String, HttpRequestMatcher> fallthrough = new ConcurrentHashMap<>();
        // expectationId -> where it is placed (a bucket key, or FALLTHROUGH_KEY). Lets onRemoved
        // find the exact placement in O(1) WITHOUT recomputing the bucket key, so removal is
        // robust even if the matcher's state changed between add and remove.
        final ConcurrentMap<String, String> locator = new ConcurrentHashMap<>();
        final boolean caseInsensitive;

        State(boolean caseInsensitive) {
            this.caseInsensitive = caseInsensitive;
        }
    }

    private volatile State snapshot;

    CandidateIndex(boolean caseInsensitive) {
        this.snapshot = new State(caseInsensitive);
    }

    // ---- incremental maintenance (control-plane, single-writer; serialised for the rare rebuild) ----

    /**
     * Records that {@code matcher} has entered the store (or re-entered it after an in-place
     * update). Idempotent: any prior placement of the same id is removed first, so a re-add or a
     * missed {@link #onRemoved} can never leave a duplicate.
     */
    synchronized void onAdded(HttpRequestMatcher matcher) {
        String id = idOf(matcher);
        if (id == null) {
            return;
        }
        State state = snapshot;
        placeIntoState(state, id, matcher);
    }

    /**
     * Records that {@code matcher} has left the store. Uses the {@code locator} to remove the id
     * from its exact placement in O(1); a no-op if the id was never indexed.
     */
    synchronized void onRemoved(HttpRequestMatcher matcher) {
        String id = idOf(matcher);
        if (id == null) {
            return;
        }
        removeFromState(snapshot, id);
    }

    private static void placeIntoState(State state, String id, HttpRequestMatcher matcher) {
        // Drop any previous placement of this id first (handles re-add / re-bucket robustly).
        removeFromState(state, id);
        String key = bucketKeyFor(matcher, state.caseInsensitive);
        if (key == null) {
            state.fallthrough.put(id, matcher);
            state.locator.put(id, FALLTHROUGH_KEY);
        } else {
            state.buckets.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(id, matcher);
            state.locator.put(id, key);
        }
    }

    private static void removeFromState(State state, String id) {
        String key = state.locator.remove(id);
        if (key == null) {
            return;
        }
        if (FALLTHROUGH_KEY.equals(key)) {
            state.fallthrough.remove(id);
        } else {
            ConcurrentMap<String, HttpRequestMatcher> bucket = state.buckets.get(key);
            if (bucket != null) {
                bucket.remove(id);
                // Reclaim an emptied bucket so a long churn of DISTINCT (method,path) keys
                // (e.g. many one-time expectations) cannot leak empty maps. Single-writer, so
                // the value-guarded remove only prevents dropping a bucket already replaced.
                if (bucket.isEmpty()) {
                    state.buckets.remove(key, bucket);
                }
            }
        }
    }

    private static String idOf(HttpRequestMatcher matcher) {
        if (matcher == null || matcher.getExpectation() == null) {
            return null;
        }
        return matcher.getExpectation().getId();
    }

    /**
     * Returns the candidate matchers for the request in GLOBAL sorted order (the same
     * order {@code CircularPriorityQueue.toSortedList()} produces), so the first match
     * among them equals the first match of the full scan.
     *
     * <p>The index is maintained incrementally, so a read normally performs NO rebuild and
     * touches no shared lock. The only exception is a live {@code matchExactCase} change: if the
     * supplied case mode differs from the maintained one the index is rebuilt once from the
     * authoritative snapshot (rare — the config is effectively fixed at runtime).
     *
     * @param requestDefinition     the incoming request
     * @param caseInsensitiveNow    whether case-folding applies now (matchExactCase off)
     * @param authoritativeSnapshot supplier of the full sorted snapshot, used only for the
     *                              non-ASCII case-insensitive fallback and a case-mode rebuild
     *                              (never for a steady-state read)
     */
    List<HttpRequestMatcher> candidatesInGlobalOrder(
        RequestDefinition requestDefinition,
        boolean caseInsensitiveNow,
        Supplier<List<HttpRequestMatcher>> authoritativeSnapshot
    ) {
        State state = snapshot;
        if (state.caseInsensitive != caseInsensitiveNow) {
            // Pass the SUPPLIER, not its result. Evaluating it here would read the
            // authoritative list on this (unsynchronised) reader thread BEFORE rebuild
            // takes the monitor, so a concurrent onAdded could place a matcher into the
            // state we are about to replace and be silently lost — permanently, because
            // the incremental design has no generation re-check to self-heal it the way
            // the previous rebuild-on-generation design did.
            state = rebuild(authoritativeSnapshot, caseInsensitiveNow);
        }

        ConcurrentMap<String, HttpRequestMatcher> bucket = null;
        if (requestDefinition instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) requestDefinition;
            // Case-insensitive bucketing folds the key with toLowerCase(ROOT), which is NOT
            // equivalent to the matcher's char-by-char equalsIgnoreCase for non-ASCII characters
            // (e.g. Turkish dotted/dotless i U+0130/U+0131, long s U+017F): a non-ASCII request can
            // equalsIgnoreCase-match a bucketed pure-ASCII literal yet fold to a different bucket key
            // (U+0130 even changes length under toLowerCase). We therefore cannot narrow a non-ASCII
            // request in case-insensitive mode without risking a SILENT MISS of a bucketed
            // expectation — fall back to the full authoritative scan for this request (byte-for-byte
            // identical to the un-indexed path). Case-sensitive mode uses exact equality with no
            // fold, so a non-ASCII request can never exact-match a pure-ASCII bucketed literal and
            // narrowing stays sound.
            if (state.caseInsensitive && requestHasNonAsciiMethodOrPath(httpRequest)) {
                return authoritativeSnapshot.get();
            }
            String key = requestKeyFor(httpRequest, state.caseInsensitive);
            if (key != null) {
                bucket = state.buckets.get(key);
            }
        }
        // A non-HTTP request (DNS/binary/OpenAPI) cannot match any bucketed (literal HTTP
        // method+path) expectation, so its candidate set is the fallthrough only — which
        // is correct: all such expectations are themselves in the fallthrough.

        boolean hasFallthrough = !state.fallthrough.isEmpty();
        boolean hasBucket = bucket != null && !bucket.isEmpty();
        if (!hasFallthrough && !hasBucket) {
            // No candidate matches this request — a full miss allocates nothing.
            return java.util.Collections.emptyList();
        }

        // Common case (bucketable request, no fallthrough expectations): copy just the one small
        // bucket. Only when fallthrough expectations exist do we union the two, so the read path
        // allocates no more than the pre-existing "fallthrough copy + bucket addAll" did.
        List<HttpRequestMatcher> candidates;
        if (!hasFallthrough) {
            candidates = new ArrayList<>(bucket.values());
        } else {
            candidates = new ArrayList<>(state.fallthrough.values());
            if (hasBucket) {
                candidates.addAll(bucket.values());
            }
        }

        if (candidates.size() > 1) {
            candidates.sort((a, b) -> EXPECTATION_SORTABLE_PRIORITY_COMPARATOR.compare(
                sortableId(a), sortableId(b)
            ));
        }
        return candidates;
    }

    /**
     * Rebuilds the whole index from the authoritative sorted snapshot for a new case mode and
     * publishes it. Serialised on the index monitor against {@link #onAdded}/{@link #onRemoved}
     * so a data-plane rebuild can never lose a concurrent control-plane mutation. The
     * double-check avoids a redundant rebuild when two readers race the same mode flip. Holds no
     * lock that spans the matching scan.
     */
    private synchronized State rebuild(
        Supplier<List<HttpRequestMatcher>> authoritativeSnapshot,
        boolean caseInsensitive
    ) {
        State current = snapshot;
        if (current.caseInsensitive == caseInsensitive) {
            // Another reader already rebuilt for this case mode.
            return current;
        }
        // Read the authoritative list UNDER THIS MONITOR. onAdded/onRemoved serialise on
        // the same monitor and complete their skipList mutation before their listener
        // callback, so any mutation whose callback finished before we acquired the lock is
        // already visible here, and any that has not yet acquired it will apply to the
        // fresh state after we publish. Capturing outside the lock loses the former.
        List<HttpRequestMatcher> authoritative = authoritativeSnapshot.get();
        State fresh = new State(caseInsensitive);
        for (HttpRequestMatcher matcher : authoritative) {
            String id = idOf(matcher);
            if (id != null) {
                placeIntoState(fresh, id, matcher);
            }
        }
        this.snapshot = fresh;
        return fresh;
    }

    private static SortableExpectationId sortableId(HttpRequestMatcher matcher) {
        return matcher.getExpectation() != null
            ? matcher.getExpectation().getSortableId()
            : SortableExpectationId.NULL;
    }

    // ---- bucketable predicate ----

    /**
     * Returns the {@code (method, path)} bucket key for a matcher when — and only when
     * — the expectation matches exactly one literal {@code (method, path)} pair, or
     * {@code null} (meaning "fallthrough — checked on every request") otherwise.
     *
     * <p>EXTREMELY conservative: any doubt routes the expectation to the fallthrough.
     * An expectation is bucketable iff ALL hold:
     * <ul>
     *   <li>its request definition is a plain {@link HttpRequest} (not OpenAPI/DNS/binary);</li>
     *   <li>the matcher is an {@link HttpRequestPropertiesMatcher} (the only matcher whose
     *       method/path semantics this predicate reasons about);</li>
     *   <li>it declares NO path parameters (otherwise the matched path is rewritten to a
     *       {@code .*} regex by {@code PathParametersDecoder.normalisePathWithParametersForMatching});</li>
     *   <li>both method and path are plain literal values — non-null, not blank, not notted
     *       ({@code !}), not optional, not a schema/OpenAPI string, and containing no regex
     *       metacharacter and only ASCII characters (so the matcher's anchored-regex path is
     *       provably equivalent to a literal (case-insensitive) string equals).</li>
     * </ul>
     */
    private static String bucketKeyFor(HttpRequestMatcher matcher, boolean caseInsensitive) {
        if (!(matcher instanceof HttpRequestPropertiesMatcher)) {
            return null;
        }
        Expectation expectation = matcher.getExpectation();
        if (expectation == null) {
            return null;
        }
        RequestDefinition requestDefinition = expectation.getHttpRequest();
        if (!(requestDefinition instanceof HttpRequest)) {
            return null;
        }
        HttpRequest httpRequest = (HttpRequest) requestDefinition;

        // Path parameters rewrite the matched path into a regex — not bucketable.
        Parameters pathParameters = httpRequest.getPathParameters();
        if (pathParameters != null && !pathParameters.isEmpty()) {
            return null;
        }

        String method = literalValue(httpRequest.getMethod());
        if (method == null) {
            return null;
        }
        String path = literalValue(httpRequest.getPath());
        if (path == null) {
            return null;
        }
        return composeKey(method, path, caseInsensitive);
    }

    /**
     * Returns the plain literal string value of a method/path matcher component, or
     * {@code null} when the component is anything other than a plain literal (blank,
     * notted, optional, schema, or containing a regex metacharacter / non-ASCII char).
     */
    private static String literalValue(NottableString nottableString) {
        if (nottableString == null) {
            return null;
        }
        // Schema (OpenAPI) and optional matchers are never plain literal equality.
        if (nottableString instanceof NottableSchemaString || nottableString instanceof NottableOptionalString) {
            return null;
        }
        if (nottableString.isOptional() || nottableString.isNot()) {
            return null;
        }
        if (nottableString.isBlank()) {
            // A blank matcher matches ANY value — must be checked on every request.
            return null;
        }
        String value = nottableString.getValue();
        if (value == null) {
            return null;
        }
        // Must be a pure-ASCII literal so the matcher's anchored-regex comparison is
        // provably equivalent to a (case-insensitive) literal equals. Mirrors the
        // RegexStringMatcher pure-ASCII-literal short-circuit.
        if (!isPureAsciiLiteral(value)) {
            return null;
        }
        return value;
    }

    private static String requestKeyFor(HttpRequest request, boolean caseInsensitive) {
        NottableString method = request.getMethod();
        NottableString path = request.getPath();
        if (method == null || path == null) {
            return null;
        }
        String methodValue = method.getValue();
        String pathValue = path.getValue();
        if (methodValue == null || pathValue == null) {
            return null;
        }
        return composeKey(methodValue, pathValue, caseInsensitive);
    }

    /**
     * True when the request's method or path value contains a non-ASCII character. Used to force
     * the full-scan fallback in case-insensitive mode, where toLowerCase(ROOT) bucket-key folding
     * diverges from the matcher's char-by-char equalsIgnoreCase (see {@link #candidatesInGlobalOrder}).
     */
    private static boolean requestHasNonAsciiMethodOrPath(HttpRequest request) {
        NottableString method = request.getMethod();
        NottableString path = request.getPath();
        return (method != null && !isPureAscii(method.getValue()))
            || (path != null && !isPureAscii(path.getValue()));
    }

    private static String composeKey(String method, String path, boolean caseInsensitive) {
        if (caseInsensitive) {
            method = method.toLowerCase(Locale.ROOT);
            path = path.toLowerCase(Locale.ROOT);
        }
        return method + METHOD_PATH_SEPARATOR + path;
    }

    // ---- pure-ASCII-literal test (mirrors RegexStringMatcher) ----

    private static boolean isPureAsciiLiteral(String s) {
        return !looksLikeRegex(s) && isPureAscii(s);
    }

    private static boolean looksLikeRegex(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '\\':
                case '.':
                case '[':
                case ']':
                case '{':
                case '}':
                case '(':
                case ')':
                case '*':
                case '+':
                case '?':
                case '^':
                case '$':
                case '|':
                    return true;
                default:
                    // continue scanning
            }
        }
        return false;
    }

    private static boolean isPureAscii(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
