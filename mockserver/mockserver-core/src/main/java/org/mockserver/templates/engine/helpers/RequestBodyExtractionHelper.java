package org.mockserver.templates.engine.helpers;

import com.jayway.jsonpath.JsonPath;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.xml.XPathEvaluator;
import org.slf4j.event.Level;

import javax.xml.xpath.XPathConstants;

/**
 * Shared JSONPath / XPath extraction helper used by the Velocity and JavaScript template engines so
 * those engines extract request-body fields with the exact same underlying libraries, error handling
 * and return semantics as the Mustache engine's {@code jsonPath} / {@code xPath} lambdas.
 * <p>
 * Both methods operate on the current request body (via {@link HttpRequest#getBodyAsJsonOrXmlString()}).
 * On a missing path or any evaluation error they mirror Mustache: the error is logged and an empty
 * value is returned (empty string for {@code xPath}, empty string for {@code jsonPath}) rather than
 * throwing, so a template that references a non-existent path still renders.
 *
 * @author jamesdbloom
 */
public class RequestBodyExtractionHelper {

    private final HttpRequest request;
    private final MockServerLogger mockServerLogger;

    public RequestBodyExtractionHelper(HttpRequest request, MockServerLogger mockServerLogger) {
        this.request = request;
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * Evaluate a JSONPath expression against the request body. Returns the extracted value (which may
     * be a scalar, list or map depending on the expression), or an empty string if the path is missing,
     * resolves to a JSON {@code null}, or evaluation fails. (Templates render {@code null} as empty
     * anyway, so coercing to {@code ""} keeps Velocity/JavaScript output consistent with Mustache.)
     */
    public Object jsonPath(String jsonPath) {
        try {
            Object jsonPathResult = JsonPath.compile(jsonPath).read(request.getBodyAsJsonOrXmlString());
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setHttpRequest(request)
                        .setMessageFormat("evaluated jsonPath:{}against json body:{}as:{}")
                        .setArguments(jsonPath, request.getBodyAsJsonOrXmlString(), jsonPathResult)
                );
            }
            return jsonPathResult == null ? "" : jsonPathResult;
        } catch (Throwable throwable) {
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("exception evaluating jsonPath:{}against json body:{}")
                        .setArguments(jsonPath, request.getBodyAsJsonOrXmlString())
                        .setThrowable(throwable)
                );
            }
            return "";
        }
    }

    /**
     * Evaluate an XPath expression against the request body, returning the result as a string (the same
     * {@link XPathConstants#STRING} return type the Mustache engine uses), or an empty string if the
     * path is missing or evaluation fails.
     */
    public String xPath(String xPath) {
        try {
            String xPathResult = String.valueOf(new XPathEvaluator(xPath, null, mockServerLogger != null ? mockServerLogger.getConfiguration() : null).evaluateXPathExpression(request.getBodyAsJsonOrXmlString(), (matched, exception, level) -> {
                if (mockServerLogger != null) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.INFO)
                            .setHttpRequest(request)
                            .setMessageFormat("exception evaluating xPath:{}against xml body:{}")
                            .setArguments(xPath, request.getBodyAsJsonOrXmlString())
                            .setThrowable(exception)
                    );
                }
            }, XPathConstants.STRING));
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setHttpRequest(request)
                        .setMessageFormat("evaluated xPath:{}against xml body:{}as:{}")
                        .setArguments(xPath, request.getBodyAsJsonOrXmlString(), xPathResult)
                );
            }
            return xPathResult;
        } catch (Throwable throwable) {
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("exception evaluating xPath:{}against xml body:{}")
                        .setArguments(xPath, request.getBodyAsJsonOrXmlString())
                        .setThrowable(throwable)
                );
            }
            return "";
        }
    }

    @Override
    public String toString() {
        return "RequestBodyExtractionHelper";
    }

    /**
     * Velocity tool exposing JSONPath extraction as {@code $jsonPath.find("$.field")} (and {@code $jsonPath.jsonPath(...)}).
     */
    public static class JsonPathTool {
        private final RequestBodyExtractionHelper delegate;

        public JsonPathTool(RequestBodyExtractionHelper delegate) {
            this.delegate = delegate;
        }

        public Object find(String expression) {
            return delegate.jsonPath(expression);
        }

        public Object jsonPath(String expression) {
            return delegate.jsonPath(expression);
        }

        @Override
        public String toString() {
            return "JsonPathTool";
        }
    }

    /**
     * Velocity tool exposing XPath extraction as {@code $xPath.find("//field")} (and {@code $xPath.xPath(...)}).
     */
    public static class XPathTool {
        private final RequestBodyExtractionHelper delegate;

        public XPathTool(RequestBodyExtractionHelper delegate) {
            this.delegate = delegate;
        }

        public String find(String expression) {
            return delegate.xPath(expression);
        }

        public String xPath(String expression) {
            return delegate.xPath(expression);
        }

        @Override
        public String toString() {
            return "XPathTool";
        }
    }
}
