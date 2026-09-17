package org.mockserver.log;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.Verification;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationTimes.atMost;
import static org.mockserver.verify.VerificationTimes.never;

/**
 * Verifies the in-flight (ring backlog) byte bound added to {@link MockServerEventLog}: the pool of
 * bytes held by log entries that have been published to the disruptor ring but not yet processed by
 * the single consumer. The deque byte budget ({@code maxEventLogSizeInBytes}) bounds only what has
 * already been RETAINED after processing and cannot see the ring; under sustained large-body ingress
 * the ring backlog — one full body per pre-allocated slot — is what exhausts the heap, so the same
 * budget is applied to the in-flight pool and over-budget entries are dropped rather than allowed to
 * OOM the server.
 * <p>
 * Three properties are covered: the admission decision (pure {@link MockServerEventLog#wouldExceedInFlightBudget}
 * seam), the increment/decrement accounting balancing across ring reuse, and — critically — that a
 * DROPPED entry taints the fail-closed verify path exactly as an eviction does, so an upper-bound
 * verify cannot silently pass on evidence that was dropped before it was ever recorded.
 */
public class MockServerEventLogInFlightBytesTest {

    // ---- wouldExceedInFlightBudget: the admission boundary rules ----

    @Test
    public void shouldNotRejectWhenBudgetDisabled() {
        // budget <= 0 disables the in-flight bound (ring bounded by slot count only), like the deque's
        // "0 disables the byte budget" contract — so it never rejects however large the backlog.
        assertThat(MockServerEventLog.wouldExceedInFlightBudget(0L, 1_000_000L, 1_000_000L), is(false));
        assertThat(MockServerEventLog.wouldExceedInFlightBudget(-1L, 1_000_000L, 1_000_000L), is(false));
    }

    @Test
    public void shouldNotRejectABodylessEntry() {
        // a control / diagnostic entry with no body costs nothing to hold in flight
        assertThat(MockServerEventLog.wouldExceedInFlightBudget(100L, 100L, 0L), is(false));
    }

    @Test
    public void shouldAlwaysAdmitIntoAnEmptyBacklogEvenWhenOversized() {
        // never reject into an empty backlog: a single body larger than the whole budget is still
        // admitted (mirrors the deque's "one oversized element is still retained" rule)
        assertThat(MockServerEventLog.wouldExceedInFlightBudget(100L, 0L, 10_000L), is(false));
    }

    @Test
    public void shouldRejectOnceInFlightPlusIncomingExceedsBudget() {
        // 60 already in flight + 50 incoming = 110 > 100 -> reject
        assertThat(MockServerEventLog.wouldExceedInFlightBudget(100L, 60L, 50L), is(true));
    }

    @Test
    public void shouldAdmitExactlyUpToTheBudget() {
        // 60 + 40 = 100, not over 100 -> admit (strict greater-than boundary)
        assertThat(MockServerEventLog.wouldExceedInFlightBudget(100L, 60L, 40L), is(false));
        // one more byte tips it over
        assertThat(MockServerEventLog.wouldExceedInFlightBudget(100L, 60L, 41L), is(true));
    }

    // ---- end-to-end: the increment (publish) / decrement (process) accounting balances across reuse ----

    @Test
    public void shouldReturnInFlightBytesToZeroAfterTheRingDrains() {
        // large budget so nothing is dropped: every published body is counted in, then counted out as
        // the consumer processes it. Publish MORE entries than the ring has slots (maxLogEntries 1000
        // => a 1024-slot ring) so slots are reused many times over — proving the accounting does not
        // drift on slot reuse. After a drain the in-flight total must be exactly zero.
        Configuration configuration = configuration()
            .maxLogEntries(1000)
            .maxEventLogSizeInBytes(64L * 1024 * 1024)
            .maxLoggedBodyBytes(0);
        MockServerEventLog log = asynchronousEventLog(configuration);
        try {
            for (int i = 0; i < 1500; i++) {
                log.add(receivedRequestWithBody("/load", 10_000));
            }
            drain(log);

            assertThat(log.getInFlightBytes(), is(0L));
        } finally {
            log.stop();
        }
    }

