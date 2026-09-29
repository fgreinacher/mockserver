package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;

import java.lang.reflect.Field;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.slf4j.event.Level.ERROR;

/**
 * {@link ObjectMapperFactory#createObjectMapper()} returns one process-wide mapper, so a caller that
 * reconfigures it (rather than a copy) silently changes serialisation for every other user in the JVM.
 * Widening field visibility to ANY made Jackson reach into {@code java.lang.Throwable}'s private fields,
 * which the JDK module system refuses, so every later serialisation of a Throwable subclass (for
 * example a log entry's throwable) failed.
 */
public class SloCriteriaSerializerSharedMapperTest {

    @SuppressWarnings("serial")
    private static class NotAJdkException extends RuntimeException {
        NotAJdkException(String message) {
            super(message);
        }
    }

    @Test
    public void constructingSerializerShouldNotWidenTheSharedMapperFieldVisibility() throws Exception {
        // when
        new SloCriteriaSerializer(new MockServerLogger());

        // then
        ObjectMapper sharedMapper = ObjectMapperFactory.createObjectMapper();
        Field privateField = Throwable.class.getDeclaredField("detailMessage");
        assertThat(
            "shared mapper must still hide private fields",
            sharedMapper.getSerializationConfig().getDefaultVisibilityChecker().isFieldVisible(privateField),
            is(false)
        );
        assertThat(
            sharedMapper.getDeserializationConfig().getDefaultVisibilityChecker().isFieldVisible(privateField),
            is(false)
        );
    }

    @Test
    public void sharedMapperShouldStillSerialiseThrowableSubclassAfterSerializerConstructed() throws Exception {
        // given
        new SloCriteriaSerializer(new MockServerLogger());

        // when
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(new NotAJdkException("boom"));

        // then
        assertThat(json, containsString("\"message\":\"boom\""));
    }

    @Test
    public void logEntryWithThrowableSubclassShouldStillSerialiseAfterSerializerConstructed() {
        // given
        new SloCriteriaSerializer(new MockServerLogger());

        // when
        String json = new LogEntrySerializer(new MockServerLogger()).serialize(
            new LogEntry()
                .setType(LogEntry.LogMessageType.EXCEPTION)
                .setLogLevel(ERROR)
                .setMessageFormat("failed")
                .setThrowable(new NotAJdkException("boom"))
        );

        // then
        assertThat(json, containsString("boom"));
    }
}
