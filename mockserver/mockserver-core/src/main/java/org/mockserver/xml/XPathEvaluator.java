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

        // Which phase the single callable is in when a timeout fires. Written by the pool thread, read
        // by the calling thread in the timeout callback — an AtomicInteger gives that cross-thread read
        // a defined, recent value.
        AtomicInteger phase = new AtomicInteger(PHASE_PARSE);

        try {
            // ONE hand-off around BOTH the parse and the evaluation under a single timeout budget. The
            // parse is attacker-influenced (it IS the request body) so it must be bounded too, and a
            // single budget expresses the property that matters — the whole match took too long. The
            // body is parsed FRESH on every call: a DOM Document is mutable and not thread-safe, so it
            // is deliberately not cached and shared across requests/threads. This is NOT run via the
            // inlineSafe fast path: unlike a regex proven linear by RegexComplexityClassifier, a user
            // XPath expression can be pathological independently of body size, and with parse and
            // evaluate sharing one budget the inline path would only be sound if BOTH were provably
            // cheap. There is no expression-linearity proof, so the callable keeps the pool's DoS
            // isolation.
            return MatchingTimeoutExecutor.callWithTimeout(
                () -> {
                    phase.set(PHASE_PARSE);
                    Document document = stringToXmlDocumentParser.buildDocument(xmlAsString, errorLogger, namespaceAware);
                    phase.set(PHASE_EVALUATE);
                    return xPathExpression.evaluate(document, returnType);
                },
                timeoutMillis,
                onTimeout,
                fired -> {
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
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable.getMessage(), throwable);
        }
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
