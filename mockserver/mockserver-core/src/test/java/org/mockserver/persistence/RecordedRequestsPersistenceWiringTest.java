package org.mockserver.persistence;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Locks the end-to-end wiring that the direct {@link RecordedRequestsFileSystemPersistenceTest} does
 * NOT cover: when {@code persistRecordedRequestsToDisk=true}, the real {@link HttpState} constructor
 * registers {@link RecordedRequestsFileSystemPersistence} as the {@code MockServerEventLog}
 * recorded-request consumer, and each FORWARDED_REQUEST driven through the live event-log wiring is
 * appended AND flushed per line — so the NDJSON archive is readable BEFORE {@code stop()}/close is ever
 * called (the flush-before-stop durability guarantee that protects captured traffic against an OOM
 * kill).
 */
public class RecordedRequestsPersistenceWiringTest {

    private ScheduledExecutorService schedulerExecutor;

    @After
    public void tearDown() {
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
        }
    }

    @Test(timeout = 30000)
    public void shouldWireRecordedRequestConsumerAndFlushEachLineBeforeStop() throws Exception {
        // given a real HttpState configured to persist recorded requests to a temp NDJSON file
        File persistedFile = File.createTempFile("persistedRecordedRequestsWiring", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(persistedFile.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsPersistenceWiringTest.class);

        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = Executors.newScheduledThreadPool(2);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);

        // constructing HttpState is what wires RecordedRequestsFileSystemPersistence::append as the
        // MockServerEventLog recorded-request consumer (only when the flag is on)
        HttpState httpState = new HttpState(configuration, logger, scheduler);

        // when a FORWARDED_REQUEST exchange flows through the live event-log wiring
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/api/wired").withMethod("GET"))
            .setHttpResponse(response().withStatusCode(200).withBody("wired-response")));

        // deterministic barrier (no sleep): the disruptor is a single FIFO consumer, so a retrieve
        // published after the log entry only completes once that entry has been fully processed —
        // which includes the recorded-request consumer's append+flush that runs before add()
        CompletableFuture<List<RequestDefinition>> processed = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveRequests(request("/api/wired"), processed::complete);
        assertThat(processed.get(10, SECONDS), notNullValue());

        // then — the NDJSON line is durably readable WITHOUT calling stop()/close (flush-per-line)
        HttpRequestAndHttpResponseSerializer serializer = new HttpRequestAndHttpResponseSerializer(logger);
        List<String> lines = Files.readAllLines(persistedFile.toPath(), StandardCharsets.UTF_8);
        assertThat(lines.size(), is(1));
        HttpRequestAndHttpResponse persisted = serializer.deserialize(lines.get(0));
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
        Configuration configuration = configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(persistedFile.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsPersistenceWiringTest.class);

        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = Executors.newScheduledThreadPool(2);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);

        HttpState httpState = new HttpState(configuration, logger, scheduler);

        // when a FORWARDED (proxied) and an EXPECTATION_RESPONSE (mocked) exchange flow through
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/api/forwarded").withMethod("GET"))
            .setHttpResponse(response().withStatusCode(200).withBody("forwarded")));
        httpState.log(new LogEntry()
            .setType(EXPECTATION_RESPONSE)
            .setHttpRequest(request("/api/mocked").withMethod("GET"))
            .setHttpResponse(response().withStatusCode(200).withBody("mocked")));

        // deterministic barrier: a retrieve published after the entries only completes once both
        // have been fully processed (append+flush runs before add())
        CompletableFuture<List<RequestDefinition>> processed = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveRequests(request("/api/mocked"), processed::complete);
        assertThat(processed.get(10, SECONDS), notNullValue());

        // then — BOTH exchanges are on disk (not just the forwarded one)
        HttpRequestAndHttpResponseSerializer serializer = new HttpRequestAndHttpResponseSerializer(logger);
        List<String> lines = Files.readAllLines(persistedFile.toPath(), StandardCharsets.UTF_8);
        assertThat(lines.size(), is(2));
        HttpRequestAndHttpResponse first = serializer.deserialize(lines.get(0));
        HttpRequestAndHttpResponse second = serializer.deserialize(lines.get(1));
        assertThat(first.getHttpRequest().getPath().getValue(), is("/api/forwarded"));
        assertThat(first.getHttpResponse().getBodyAsString(), is("forwarded"));
        assertThat(second.getHttpRequest().getPath().getValue(), is("/api/mocked"));
        assertThat(second.getHttpResponse().getBodyAsString(), is("mocked"));
    }
}
