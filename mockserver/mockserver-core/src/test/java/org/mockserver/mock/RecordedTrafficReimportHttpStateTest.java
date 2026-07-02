package org.mockserver.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.RetrieveType.REQUEST_RESPONSES;

/**
 * End-to-end coverage for recorded-traffic re-import via the {@code PUT /mockserver/import?format=recording}
 * control-plane endpoint: an NDJSON archive of request/response pairs is loaded back and the pairs
 * become retrievable exactly like in-memory recordings (via {@code GET/PUT /retrieve?type=REQUEST_RESPONSES}).
 */
public class RecordedTrafficReimportHttpStateTest {

    private final Configuration configuration = configuration();
    private final MockServerLogger logger = new MockServerLogger(configuration, MockServerLogger.class);
    private final HttpRequestAndHttpResponseSerializer serializer = new HttpRequestAndHttpResponseSerializer(logger);
    private ScheduledExecutorService schedulerExecutor;
    private HttpState httpState;

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

    @Before
    public void setUp() {
        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = Executors.newScheduledThreadPool(2);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, logger, scheduler);
    }

    @After
    public void tearDown() {
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
        }
    }

    private String ndjsonLine(HttpRequestAndHttpResponse pair) {
        return serializer.serialize(pair).replaceAll("\\s*\\n\\s*", " ").trim();
    }

    @Test
    public void shouldReimportArchiveFromRequestBodyAndMakeItRetrievable() throws Exception {
        // given — an NDJSON archive body with two recorded exchanges
        String forwarded = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/forwarded").withMethod("GET").withHeader("host", "example.com"))
            .withHttpResponse(response().withStatusCode(200).withReasonPhrase("OK").withBody("forwarded-body")));
        String mocked = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/mocked").withMethod("GET").withHeader("host", "example.com"))
            .withHttpResponse(response().withStatusCode(201).withReasonPhrase("Created").withBody("mocked-body")));
        String archive = forwarded + "\n" + mocked + "\n";

        // when — re-import via the control-plane endpoint (redaction disabled to preserve values)
        FakeResponseWriter importWriter = new FakeResponseWriter();
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "recording")
            .withQueryStringParameter("redactSensitiveData", "false")
            .withBody(archive);
        boolean handled = httpState.handle(importRequest, importWriter, false);
        importWriter.await();

        // then — created, and the returned body echoes both pairs
        assertThat(handled, is(true));
        assertThat(importWriter.response.getStatusCode(), is(201));
        assertThat(importWriter.response.getBodyAsString(), containsString("/api/forwarded"));
        assertThat(importWriter.response.getBodyAsString(), containsString("/api/mocked"));

        // and — both pairs are now retrievable like in-memory recordings
        FakeResponseWriter retrieveWriter = new FakeResponseWriter();
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", REQUEST_RESPONSES.name())
            .withQueryStringParameter("format", "JSON")
            .withBody("");
        httpState.handle(retrieveRequest, retrieveWriter, false);
        retrieveWriter.await();

        String retrieved = retrieveWriter.response.getBodyAsString();
        assertThat(retrieved, containsString("/api/forwarded"));
        assertThat(retrieved, containsString("forwarded-body"));
        assertThat(retrieved, containsString("/api/mocked"));
        assertThat(retrieved, containsString("mocked-body"));
    }

    @Test
    public void shouldReimportArchiveFromDiskWhenSourceIsDisk() throws Exception {
        // given — a persisted NDJSON archive on disk and configuration pointing at it
        File archiveFile = File.createTempFile("reimportFromDisk", ".ndjson");
        archiveFile.deleteOnExit();
        String line = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/on-disk").withMethod("GET").withHeader("host", "example.com"))
            .withHttpResponse(response().withStatusCode(200).withBody("disk-body")));
        Files.write(archiveFile.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8));
        configuration.persistedRecordedRequestsPath(archiveFile.getAbsolutePath());

        // when — re-import with ?source=disk and NO body
        FakeResponseWriter importWriter = new FakeResponseWriter();
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "recording")
            .withQueryStringParameter("source", "disk")
            .withQueryStringParameter("redactSensitiveData", "false");
        httpState.handle(importRequest, importWriter, false);
        importWriter.await();

        // then — created and retrievable
        assertThat(importWriter.response.getStatusCode(), is(201));
        FakeResponseWriter retrieveWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", REQUEST_RESPONSES.name())
            .withQueryStringParameter("format", "JSON")
            .withBody(""), retrieveWriter, false);
        retrieveWriter.await();
        assertThat(retrieveWriter.response.getBodyAsString(), containsString("/api/on-disk"));
        assertThat(retrieveWriter.response.getBodyAsString(), containsString("disk-body"));
    }

    @Test
    public void shouldRedactSensitiveDataOnReimportByDefault() throws Exception {
        // given — an archive line carrying a sensitive Authorization header
        String line = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/secured").withMethod("GET")
                .withHeader("host", "example.com")
                .withHeader("Authorization", "Bearer TOPSECRET"))
            .withHttpResponse(response().withStatusCode(200).withBody("ok")));

        // when — re-import WITHOUT disabling redaction (default on)
        FakeResponseWriter importWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "recording")
            .withBody(line), importWriter, false);
        importWriter.await();

        // then — the returned/retrievable copy has the secret masked
        assertThat(importWriter.response.getStatusCode(), is(201));
        assertThat(importWriter.response.getBodyAsString(), not(containsString("TOPSECRET")));

        FakeResponseWriter retrieveWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", REQUEST_RESPONSES.name())
            .withQueryStringParameter("format", "JSON")
            .withBody(""), retrieveWriter, false);
        retrieveWriter.await();
        assertThat(retrieveWriter.response.getBodyAsString(), not(containsString("TOPSECRET")));
    }

    @Test
    public void shouldReturnBadRequestWhenDiskArchiveMissing() throws Exception {
        // given — configuration points at a non-existent archive
        configuration.persistedRecordedRequestsPath("target/does-not-exist-" + System.nanoTime() + ".ndjson");

        // when — re-import from disk with no body
        FakeResponseWriter importWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "recording")
            .withQueryStringParameter("source", "disk"), importWriter, false);
        importWriter.await();

        // then — a clear 400 rather than a 500 / silent success
        assertThat(importWriter.response.getStatusCode(), is(400));
        assertThat(importWriter.response.getBodyAsString(), containsString("no persisted recorded requests archive"));
    }

    @Test
    public void shouldReturnEmptyForEmptyDiskArchive() throws Exception {
        // given — an archive file that exists but is empty (persistence enabled but nothing recorded yet)
        File archiveFile = File.createTempFile("emptyArchive", ".ndjson");
        archiveFile.deleteOnExit();
        Files.write(archiveFile.toPath(), new byte[0]);
        configuration.persistedRecordedRequestsPath(archiveFile.getAbsolutePath());

        // when — re-import from disk
        FakeResponseWriter importWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "recording")
            .withQueryStringParameter("source", "disk"), importWriter, false);
        importWriter.await();

        // then — 0 imported (201 with an empty array), NOT a 400 body-required error
        assertThat(importWriter.response.getStatusCode(), is(201));
        assertThat(importWriter.response.getBodyAsString().replaceAll("\\s", ""), is("[]"));
    }

    @Test
    public void shouldRecoverIntactExchangesFromTruncatedDiskArchive() throws Exception {
        // given — a durable archive with two intact lines then a crash-truncated final line
        File archiveFile = File.createTempFile("truncatedArchive", ".ndjson");
        archiveFile.deleteOnExit();
        String first = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/recovered1").withMethod("GET").withHeader("host", "example.com"))
            .withHttpResponse(response().withStatusCode(200).withBody("body1")));
        String second = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/recovered2").withMethod("GET").withHeader("host", "example.com"))
            .withHttpResponse(response().withStatusCode(200).withBody("body2")));
        String truncated = second.substring(0, second.length() / 2);
        Files.write(archiveFile.toPath(), (first + "\n" + second + "\n" + truncated).getBytes(StandardCharsets.UTF_8));
        configuration.persistedRecordedRequestsPath(archiveFile.getAbsolutePath());

        // when — recover via source=disk
        FakeResponseWriter importWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "recording")
            .withQueryStringParameter("source", "disk")
            .withQueryStringParameter("redactSensitiveData", "false"), importWriter, false);
        importWriter.await();

        // then — both intact exchanges import (crash-recovery guarantee holds), the skipped line is reported
        assertThat(importWriter.response.getStatusCode(), is(201));
        assertThat(importWriter.response.getBodyAsString(), containsString("/api/recovered1"));
        assertThat(importWriter.response.getBodyAsString(), containsString("/api/recovered2"));
        assertThat(importWriter.response.getFirstHeader("x-mockserver-recorded-requests-skipped"), is("1"));

        // and — both are retrievable like in-memory recordings
        FakeResponseWriter retrieveWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", REQUEST_RESPONSES.name())
            .withQueryStringParameter("format", "JSON")
            .withBody(""), retrieveWriter, false);
        retrieveWriter.await();
        assertThat(retrieveWriter.response.getBodyAsString(), containsString("/api/recovered1"));
        assertThat(retrieveWriter.response.getBodyAsString(), containsString("/api/recovered2"));
    }
}
