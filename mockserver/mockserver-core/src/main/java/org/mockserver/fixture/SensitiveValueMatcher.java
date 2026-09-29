package org.mockserver.fixture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Masks every occurrence of a set of credential values in free text, such as a matcher's "found" text or a rendered cURL
 * command: every stretch of the text covered by an occurrence of any value is replaced by
 * {@value FixtureRedactor#REDACTED_PLACEHOLDER}, occurrences that overlap merging into one placeholder.
 * <p>
 * Short texts are checked value by value until that work would pass the cost of building an Aho-Corasick automaton (the
 * values' total length); from then on the automaton finds every occurrence in one pass, each character costing at most
 * a binary search over one node's children. Both give the same result.
 * <p>
 * The values searched for are bounded, so memory and time stay bounded however many or however large the values an
 * entry carries: values longer than {@link #MAX_VALUE_LENGTH} are not searched for, and values beyond
 * {@link #MAX_VALUES} or {@link #MAX_TOTAL_LENGTH} (in the order given, so callers pass the most important first) are
 * dropped with a one-time warning. Such values are still masked in the fields they came from; only a copy quoted in free
 * text is left as it is.
 */
public final class SensitiveValueMatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SensitiveValueMatcher.class);

    /**
     * Longest value searched for in free text. A longer value is a structured body field or a whole message, masked where
     * it appears as a field.
     */
    public static final int MAX_VALUE_LENGTH = 4 * 1024;
    /**
     * Most values searched for in one render.
     */
    public static final int MAX_VALUES = 10_000;
    /**
     * Most characters, summed over the values, searched for in one render; the automaton holds about 18 bytes per
     * character at most, plus about 14 while it is built.
     */
    public static final int MAX_TOTAL_LENGTH = 256 * 1024;

    static final AtomicBoolean TRUNCATION_WARNED = new AtomicBoolean(false);

    private static final SensitiveValueMatcher EMPTY = new SensitiveValueMatcher(Collections.emptyList(), 0);

    private final List<String> values;
    private final long totalLength;
    private long valueByValueWork;
    private Automaton automaton;

    private SensitiveValueMatcher(List<String> values, long totalLength) {
        this.values = values;
        this.totalLength = totalLength;
    }

    /**
     * A matcher for {@code values}, most important first; values that are {@code null}, empty or longer than
     * {@link #MAX_VALUE_LENGTH} are ignored, and values beyond {@link #MAX_VALUES} or {@link #MAX_TOTAL_LENGTH} are
     * dropped. Not thread-safe: one per render.
     */
    public static SensitiveValueMatcher of(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        Set<String> kept = new LinkedHashSet<>();
        long totalLength = 0;
        boolean dropped = false;
        for (String value : values) {
            if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH || kept.contains(value)) {
                continue;
            }
            if (kept.size() == MAX_VALUES || totalLength + value.length() > MAX_TOTAL_LENGTH) {
                dropped = true;
                continue;
            }
            kept.add(value);
            totalLength += value.length();
        }
        if (dropped && TRUNCATION_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("redactSecretsInLog: a log entry carries more credential values than are searched for in its free text (at most " + MAX_VALUES + " values and " + MAX_TOTAL_LENGTH + " characters); the values beyond that are still masked in the headers, cookies, query parameters and body fields they came from, but not where the entry's message text quotes them");
        }
        if (kept.isEmpty()) {
            return EMPTY;
        }
        List<String> ordered = new ArrayList<>(kept);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        return new SensitiveValueMatcher(ordered, totalLength);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Whether {@code text} contains any of the values.
     */
    public boolean containsAny(String text) {
        if (values.isEmpty() || text == null) {
            return false;
        }
        if (useAutomaton(text)) {
            return automaton.containsAny(text);
        }
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code text} with every stretch covered by a value replaced by {@value FixtureRedactor#REDACTED_PLACEHOLDER}; the
     * same instance when nothing matched.
     */
    public String scrub(String text) {
        if (values.isEmpty() || text == null) {
            return text;
        }
        if (useAutomaton(text)) {
            return automaton.scrub(text);
        }
        return scrubValueByValue(text, values);
    }

    /**
     * Short texts are checked value by value while the budget allows: that work (values x text length, summed over the
     * render) may add up to the values' total length, the cost of building the automaton. The first text that would pass
     * the budget builds the automaton, used from then on, which costs about the length of each text it scrubs.
     */
    private boolean useAutomaton(String text) {
        if (automaton != null) {
            return true;
        }
        long work = (long) values.size() * text.length();
        if (valueByValueWork + work <= totalLength) {
            valueByValueWork += work;
            return false;
        }
        automaton();
        return true;
    }

    int valueCount() {
        return values.size();
    }

    long totalLength() {
        return totalLength;
    }

    Automaton automaton() {
        if (automaton == null) {
            automaton = Automaton.of(values);
        }
        return automaton;
    }

    static String scrubValueByValue(String text, List<String> values) {
        // each occurrence as (start << 32 | end), so sorting orders them by start
        long[] spans = null;
        int count = 0;
        for (String value : values) {
            int from = 0;
            int index;
            while ((index = text.indexOf(value, from)) >= 0) {
                if (spans == null) {
                    spans = new long[8];
                } else if (count == spans.length) {
                    spans = Arrays.copyOf(spans, count * 2);
                }
                spans[count++] = ((long) index << 32) | (index + value.length());
                from = index + 1;
            }
        }
        if (count == 0) {
            return text;
        }
        Arrays.sort(spans, 0, count);
        int[] starts = new int[count];
        int[] ends = new int[count];
        int merged = 0;
        for (int i = 0; i < count; i++) {
            int start = (int) (spans[i] >>> 32);
            int end = (int) spans[i];
            if (merged > 0 && start < ends[merged - 1]) {
                ends[merged - 1] = Math.max(ends[merged - 1], end);
            } else {
                starts[merged] = start;
                ends[merged] = end;
                merged++;
            }
        }
        return replace(text, starts, ends, merged);
    }

    /**
     * {@code text} with each of the {@code count} ordered, non-overlapping stretches {@code [starts[i], ends[i])}
     * replaced by the placeholder.
     */
    private static String replace(String text, int[] starts, int[] ends, int count) {
        StringBuilder scrubbed = new StringBuilder(text.length());
        int copied = 0;
        for (int i = 0; i < count; i++) {
            scrubbed.append(text, copied, starts[i]).append(FixtureRedactor.REDACTED_PLACEHOLDER);
            copied = ends[i];
        }
        return scrubbed.append(text, copied, text.length()).toString();
    }

    static final class Automaton {

        private static final int ROOT = 0;
        private static final int NONE = -1;

        // the trie as flat arrays: node n's children are childNode[childStart[n] .. childStart[n + 1]), ordered by the
        // character on the edge into them (childChar), so a transition is a binary search; plus each node's failure link
        // and the length of the longest value ending at it (0 if none)
        private final int[] childStart;
        private final char[] childChar;
        private final int[] childNode;
        private final int[] failure;
        private final int[] longestEnding;
        private final int[] asciiRootChild = new int[128];

        private Automaton(int[] childStart, char[] childChar, int[] childNode, int[] longestEnding) {
            this.childStart = childStart;
            this.childChar = childChar;
            this.childNode = childNode;
            this.longestEnding = longestEnding;
            this.failure = new int[longestEnding.length];
            Arrays.fill(asciiRootChild, NONE);
            for (int i = childStart[ROOT]; i < childStart[ROOT + 1] && childChar[i] < 128; i++) {
                asciiRootChild[childChar[i]] = childNode[i];
            }
        }

        /**
         * The automaton for {@code values}, which must be non-empty strings.
         */
        static Automaton of(Collection<String> values) {
            // inserted in lexicographic order, each value shares the path of the previous one up to their common prefix
            // and adds its remaining characters as new nodes, so every node's children are created in character order
            List<String> sorted = new ArrayList<>(values);
            sorted.sort(null);
            int capacity = 1;
            int longest = 0;
            for (String value : sorted) {
                capacity += value.length();
                longest = Math.max(longest, value.length());
            }
            int[] parent = new int[capacity];
            char[] edge = new char[capacity];
            int[] ending = new int[capacity];
            int[] path = new int[longest + 1];
            int nodes = 1;
            String previous = "";
            for (String value : sorted) {
                int common = 0;
                int limit = Math.min(previous.length(), value.length());
                while (common < limit && previous.charAt(common) == value.charAt(common)) {
                    common++;
                }
                int node = path[common];
                for (int i = common; i < value.length(); i++) {
                    parent[nodes] = node;
                    edge[nodes] = value.charAt(i);
                    node = nodes++;
                    path[i + 1] = node;
                }
                ending[node] = value.length();
                previous = value;
            }
            int[] childStart = new int[nodes + 1];
            for (int node = 1; node < nodes; node++) {
                childStart[parent[node] + 1]++;
            }
            for (int node = 0; node < nodes; node++) {
                childStart[node + 1] += childStart[node];
            }
            int[] next = Arrays.copyOf(childStart, nodes);
            char[] childChar = new char[Math.max(nodes - 1, 0)];
            int[] childNode = new int[Math.max(nodes - 1, 0)];
            for (int node = 1; node < nodes; node++) {
                int slot = next[parent[node]]++;
                childChar[slot] = edge[node];
                childNode[slot] = node;
            }
            Automaton matcher = new Automaton(childStart, childChar, childNode, Arrays.copyOf(ending, nodes));
            int[] queue = next;
            int head = 0;
            int tail = 0;
            for (int slot = childStart[ROOT]; slot < childStart[ROOT + 1]; slot++) {
                queue[tail++] = childNode[slot];
            }
            while (head < tail) {
                int node = queue[head++];
                if (matcher.longestEnding[node] == 0) {
                    matcher.longestEnding[node] = matcher.longestEnding[matcher.failure[node]];
                }
                for (int slot = childStart[node]; slot < childStart[node + 1]; slot++) {
                    matcher.failure[childNode[slot]] = matcher.step(matcher.failure[node], childChar[slot]);
                    queue[tail++] = childNode[slot];
                }
            }
            return matcher;
        }

        int nodeCount() {
            return failure.length;
        }

        /**
         * Whether {@code text} contains any of the values.
         */
        boolean containsAny(String text) {
            int node = ROOT;
            for (int i = 0; i < text.length(); i++) {
                node = step(node, text.charAt(i));
                if (longestEnding[node] > 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * {@code text} with every stretch covered by a value replaced by {@value FixtureRedactor#REDACTED_PLACEHOLDER}; the
         * same instance when nothing matched.
         */
        String scrub(String text) {
            // covered stretches as [start, end) pairs, merged while they overlap; ends only grow
            int[] starts = null;
            int[] ends = null;
            int count = 0;
            int node = ROOT;
            for (int i = 0; i < text.length(); i++) {
                node = step(node, text.charAt(i));
                int length = longestEnding[node];
                if (length > 0) {
                    int start = i + 1 - length;
                    if (starts == null) {
                        starts = new int[8];
                        ends = new int[8];
                    }
                    while (count > 0 && start < ends[count - 1]) {
                        start = Math.min(start, starts[count - 1]);
                        count--;
                    }
                    if (count == starts.length) {
                        starts = Arrays.copyOf(starts, count * 2);
                        ends = Arrays.copyOf(ends, count * 2);
                    }
                    starts[count] = start;
                    ends[count] = i + 1;
                    count++;
                }
            }
            return count == 0 ? text : replace(text, starts, ends, count);
        }

        private int step(int node, char c) {
            int next;
            while ((next = child(node, c)) == NONE && node != ROOT) {
                node = failure[node];
            }
            return next == NONE ? ROOT : next;
        }

        private int child(int node, char c) {
            if (node == ROOT && c < 128) {
                return asciiRootChild[c];
            }
            int low = childStart[node];
            int high = childStart[node + 1] - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                char found = childChar[middle];
                if (found < c) {
                    low = middle + 1;
                } else if (found > c) {
                    high = middle - 1;
                } else {
                    return childNode[middle];
                }
            }
            return NONE;
        }
    }
}
