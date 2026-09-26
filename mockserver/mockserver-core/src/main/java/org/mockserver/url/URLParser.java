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
    private static final Pattern schemeRegex = Pattern.compile("https?://.*");
    private static final Pattern schemeHostAndPortRegex = Pattern.compile("https?://([A-Za-z0-9-_.:]*@)?[A-Za-z0-9-_.]*(:[0-9]*)?");

    public static boolean isFullUrl(String uri) {
        return uri != null && schemeRegex.matcher(uri).matches();
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
