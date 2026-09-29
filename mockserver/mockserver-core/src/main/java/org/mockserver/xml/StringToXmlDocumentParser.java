package org.mockserver.xml;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;

/**
 * @author jamesdbloom
 */
public class StringToXmlDocumentParser extends ObjectWithReflectiveEqualsHashCodeToString {

    // DocumentBuilderFactory.newInstance() runs the JAXP provider lookup, so one hardened factory per
    // namespace mode is built once and never reconfigured. Each parse still gets its own
    // DocumentBuilder and Document (both mutable); the factory is only read by newDocumentBuilder().
    // Null when the provider rejects the hardening, in which case every parse builds a factory as before.
    private static final DocumentBuilderFactory NAMESPACE_AWARE_FACTORY = sharedFactory(true);
    private static final DocumentBuilderFactory NAMESPACE_UNAWARE_FACTORY = sharedFactory(false);

    public Document buildDocument(final String matched, final ErrorLogger errorLogger) throws ParserConfigurationException, IOException, SAXException {
        return buildDocument(matched, errorLogger, false);
    }

    public Document buildDocument(final String matched, final ErrorLogger errorLogger, boolean namespaceAware) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory documentBuilderFactory = namespaceAware ? NAMESPACE_AWARE_FACTORY : NAMESPACE_UNAWARE_FACTORY;
        if (documentBuilderFactory == null) {
            documentBuilderFactory = newFactory(namespaceAware);
        }
        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        documentBuilder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) {
                errorLogger.logError(matched, exception, ErrorLevel.WARNING);
            }

            @Override
            public void error(SAXParseException exception) {
                errorLogger.logError(matched, exception, ErrorLevel.ERROR);
            }

            @Override
            public void fatalError(SAXParseException exception) {
                errorLogger.logError(matched, exception, ErrorLevel.FATAL_ERROR);
            }
        });
        return documentBuilder.parse(new InputSource(new StringReader(matched)));
    }

    private static DocumentBuilderFactory newFactory(boolean namespaceAware) throws ParserConfigurationException {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        documentBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        documentBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        documentBuilderFactory.setNamespaceAware(namespaceAware);
        return documentBuilderFactory;
    }

    private static DocumentBuilderFactory sharedFactory(boolean namespaceAware) {
        try {
            return newFactory(namespaceAware);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public interface ErrorLogger {
        void logError(final String xmlAsString, final Exception exception, ErrorLevel level);
    }

    public enum ErrorLevel {
        WARNING,
        ERROR,
        FATAL_ERROR;

        public static String prettyPrint(ErrorLevel errorLevel) {
            return errorLevel.name().toLowerCase().replaceAll("_", " ");
        }
    }
}
