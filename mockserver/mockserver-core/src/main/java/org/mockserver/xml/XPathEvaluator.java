package org.mockserver.xml;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.matchers.MatchingTimeoutExecutor;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class XPathEvaluator extends ObjectWithReflectiveEqualsHashCodeToString {

    /**
     * Phase markers for the single timed callable, so a timeout can name which phase overran — the
     * caller needs to know whether to shrink the body (parse) or simplify the expression (evaluate).
     */
    private static final int PHASE_PARSE = 0;
    private static final int PHASE_EVALUATE = 1;

    /**
     * The injected server configuration is ambient context, not part of this evaluator's identity:
     * two evaluators for the same expression are equal regardless of which {@code Configuration}
     * supplied their timeout. Excluding it also keeps {@code toString()} from dumping the entire
     * configuration object into log output. {@code stringToXmlDocumentParser} is a stateless helper
     * (a test seam, see the package-private constructor) and equally not part of identity.
     */
    private static final String[] EXCLUDED_FIELDS = {"configuration", "stringToXmlDocumentParser"};

    /**
     * Per-thread, single-slot cache of the last parsed request body, so a body matched against N
     * XPath expectations is parsed once rather than N times. Layout: {@code [String body,
     * Boolean namespaceAware, org.w3c.dom.Document]}. Modelled on {@code JsonStringMatcher}'s
     * {@code BODY_PARSE_CACHE}, extended with {@code namespaceAware} in the key because the same body
     * parsed namespace-aware and not are two different {@link Document}s that must never be conflated.
     * <p>
     * Static (shared across all evaluators) so the reuse spans the whole candidate scan; {@code ThreadLocal}
     * keyed to the <em>request</em> (calling) thread. The lookup AND the store both happen on the calling
     * thread — never inside the timed callable, which runs on a pool thread and would key the cache to
     * that pool thread, defeating reuse across candidates and letting a pool thread carry a Document into
     * a later unrelated request. Only the parse-on-miss and the evaluation run inside the callable; the
     * freshly parsed {@link Document} is published back to the calling thread for storage. Because each
     * candidate's timed call blocks on {@code future.get} in {@link MatchingTimeoutExecutor}, the cached
     * {@link Document} is read by at most one pool thread at a time. The only lingering-reader hazard is a
     * call that timed out (its uninterruptible pool thread may still be reading); that path
     * {@link ThreadLocal#remove() drops} the entry so the {@link Document} is never handed to a second
     * thread again, and a timed-out parse is never stored.
     */
    private static final ThreadLocal<Object[]> BODY_PARSE_CACHE = ThreadLocal.withInitial(() -> new Object[3]);

    @Override
    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }

    private final boolean namespaceAware;
    private final XPathExpression xPathExpression;
    private final String expression;
    private final StringToXmlDocumentParser stringToXmlDocumentParser;
    /**
     * The live server {@link org.mockserver.configuration.Configuration}, or {@code null} when the
     * evaluator is constructed without one. Preferred over the static {@link ConfigurationProperties}
     * store when resolving the xpath evaluation timeout, so a value set over
     * {@code PUT /mockserver/configuration} actually takes effect.
     */
    private final org.mockserver.configuration.Configuration configuration;

    public XPathEvaluator(String expression, Map<String, String> namespacePrefixes) {
        this(expression, namespacePrefixes, null);
    }

    public XPathEvaluator(String expression, Map<String, String> namespacePrefixes, org.mockserver.configuration.Configuration configuration) {
        this(expression, namespacePrefixes, configuration, new StringToXmlDocumentParser());
    }

    /**
     * Test seam: lets a test inject a {@link StringToXmlDocumentParser} whose {@code buildDocument}
     * is instrumented (e.g. to count parses or to sleep so the parse timeout fires deterministically).
     * Production always goes through the public constructors, which supply a plain parser.
     */
    XPathEvaluator(String expression, Map<String, String> namespacePrefixes, org.mockserver.configuration.Configuration configuration, StringToXmlDocumentParser stringToXmlDocumentParser) {
        this.configuration = configuration;
        this.stringToXmlDocumentParser = stringToXmlDocumentParser;
        XPath xpath = XPathFactory.newInstance().newXPath();
        if (namespacePrefixes != null) {
            xpath.setNamespaceContext(new NamespaceContext() {
                public String getNamespaceURI(String prefix) {
                    if (namespacePrefixes.containsKey(prefix)) {
                        return namespacePrefixes.get(prefix);
                    }
                    return XMLConstants.NULL_NS_URI;
                }

                // This method isn't necessary for XPath processing.
                public String getPrefix(String uri) {
                    throw new UnsupportedOperationException();
                }

                // This method isn't necessary for XPath processing either.
                public Iterator getPrefixes(String uri) {
                    throw new UnsupportedOperationException();
                }
            });
        }
        namespaceAware = namespacePrefixes != null;
        this.expression = expression;
        try {
            xPathExpression = xpath.compile(expression);
        } catch (XPathExpressionException xpee) {
            throw new RuntimeException(xpee.getMessage(), xpee);
        }
    }

    public Object evaluateXPathExpression(String xmlAsString, StringToXmlDocumentParser.ErrorLogger errorLogger, QName returnType) {
        long timeoutMillis = configuration != null
            ? configuration.xpathMatchingTimeoutMillis()
            : ConfigurationProperties.xpathMatchingTimeoutMillis();
        Object onTimeout = defaultForReturnType(returnType);

        // Cache lookup happens on THIS (the request) thread so parse reuse has real affinity across
        // the candidate expectations of one request, and a Document parsed for one request can never
        // be handed to a pool thread serving a later, unrelated request.
        Object[] cache = BODY_PARSE_CACHE.get();
        boolean cacheHit = cache[2] != null
            && xmlAsString.equals(cache[0])
            && Boolean.valueOf(namespaceAware).equals(cache[1]);
        Document cachedDocument = cacheHit ? (Document) cache[2] : null;

        // Which phase the single callable is in when a timeout fires. Written by the pool thread, read
        // by the calling thread in the timeout callback — an AtomicInteger gives that cross-thread read
        // a defined, recent value.
        AtomicInteger phase = new AtomicInteger(cachedDocument != null ? PHASE_EVALUATE : PHASE_PARSE);
        // A freshly parsed Document is published here so the CALLING thread can store it in its own
        // ThreadLocal after the pool thread completes (storing inside the callable would key the cache
        // to the pool thread). Read only on the success path.
        Document[] freshlyParsed = new Document[1];
        AtomicBoolean timedOut = new AtomicBoolean(false);

        try {
            // ONE hand-off around BOTH the parse-on-miss and the evaluation: a single timeout budget
            // expresses the property that matters — "the whole match took too long" — and restores the
            // single pool submission. This is NOT run via the inlineSafe fast path: unlike a regex
            // proven linear by RegexComplexityClassifier, a user XPath expression can be pathological
            // independently of body size (see the evaluation-timeout test), and with parse and evaluate
            // sharing one budget the inline path would only be sound if BOTH were provably cheap. There
            // is no expression-linearity proof, so the callable keeps the pool's DoS isolation.
            Object result = MatchingTimeoutExecutor.callWithTimeout(
                () -> {
                    Document document = cachedDocument;
                    if (document == null) {
                        phase.set(PHASE_PARSE);
                        document = stringToXmlDocumentParser.buildDocument(xmlAsString, errorLogger, namespaceAware);
                        freshlyParsed[0] = document;
                    }
                    phase.set(PHASE_EVALUATE);
                    return xPathExpression.evaluate(document, returnType);
                },
                timeoutMillis,
                onTimeout,
                fired -> {
                    timedOut.set(true);
                    // The pool thread that timed out is uninterruptible and may still be reading the
                    // (cached or freshly parsed) Document; drop this thread's cache entry so a later
                    // candidate does not hand the same Document to a second pool thread concurrently.
                    BODY_PARSE_CACHE.remove();
                    if (phase.get() == PHASE_PARSE) {
                        errorLogger.logError(xmlAsString,
                            new RuntimeException("xpath timed out parsing the body after " + fired + "ms"),
                            StringToXmlDocumentParser.ErrorLevel.WARNING);
                    } else {
                        errorLogger.logError(xmlAsString,
                            new RuntimeException("xpath evaluation timed out after " + fired + "ms for expression: " + expression),
                            StringToXmlDocumentParser.ErrorLevel.WARNING);
                    }
                });

            // Store on the calling thread, only on the success path: a timed-out parse is never cached
            // (its Document may be incomplete and a pool thread may still be touching it), and the
            // timeout callback has already dropped any prior entry.
            if (!timedOut.get() && freshlyParsed[0] != null) {
                cache[0] = xmlAsString;
                cache[1] = namespaceAware;
                cache[2] = freshlyParsed[0];
            }
            return result;
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable.getMessage(), throwable);
        }
    }

    /**
     * Test hook: clear this thread's parsed-body cache so a test starts from a known-empty state and
     * cache-reuse assertions are not contaminated by an earlier test on the same (pooled) thread.
     */
    static void clearBodyParseCache() {
        BODY_PARSE_CACHE.remove();
    }

    /**
     * Picks a type-appropriate sentinel for callers to receive on timeout so that
     * downstream casts (e.g. XPathMatcher's {@code (Boolean)} unbox) never NPE.
     */
    private static Object defaultForReturnType(QName returnType) {
        if (XPathConstants.BOOLEAN.equals(returnType)) {
            return Boolean.FALSE;
        }
        if (XPathConstants.STRING.equals(returnType)) {
            return "";
        }
        if (XPathConstants.NUMBER.equals(returnType)) {
            return Double.NaN;
        }
        // NODE / NODESET — null is acceptable here; XPath callers already handle it.
        return null;
    }

}
