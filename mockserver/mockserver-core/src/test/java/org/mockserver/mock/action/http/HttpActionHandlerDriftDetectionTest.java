package org.mockserver.mock.action.http;

import org.junit.*;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.mock.drift.DriftRecord;
import org.mockserver.mock.drift.DriftStore;
import org.mockserver.mock.drift.DriftType;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.uuid.UUIDService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural enforcement tests for the {@code driftDetectionEnabled} master switch,
 * driven through the REAL forward request path in {@link HttpActionHandler}
 * (not a hand-mirrored copy of the gate expression).
 *
 * <p>A request is forwarded (proxied) upstream while a response-type stub expectation
 * matching the same request describes the response the upstream is <em>supposed</em> to
 * return. When the real upstream response differs from that stub, drift analysis must
 * record a {@link DriftRecord} into the shared {@link DriftStore} — but only when the
 * {@code driftDetectionEnabled} gate permits it. This exercises the production gate at
 * lines that guard {@code analyseDrift(...)}, asserting on the real drift-recording
 * outcome rather than re-implementing the boolean.</p>
 */
public class HttpActionHandlerDriftDetectionTest {

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

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    @Before
    public void setupMocks() {
        DriftStore.getInstance().clear();
        configuration = configuration().logLevel(Level.INFO);

        mockHttpStateHandler = mock(HttpState.class);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);

        // Run the (normally async) drift-analysis task synchronously so assertions are deterministic.
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(scheduler).submit(any(Runnable.class));
    }

    @After
    public void resetDriftStore() {
        DriftStore.getInstance().clear();
    }

    /**
     * Drives a real forward whose upstream returns 500 while a matching response-type
     * stub says the endpoint returns 200 — a STATUS drift.
     *
     * @return the drift records recorded against the stub expectation id
     */
    private List<DriftRecord> forwardDriftingRequest(Configuration configurationForRun) {
        HttpRequest request = request("/some_path");
        // real upstream response drifts from the stub (500 vs 200)
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(500).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        // the matched action drives the forward path
        Expectation forwardExpectation = new Expectation(request).thenForward(forward);
        // the response-type stub describes what the upstream is *supposed* to return (200)
        Expectation stubExpectation = new Expectation(request).thenRespond(response("stub body").withStatusCode(200));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(forwardExpectation);
        when(mockHttpStateHandler.allMatchingExpectation(request)).thenReturn(List.of(stubExpectation));
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        return DriftStore.getInstance().getByExpectationId(stubExpectation.getId());
    }

    @Test
    public void recordsDriftWhenDriftDetectionEnabled() {
        // given - drift detection enabled (per-instance override, rate 1.0 = always sample)
        configuration.driftDetectionEnabled(true).driftSampleRate(1.0d);

        // when - a drifting request is forwarded through the real handler path
        List<DriftRecord> records = forwardDriftingRequest(configuration);

        // then - a STATUS drift record is produced into the shared DriftStore
        assertThat("drift detection enabled must record drift for a drifting forward", records.size(), is(1));
        DriftRecord record = records.get(0);
        assertThat(record.getDriftType(), is(DriftType.STATUS));
        assertThat(record.getExpectedValue(), is("200"));
        assertThat(record.getActualValue(), is("500"));
    }

    @Test
    public void recordsNoDriftWhenDriftDetectionDisabled() {
        // given - the master switch is OFF
        configuration.driftDetectionEnabled(false).driftSampleRate(1.0d);

        // when - the same drifting request is forwarded
        List<DriftRecord> records = forwardDriftingRequest(configuration);

        // then - drift analysis is skipped entirely: nothing recorded
        assertThat("drift detection disabled must NOT record any drift", records.size(), is(0));
    }

    @Test
    public void recordsNoDriftWhenSampleRateZeroEvenIfEnabled() {
        // given - enabled but sampled out (rate 0.0 = never draw)
        configuration.driftDetectionEnabled(true).driftSampleRate(0.0d);

        // when
        List<DriftRecord> records = forwardDriftingRequest(configuration);

        // then - the sampling gate short-circuits analysis
        assertThat("a zero sample rate must skip drift analysis", records.size(), is(0));
    }

    private HttpForwardActionResult completedForwardResult(HttpResponse upstreamResponse) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(upstreamResponse);
        HttpRequest forwardedRequest = mock(HttpRequest.class);
        return new HttpForwardActionResult(forwardedRequest, future, null, new InetSocketAddress(1234));
    }
}
