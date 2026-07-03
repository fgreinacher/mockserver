package org.mockserver.mock.action.http;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.metrics.MetricsLock;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.time.FixedTime;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.HashSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for HTTP chaos injection wired through HttpActionHandler.
 * Uses the same Mockito harness as HttpActionHandlerTest.
 */
public class HttpActionHandlerChaosTest {

    private static Scheduler scheduler;
    @Mock
    private HttpResponseActionHandler mockHttpResponseActionHandler;
    @Mock
    private HttpResponseTemplateActionHandler mockHttpResponseTemplateActionHandler;
    @Mock
    private HttpResponseClassCallbackActionHandler mockHttpResponseClassCallbackActionHandler;
    @Mock
    private HttpResponseObjectCallbackActionHandler mockHttpResponseObjectCallbackActionHandler;
    @Mock
    private HttpForwardActionHandler mockHttpForwardActionHandler;
    @Mock
    private HttpForwardTemplateActionHandler mockHttpForwardTemplateActionHandler;
    @Mock
    private HttpForwardClassCallbackActionHandler mockHttpForwardClassCallbackActionHandler;
    @Mock
    private HttpForwardObjectCallbackActionHandler mockHttpForwardObjectCallbackActionHandler;
    @Mock
    private HttpOverrideForwardedRequestActionHandler mockHttpOverrideForwardedRequestActionHandler;
    @Mock
    private HttpErrorActionHandler mockHttpErrorActionHandler;
    @Mock
    private ResponseWriter mockResponseWriter;
    @Mock
    private MockServerLogger mockServerLogger;
    @Spy
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer = new HttpRequestToCurlSerializer(mockServerLogger);
    @Mock
    private NettyHttpClient mockNettyHttpClient;
    private HttpState mockHttpStateHandler;
    @InjectMocks
    private HttpActionHandler actionHandler;
    private Configuration configuration;

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @ClassRule
    public static final MetricsLock metricsLock = new MetricsLock();

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    @Before
    public void setupMocks() {
        Metrics.resetAdditionalMetricsForTesting();
        HttpQuotaRegistry.getInstance().reset();
        configuration = configuration().logLevel(Level.INFO).metricsEnabled(true);

        mockHttpStateHandler = mock(HttpState.class);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    @Test
    public void chaosErrorReplacesResponseWhenProbabilityIsOne() {
        // given - an expectation with RESPONSE action and chaos (errorProbability=1.0, errorStatus=503, retryAfter="30")
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withRetryAfter("30"));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a 503 response with Retry-After header is written instead of the normal 200
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(503));
        assertThat(writtenResponse.getFirstHeader("Retry-After"), is("30"));
        assertThat(writtenResponse.getBodyAsString(), is("{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected HTTP chaos error\"}}"));
    }

    @Test
    public void chaosErrorDoesNotFireWhenProbabilityIsZero() {
        // given - an expectation with chaos but probability=0
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(0.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosWithNoErrorStatusDoesNotInjectError() {
        // given - chaos profile with no errorStatus (only latency could apply)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written (no error injection without errorStatus)
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void noChaosProfilePassesThroughNormally() {
        // given - no chaos
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosLatencyReachesSchedulerWhenNoErrorInjected() {
        // given - chaos with latency only (no error injection); use doAnswer to
        // capture delays without actually sleeping
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withLatency(milliseconds(500)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // capture the Delay[] arg from the inner schedule call (the one with varargs delays)
        java.util.concurrent.atomic.AtomicReference<Delay[]> capturedDelays = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            Runnable cmd = invocation.getArgument(0);
            Delay[] delays = invocation.getArguments().length > 2
                ? java.util.Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length, Delay[].class)
                : new Delay[0];
            if (delays.length > 0) {
                capturedDelays.set(delays);
            }
            cmd.run(); // run synchronously without sleeping
            return null;
        }).when(scheduler).schedule(any(Runnable.class), eq(true), any(Delay[].class));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the chaos latency (500ms) should be among the delays passed to the scheduler
        Delay[] delays = capturedDelays.get();
        assertThat("delays should have been captured", delays != null, is(true));
        boolean foundChaosLatency = false;
        for (Delay d : delays) {
            if (d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                foundChaosLatency = true;
                break;
            }
        }
        assertThat("chaos latency should be passed to the scheduler", foundChaosLatency, is(true));

        // also verify the normal response was written through
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosErrorWithNoRetryAfterHeaderOmitsIt() {
        // given
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(500)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a 500 response without Retry-After
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(500));
        assertThat(writtenResponse.getFirstHeader("Retry-After"), is(""));
    }

    // --- Count-based stateful chaos tests ---
    //
    // These tests create a real Expectation with Times.unlimited() and call
    // consumeMatch() to increment matchCount before each processAction call.
    // This mirrors the real flow where RequestMatchers.firstMatchingExpectation
    // calls consumeMatch() (which increments matchCount via AtomicInteger).

    /**
     * Helper: simulates the matching flow by calling consumeMatch() on the
     * expectation (which increments matchCount) and then dispatches the request.
     * Returns the HttpResponse that was written to the response writer.
     */
    private HttpResponse dispatchAndCapture(HttpRequest request, Expectation expectation, HttpResponse normalResponse) {
        // simulate the matching flow: consumeMatch increments matchCount
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // reset to capture this invocation
        reset(mockResponseWriter);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        return responseCaptor.getValue();
    }

    @Test
    public void failFirstTwoThenRecover() {
        // succeedFirst=0, failRequestCount=2, errorStatus=503
        // → matches #1,#2 return 503, #3 returns the mocked 200
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(0)
                .withFailRequestCount(2));

        HttpResponse r1 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #1 should be 503", r1.getStatusCode(), is(503));

        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #2 should be 503", r2.getStatusCode(), is(503));

        HttpResponse r3 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #3 should recover to 200", r3.getBodyAsString(), is("normal body"));
    }

    @Test
    public void succeedFirstTwoThenFail() {
        // succeedFirst=2, failRequestCount=null, errorStatus=503
        // → #1,#2 = 200, #3 = 503
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(2));

        HttpResponse r1 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #1 should succeed", r1.getBodyAsString(), is("normal body"));

        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #2 should succeed", r2.getBodyAsString(), is("normal body"));

        HttpResponse r3 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #3 should be 503", r3.getStatusCode(), is(503));
    }

    @Test
    public void failOnlyTheNthRequest() {
        // succeedFirst=2, failRequestCount=1 → only #3 fails
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(2)
                .withFailRequestCount(1));

        HttpResponse r1 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #1 should succeed", r1.getBodyAsString(), is("normal body"));

        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #2 should succeed", r2.getBodyAsString(), is("normal body"));

        HttpResponse r3 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #3 should be 503", r3.getStatusCode(), is(503));

        HttpResponse r4 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #4 should recover to 200", r4.getBodyAsString(), is("normal body"));
    }

    @Test
    public void countWindowLatencyAppliesOnlyWithinWindow() {
        // succeedFirst=1, failRequestCount=1, latency=500ms
        // → match #1: no latency, match #2: latency applies, match #3: no latency
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withLatency(milliseconds(500))
                .withSucceedFirst(1)
                .withFailRequestCount(1));

        // Capture delays for each invocation
        java.util.List<Delay[]> capturedDelaysList = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            Runnable cmd = invocation.getArgument(0);
            Delay[] delays = invocation.getArguments().length > 2
                ? java.util.Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length, Delay[].class)
                : new Delay[0];
            capturedDelaysList.add(delays);
            cmd.run();
            return null;
        }).when(scheduler).schedule(any(Runnable.class), eq(true), any(Delay[].class));

        // match #1: outside window (succeedFirst=1), no chaos latency
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat("scheduler should have been invoked for match #1", capturedDelaysList.isEmpty(), is(false));
        boolean found500msInMatch1 = false;
        for (Delay d : capturedDelaysList.get(capturedDelaysList.size() - 1)) {
            if (d != null && d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                found500msInMatch1 = true;
                break;
            }
        }
        assertThat("match #1 should NOT have chaos latency", found500msInMatch1, is(false));

        // match #2: within window, chaos latency should apply
        capturedDelaysList.clear();
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat("scheduler should have been invoked for match #2", capturedDelaysList.isEmpty(), is(false));
        boolean found500msInMatch2 = false;
        for (Delay d : capturedDelaysList.get(capturedDelaysList.size() - 1)) {
            if (d != null && d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                found500msInMatch2 = true;
                break;
            }
        }
        assertThat("match #2 should have chaos latency", found500msInMatch2, is(true));

        // match #3: outside window (beyond failRequestCount), no chaos latency
        capturedDelaysList.clear();
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat("scheduler should have been invoked for match #3", capturedDelaysList.isEmpty(), is(false));
        boolean found500msInMatch3 = false;
        for (Delay d : capturedDelaysList.get(capturedDelaysList.size() - 1)) {
            if (d != null && d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                found500msInMatch3 = true;
                break;
            }
        }
        assertThat("match #3 should NOT have chaos latency", found500msInMatch3, is(false));
    }

    @Test
    public void backwardCompatNoCountFieldsBehavesLikeBefore() {
        // No succeedFirst/failRequestCount → errorProbability governs (always inject)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0));

        // All 3 matches should get chaos error
        for (int i = 1; i <= 3; i++) {
            HttpResponse r = dispatchAndCapture(request, expectation, normalResponse);
            assertThat("match #" + i + " should be 503", r.getStatusCode(), is(503));
        }
    }

    // --- Drop connection chaos tests ---

    @Test
    public void chaosDropConnectionWhenProbabilityIsOne() {
        // given - an expectation with RESPONSE action and chaos (dropConnectionProbability=1.0)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withDropConnectionProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - no response is written (connection is dropped); ctx is null in test so
        // HttpErrorActionHandler is NOT called, but the postProcessor runs and
        // responseWriter is NOT invoked
        verify(mockResponseWriter, never()).writeResponse(any(), any(HttpResponse.class), anyBoolean());
    }

    @Test
    public void chaosDropConnectionDoesNotFireWhenProbabilityIsZero() {
        // given - chaos drop probability=0
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withDropConnectionProbability(0.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosDropTakesPriorityOverError() {
        // given - both drop and error at 1.0 — drop should win
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withDropConnectionProbability(1.0)
                .withErrorStatus(503)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - connection is dropped, no response written (drop wins over error)
        verify(mockResponseWriter, never()).writeResponse(any(), any(HttpResponse.class), anyBoolean());
    }

    @Test
    public void chaosDropIncrementsDropMetric() {
        // given - chaos drop fires (probability=1.0)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withDropConnectionProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "drop"), is(1.0));
        // error metric should NOT be incremented (drop takes priority)
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));
    }

    // --- Prometheus metrics tests for HTTP chaos injection ---

    @Test
    public void chaosErrorIncrementsHttpChaosMetric() {
        // given - chaos error fires (probability=1.0, errorStatus=503)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(1.0));
    }

    @Test
    public void chaosLatencyOnlyIncrementsLatencyMetric() {
        // given - chaos with latency only, no error
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withLatency(milliseconds(100)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - latency counter increments, error counter does NOT
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "latency"), is(1.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));
    }

    @Test
    public void noChaosDoesNotIncrementMetric() {
        // given - no chaos profile
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - no chaos counter increments
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "latency"), is(0.0));
    }

    @Test
    public void chaosErrorProbabilityZeroDoesNotIncrementMetric() {
        // given - chaos error probability=0
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(0.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));
    }

    @Test
    public void chaosOutsideCountWindowDoesNotIncrementMetric() {
        // given - chaos with succeedFirst=2, so match #1,#2 are outside chaos window
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(2));

        // first two matches are outside the window
        dispatchAndCapture(request, expectation, normalResponse);
        dispatchAndCapture(request, expectation, normalResponse);

        // then - no error metric increments for the first two (outside window)
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));

        // third match is inside window
        dispatchAndCapture(request, expectation, normalResponse);
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(1.0));
    }

    @Test
    public void chaosIsGatedByTimeBasedOutageWindow() {
        try {
            // freeze the controllable clock so the outage window is deterministic
            org.mockserver.time.TimeService.freeze(java.time.Instant.ofEpochMilli(2_000_000L));
            HttpRequest request = request("some_path");
            HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
            Expectation expectation = new Expectation(request)
                .thenRespond(normalResponse)
                .withChaos(httpChaosProfile()
                    .withErrorStatus(503)
                    .withErrorProbability(1.0)
                    .withOutageAfterMillis(5_000L)
                    .withOutageDurationMillis(10_000L));

            // first request anchors the window at the frozen instant; elapsed 0 -> before the window -> normal
            HttpResponse before = dispatchAndCapture(request, expectation, normalResponse);
            assertThat(before.getBodyAsString(), is("normal body"));

            // advance into the window -> chaos error injected
            org.mockserver.time.TimeService.advance(java.time.Duration.ofMillis(6_000L));
            HttpResponse during = dispatchAndCapture(request, expectation, normalResponse);
            assertThat(during.getStatusCode(), is(503));

            // advance past the window -> self-healed -> normal
            org.mockserver.time.TimeService.advance(java.time.Duration.ofMillis(10_000L));
            HttpResponse after = dispatchAndCapture(request, expectation, normalResponse);
            assertThat(after.getBodyAsString(), is("normal body"));
        } finally {
            org.mockserver.time.TimeService.reset();
        }
    }

    // --- Body-corruption chaos tests ---

    private static String capturedBody(ArgumentCaptor<HttpResponse> captor) {
        return new String(captor.getValue().getBodyAsRawBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    public void truncateBodyKeepsLeadingFraction() {
        // given - body "normal body" (11 bytes), truncateBodyAtFraction=0.5 -> keep floor(11*0.5)=5
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withTruncateBodyAtFraction(0.5));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(capturedBody(responseCaptor), is("norma"));
    }

    @Test
    public void malformedBodyAppendsBrokenFragment() {
        // given - malformedBody=true appends an unterminated JSON object
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withMalformedBody(true));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(capturedBody(responseCaptor), is("normal body{\"__chaos_malformed__\":"));
    }

    @Test
    public void truncateThenMalformedComposes() {
        // given - truncate to 0 bytes then append the malformed fragment
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withTruncateBodyAtFraction(0.0)
                .withMalformedBody(true));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(capturedBody(responseCaptor), is("{\"__chaos_malformed__\":"));
    }

    @Test
    public void bodyChaosNotAppliedWhenErrorInjected() {
        // given - error injection AND body corruption both set; error wins and its body is left intact
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withMalformedBody(true)
                .withTruncateBodyAtFraction(0.5));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(503));
        assertThat(capturedBody(responseCaptor), is("{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected HTTP chaos error\"}}"));
        // body-corruption metrics did NOT fire because the error response replaced the real one
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "truncate"), is(0.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "malformed"), is(0.0));
    }

    @Test
    public void truncateBodyIncrementsTruncateMetric() {
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withTruncateBodyAtFraction(0.5));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "truncate"), is(1.0));
    }

    @Test
    public void malformedBodyIncrementsMalformedMetric() {
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withMalformedBody(true));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "malformed"), is(1.0));
    }

    @Test
    public void bodyChaosGatedByCountWindow() {
        // succeedFirst=1, failRequestCount=1, truncateBodyAtFraction=0.0
        // -> match #1 full body, match #2 truncated to empty, match #3 full body again
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withTruncateBodyAtFraction(0.0)
                .withSucceedFirst(1)
                .withFailRequestCount(1));

        assertThat("match #1 outside window keeps full body", dispatchAndCapture(request, expectation, normalResponse).getBodyAsString(), is("normal body"));

        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #2 inside window is truncated", r2.getBodyAsRawBytes().length, is(0));

        assertThat("match #3 past window keeps full body", dispatchAndCapture(request, expectation, normalResponse).getBodyAsString(), is("normal body"));
    }

    // --- Slow-response (dribble) chaos tests ---

    @Test
    public void slowResponseSetsChunkedConnectionOptions() {
        // given - slowResponseChunkSize + slowResponseChunkDelay set
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withSlowResponseChunkSize(4)
                .withSlowResponseChunkDelay(milliseconds(100)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse written = responseCaptor.getValue();
        // body is unchanged; the response carries chunk settings so the writer dribbles it
        assertThat(written.getBodyAsString(), is("normal body"));
        ConnectionOptions connectionOptions = written.getConnectionOptions();
        assertThat("connection options set", connectionOptions != null, is(true));
        assertThat(connectionOptions.getChunkSize(), is(4));
        assertThat(connectionOptions.getChunkDelay().getValue(), is(100L));
    }

    @Test
    public void slowResponseIncrementsSlowMetric() {
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withSlowResponseChunkSize(4)
                .withSlowResponseChunkDelay(milliseconds(100)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "slow"), is(1.0));
    }

    @Test
    public void slowResponseNotAppliedWhenOnlyChunkSizeSet() {
        // chunkDelay is required alongside chunkSize for the dribble to apply
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withSlowResponseChunkSize(4));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        // no chunk dribble applied (the original response had no connection options)
        assertThat(responseCaptor.getValue().getConnectionOptions() == null, is(true));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "slow"), is(0.0));
    }

    @Test
    public void slowResponseNotAppliedWhenOnlyChunkDelaySet() {
        // chunkSize is required alongside chunkDelay for the dribble to apply
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withSlowResponseChunkDelay(milliseconds(100)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getConnectionOptions() == null, is(true));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "slow"), is(0.0));
    }

    // --- Stateful quota (rate limit) chaos tests ---

    @Test
    public void quotaAllowsUpToLimitThenReturnsConfiguredStatus() {
        // quotaLimit=2 within a 60s window -> matches #1,#2 normal, #3 rejected with 429 + Retry-After
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withQuotaName("test-quota-allow")
                .withQuotaLimit(2)
                .withQuotaWindowMillis(60_000L)
                .withRetryAfter("5"));

        assertThat("request #1 within quota", dispatchAndCapture(request, expectation, normalResponse).getBodyAsString(), is("normal body"));
        assertThat("request #2 within quota", dispatchAndCapture(request, expectation, normalResponse).getBodyAsString(), is("normal body"));

        HttpResponse r3 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("request #3 exceeds quota", r3.getStatusCode(), is(429));
        assertThat(r3.getFirstHeader("Retry-After"), is("5"));
        assertThat(r3.getBodyAsString(), is("{\"error\":{\"type\":\"quota_exceeded\",\"message\":\"HTTP request quota exceeded\"}}"));
    }

    @Test
    public void quotaUsesConfiguredErrorStatusAndIncrementsQuotaMetric() {
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withQuotaName("test-quota-status")
                .withQuotaLimit(1)
                .withQuotaWindowMillis(60_000L)
                .withQuotaErrorStatus(503));

        dispatchAndCapture(request, expectation, normalResponse); // #1 allowed
        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse); // #2 rejected
        assertThat(r2.getStatusCode(), is(503));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "quota"), is(1.0));
        // the probabilistic error metric was NOT used for the quota rejection
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));
    }

    @Test
    public void quotaTakesPriorityOverProbabilisticError() {
        // both quota (limit 1) and a guaranteed error (probability 1.0) set; once over quota the
        // quota response wins and is metered as quota, not error
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withQuotaName("test-quota-priority")
                .withQuotaLimit(1)
                .withQuotaWindowMillis(60_000L)
                .withErrorStatus(418)
                .withErrorProbability(1.0));

        // #1: within quota, but probabilistic error fires -> 418
        HttpResponse r1 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("request #1 gets the probabilistic error", r1.getStatusCode(), is(418));
        // #2: over quota -> quota response (429) wins over the 418 error
        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("request #2 gets the quota rejection", r2.getStatusCode(), is(429));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "quota"), is(1.0));
    }

    // --- Gradual degradation tests ---

    @Test
    public void degradationRampScalesErrorProbabilityOverTime() {
        try {
            // freeze the controllable clock so the ramp is deterministic
            org.mockserver.time.TimeService.freeze(java.time.Instant.ofEpochMilli(2_000_000L));
            HttpRequest request = request("some_path");
            HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
            Expectation expectation = new Expectation(request)
                .thenRespond(normalResponse)
                .withChaos(httpChaosProfile()
                    .withErrorStatus(503)
                    .withErrorProbability(1.0)
                    .withDegradationRampMillis(10_000L));

            // first request anchors the ramp at the frozen instant; elapsed 0 -> factor 0 -> no error
            HttpResponse before = dispatchAndCapture(request, expectation, normalResponse);
            assertThat(before.getBodyAsString(), is("normal body"));

            // advance to the end of the ramp -> factor 1.0 -> errorProbability back to full -> 503
            org.mockserver.time.TimeService.advance(java.time.Duration.ofMillis(10_000L));
            HttpResponse after = dispatchAndCapture(request, expectation, normalResponse);
            assertThat(after.getStatusCode(), is(503));
        } finally {
            org.mockserver.time.TimeService.reset();
        }
    }

    // --- Injected-vs-real latency waterfall: timing block on mock-served responses ---

    /**
     * Runs the runnable passed to scheduler.schedule immediately (without sleeping) so the injected
     * timing fields — which are derived from the Delay objects, not from wall-clock sleep — can be
     * asserted deterministically and fast.
     */
    private void runSchedulerWithoutSleeping() {
        doAnswer(invocation -> {
            Runnable cmd = invocation.getArgument(0);
            cmd.run();
            return null;
        }).when(scheduler).schedule(any(Runnable.class), eq(true), any(Delay[].class));
    }

    @Test
    public void mockResponseWithChaosLatencyCarriesInjectedChaosTiming() {
        // given - a mock response with a chaos latency fault of 500ms
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withLatency(milliseconds(500)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);
        runSchedulerWithoutSleeping();

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the written mock response carries a timing block attributing 500ms to injected chaos latency
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        Timing timing = responseCaptor.getValue().getTiming();
        assertThat("mock response should carry a timing block", timing != null, is(true));
        assertThat(timing.getInjectedChaosLatencyMillis(), is(500L));
        assertThat("action delay was zero so no injected delay is reported", timing.getInjectedDelayMillis(), is((Long) null));
        assertThat("no breakpoint held this exchange", timing.getBreakpointHeldMillis(), is((Long) null));
        assertThat("total time is measured", timing.getTotalTimeInMillis() != null, is(true));
    }

    @Test
    public void mockResponseWithActionDelayCarriesInjectedDelayTiming() {
        // given - a mock response with a configured action delay of 200ms (no chaos)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(200));
        Expectation expectation = new Expectation(request).thenRespond(normalResponse);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);
        runSchedulerWithoutSleeping();

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the written mock response carries a timing block attributing 200ms to the injected action delay
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        Timing timing = responseCaptor.getValue().getTiming();
        assertThat("mock response should carry a timing block", timing != null, is(true));
        assertThat(timing.getInjectedDelayMillis(), is(200L));
        assertThat("no chaos latency was configured", timing.getInjectedChaosLatencyMillis(), is((Long) null));
        assertThat("total time is measured", timing.getTotalTimeInMillis() != null, is(true));
    }

    @Test
    public void mockResponseWithoutInjectionStillCarriesTimingBlock() {
        // given - a plain mock response with no chaos and no delay
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request).thenRespond(normalResponse);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);
        runSchedulerWithoutSleeping();

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a timing block is present (so the dashboard renders a waterfall for mocks) with no injected time
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        Timing timing = responseCaptor.getValue().getTiming();
        assertThat("mock response should carry a timing block", timing != null, is(true));
        assertThat(timing.getTotalTimeInMillis() != null, is(true));
        assertThat(timing.getInjectedChaosLatencyMillis(), is((Long) null));
        assertThat(timing.getInjectedDelayMillis(), is((Long) null));
        assertThat(timing.getBreakpointHeldMillis(), is((Long) null));
    }

    private static double scrapeCounterValue(String name, String labelName, String labelValue) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name) && snapshot instanceof CounterSnapshot counterSnapshot) {
                for (CounterSnapshot.CounterDataPointSnapshot dataPoint : counterSnapshot.getDataPoints()) {
                    if (labelValue.equals(dataPoint.getLabels().get(labelName))) {
                        return dataPoint.getValue();
                    }
                }
            }
        }
        return 0.0;
    }
}
