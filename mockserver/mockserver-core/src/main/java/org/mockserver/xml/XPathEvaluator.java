package org.mockserver.xml;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.matchers.MatchingTimeoutExecutor;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

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

public class XPathEvaluator extends ObjectWithReflectiveEqualsHashCodeToString {

    /**
     * The injected server configuration is ambient context, not part of this evaluator's identity:
     * two evaluators for the same expression are equal regardless of which {@code Configuration}
     * supplied their timeout. Excluding it also keeps {@code toString()} from dumping the entire
     * configuration object into log output.
     */
    private static final String[] EXCLUDED_FIELDS = {"configuration"};

    @Override
    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }

    private final boolean namespaceAware;
    private final XPathExpression xPathExpression;
    private final String expression;
    private final StringToXmlDocumentParser stringToXmlDocumentParser = new StringToXmlDocumentParser();
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
        this.configuration = configuration;
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
        try {
            org.w3c.dom.Document document = stringToXmlDocumentParser.buildDocument(xmlAsString, errorLogger, namespaceAware);
            long timeoutMillis = configuration != null
                ? configuration.xpathMatchingTimeoutMillis()
                : ConfigurationProperties.xpathMatchingTimeoutMillis();
            Object onTimeout = defaultForReturnType(returnType);
            return MatchingTimeoutExecutor.callWithTimeout(
                () -> xPathExpression.evaluate(document, returnType),
                timeoutMillis,
                onTimeout,
                fired -> errorLogger.logError(xmlAsString,
                    new RuntimeException("xpath evaluation timed out after " + fired + "ms for expression: " + expression),
                    StringToXmlDocumentParser.ErrorLevel.WARNING));
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
