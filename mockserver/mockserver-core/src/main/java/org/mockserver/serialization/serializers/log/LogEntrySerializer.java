package org.mockserver.serialization.serializers.log;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.log.model.LogEntry;

import java.io.IOException;

import static org.mockserver.character.Character.NEW_LINE;

/**
 * @author jamesdbloom
 */
public class LogEntrySerializer extends StdSerializer<LogEntry> {

    private static final long serialVersionUID = 1L;

    public LogEntrySerializer() {
        super(LogEntry.class);
    }

    /**
     * Attribute key under which callers may supply the effective {@link org.mockserver.configuration.Configuration}
     * on the {@code ObjectWriter} (see {@code ObjectWriter.withAttribute}). This serializer is registered against a
     * shared, process-wide {@code ObjectMapper} and so has no configuration of its own; when the attribute is
     * absent the redaction accessors fall back to the static store, preserving existing behaviour for every other
     * consumer of that mapper.
     */
    public static final String CONFIGURATION_ATTRIBUTE = "org.mockserver.configuration.Configuration";

    private static org.mockserver.configuration.Configuration configurationOf(SerializerProvider provider) {
        Object attribute = provider == null ? null : provider.getAttribute(CONFIGURATION_ATTRIBUTE);
        return attribute instanceof org.mockserver.configuration.Configuration
            ? (org.mockserver.configuration.Configuration) attribute
            : null;
    }

    @Override
    public void serialize(LogEntry logEntry, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        org.mockserver.configuration.Configuration configuration = configurationOf(provider);
        jgen.writeStartObject();
        if (logEntry.getLogLevel() != null) {
            jgen.writeObjectField("logLevel", logEntry.getLogLevel());
        }
        if (logEntry.getEpochTime() > 0) {
            jgen.writeNumberField("epochTime", logEntry.getEpochTime());
        }
        if (logEntry.getTimestamp() != null) {
            jgen.writeObjectField("timestamp", logEntry.getTimestamp());
        }
        if (logEntry.getType() != null) {
            jgen.writeObjectField("type", logEntry.getType());
        }
        if (logEntry.getCorrelationId() != null) {
            jgen.writeStringField("correlationId", logEntry.getCorrelationId());
        }
        if (logEntry.getPort() != null) {
            jgen.writeNumberField("port", logEntry.getPort());
        }
        if (logEntry.getHttpRequests() != null) {
            if (logEntry.getHttpRequests().length > 1) {
                jgen.writeObjectField("httpRequests", logEntry.getHttpUpdatedRequests(configuration));
            } else if (logEntry.getHttpRequests().length == 1) {
                jgen.writeObjectField("httpRequest", logEntry.getHttpUpdatedRequests(configuration)[0]);
            }
        }
        if (logEntry.getHttpResponse() != null) {
            jgen.writeObjectField("httpResponse", logEntry.getHttpUpdatedResponse(configuration));
        }
        if (logEntry.getHttpError() != null) {
            jgen.writeObjectField("httpError", logEntry.getHttpError());
        }
        if (logEntry.getExpectation() != null) {
            jgen.writeObjectField("expectation", logEntry.getExpectation());
        }
        if (logEntry.getExpectationId() != null) {
            jgen.writeStringField("expectationId", logEntry.getExpectationId());
        }
        if (logEntry.getMessageFormat() != null) {
            jgen.writeStringField("messageFormat", logEntry.getMessageFormat());
        }
        if (logEntry.getMessage() != null) {
            jgen.writeObjectField("message", logEntry.getMessage().replaceAll(" {2}", "   ").split(NEW_LINE));
        }
        if (logEntry.getArguments() != null) {
            jgen.writeObjectField("arguments", logEntry.getArguments());
        }
        if (logEntry.getBecause() != null) {
            jgen.writeStringField("because", logEntry.getBecause());
        }
        if (logEntry.getThrowable() != null) {
            jgen.writeObjectField("throwable", logEntry.getThrowable());
        }
        jgen.writeEndObject();
    }
}
