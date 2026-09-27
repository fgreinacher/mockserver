package org.mockserver.responsewriter;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.slf4j.event.Level;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The Content-Length-vs-body-length truncation diagnostic is INFO-only. These tests pin that the
 * header scan and body materialisation behind it are gated on the log level FIRST, so nothing runs
 * when INFO is disabled, while the warning still fires when INFO is enabled and the header
 * under-states the body.
 */
public class ResponseWriterContentLengthDiagnosticTest {

    private static final class TestResponseWriter extends ResponseWriter {
        private TestResponseWriter(MockServerLogger logger) {
            super(Configuration.configuration(), logger);
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
        }
    }

    @Test
    public void doesNotScanBodyWhenInfoDisabled() {
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(Level.INFO)).thenReturn(false);
        HttpResponse response = spy(response("some body").withHeader("content-length", "2"));

        new TestResponseWriter(logger).writeResponse(request("/x"), response, false);

        verify(response, never()).getBodyAsRawBytes();
    }

    @Test
    public void logsTruncationWarningWhenInfoEnabledAndContentLengthTooSmall() {
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(Level.INFO)).thenReturn(true);
        HttpResponse response = response("some body").withHeader("content-length", "2");

        new TestResponseWriter(logger).writeResponse(request("/x"), response, false);

        verify(logger, times(1)).logEvent(any(LogEntry.class));
    }

    @Test
    public void doesNotLogWhenContentLengthMatchesBody() {
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(Level.INFO)).thenReturn(true);
        HttpResponse response = response("some body").withHeader("content-length", "9");

        new TestResponseWriter(logger).writeResponse(request("/x"), response, false);

        verify(logger, never()).logEvent(any(LogEntry.class));
    }
}
