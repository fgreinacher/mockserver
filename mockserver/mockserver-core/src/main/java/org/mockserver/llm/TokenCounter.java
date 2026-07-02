package org.mockserver.llm;

import org.mockserver.model.StreamingPhysics;
import org.mockserver.model.ToolUse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure, deterministic <strong>approximate</strong> token counter and lightweight
 * subword segmenter for LLM text.
 *
 * <p><strong>This is an estimate, not a real tokenizer.</strong> It does
 * <em>not</em> load byte-pair encoding (BPE) merges, SentencePiece, or any
 * provider's exact vocabulary — no dependency, no embedded vocabulary. Instead
 * it approximates GPT-style BPE segmentation with a handful of cheap structural
 * rules, so its counts <em>land close to</em> a provider's billed counts
 * (typically within roughly &plusmn;15% for ordinary English prose, further off
 * for code, non-Latin scripts, or long runs of punctuation). It exists so
 * MockServer can populate <em>plausible</em> {@code usage} numbers when a mocked
 * completion omits them, back the rough token-quota accounting, and drive
 * subword-granular streaming deltas.
 *
 * <h2>Segmentation heuristic (BPE approximation)</h2>
 * The text is walked once and split into runs, each scored/segmented by class:
 * <ul>
 *   <li><strong>Words</strong> (letters/digits, with {@code - ' _} treated as
 *       word-internal so {@code open-source} stays one word): a leading space is
 *       absorbed (real BPE merges a leading space into the following token), the
 *       word is split at {@code lower&rarr;Upper} case boundaries and
 *       letter&harr;digit boundaries (so {@code ChatGPT}&rarr;{@code Chat},{@code GPT}),
 *       and each resulting sub-run costs {@code 1} token plus one extra per ~5
 *       characters beyond the first five (so short/common words stay whole while
 *       long words split into subword units).</li>
 *   <li><strong>CJK</strong> (Han/Hangul ~1.2 tokens/char, Kana ~0.5): scripts
 *       that tokenizers split far more densely than Latin text.</li>
 *   <li><strong>Punctuation/symbols</strong>: ~1 token each — BPE tends to split
 *       these into their own tokens.</li>
 *   <li><strong>Whitespace</strong>: a plain space is free (absorbed into the
 *       next word); each newline/tab costs ~1 token.</li>
 * </ul>
 * The result is clamped to at least {@code 1} for any non-empty text (every real
 * request costs at least one token) and {@code 0} for {@code null}/empty.
 *
 * <p>The methods are pure and side-effect free: the same input always yields the
 * same result, so they never make a test flaky.
 */
public final class TokenCounter {

    /**
     * Extra tokens accrue once per this many characters beyond the first
     * {@link #WORD_WHOLE_LEN}. ~5 chars/token matches real BPE for uncommon words
     * while keeping typical short words a single token.
     */
    private static final int WORD_SPLIT_STRIDE = 5;

    /** Words up to this many characters (per case/digit sub-run) stay a single token. */
    private static final int WORD_WHOLE_LEN = 5;

    /** Approximate tokens per Han/Hangul character. */
    private static final double CJK_DENSE_WEIGHT = 1.2;

    /** Approximate tokens per Kana character (denser merging than Han). */
    private static final double CJK_KANA_WEIGHT = 0.5;

    /** Subword chunk size (in chars) for streaming deltas when subword streaming is enabled. */
    private static final int STREAM_SUBWORD_LEN = 4;

    private TokenCounter() {
    }

    /**
     * Estimate the approximate token count for a single piece of text using the
     * BPE-approximating segmentation described in the class javadoc.
     *
     * @param text the text to estimate (may be {@code null})
     * @return {@code 0} for {@code null}/empty text, otherwise an approximate
     *         token count {@code >= 1}
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0.0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                while (i < n) {
                    int c = text.codePointAt(i);
                    if (!Character.isWhitespace(c)) {
                        break;
                    }
                    // a plain space is absorbed into the following word (free);
                    // a newline/tab is its own token
                    if (c == '\n' || c == '\t' || c == '\r' || c == '\f') {
                        tokens += 1;
                    }
                    i += Character.charCount(c);
                }
            } else if (isCjk(cp)) {
                while (i < n) {
                    int c = text.codePointAt(i);
                    if (!isCjk(c)) {
                        break;
                    }
                    tokens += cjkWeight(c);
                    i += Character.charCount(c);
                }
            } else if (isWordChar(cp)) {
                int prevType = -1; // 0 lower letter, 1 upper letter, 2 digit, 3 connector
                int subLen = 0;
                while (i < n) {
                    int c = text.codePointAt(i);
                    if (!isWordChar(c)) {
                        break;
                    }
                    int type;
                    if (isWordConnector(c)) {
                        type = 3;
                    } else if (Character.isDigit(c)) {
                        type = 2;
                    } else if (Character.isUpperCase(c)) {
                        type = 1;
                    } else {
                        type = 0;
                    }
                    boolean boundary;
                    if (prevType == -1 || type == 3 || prevType == 3) {
                        boundary = false;
                    } else if (prevType == 0 && type == 1) {
                        boundary = true; // lower -> Upper (camelCase)
                    } else if ((prevType == 0 || prevType == 1) && type == 2) {
                        boundary = true; // letter -> digit
                    } else if (prevType == 2 && (type == 0 || type == 1)) {
                        boundary = true; // digit -> letter
                    } else {
                        boundary = false;
                    }
                    if (boundary && subLen > 0) {
                        tokens += wordSubRunTokens(subLen);
                        subLen = 0;
                    }
                    subLen++;
                    prevType = type;
                    i += Character.charCount(c);
                }
                if (subLen > 0) {
                    tokens += wordSubRunTokens(subLen);
                }
            } else {
                // punctuation / symbol run — ~1 token each
                while (i < n) {
                    int c = text.codePointAt(i);
                    if (Character.isWhitespace(c) || isWordChar(c) || isCjk(c)) {
                        break;
                    }
                    tokens += 1;
                    i += Character.charCount(c);
                }
            }
        }
        return Math.max(1, (int) Math.round(tokens));
    }

    /**
     * Estimate the approximate prompt (input) token count for a decoded
     * conversation: the sum of the per-message text estimates plus a small
     * fixed per-message overhead (real chat formats wrap each message in role
     * markers / delimiters that cost a few tokens). Tool-call arguments and tool
     * results carried on a message are included in its text estimate.
     *
     * @param conversation the decoded conversation (may be {@code null})
     * @return {@code 0} for a {@code null}/empty conversation, otherwise an
     *         approximate prompt token count {@code >= 1}
     */
    public static int estimatePromptTokens(ParsedConversation conversation) {
        if (conversation == null || conversation.getMessages() == null || conversation.getMessages().isEmpty()) {
            return 0;
        }
        long total = 0;
        for (ParsedMessage message : conversation.getMessages()) {
            if (message == null) {
                continue;
            }
            // ~3 tokens of per-message chat-format overhead (role marker + delimiters).
            total += 3;
            total += estimateTokens(message.getTextContent());
            List<ToolUse> toolCalls = message.getToolCalls();
            if (toolCalls != null) {
                for (ToolUse toolCall : toolCalls) {
                    if (toolCall != null) {
                        total += estimateTokens(toolCall.getName());
                        total += estimateTokens(toolCall.getArguments());
                    }
                }
            }
            if (message.getToolResults() != null) {
                for (String result : message.getToolResults().values()) {
                    total += estimateTokens(result);
                }
            }
        }
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Estimate the approximate completion (output) token count for the text and
     * tool-call arguments a mocked completion would return.
     *
     * @param text      the response text (may be {@code null})
     * @param toolCalls the response tool calls (may be {@code null}/empty)
     * @return an approximate completion token count ({@code 0} when there is no
     *         output at all)
     */
    public static int estimateCompletionTokens(String text, List<ToolUse> toolCalls) {
        long total = estimateTokens(text);
        if (toolCalls != null) {
            for (ToolUse toolCall : toolCalls) {
                if (toolCall != null) {
                    total += estimateTokens(toolCall.getName());
                    total += estimateTokens(toolCall.getArguments());
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Split completion text into the ordered list of streamed delta strings for a
     * streaming response, honouring the streaming physics' {@code subwordStreaming}
     * mode.
     *
     * <p>When {@code subwordStreaming} is on — which is now the <strong>default</strong>
     * for a {@code null} physics or an unset flag — the text is segmented into
     * subword-sized pieces via {@link #segmentForStreaming(String)}, giving finer,
     * more realistic per-token deltas. Only an <em>explicit</em>
     * {@code subwordStreaming=false} selects the legacy whitespace-boundary split
     * (each word and each whitespace run its own delta). Either way the
     * concatenation of the returned pieces equals the input text exactly, and every
     * piece is non-empty.
     *
     * @param text    the completion text (may be {@code null}/empty)
     * @param physics the streaming physics (may be {@code null})
     * @return the ordered, non-empty delta pieces (empty list for {@code null}/empty text)
     */
    public static List<String> streamingTextTokens(String text, StreamingPhysics physics) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        // Subword streaming is the default: only an explicit subwordStreaming=false
        // (not null / unset) opts back into the legacy whole-word/whitespace split.
        boolean subword = physics == null || !Boolean.FALSE.equals(physics.getSubwordStreaming());
        if (subword) {
            return segmentForStreaming(text);
        }
        List<String> out = new ArrayList<>();
        for (String token : text.split("(?<=\\s)|(?=\\s)")) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * Segment text into subword-sized, concatenation-exact pieces approximating
     * BPE tokens for streaming. A leading space is absorbed into the following
     * word's first piece (BPE-style), words are chunked into
     * {@value #STREAM_SUBWORD_LEN}-character subword units, CJK and punctuation
     * characters are emitted individually. The pieces joined in order equal the
     * input exactly.
     *
     * @param text the text to segment (may be {@code null}/empty)
     * @return the ordered, non-empty pieces (empty list for {@code null}/empty text)
     */
    public static List<String> segmentForStreaming(String text) {
        List<String> pieces = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return pieces;
        }
        StringBuilder pendingWs = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            int cc = Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                pendingWs.appendCodePoint(cp);
                i += cc;
            } else if (isWordChar(cp)) {
                int start = i;
                while (i < n) {
                    int c = text.codePointAt(i);
                    if (!isWordChar(c)) {
                        break;
                    }
                    i += Character.charCount(c);
                }
                String word = text.substring(start, i);
                int p = 0;
                int len = word.length();
                boolean first = true;
                while (p < len) {
                    int end = Math.min(len, p + STREAM_SUBWORD_LEN);
                    String chunk = word.substring(p, end);
                    if (first) {
                        pieces.add(drain(pendingWs) + chunk);
                        first = false;
                    } else {
                        pieces.add(chunk);
                    }
                    p = end;
                }
            } else {
                // CJK char or punctuation/symbol — its own delta
                pieces.add(drain(pendingWs) + new String(Character.toChars(cp)));
                i += cc;
            }
        }
        if (pendingWs.length() > 0) {
            // trailing whitespace with no following content — its own delta
            pieces.add(pendingWs.toString());
        }
        return pieces;
    }

    private static String drain(StringBuilder pendingWs) {
        if (pendingWs.length() == 0) {
            return "";
        }
        String ws = pendingWs.toString();
        pendingWs.setLength(0);
        return ws;
    }

    /** Tokens for a single case/digit sub-run of a word of the given char length. */
    private static double wordSubRunTokens(int length) {
        return 1 + Math.max(0, Math.floor((double) Math.max(0, length - WORD_WHOLE_LEN) / WORD_SPLIT_STRIDE));
    }

    private static boolean isWordConnector(int cp) {
        return cp == '-' || cp == '\'' || cp == '’' || cp == '_';
    }

    private static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp) && !isCjk(cp) || isWordConnector(cp);
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(cp);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return script == Character.UnicodeScript.HAN
            || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA
            || script == Character.UnicodeScript.HANGUL;
    }

    private static double cjkWeight(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
            return CJK_KANA_WEIGHT;
        }
        return CJK_DENSE_WEIGHT;
    }
}
