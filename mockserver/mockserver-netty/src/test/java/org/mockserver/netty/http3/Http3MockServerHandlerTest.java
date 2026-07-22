package org.mockserver.netty.http3;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcDerivedHeaders;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http3.Http3HeadersFrame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Unit tests for {@link Http3MockServerHandler}, specifically testing ByteBuf
 * lifecycle management (leak prevention, no double-release).
 * <p>
 * These tests do NOT require the native QUIC transport -- they exercise the
 * handler's buffer management by directly invoking the protected methods
 * (accessible from the same package).
 */
public class Http3MockServerHandlerTest {

    private static final Configuration CONFIGURATION = configuration();
    private static final MockServerLogger LOGGER = new MockServerLogger(Http3MockServerHandlerTest.class);

    private static final String BIDI_SERVICE = "com.example.grpc.GreetingService";
    private static final String BIDI_METHOD = "Chat";
    private static final String BIDI_PATH = "/" + BIDI_SERVICE + "/" + BIDI_METHOD;

    @Test
    public void shouldReleaseBodyAccumulatorOnHandlerRemoved() throws Exception {
        // given: a handler that has received headers and a data frame
        Metrics metrics = new Metrics(CONFIGURATION);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        headersFrame.headers().authority("localhost:8443");

        // invoke protected channelRead to initialise the bodyAccumulator
        handler.channelRead(ctx, headersFrame);

        DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer("test-body".getBytes(StandardCharsets.UTF_8))
        );
        handler.channelRead(ctx, dataFrame);

