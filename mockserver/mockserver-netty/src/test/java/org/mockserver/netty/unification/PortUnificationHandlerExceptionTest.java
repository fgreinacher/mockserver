package org.mockserver.netty.unification;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * Verifies that {@link PortUnificationHandler#exceptionCaught} no longer silently drops a plain
 * {@link DecoderException}. SSLException-family faults are handled by the sslHandshakeException
 * branch; a plain decoder fault must now hit the isSslOrDecoderFault WARN branch.
 */
public class PortUnificationHandlerExceptionTest {

    @Test
    public void shouldLogWarnOnPlainDecoderFault() {
        // given - a handler whose logger is a mock so the log level can be asserted
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(logger);
        PortUnificationHandler handler = new PortUnificationHandler(
            mock(Configuration.class),
            mock(LifeCycle.class),
            httpState,
            mock(HttpActionHandler.class),
            mock(NettySslContextFactory.class),
            null
        );
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // when - a plain decoder fault (no SSLException cause) is caught
        channel.pipeline().fireExceptionCaught(new DecoderException("could not decode frame"));
        channel.runPendingTasks();

        // then - it is surfaced at WARN rather than silently dropped
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));

        channel.finishAndReleaseAll();
    }
}
