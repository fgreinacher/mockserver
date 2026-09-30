package org.mockserver.log;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

/**
 * The event log's own wake-ups, isolated from the timed poll: each log here uses a wait strategy that polls
 * once an hour and never deep-parks, so an entry is only processed if {@link MockServerEventLog} explicitly
 * wakes the consumer.
 */
public class MockServerEventLogConsumerWakeTest {

    private static final long NEVER = TimeUnit.HOURS.toNanos(1);

    private final List<MockServerEventLog> logs = new ArrayList<>();
    private CoalescingWakeWaitStrategy strategy;

    @After
    public void stopLogs() {
        logs.forEach(MockServerEventLog::stop);
    }

    private MockServerEventLog neverPollingLog(long maxEventLogSizeInBytes) {
        Configuration configuration = configuration()
            .maxLogEntries(1_000)
            .maxEventLogSizeInBytes(maxEventLogSizeInBytes)
            .logLevel(Level.WARN);
        MockServerEventLog log = new MockServerEventLog(
            configuration,
            new MockServerLogger(configuration, MockServerEventLog.class),
            mock(Scheduler.class),
            true,
            ringBufferSize -> strategy = new CoalescingWakeWaitStrategy(NEVER, NEVER, Long.MAX_VALUE, NEVER)
        );
        logs.add(log);
        return log;
    }

    /**
     * Publishing before the consumer has parked would let it see the entry without any wake, so every add
     * whose delivery is asserted waits for the park first.
     */
    private void awaitConsumerParked() throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(30);
        while (strategy.consumerThread() == null || strategy.consumerThread().getState() != Thread.State.TIMED_WAITING) {
            assertThat("consumer did not park", System.nanoTime() < deadline, is(true));
            Thread.sleep(1);
        }
    }

    private static LogEntry receivedRequest(String path, String body) {
        return new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request(path).withBody(body))
            .setMessageFormat("received request:{}")
            .setArguments(request(path));
    }

    private static boolean awaitSize(MockServerEventLog log, int size, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (log.size() != size) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    private static boolean awaitRingDrained(MockServerEventLog log, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (log.getRingBufferOccupancy() != 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    @Test
    public void controlPlaneOperationsWakeAPollingConsumer() throws Exception {
        MockServerEventLog log = neverPollingLog(0);
        awaitConsumerParked();
        log.add(receivedRequest("/first", "body"));
        assertThat("a data entry alone does not wake the consumer", awaitSize(log, 1, 200), is(false));

        CompletableFuture<List<RequestDefinition>> retrieved = new CompletableFuture<>();
        log.retrieveRequests(request(), retrieved::complete);
        // the wake is proven by the ring draining; the scan then runs on the query pool, which a loaded host can delay
        assertThat("retrieve woke the consumer", awaitRingDrained(log, 30_000), is(true));
        assertThat(retrieved.get(120, SECONDS).size(), is(1));

        // clear gives up waiting for its RUNNABLE after 2s without failing, so await its effect instead
        awaitConsumerParked();
        log.add(receivedRequest("/second", "body"));
        log.clear(null);
        assertThat("clear woke the consumer", awaitRingDrained(log, 30_000), is(true));
        assertThat(log.size(), is(0));
    }

    @Test
    public void inFlightBytesPastAQuarterOfTheBudgetWakeAPollingConsumer() throws Exception {
        String body = "x".repeat(4_096);
        long weight = receivedRequest("/weighed", body).estimatedHeapSize();

        MockServerEventLog belowQuarter = neverPollingLog(weight * 8);
        awaitConsumerParked();
        belowQuarter.add(receivedRequest("/small-share", body));
        assertThat("an entry within a quarter of the budget waits for the poll", awaitSize(belowQuarter, 1, 200), is(false));

        MockServerEventLog aboveQuarter = neverPollingLog(weight * 2);
        awaitConsumerParked();
        aboveQuarter.add(receivedRequest("/large-share", body));
        assertThat("an entry past a quarter of the budget wakes the consumer", awaitSize(aboveQuarter, 1, 30_000), is(true));
        assertThat(aboveQuarter.getDroppedLogEventCount(), is(0L));
        assertThat(aboveQuarter.getInFlightBytes(), is(0L));
    }
}