        // capture the bodyAccumulator via reflection to verify its refCnt
        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);
        assertThat("bodyAccumulator should be allocated", accumulator.refCnt(), is(1));

        // when: handlerRemoved fires (simulating abrupt disconnect before channelInputClosed)
        handler.handlerRemoved(ctx);

        // then: the body accumulator should be released
        assertThat("bodyAccumulator should be released after handlerRemoved", accumulator.refCnt(), is(0));

        // and: the field should be nulled out (verify via reflection)
        assertThat("bodyAccumulator field should be null after release", accField.get(handler) == null, is(true));
    }

    @Test
    public void shouldNotDoubleReleaseWhenChannelInputClosedThenHandlerRemoved() throws Exception {
        // given: a handler that processes a complete request via channelInputClosed
        Metrics metrics = new Metrics(CONFIGURATION);
        HttpState httpState = mock(HttpState.class);
        when(httpState.handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(true); // simulate control-plane handling to avoid needing httpActionHandler

        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, httpState, mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");

        handler.channelRead(ctx, headersFrame);

        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);

        // when: channelInputClosed releases the accumulator
        handler.channelInputClosed(ctx);
        assertThat("bodyAccumulator should be released after channelInputClosed", accumulator.refCnt(), is(0));

        // then: handlerRemoved should not throw (no double-release because field was nulled)
        handler.handlerRemoved(ctx); // should be a no-op since bodyAccumulator is already null
    }

    @Test
    public void shouldReleaseBodyAccumulatorWhenNoHeadersReceivedAndChannelInputClosed() throws Exception {
        // given: a handler that receives no headers but has channelInputClosed called
        // (edge case: stream closes immediately)
        Metrics metrics = new Metrics(CONFIGURATION);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        // when: channelInputClosed fires without prior headers
        handler.channelInputClosed(ctx);

        // then: no exception (bodyAccumulator was never allocated, early-return path handles it)
        handler.handlerRemoved(ctx); // also a no-op
    }

    @Test
    public void shouldReleaseBodyAccumulatorOnEarlyReturnWhenParsedHeadersNull() throws Exception {
        // given: a handler where channelRead(HeadersFrame) was called but parseHeaders returned
        // a valid ParsedHeaders -- this test ensures the finally block runs on the channelInputClosed
        // early-return path when parsedHeaders IS null (only bodyAccumulator was allocated)

        // Simulate the scenario where headers frame was received (so bodyAccumulator is allocated)
        // but parsedHeaders is forcibly set to null (simulating a bizarre edge case)
        Metrics metrics = new Metrics(CONFIGURATION);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        handler.channelRead(ctx, headersFrame);

        // Force parsedHeaders to null via reflection to test the early-return + finally release
        java.lang.reflect.Field parsedField = Http3MockServerHandler.class.getDeclaredField("parsedHeaders");
        parsedField.setAccessible(true);
        parsedField.set(handler, null);

        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);
        assertThat("bodyAccumulator should be allocated", accumulator.refCnt(), is(1));

        // when: channelInputClosed fires with null parsedHeaders (early-return path)
        handler.channelInputClosed(ctx);

        // then: the body accumulator should still be released by the finally block
        assertThat("bodyAccumulator should be released on early-return path", accumulator.refCnt(), is(0));
    }

    @Test
    public void shouldRejectRequestBodyExceedingMaxRequestBodySize() throws Exception {
        // given: a handler with maxRequestBodySize set to 100 bytes
        Configuration config = configuration().maxRequestBodySize(100);
        Metrics metrics = new Metrics(config);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/upload");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        // when: send a data frame that exceeds the 100-byte limit
        byte[] oversizedPayload = new byte[150];
        java.util.Arrays.fill(oversizedPayload, (byte) 'X');
        DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer(oversizedPayload)
        );
        handler.channelRead(ctx, dataFrame);

        // then: the body accumulator should be released (null)
        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        assertThat("bodyAccumulator should be null after body exceeded", accField.get(handler) == null, is(true));

        // and: bodyExceeded flag should be set
        java.lang.reflect.Field exceededField = Http3MockServerHandler.class.getDeclaredField("bodyExceeded");
        exceededField.setAccessible(true);
        assertThat("bodyExceeded should be true", (Boolean) exceededField.get(handler), is(true));

        // and: a 413 response headers frame should have been written
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(ctx).write(captor.capture());
        Object written = captor.getValue();
        assertThat("should write Http3HeadersFrame", written instanceof Http3HeadersFrame, is(true));
        Http3HeadersFrame responseHeaders = (Http3HeadersFrame) written;
        assertThat("status should be 413", responseHeaders.headers().status().toString(), is("413"));

        // cleanup: handlerRemoved should be a no-op (accumulator already released)
        handler.handlerRemoved(ctx);
    }

    @Test
    public void shouldNotAccumulateAfterBodyExceeded() throws Exception {
        // given: a handler that has already rejected a body as too large
        Configuration config = configuration().maxRequestBodySize(50);
        Metrics metrics = new Metrics(config);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/upload");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        // send first frame that triggers rejection
        byte[] payload = new byte[60];
        DefaultHttp3DataFrame frame1 = new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(payload));
        handler.channelRead(ctx, frame1);

        // reset mock to count only subsequent interactions
        reset(ctx);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);

        // when: send a second data frame after rejection
        DefaultHttp3DataFrame frame2 = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer("more data".getBytes(StandardCharsets.UTF_8))
        );
        handler.channelRead(ctx, frame2);

        // then: no further writes to ctx (413 was already sent)
        verify(ctx, never()).write(any());
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    public void shouldNotProcessRequestAfterBodyExceeded() throws Exception {
        // given: a handler that has rejected a body as too large
        Configuration config = configuration().maxRequestBodySize(50);
        Metrics metrics = new Metrics(config);
        HttpState httpState = mock(HttpState.class);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, httpState, mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/upload");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        // trigger rejection
        DefaultHttp3DataFrame frame = new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[60]));
        handler.channelRead(ctx, frame);

        // when: channelInputClosed fires (client half-closes)
        handler.channelInputClosed(ctx);

        // then: httpState.handle should NOT have been called (request was already rejected)
        verify(httpState, never()).handle(any(), any(), anyBoolean());
    }

    @Test
    public void shouldAccumulateWithinLimit() throws Exception {
        // given: a handler with maxRequestBodySize set to 200 bytes
        Configuration config = configuration().maxRequestBodySize(200);
        Metrics metrics = new Metrics(config);
        HttpState httpState = mock(HttpState.class);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, httpState, mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/small-upload");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        // when: send data within the limit
        DefaultHttp3DataFrame frame = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer(new byte[100])
        );
        handler.channelRead(ctx, frame);

        // then: bodyExceeded should still be false
        java.lang.reflect.Field exceededField = Http3MockServerHandler.class.getDeclaredField("bodyExceeded");
        exceededField.setAccessible(true);
        assertThat("bodyExceeded should be false", (Boolean) exceededField.get(handler), is(false));

        // and: bodyAccumulator should still be allocated
        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf acc = (CompositeByteBuf) accField.get(handler);
        assertThat("bodyAccumulator should still be allocated", acc != null && acc.refCnt() > 0, is(true));
        assertThat("bodyAccumulator should contain the data", acc.readableBytes(), is(100));

        // cleanup
        handler.channelInputClosed(ctx);
        handler.handlerRemoved(ctx);
    }

    /**
     * {@code x-grpc-service} and {@code x-grpc-method} are <strong>server-derived</strong> from the
     * {@code :path}, so a client must not be able to contribute a value. Because
     * {@code HttpRequest.withHeader} appends rather than replaces, the defence is the
     * {@link org.mockserver.grpc.GrpcDerivedHeaders#strip} call in {@code tryBeginGrpcBidi}, and
     * header matching being SUB_SET means a surviving forged value would let an expectation
     * qualified by {@code x-grpc-service: com.example.evil.OtherService} match a stream that
     * actually belongs to {@code com.example.grpc.GreetingService}.
     * <p>
     * This is the HTTP/3 bidi counterpart of the cases in
     * {@code org.mockserver.netty.grpc.GrpcDerivedHeaderSpoofingTest} and
     * {@code GrpcBidiMetadataMatchingTest#shouldNotLetAClientSpoofTheDerivedServiceHeader} — the one
     * strip site those did not reach.
     * <p>
     * <strong>The assertion is on the value list, never {@code getFirstHeader}.</strong> Without the
     * strip the request carries {@code [com.example.evil.OtherService, com.example.grpc.GreetingService]};
     * a {@code getFirstHeader} assertion could read either value and pass while the spoof is still
     * present and still matchable.
     * <p>
     * The request is observed where {@code tryBeginGrpcBidi} first hands it to the matcher — the
     * peek — because that is precisely the request an expectation is matched against. The peek
     * returning no expectation is irrelevant to this test: the strip has already happened by then.
     */
    @Test
    public void shouldNotLetAClientSpoofTheDerivedGrpcHeadersOnAnHttp3BidiStream() throws Exception {
        // given: bidi streaming enabled and the greeting descriptors loaded (Chat is bidi)
        Configuration config = configuration().grpcBidiStreamingEnabled(true);
        GrpcProtoDescriptorStore descriptorStore = new GrpcProtoDescriptorStore(LOGGER);
        descriptorStore.loadDescriptorSetFromPath(
            Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));

        HttpState httpState = mock(HttpState.class);
        when(httpState.getGrpcDescriptorStore()).thenReturn(descriptorStore);

        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, httpState, mock(HttpActionHandler.class), new Metrics(config)
        );
        ChannelHandlerContext ctx = mockChannelHandlerContext();

        // when: the opening HEADERS frame carries forged copies of the derived headers
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path(BIDI_PATH);
        headersFrame.headers().scheme("https");
        headersFrame.headers().add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        headersFrame.headers().add(GrpcDerivedHeaders.SERVICE, "com.example.evil.OtherService");
        headersFrame.headers().add(GrpcDerivedHeaders.METHOD, "Evil");

        handler.channelRead(ctx, headersFrame);

        // then: the request offered for matching carries ONLY the path-derived values
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpState).peekFirstMatchingExpectation(captor.capture());
        HttpRequest matched = captor.getValue();

        assertThat("x-grpc-service must carry ONLY the path-derived service",
            matched.getHeader(GrpcDerivedHeaders.SERVICE), contains(BIDI_SERVICE));
        assertThat("x-grpc-method must carry ONLY the path-derived method",
            matched.getHeader(GrpcDerivedHeaders.METHOD), contains(BIDI_METHOD));

        handler.handlerRemoved(ctx);
    }

    private ChannelHandlerContext mockChannelHandlerContext() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        return ctx;
    }

    /**
     * Create a mock ChannelHandlerContext that supports write/writeAndFlush
     * (needed for tests that trigger the 413 response path).
     */
    private ChannelHandlerContext mockChannelHandlerContextWithWrite() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        ChannelFuture future = mock(ChannelFuture.class);
        when(future.addListener(any())).thenReturn(future);
        when(ctx.write(any())).thenReturn(future);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        return ctx;
    }
}
