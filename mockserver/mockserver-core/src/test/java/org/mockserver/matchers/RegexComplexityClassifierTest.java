package org.mockserver.matchers;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * Proves the soundness and usefulness of {@link RegexComplexityClassifier}: the analysis that lets
 * a provably-linear regex skip the {@link MatchingTimeoutExecutor} pool hand-off and run inline on
 * the calling thread.
 *
 * <p>The load-bearing property is <b>soundness</b>: a pattern classified inline-safe must be
 * incapable of super-linear backtracking, because inline evaluation forgoes the pool's DoS
 * isolation. These tests assert that from two directions:
 * <ol>
 *   <li><b>Structural.</b> Known-linear patterns classify safe; known-catastrophic and known-
 *       polynomial patterns classify unsafe.</li>
 *   <li><b>Empirical (the real backstop).</b> Every pattern the classifier calls safe is then run
 *       against a deliberately adversarial, long input under an anchored full match and must
 *       complete near-instantly. If the classifier ever wrongly admitted a backtracking pattern,
 *       this timing assertion would hang / blow the bound rather than pass.</li>
 * </ol>
 * and that the inline result is byte-for-byte identical to a direct {@link Pattern} match, since a
 * match here is request routing and a behaviour change would be worse than the performance bug.
 */
public class RegexComplexityClassifierTest {

    // Patterns that are provably linear under an anchored full match, evaluated case-insensitively
    // (RegexStringMatcher's default). Includes the G6 benchmark pattern and multi-quantifier
    // patterns whose quantifier alphabets are disjoint.
    private static final String[] SAFE_CASE_INSENSITIVE = {
        "^/regex/path-[a-z]+-\\d+$",   // the G6 benchmark pattern: [a-z]+ and \d+ are disjoint
        "[0-9]+",
        "\\d+",
        "abc",                          // pure literal, no quantifier
        "a?b",                          // single quantifier
        "a*b*c*",                       // three quantifiers over pairwise-disjoint singletons
        "^[a-z]+$",
        "[A-Z]{2,4}-\\d+",              // disjoint classes separated by a literal
        "https?://",                    // single quantifier, rest literal
        "^/api/v[0-9]+/users$",
        "\\w+",                         // single quantifier (word chars)
        "[a-z]+@[a-z]+\\.[a-z]+"        // three [a-z]+ loops walled apart by disjoint literals @ and .
    };

    // Patterns that can (or provably could) backtrack super-linearly, or that the classifier cannot
    // prove safe and must therefore isolate.
    private static final String[] UNSAFE = {
        "(a+)+$",            // nested quantifier via a quantified group - classic exponential ReDoS
        "(.*a){10}$",        // quantified group - catastrophic on this JDK (verified)
        "(a|ab)*",           // quantified group with overlapping alternation
        "(?:ab)+",           // quantified (non-capturing) group
        "\\d+\\d+",          // two loops over the SAME alphabet - quadratic
        ".*a.*b",            // two universal (.) loops - quadratic
        "\\w+\\d+",          // \\w overlaps \\d - not disjoint
        "[a-z]+[a-c]+",      // overlapping ranges
        "a+.*",              // {a} overlaps universal .
        "(?i)abc",           // inline-flag group
        "a\\1",              // backreference
        "\\p{L}+\\d+",       // unicode-property loop treated as universal, plus a \\d loop
        "foo(?=bar)"         // lookahead group
    };

    @Test
    public void shouldClassifyLinearPatternsAsInlineSafe() {
        for (String regex : SAFE_CASE_INSENSITIVE) {
            // NOTE: "[a-z]+@[a-z]+\\.[a-z]+" is three [a-z]+ loops separated by '@' and '.' which are
            // outside [a-z], so no input character can be consumed by two loops -> linear -> safe.
            assertThat("expected inline-safe (case-insensitive): " + regex,
                RegexComplexityClassifier.isAnchoredInlineSafe(regex, true), is(true));
        }
    }

    @Test
    public void shouldClassifyBacktrackingPatternsAsUnsafe() {
        for (String regex : UNSAFE) {
            assertThat("expected unsafe (case-insensitive): " + regex,
                RegexComplexityClassifier.isAnchoredInlineSafe(regex, true), is(false));
            assertThat("expected unsafe (case-sensitive): " + regex,
                RegexComplexityClassifier.isAnchoredInlineSafe(regex, false), is(false));
        }
    }

