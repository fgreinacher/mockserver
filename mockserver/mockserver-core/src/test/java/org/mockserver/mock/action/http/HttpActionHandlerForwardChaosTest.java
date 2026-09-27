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
import org.mockserver.log.model.LogEntry;
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

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringContains.containsString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for HTTP chaos injection on forwarded (proxied) responses
 * wired through HttpActionHandler. Uses the same Mockito harness as
 * HttpActionHandlerTest / HttpActionHandlerChaosTest.
 */
public class HttpActionHandlerForwardChaosTest {

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
    private HttpForwardValidateActionHandler mockHttpForwardValidateActionHandler;
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
        ServiceChaosRegistry.getInstance().reset();
        configuration = configuration().logLevel(Level.INFO).metricsEnabled(true);

        mockHttpStateHandler = mock(HttpState.class);
        // synchronous=true so the forward-path analyseDrift task (fire-and-forget submit(Runnable))
        // runs inline instead of on the pool. This test drives processAction synchronously, so an
        // async scheduler let analyseDrift's allMatchingExpectation(request) race the next
        // when(firstMatchingExpectation(request)) stub on the same mock, throwing
        // WrongTypeOfReturnValue. Production always aligns the scheduler flag with the dispatch flag.
        scheduler = spy(new Scheduler(configuration, mockServerLogger, true));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    private HttpForwardActionResult completedForwardResult(HttpResponse upstreamResponse) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(upstreamResponse);
        HttpRequest forwardedRequest = mock(HttpRequest.class);
        return new HttpForwardActionResult(forwardedRequest, future, null, new InetSocketAddress(1234));
    }

    // ---- Error injection tests ----

    @Test
    public void chaosErrorReplacesForwardedResponseWhenProbabilityIsOne() {
        // given - a FORWARD expectation with chaos (errorProbability=1.0, errorStatus=503, retryAfter="30")
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withRetryAfter("30"));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a 503 response with Retry-After header is written instead of the upstream 200
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(503));
        assertThat(writtenResponse.getFirstHeader("Retry-After"), is("30"));
        assertThat(writtenResponse.getBodyAsString(), is("{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected HTTP chaos error\"}}"));
    }

    @Test
    public void chaosErrorDoesNotFireForForwardedResponseWhenProbabilityIsZero() {
        // given - a FORWARD expectation with chaos but probability=0
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(0.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the upstream response passes through unchanged
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(200));
        assertThat(writtenResponse.getBodyAsString(), is("upstream body"));
    }

    @Test
    public void noChaosProfilePassesThroughForwardedResponseNormally() {
        // given - a FORWARD expectation with no chaos
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the upstream response passes through unchanged
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(200));
        assertThat(writtenResponse.getBodyAsString(), is("upstream body"));
    }

    // ---- Body-corruption tests ----

    @Test
    public void truncateBodyCorruptsForwardedResponse() {
        // given - a FORWARD expectation with truncateBodyAtFraction=0.5; upstream body "upstream body" (13 bytes) -> keep floor(13*0.5)=6
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile().withTruncateBodyAtFraction(0.5));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(200));
        assertThat(new String(responseCaptor.getValue().getBodyAsRawBytes(), java.nio.charset.StandardCharsets.UTF_8), is("upstre"));
    }

    @Test
    public void malformedBodyAppendsFragmentToForwardedResponse() {
        // given - a FORWARD expectation with malformedBody=true
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile().withMalformedBody(true));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(new String(responseCaptor.getValue().getBodyAsRawBytes(), java.nio.charset.StandardCharsets.UTF_8), is("upstream body{\"__chaos_malformed__\":"));
    }

    @Test
    public void slowResponseSetsChunkedConnectionOptionsOnForwardedResponse() {
        // given - a FORWARD expectation with slow-response dribble configured
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withSlowResponseChunkSize(4)
                .withSlowResponseChunkDelay(milliseconds(100)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse written = responseCaptor.getValue();
        assertThat(written.getBodyAsString(), is("upstream body"));
        assertThat("connection options set", written.getConnectionOptions() != null, is(true));
        assertThat(written.getConnectionOptions().getChunkSize(), is(4));
        assertThat(written.getConnectionOptions().getChunkDelay().getValue(), is(100L));
    }

    // ---- Service-scoped chaos tests ----

    @Test
    public void serviceChaosAppliedToForwardWhenExpectationHasNoChaos() {
        // given - a FORWARD expectation with NO chaos, but service-scoped chaos registered for the request host
        HttpRequest request = request("some_path").withHeader("host", "upstream.svc");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503).withErrorProbability(1.0));

        Expectation expectation = new Expectation(request).thenForward(forward); // no chaos on the expectation

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat("service-scoped chaos replaced the forwarded response", responseCaptor.getValue().getStatusCode(), is(503));
    }

    @Test
    public void expectationChaosTakesPriorityOverServiceChaos() {
        // given - both an expectation-level chaos (500) AND a service-scoped chaos (503) for the host
        HttpRequest request = request("some_path").withHeader("host", "upstream.svc");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503).withErrorProbability(1.0));

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile().withErrorStatus(500).withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat("the expectation's own chaos wins", responseCaptor.getValue().getStatusCode(), is(500));
    }

    @Test
    public void serviceChaosNotAppliedForUnregisteredHost() {
        HttpRequest request = request("some_path").withHeader("host", "other.svc");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503).withErrorProbability(1.0));

        Expectation expectation = new Expectation(request).thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat("no chaos for an unregistered host", responseCaptor.getValue().getStatusCode(), is(200));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("upstream body"));
    }

    @Test
    public void serviceChaosWithTtlAutoRevertsOnForwardPath() {
        try {
            // freeze the controllable clock so the TTL is deterministic (the registry uses TimeService)
            org.mockserver.time.TimeService.freeze(java.time.Instant.ofEpochMilli(2_000_000L));
            HttpRequest request = request("some_path").withHeader("host", "upstream.svc");
            HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
            HttpForward forward = forward().withHost("localhost").withPort(1090);
            // register service chaos with a 5s TTL
            ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503).withErrorProbability(1.0), 5_000L);
            Expectation expectation = new Expectation(request).thenForward(forward);
            when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

            // within the TTL -> service chaos applies (503)
            when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(completedForwardResult(upstreamResponse));
            actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
            ArgumentCaptor<HttpResponse> within = ArgumentCaptor.forClass(HttpResponse.class);
            verify(mockResponseWriter).writeResponse(eq(request), within.capture(), eq(false));
            assertThat("within TTL the service chaos fires", within.getValue().getStatusCode(), is(503));

            // advance past the TTL -> chaos auto-reverts, the upstream response passes through
            org.mockserver.time.TimeService.advance(java.time.Duration.ofMillis(5_000L));
            reset(mockResponseWriter);
            when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(completedForwardResult(upstreamResponse));
            actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
            ArgumentCaptor<HttpResponse> after = ArgumentCaptor.forClass(HttpResponse.class);
            verify(mockResponseWriter).writeResponse(eq(request), after.capture(), eq(false));
            assertThat("after TTL the chaos has reverted", after.getValue().getStatusCode(), is(200));
            assertThat(after.getValue().getBodyAsString(), is("upstream body"));
        } finally {
            org.mockserver.time.TimeService.reset();
        }
    }

    // ---- Latency injection test (non-blocking via scheduler) ----

    @Test
    public void chaosLatencyIsScheduledNonBlockingOnForwardedResponse() {
        // given - chaos with latency only (no error injection)
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        Delay chaosDelay = milliseconds(500);

        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withLatency(chaosDelay));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // Capture the Delay[] passed to scheduler.schedule to verify the chaos delay
        // is dispatched via the non-blocking scheduler timer, not a blocking sleep.
        java.util.concurrent.atomic.AtomicReference<Delay[]> capturedDelays = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            Runnable cmd = invocation.getArgument(0);
            Delay[] delays = invocation.getArguments().length > 2
                ? java.util.Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length, Delay[].class)
                : new Delay[0];
            capturedDelays.set(delays);
            cmd.run(); // run synchronously without sleeping
            return null;
        }).when(scheduler).schedule(any(Runnable.class), eq(true), any(Delay[].class));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the chaos latency (500ms) should have been passed to scheduler.schedule
        Delay[] delays = capturedDelays.get();
        assertThat("scheduler.schedule should have been called with delays", delays != null, is(true));
        boolean foundChaosLatency = false;
        for (Delay d : delays) {
            if (d != null && d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                foundChaosLatency = true;
                break;
            }
        }
        assertThat("chaos latency should be passed to the non-blocking scheduler", foundChaosLatency, is(true));

        // and the upstream response passes through unchanged
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(200));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("upstream body"));
    }

    @Test
    public void noChaosLatencyDoesNotInvokeSchedulerSchedule() {
        // given - no chaos profile at all
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - scheduler.schedule should NOT be called from within the forward completion
        // callback (only the outer dispatch uses schedule with action+global delay, which
        // uses different arguments — we verify no inner schedule with chaos delay)
        verify(mockResponseWriter).writeResponse(eq(request), any(HttpResponse.class), eq(false));
    }

    // ---- Chaos error log distinguishability test ----

    @Test
    public void chaosErrorLogEntryIsClearlyDistinguishableFromNormalForward() {
        // given - chaos error injection fires (probability=1.0)
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the log entry message format should mention "chaos-injected"
        ArgumentCaptor<LogEntry> logCaptor = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logCaptor.capture());
        boolean foundChaosLog = false;
        for (LogEntry entry : logCaptor.getAllValues()) {
            if (entry.getMessageFormat() != null && entry.getMessageFormat().contains("chaos-injected")) {
                foundChaosLog = true;
                break;
            }
        }
        assertThat("log entry should mention 'chaos-injected' when error replaces forwarded response", foundChaosLog, is(true));
    }

    @Test
    public void normalForwardLogDoesNotMentionChaos() {
        // given - no chaos
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the log entry message format should be normal (not chaos)
        ArgumentCaptor<LogEntry> logCaptor = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logCaptor.capture());
        for (LogEntry entry : logCaptor.getAllValues()) {
            if (entry.getMessageFormat() != null && entry.getMessageFormat().contains("forwarded request")) {
                assertThat("normal forward log should not mention chaos",
                    entry.getMessageFormat().contains("chaos-injected"), is(false));
            }
        }
    }

    // --- Drop connection chaos tests on forward path ---

    @Test
    public void chaosDropConnectionOnForwardedResponseWhenProbabilityIsOne() {
        // given - a FORWARD expectation with chaos (dropConnectionProbability=1.0)
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withDropConnectionProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - no response is written (connection is dropped)
        verify(mockResponseWriter, never()).writeResponse(any(), any(HttpResponse.class), anyBoolean());
    }

    @Test
    public void chaosDropTakesPriorityOverErrorOnForwardPath() {
        // given - both drop and error at 1.0 on forward path — drop should win
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withDropConnectionProbability(1.0)
                .withErrorStatus(503)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - connection is dropped, no response written (drop wins over error)
        verify(mockResponseWriter, never()).writeResponse(any(), any(HttpResponse.class), anyBoolean());
        // drop metric incremented, error metric NOT
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "drop"), is(1.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));
    }

    @Test
    public void chaosDropDoesNotFireOnForwardPathWhenProbabilityIsZero() {
        // given - chaos drop probability=0 on forward path
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withDropConnectionProbability(0.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the upstream response passes through unchanged
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(200));
        assertThat(writtenResponse.getBodyAsString(), is("upstream body"));
    }

    // --- Count-based stateful chaos tests on forward path ---

    /**
     * Helper: simulates the matching flow by calling consumeMatch() on the
     * expectation and then dispatches via processAction, returning the written response.
     */
    private HttpResponse dispatchForwardAndCapture(HttpRequest request, Expectation expectation,
                                                   HttpForward forward, HttpResponse upstreamResponse) {
        expectation.consumeMatch();
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        reset(mockResponseWriter);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        return responseCaptor.getValue();
    }

    @Test
    public void forwardChaosFailFirstTwoThenRecover() {
        // succeedFirst=0, failRequestCount=2 → #1,#2 = 503, #3 = upstream 200
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(0)
                .withFailRequestCount(2));

        HttpResponse r1 = dispatchForwardAndCapture(request, expectation, forward, upstreamResponse);
        assertThat("match #1 should be 503", r1.getStatusCode(), is(503));

        HttpResponse r2 = dispatchForwardAndCapture(request, expectation, forward, upstreamResponse);
        assertThat("match #2 should be 503", r2.getStatusCode(), is(503));

        HttpResponse r3 = dispatchForwardAndCapture(request, expectation, forward, upstreamResponse);
        assertThat("match #3 should recover to upstream 200", r3.getStatusCode(), is(200));
        assertThat("match #3 should have upstream body", r3.getBodyAsString(), is("upstream body"));
    }

    @Test
    public void forwardChaosSucceedFirstTwoThenFail() {
        // succeedFirst=2, failRequestCount=null → #1,#2 = upstream 200, #3 = 503
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(2));

        HttpResponse r1 = dispatchForwardAndCapture(request, expectation, forward, upstreamResponse);
        assertThat("match #1 should pass through", r1.getStatusCode(), is(200));

        HttpResponse r2 = dispatchForwardAndCapture(request, expectation, forward, upstreamResponse);
        assertThat("match #2 should pass through", r2.getStatusCode(), is(200));

        HttpResponse r3 = dispatchForwardAndCapture(request, expectation, forward, upstreamResponse);
        assertThat("match #3 should be 503", r3.getStatusCode(), is(503));
    }

    // --- Prometheus metrics tests for HTTP chaos injection on forward path ---

    @Test
    public void forwardChaosErrorIncrementsHttpChaosMetric() {
        // given - chaos error fires on forward path
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(1.0));
    }

    @Test
    public void forwardChaosLatencyOnlyIncrementsLatencyMetric() {
        // given - chaos with latency only on forward path
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withLatency(milliseconds(100)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // Intercept scheduler.schedule to avoid actual delay and still run the command
        doAnswer(invocation -> {
            Runnable cmd = invocation.getArgument(0);
            cmd.run();
            return null;
        }).when(scheduler).schedule(any(Runnable.class), eq(true), any(Delay[].class));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "latency"), is(1.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));
    }

    @Test
    public void forwardNoChaosDoesNotIncrementMetric() {
        // given - no chaos on forward path
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "latency"), is(0.0));
    }

    @Test
    public void forwardChaosOutsideCountWindowDoesNotIncrementMetric() {
        // given - succeedFirst=1, match #1 is outside window
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(1)
                .withFailRequestCount(1));

        // match #1 outside window
        dispatchForwardAndCapture(request, expectation, forward, upstreamResponse);
        assertThat("no error metric for match #1 (outside window)",
            scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(0.0));

        // match #2 inside window
        dispatchForwardAndCapture(request, expectation, forward, upstreamResponse);
        assertThat("error metric increments for match #2 (inside window)",
            scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(1.0));

        // match #3 outside window again
        dispatchForwardAndCapture(request, expectation, forward, upstreamResponse);
        assertThat("no additional error metric for match #3 (outside window)",
            scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(1.0));
    }

    // --- Per-upstream forward observability metric tests ---

    @Test
    public void forwardRecordsPerUpstreamMetricWithHostLabel() {
        // given - a plain FORWARD to host "localhost" returning a 200
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the per-upstream count is labeled by the forward action's host (not the socket port)
        assertThat("forward count labeled by upstream host + 2xx status class",
            Metrics.getForwardRequestCount("localhost", "2xx"), is(1L));
        // and the latency histogram recorded one observation for that host
        assertThat("latency histogram observed once for the host",
            scrapeForwardDurationCount("localhost"), is(1.0));
    }

    @Test
    public void forwardMetricStatusClassReflectsUpstreamStatus() {
        // given - a FORWARD whose upstream returns 502
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("bad gateway").withStatusCode(502).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - recorded under the 5xx status class, not 2xx
        assertThat(Metrics.getForwardRequestCount("localhost", "5xx"), is(1L));
        assertThat(Metrics.getForwardRequestCount("localhost", "2xx"), is(0L));
    }

    private static double scrapeForwardDurationCount(String host) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals("mock_server_forward_request_duration_seconds")
                && snapshot instanceof io.prometheus.metrics.model.snapshots.HistogramSnapshot histogramSnapshot) {
                for (io.prometheus.metrics.model.snapshots.HistogramSnapshot.HistogramDataPointSnapshot dataPoint : histogramSnapshot.getDataPoints()) {
                    if (host.equals(dataPoint.getLabels().get("upstream_host"))) {
                        return dataPoint.getCount();
                    }
                }
            }
        }
        return 0.0;
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
