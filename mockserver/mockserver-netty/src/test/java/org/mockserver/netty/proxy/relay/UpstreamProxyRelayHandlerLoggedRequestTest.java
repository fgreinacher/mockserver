package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.codec.StreamingAwareHttpObjectAggregator;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockserver.configuration.Configuration.configuration;

public class UpstreamProxyRelayHandlerLoggedRequestTest {

    private static final Configuration REDACTING = configuration().redactSecretsInLog(true);

    private static FullHttpRequest relayedRequest(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));
    }

    @Test
    public void shouldLogTheRelayedQueryStringAsParametersSoItsCredentialsCanBeMasked() {
        FullHttpRequest relayed = relayedRequest("/v1/models?key=QUERY-SECRET-5&alt=json");
        try {
            HttpRequest loggedRequest = UpstreamProxyRelayHandler.loggedRequest(relayed);

            assertThat(loggedRequest.getMethod().getValue(), is("POST"));
            assertThat(loggedRequest.getPath().getValue(), is("/v1/models"));
            assertThat(loggedRequest.getFirstQueryStringParameter("key"), is("QUERY-SECRET-5"));
            LogEntry entry = new LogEntry()
                .setLogLevel(Level.ERROR)
                .setHttpRequest(loggedRequest)
                .setMessageFormat("exception while returning response for request:{}")
                .setArguments(loggedRequest);
            assertThat(entry.getMessage(REDACTING), not(containsString("QUERY-SECRET-5")));
            assertThat(entry.getMessage(REDACTING), containsString("/v1/models"));
        } finally {
            relayed.release();
        }
    }

    @Test
    public void shouldLeaveOutAnUndecodableQueryStringRatherThanThrow() {
        for (String uri : new String[]{"/v1/models?key=QUERY-SECRET-5&x=50%", "/v1/models?key=QUERY-SECRET-5&alt=%zz"}) {
            FullHttpRequest relayed = relayedRequest(uri);
            try {
                HttpRequest loggedRequest = UpstreamProxyRelayHandler.loggedRequest(relayed);

                assertThat(uri, loggedRequest.getPath().getValue(), is(uri.substring(0, uri.indexOf('?'))));
                assertThat(uri, loggedRequest.toString(), not(containsString("QUERY-SECRET-5")));
            } finally {
                relayed.release();
            }
        }
        // a malformed path is kept raw; the query string, still decodable, stays a maskable parameter
        FullHttpRequest malformedPath = relayedRequest("/files/100%?key=QUERY-SECRET-5");
        try {
            HttpRequest loggedRequest = UpstreamProxyRelayHandler.loggedRequest(malformedPath);
            assertThat(loggedRequest.getPath().getValue(), is("/files/100%"));
            assertThat(loggedRequest.getFirstQueryStringParameter("key"), is("QUERY-SECRET-5"));
        } finally {
            malformedPath.release();
        }
    }

    @Test
    public void shouldRelayAndReleaseRequestsWithMalformedEscapes() {
        for (String uri : new String[]{"/files/100%?key=QUERY-SECRET-5", "/a%zz?key=QUERY-SECRET-5&x=%zz", "/ok?key=QUERY-SECRET-5&x=50%"}) {
            EmbeddedChannel downstream = new EmbeddedChannel();
            EmbeddedChannel handler = new EmbeddedChannel(new UpstreamProxyRelayHandler(new MockServerLogger(), new EmbeddedChannel(), downstream, null, 0));
            FullHttpRequest request = relayedRequest(uri);

            handler.writeInbound(request);
            handler.checkException();

            Object relayed = downstream.readOutbound();
            assertThat(uri + " was relayed", relayed, sameInstance(request));
            assertThat(uri, handler.isOpen(), is(true));
            String requestLine = downstream.attr(StreamingAwareHttpObjectAggregator.REQUEST_LINE).get();
            assertThat(uri, requestLine, is("POST " + uri.substring(0, uri.indexOf('?'))));
            ReferenceCountUtil.release(relayed);
            assertThat(uri + " released", request.refCnt(), is(0));
            handler.finishAndReleaseAll();
            downstream.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldLogAWriteFailureWithoutTheQueryCredentialAndClose() {
        MockServerLogger logger = mock(MockServerLogger.class);
        EmbeddedChannel downstream = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IOException("write failed"));
            }
        });
        EmbeddedChannel handler = new EmbeddedChannel(new UpstreamProxyRelayHandler(logger, new EmbeddedChannel(), downstream, null, 0));
        FullHttpRequest request = relayedRequest("/v1/models?key=QUERY-SECRET-5&x=50%");

        handler.writeInbound(request);
        handler.runPendingTasks();
        downstream.runPendingTasks();

        ArgumentCaptor<LogEntry> logged = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(logged.capture());
        assertThat(logged.getValue().getMessage(REDACTING), not(containsString("QUERY-SECRET-5")));
        assertThat(logged.getValue().getMessage(configuration().redactSecretsInLog(false)), not(containsString("QUERY-SECRET-5")));
        assertThat("the failed downstream channel is closed", downstream.isOpen(), is(false));
        assertThat(request.refCnt(), is(0));
        handler.finishAndReleaseAll();
    }
}
