package org.mockserver.netty.http3;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.TypeRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.Test;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.HttpTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WS2.6 (HTTP/3): mirrors the HTTP/2
 * {@link org.mockserver.netty.grpc.GrpcBidiInterleavingMultiplexTest#shouldRenderTemplatedResponseAgainstInboundMessage}
 * end-to-end templating assertion for the QUIC bidi path. The handler is driven directly with a
 * mocked QUIC-stream {@link ChannelHandlerContext} whose executor runs inline
 * ({@link ImmediateEventExecutor}) and whose writes are captured, and a real
 * {@link GrpcJsonMessageConverter} built from an inline proto with a string field so a templated
 * response can echo the inbound message.
 */
public class Http3GrpcBidiTemplatingTest {

    private final MockServerLogger logger = new MockServerLogger();

    @Test
    public void shouldRenderTemplatedResponseAgainstInboundMessageOverHttp3() throws Exception {
        // given: a real converter for a Msg{ string name = 1 } bidi method
        Descriptors.MethodDescriptor method = bidiMethodDescriptor();
        TypeRegistry typeRegistry = TypeRegistry.newBuilder()
            .add(method.getInputType())
            .add(method.getOutputType())
            .build();
        GrpcJsonMessageConverter converter = new GrpcJsonMessageConverter(typeRegistry);

        // a rule whose response templates the inbound name plus a static sibling response
        GrpcBidiResponse config = GrpcBidiResponse.grpcBidiResponse()
            .withRule(GrpcBidiRule.grpcBidiRule(".*")
                .withResponse(GrpcStreamMessage
                    .grpcStreamMessage("{\"name\": \"Hi $jsonPath.find(\"$.name\")\"}")
                    .withTemplateType(HttpTemplate.TemplateType.VELOCITY))
                .withResponse("{\"name\": \"static reply\"}"))
            .withStatusName("OK");

        List<Http3DataFrame> outboundData = new ArrayList<>();
        ChannelHandlerContext ctx = mockStreamCtx(outboundData);

        Http3GrpcBidiStreamHandler handler = new Http3GrpcBidiStreamHandler(
            ctx, method, converter, config, () -> { }, logger);

        // when: HEADERS (start), then a single inbound message, then the client half-closes
        handler.start(org.mockserver.model.HttpRequest.request());
        byte[] proto = converter.toProtobuf("{\"name\":\"Alice\"}", method.getInputType());
        handler.onData(GrpcFrameCodec.encode(proto));
        handler.onInputClosed();

        // then: two DATA frames — the templated reply echoing the inbound name, then the static reply
        assertThat("should emit templated + static DATA frames", outboundData.size(), is(2));

        String templatedJson = decodeDataFrame(outboundData.get(0), converter, method);
        assertThat("templated response should echo the inbound name", templatedJson, containsString("Hi Alice"));

        String staticJson = decodeDataFrame(outboundData.get(1), converter, method);
        assertThat("static response should be emitted unchanged", staticJson, containsString("static reply"));

        for (Http3DataFrame frame : outboundData) {
            frame.release();
        }
    }

    private static String decodeDataFrame(Http3DataFrame frame, GrpcJsonMessageConverter converter, Descriptors.MethodDescriptor method) {
        ByteBuf content = frame.content();
        byte[] bytes = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), bytes);
        return converter.toJson(GrpcFrameCodec.decode(bytes).get(0), method.getOutputType());
    }

    private ChannelHandlerContext mockStreamCtx(List<Http3DataFrame> outboundData) {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.executor()).thenReturn(ImmediateEventExecutor.INSTANCE);
        ChannelFuture future = mock(ChannelFuture.class);
        when(future.addListener(any())).thenReturn(future);
        when(ctx.write(any())).thenReturn(future);
        doAnswer(invocation -> {
            Object written = invocation.getArgument(0);
            if (written instanceof Http3DataFrame) {
                // retain so the captured frame survives until the test decodes it
                outboundData.add((Http3DataFrame) ((Http3DataFrame) written).retain());
            }
            return future;
        }).when(ctx).writeAndFlush(any());
        return ctx;
    }

    /**
     * Builds a real bidi gRPC {@link Descriptors.MethodDescriptor} for {@code Msg{ string name = 1 }}
     * so the converter can round-trip JSON with a templatable field.
     */
    private static Descriptors.MethodDescriptor bidiMethodDescriptor() throws Exception {
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("h3biditemplate.proto")
            .setSyntax("proto3")
            .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Msg")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                    .setName("name")
                    .setNumber(1)
                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                    .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)))
            .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("BidiService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName("Chat")
                    .setInputType(".Msg")
                    .setOutputType(".Msg")
                    .setClientStreaming(true)
                    .setServerStreaming(true)))
            .build();
        Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(
            fileProto, new Descriptors.FileDescriptor[]{});
        return fd.findServiceByName("BidiService").findMethodByName("Chat");
    }
}
