package org.mockserver.persistence;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RUNNABLE;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Locks the end-to-end wiring that the direct {@link RecordedRequestsFileSystemPersistenceTest} does
 * NOT cover: when {@code persistRecordedRequestsToDisk=true}, the real {@link HttpState} constructor
 * registers {@link RecordedRequestsFileSystemPersistence} as the {@code MockServerEventLog}
 * recorded-request consumer, lines are buffered and flushed when the event-log consumer ends a batch
 * (or per entry when processing is synchronous) — so the NDJSON archive is readable BEFORE
 * {@code stop()}/close is ever called — and a {@code ?source=disk} import sees every exchange the
 * consumer has already recorded, even mid-batch.
 */
public class RecordedRequestsPersistenceWiringTest {

    private ScheduledExecutorService schedulerExecutor;
    private final List<HttpState> httpStates = new ArrayList<>();

    @After
    public void tearDown() {
        try {
            // stops each event log and closes each capture file, even when the test failed
            for (HttpState httpState : httpStates) {
                httpState.stop();
            }
        } finally {
            if (schedulerExecutor != null) {
                schedulerExecutor.shutdownNow();
            }
        }
    }

    private HttpState httpState(Configuration configuration, MockServerLogger logger) {
        HttpState httpState = new HttpState(configuration, logger, scheduler());
        httpStates.add(httpState);
        return httpState;
    }

    @Test(timeout = 30000)
    public void shouldWireRecordedRequestConsumerAndFlushAtEndOfBatchBeforeStop() throws Exception {
        // given a real HttpState configured to persist recorded requests to a temp NDJSON file
        File persistedFile = File.createTempFile("persistedRecordedRequestsWiring", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = capturingTo(persistedFile);
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsPersistenceWiringTest.class);

        // constructing HttpState is what wires RecordedRequestsFileSystemPersistence as the
        // MockServerEventLog recorded-request consumer (only when the flag is on)
        HttpState httpState = httpState(configuration, logger);

        // when a FORWARDED_REQUEST exchange flows through the live event-log wiring
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/api/wired").withMethod("GET"))
            .setHttpResponse(response().withStatusCode(200).withBody("wired-response")));

