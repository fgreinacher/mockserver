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
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.model.Header;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class PreserveHeadersNettyRemoves extends MessageToMessageDecoder<HttpObject> {

    private static final AttributeKey<List<Header>> PRESERVED_HEADERS = AttributeKey.valueOf("PRESERVED_HEADERS");
    // accumulates the original (still compressed) request body before the downstream
    // HttpContentDecompressor decompresses it; finalised onto PRESERVED_RAW_BODY at end of request
    private static final AttributeKey<ByteArrayOutputStream> RAW_BODY_ACCUMULATOR = AttributeKey.valueOf("RAW_BODY_ACCUMULATOR");
    private static final AttributeKey<byte[]> PRESERVED_RAW_BODY = AttributeKey.valueOf("PRESERVED_RAW_BODY");

    @Override
    protected void decode(ChannelHandlerContext ctx, HttpObject httpObject, List<Object> out) throws Exception {
        if (httpObject instanceof HttpMessage) {
            final HttpHeaders headers = ((HttpMessage) httpObject).headers();
            boolean hasContentEncoding = headers.contains(HttpHeaderNames.CONTENT_ENCODING);
            boolean hasTransferEncoding = headers.contains(HttpHeaderNames.TRANSFER_ENCODING);
            final List<Header> preservedHeaders;
            if (hasContentEncoding || hasTransferEncoding) {
                ImmutableList.Builder<Header> builder = ImmutableList.builder();
                if (hasContentEncoding) {
                    builder.add(new Header(HttpHeaderNames.CONTENT_ENCODING.toString(), headers.getAll(HttpHeaderNames.CONTENT_ENCODING)));
                }
                if (hasTransferEncoding) {
                    builder.add(new Header(HttpHeaderNames.TRANSFER_ENCODING.toString(), headers.getAll(HttpHeaderNames.TRANSFER_ENCODING)));
                }
                preservedHeaders = builder.build();
            } else {
                preservedHeaders = ImmutableList.of();
            }
            // Always reset the preserved headers for each request, even when empty, so that
            // stale values do not leak to later requests sharing the same (pooled) connection.
            ctx.channel().attr(PRESERVED_HEADERS).set(preservedHeaders);

            // Reset the original-body capture for this request. Only capture when the body is
            // content-encoded (compressed), so the original on-the-wire bytes can be preserved
            // before the downstream HttpContentDecompressor decompresses them.
            ctx.channel().attr(PRESERVED_RAW_BODY).set(null);
            ctx.channel().attr(RAW_BODY_ACCUMULATOR).set(hasContentEncoding ? new ByteArrayOutputStream() : null);
        }
        if (httpObject instanceof HttpContent) {
            ByteArrayOutputStream accumulator = ctx.channel().attr(RAW_BODY_ACCUMULATOR).get();
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
                    ctx.channel().attr(PRESERVED_RAW_BODY).set(accumulator.toByteArray());
                    ctx.channel().attr(RAW_BODY_ACCUMULATOR).set(null);
                }
            }
        }
        ReferenceCountUtil.retain(httpObject);
        out.add(httpObject);
    }

    public static List<Header> preservedHeaders(Channel channel) {
        if (channel.attr(PRESERVED_HEADERS) != null && channel.attr(PRESERVED_HEADERS).get() != null) {
            return channel.attr(PRESERVED_HEADERS).get();
        } else {
            return ImmutableList.of();
        }
    }

    /**
     * The original (still compressed) request body bytes captured before decompression, or null when the
     * request body was not content-encoded.
     */
    public static byte[] originalRawBody(Channel channel) {
        return channel.attr(PRESERVED_RAW_BODY) != null ? channel.attr(PRESERVED_RAW_BODY).get() : null;
    }

}
