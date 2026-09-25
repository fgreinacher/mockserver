package org.mockserver.netty.proxy;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link BinaryRequestProxyingHandler#exceptionCaught} routes exceptions to the
 * correct log level: ERROR for unexpected exceptions, WARN for genuine SSL/decoder faults (so they
 * are not silently dropped), and silent for benign connection closes — and always closes the channel.
 */
public class BinaryRequestProxyingHandlerExceptionTest {

    private BinaryRequestProxyingHandler handler(MockServerLogger logger) {
        Configuration configuration = mock(Configuration.class);
        // constructor reads configuration.binaryProxyListener(); a null return is acceptable here
        return new BinaryRequestProxyingHandler(
            configuration,
            logger,
            mock(Scheduler.class),
            mock(NettyHttpClient.class),
            mock(HttpState.class)
        );
    }

    @Test
    public void shouldLogWarnAndCloseOnDecoderFault() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler(logger));

        // when
        channel.pipeline().fireExceptionCaught(new DecoderException("bad frame"));
        channel.runPendingTasks();

        // then
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));
        assertFalse("channel should be closed on decoder fault", channel.isActive());

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogWarnAndCloseOnSslFault() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler(logger));

        // when
        channel.pipeline().fireExceptionCaught(new RuntimeException("relay failed", new SSLException("handshake failed")));
        channel.runPendingTasks();

        // then
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));
        assertFalse("channel should be closed on SSL fault", channel.isActive());

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldStaySilentOnBenignConnectionClose() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler(logger));

        // when
        channel.pipeline().fireExceptionCaught(new RuntimeException("Connection reset by peer"));
        channel.runPendingTasks();

        // then
        verify(logger, never()).logEvent(any(LogEntry.class));
        assertFalse("channel should be closed on benign close", channel.isActive());

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogErrorAndCloseOnUnexpectedException() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler(logger));

        // when
        channel.pipeline().fireExceptionCaught(new IllegalStateException("unexpected"));
        channel.runPendingTasks();

        // then
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.ERROR));
        assertFalse("channel should be closed on unexpected exception", channel.isActive());

        channel.finishAndReleaseAll();
    }
}
