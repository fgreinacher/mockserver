package org.mockserver.xml;

import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.w3c.dom.Document;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.xml.sax.SAXException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

public class XPathEvaluatorTest {

    private static final StringToXmlDocumentParser.ErrorLogger NOOP = (xmlAsString, exception, level) -> {
    };

    @Test
    public void shouldMatchMatchingXPath() {
        String xml = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";

        evaluateXPath(xml, "/element[key = 'some_key' and value = 'some_value']", "   some_key   some_value");
        evaluateXPath(xml, "/element[key = 'some_key']", "   some_key   some_value");
        evaluateXPath(xml, "/element/key", "some_key");
        evaluateXPath(xml, "/element[key and value]", "   some_key   some_value");
    }

    private void evaluateXPath(String matched, String expression, String expected) {
        AtomicReference<Throwable> throwable = new AtomicReference<>();
        assertThat(new XPathEvaluator(expression, null).evaluateXPathExpression(matched, (xmlAsString, exception, level) -> throwable.set(exception), XPathConstants.STRING), is(expected));
        if (throwable.get() != null) {
            throw new RuntimeException(throwable.get().getMessage(), throwable.get());
        }
    }

    @Test
    public void shouldReturnTypeAppropriateSentinelOnTimeoutForBooleanReturnType() {
        // Build a synthetic large XML document and an XPath with a quadratic predicate so
        // the timeout fires reliably even on fast machines.
        long previous = org.mockserver.configuration.ConfigurationProperties.xpathMatchingTimeoutMillis();
        try {
            org.mockserver.configuration.ConfigurationProperties.xpathMatchingTimeoutMillis(10L);
            String xml = buildSlowXml(800);
            AtomicReference<Throwable> throwable = new AtomicReference<>();
            long start = System.nanoTime();
            Object result = new XPathEvaluator("count(/root/n[@id = //n/@id]) > 0", null)
                .evaluateXPathExpression(xml, (xmlAsString, exception, level) -> throwable.set(exception), XPathConstants.BOOLEAN);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            // contract: must not hang, and must return a Boolean rather than null so callers can unbox safely.
            assertThat(elapsedMs < 5_000L, is(true));
            assertThat(result instanceof Boolean, is(true));
        } finally {
            org.mockserver.configuration.ConfigurationProperties.xpathMatchingTimeoutMillis(previous);
        }
    }

