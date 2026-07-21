package org.mockserver.mock.action.http;

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
import org.mockserver.ratelimit.RateLimitRegistry;
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
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.RateLimit.rateLimit;

/**
 * Behavioural tests for declarative rate-limiting wired through HttpActionHandler.
 * Mutates the {@link RateLimitRegistry} singleton, so it is registered in the
 * sequential Surefire phase (see mockserver-core/pom.xml) and resets the registry
 * in {@link #setupMocks()}.
 */
public class HttpActionHandlerRateLimitTest {

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
        RateLimitRegistry.getInstance().reset();
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

    @After
    public void tearDown() {
        RateLimitRegistry.getInstance().reset();
    }

    private HttpResponse dispatchOnce(Expectation expectation, HttpRequest request, HttpResponse normalResponse) {
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        return responseCaptor.getValue();
    }

    @Test
    public void overLimitReturns429WithAllHeaders() {
        // given - limit of 1 per 60s
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        RateLimit rl = rateLimit().withName("acct").withLimit(1).withWindowMillis(60_000L);
        Expectation expectation = new Expectation(request).thenRespond(normalResponse).withRateLimit(rl);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // when - two requests: first allowed, second over-limit
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the second write is a 429 with all four rate-limit headers
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, times(2)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse first = responseCaptor.getAllValues().get(0);
        HttpResponse second = responseCaptor.getAllValues().get(1);

        assertThat(first.getBodyAsString(), is("normal body"));

        assertThat(second.getStatusCode(), is(429));
        assertThat(second.getFirstHeader("X-RateLimit-Limit"), is("1"));
        assertThat(second.getFirstHeader("X-RateLimit-Remaining"), is("0"));
        assertThat(second.getFirstHeader("X-RateLimit-Reset").isEmpty(), is(false));
        assertThat(second.getFirstHeader("Retry-After").isEmpty(), is(false));
        assertThat(second.getBodyAsString(), is("{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"request rate limit exceeded\"}}"));
    }

    @Test
    public void errorStatusOverrideIsHonoured() {
        // given - limit of 1, custom over-limit status 529, literal retryAfter
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        RateLimit rl = rateLimit().withName("acct").withLimit(1).withWindowMillis(60_000L).withErrorStatus(529).withRetryAfter("120");
        Expectation expectation = new Expectation(request).thenRespond(normalResponse).withRateLimit(rl);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, times(2)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse second = responseCaptor.getAllValues().get(1);
        assertThat(second.getStatusCode(), is(529));
        assertThat(second.getFirstHeader("Retry-After"), is("120"));
    }

