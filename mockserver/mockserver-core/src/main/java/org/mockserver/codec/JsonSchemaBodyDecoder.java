package org.mockserver.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.google.common.base.Joiner;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.BodyMatcher;
import org.mockserver.matchers.BodyMatching;
import org.mockserver.matchers.BodySource;
import org.mockserver.matchers.JsonSchemaMatcher;
import org.mockserver.xml.StringToXmlDocumentParser;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.event.Level;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Supplier;

import static java.lang.Double.parseDouble;
import static java.lang.Long.parseLong;
import static java.util.jar.Attributes.Name.CONTENT_TYPE;
import static java.util.stream.Collectors.toList;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXCEPTION;
import static org.mockserver.xml.StringToXmlDocumentParser.ErrorLevel.FATAL_ERROR;
import static org.mockserver.xml.StringToXmlDocumentParser.ErrorLevel.prettyPrint;
import static org.mockserver.model.NottableString.serialiseNottableString;

public class JsonSchemaBodyDecoder {

    private static final String APPLICATION_XML = "application/xml";
    private static final String TEXT_XML = "text/xml";
    private static final String APPLICATION_X_WWW_FORM_URLENCODED = "application/x-www-form-urlencoded";
    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final Expectation expectation;
    private final HttpRequest httpRequest;
    private final ExpandedParameterDecoder formParameterParser;