    /**
     * Robustness fix: a parse that exceeds the timeout budget is bounded (does not run to completion on
     * the calling thread) and is logged with a message that names PARSING, distinct from the evaluation
     * timeout message. Uses an injected parser that sleeps far longer than the budget so the outcome
     * does not depend on machine speed.
     */
    @Test
    public void shouldBoundAndDistinctlyLogAParseThatExceedsTheTimeout() {
        long previous = ConfigurationProperties.xpathMatchingTimeoutMillis();
        try {
            ConfigurationProperties.xpathMatchingTimeoutMillis(20L);
            StringToXmlDocumentParser sleepingParser = new StringToXmlDocumentParser() {
                @Override
                public Document buildDocument(String matched, ErrorLogger errorLogger, boolean namespaceAware) throws ParserConfigurationException, IOException, SAXException {
                    try {
                        Thread.sleep(2_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return super.buildDocument(matched, errorLogger, namespaceAware);
                }
            };
            AtomicReference<Throwable> logged = new AtomicReference<>();
            long start = System.nanoTime();
            Object result = new XPathEvaluator("/a/b", null, null, sleepingParser)
                .evaluateXPathExpression("<a><b>x</b></a>", (xmlAsString, exception, level) -> logged.set(exception), XPathConstants.BOOLEAN);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            // bounded: returned well before the 2s parse sleep would have completed
            assertThat("parse must be bounded by the timeout, not run to completion", elapsedMs < 1_500L, is(true));
            // type-appropriate sentinel so a downstream (Boolean) unbox never NPEs
            assertThat(result instanceof Boolean, is(true));
            assertThat(result, is(Boolean.FALSE));
            // logged, and distinguishable from an evaluation timeout
            assertThat(logged.get(), is(notNullValue()));
            assertThat(logged.get().getMessage(), containsString("timed out parsing the body"));
            assertThat(logged.get().getMessage(), not(containsString("evaluation timed out")));
        } finally {
            ConfigurationProperties.xpathMatchingTimeoutMillis(previous);
        }
    }

    /**
     * Robustness fix: the evaluation timeout keeps its existing message, which names EVALUATION and is
     * distinct from the parse-timeout message. An injected parser returns an already-built Document so
     * the parse phase is effectively instant, isolating the overrun to the evaluation phase with no
     * dependence on parse-vs-eval timing.
     */
    @Test
    public void shouldDistinctlyLogAnEvaluationTimeout() throws Exception {
        long previous = ConfigurationProperties.xpathMatchingTimeoutMillis();
        try {
            String xml = buildSlowXml(800);
            // pre-build once so the injected parser hands it back instantly (no measurable parse time)
            Document preParsed = new StringToXmlDocumentParser().buildDocument(xml, NOOP, false);
            StringToXmlDocumentParser instantParser = new StringToXmlDocumentParser() {
                @Override
                public Document buildDocument(String matched, ErrorLogger errorLogger, boolean namespaceAware) {
                    return preParsed;
                }
            };

            ConfigurationProperties.xpathMatchingTimeoutMillis(10L);
            AtomicReference<Throwable> logged = new AtomicReference<>();
            Object result = new XPathEvaluator("count(/root/n[@id = //n/@id]) > 0", null, null, instantParser)
                .evaluateXPathExpression(xml, (xmlAsString, exception, level) -> logged.set(exception), XPathConstants.BOOLEAN);

            assertThat(result instanceof Boolean, is(true));
            assertThat(logged.get(), is(notNullValue()));
            assertThat(logged.get().getMessage(), containsString("evaluation timed out"));
            assertThat(logged.get().getMessage(), not(containsString("parsing the body")));
        } finally {
            ConfigurationProperties.xpathMatchingTimeoutMillis(previous);
        }
    }

    /**
     * Correctness: namespace-aware and non-namespace-aware parses of the SAME body yield different
     * results and must not be conflated. An unprefixed step matches a namespaced element only when the
     * parse is NOT namespace-aware; a namespace-aware parse of the same body yields no match.
     */
    @Test
    public void shouldNotConflateNamespaceAwareAndNonNamespaceAwareParsesOfTheSameBody() {
        String body = "<a xmlns=\"urn:x\"><b>v</b></a>";
        // namespacePrefixes == null -> NOT namespace-aware: unprefixed step matches the element name literally
        Object nonNamespaceAware = new XPathEvaluator("/a/b/text()", null)
            .evaluateXPathExpression(body, NOOP, XPathConstants.STRING);
        // namespacePrefixes != null -> namespace-aware: unprefixed step matches only no-namespace elements,
        // but a/b are in urn:x, so there is no match
        Object namespaceAware = new XPathEvaluator("/a/b/text()", new HashMap<>())
            .evaluateXPathExpression(body, NOOP, XPathConstants.STRING);

        assertThat(nonNamespaceAware, is("v"));
        assertThat(namespaceAware, is(""));
    }

    /**
     * Correctness: malformed XML behaves as before — the parse error is surfaced (a RuntimeException
     * is thrown to the caller) and the SAX error handler reports it at FATAL_ERROR.
     */
    @Test
    public void shouldBehaveAsBeforeOnMalformedXml() {
        AtomicReference<StringToXmlDocumentParser.ErrorLevel> level = new AtomicReference<>();
        RuntimeException thrown = null;
        try {
            new XPathEvaluator("/a", null)
                .evaluateXPathExpression("<a><b></a>", (xmlAsString, exception, l) -> level.set(l), XPathConstants.STRING);
        } catch (RuntimeException re) {
            thrown = re;
        }
        assertThat("malformed XML must still surface a RuntimeException", thrown, is(notNullValue()));
        assertThat(level.get(), is(StringToXmlDocumentParser.ErrorLevel.FATAL_ERROR));
    }

    /**
     * Regression lock for the XPath-match-returns-404 defect.
     * <p>
     * The body must be parsed FRESH on every evaluation and never handed a shared, cached
     * {@code org.w3c.dom.Document}. A DOM {@code Document} is mutable and not thread-safe; caching one
     * per (reused) worker / event-loop thread and reusing it across requests reintroduces the exact
     * defect that made a matching XPath body return 404 under the WAR/servlet deployment. Three
     * evaluations of the SAME body must each parse — count == 3 — and each must return the correct
     * result. Reintroducing the per-thread parse cache flips this count to 1 and fails the test.
     */
    @Test
    public void shouldParseTheBodyFreshOnEveryCallSoNoDocumentIsSharedAcrossCalls() {
        AtomicInteger parses = new AtomicInteger();
        StringToXmlDocumentParser counting = countingParser(parses);
        String body = "<element><key>k</key><value>v</value></element>";

        Object r1 = new XPathEvaluator("/element/key/text()", null, null, counting)
            .evaluateXPathExpression(body, NOOP, XPathConstants.STRING);
        Object r2 = new XPathEvaluator("/element/value/text()", null, null, counting)
            .evaluateXPathExpression(body, NOOP, XPathConstants.STRING);
        Object r3 = new XPathEvaluator("/element[key='k']", null, null, counting)
            .evaluateXPathExpression(body, NOOP, XPathConstants.BOOLEAN);

        assertThat(r1, is("k"));
        assertThat(r2, is("v"));
        assertThat(r3, is(true));
        assertThat("every evaluation parses a fresh Document — no shared/cached DOM across calls",
            parses.get(), is(3));
    }

    private static StringToXmlDocumentParser countingParser(AtomicInteger parses) {
        return new StringToXmlDocumentParser() {
            @Override
            public Document buildDocument(String matched, ErrorLogger errorLogger, boolean namespaceAware) throws ParserConfigurationException, IOException, SAXException {
                parses.incrementAndGet();
                return super.buildDocument(matched, errorLogger, namespaceAware);
            }
        };
    }

    private static String buildSlowXml(int nodes) {
        StringBuilder sb = new StringBuilder("<root>");
        for (int i = 0; i < nodes; i++) {
            sb.append("<n id=\"").append(i).append("\">v").append(i).append("</n>");
        }
        sb.append("</root>");
        return sb.toString();
    }

}
