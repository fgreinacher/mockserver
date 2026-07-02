package org.mockserver.llm.realtime;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared, pure text helpers for the realtime codecs: token splitting (so text can be streamed as deltas whose
 * concatenation reproduces the original text exactly) and a synthetic-silence audio placeholder.
 */
final class RealtimeText {

    private RealtimeText() {
    }

    /** One word plus its surrounding whitespace — a non-overlapping tiling of the input so concatenation is lossless. */
    private static final Pattern TOKEN = Pattern.compile("\\s*\\S+\\s*");

    /**
     * A tiny opaque "silence" audio placeholder (16 zero bytes, base64). The realtime codecs' fidelity target is
     * the event protocol, not audio DSP, so a fixed placeholder chunk is emitted per transcript token.
     */
    static final String SILENCE_CHUNK_BASE64 = Base64.getEncoder().encodeToString(new byte[16]);

    /**
     * Split {@code text} into streaming tokens. Concatenating the returned tokens reproduces {@code text}
     * byte-for-byte. A {@code null}/empty input yields an empty list.
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        Matcher matcher = TOKEN.matcher(text);
        int consumed = 0;
        while (matcher.find()) {
            tokens.add(matcher.group());
            consumed = matcher.end();
        }
        if (tokens.isEmpty() || consumed != text.length()) {
            // Pure-whitespace or otherwise-untiled input: emit the whole thing as one token.
            tokens.clear();
            tokens.add(text);
        }
        return tokens;
    }
}
