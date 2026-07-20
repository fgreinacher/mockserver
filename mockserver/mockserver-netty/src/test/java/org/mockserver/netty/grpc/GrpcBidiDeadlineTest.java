package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcTimeout;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcStreamMessage;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code grpc-timeout} enforcement on the HTTP/2 <strong>bidi</strong> path.
 * <p>
 * This coverage did not exist, which is why an inverted ordering survived: the deadline was armed
 * <em>before</em> the initial HEADERS, and those HEADERS are themselves deferred by the action
 * delay. With a {@code grpc-timeout} shorter than that delay the deadline emitted a bare trailing
 * HEADERS frame with no {@code :status} as the FIRST frame on the stream — an invalid Trailers-Only
 * response — and the delayed initial HEADERS then wrote {@code :status 200} onto an already-ended
 * stream, followed by DATA frames.
 * <p>
 * The changelog and consumer docs both claim bidi mid-stream enforcement, so this is the test that
 * makes that claim true rather than asserted.
 */
public class GrpcBidiDeadlineTest {

    private static final String DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    private GrpcProtoDescriptorStore store;
    private GrpcJsonMessageConverter converter;
    private Descriptors.MethodDescriptor chatMethod;

    @Before
    public void setUp() {
        store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get(DESCRIPTOR));
        converter = store.getConverter();
        chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");
        assertThat("Chat method must exist", chatMethod, is(notNullValue()));
    }

    private static DefaultHttp2HeadersFrame requestHeaders(String grpcTimeout) {
        DefaultHttp2Headers headers = new DefaultHttp2Headers();
        headers.method("POST");
        headers.path("/com.example.grpc.GreetingService/Chat");
        headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        if (grpcTimeout != null) {
            headers.set(GrpcTimeout.GRPC_TIMEOUT_HEADER, grpcTimeout);
        }
        return new DefaultHttp2HeadersFrame(headers, false);
    }

    /**
     * The deadline fires while the initial HEADERS are still deferred by the action delay. The
     * stream must be terminated with a single, VALID Trailers-Only response, and nothing may follow.
     */
    @Test
    public void shouldTerminateWithAValidTrailersOnlyResponseWhenTheDeadlineBeatsTheInitialHeaders() {
        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withDelay(new Delay(TimeUnit.SECONDS, 5))
            .withMessage(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Welcome\"}"))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(
            new FrameCaptureHandler(outbound),
            new GrpcBidiStreamHandler(chatMethod, converter, config, (Runnable) null));

        channel.writeInbound(requestHeaders("100m"));
        assertThat("the initial HEADERS are deferred by the action delay, so nothing yet",
            outbound.isEmpty(), is(true));

        channel.advanceTimeBy(300, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertThat("the deadline must terminate the stream with exactly one frame",
            outbound.size(), is(1));
        assertThat(outbound.get(0), instanceOf(Http2HeadersFrame.class));
        Http2HeadersFrame terminal = (Http2HeadersFrame) outbound.get(0);
        assertThat("the first frame on a stream MUST carry :status - a bare trailing HEADERS frame"
                + " here is an invalid response",
            terminal.headers().status(), is(notNullValue()));
        assertThat(terminal.headers().status().toString(), is("200"));
        assertThat(terminal.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode())));
        assertThat("it must end the stream", terminal.isEndStream(), is(true));

        // the deferred initial HEADERS must NOT then be written onto the ended stream
        channel.advanceTimeBy(10, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertThat("nothing may follow the terminal frame - the deferred initial HEADERS and its"
                + " eager DATA must be suppressed",
            outbound.size(), is(1));
    }

    /**
     * The deadline fires after the stream has started. The terminal frame must then be a plain
     * trailing HEADERS frame (no second {@code :status}), and no message may follow it.
     */
    @Test
    public void shouldTerminateMidStreamWithTrailingHeadersOnceStarted() {
        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withMessage(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Welcome\"}"))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(
            new FrameCaptureHandler(outbound),
            new GrpcBidiStreamHandler(chatMethod, converter, config, (Runnable) null));

        channel.writeInbound(requestHeaders("100m"));
        assertThat("the stream starts immediately when no action delay is configured",
            outbound.size(), is(2));
        assertThat(((Http2HeadersFrame) outbound.get(0)).headers().status().toString(), is("200"));
        assertThat(outbound.get(1), instanceOf(Http2DataFrame.class));

        channel.advanceTimeBy(300, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertThat("the deadline must add exactly one terminal frame", outbound.size(), is(3));
        Http2HeadersFrame terminal = (Http2HeadersFrame) outbound.get(2);
        assertThat("the initial HEADERS already carried :status, so this must not carry another",
            terminal.headers().status(), is(nullValue()));
        assertThat(terminal.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode())));
        assertThat(terminal.isEndStream(), is(true));
    }

    /**
     * With no {@code grpc-timeout} the bidi stream is unaffected: no timer is armed and the action
     * delay still governs when the initial HEADERS are written.
     */
    @Test
    public void shouldNotArmADeadlineWhenTheClientSentNoTimeout() {
        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withDelay(new Delay(TimeUnit.MILLISECONDS, 100))
            .withMessage(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Welcome\"}"))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(
            new FrameCaptureHandler(outbound),
            new GrpcBidiStreamHandler(chatMethod, converter, config, (Runnable) null));

        channel.writeInbound(requestHeaders(null));
        channel.advanceTimeBy(500, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertThat("initial HEADERS plus the eager message must both be delivered",
            outbound.size(), is(2));
        Http2HeadersFrame initial = (Http2HeadersFrame) outbound.get(0);
        assertThat(initial.headers().status().toString(), is("200"));
        assertThat("no DEADLINE_EXCEEDED may be emitted",
            initial.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER), is(nullValue()));
    }

    private static class FrameCaptureHandler extends ChannelOutboundHandlerAdapter {
        private final List<Object> captured;

        FrameCaptureHandler(List<Object> captured) {
            this.captured = captured;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof Http2StreamFrame) {
                captured.add(msg);
            }
            promise.setSuccess();
        }
    }
}
