package org.mockserver.url;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class URLParserTest {

    // The regex isFullUrl replaced, kept here as the reference oracle for the differential corpus test.
    private static final Pattern ORIGINAL_SCHEME_REGEX = Pattern.compile("https?://.*");

    private static boolean originalIsFullUrl(String uri) {
        return uri != null && ORIGINAL_SCHEME_REGEX.matcher(uri).matches();
    }

    @Test
    public void isFullUrlMatchesTheOriginalRegexAcrossAnAdversarialCorpus() {
        // The startsWith-based isFullUrl must be byte-for-byte equivalent to a whole-input match of
        // "https?://.*". The dimensions the change touches are the scheme prefix and the line-terminator
        // behaviour of "." (no DOTALL), so the corpus is adversarial in exactly those: each of the five
        // line terminators the regex "." does not match, in the middle and at the very end, plus scheme
        // near-misses. A naive prefix check with no terminator scan returns true for the "\n" cases where
        // the regex returns false, so this corpus is a live degrade test for that omission.
        String[] corpus = {
            null, "", " ", "/", "/some/path", "some/path", "ftp://host", "HTTP://host",
            "http:/host", "http:://host", "http", "https", "http://", "https://",
            "http://host", "https://host/some/path", "https:////localhost/some/path",
            "http://user:pass@host:8080/p?q=1#frag", "https://例え.テスト/パス",
            "http://host\n", "http://host\nmore", "https://host\r", "https://host\r\nmore",
            "http://host\u0085x", "http://host\u2028x", "http://host\u2029x",
            "\nhttp://host", "http://ho\tst", "http://host\u000B\u000C",
            "xhttp://host", "http://host ", " http://host",
        };
        for (String uri : corpus) {
            assertThat("isFullUrl differs from regex for: " + describe(uri),
                URLParser.isFullUrl(uri), is(originalIsFullUrl(uri)));
        }
    }

    private static String describe(String uri) {
        return uri == null ? "null" : "\"" + uri.replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    @Test
    public void shouldDetectPath() {
        // isn't path
        assertThat(URLParser.isFullUrl("http://www.mock-server.com/some/path"), is(true));
        assertThat(URLParser.isFullUrl("https://www.mock-server.com/some/path"), is(true));
        assertThat(URLParser.isFullUrl("https:////localhost/some/path"), is(true));

        // is path
        assertThat(URLParser.isFullUrl(null), is(false));
        assertThat(URLParser.isFullUrl("/some/path"), is(false));
        assertThat(URLParser.isFullUrl("some/path"), is(false));
    }

    @Test
    public void shouldReturnPath() {
        assertThat(URLParser.returnPath("http://www.mock-server.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.mock-server.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc123.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc.123.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc.123.com//some/path"), is("//some/path"));
        assertThat(URLParser.returnPath("http://Administrator:password@192.168.50.70:8091/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://Administrator:password@www.abc.123.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("//some/path"), is("//some/path"));
        assertThat(URLParser.returnPath("/123/456"), is("/123/456"));
    }

    @Test
    public void shouldStripQueryString() {
        assertThat(URLParser.returnPath("http://www.mock-server.com/some/path?foo=bar"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.mock-server.com/some/path?foo=bar&bar=foo"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc123.com/some/path?foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc.123.com/some/path?foo=bar"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc.123.com/some/path?foo=bar&bar=foo&bar=foo"), is("/some/path"));
        assertThat(URLParser.returnPath("http://Administrator:password@192.168.50.70:8091/some/path?foo=bar&bar=foo"), is("/some/path"));
        assertThat(URLParser.returnPath("https://Administrator:password@www.abc.123.com/some/path?foo=bar"), is("/some/path"));
        assertThat(URLParser.returnPath("https://Administrator:password@www.abc.123.com/some/path?foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo&foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"), is("/some/path"));
        assertThat(URLParser.returnPath("/some/path?foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo&foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"), is("/some/path"));
        assertThat(URLParser.returnPath("/123/456%3Ffoo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"), is("/123/456%3Ffoo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"));
    }


}