        // then — the line becomes readable WITHOUT stop()/close or any explicit flush: the consumer
        // flushes when its batch ends (a line this small never fills the write buffer on its own)
        List<String> lines = awaitLines(persistedFile, 1);
        HttpRequestAndHttpResponse persisted = new HttpRequestAndHttpResponseSerializer(logger).deserialize(lines.get(0));
        assertThat(persisted, notNullValue());
        assertThat(persisted.getHttpRequest().getPath().getValue(), is("/api/wired"));
        assertThat(persisted.getHttpRequest().getMethod().getValue(), is("GET"));
        assertThat(persisted.getHttpResponse().getBodyAsString(), is("wired-response"));
    }

    @Test(timeout = 30000)
    public void shouldPersistBothForwardedAndMockedExchanges() throws Exception {
        // given a real HttpState configured to persist recorded requests
        File persistedFile = File.createTempFile("persistedRecordedRequestsBoth", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = capturingTo(persistedFile);
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsPersistenceWiringTest.class);
        HttpState httpState = httpState(configuration, logger);

        // when a FORWARDED (proxied) and an EXPECTATION_RESPONSE (mocked) exchange flow through
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/api/forwarded").withMethod("GET"))
            .setHttpResponse(response().withStatusCode(200).withBody("forwarded")));
        httpState.log(new LogEntry()
            .setType(EXPECTATION_RESPONSE)
            .setHttpRequest(request("/api/mocked").withMethod("GET"))
            .setHttpResponse(response().withStatusCode(200).withBody("mocked")));

        // then — BOTH exchanges are on disk (not just the forwarded one), in order
        List<String> lines = awaitLines(persistedFile, 2);
        HttpRequestAndHttpResponseSerializer serializer = new HttpRequestAndHttpResponseSerializer(logger);
        HttpRequestAndHttpResponse first = serializer.deserialize(lines.get(0));
        HttpRequestAndHttpResponse second = serializer.deserialize(lines.get(1));
        assertThat(first.getHttpRequest().getPath().getValue(), is("/api/forwarded"));
        assertThat(first.getHttpResponse().getBodyAsString(), is("forwarded"));
        assertThat(second.getHttpRequest().getPath().getValue(), is("/api/mocked"));
        assertThat(second.getHttpResponse().getBodyAsString(), is("mocked"));
    }

    @Test(timeout = 30000)
    public void shouldImportFromDiskAnExchangeRecordedEarlierInTheSameUnfinishedBatch() throws Exception {
        // given a real HttpState capturing to disk
        File persistedFile = File.createTempFile("persistedRecordedRequestsMidBatch", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = capturingTo(persistedFile);
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsPersistenceWiringTest.class);
        HttpState httpState = httpState(configuration, logger);

        // and the consumer held INSIDE a batch that has already recorded an exchange: park it, publish
        // the exchange and a blocking task behind it, then let it drain them as one batch — so the
        // end-of-batch flush has not run when the import reads the file
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch unpark = new CountDownLatch(1);
        CountDownLatch heldAfterExchange = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        httpState.log(runnable(() -> {
            parked.countDown();
            await(unpark);
        }));
        await(parked);
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/api/mid-batch").withMethod("GET"))
            .setHttpResponse(response().withStatusCode(200).withBody("mid-batch-body")));
        httpState.log(runnable(() -> {
            heldAfterExchange.countDown();
            await(release);
        }));
        unpark.countDown();
        await(heldAfterExchange);

        try {
            // when — re-import the archive from disk
            FakeResponseWriter importWriter = new FakeResponseWriter();
            httpState.handle(request("/mockserver/import")
                .withMethod("PUT")
                .withQueryStringParameter("format", "recording")
                .withQueryStringParameter("source", "disk")
                .withQueryStringParameter("redactSensitiveData", "false"), importWriter, false);
            importWriter.await();

            // then — the exchange recorded before the read is in the imported archive
            assertThat(importWriter.response.getStatusCode(), is(201));
            assertThat(importWriter.response.getBodyAsString(), containsString("/api/mid-batch"));
            assertThat(importWriter.response.getBodyAsString(), containsString("mid-batch-body"));
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 30000)
    public void shouldFlushEachRecordedExchangeWhenEventProcessingIsSynchronous() throws Exception {
        // given a synchronous event log wired to disk capture exactly as HttpState wires it
        File persistedFile = File.createTempFile("persistedRecordedRequestsSynchronous", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = capturingTo(persistedFile);
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsPersistenceWiringTest.class);
        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);
        MockServerEventLog eventLog = new MockServerEventLog(configuration, logger, scheduler(), false);
        eventLog.setRecordedRequestConsumer(persistence::append, persistence::flush);
        try {
            // when
            eventLog.add(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/synchronous"))
                .setHttpResponse(response().withBody("synchronous-body")));

            // then — on disk as soon as add() returns, with no batch boundary to wait for
            List<String> lines = Files.readAllLines(persistedFile.toPath(), StandardCharsets.UTF_8);
            assertThat(lines.size(), is(1));
            assertThat(lines.get(0), containsString("/api/synchronous"));
        } finally {
            eventLog.stop();
            persistence.stop();
        }
    }

    private Scheduler scheduler() {
        Scheduler scheduler = mock(Scheduler.class);
        if (schedulerExecutor == null) {
            schedulerExecutor = Executors.newScheduledThreadPool(2);
        }
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        return scheduler;
    }

    private static Configuration capturingTo(File persistedFile) {
        return configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(persistedFile.getAbsolutePath());
    }

    private static LogEntry runnable(Runnable runnable) {
        return new LogEntry().setType(RUNNABLE).setConsumer(runnable);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, SECONDS)) {
                throw new AssertionError("timed out waiting for the event-log consumer");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static List<String> awaitLines(File file, int expected) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        while (lines.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        }
        assertThat(lines.size(), is(expected));
        return lines;
    }

    private static class FakeResponseWriter extends ResponseWriter {
        volatile HttpResponse response;
        private final CountDownLatch latch = new CountDownLatch(1);

        FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
            latch.countDown();
        }

        void await() throws InterruptedException {
            if (!latch.await(30, SECONDS)) {
                fail("timed out waiting for handler response");
            }
        }
    }
}
