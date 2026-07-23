package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.mock.drift.DriftStore;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.uuid.UUIDService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Full drift-detection <em>assembly</em> test: proves the whole path holds together end to end —
 * a live forward through the real {@link HttpActionHandler} → asynchronous drift analysis (run
 * synchronously here) → the process-wide {@link DriftStore} → the real {@code GET /mockserver/drift}
 * retrieval endpoint in {@link HttpState}.
 *
 * <p>The individual pieces are unit-tested elsewhere ({@code DriftAnalyzerTest},
 * {@code DriftStoreTest}, {@code HttpActionHandlerDriftDetectionTest} for the {@code driftDetectionEnabled}
 * gate). What none of those exercise is the <em>assembled</em> path through to the GET endpoint that
 * the Drift dashboard tab actually consumes. This test drives both real ends — the forward handler
 * that <em>writes</em> drift and the control-plane handler that <em>reads</em> it back — bridged by
 * the same singleton {@link DriftStore} that bridges them in production.</p>
 */
public class DriftEndToEndAssemblyTest {

    // -------- forward-path collaborators (real HttpActionHandler, mocked action handlers) --------
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
    private Scheduler forwardScheduler;

    // -------- retrieval-side (real HttpState serving GET /mockserver/drift) --------
    private HttpState realHttpState;

    /**
     * Capturing {@link ResponseWriter} that records the response the control-plane handler writes.
     */
    private static class CapturingResponseWriter extends ResponseWriter {
        private volatile HttpResponse response;

        protected CapturingResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }

    @Before
    public void setup() {
        DriftStore.getInstance().clear();
        configuration = configuration().logLevel(Level.INFO);

        // --- real forward handler backed by a mocked HttpState for expectation injection ---
        mockHttpStateHandler = mock(HttpState.class);
        forwardScheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(forwardScheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);

        // run the (normally async) drift-analysis task synchronously so the store is populated deterministically
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(forwardScheduler).submit(any(Runnable.class));

        // --- real HttpState that serves the GET /mockserver/drift retrieval endpoint ---
        Scheduler retrievalScheduler = mock(Scheduler.class);
        realHttpState = new HttpState(configuration, new MockServerLogger(configuration, DriftEndToEndAssemblyTest.class), retrievalScheduler);
    }

    @After
    public void tearDown() {
        DriftStore.getInstance().clear();
        if (forwardScheduler != null) {
            forwardScheduler.shutdown();
        }
    }

    /**
     * Drives a live forward through the real handler whose upstream returns 500 while a co-registered
     * response stub says the endpoint returns 200 (a STATUS drift), then reads the recorded drift back
     * through the real {@code GET /mockserver/drift} endpoint.
     */
    @Test
    public void driftDetectedOnForwardIsRetrievableThroughGetDriftEndpoint() throws Exception {
        // given - drift detection enabled and always sampled
        configuration.driftDetectionEnabled(true).driftSampleRate(1.0d);

        HttpRequest request = request("/some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(500).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation forwardExpectation = new Expectation(request).thenForward(forward);
        Expectation stubExpectation = new Expectation(request).thenRespond(response("stub body").withStatusCode(200));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(forwardExpectation);
        when(mockHttpStateHandler.allMatchingExpectation(request)).thenReturn(List.of(stubExpectation));
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when - the request is forwarded through the real handler (live forward -> analyse -> DriftStore)
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // and - the Drift dashboard's retrieval call is served by the real control-plane endpoint
        CapturingResponseWriter driftGetWriter = new CapturingResponseWriter();
        boolean handled = realHttpState.handle(request("/mockserver/drift").withMethod("GET"), driftGetWriter, false);

        // then - the assembled path returns the recorded STATUS drift end to end
        assertThat("GET /mockserver/drift must be handled by the control plane", handled, is(true));
        assertThat(driftGetWriter.response.getStatusCode(), is(200));

        JsonNode body = ObjectMapperFactory.createObjectMapper().readTree(driftGetWriter.response.getBodyAsString());
        assertThat("GET /mockserver/drift must report at least the one recorded drift",
            body.get("count").asInt(), is(greaterThanOrEqualTo(1)));

        JsonNode statusDrift = findStatusDriftFor(body, stubExpectation.getId());
        assertThat("the STATUS drift recorded on the forward must be retrievable via GET /mockserver/drift",
            statusDrift, is(org.hamcrest.Matchers.notNullValue()));
        assertThat(statusDrift.get("expectationId").asText(), is(stubExpectation.getId()));
        assertThat(statusDrift.get("expectedValue").asText(), is("200"));
        assertThat(statusDrift.get("actualValue").asText(), is("500"));

        // and - the same record is retrievable by the expectationId query filter the dashboard uses
        CapturingResponseWriter byIdWriter = new CapturingResponseWriter();
        realHttpState.handle(
            request("/mockserver/drift").withMethod("GET").withQueryStringParameter("expectationId", stubExpectation.getId()),
            byIdWriter, false);
        JsonNode byIdBody = ObjectMapperFactory.createObjectMapper().readTree(byIdWriter.response.getBodyAsString());
        assertThat(byIdBody.get("count").asInt(), is(greaterThanOrEqualTo(1)));
        assertThat(findStatusDriftFor(byIdBody, stubExpectation.getId()), is(org.hamcrest.Matchers.notNullValue()));
    }

    /**
     * Negative half of the assembly: when nothing drifts (upstream matches the stub) the forward writes
     * no record, and the real GET endpoint reports an empty result — proving the endpoint reflects the
     * store rather than fabricating data.
     */
    @Test
    public void noForwardDriftYieldsEmptyGetDriftResult() throws Exception {
        configuration.driftDetectionEnabled(true).driftSampleRate(1.0d);

        HttpRequest request = request("/matching_path");
        // upstream response matches the stub exactly -> no drift
        HttpResponse upstreamResponse = response("stub body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation forwardExpectation = new Expectation(request).thenForward(forward);
        Expectation stubExpectation = new Expectation(request).thenRespond(response("stub body").withStatusCode(200));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(forwardExpectation);
        when(mockHttpStateHandler.allMatchingExpectation(request)).thenReturn(List.of(stubExpectation));
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        CapturingResponseWriter driftGetWriter = new CapturingResponseWriter();
        realHttpState.handle(request("/mockserver/drift").withMethod("GET"), driftGetWriter, false);

        assertThat(driftGetWriter.response.getStatusCode(), is(200));
        JsonNode body = ObjectMapperFactory.createObjectMapper().readTree(driftGetWriter.response.getBodyAsString());
        assertThat("a non-drifting forward must leave GET /mockserver/drift empty",
            body.get("count").asInt(), is(0));
    }

    private JsonNode findStatusDriftFor(JsonNode body, String expectationId) {
        JsonNode drifts = body.get("drifts");
        if (drifts != null && drifts.isArray()) {
            for (JsonNode drift : drifts) {
                if ("STATUS".equals(drift.path("driftType").asText())
                    && expectationId.equals(drift.path("expectationId").asText())) {
                    return drift;
                }
            }
        }
        return null;
    }

    private HttpForwardActionResult completedForwardResult(HttpResponse upstreamResponse) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(upstreamResponse);
        HttpRequest forwardedRequest = mock(HttpRequest.class);
        return new HttpForwardActionResult(forwardedRequest, future, null, new InetSocketAddress(1234));
    }
}
