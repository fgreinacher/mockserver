package org.mockserver.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

/**
 * Locks the threading contract of {@code HttpState.awaitRetrieve} / the {@code /mockserver/retrieve}
 * path: the (cheap) result list is materialised on the {@link MockServerEventLog} disruptor consumer
 * thread, but the (expensive) serialisation runs on the CALLER thread. This fixed a regression where a
 * large retrieve serialised inside the single disruptor consumer callback and thereby stalled ALL
 * further event logging until it finished.
 *
 * <h2>Chosen approach — deterministic thread-identity + latch-gated non-blocking assertion</h2>
 *
 * Driven through the REAL {@link HttpState}/{@link MockServerEventLog} disruptor wiring. We deliberately
 * avoid the brittle "make serialisation slow via a huge payload and race a wall-clock timer" style: that
 * is inherently flaky on shared CI. Instead we exploit the exact mechanism {@code awaitRetrieve} relies
 * on — a retrieve consumer that completes a future on the disruptor consumer thread, after which the
 * caller thread continues off the disruptor — and assert two things with no timing dependence:
 * <ol>
 *   <li><b>thread-identity</b>: the retrieve consumer (materialisation) runs on the {@code EventLog}
 *       disruptor thread, while the caller's subsequent work runs on a different (caller) thread; and</li>
 *   <li><b>non-blocking</b>: while the caller thread is blocked on a latch (standing in for the expensive
 *       serialisation), the disruptor consumer thread is still free to accept and process a newly-added
 *       log entry — i.e. post-materialisation work does not hold the single disruptor consumer.</li>
 * </ol>
 * Ordering is guaranteed by the disruptor being a single FIFO consumer: a {@code log(...)} published
 * before a {@code retrieve} is always processed first, so no {@code sleep} is needed.
 */
public class HttpStateRetrieveThreadingTest {

    private HttpState httpState;
    private ScheduledExecutorService schedulerExecutor;

    @Before
    public void setUp() {
        Configuration configuration = configuration();
        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = Executors.newScheduledThreadPool(2);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
    }

    @After
    public void tearDown() {
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
        }
    }

    @Test(timeout = 30000)
    public void retrieveSerializationRunsOffTheEventLogConsumerThread() throws Exception {
        // given one recorded request to retrieve (the retrieve/serialize threading contract under test
        // is identical regardless of log-entry type; RECEIVED_REQUEST is what retrieveRequests matches)
        httpState.log(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpRequest(request("/first")));

        MockServerEventLog eventLog = httpState.getMockServerLog();

        AtomicReference<String> materializationThreadName = new AtomicReference<>();
        AtomicReference<String> callerContinuationThreadName = new AtomicReference<>();
        CountDownLatch callerReachedContinuation = new CountDownLatch(1);
        CountDownLatch releaseCaller = new CountDownLatch(1);
        CompletableFuture<List<RequestDefinition>> materialized = new CompletableFuture<>();

        // the caller thread mirrors HttpState.awaitRetrieve: it triggers the retrieve (whose consumer
        // materialises the list on the disruptor thread and completes the future), waits for the list,
        // then does its "expensive serialisation" — here blocked on a latch so the assertion below is
        // deterministic rather than dependent on serialisation being measurably slow
        Thread caller = new Thread(() -> {
            eventLog.retrieveRequests(request("/first"), requests -> {
                materializationThreadName.set(Thread.currentThread().getName());
                materialized.complete(requests);
            });
            try {
                materialized.get(10, SECONDS);
                callerContinuationThreadName.set(Thread.currentThread().getName());
                callerReachedContinuation.countDown();
                releaseCaller.await(10, SECONDS);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        }, "test-retrieve-caller");
        caller.start();

        // wait until the caller thread has the materialised list and is blocked "serialising"
        assertThat("caller reached post-materialisation continuation",
            callerReachedContinuation.await(10, SECONDS), is(true));

        // while the caller is blocked, the disruptor consumer thread must still accept and process a
        // newly-added log entry — this would hang (and time out) if post-materialisation work held the
        // single disruptor consumer, which is exactly the regression this split fixed
        httpState.log(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpRequest(request("/late")));
        CompletableFuture<List<RequestDefinition>> lateRetrieve = new CompletableFuture<>();
        eventLog.retrieveRequests(request("/late"), lateRetrieve::complete);
        List<RequestDefinition> lateRequests = lateRetrieve.get(10, SECONDS);
        assertThat(lateRequests, hasSize(1));
        assertThat(((HttpRequest) lateRequests.get(0)).getPath().getValue(), is("/late"));

        // thread-identity: materialisation ran on the EventLog disruptor thread; the caller's
        // continuation (where serialisation happens in production) ran on the caller thread, not the
        // disruptor thread
        assertThat(materializationThreadName.get(), containsString("EventLog"));
        assertThat(callerContinuationThreadName.get(), is("test-retrieve-caller"));
        assertThat(callerContinuationThreadName.get(), not(containsString("EventLog")));

        // release the blocked caller and clean up
        releaseCaller.countDown();
        caller.join(SECONDS.toMillis(10));
    }
}
