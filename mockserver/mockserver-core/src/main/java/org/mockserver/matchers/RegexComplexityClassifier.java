package org.mockserver.matchers;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, once per distinct regex, whether that regex can be evaluated inline on the calling
 * (Netty event-loop) thread without the {@link MatchingTimeoutExecutor} thread-pool hand-off, or
 * whether it must be isolated on the shared pool because it could exhibit catastrophic
 * (super-linear) backtracking - the ReDoS the pool exists to contain.
 *
 * <p><b>Why this exists.</b> The pool protects a fixed, tiny set of event-loop threads from a
 * pathological user-supplied pattern that would otherwise pin a thread (uninterruptibly - Java's
 * regex engine does not observe {@code Thread.interrupt()} mid-backtrack) for the full timeout, or
 * forever without one. But the hand-off (submit + block on {@code Future.get}) costs microseconds
 * around a match that is itself nanoseconds, and that tax is paid on every evaluation including the
 * overwhelming majority that are structurally incapable of blowing up. This classifier lets those
 * provably-safe patterns skip the pool while every pattern that could backtrack catastrophically
 * keeps its isolation.
 *
 * <p><b>Soundness is the whole point.</b> A false "safe" reintroduces exactly the DoS the pool
 * guards against, on the event loop, so the analysis is deliberately conservative: it returns
 * {@code true} only for patterns it can prove run in linear time under an anchored full match, and
 * returns {@code false} for anything it cannot prove - including anything it does not fully
 * understand. Being wrong in the "unsafe" direction only forgoes a performance win; being wrong in
 * the "safe" direction is a security regression, so all ambiguity resolves to "unsafe".
 *
 * <p><b>The proof.</b> Catastrophic (exponential) backtracking requires a quantifier applied to a
 * sub-expression that itself contains ambiguity - a nested quantifier, or alternation with
 * overlapping branches (e.g. {@code (a+)+}, {@code (a|ab)*}) - or a backreference. Super-linear
 * polynomial backtracking requires two or more quantifier "loops" that can compete for the same
 * input characters (e.g. {@code \d+\d+}, {@code .*a.*b}). A pattern is therefore provably
 * linear-time under a single anchored match attempt when ALL of the following hold:
 * <ul>
 *   <li>it contains no group {@code (} ... {@code )} of any kind (this alone excludes every nested
 *       quantifier and every quantified / alternating group / lookaround - the entire exponential
 *       class),</li>
 *   <li>it contains no top-level alternation {@code |},</li>
 *   <li>it contains no backreference ({@code \1}..{@code \9}, {@code \k}),</li>
 *   <li>every quantifier applies to a single character or single character class, and</li>
 *   <li>no two quantifier loops can compete for the same input character. Two loops are safe when
 *       their alphabets are disjoint, OR when a required single atom between them (a "hard wall")
 *       has an alphabet disjoint from both - so neither loop can consume the wall character and
 *       neither can reach across it. This bounds total backtracking to O(n).</li>
 * </ul>
 * Anything richer (groups, alternation, backreferences, adjacent loops over overlapping alphabets
 * with no separating wall, non-analysable quantifier alphabets, unusual escapes) is reported unsafe
 * and keeps the pool.
 *
 * <p><b>Anchored full match only.</b> The linearity proof assumes a single match attempt anchored
 * at the input start (Java {@link java.util.regex.Matcher#matches()}), which is what the
 * path/method/body/GraphQL-operationName/JSON-RPC-method matchers use. It does NOT hold for
 * {@link java.util.regex.Matcher#find()}, whose outer position scan can turn an otherwise-linear
 * pattern quadratic; find()-based callers (the LLM conversation matcher) must not use this and keep
 * the pool.
 */
final class RegexComplexityClassifier {

    /**
     * Bounded cache of analysis results, keyed on case-flag + regex text. Classification is a pure
     * function of (pattern text, case-insensitivity) so the result is stable and safe to memoise.
     * The cap prevents an attacker who cycles through unbounded distinct expectation regexes from
     * growing the map without limit; once full, uncached patterns are simply re-analysed (still
     * correct, just not memoised) rather than evicting - analysis is cheap and one-shot per pattern
     * in practice.
     */
    private static final int MAX_CACHE_ENTRIES = 4096;
    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private RegexComplexityClassifier() {
    }

    /**
     * @param regex           the user-supplied regular expression text (may be {@code null})
     * @param caseInsensitive whether the pattern is (or will be) compiled with
     *                        {@code CASE_INSENSITIVE} - affects quantifier-alphabet disjointness
     * @return {@code true} only when the pattern is provably free of super-linear backtracking under
     * an anchored full match, and may therefore be evaluated inline without the timeout pool
     */
    static boolean isAnchoredInlineSafe(String regex, boolean caseInsensitive) {
        if (regex == null) {
            // A null value never compiles to a real pattern (NottableString short-circuits it to a
            // non-match without touching the regex engine), so there is nothing that can backtrack.
            return true;
        }
        String key = (caseInsensitive ? "i " : "s ") + regex;
        Boolean cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean safe;
        try {
            safe = analyse(regex, caseInsensitive);
        } catch (RuntimeException unexpected) {
            // Any parser surprise resolves to "unsafe" - never let an analysis bug open the inline
            // path to an unproven pattern.
            safe = false;
        }
        if (CACHE.size() < MAX_CACHE_ENTRIES) {
            CACHE.putIfAbsent(key, safe);
        }
        return safe;
    }

    /**
     * Structural analysis. Walks the pattern once into an ordered list of items (each quantified
     * atom is a LOOP, each required single atom is a WALL candidate), bailing to {@code false} the
     * moment it sees any construct outside the provably-linear subset, then verifies that no two
     * loops can compete for the same input character.
     */
    private static boolean analyse(String regex, boolean caseInsensitive) {
        final int n = regex.length();
        final List<Item> items = new ArrayList<>();

        // The atom immediately preceding the cursor, not yet committed as a required atom because it
        // might still be quantified by the next character. Anchors and zero-width escapes leave this
        // null (they consume no input and cannot be a wall or be quantified).
        CharSet pendingAtom = null;

        int i = 0;
        while (i < n) {
            char c = regex.charAt(i);
            switch (c) {
                case '(':
                case ')':
                case '|':
                    // groups (any nesting / alternation / lookaround) and top-level alternation are
                    // outside the analysable subset
                    return false;
                case '^':
                case '$':
                    pendingAtom = flushRequired(items, pendingAtom);
                    i++;
                    break;
                case '.':
                    pendingAtom = flushRequired(items, pendingAtom);
                    pendingAtom = CharSet.universal();
                    i++;
                    break;
                case '[': {
                    pendingAtom = flushRequired(items, pendingAtom);
                    ClassScan scan = scanCharClass(regex, i, caseInsensitive);
                    if (scan == null) {
                        return false;
                    }
                    pendingAtom = scan.charSet;
                    i = scan.endExclusive;
                    break;
                }
                case '\\': {
                    pendingAtom = flushRequired(items, pendingAtom);
                    EscapeScan scan = scanEscape(regex, i, caseInsensitive);
                    if (scan == null) {
                        return false;
                    }
                    pendingAtom = scan.charSet; // null for a zero-width anchor escape
                    i = scan.endExclusive;
                    break;
                }
                case '*':
                case '+':
                case '?': {
                    if (pendingAtom == null) {
                        return false; // quantifier with no quantifiable atom -> malformed
                    }
                    items.add(Item.loop(pendingAtom));
                    pendingAtom = null;
                    i++;
                    i = consumeQuantifierModifier(regex, i);
                    break;
                }
                case '{': {
                    int afterBrace = scanBraceQuantifier(regex, i);
                    if (afterBrace < 0) {
                        // not a well-formed {n}/{n,}/{n,m} -> Java would reject it; treat as unsafe
                        return false;
                    }
                    if (pendingAtom == null) {
                        return false;
                    }
                    items.add(Item.loop(pendingAtom));
                    pendingAtom = null;
                    i = consumeQuantifierModifier(regex, afterBrace);
                    break;
                }
                default: {
                    pendingAtom = flushRequired(items, pendingAtom);
                    pendingAtom = CharSet.singleton(c, caseInsensitive);
                    i++;
                }
            }
        }
        flushRequired(items, pendingAtom);

        return noTwoLoopsCompete(items);
    }

    /**
     * Commit any pending atom as a required (min-1) atom - a candidate "wall" between loops - and
     * clear it. A null pending atom (an anchor or zero-width escape) commits nothing.
     */
    private static CharSet flushRequired(List<Item> items, CharSet pendingAtom) {
        if (pendingAtom != null) {
            items.add(Item.required(pendingAtom));
        }
        return null;
    }

    /**
     * The core linearity check. For every pair of loops, they are safe if their alphabets are
     * provably disjoint, or if some required atom lying between them in the pattern has an alphabet
     * provably disjoint from both (a hard wall neither loop can cross). If any pair is neither
     * disjoint nor walled, the two loops can compete for the same characters and the pattern may
     * backtrack super-linearly -> unsafe.
     */
    private static boolean noTwoLoopsCompete(List<Item> items) {
        List<Integer> loops = new ArrayList<>();
        for (int idx = 0; idx < items.size(); idx++) {
            if (items.get(idx).loop) {
                loops.add(idx);
            }
        }
        for (int a = 0; a < loops.size(); a++) {
            for (int b = a + 1; b < loops.size(); b++) {
                CharSet la = items.get(loops.get(a)).charSet;
                CharSet lb = items.get(loops.get(b)).charSet;
                if (CharSet.provablyDisjoint(la, lb)) {
                    continue;
                }
                if (!hasWallBetween(items, loops.get(a), loops.get(b), la, lb)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasWallBetween(List<Item> items, int from, int to, CharSet la, CharSet lb) {
        for (int k = from + 1; k < to; k++) {
            Item item = items.get(k);
            if (!item.loop
                && CharSet.provablyDisjoint(item.charSet, la)
                && CharSet.provablyDisjoint(item.charSet, lb)) {
                return true;
            }
        }
        return false;
    }

    /** Consume an optional lazy ({@code ?}) or possessive ({@code +}) quantifier modifier. */
    private static int consumeQuantifierModifier(String regex, int i) {
        if (i < regex.length()) {
            char c = regex.charAt(i);
            if (c == '?' || c == '+') {
                return i + 1;
            }
        }
        return i;
    }

    /**
     * @return the index just past a well-formed {@code {n}}, {@code {n,}} or {@code {n,m}} starting
     * at {@code start} (which points at '{'), or {@code -1} if the braces are not a valid quantifier.
     */
    private static int scanBraceQuantifier(String regex, int start) {
        int i = start + 1;
        int n = regex.length();
        int digitsBefore = 0;
        while (i < n && isDigit(regex.charAt(i))) {
            i++;
            digitsBefore++;
        }
        if (digitsBefore == 0) {
            return -1;
        }
        if (i < n && regex.charAt(i) == ',') {
            i++;
            while (i < n && isDigit(regex.charAt(i))) {
                i++;
            }
        }
        if (i < n && regex.charAt(i) == '}') {
            return i + 1;
        }
        return -1;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ---- escapes -------------------------------------------------------------------------------

    private static final class EscapeScan {
        final CharSet charSet;      // null for a zero-width anchor escape (\b, \A, ...)
        final int endExclusive;

        EscapeScan(CharSet charSet, int endExclusive) {
            this.charSet = charSet;
            this.endExclusive = endExclusive;
        }
    }

    /**
     * Interpret the escape starting at {@code start} (which points at '\\'). Returns {@code null}
     * for anything outside the analysable subset (backreferences, quoted regions, numeric/property
     * escapes we do not decode) so the caller resolves to unsafe.
     */
    private static EscapeScan scanEscape(String regex, int start, boolean caseInsensitive) {
        int i = start + 1;
        if (i >= regex.length()) {
            return null; // dangling backslash -> malformed
        }
        char e = regex.charAt(i);
        i++;
        switch (e) {
            // backreferences (numeric and named) can be exponential / NP-hard -> never inline
            case '1': case '2': case '3': case '4': case '5':
            case '6': case '7': case '8': case '9':
            case '0': // octal escape: decodes to a specific char we do not compute -> be conservative
            case 'k': // named backreference
            case 'Q': // \Q..\E quoted region: a quantifier could apply to the whole run
            case 'E':
            case 'x': // hex escape: specific char we do not decode
            case 'u': // unicode hex escape
            case 'c': // control escape
            case 'p': // \p{...} unicode property
            case 'P': // \P{...} negated unicode property
                return null;
            // zero-width anchors: not quantifiable, contribute no character set
            case 'b': case 'B': case 'A': case 'G': case 'z': case 'Z':
                return new EscapeScan(null, i);
            // predefined character classes with a known, analysable alphabet
            case 'd':
                return new EscapeScan(CharSet.digits(), i);
            case 'w':
                return new EscapeScan(CharSet.word(), i);
            case 's':
                return new EscapeScan(CharSet.whitespace(), i);
            // complemented predefined classes are effectively universal for disjointness purposes
            case 'D': case 'W': case 'S':
                return new EscapeScan(CharSet.universal(), i);
            default: {
                int decoded = decodeSingleCharEscape(e);
                if (decoded < 0) {
                    return null; // not a single character we can model -> be conservative
                }
                return new EscapeScan(CharSet.singleton((char) decoded, caseInsensitive), i);
            }
        }
    }

    /**
     * Decode an escape to the character it actually matches, or {@code -1} when it is not a plain
     * single character (a predefined class, an anchor, a backreference, or a numeric/property escape
     * we deliberately do not decode).
     * <p>
     * An escape must NEVER be modelled as the letter that follows the backslash: {@code \t} matches
     * a TAB, not a {@code 't'}. Getting that wrong under-approximates the alphabet, which makes
     * {@link #isAnchoredInlineSafe} claim two adjacent quantified atoms are disjoint when they in
     * fact overlap -- exactly the quadratic backtracking the classifier exists to keep out of the
     * event loop. Callers MUST treat {@code -1} as "not analysable" and resolve to unsafe/universal.
     */
    private static int decodeSingleCharEscape(char e) {
        switch (e) {
            case 't': return '\t';   // 0x09
            case 'n': return '\n';   // 0x0A
            case 'r': return '\r';   // 0x0D
            case 'f': return '\f';   // 0x0C
            case 'a': return 0x07;   // alert (bell)
            case 'e': return 0x1B;   // escape
            default:
                // Java reads '\' + non-alphanumeric as that literal character (\., \*, \\, \-, ...).
                // Every alphanumeric escape not decoded above is a class, an anchor, a backreference
                // or an undecoded numeric escape, so it is not a single character we can model.
                return Character.isLetterOrDigit(e) ? -1 : e;
        }
    }

    // ---- character classes ---------------------------------------------------------------------

    private static final class ClassScan {
        final CharSet charSet;
        final int endExclusive;

        ClassScan(CharSet charSet, int endExclusive) {
            this.charSet = charSet;
            this.endExclusive = endExclusive;
        }
    }

    /**
     * Scan a character class {@code [...]} starting at {@code start} (which points at '['). Computes
     * an exact ASCII alphabet for simple positive classes; falls back to a universal set (still
     * locating the correct end) for negation, intersection, complemented sub-classes, unicode
     * properties or any non-ASCII content - universal is never provably disjoint from anything, so
     * such a class simply keeps its loop from being proven independent of another. Returns
     * {@code null} only if the class is malformed (no closing ']').
     */
    private static ClassScan scanCharClass(String regex, int start, boolean caseInsensitive) {
        int n = regex.length();
        int i = start + 1;
        boolean universal = false;
        CharSet set = CharSet.empty();

        if (i < n && regex.charAt(i) == '^') {
            // negated class: its alphabet is a complement -> treat as universal for disjointness
            universal = true;
            i++;
        }
        // a ']' as the very first class member (after optional '^') is a literal, not the terminator
        boolean first = true;

        while (i < n) {
            char c = regex.charAt(i);
            if (c == ']' && !first) {
                CharSet result = universal ? CharSet.universal() : set;
                return new ClassScan(result, i + 1);
            }
            first = false;
            if (c == '&' && i + 1 < n && regex.charAt(i + 1) == '&') {
                // class intersection -> not analysed; universal (still must find the end)
                universal = true;
                i += 2;
                continue;
            }
            if (c == '[') {
                // A nested class start. Scanning on would terminate at the INNER ']' and hand the
                // caller an end offset in the middle of this class, so the remainder of the pattern
                // would be parsed as top-level regex -- misplacing quantifier boundaries. There is
                // no safe recovery: refuse the pattern.
                return null;
            }
            if (c == '\\') {
                if (i + 1 >= n) {
                    return null; // dangling escape inside class
                }
                char e = regex.charAt(i + 1);
                switch (e) {
                    case 'd':
                        if (!universal) {
                            set.addAll(CharSet.digits());
                        }
                        break;
                    case 'w':
                        if (!universal) {
                            set.addAll(CharSet.word());
                        }
                        break;
                    case 's':
                        if (!universal) {
                            set.addAll(CharSet.whitespace());
                        }
                        break;
                    case 'D': case 'W': case 'S': case 'p': case 'P':
                        universal = true; // complemented / property sub-class
                        break;
                    case 'x': case 'u': case '0': case 'c':
                        universal = true; // undecoded numeric/control escape
                        break;
                    default: {
                        int decoded = decodeSingleCharEscape(e);
                        if (decoded < 0 || !addLiteralToClass(set, (char) decoded, caseInsensitive)) {
                            universal = true;
                        }
                    }
                }
                // an escaped low endpoint of a range ([\t-z]) -- the range branch below only sees
                // unescaped endpoints, so without this the range would be modelled as its two ends
                // alone, under-approximating the alphabet
                if (i + 2 < n && regex.charAt(i + 2) == '-' && i + 3 < n && regex.charAt(i + 3) != ']') {
                    universal = true;
                }
                i += 2;
                continue;
            }
            // possible range a-z
            if (i + 2 < n && regex.charAt(i + 1) == '-' && regex.charAt(i + 2) != ']') {
                char lo = c;
                char hi = regex.charAt(i + 2);
                if (hi == '\\') {
                    // escaped range endpoint -> do not analyse precisely
                    universal = true;
                    i += 3;
                    continue;
                }
                if (!universal && !addRangeToClass(set, lo, hi, caseInsensitive)) {
                    universal = true;
                }
                i += 3;
                continue;
            }
            // single literal member
            if (!universal && !addLiteralToClass(set, c, caseInsensitive)) {
                universal = true;
            }
            i++;
        }
        return null; // no closing ']'
    }

    private static boolean addLiteralToClass(CharSet set, char c, boolean caseInsensitive) {
        if (c > 0x7F) {
            return false; // non-ASCII: fold conservatively -> universal
        }
        set.addChar(c, caseInsensitive);
        return true;
    }

    private static boolean addRangeToClass(CharSet set, char lo, char hi, boolean caseInsensitive) {
        if (lo > 0x7F || hi > 0x7F || lo > hi) {
            return false; // non-ASCII or inverted range -> universal
        }
        set.addRange(lo, hi, caseInsensitive);
        return true;
    }

    // ---- items ---------------------------------------------------------------------------------

    /** One parsed element: a quantifier LOOP, or a required (min-1) single atom (WALL candidate). */
    private static final class Item {
        final boolean loop;
        final CharSet charSet;

        private Item(boolean loop, CharSet charSet) {
            this.loop = loop;
            this.charSet = charSet;
        }

        static Item loop(CharSet charSet) {
            return new Item(true, charSet);
        }

        static Item required(CharSet charSet) {
            return new Item(false, charSet);
        }
    }

    // ---- character sets ------------------------------------------------------------------------

    /**
     * A character set over the ASCII range 0..127, plus a {@code universal} flag standing for "any
     * character, possibly including non-ASCII or a complement". Only exact ASCII sets can be proven
     * disjoint from one another; a universal set is never provably disjoint from a non-empty set.
     */
    private static final class CharSet {
        private final boolean universal;
        private final BitSet bits; // ASCII 0..127; null when universal

        private CharSet(boolean universal, BitSet bits) {
            this.universal = universal;
            this.bits = bits;
        }

        static CharSet universal() {
            return new CharSet(true, null);
        }

        static CharSet empty() {
            return new CharSet(false, new BitSet(128));
        }

        static CharSet singleton(char c, boolean caseInsensitive) {
            if (c > 0x7F) {
                return universal();
            }
            CharSet s = empty();
            s.addChar(c, caseInsensitive);
            return s;
        }

        static CharSet digits() {
            CharSet s = empty();
            s.bits.set('0', '9' + 1);
            return s;
        }

        static CharSet word() {
            // [A-Za-z0-9_] - case folding does not change its membership
            CharSet s = empty();
            s.bits.set('a', 'z' + 1);
            s.bits.set('A', 'Z' + 1);
            s.bits.set('0', '9' + 1);
            s.bits.set('_');
            return s;
        }

        static CharSet whitespace() {
            // \s == [ \t\n\x0B\f\r]
            CharSet s = empty();
            s.bits.set(' ');
            s.bits.set('\t');
            s.bits.set('\n');
            s.bits.set(0x0B);
            s.bits.set('\f');
            s.bits.set('\r');
            return s;
        }

        void addChar(char c, boolean caseInsensitive) {
            if (c > 0x7F) {
                return; // caller has already decided how to treat non-ASCII
            }
            bits.set(c);
            if (caseInsensitive) {
                char lower = Character.toLowerCase(c);
                char upper = Character.toUpperCase(c);
                if (lower <= 0x7F) {
                    bits.set(lower);
                }
                if (upper <= 0x7F) {
                    bits.set(upper);
                }
            }
        }

        void addRange(char lo, char hi, boolean caseInsensitive) {
            for (char c = lo; c <= hi; c++) {
                addChar(c, caseInsensitive);
                if (c == 0x7F) {
                    break; // guard against char overflow
                }
            }
        }

        void addAll(CharSet other) {
            if (other.universal || bits == null) {
                return;
            }
            bits.or(other.bits);
        }

        /**
         * @return {@code true} only when the two sets provably share no character. A universal set
         * is never provably disjoint from a non-empty set; two exact ASCII sets are disjoint iff
         * their bitsets do not intersect.
         */
        static boolean provablyDisjoint(CharSet a, CharSet b) {
            if (a.universal || b.universal) {
                if (a.universal && b.universal) {
                    return false;
                }
                CharSet other = a.universal ? b : a;
                return other.bits != null && other.bits.isEmpty();
            }
            return !a.bits.intersects(b.bits);
        }
    }
}