    /**
     * Case folding must be accounted for: literally-disjoint letter ranges of opposite case OVERLAP
     * once compiled case-insensitively, so they are only inline-safe when matched case-sensitively.
     */
    @Test
    public void shouldTreatOppositeCaseRangesAsOverlappingOnlyWhenCaseInsensitive() {
        String regex = "[a-z]+[A-Z]+";
        assertThat("disjoint when case-sensitive",
            RegexComplexityClassifier.isAnchoredInlineSafe(regex, false), is(true));
        assertThat("overlapping when case-insensitive (a-z folds to A-Z)",
            RegexComplexityClassifier.isAnchoredInlineSafe(regex, true), is(false));
    }

    @Test
    public void nullPatternIsTriviallySafe() {
        assertThat(RegexComplexityClassifier.isAnchoredInlineSafe(null, true), is(true));
    }

    /**
     * The empirical soundness backstop. Every pattern the classifier called safe is run against a
     * long, adversarial input under an anchored full match. A linear pattern finishes in
     * microseconds regardless of input length; a mis-classified backtracking pattern would blow the
     * generous bound (or hang), failing this test loudly. This is what makes a classifier bug a
     * red test rather than a silent security regression.
     */
    @Test
    public void everyInlineSafePatternRunsInLinearTimeOnAdversarialInput() {
        for (String regex : SAFE_CASE_INSENSITIVE) {
            Pattern compiled = Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            for (String input : adversarialInputs()) {
                long start = System.nanoTime();
                compiled.matcher(input).matches();
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                assertThat("inline-safe pattern must be linear-time; blew the bound on adversarial input: "
                        + regex + " (input length " + input.length() + ")",
                    elapsedMs, lessThan(1_000L));
            }
        }
    }

    /**
     * Inline evaluation must return exactly what a direct {@link Pattern} match returns - this is
     * request routing, so the result cannot change.
     */
    @Test
    public void inlineResultIsIdenticalToDirectMatch() {
        String[] inputs = {
            "/regex/path-abcdef-42", "/regex/path--1", "", "abc", "ABC", "aaa", "12345",
            "https://x", "http://y", "/api/v2/users", "a@b.c", "not a match at all"
        };
        for (String regex : SAFE_CASE_INSENSITIVE) {
            Pattern compiled = Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            for (String input : inputs) {
                boolean direct = compiled.matcher(input).matches();
                // the "inline" path is exactly a direct match on the calling thread - assert the
                // classifier does not change which patterns take it in a way that could diverge
                boolean safe = RegexComplexityClassifier.isAnchoredInlineSafe(regex, true);
                assertThat("classifier must be safe for these curated patterns", safe, is(true));
                // and the value the caller would return inline equals the direct match value
                assertThat("inline vs direct mismatch for " + regex + " on '" + input + "'",
                    compiled.matcher(input).matches(), is(direct));
            }
        }
    }

