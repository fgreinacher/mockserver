package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.LogEntrySerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A relayed response whose write back to the client fails (broken pipe, connection reset) is logged at ERROR; its
 * Netty text lists every header, so the credentials must be masked when redactSecretsInLog is on.
 */
public class DownstreamProxyRelayHandlerWriteFailureTest {

    private static final String SESSION = "SESSION-COOKIE-SECRET-123";
    private static final String BEARER = "BEARER-TOKEN-SECRET-456";

    private static List<LogEntry> relayWithFailingWrite(HttpObject message) {
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        MockServerLogger logger = new MockServerLogger(DownstreamProxyRelayHandlerWriteFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
        EmbeddedChannel upstream = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IOException("Broken pipe"));
            }
        });
        EmbeddedChannel downstream = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, upstream));
        downstream.writeInbound(message);
        upstream.runPendingTasks();
        downstream.runPendingTasks();
        assertThat("the failed upstream channel is closed", upstream.isOpen(), is(false));
        downstream.finishAndReleaseAll();
        return logged;
    }

    @Test
    public void shouldMaskTheRelayedResponseHeadersWhenTheWriteFails() {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));
        response.headers().add("Set-Cookie", "session=" + SESSION + "; Path=/").add("Authorization", "Bearer " + BEARER);

        List<LogEntry> logged = relayWithFailingWrite(response);

        assertThat(response.refCnt(), is(0));
        assertThat(logged, hasSize(1));
        LogEntry entry = logged.get(0);
        for (String output : new String[]{
            entry.getMessage(configuration().redactSecretsInLog(true)),
            entry.getCompactMessage(configuration().redactSecretsInLog(true)),
            new LogEntrySerializer(new MockServerLogger(), configuration().redactSecretsInLog(true)).serialize(entry)
        }) {
            assertThat(output, containsString("exception while returning writing"));
            assertThat(output, not(containsString(SESSION)));
            assertThat(output, not(containsString(BEARER)));
        }
        assertThat("unchanged with redaction off", entry.getMessage(configuration().redactSecretsInLog(false)), containsString(BEARER));
    }

    @Test
    public void shouldMaskARelayedBodyChunkWhole() {
        DefaultHttpContent chunk = new DefaultHttpContent(Unpooled.copiedBuffer("chunk", StandardCharsets.UTF_8));

        List<LogEntry> logged = relayWithFailingWrite(chunk);

        assertThat(chunk.refCnt(), is(0));
        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getMessage(configuration().redactSecretsInLog(true)), containsString("***REDACTED***"));
        assertThat(logged.get(0).getMessage(configuration().redactSecretsInLog(false)), containsString("DefaultHttpContent"));
    }
}
