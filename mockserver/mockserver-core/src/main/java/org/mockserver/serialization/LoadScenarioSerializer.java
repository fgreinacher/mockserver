package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.load.LoadScenario;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.model.LoadScenarioDTO;
import org.slf4j.event.Level;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.character.Character.NEW_LINE;

/**
 * Serializer for {@link LoadScenario}, mirroring {@code VerificationSequenceSerializer}.
 *
 * <p>v1 parses leniently with the standard {@link ObjectMapperFactory} mapper (no JSON-schema
 * validation step yet); a malformed body surfaces as an {@link IllegalArgumentException}.
 *
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class LoadScenarioSerializer implements Serializer<LoadScenario> {

    private final MockServerLogger mockServerLogger;
    private ObjectWriter objectWriter = ObjectMapperFactory.createObjectMapper(true, false);
    private ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    public LoadScenarioSerializer(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    public String serialize(LoadScenario loadScenario) {
        try {
            return objectWriter.writeValueAsString(new LoadScenarioDTO(loadScenario));
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception while serializing loadScenario to JSON with value:{}")
                    .setArguments(loadScenario)
                    .setThrowable(e)
            );
            throw new RuntimeException("Exception while serializing loadScenario to JSON with value " + loadScenario, e);
        }
    }

    public LoadScenario deserialize(String jsonLoadScenario) {
        if (isBlank(jsonLoadScenario)) {
            throw new IllegalArgumentException(
                "1 error:" + NEW_LINE +
                    " - a load scenario is required but value was \"" + jsonLoadScenario + "\""
            );
        }
        LoadScenario loadScenario;
        try {
            LoadScenarioDTO loadScenarioDTO = objectMapper.readValue(jsonLoadScenario, LoadScenarioDTO.class);
            loadScenario = loadScenarioDTO != null ? loadScenarioDTO.buildObject() : null;
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception while parsing{}for LoadScenario " + throwable.getMessage())
                    .setArguments(jsonLoadScenario)
                    .setThrowable(throwable)
            );
            throw new IllegalArgumentException("exception while parsing [" + jsonLoadScenario + "] for LoadScenario", throwable);
        }
        return loadScenario;
    }

    @Override
    public Class<LoadScenario> supportsType() {
        return LoadScenario.class;
    }
}
