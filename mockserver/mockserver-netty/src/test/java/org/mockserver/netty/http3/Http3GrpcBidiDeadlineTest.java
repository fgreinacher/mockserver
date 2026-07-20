package org.mockserver.netty.http3;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.TypeRegistry;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.After;
import org.junit.Test;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcTimeout;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.HttpRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code grpc-timeout} enforcement on the HTTP/3 <strong>bidi</strong> path — the sibling of
 * {@link org.mockserver.netty.grpc.GrpcBidiDeadlineTest}.
 * <p>
 * Neither bidi transport had any deadline coverage, while the changelog and consumer docs claimed
 * mid-stream enforcement on both. This pins the HTTP/3 half.
 */
public class Http3GrpcBidiDeadlineTest {

    private final MockServerLogger logger = new MockServerLogger();
    private EmbeddedChannel streamChannel;

    @After
    public void tearDown() {
        if (streamChannel != null) {
            streamChannel.finishAndReleaseAll();
        }
    }

    /**
     * The deadline must terminate an in-progress bidi stream with a DEADLINE_EXCEEDED trailing
     * HEADERS frame, and nothing may follow it.
     */
    @Test
    public void shouldTerminateBidiStreamWhenTheDeadlineElapses() throws Exception {
        Descriptors.MethodDescriptor method = bidiMethodDescriptor();
        GrpcJsonMessageConverter converter = new GrpcJsonMessageConverter(TypeRegistry.newBuilder()
            .add(method.getInputType()).add(method.getOutputType()).build());

        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withMessage(GrpcStreamMessage.grpcStreamMessage("{\"name\": \"Welcome\"}"))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        ChannelHandlerContext ctx = mockStreamCtx(outbound);

        Http3GrpcBidiStreamHandler handler = new Http3GrpcBidiStreamHandler(
            ctx, method, converter, config, () -> { }, logger);

        handler.start(HttpRequest.request()
            .withHeader(GrpcTimeout.GRPC_TIMEOUT_HEADER, "100m"));

        int framesBeforeDeadline = outbound.size();
        assertThat("the stream starts immediately: initial HEADERS plus the eager message",
            framesBeforeDeadline >= 2, is(true));

        streamChannel.advanceTimeBy(300, TimeUnit.MILLISECONDS);
        streamChannel.runScheduledPendingTasks();

        assertThat("the deadline must add exactly one terminal frame",
            outbound.size(), is(framesBeforeDeadline + 1));
        Object terminal = outbound.get(outbound.size() - 1);
        assertThat(terminal instanceof Http3HeadersFrame, is(true));
        Http3HeadersFrame terminalHeaders = (Http3HeadersFrame) terminal;
        assertThat(terminalHeaders.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER), is(notNullValue()));
        assertThat(terminalHeaders.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode())));

        // a later inbound message must not produce any further output
        handler.onInputClosed();
        assertThat("nothing may follow the terminal trailer",
            outbound.size(), is(framesBeforeDeadline + 1));
    }

    /**
     * With no {@code grpc-timeout} no timer is armed and the stream completes normally.
     */
    @Test
    public void shouldNotArmADeadlineWhenTheClientSentNoTimeout() throws Exception {
        Descriptors.MethodDescriptor method = bidiMethodDescriptor();
        GrpcJsonMessageConverter converter = new GrpcJsonMessageConverter(TypeRegistry.newBuilder()
            .add(method.getInputType()).add(method.getOutputType()).build());

        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withMessage(GrpcStreamMessage.grpcStreamMessage("{\"name\": \"Welcome\"}"))
            .withStatusName("OK");

        List<Object> outbound = new ArrayList<>();
        ChannelHandlerContext ctx = mockStreamCtx(outbound);

        Http3GrpcBidiStreamHandler handler = new Http3GrpcBidiStreamHandler(
            ctx, method, converter, config, () -> { }, logger);

        handler.start(HttpRequest.request());
        int framesAfterStart = outbound.size();

        streamChannel.advanceTimeBy(10, TimeUnit.SECONDS);
        streamChannel.runScheduledPendingTasks();

        assertThat("no deadline may fire", outbound.size(), is(framesAfterStart));
    }

    private ChannelHandlerContext mockStreamCtx(List<Object> outbound) {
        // a REAL channel so the deadline can be scheduled on its event loop and driven by the test
        streamChannel = new EmbeddedChannel();
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(streamChannel);
        when(ctx.executor()).thenReturn(ImmediateEventExecutor.INSTANCE);
        ChannelFuture future = mock(ChannelFuture.class);
        when(future.addListener(any())).thenReturn(future);
        doAnswer(invocation -> {
            capture(outbound, invocation.getArgument(0));
            return future;
        }).when(ctx).write(any());
        doAnswer(invocation -> {
            capture(outbound, invocation.getArgument(0));
            return future;
        }).when(ctx).writeAndFlush(any());
        return ctx;
    }

    private static void capture(List<Object> outbound, Object written) {
        if (written instanceof Http3DataFrame) {
            ((Http3DataFrame) written).retain();
        }
        outbound.add(written);
    }

    /**
     * Builds a minimal bidi method descriptor: {@code Msg{ string name = 1 }} in both directions.
     */
    private static Descriptors.MethodDescriptor bidiMethodDescriptor() throws Exception {
        DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("bidi_deadline.proto")
            .setPackage("bidideadline")
            .setSyntax("proto3")
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Msg")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                    .setName("name").setNumber(1)
                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                    .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)))
            .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("BidiService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName("Chat")
                    .setInputType(".bidideadline.Msg")
                    .setOutputType(".bidideadline.Msg")
                    .setClientStreaming(true)
                    .setServerStreaming(true)))
            .build();
        Descriptors.FileDescriptor fileDescriptor =
            Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0]);
        return fileDescriptor.findServiceByName("BidiService").findMethodByName("Chat");
    }
}