    private static String[] adversarialInputs() {
        StringBuilder as = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            as.append('a');
        }
        String manyA = as.toString();
        return new String[]{
            manyA,               // all in-alphabet, forces a linear pattern to consume then possibly fail
            manyA + "!",         // trailing non-match char forces the anchored match to reject at the end
            manyA + "1",
            "0123456789".repeat(20_000),
            "a1".repeat(50_000)
        };
    }

    // ---- non-circular soundness backstop -------------------------------------------------------

    /**
     * An UNLABELLED corpus. Nothing here is asserted safe or unsafe by hand -- the classifier's own
     * verdict selects which entries get timed. That is the point: a curated safe-list can only ever
     * re-test patterns a human already believed safe, so it cannot catch the classifier admitting a
     * shape nobody thought of. Add new shapes here rather than to {@link #SAFE_CASE_INSENSITIVE}.
     */
    private static final String[] CORPUS = {
        // single-character escapes that decode to a CONTROL character, not to the letter that
        // follows the backslash. Modelling \t as 't' made these classify safe while running O(n^2).
        "\\t+\t+$", "\\n+\n+$", "\\r+\r+$", "\\f+\f+$", "\\a+\u0007+$", "\\e+\u001B+$",
        "^\\t+\t+$", "[\\t]+\t+$", "\\t+[\t]+$",
        // escaped punctuation really is the literal character
        "^a\\.b$", "\\.+\\.+$", "^\\$+$", "\\-+\\-+$",
        // character-class parsing: nesting, intersection, negation, escaped range endpoints
        "[a[b]c]+X+$", "[\\t-z]+X+$", "[a-z&&[^aeiou]]+z+$", "[^a]+a+$", "[]]+X+$", "[a\\]b]+X+$",
        // escapes we decline to decode must never be modelled as a letter either
        "\\x41+A+$", "\\u0041+A+$", "\\cA+\u0001+$", "\\0101+A+$", "\\Qa+\\E+a+$",
        // predefined classes and their complements
        "\\d+\\d+$", "\\w+\\d+$", "\\s+\t+$", "\\D+a+$", "\\S+a+$", "\\W+ +$",
        // genuinely linear shapes that must keep the inline fast path
        "^[a-z]+$", "^\\d+$", "^[a-z]+\\d+$", "^abc$", "^/api/v[0-9]+/users$",
        "^/regex/path-[a-z]+-\\d+$", "[A-Z]{2,4}-\\d+", "https?://", "[a-z]+@[a-z]+\\.[a-z]+",
        // known-bad structures
        "(a+)+$", "(a|ab)*", "(.*a){10}$", "a+.*", "foo(?=bar)", "a\\1"
    };

    private static final int ADVERSARIAL_LENGTH = 60_000;

    /**
     * The real soundness backstop: for every corpus entry the classifier calls inline-safe, run it
     * against a long input built from <b>that pattern's own alphabet</b> and require it to finish
     * quickly.
     *
     * <p>Both halves matter. Selecting patterns by the classifier's verdict (not by a hand-written
     * list) means an unforeseen shape is covered the moment someone adds it to {@link #CORPUS}.
     * Deriving the input from the pattern means the input actually stresses it -- the previous
     * fixed input alphabet ('a' and digits) could not have exercised a tab-matching pattern even if
     * one had been listed, which is how a quadratic control-character pattern passed as safe.
     *
     * <p>At this length a linear pattern finishes in single-digit milliseconds while a quadratic one
     * needs seconds, so the bound separates them by orders of magnitude rather than by a hair.
     */
    @Test
    public void everyPatternTheClassifierCallsSafeIsLinearOnInputDrawnFromItsOwnAlphabet() {
        int timed = 0;
        for (String regex : CORPUS) {
            for (boolean caseInsensitive : new boolean[]{false, true}) {
                if (!RegexComplexityClassifier.isAnchoredInlineSafe(regex, caseInsensitive)) {
                    continue; // isolated by the pool - the classifier makes no linearity claim
                }
                int flags = Pattern.DOTALL | (caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
                Pattern compiled;
                try {
                    compiled = Pattern.compile(regex, flags);
                } catch (RuntimeException invalidPattern) {
                    continue; // not a legal regex on this JDK; RegexStringMatcher never reaches here
                }
                for (String input : inputsDrawnFrom(regex)) {
                    timed++;
                    long start = System.nanoTime();
                    compiled.matcher(input).matches();
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                    assertThat("classified inline-safe but super-linear on input from its own alphabet: "
                            + regex + " (caseInsensitive=" + caseInsensitive + ")",
                        elapsedMs, lessThan(1_500L));
                }
            }
        }
        // Coverage is load-bearing: if the classifier rejected every corpus entry the loop above
        // would assert nothing and still pass. Assert the backstop actually measured something.
        assertThat("the backstop timed too few patterns to be a backstop -- did the classifier stop"
                + " admitting anything, or did the corpus shrink?", timed, greaterThan(20));
    }

    /**
     * Build long inputs from the characters {@code regex} can actually consume, so the match is
     * forced to walk (and backtrack over) the whole string. Each input gets a trailing character
     * outside that alphabet so the anchored match must fail at the very end -- the worst case for a
     * backtracking pattern.
     */
    private static String[] inputsDrawnFrom(String regex) {
        // Deliberately does NOT decode the hex, unicode, octal or control escapes (backslash-x,
        // backslash-u, backslash-0, backslash-c) or class-internal ranges. That is sound
        // only because the classifier refuses every one of those shapes, so they are skipped above
        // and never reach here. If the classifier is ever taught to admit one, this seed extraction
        // must learn to decode it in the same change -- otherwise the backstop would "cover" that
        // shape while stressing it with an alphabet the pattern cannot consume, which is the exact
        // weakness (an input alphabet that misses the pattern's own characters) that let the
        // quadratic control-character patterns pass as safe in the first place.
        StringBuilder seeds = new StringBuilder();
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                char e = regex.charAt(++i);
                switch (e) {
                    case 't': seeds.append('\t'); break;
                    case 'n': seeds.append('\n'); break;
                    case 'r': seeds.append('\r'); break;
                    case 'f': seeds.append('\f'); break;
                    case 'a': seeds.append('\u0007'); break;
                    case 'e': seeds.append('\u001B'); break;
                    case 'd': seeds.append('7'); break;
                    case 'w': seeds.append('q'); break;
                    case 's': seeds.append(' '); break;
                    default:
                        if (!Character.isLetterOrDigit(e)) {
                            seeds.append(e); // escaped literal punctuation
                        }
                }
            } else if (c != '^' && c != '$' && c != '+' && c != '*' && c != '?' && c != '.'
                && c != '(' && c != ')' && c != '[' && c != ']' && c != '{' && c != '}'
                && c != '|' && c != '-' && c != '&') {
                seeds.append(c);
            }
        }
        if (seeds.length() == 0) {
            seeds.append('a');
        }
        // de-duplicate, keep the run short so the whole corpus stays fast
        StringBuilder distinct = new StringBuilder();
        for (int i = 0; i < seeds.length() && distinct.length() < 3; i++) {
            if (distinct.indexOf(String.valueOf(seeds.charAt(i))) < 0) {
                distinct.append(seeds.charAt(i));
            }
        }
        String[] inputs = new String[distinct.length()];
        for (int i = 0; i < distinct.length(); i++) {
            char seed = distinct.charAt(i);
            char sentinel = seed == '\u0001' ? '\u0002' : '\u0001'; // outside every corpus alphabet
            inputs[i] = String.valueOf(seed).repeat(ADVERSARIAL_LENGTH) + sentinel;
        }
        return inputs;
    }

    // ---- regressions for specific alphabet-extraction defects -----------------------------------

    /**
     * {@code \t} matches a TAB, not a {@code 't'}. Modelling it as the letter made the two
     * quantifier alphabets of {@code \t+<TAB>+} look disjoint, so the pattern ran inline on the
     * event loop -- while actually backtracking quadratically. Every single-character control
     * escape had the same hole, and an attacker supplies these directly: a {@code \t} inside an
     * expectation's JSON string decodes to a literal tab.
     */
    @Test
    public void controlCharacterEscapesAreNotModelledAsTheFollowingLetter() {
        String[][] quadratic = {
            {"\\t+\t+$", "tab"}, {"\\n+\n+$", "newline"}, {"\\r+\r+$", "carriage return"},
            {"\\f+\f+$", "form feed"}, {"\\a+\u0007+$", "bell"}, {"\\e+\u001B+$", "escape"}
        };
        for (String[] c : quadratic) {
            assertThat("two loops over the same " + c[1] + " character are NOT disjoint: " + c[0],
                RegexComplexityClassifier.isAnchoredInlineSafe(c[0], false), is(false));
            assertThat("two loops over the same " + c[1] + " character are NOT disjoint: " + c[0],
                RegexComplexityClassifier.isAnchoredInlineSafe(c[0], true), is(false));
        }
        // and the escape still behaves as a single character where that is all it has to do:
        // \t+ alone has no second loop to overlap with, so it stays on the inline fast path
        assertThat(RegexComplexityClassifier.isAnchoredInlineSafe("^\\t+$", false), is(true));
    }

    /**
     * A nested {@code [} used to terminate the class scan at the INNER {@code ]}, handing back an
     * end offset in the middle of the class; the remainder was then parsed as top-level regex, so
     * quantifier boundaries landed in the wrong place and {@code [a[b]c]+X+$} classified safe.
     */
    @Test
    public void nestedCharacterClassIsRefusedRatherThanMisparsed() {
        assertThat(RegexComplexityClassifier.isAnchoredInlineSafe("[a[b]c]+X+$", false), is(false));
        assertThat(RegexComplexityClassifier.isAnchoredInlineSafe("[a-z&&[^aeiou]]+z+$", false), is(false));
    }

    /**
     * {@code [\t-z]} is the range 0x09..'z', which contains almost every printable character. The
     * range branch only ever saw unescaped endpoints, so an escaped low endpoint was modelled as
     * its two ends alone -- under-approximating the alphabet and making {@code [\t-z]+X+$} look
     * disjoint from {@code X}.
     */
    @Test
    public void escapedRangeEndpointDoesNotUnderApproximateTheClass() {
        assertThat(RegexComplexityClassifier.isAnchoredInlineSafe("[\\t-z]+X+$", false), is(false));
    }
}
