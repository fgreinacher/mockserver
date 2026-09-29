package org.mockserver.codec;

import com.google.common.collect.ImmutableList;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.model.Header;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class PreserveHeadersNettyRemoves extends MessageToMessageDecoder<HttpObject> {

    private static final AttributeKey<PreservedRequest> PRESERVED_REQUEST = AttributeKey.valueOf("PRESERVED_REQUEST");

    // Not @Sharable, so Netty binds each instance to exactly one channel: per-channel state can live
    // in fields. Both are touched only from decode(), i.e. on that channel's event loop.
    private Attribute<PreservedRequest> preservedRequest;
    // accumulates the original (still compressed) request body before the downstream
    // HttpContentDecompressor decompresses it; published with the headers at end of request
    private ByteArrayOutputStream rawBodyAccumulator;

    @Override
    protected void decode(ChannelHandlerContext ctx, HttpObject httpObject, List<Object> out) throws Exception {
        if (httpObject instanceof HttpMessage) {
            final HttpHeaders headers = ((HttpMessage) httpObject).headers();
            boolean hasContentEncoding = headers.contains(HttpHeaderNames.CONTENT_ENCODING);
            boolean hasTransferEncoding = headers.contains(HttpHeaderNames.TRANSFER_ENCODING);
            final PreservedRequest preserved;
            if (hasContentEncoding || hasTransferEncoding) {
                ImmutableList.Builder<Header> builder = ImmutableList.builder();
                if (hasContentEncoding) {
                    builder.add(new Header(HttpHeaderNames.CONTENT_ENCODING.toString(), headers.getAll(HttpHeaderNames.CONTENT_ENCODING)));
                }
                if (hasTransferEncoding) {
                    builder.add(new Header(HttpHeaderNames.TRANSFER_ENCODING.toString(), headers.getAll(HttpHeaderNames.TRANSFER_ENCODING)));
                }
                preserved = new PreservedRequest(builder.build(), null);
            } else {
                preserved = PreservedRequest.NONE;
            }
            // Always reset for each request, even when empty, so that stale headers or a stale original
            // body do not leak to later requests sharing the same (pooled) connection.
            preservedRequest(ctx).set(preserved);
            // Only capture when the body is content-encoded (compressed), so the original on-the-wire
            // bytes can be preserved before the downstream HttpContentDecompressor decompresses them.
            rawBodyAccumulator = hasContentEncoding ? new ByteArrayOutputStream() : null;
        }
        if (httpObject instanceof HttpContent) {
            ByteArrayOutputStream accumulator = rawBodyAccumulator;
            if (accumulator != null) {
                ByteBuf content = ((HttpContent) httpObject).content();
                int readableBytes = content.readableBytes();
                if (readableBytes > 0) {
                    byte[] chunk = new byte[readableBytes];
                    // non-destructive read: the downstream decompressor still reads the full content
                    content.getBytes(content.readerIndex(), chunk);
                    accumulator.write(chunk, 0, chunk.length);
                }
                if (httpObject instanceof LastHttpContent) {
                    Attribute<PreservedRequest> attribute = preservedRequest(ctx);
                    PreservedRequest current = attribute.get();
                    attribute.set(new PreservedRequest(current != null ? current.headers : ImmutableList.of(), accumulator.toByteArray()));
                    rawBodyAccumulator = null;
                }
            }
        }
        ReferenceCountUtil.retain(httpObject);
        out.add(httpObject);
    }

    private Attribute<PreservedRequest> preservedRequest(ChannelHandlerContext ctx) {
        Attribute<PreservedRequest> attribute = preservedRequest;
        if (attribute == null) {
            attribute = ctx.channel().attr(PRESERVED_REQUEST);
            preservedRequest = attribute;
        }
        return attribute;
    }

    /**
     * The headers and original body preserved for the current request on {@code channel}, never null.
     */
    public static PreservedRequest preservedRequest(Channel channel) {
        PreservedRequest preserved = channel.attr(PRESERVED_REQUEST).get();
        return preserved != null ? preserved : PreservedRequest.NONE;
    }

    public static List<Header> preservedHeaders(Channel channel) {
        return preservedRequest(channel).headers();
    }

    /**
     * The original (still compressed) request body bytes captured before decompression, or null when the
     * request body was not content-encoded.
     */
    public static byte[] originalRawBody(Channel channel) {
        return preservedRequest(channel).originalRawBody();
    }

    /**
     * Immutable per-request snapshot published as ONE channel attribute, so a request costs one
     * attribute lookup to write and one to read rather than one per field.
     */
    public static final class PreservedRequest {
        private static final PreservedRequest NONE = new PreservedRequest(ImmutableList.of(), null);

        private final List<Header> headers;
        private final byte[] originalRawBody;

        private PreservedRequest(List<Header> headers, byte[] originalRawBody) {
            this.headers = headers;
            this.originalRawBody = originalRawBody;
        }

        public List<Header> headers() {
            return headers;
        }

        public byte[] originalRawBody() {
            return originalRawBody;
        }
    }

}
