package org.mockserver.xml;

import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.w3c.dom.Document;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
     * Fix (1): a parse that exceeds the timeout budget is bounded (does not run to completion on the
     * calling thread) and is logged with a message that names PARSING, distinct from the evaluation
     * timeout message. Uses an injected parser that sleeps far longer than the budget so the outcome
     * does not depend on machine speed.
     */
    @Test
    public void shouldBoundAndDistinctlyLogAParseThatExceedsTheTimeout() {
        XPathEvaluator.clearBodyParseCache();
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
            XPathEvaluator.clearBodyParseCache();
        }
    }

    /**
     * Fix (1): the evaluation timeout keeps its existing message, which names EVALUATION and is
     * distinct from the parse-timeout message. The parse is pre-warmed into the per-thread cache with
     * a generous budget so the timed call's parse is an instant cache hit — isolating the timeout to
     * evaluation with no dependence on parse-vs-eval timing.
     */
    @Test
    public void shouldDistinctlyLogAnEvaluationTimeout() {
        XPathEvaluator.clearBodyParseCache();
        long previous = ConfigurationProperties.xpathMatchingTimeoutMillis();
        try {
            String xml = buildSlowXml(800);
            ConfigurationProperties.xpathMatchingTimeoutMillis(5_000L);
            // pre-warm: parse (namespaceAware == false) is cached for this thread
            new XPathEvaluator("/root", null).evaluateXPathExpression(xml, NOOP, XPathConstants.STRING);

            ConfigurationProperties.xpathMatchingTimeoutMillis(10L);
            AtomicReference<Throwable> logged = new AtomicReference<>();
            Object result = new XPathEvaluator("count(/root/n[@id = //n/@id]) > 0", null)
                .evaluateXPathExpression(xml, (xmlAsString, exception, level) -> logged.set(exception), XPathConstants.BOOLEAN);

            assertThat(result instanceof Boolean, is(true));
            assertThat(logged.get(), is(notNullValue()));
            assertThat(logged.get().getMessage(), containsString("evaluation timed out"));
            assertThat(logged.get().getMessage(), not(containsString("parsing the body")));
        } finally {
            ConfigurationProperties.xpathMatchingTimeoutMillis(previous);
            XPathEvaluator.clearBodyParseCache();
        }
    }

    /**
     * Correctness: namespace-aware and non-namespace-aware parses of the SAME body must not be
     * conflated by the cache. An unprefixed step matches a namespaced element only when the parse is
     * NOT namespace-aware; a namespace-aware parse of the same body yields no match. If the cache key
     * omitted {@code namespaceAware}, the second call would return the first call's Document and this
     * would report "v" instead of "".
     */
    @Test
    public void shouldNotConflateNamespaceAwareAndNonNamespaceAwareParsesOfTheSameBody() {
        XPathEvaluator.clearBodyParseCache();
        try {
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
        } finally {
            XPathEvaluator.clearBodyParseCache();
        }
    }

    /**
     * Correctness: malformed XML behaves as before — the parse error is surfaced (a RuntimeException
     * is thrown to the caller) and the SAX error handler reports it at FATAL_ERROR. Nothing is cached.
     */
    @Test
    public void shouldBehaveAsBeforeOnMalformedXml() {
        XPathEvaluator.clearBodyParseCache();
        AtomicReference<StringToXmlDocumentParser.ErrorLevel> level = new AtomicReference<>();
        RuntimeException thrown = null;
        try {
            new XPathEvaluator("/a", null)
                .evaluateXPathExpression("<a><b></a>", (xmlAsString, exception, l) -> level.set(l), XPathConstants.STRING);
        } catch (RuntimeException re) {
            thrown = re;
        } finally {
            XPathEvaluator.clearBodyParseCache();
        }
        assertThat("malformed XML must still surface a RuntimeException", thrown, is(notNullValue()));
        assertThat(level.get(), is(StringToXmlDocumentParser.ErrorLevel.FATAL_ERROR));
    }

    /**
     * The crux safety property of the parse cache, and the one with the worst failure mode.
     * <p>
     * A {@code Document} is mutable and NOT thread-safe. When an evaluation times out, the pool
     * thread running it is UNINTERRUPTIBLE - XPath evaluation does not observe
     * {@code Thread.interrupt()} - so it may still be reading that {@code Document} long after the
     * caller has given up. If the cache kept the entry, the very next candidate expression for the
     * same body would be handed the same instance while that abandoned thread is still walking it:
     * two threads on one mutable DOM, with no error anywhere.
     * <p>
     * So {@code evaluateXPathExpression} drops the entry in the timeout callback. This test pins
     * that single line. It was originally reported as impossible to degrade-test; it is not, and a
     * safety property with no regression guard is how this exact class of defect returns.
     * <p>
     * Deterministic by construction: step 2 is a cache HIT whose EVALUATION overruns, so the timeout
     * never depends on parse-vs-eval timing.
     */
    @Test
    public void shouldDropTheCachedParseWhenAnEvaluationTimesOutSoNoLaterCallReusesIt() {
        XPathEvaluator.clearBodyParseCache();
        long previous = ConfigurationProperties.xpathMatchingTimeoutMillis();
        try {
            AtomicInteger parses = new AtomicInteger();
            StringToXmlDocumentParser counting = countingParser(parses);
            String body = buildSlowXml(800);

            // 1. warm the cache for this body with a generous budget
            ConfigurationProperties.xpathMatchingTimeoutMillis(5_000L);
            new XPathEvaluator("/root", null, null, counting)
                .evaluateXPathExpression(body, NOOP, XPathConstants.STRING);
            assertThat("precondition: the warm-up parsed once", parses.get(), is(1));

            // 2. same body (a cache HIT), an expression whose EVALUATION reliably overruns a 10ms
            //    budget. The timeout callback is what must drop the entry.
            ConfigurationProperties.xpathMatchingTimeoutMillis(10L);
            AtomicReference<Throwable> logged = new AtomicReference<>();
            new XPathEvaluator("count(/root/n[@id = //n/@id]) > 0", null, null, counting)
                .evaluateXPathExpression(body, (xmlAsString, exception, level) -> logged.set(exception), XPathConstants.BOOLEAN);
            assertThat("precondition: the evaluation really did time out", logged.get(), is(notNullValue()));
            assertThat(logged.get().getMessage(), containsString("evaluation timed out"));
            assertThat("precondition: step 2 was a cache hit, not a re-parse", parses.get(), is(1));

            // 3. the same body again. The entry must be GONE, so this re-parses rather than handing
            //    back a Document an abandoned pool thread may still be reading.
            ConfigurationProperties.xpathMatchingTimeoutMillis(5_000L);
            new XPathEvaluator("/root", null, null, counting)
                .evaluateXPathExpression(body, NOOP, XPathConstants.STRING);

            assertThat("a timed-out evaluation must drop the cached Document, forcing a re-parse",
                parses.get(), is(2));
        } finally {
            ConfigurationProperties.xpathMatchingTimeoutMillis(previous);
            XPathEvaluator.clearBodyParseCache();
        }
    }

    /**
     * Fix (2): a body matched against several XPath expressions is parsed once per thread. Three
     * evaluators (distinct expressions) share the static per-thread cache, so only the first parses.
     */
    @Test
    public void shouldParseOnceWhenTheSameBodyIsMatchedAgainstMultipleExpressions() {
        XPathEvaluator.clearBodyParseCache();
        try {
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
            assertThat("body parsed once across three evaluations", parses.get(), is(1));
        } finally {
            XPathEvaluator.clearBodyParseCache();
        }
    }

    /**
     * Fix (2): two different bodies must not collide in the single-slot cache — the second body is
     * re-parsed and its own result returned, never the first body's cached result.
     */
    @Test
    public void shouldNotReuseTheCachedParseForADifferentBody() {
        XPathEvaluator.clearBodyParseCache();
        try {
            AtomicInteger parses = new AtomicInteger();
            StringToXmlDocumentParser counting = countingParser(parses);
            String bodyA = "<r><v>A</v></r>";
            String bodyB = "<r><v>B</v></r>";

            Object a = new XPathEvaluator("/r/v/text()", null, null, counting)
                .evaluateXPathExpression(bodyA, NOOP, XPathConstants.STRING);
            Object b = new XPathEvaluator("/r/v/text()", null, null, counting)
                .evaluateXPathExpression(bodyB, NOOP, XPathConstants.STRING);

            assertThat(a, is("A"));
            assertThat(b, is("B"));
            assertThat("a different body must be re-parsed", parses.get(), is(2));
        } finally {
            XPathEvaluator.clearBodyParseCache();
        }
    }

    /**
     * Fix (2): the parsed Document must not leak between threads. Two distinct single-thread
     * executors each evaluate the same body; each thread has its own cache slot, so each parses
     * independently (count == 2). A shared (non-ThreadLocal) cache would let the second thread reuse
     * the first thread's Document and the count would be 1.
     */
    @Test
    public void shouldNotShareTheParsedBodyAcrossThreads() throws Exception {
        XPathEvaluator.clearBodyParseCache();
        ExecutorService threadOne = Executors.newSingleThreadExecutor();
        ExecutorService threadTwo = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger parses = new AtomicInteger();
            StringToXmlDocumentParser counting = countingParser(parses);
            String body = "<r><v>shared</v></r>";

            // deliberately do NOT clear the cache inside the task: the ThreadLocal is what must keep
            // the two threads' parses independent
            Object r1 = threadOne.submit(() -> new XPathEvaluator("/r/v/text()", null, null, counting)
                .evaluateXPathExpression(body, NOOP, XPathConstants.STRING)).get();
            Object r2 = threadTwo.submit(() -> new XPathEvaluator("/r/v/text()", null, null, counting)
                .evaluateXPathExpression(body, NOOP, XPathConstants.STRING)).get();

            assertThat(r1, is("shared"));
            assertThat(r2, is("shared"));
            assertThat("each thread parses its own copy — no cross-thread reuse", parses.get(), is(2));
        } finally {
            threadOne.shutdownNow();
            threadTwo.shutdownNow();
            XPathEvaluator.clearBodyParseCache();
        }
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
