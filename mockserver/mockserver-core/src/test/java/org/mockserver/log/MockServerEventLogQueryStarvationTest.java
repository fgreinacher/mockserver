package org.mockserver.log;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

/**
 * Invariant test for the query/append starvation fix: while a query is executing, log ingestion must
 * not be starved into dropping events.
 * <p>
 * Historically every query (retrieve / verify / clear scan) executed to completion on the SINGLE
 * disruptor consumer thread — the same thread that appends log entries. A query's whole execution (its
 * O(n) scan, each entry doing a cloned request match, AND delivery of the result to the caller's
 * consumer) therefore blocked ingestion: while it ran, incoming writes backed up in the fixed ring
 * buffer and, once the ring filled, were DROPPED with only a WARN-once — silently losing the
 * {@code RECEIVED_REQUEST} evidence a later {@code verify} needs.
 * <p>
 * This test drives that invariant deterministically rather than relying on a scan being slow enough on
 * a given machine: it issues a REAL retrieve query whose result consumer is held on a latch for the
 * duration of a bounded, paced writer. On the fixed code the query's scan-and-deliver runs OFF the
 * consumer thread (against a cheap consistent snapshot), so the consumer stays free to append and the
 * paced writer — comfortably within a free consumer's throughput — drops nothing. Reverting the fix
 * puts that execution back on the consumer thread, where the held consumer stalls ingestion and the
 * writer overflows the ring: the assertion below goes from 0 drops to tens of thousands. That is the
 * degrade-proof for this control.
 */
public class MockServerEventLogQueryStarvationTest {

    private static final int MAX_LOG_ENTRIES = 100_000;   // large enough that nothing is evicted
    private static final int RING = 4_096;                 // fixed ring; a stalled consumer overflows it
    private static final int PREFILL = 5_000;              // gives the query a real, non-trivial scan
    private static final int WRITES_DURING_QUERY = 40_000; // >> RING, so a stalled consumer certainly drops
    private static final int WRITES_PER_MILLI = 20;        // ~20k/s: far below a free consumer's throughput

    @Test
    public void queryExecutingConcurrentlyWithPacedWriterCausesNoDrops() throws Exception {
        Configuration configuration = configuration()
            .maxLogEntries(MAX_LOG_ENTRIES)
            .ringBufferSize(RING)
            // Count-bounded only: disable the byte bounds so the ONLY drop mechanism exercised here is
            // ring-buffer saturation (the starvation cliff), not the in-flight / retained byte valves.
            .maxEventLogSizeInBytes(0L)
            // WARN so the consumer does not render every INFO entry to system out — keeps the append path
            // fast and the test deterministic; RECEIVED_REQUEST entries are retained at any level.
            .logLevel(Level.WARN);
        Scheduler scheduler = mock(Scheduler.class);
        MockServerEventLog eventLog = new MockServerEventLog(
            configuration,
            new MockServerLogger(configuration, MockServerEventLog.class),
            scheduler,
            true // asynchronousEventProcessing => the disruptor path that can drop on overflow
        );

        final CountDownLatch queryConsumerStarted = new CountDownLatch(1);
        final CountDownLatch releaseQueryConsumer = new CountDownLatch(1);
        try {
            // Prefill a real log, backpressured on the (public, cheap) ring occupancy so the prefill
            // itself never overflows the fixed ring and drops nothing.
            for (int i = 0; i < PREFILL; i++) {
                while (eventLog.getRingBufferOccupancy() > RING * 3L / 4) {
                    Thread.sleep(0, 200_000);
                }
                eventLog.add(receivedRequest("/prefill/" + i));
            }
            settle(eventLog);
            assertThat("prefill must not itself drop", eventLog.getDroppedLogEventCount(), is(0L));
            assertThat("prefill must be fully retained (no eviction)", eventLog.size(), is(PREFILL));

            // Issue a real query (request() matches every entry) whose result consumer parks on a latch
            // for the whole writer window. On the pre-fix code this parks the disruptor consumer thread;
            // on the fixed code it parks a query-executor thread, leaving the consumer free to append.
            eventLog.retrieveRequests(request(), (List<RequestDefinition> ignored) -> {
                queryConsumerStarted.countDown();
                try {
                    releaseQueryConsumer.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat("query consumer should start", queryConsumerStarted.await(30, TimeUnit.SECONDS), is(true));

            // Paced writer: publish WRITES_DURING_QUERY entries at ~WRITES_PER_MILLI per millisecond while
            // the query consumer is held. A free consumer keeps up (0 drops); a consumer stalled by the
            // held query cannot, and the ring overflows.
            for (int i = 0; i < WRITES_DURING_QUERY; i++) {
                eventLog.add(receivedRequest("/live/" + i));
                if (i % WRITES_PER_MILLI == WRITES_PER_MILLI - 1) {
                    Thread.sleep(1);
                }
            }
        } finally {
            releaseQueryConsumer.countDown();
        }
        settle(eventLog);

        try {
            assertThat("the paced writer must exceed the ring so a stalled consumer would certainly drop",
                WRITES_DURING_QUERY, greaterThan(RING));
            assertThat(
                "a query executing concurrently with a paced writer must not drop any log events (drops=" +
                    eventLog.getDroppedLogEventCount() + ")",
                eventLog.getDroppedLogEventCount(), is(0L));
        } finally {
            eventLog.stop();
        }
    }

    private static LogEntry receivedRequest(String path) {
        return new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request(path))
            .setMessageFormat("received request:{}")
            .setArguments(request(path));
    }

    /**
     * Round-trip a retrieve through the disruptor and wait for it, forcing the consumer to process
     * everything published before it (its RUNNABLE is ordered after all prior appends).
     */
    private static void settle(MockServerEventLog eventLog) throws Exception {
        CompletableFuture<List<RequestDefinition>> future = new CompletableFuture<>();
        eventLog.retrieveRequests(request(), future::complete);
        future.get(60, TimeUnit.SECONDS);
    }
}
