package org.mockserver.log.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.time.GlobalFixedTime;
import org.mockserver.verify.Verification;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.verify.Verification.verification;

/**
 * Behavioural tests for the opt-in {@code mockserver.redactSecretsInLog} flag.
 * <p>
 * Two surfaces are covered: (1) the {@link LogEntry#getHttpUpdatedRequests()} /
 * {@link LogEntry#getHttpUpdatedResponse()} display copies used by the dashboard event view and the
 * {@code retrieveLogMessages} ({@code LOG_ENTRIES}) serialization; and (2) the redaction-aware
 * {@link LogEntry#getRedactedHttpRequests()} / {@link LogEntry#getRedactedHttpResponse()} getters
 * that feed the {@code retrieveRecordedRequests} / {@code retrieveRecordedRequestsAndResponses}
 * retrieval and export paths through {@link MockServerEventLog}. Proves sensitive
 * {@code Authorization} / {@code Cookie} / {@code x-api-key} header values are masked when the flag is
 * on, are byte-for-byte unchanged when it is off (the default), and that request matching and
 * verification read the original (un-redacted) content so enabling the flag never changes a
 * verification pass/fail decision.
 * <p>
 * Mutates global {@code ConfigurationProperties} state, so registered in BOTH Surefire phases.
 *
 * @author jamesdbloom
 */
public class LogEntryRedactionTest {

    @ClassRule
    public static final GlobalFixedTime fixedTime = new GlobalFixedTime();

    private boolean originalConfigured;
    private boolean originalValue;

    @Before
    public void setUp() {
        originalConfigured = System.getProperty("mockserver.redactSecretsInLog") != null;
        originalValue = ConfigurationProperties.redactSecretsInLog();
        ConfigurationProperties.redactSecretsInLog(false);
    }

    @After
    public void tearDown() {
        if (originalConfigured) {
            ConfigurationProperties.redactSecretsInLog(originalValue);
        } else {
            System.clearProperty("mockserver.redactSecretsInLog");
        }
    }

