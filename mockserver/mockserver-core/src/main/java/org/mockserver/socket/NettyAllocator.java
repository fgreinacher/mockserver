package org.mockserver.socket;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;

/**
 * The one {@link ByteBufAllocator} used for all MockServer request/response traffic, so a single
 * allocator family (and one set of pooled arenas) serves it. HTTP/3 connection-local control and
 * QPACK streams, which Netty opens itself, keep Netty's default.
 * <p>
 * Netty 4.2's {@link ByteBufAllocator#DEFAULT} is the adaptive allocator, and any channel whose
 * allocator is not set explicitly uses it. A bootstrap {@code option}/{@code childOption} does not
 * reach every channel: HTTP/2 stream child channels ({@code Http2StreamChannel}) and QUIC channels
 * are created by their codecs with a fresh default config and do not inherit the parent's
 * allocator, so they must be pinned with {@link #pin(Channel)} or the codec's own option setter.
 */
public final class NettyAllocator {

    public static final ByteBufAllocator ALLOCATOR = PooledByteBufAllocator.DEFAULT;

    private NettyAllocator() {
        // utility class
    }

    /**
     * Sets {@link #ALLOCATOR} on a channel that no bootstrap option reaches, such as an HTTP/2
     * stream child channel. Call it before the channel reads or writes anything.
     */
    public static void pin(Channel channel) {
        channel.config().setAllocator(ALLOCATOR);
    }
}
