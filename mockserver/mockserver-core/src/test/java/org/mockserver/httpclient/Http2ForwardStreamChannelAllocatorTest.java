package org.mockserver.httpclient;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.concurrent.Future;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.NettyAllocator;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * An HTTP/2 forward sends each request on its own stream child channel. Netty creates that channel
 * with a fresh default config, so without an explicit pin the forward's per-stream handlers would
 * allocate from Netty 4.2's adaptive default while the upstream connection itself is pooled.
 */
public class Http2ForwardStreamChannelAllocatorTest {

    @Test
    public void shouldOpenForwardStreamWithSharedPooledAllocator() {
        Http2ForwardStreamChildInitializer childInitializer = new Http2ForwardStreamChildInitializer(
            configuration(),
            new MockServerLogger(),
            Collections.emptyMap(),
            new ChannelInboundHandlerAdapter(),
            null
        );
        EmbeddedChannel connection = new EmbeddedChannel();
        // mirror NettyHttpClient's bootstrap option: the upstream connection itself is pooled
        connection.config().setAllocator(NettyAllocator.ALLOCATOR);
        connection.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
        connection.pipeline().addLast(new Http2MultiplexHandler(childInitializer));
        try {
            // the same call HttpClientInitializer's dispatch handler makes for every forwarded request
            Future<Http2StreamChannel> open = new Http2StreamChannelBootstrap(connection)
                .handler(childInitializer)
                .open();
            connection.runPendingTasks();

            assertThat(open.isSuccess(), is(true));
            Http2StreamChannel stream = open.getNow();
            assertThat(stream.alloc(), sameInstance(NettyAllocator.ALLOCATOR));
            assertThat(stream.pipeline().lastContext().alloc(), sameInstance(NettyAllocator.ALLOCATOR));
            stream.close();
        } finally {
            connection.finishAndReleaseAll();
        }
    }
}