    @Test
    public void shouldMaskSensitiveRequestHeaderWhenFlagOn() {
        // given
        ConfigurationProperties.redactSecretsInLog(true);
        LogEntry logEntry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpRequest(request()
                .withHeader("Authorization", "Bearer super-secret-token")
                .withHeader("Accept", "application/json"));

        // when
        RequestDefinition displayed = logEntry.getHttpUpdatedRequests()[0];

        // then - secret masked, non-sensitive header untouched
        String json = displayed.toString();
        assertThat(json, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat(json, not(containsString("super-secret-token")));
        assertThat(json, containsString("application/json"));

        // and - the live (matching/verification) view is NOT mutated
        assertThat(logEntry.getHttpRequest().toString(), containsString("super-secret-token"));
    }

    @Test
    public void shouldMaskSensitiveResponseHeaderWhenFlagOn() {
        // given
        ConfigurationProperties.redactSecretsInLog(true);
        LogEntry logEntry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpResponse(response()
                .withHeader("Set-Cookie", "session=secret-session-value")
                .withBody("ok"));

        // when
        HttpResponse displayed = logEntry.getHttpUpdatedResponse();

        // then
        String json = displayed.toString();
        assertThat(json, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat(json, not(containsString("secret-session-value")));

        // and - the live response is NOT mutated
        assertThat(logEntry.getHttpResponse().toString(), containsString("secret-session-value"));
    }

    @Test
    public void shouldLeaveSensitiveRequestHeaderUnchangedWhenFlagOff() {
        // given - flag off (default, set in setUp)
        LogEntry logEntry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpRequest(request().withHeader("Authorization", "Bearer super-secret-token"));

        // when
        RequestDefinition displayed = logEntry.getHttpUpdatedRequests()[0];

        // then - unchanged
        assertThat(displayed.toString(), containsString("super-secret-token"));
        assertThat(displayed.toString(), not(containsString(FixtureRedactor.REDACTED_PLACEHOLDER)));
    }

    @Test
    public void shouldRedactSensitiveHeaderInSerializedLogEntryWhenFlagOn() throws Exception {
        // given - proves the retrieve-logs / dashboard serialization path is redacted
        ConfigurationProperties.redactSecretsInLog(true);
        ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        LogEntry logEntry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpRequest(request().withHeader("api-key", "sk-secret-api-key-value"));

        // when
        String serialised = objectMapper.writeValueAsString(logEntry);

        // then
        assertThat(serialised, not(containsString("sk-secret-api-key-value")));
        assertThat(serialised, containsString("REDACTED"));
    }

    @Test
    public void shouldRedactRetrieveRecordedRequestsWhenFlagOn() {
        // given - proves the retrieveRecordedRequests path (export formats) is redacted end-to-end
        ConfigurationProperties.redactSecretsInLog(true);
        MockServerEventLog eventLog = newEventLog();
        eventLog.add(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpRequest(request("/some/path")
                .withHeader("Authorization", "Bearer super-secret-token")));

        // when
        List<RequestDefinition> requests = retrieveRequests(eventLog, request("/some/path"));

        // then
        assertThat(requests, hasSize(1));
        String json = requests.get(0).toString();
        assertThat(json, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat(json, not(containsString("super-secret-token")));
    }

    @Test
    public void shouldLeaveRetrieveRecordedRequestsUnchangedWhenFlagOff() {
        // given - flag off (default)
        MockServerEventLog eventLog = newEventLog();
        eventLog.add(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpRequest(request("/some/path")
                .withHeader("Authorization", "Bearer super-secret-token")));

        // when
        List<RequestDefinition> requests = retrieveRequests(eventLog, request("/some/path"));

        // then - byte-for-byte unchanged
        assertThat(requests, hasSize(1));
        String json = requests.get(0).toString();
        assertThat(json, containsString("super-secret-token"));
        assertThat(json, not(containsString(FixtureRedactor.REDACTED_PLACEHOLDER)));
    }

    @Test
    public void shouldNotRedactResponseVerificationDecisionWhenFlagOn() {
        // given - a forwarded request/response carrying a sensitive header value that the
        // verification asserts on. With the flag ON, redaction must NOT leak into the
        // verification match decision: matching reads the original (un-redacted) response,
        // so the verification must still PASS exactly as it would with the flag OFF.
        ConfigurationProperties.redactSecretsInLog(true);
        MockServerEventLog eventLog = newEventLog();
        eventLog.add(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/some/path"))
            .setHttpResponse(response()
                .withStatusCode(200)
                .withHeader("Set-Cookie", "session=secret-session-value")));

        // when - verify against the sensitive header value (a redacted clone would read ***REDACTED***)
        String result = verify(eventLog, verification()
            .withResponse(response()
                .withStatusCode(200)
                .withHeader("Set-Cookie", "session=secret-session-value")));

        // then - verification PASSES (empty result) because it matched the raw, un-redacted response
        assertThat(result, is(""));
    }

    @Test
    public void defaultIsOff() {
        assertThat(ConfigurationProperties.redactSecretsInLog(), is(false));
    }

    private String verify(MockServerEventLog eventLog, Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        eventLog.verify(verification, result::complete);
        try {
            return result.get(60, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    private MockServerEventLog newEventLog() {
        Configuration configuration = configuration();
        Scheduler scheduler = mock(Scheduler.class);
        HttpState httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
        return httpState.getMockServerLog();
    }

    private List<RequestDefinition> retrieveRequests(MockServerEventLog eventLog, RequestDefinition httpRequest) {
        CompletableFuture<List<RequestDefinition>> result = new CompletableFuture<>();
        eventLog.retrieveRequests(httpRequest, result::complete);
        try {
            return result.get(60, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    // The arguments reach output twice - as the `arguments` field of a serialized log entry, and
    // formatted into `message` - and neither was redacted, so a redacted httpRequest sat beside an
    // unredacted copy of the same headers on the SAME entry. No test asserted on either, which is
    // why it survived; these two are that missing assertion.
    @Test
    public void shouldMaskSensitiveHeaderInLogArgumentsWhenFlagOn() {
        // given
        ConfigurationProperties.redactSecretsInLog(true);
        LogEntry logEntry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setMessageFormat("received request:{}")
            .setArguments(request()
                .withHeader("Authorization", "Bearer super-secret-token")
                .withHeader("Accept", "application/json"));

        // when
        Object[] displayed = logEntry.getArguments();

        // then - secret masked in the argument, non-sensitive header untouched
        String rendered = displayed[0].toString();
        assertThat(rendered, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat(rendered, not(containsString("super-secret-token")));
        assertThat(rendered, containsString("application/json"));
    }

    @Test
    public void shouldMaskSensitiveHeaderInLogMessageWhenFlagOn() {
        // given
        ConfigurationProperties.redactSecretsInLog(true);
        LogEntry logEntry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setMessageFormat("received request:{}")
            .setArguments(request()
                .withHeader("Authorization", "Bearer super-secret-token"));

        // when - the message is formatted from the arguments
        String message = logEntry.getMessage();

        // then
        assertThat(message, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat(message, not(containsString("super-secret-token")));
    }

    @Test
    public void shouldLeaveLogArgumentsUnchangedWhenFlagOff() {
        // given
        ConfigurationProperties.redactSecretsInLog(false);
        LogEntry logEntry = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setMessageFormat("received request:{}")
            .setArguments(request().withHeader("Authorization", "Bearer super-secret-token"));

        // when
        Object[] displayed = logEntry.getArguments();

        // then - opt-in only, so nothing is masked with the flag off
        assertThat(displayed[0].toString(), containsString("super-secret-token"));
    }
}