    @Test
    public void shouldNeverDropWhenBudgetDisabled() throws Exception {
        // With the budget disabled (0) the accounting still runs (add() computes estimatedHeapSize and
        // tracks in/out unconditionally, so the counter cannot drift on a runtime budget change) — but
        // wouldExceedInFlightBudget always returns false, so NOTHING is ever dropped, even when a large
        // backlog is deliberately built up behind a held consumer. That "no drops despite a big
        // backlog" is the real disabled property, and it distinguishes disabled from an enabled bound;
        // the after-drain zero alone cannot, because it is true whether or not tracking ran.
        Configuration configuration = configuration()
            .maxLogEntries(1000)
            .maxEventLogSizeInBytes(0L)
            .maxLoggedBodyBytes(0);
        MockServerEventLog log = asynchronousEventLog(configuration);
        try {
            CountDownLatch release = blockConsumer(log);
            for (int i = 0; i < 20; i++) {
                log.add(receivedRequestWithBody("/x", 100_000)); // ~2 MB backlog held in the ring
            }
            assertThat(log.getDroppedLogEventCount(), is(0L));
            release.countDown();
            drain(log);

            assertThat(log.getInFlightBytes(), is(0L));
        } finally {
            log.stop();
        }
    }

    // ---- CRITICAL: a dropped body-bearing entry must fail the upper-bound verify closed ----

    @Test
    public void shouldFailClosedUpperBoundVerifyAfterDroppingABodyBearingEntry() throws Exception {
        // A tiny in-flight budget so that, with the consumer held, admitting one oversized entry tips
        // the backlog over budget and every subsequent body-bearing entry is DROPPED before publish.
        Configuration configuration = configuration()
            .maxLogEntries(1000)
            .maxEventLogSizeInBytes(1000L)
            .maxLoggedBodyBytes(0);
        MockServerEventLog log = asynchronousEventLog(configuration);
        try {
            CountDownLatch release = blockConsumer(log);

            // first entry admitted into the empty backlog (even though > budget); it pushes in-flight > budget
            log.add(receivedRequestWithBody("/admitted", 2000));
            // subsequent body-bearing entries now exceed the in-flight budget and are dropped (never recorded)
            for (int i = 0; i < 5; i++) {
                log.add(receivedRequestWithBody("/dropped", 2000));
            }
            // drops are accounted on the producer thread inside add(), so they are already observable
            assertThat(log.getDroppedLogEventCount(), greaterThan(0L));

            release.countDown();
            drain(log);

            // /dropped never reached the log. A naive never() sees matchedCount==0 and would PASS — a
            // silent false green. It MUST fail closed: the request may have arrived and been dropped.
            String never = verify(log, verification().withRequest(request("/dropped")).withTimes(never()));
            assertThat(never, is(not("")));
            assertThat(never, containsString("could not be verified"));
            assertThat(never, containsString("DROPPED"));
            // atMost(0) is the same upper bound and must also fail closed
            assertThat(verify(log, verification().withRequest(request("/dropped")).withTimes(atMost(0))), containsString("DROPPED"));

            // after a reset the drop taint clears, so a later upper-bound verify can pass again
            log.reset();
            assertThat(verify(log, verification().withRequest(request("/dropped")).withTimes(never())), is(""));
        } finally {
            log.stop();
        }
    }

    // ---- helpers ----

    private MockServerEventLog asynchronousEventLog(Configuration configuration) {
        // asynchronous (true) so add() publishes to the disruptor ring and the in-flight accounting is
        // exercised; the ring runs on its own consumer thread, so the injected Scheduler can be a mock.
        return new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), mock(Scheduler.class), true);
    }

    private LogEntry receivedRequestWithBody(String path, int bodyBytes) {
        HttpRequest request = request().withMethod("POST").withPath(path).withBody(new byte[bodyBytes]);
        return new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setMessageFormat("received request:{}")
            .setArguments(request);
    }

    // Occupy the single consumer thread until the returned latch is counted down, so entries added in
    // the meantime pile up in the ring (published-but-unprocessed) and the in-flight bound is exercised
    // deterministically rather than racing the consumer.
    private CountDownLatch blockConsumer(MockServerEventLog log) throws InterruptedException {
        CountDownLatch consumerBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        log.retrieveMessageLogEntries(request(), entries -> {
            consumerBlocked.countDown();
            try {
                release.await(10, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(consumerBlocked.await(10, SECONDS), is(true));
        return release;
    }

    // Publish a retrieval (a RUNNABLE marker) and block on it: the disruptor processes FIFO, so when
    // this completes every entry added before it has been consumed and its in-flight bytes released.
    private void drain(MockServerEventLog log) {
        CompletableFuture<List<LogEntry>> future = new CompletableFuture<>();
        log.retrieveMessageLogEntries(request(), future::complete);
        try {
            future.get(60, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    private String verify(MockServerEventLog log, Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        log.verify(verification, result::complete);
        try {
            return result.get(30, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }
}
