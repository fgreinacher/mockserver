package org.mockserver.log;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.JsonBody;
import org.mockserver.model.StringBody;
import org.mockserver.model.XmlBody;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.matchers.MatchType.ONLY_MATCHING_FIELDS;
import static org.mockserver.model.HttpRequest.request;

/**
 * Proves the derived-String release hook is actually WIRED into the retained path: when a body-bearing
 * entry is processed into the event-log deque, {@code MockServerEventLog.processLogEntry} calls
 * {@link LogEntry#releaseDerivedForms()}, dropping each text body's cached String (and JSON tree) while
 * the canonical raw bytes - and therefore the re-derived String - are preserved. A fix that silently
 * never releases would pass every other test; this is the one that fails if the hook is not reached.
 * <p>
 * Uses the SYNCHRONOUS log (processing runs on the calling thread) so the retained path is exercised
 * deterministically, and configures {@code logLevel(WARN)} so the (INFO-level) request entry is retained
 * but NOT rendered to stdout - rendering would transiently re-derive and re-cache the String.
 */
public class MockServerEventLogDerivedFormReleaseTest {

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private MockServerEventLog synchronousEventLog(Configuration configuration) {
        return new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerEventLogDerivedFormReleaseTest.class), mock(Scheduler.class), false);
    }

    private Configuration retainingConfiguration() {
        return configuration()
            .logLevel(Level.WARN)
            .maxLogEntries(1000)
            .maxEventLogSizeInBytes(64L * 1024 * 1024)
            .maxLoggedBodyBytes(0);
    }

    @Test
    public void shouldReleaseJsonBodyDerivedFormsWhenEntryIsRetained() {
        MockServerEventLog log = synchronousEventLog(retainingConfiguration());
        try {
            String json = "{ \"name\" : \"value\" }";
            byte[] rawBytes = json.getBytes(StandardCharsets.UTF_8);
            JsonBody body = new JsonBody(new String(rawBytes, StandardCharsets.UTF_8), rawBytes, JsonBody.DEFAULT_JSON_CONTENT_TYPE, ONLY_MATCHING_FIELDS);
            HttpRequest request = request().withMethod("POST").withPath("/json").withBody(body);

            // warm the caches as request-matching would, so the release has something to drop
            assertThat(body.getValue(), is(json));
            body.get("name");
            assertThat(field(body, "json"), is(notNullValue()));
            assertThat(field(body, "jsonNode"), is(notNullValue()));

            log.add(new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat("received request:{}")
                .setArguments(request));

            // the retained entry shares this body instance; the hook has dropped both caches
            assertThat(field(body, "json"), is(nullValue()));
            assertThat(field(body, "jsonNode"), is(nullValue()));

            // but the String is still readable, re-derived identically from the canonical bytes
            assertThat(body.getValue(), is(json));
            assertThat(request.getBodyAsString(), is(json));
        } finally {
            log.stop();
        }
    }

    @Test
    public void shouldReleaseStringBodyDerivedFormsWhenEntryIsRetained() {
        MockServerEventLog log = synchronousEventLog(retainingConfiguration());
        try {
            String value = "some plain text body";
            byte[] rawBytes = value.getBytes(StandardCharsets.UTF_8);
            StringBody body = new StringBody(new String(rawBytes, StandardCharsets.UTF_8), rawBytes, false, StringBody.DEFAULT_CONTENT_TYPE.withCharset(StandardCharsets.UTF_8));
            HttpRequest request = request().withMethod("POST").withPath("/text").withBody(body);
            assertThat(body.getValue(), is(value));
            assertThat(field(body, "value"), is(notNullValue()));

            log.add(new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat("received request:{}")
                .setArguments(request));

            assertThat(field(body, "value"), is(nullValue()));
            assertThat(body.getValue(), is(value));
            assertThat(request.getBodyAsString(), is(value));
        } finally {
            log.stop();
        }
    }

    @Test
    public void shouldReleaseXmlBodyDerivedFormsWhenEntryIsRetained() {
        MockServerEventLog log = synchronousEventLog(retainingConfiguration());
        try {
            String xml = "<a><b>value</b></a>";
            byte[] rawBytes = xml.getBytes(StandardCharsets.UTF_8);
            XmlBody body = new XmlBody(new String(rawBytes, StandardCharsets.UTF_8), rawBytes, XmlBody.DEFAULT_XML_CONTENT_TYPE);
            HttpRequest request = request().withMethod("POST").withPath("/xml").withBody(body);
            assertThat(body.getValue(), is(xml));
            assertThat(field(body, "xml"), is(notNullValue()));

            log.add(new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat("received request:{}")
                .setArguments(request));

            assertThat(field(body, "xml"), is(nullValue()));
            assertThat(body.getValue(), is(xml));
            assertThat(request.getBodyAsString(), is(xml));
        } finally {
            log.stop();
        }
    }
}
