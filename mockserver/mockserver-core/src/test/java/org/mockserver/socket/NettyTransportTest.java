package org.mockserver.socket;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.Test;
import org.slf4j.Logger;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests for {@link NettyTransport} transport-selection utility.
 * <p>
 * On this host (macOS) epoll is NOT available, so these tests validate the
 * NIO fallback path. The epoll-active path can only be validated on Linux
 * with the native library present.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class NettyTransportTest {

    private static final ThreadFactory THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "test-transport");
        t.setDaemon(true);
        return t;
    };

    @Test
    public void shouldReturnFalseForEpollOnNonLinux() {
        // On macOS, epoll is never available
        assertFalse("epoll should not be available on this platform",
            NettyTransport.isEpollAvailable());
    }

    @Test
    public void shouldReturnNioWhenNativeTransportEnabledButUnavailable() {
        // Even when useNativeTransport=true, falls back to NIO on non-Linux
        assertFalse("should use NIO when epoll is unavailable",
            NettyTransport.useNativeTransport(true));
    }

    @Test
    public void shouldReturnNioWhenNativeTransportDisabled() {
        assertFalse("should use NIO when explicitly disabled",
            NettyTransport.useNativeTransport(false));
    }

    @Test
    public void shouldCreateNioEventLoopGroupWhenNativeEnabled() {
        EventLoopGroup group = NettyTransport.newEventLoopGroup(1, THREAD_FACTORY, true);
        try {
            assertThat(group, is(instanceOf(NioEventLoopGroup.class)));
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void shouldCreateNioEventLoopGroupWhenNativeDisabled() {
        EventLoopGroup group = NettyTransport.newEventLoopGroup(1, THREAD_FACTORY, false);
        try {
            assertThat(group, is(instanceOf(NioEventLoopGroup.class)));
        } finally {
            group.shutdownGracefully();
        }
    }

    // --- group-derived channel class tests ---

    @Test
    public void shouldReturnNioServerSocketChannelClassForNioGroup() {
        EventLoopGroup group = new NioEventLoopGroup(1, THREAD_FACTORY);
        try {
            assertThat(NettyTransport.serverSocketChannelClassFor(group),
                is((Object) NioServerSocketChannel.class));
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void shouldReturnNioSocketChannelClassForNioGroup() {
        EventLoopGroup group = new NioEventLoopGroup(1, THREAD_FACTORY);
        try {
            assertThat(NettyTransport.socketChannelClassFor(group),
                is((Object) NioSocketChannel.class));
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void shouldReturnNioDatagramChannelClassForNioGroup() {
        EventLoopGroup group = new NioEventLoopGroup(1, THREAD_FACTORY);
        try {
            assertThat(NettyTransport.datagramChannelClassFor(group),
                is((Object) NioDatagramChannel.class));
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void shouldReturnNioSocketChannelClassForSingleEventLoop() {
        // RelayConnectHandler passes a single EventLoop (channel.eventLoop()), not the
        // multi-thread group. An EventLoop IS an EventLoopGroup, so the selector must still
        // return a channel class compatible with that loop (NioSocketChannel for a NioEventLoop).
        EventLoopGroup group = new NioEventLoopGroup(1, THREAD_FACTORY);
        try {
            EventLoopGroup singleLoop = group.next();
            assertThat(NettyTransport.socketChannelClassFor(singleLoop),
                is((Object) NioSocketChannel.class));
        } finally {
            group.shutdownGracefully();
        }
    }

    // --- decision matrix (epoll branch exercised independently of platform availability) ---

    @Test
    public void decideShouldRequireBothFlagAndAvailability() {
        assertTrue("config on + epoll available -> use native", NettyTransport.decide(true, true));
        assertFalse("config on + epoll unavailable -> NIO", NettyTransport.decide(true, false));
        assertFalse("config off + epoll available -> NIO", NettyTransport.decide(false, true));
        assertFalse("config off + epoll unavailable -> NIO", NettyTransport.decide(false, false));
    }

    @Test
    public void eventLoopGroupShouldFallBackToNioWhenEpollSelectedButNativeMissing() {
        // Force the epoll branch with epollAvailable=true. On this (non-Linux) host the
        // EpollEventLoopGroup construction fails to load the native library, so the
        // try/catch fallback must yield a working NioEventLoopGroup rather than throwing.
        EventLoopGroup group = NettyTransport.newEventLoopGroup(1, THREAD_FACTORY, true, true);
        try {
            assertThat(group, is(instanceOf(NioEventLoopGroup.class)));
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void eventLoopGroupShouldBeNioWhenEpollNotChosen() {
        EventLoopGroup group = NettyTransport.newEventLoopGroup(1, THREAD_FACTORY, false, true);
        try {
            assertThat(group, is(instanceOf(NioEventLoopGroup.class)));
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void selectionShouldBeLoggedOnlyOnce() {
        Logger logger = mock(Logger.class);
        AtomicBoolean logged = new AtomicBoolean(false);

        NettyTransport.logSelectionOnce(logged, logger, true, "native epoll transport");
        NettyTransport.logSelectionOnce(logged, logger, true, "NIO transport (second group)");

        verify(logger, times(1)).info("using {}", "native epoll transport");
        verify(logger, never()).info("using {}", "NIO transport (second group)");
    }

    @Test
    public void selectionShouldBeLoggedAtDebugWhenNotInfo() {
        Logger logger = mock(Logger.class);

        NettyTransport.logSelectionOnce(new AtomicBoolean(false), logger, false, "NIO transport (x)");

        verify(logger).debug("using {}", "NIO transport (x)");
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void nioFallbackShouldBeInfoOnLinuxOrWhenDisabledAndDebugOtherwise() {
        assertTrue("epoll wanted but unavailable on Linux -> INFO", NettyTransport.nioSelectionLoggedAtInfo(true, true));
        assertFalse("epoll wanted but unavailable off Linux -> DEBUG", NettyTransport.nioSelectionLoggedAtInfo(true, false));
        assertTrue("epoll switched off on Linux -> INFO", NettyTransport.nioSelectionLoggedAtInfo(false, true));
        assertTrue("epoll switched off off Linux -> INFO", NettyTransport.nioSelectionLoggedAtInfo(false, false));
    }
}