    @Test
    public void namedCounterIsSharedAcrossExpectations() {
        // given - two distinct expectations (distinct paths/ids) sharing one named counter, limit 1
        HttpRequest requestA = request("path_a");
        HttpRequest requestB = request("path_b");
        HttpResponse responseA = response("body a").withDelay(milliseconds(0));
        HttpResponse responseB = response("body b").withDelay(milliseconds(0));
        RateLimit rlA = rateLimit().withName("shared-acct").withLimit(1).withWindowMillis(60_000L);
        RateLimit rlB = rateLimit().withName("shared-acct").withLimit(1).withWindowMillis(60_000L);
        Expectation expectationA = new Expectation(requestA).thenRespond(responseA).withRateLimit(rlA);
        Expectation expectationB = new Expectation(requestB).thenRespond(responseB).withRateLimit(rlB);

        // first request on A consumes the single slot
        HttpResponse writtenA = dispatchOnce(expectationA, requestA, responseA);
        assertThat(writtenA.getBodyAsString(), is("body a"));

        // request on B (same shared name) is now over-limit -> 429
        when(mockHttpStateHandler.firstMatchingExpectation(requestB)).thenReturn(expectationB);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(responseB);
        actionHandler.processAction(requestB, mockResponseWriter, null, new HashSet<>(), false, true);
        ArgumentCaptor<HttpResponse> captorB = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(requestB), captorB.capture(), eq(false));
        assertThat(captorB.getValue().getStatusCode(), is(429));
    }

    @Test
    public void allowedResponseIsUnchangedWhenWithinLimit() {
        // given - a generous limit
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withStatusCode(200).withHeader("x-custom", "v").withDelay(milliseconds(0));
        RateLimit rl = rateLimit().withName("acct").withLimit(1000).withWindowMillis(60_000L);
        Expectation expectation = new Expectation(request).thenRespond(normalResponse).withRateLimit(rl);

        // when
        HttpResponse written = dispatchOnce(expectation, request, normalResponse);

        // then - the original response is written untouched, with no rate-limit headers
        assertThat(written.getStatusCode(), is(200));
        assertThat(written.getBodyAsString(), is("normal body"));
        assertThat(written.getFirstHeader("x-custom"), is("v"));
        assertThat(written.getFirstHeader("X-RateLimit-Limit").isEmpty(), is(true));
        assertThat(written.getFirstHeader("Retry-After").isEmpty(), is(true));
    }

    @Test
    public void rateLimitTakesPrecedenceOverChaosError() {
        // given - over-limit rate limit AND an always-fire chaos error; the rate-limit 429 must win
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        RateLimit rl = rateLimit().withName("acct").withLimit(1).withWindowMillis(60_000L);
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withRateLimit(rl)
            .withChaos(org.mockserver.model.HttpChaosProfile.httpChaosProfile().withErrorStatus(503).withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(normalResponse);

        // first request: rate limit allows, but chaos error fires -> 503
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
        // second request: over the rate limit -> the rate-limit 429 takes precedence over the 503 chaos error
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, times(2)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse second = responseCaptor.getAllValues().get(1);
        assertThat(second.getStatusCode(), is(429));
        assertThat(second.getBodyAsString(), is("{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"request rate limit exceeded\"}}"));
    }

    // --- streaming action paths (SSE / WebSocket / gRPC server-stream) ---
    //
    // These are dispatched with a null ChannelHandlerContext in this unit harness, so an
    // ALLOWED request falls through to the deployment's WAR-501 branch (streaming needs a
    // live Netty channel). The point under test is that an OVER-LIMIT request is throttled
    // with the synthetic 429 BEFORE the stream path — without the wiring both requests would
    // return 501 and the second would never be a 429.

    @Test
    public void sseStreamRequestIsRateLimited() {
        HttpRequest request = request("sse_path");
        HttpSseResponse sseResponse = HttpSseResponse.sseResponse();
        RateLimit rl = rateLimit().withName("sse-acct").withLimit(1).withWindowMillis(60_000L);
        Expectation expectation = new Expectation(request).thenRespondWithSse(sseResponse).withRateLimit(rl);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> captor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, times(2)).writeResponse(eq(request), captor.capture(), eq(false));
        // first request: allowed (WAR 501 in this harness, i.e. NOT a 429)
        assertThat(captor.getAllValues().get(0).getStatusCode(), is(501));
        // second request: over the rate limit -> 429
        HttpResponse second = captor.getAllValues().get(1);
        assertThat(second.getStatusCode(), is(429));
        assertThat(second.getFirstHeader("X-RateLimit-Limit"), is("1"));
        assertThat(second.getBodyAsString(), is("{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"request rate limit exceeded\"}}"));
    }

    @Test
    public void webSocketStreamRequestIsRateLimited() {
        HttpRequest request = request("ws_path");
        HttpWebSocketResponse wsResponse = HttpWebSocketResponse.webSocketResponse();
        RateLimit rl = rateLimit().withName("ws-acct").withLimit(1).withWindowMillis(60_000L);
        Expectation expectation = new Expectation(request).thenRespondWithWebSocket(wsResponse).withRateLimit(rl);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> captor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, times(2)).writeResponse(eq(request), captor.capture(), eq(false));
        assertThat(captor.getAllValues().get(0).getStatusCode(), is(501));
        HttpResponse wsSecond = captor.getAllValues().get(1);
        assertThat(wsSecond.getStatusCode(), is(429));
        assertThat(wsSecond.getFirstHeader("X-RateLimit-Limit"), is("1"));
    }

    @Test
    public void grpcStreamRequestIsRateLimited() {
        HttpRequest request = request("grpc_path");
        GrpcStreamResponse grpcResponse = GrpcStreamResponse.grpcStreamResponse();
        RateLimit rl = rateLimit().withName("grpc-acct").withLimit(1).withWindowMillis(60_000L);
        Expectation expectation = new Expectation(request).thenRespondWithGrpcStream(grpcResponse).withRateLimit(rl);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> captor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, times(2)).writeResponse(eq(request), captor.capture(), eq(false));
        assertThat(captor.getAllValues().get(0).getStatusCode(), is(501));
        HttpResponse grpcSecond = captor.getAllValues().get(1);
        assertThat(grpcSecond.getStatusCode(), is(429));
        assertThat(grpcSecond.getFirstHeader("X-RateLimit-Limit"), is("1"));
    }

    @Test
    public void sseStreamWithinLimitIsNotThrottled() {
        // a generous limit leaves the SSE path unthrottled: the allowed request is NOT a 429
        HttpRequest request = request("sse_ok_path");
        HttpSseResponse sseResponse = HttpSseResponse.sseResponse();
        RateLimit rl = rateLimit().withName("sse-ok").withLimit(1000).withWindowMillis(60_000L);
        Expectation expectation = new Expectation(request).thenRespondWithSse(sseResponse).withRateLimit(rl);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> captor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), captor.capture(), eq(false));
        HttpResponse written = captor.getValue();
        assertThat(written.getStatusCode(), is(501)); // WAR 501 (allowed), not a 429
        assertThat(written.getFirstHeader("X-RateLimit-Limit").isEmpty(), is(true));
    }

    @Test
    public void noRateLimitClausePassesThroughUnchanged() {
        // given - no rate limit
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request).thenRespond(normalResponse);

        // when
        HttpResponse written = dispatchOnce(expectation, request, normalResponse);

        // then
        assertThat(written.getBodyAsString(), is("normal body"));
        assertThat(written.getFirstHeader("X-RateLimit-Limit").isEmpty(), is(true));
    }
}
