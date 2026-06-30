package org.mockserver.log;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies the three composed OOM-guard behaviours in {@link MockServerEventLog#processLogEntry}:
 * the recorded-request disk hook sees FULL bodies, {@code maxLoggedBodyBytes} truncates the retained
 * in-memory copy (without mutating the shared live objects passed to the disk hook), and the byte
 * budget caps the total retained size.
 */
public class MockServerEventLogCaptureTest {

    private MockServerEventLog synchronousEventLog(Configuration configuration) {
        // synchronous processing (false) so add() runs processLogEntry inline and assertions are deterministic
        return new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), mock(Scheduler.class), false);
    }

    private List<LogEntry> retrieveMessageLogEntries(MockServerEventLog log, RequestDefinition requestDefinition) {
        CompletableFuture<List<LogEntry>> future = new CompletableFuture<>();
        log.retrieveMessageLogEntries(requestDefinition, future::complete);
        try {
            return future.get(60, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    @Test
    public void shouldPersistFullBodyToHookWhileTruncatingInMemoryCopy() {
        // given
        Configuration configuration = configuration().maxLoggedBodyBytes(5);
        MockServerEventLog log = synchronousEventLog(configuration);
        List<HttpRequest> capturedFullRequests = new ArrayList<>();
        log.setRecordedRequestConsumer(entry -> capturedFullRequests.add((HttpRequest) entry.getHttpRequest()));

        HttpRequest liveRequest = request("/capture").withMethod("POST").withBody("0123456789ABCDEF"); // 16 bytes

        // when
        log.add(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(liveRequest)
            .setHttpResponse(response().withStatusCode(200).withBody("a-very-long-response-body"))); // 25 bytes

        // then — the disk hook received the FULL (un-truncated) request body
        assertThat(capturedFullRequests.size(), is(1));
        assertThat(capturedFullRequests.get(0).getBodyAsRawBytes().length, is(16));

        // and the live request object was NOT mutated (no truncation header leaked onto it)
        assertThat(liveRequest.getBodyAsRawBytes().length, is(16));
        assertThat(liveRequest.getFirstHeader("x-mockserver-body-truncated"), is(""));

        // and the in-memory retained copy was truncated to maxLoggedBodyBytes with a marker header
        List<LogEntry> entries = retrieveMessageLogEntries(log, null);
        assertThat(entries.size(), is(1));
        LogEntry retained = entries.get(0);
        HttpRequest retainedRequest = (HttpRequest) retained.getHttpRequest();
        assertThat(retainedRequest.getBodyAsRawBytes().length, is(5));
        assertThat(retainedRequest.getFirstHeader("x-mockserver-body-truncated"), is("16"));
        assertThat(retained.getHttpResponse().getBodyAsRawBytes().length, is(5));
        assertThat(retained.getHttpResponse().getFirstHeader("x-mockserver-body-truncated"), is("25"));
    }

    @Test
    public void shouldCapRetainedEntriesByByteBudget() {
        // given — count bound generous, byte budget small; full bodies retained (no truncation)
        Configuration configuration = configuration()
            .maxLogEntries(1000)
            .maxEventLogSizeInBytes(100L)
            .maxLoggedBodyBytes(0);
        MockServerEventLog log = synchronousEventLog(configuration);

        // when — 10 forwarded exchanges, each carrying a 40-byte response body (well over the 100-byte budget)
        byte[] body = new byte[40];
        for (int i = 0; i < 10; i++) {
            log.add(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/p" + i))
                .setHttpResponse(response().withStatusCode(200).withBody(body)));
        }

        // then — only the most recent entries that fit the budget are retained (100 / 40 => 2)
        assertThat(log.size(), is(2));
        List<LogEntry> entries = retrieveMessageLogEntries(log, null);
        assertThat(entries, notNullValue());
        assertThat(entries.size(), is(2));
        // the retained entries are the newest two (oldest evicted first)
        assertThat(((HttpRequest) entries.get(0).getHttpRequest()).getPath().getValue(), is("/p8"));
        assertThat(((HttpRequest) entries.get(1).getHttpRequest()).getPath().getValue(), is("/p9"));
    }
}