    public JsonSchemaBodyDecoder(Configuration configuration, MockServerLogger mockServerLogger, Expectation expectation, HttpRequest httpRequest) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.expectation = expectation;
        this.httpRequest = httpRequest;
        formParameterParser = new ExpandedParameterDecoder(configuration, mockServerLogger);
    }

    public String convertToJson(HttpRequest request, BodyMatcher<?> bodyMatcher) {
        // The HttpRequest path is preserved byte-for-byte by delegating to the shared BodySource
        // path through an adapter that exposes the request's per-request XML→JSON cache and (for
        // the failure-log only) the request itself. Behaviour, caching and exception handling are
        // identical to the previous inline implementation.
        return convertToJson(BodyMatching.of(request), bodyMatcher);
    }

    /**
     * Shared conversion used by both request matching ({@link HttpRequest}) and response matching
     * ({@link org.mockserver.model.HttpResponse}), driven only through the {@link BodySource}
     * abstraction. For an XML or form Content-Type the body is converted to JSON so a JSON / JSON
     * schema / JSON path matcher can match an XML or form actual body; otherwise the body string is
     * returned unchanged (including {@code null} for an absent body, which the JSON matchers treat
     * as a clean non-match). The XML→JSON conversion is memoised per message via
     * {@link BodySource#getOrComputeConvertedBody(Supplier)} so it runs once per message, not once
     * per candidate matcher.
     */
    public String convertToJson(BodySource source, BodyMatcher<?> bodyMatcher) {
        String bodyAsJson = source.getBodyAsString();
        String contentType = source.getFirstHeader(CONTENT_TYPE.toString());
        if (contentType.contains(APPLICATION_XML) || contentType.contains(TEXT_XML)) {
            // The XML-to-JSON conversion is a pure function of the message body, so memoize it on the
            // message and reuse it across the N-matcher match scan rather than re-parsing the XML
            // (DOM parse + ObjectMapper serialisation) once per candidate matcher. The supplier
            // returns the same value the inline branch did — the converted JSON on success, or the
            // original body string on any parse failure — preserving match and exception behaviour
            // (this branch never propagates an exception; it swallows Throwable and falls back).
            bodyAsJson = source.getOrComputeConvertedBody(() -> convertXmlToJson(source, bodyMatcher));
        } else if (contentType.contains(APPLICATION_X_WWW_FORM_URLENCODED)) {
            ObjectNode objectNode = new ObjectNode(JsonNodeFactory.instance);
            Parameters parameters = formParameterParser
                .retrieveFormParameters(source.getBodyAsString(), false);
            if (bodyMatcher instanceof JsonSchemaMatcher) {
                splitParameters(((JsonSchemaMatcher) bodyMatcher).getParameterStyle(), parameters);
            }
            parameters
                .getEntries()
                .forEach(parameter -> objectNode.set(serialiseNottableString(parameter.getName()), toJsonObject(NottableString.serialiseNottableStrings(parameter.getValues()))));
            bodyAsJson = objectNode.toPrettyString();
        }
        return bodyAsJson;
    }

    /**
     * Performs the XML-body-to-JSON conversion. Extracted so it can be memoized on the message via
     * {@link BodySource#getOrComputeConvertedBody(Supplier)}. Behaviour is identical to the previous
     * inline branch: returns the converted JSON on success, or the original body string on any
     * failure (a fatal XML parse error is logged and swallowed, never propagated).
     */
    private String convertXmlToJson(BodySource source, BodyMatcher<?> bodyMatcher) {
        String bodyAsString = source.getBodyAsString();
        // For the exception log below the request path reports the actual request (preserving the
        // historical log content); the response path has no request to report, so it logs null.
        HttpRequest requestForLogging = source.requestForLogging();
        try {
            Map<StringToXmlDocumentParser.ErrorLevel, String> errors = new HashMap<>();
            Document document = new StringToXmlDocumentParser().buildDocument(bodyAsString, (matchedInException, throwable, level) -> {
                errors.put(level, throwable.getMessage());
            });
            if (errors.containsKey(FATAL_ERROR)) {
                throw new IllegalArgumentException(formatLogMessage("failed to convert:{}to json for json schema matcher:{}", bodyAsString, bodyMatcher, Joiner.on("\n").join(errors.values())));
            }
            for (Map.Entry<StringToXmlDocumentParser.ErrorLevel, String> errorEntry : errors.entrySet()) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("failed to convert:{}to json for json schema matcher:{}")
                        .setArguments(bodyAsString, bodyMatcher, prettyPrint(errorEntry.getKey()) + ": " + errorEntry.getValue())
                );
            }
            Object objectMap = xmlToMap(document.getFirstChild());
            return ObjectMapperFactory.createObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(objectMap);
        } catch (Throwable throwable) {
            // The request path keeps the historical message (reporting the actual request and the
            // template request); the response path has no request to attribute the failure to, so it
            // logs a response-specific message reporting the offending body and matcher instead of
            // two misleading "null"s.
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                LogEntry logEntry = new LogEntry()
                    .setType(EXCEPTION)
                    .setExpectation(this.expectation);
                if (requestForLogging != null) {
                    logEntry
                        .setHttpRequest(requestForLogging)
                        .setMessageFormat("exception parsing xml body for{}while matching against request{}")
                        .setArguments(requestForLogging, this.httpRequest);
                } else {
                    logEntry
                        .setMessageFormat("exception parsing xml response body{}while matching against body matcher{}")
                        .setArguments(bodyAsString, bodyMatcher);
                }
                mockServerLogger.logEvent(logEntry);
            }
            return bodyAsString;
        }
    }


    private void splitParameters(Map<String, ParameterStyle> parameterStyles, Parameters bodyParameters) {
        if (parameterStyles != null && bodyParameters != null) {
            for (Map.Entry<String, ParameterStyle> parameterStyleEntry : parameterStyles.entrySet()) {
                for (Parameter bodyParameterEntry : bodyParameters.getEntries()) {
                    if (parameterStyleEntry.getKey().equals(bodyParameterEntry.getName().getValue())) {
                        bodyParameterEntry.replaceValues(new ExpandedParameterDecoder(configuration, mockServerLogger).splitOnDelimiter(parameterStyleEntry.getValue(), parameterStyleEntry.getKey(), bodyParameterEntry.getValues()));
                        bodyParameters.replaceEntry(bodyParameterEntry);
                    }
                }
            }
        }
    }

    @SuppressWarnings({"unchecked"})
    private Object xmlToMap(Node node) {
        Map<String, Object> objectMap = new HashMap<>();
        NodeList childNodes = node.getChildNodes();
        JsonNode content = null;
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node item = childNodes.item(i);
            if (item.getChildNodes().getLength() > 0) {
                if (objectMap.containsKey(item.getNodeName())) {
                    Object object = objectMap.get(item.getNodeName());
                    if (object instanceof List) {
                        ((List<Object>) object).add(xmlToMap(item));
                    } else if (object != null) {
                        List<Object> list = new ArrayList<>();
                        list.add(object);
                        list.add(xmlToMap(item));
                        objectMap.put(item.getNodeName(), list);
                    }
                } else {
                    objectMap.put(item.getNodeName(), xmlToMap(item));
                }
            } else if (item.getNodeType() == Node.TEXT_NODE) {
                content = toJsonObject(item.getTextContent().trim());
            }
        }
        return objectMap.size() > 0 ? objectMap : content;
    }

    private static JsonNode toJsonObject(final Collection<String> values) {
        if (values.size() == 0) {
            return NullNode.getInstance();
        }
        if (values.size() == 1) {
            return toJsonObject(values.iterator().next());
        }
        return new ArrayNode(
            JsonNodeFactory.instance,
            values.stream().map(JsonSchemaBodyDecoder::toJsonObject).collect(toList())
        );
    }

    private static JsonNode toJsonObject(@Nullable final String value) {
        if (value == null || value.equalsIgnoreCase("null")) {
            return NullNode.getInstance();
        }
        final String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("false")) {
            return BooleanNode.getFalse();
        }
        if (trimmed.equalsIgnoreCase("true")) {
            return BooleanNode.getTrue();
        }
        try {
            return new LongNode(parseLong(trimmed));
        } catch (final NumberFormatException ignore) {
        }
        try {
            return new DoubleNode(parseDouble(trimmed));
        } catch (final NumberFormatException ignore) {
        }
        return new TextNode(trimmed);
    }
}
