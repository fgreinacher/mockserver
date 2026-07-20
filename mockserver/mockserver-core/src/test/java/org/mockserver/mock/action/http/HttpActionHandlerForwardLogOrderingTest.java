package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Ordering regression test for the forward (proxy) paths of {@link HttpActionHandler}: the
 * {@code FORWARDED_REQUEST} log entry MUST be published <em>before</em> the response is written to
 * the client.
 *
 * <p>Why the ordering matters: {@code MockServerEventLog.verify()} / {@code retrieve()} call
 * {@code drainDisruptor()}, which publishes a RUNNABLE marker onto the same FIFO ring buffer and
 * waits for it. That only guarantees visibility of entries <em>already published</em>. If the
 * exchange is written to the client first, a client that receives the forwarded response and
 * immediately verifies (or retrieves recorded requests-and-responses) can race ahead of the log
 * publication and not see the exchange at all. Logging first closes that window — exactly the
 * ordering the mocked-response ({@code EXPECTATION_RESPONSE}) path has always used.
 *
 * <p>How it is asserted: the {@link ResponseWriter} is stubbed so that, at the exact moment the
 * client write happens, it queries the event log for the recorded request-and-response pair — the
 * same public retrieval surface a client would use. Each side appends a marker to a shared list, so
 * the assertion is on the observed <em>sequence</em>, not merely on both events having occurred.
 * Swapping the two statements back to the buggy order turns {@code write(forwarded-request-visible)}
 * into {@code write(forwarded-request-NOT-visible)} and reverses the marker order, failing the test.
 *
 * <p>Determinism comes from the disruptor's FIFO ring buffer rather than from timing, so the test
 * runs with the production {@code asynchronousEventProcessing=true} — the mode in which the race
 * exists. Both the {@code FORWARDED_REQUEST} entry and the retrieval's RUNNABLE marker are published
 * onto the same ring buffer and consumed in publication order, so the probe's answer is fixed by
 * ordering alone: publish-then-retrieve always sees the entry, retrieve-then-publish never does.
 * (Retrieval is inherently disruptor-dispatched — {@code retrieveLogEntries} publishes a RUNNABLE
 * regardless of {@code asynchronousEventProcessing} — so the probe waits on a future rather than
 * sleeping.) The forward itself is driven with {@code synchronous=true} so the write path runs on
 * the test thread.
 *
 * <p>Global state: this test builds its own {@link Configuration}, {@link Scheduler} and
 * {@link MockServerEventLog}, stubs {@link HttpState}, and mutates no JVM-global statics (drift
 * detection is switched off explicitly; chaos is {@code null} so no {@code Metrics} counters are
 * touched, and SLO sampling is a no-op while disabled). It therefore does NOT need registering in
 * the sequential Surefire phase of {@code mockserver-core/pom.xml}.
 */
public class HttpActionHandlerForwardLogOrderingTest {

    private static final String WRITE_VISIBLE = "write(forwarded-request-visible)";
    private static final String WRITE_NOT_VISIBLE = "write(forwarded-request-NOT-visible)";
    private static final String LOG = "log(FORWARDED_REQUEST)";

    private Configuration configuration;
    private Scheduler scheduler;
    private MockServerEventLog eventLog;
    private HttpActionHandler actionHandler;
    private ResponseWriter responseWriter;
    private List<String> observedSequence;

    @Before
    public void setupTestFixture() {
        configuration = configuration()
            // keep the forward path free of global singletons — drift analysis writes to a global DriftStore
            .driftDetectionEnabled(false);
        scheduler = new Scheduler(configuration, new MockServerLogger());
        eventLog = new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler, true);
        observedSequence = Collections.synchronizedList(new ArrayList<>());

        HttpState httpState = mock(HttpState.class);
        when(httpState.getScheduler()).thenReturn(scheduler);
        when(httpState.getMockServerLogger()).thenReturn(new MockServerLogger(configuration, httpState));
        doAnswer(invocation -> {
            LogEntry logEntry = invocation.getArgument(0);
            if (logEntry.getType() == FORWARDED_REQUEST) {
                observedSequence.add(LOG);
            }
            eventLog.add(logEntry);
            return null;
        }).when(httpState).log(any(LogEntry.class));

        actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);

        responseWriter = mock(ResponseWriter.class);
        doAnswer(invocation -> {
            // at the moment the client is handed the response, is the exchange already retrievable?
            observedSequence.add(forwardedExchangeIsVisible() ? WRITE_VISIBLE : WRITE_NOT_VISIBLE);
            return null;
        }).when(responseWriter).writeResponse(any(HttpRequest.class), any(HttpResponse.class), anyBoolean());
    }

    @After
    public void stopTestFixture() {
        if (eventLog != null) {
            eventLog.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * Uses the same public retrieval surface as {@code retrieveRecordedRequestsAndResponses}, which
     * is built from {@code FORWARDED_REQUEST} entries — i.e. what a client sees when it retrieves
     * immediately after receiving the forwarded response.
     */
    private boolean forwardedExchangeIsVisible() throws Exception {
        CompletableFuture<List<LogEventRequestAndResponse>> retrieved = new CompletableFuture<>();
        eventLog.retrieveRequestResponses(request(), retrieved::complete);
        return !retrieved.get(10, SECONDS).isEmpty();
    }

    private static HttpForward upstream() {
        return forward().withHost("upstream.example").withPort(8080).withScheme(HttpForward.Scheme.HTTP);
    }

    /**
     * Path 3 — {@code writeForwardActionResponse(HttpResponse, ...)}, used by the simple forward
     * write sites (including the override-forwarded-request and fallback paths).
     */
    @Test
    public void shouldLogForwardedRequestBeforeWritingResponseOnDirectWritePath() {
        // given
        HttpRequest request = request("/some_path");
        HttpResponse response = response("some_body");

        // when
        actionHandler.writeForwardActionResponse(response, responseWriter, request, upstream());

        // then - the exchange is published to the log BEFORE the client can observe the response
        assertThat(observedSequence, contains(LOG, WRITE_VISIBLE));
    }

    /**
     * Path 1 — the non-streaming {@code writeCommand} inside
     * {@code writeForwardActionResponse(HttpForwardActionResult, ...)}, the ordinary proxy path.
     * Driven with {@code synchronous=true} so the future is resolved and the write command runs
     * inline on the test thread.
     */
    @Test
    public void shouldLogForwardedRequestBeforeWritingResponseOnNonStreamingForwardPath() {
        // given
        HttpRequest request = request("/some_path");
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        responseFuture.complete(response("some_body"));
        HttpForwardActionResult forwardActionResult = new HttpForwardActionResult(
            request("/some_path"), responseFuture, null, new InetSocketAddress("upstream.example", 8080));

        // when
        actionHandler.writeForwardActionResponse(forwardActionResult, responseWriter, request, upstream(), true);

        // then
        assertThat(observedSequence, contains(LOG, WRITE_VISIBLE));
    }
}
