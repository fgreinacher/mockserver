package org.mockserver.url;

import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.substringBefore;

/**
 * @author jamesdbloom
 */
public class URLParser {

    // Precompiled so the per-request parse path does not compile a fresh Pattern on every call.
    // String.matches / String.replaceAll each recompile the regex per invocation; matcher(...).matches()
    // is whole-input anchored exactly like String.matches, and matcher(...).replaceAll("") replaces every
    // occurrence exactly like String.replaceAll, so the observable behaviour is unchanged.
    private static final Pattern schemeHostAndPortRegex = Pattern.compile("https?://([A-Za-z0-9-_.:]*@)?[A-Za-z0-9-_.]*(:[0-9]*)?");

    public static boolean isFullUrl(String uri) {
        // Equivalent to a whole-input match of "https?://.*" without allocating a Matcher per call.
        // "matches()" anchors the whole input, and "." (no DOTALL) does not match a line terminator, so
        // the regex accepts the input only when it starts with the scheme AND the remainder contains no
        // line terminator. The scheme literal itself contains none, so scanning the whole string for one
        // is equivalent to scanning only the ".*" portion.
        if (uri == null || !(uri.startsWith("http://") || uri.startsWith("https://"))) {
            return false;
        }
        for (int i = 0; i < uri.length(); i++) {
            if (isLineTerminator(uri.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // The line terminators that java.util.regex "." does not match by default (no UNIX_LINES): LF, CR,
    // NEL, line separator, paragraph separator.
    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    public static String returnPath(String path) {
        String result;
        if (URLParser.isFullUrl(path)) {
            result = schemeHostAndPortRegex.matcher(path).replaceAll("");
        } else {
            result = path;
        }
        return substringBefore(result, "?");
    }
}
