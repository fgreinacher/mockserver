package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.StreamingBody;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.scheduler.Scheduler;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A matched request that a full bounded queue refuses is answered with a 503 on the dispatching thread and
 * still post-processed, while an admitted request keeps its delay.
 */
public class HttpActionHandlerOverloadTest {

    private Scheduler scheduler;
    private HttpState httpState;
    private ResponseWriter responseWriter;
    @Mock
    private HttpForwardActionHandler forwardActionHandler;
    @InjectMocks
    private HttpActionHandler injectedActionHandler;

    @Before
    public void setUp() {
        httpState = mock(HttpState.class);
        responseWriter = mock(ResponseWriter.class);
        when(httpState.getMockServerLogger()).thenReturn(new MockServerLogger());
        when(httpState.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_overload");
        when(httpState.getCrudDispatcher()).thenReturn(new CrudDispatcher());
    }

    @After
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private HttpActionHandler handler(Configuration configuration) {
        scheduler = new Scheduler(configuration, new MockServerLogger());
        when(httpState.getScheduler()).thenReturn(scheduler);
        return new HttpActionHandler(configuration, null, httpState, null, null);
    }

    @Test
    public void shouldAnswerDelayedResponseOverTheLimitWith503() {
        HttpActionHandler actionHandler = handler(configuration().maxPendingDelayedResponses(1));
        HttpRequest request = request("/delayed");
        Expectation expectation = new Expectation(request).thenRespond(response("delayed body").withDelay(TimeUnit.SECONDS, 30));
        when(httpState.firstMatchingExpectation(any(HttpRequest.class))).thenReturn(expectation);

        actionHandler.processAction(request, responseWriter, null, new HashSet<>(), false, false);
        verify(responseWriter, never()).writeResponse(any(HttpRequest.class), any(HttpResponse.class), eq(false));

        actionHandler.processAction(request, responseWriter, null, new HashSet<>(), false, false);

        assertOverloadResponse();
        verify(httpState, times(1)).postProcess(expectation);
        assertThat("only the admitted request is waiting", scheduler.getPendingDelayedTaskCount(), is(1));
    }

    @Test
    public void shouldAnswerTemplateOverTheQueueLimitWith503() throws Exception {
        HttpActionHandler actionHandler = handler(configuration().actionHandlerThreadCount(1).maxQueuedTemplateActions(1));
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch blockerStarted = new CountDownLatch(1);
            scheduler.scheduleTemplateAction(() -> {
                blockerStarted.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, false);
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS), is(true));

            HttpRequest request = request("/template");
            Expectation expectation = new Expectation(request).thenRespond(HttpTemplate.template(HttpTemplate.TemplateType.VELOCITY, "{ \"statusCode\": 200 }"));
            when(httpState.firstMatchingExpectation(any(HttpRequest.class))).thenReturn(expectation);

            actionHandler.processAction(request, responseWriter, null, new HashSet<>(), false, false);
            actionHandler.processAction(request, responseWriter, null, new HashSet<>(), false, false);

            assertOverloadResponse();
            verify(httpState, times(1)).postProcess(expectation);
        } finally {
            release.countDown();
        }
    }

    private void assertOverloadResponse() {
        ArgumentCaptor<HttpResponse> written = ArgumentCaptor.forClass(HttpResponse.class);
        verify(responseWriter, timeout(5_000).times(1)).writeResponse(any(HttpRequest.class), written.capture(), eq(false));
        assertThat(written.getValue().getStatusCode(), is(503));
        assertThat(written.getValue().getFirstHeader("Retry-After"), is("1"));
    }

    @Test
    public void shouldWriteForwardedResponseWithoutChaosLatencyRatherThanRefuseItWhenBudgetIsFull() {
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200);
        HttpResponse written = forwardWithChaosLatencyWhileBudgetIsFull(upstreamResponse);

        assertThat("the real upstream response, not a 503", written.getStatusCode(), is(200));
        assertThat(written.getBodyAsString(), is("upstream body"));
    }

    @Test
    public void shouldHandStreamingForwardedResponseToTheWriterWithoutChaosLatencyWhenBudgetIsFull() {
        HttpResponse upstreamResponse = response().withStatusCode(200).withStreamingBody(new StreamingBody(1024));
        HttpResponse written = forwardWithChaosLatencyWhileBudgetIsFull(upstreamResponse);

        assertThat("the writer subscribes to the live upstream stream", written.getStreamingBody(), is(sameInstance(upstreamResponse.getStreamingBody())));
    }

    /**
     * Fills the delayed-response budget, then forwards through an expectation with 30s chaos latency: the
     * upstream has already been called, so the response must be written at once instead of being refused.
     */
    private HttpResponse forwardWithChaosLatencyWhileBudgetIsFull(HttpResponse upstreamResponse) {
        injectedActionHandler = handler(configuration().maxPendingDelayedResponses(1));
        openMocks(this);
        scheduler.schedule(Scheduler.rejectable(() -> {
        }, () -> {
        }), false, Delay.seconds(30));

        HttpRequest request = request("/forwarded");
        CompletableFuture<HttpResponse> upstream = CompletableFuture.completedFuture(upstreamResponse);
        when(forwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class)))
            .thenReturn(new HttpForwardActionResult(mock(HttpRequest.class), upstream, null, new InetSocketAddress(1234)));
        Expectation expectation = new Expectation(request)
            .thenForward(forward().withHost("localhost").withPort(1090))
            .withChaos(httpChaosProfile().withLatency(Delay.seconds(30)));
        when(httpState.firstMatchingExpectation(any(HttpRequest.class))).thenReturn(expectation);

        injectedActionHandler.processAction(request, responseWriter, null, new HashSet<>(), false, false);

        ArgumentCaptor<HttpResponse> written = ArgumentCaptor.forClass(HttpResponse.class);
        verify(responseWriter, timeout(5_000).times(1)).writeResponse(any(HttpRequest.class), written.capture(), eq(false));
        verify(forwardActionHandler, times(1)).handle(any(HttpForward.class), any(HttpRequest.class));
        assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.DELAY_SKIPPED), is(1L));
        return written.getValue();
    }
}
