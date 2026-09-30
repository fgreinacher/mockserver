package org.mockserver.socket;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transport-selection utility that picks the highest-performance Netty transport
 * available on the current platform: Linux epoll when the native library is
 * present, NIO everywhere else.
 * <p>
 * Epoll gives better throughput and lower latency on Linux, and is required for
 * transparent-proxy {@code SO_ORIGINAL_DST} resolution (which needs
 * {@code EpollSocketChannel} children). The selection is determined once at
 * class-load time and cached; all methods are safe to call from any thread.
 * <p>
 * On non-Linux platforms (macOS, Windows) or when the opt-out flag
 * {@code useNativeTransport=false} is set, everything transparently falls back
 * to NIO with no behaviour change.
 * <p>
 * The class is placed in {@code mockserver-core} so both the server bootstrap
 * ({@code mockserver-netty}) and the outbound HTTP client
 * ({@code NettyHttpClient} in {@code mockserver-core}) can use it.
 *
 * @see org.mockserver.configuration.ConfigurationProperties#useNativeTransport()
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup/EpollEventLoopGroup deprecation in Netty 4.2
public final class NettyTransport {

    private static final Logger LOG = LoggerFactory.getLogger(NettyTransport.class);

    /**
     * Cached result of the epoll availability probe, performed once at class-load time.
     * {@code true} only when the native epoll library loaded successfully.
     */
    private static final boolean EPOLL_AVAILABLE = probeEpollAvailable();

    private static final boolean IS_LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    private static final AtomicBoolean SELECTION_LOGGED = new AtomicBoolean(false);

    private NettyTransport() {
        // utility class
    }

    /**
     * Returns {@code true} if the native epoll transport should be used, considering
     * both platform availability and the user's opt-out configuration flag.
     *
     * @param useNativeTransport the configuration flag ({@code true} = prefer native
     *                           when available; {@code false} = force NIO)
     * @return {@code true} to use epoll, {@code false} to use NIO
     */
    public static boolean useNativeTransport(boolean useNativeTransport) {
        return decide(useNativeTransport, EPOLL_AVAILABLE);
    }

    /**
     * Pure transport-selection decision, extracted so the epoll branch can be unit-tested
     * on non-Linux hosts (where {@link #EPOLL_AVAILABLE} is always {@code false}).
     *
     * @param useNativeTransport the configuration opt-out flag
     * @param epollAvailable     whether the epoll native transport is available
     * @return {@code true} to use epoll, {@code false} to use NIO
     */
    static boolean decide(boolean useNativeTransport, boolean epollAvailable) {
        return useNativeTransport && epollAvailable;
    }

    /**
     * Returns {@code true} if the epoll native library is available on this platform,
     * independent of the configuration opt-out flag.
     */
    public static boolean isEpollAvailable() {
        return EPOLL_AVAILABLE;
    }

    /**
     * Creates a new {@link EventLoopGroup} of the appropriate transport type.
     * If epoll is selected (per the config flag and platform availability), returns
     * an {@code EpollEventLoopGroup}; otherwise returns a {@code NioEventLoopGroup}.
     * <p>
     * If epoll group creation fails at runtime (e.g. native library load race),
     * falls back to NIO gracefully.
     *
     * @param nThreads          number of event loop threads
     * @param threadFactory     thread factory for naming threads
     * @param useNativeTransport the configuration flag
     * @return an event loop group — pass it to {@link #serverSocketChannelClassFor},
     *         {@link #socketChannelClassFor}, or {@link #datagramChannelClassFor} to
     *         obtain a channel class that is guaranteed to be compatible
     */
    public static EventLoopGroup newEventLoopGroup(int nThreads, ThreadFactory threadFactory, boolean useNativeTransport) {
        return newEventLoopGroup(nThreads, threadFactory, useNativeTransport, EPOLL_AVAILABLE);
    }

    /**
     * Package-private seam taking explicit {@code epollAvailable} so the epoll-selection
     * (and its graceful NIO fallback) can be unit-tested on non-Linux hosts.
     */
    static EventLoopGroup newEventLoopGroup(int nThreads, ThreadFactory threadFactory, boolean useNativeTransport, boolean epollAvailable) {
        String nioReason;
        if (decide(useNativeTransport, epollAvailable)) {
            try {
                EventLoopGroup group = new io.netty.channel.epoll.EpollEventLoopGroup(nThreads, threadFactory);
                LOG.debug("created EpollEventLoopGroup with {} threads", nThreads);
                logSelectionOnce(SELECTION_LOGGED, LOG, true, "native epoll transport");
                return group;
            } catch (NoClassDefFoundError | UnsatisfiedLinkError | Exception e) {
                LOG.warn("failed to create EpollEventLoopGroup, falling back to NIO: {}", e.getMessage());
                nioReason = "EpollEventLoopGroup creation failed: " + e.getMessage();
            }
        } else if (!useNativeTransport) {
            nioReason = "native transport disabled by useNativeTransport=false";
        } else {
            nioReason = "native epoll transport unavailable: " + epollUnavailabilityCause();
        }
        logSelectionOnce(SELECTION_LOGGED, LOG, nioSelectionLoggedAtInfo(useNativeTransport, IS_LINUX), "NIO transport (" + nioReason + ")");
        return new NioEventLoopGroup(nThreads, threadFactory);
    }

    /**
     * A NIO fallback is worth an INFO line on Linux, where epoll was expected and its absence is
     * usually a packaging fault, or when the user switched epoll off; elsewhere NIO is the only
     * option, so DEBUG.
     */
    static boolean nioSelectionLoggedAtInfo(boolean useNativeTransport, boolean linux) {
        return !useNativeTransport || linux;
    }

    /**
     * Logs the transport the first event-loop group was created with, once per JVM ({@code logged}
     * is the JVM-wide flag outside tests). Only the server lifecycle creates groups through this
     * class, so client-only users never see the line.
     */
    static void logSelectionOnce(AtomicBoolean logged, Logger logger, boolean info, String transport) {
        if (logged.compareAndSet(false, true)) {
            if (info) {
                logger.info("using {}", transport);
            } else {
                logger.debug("using {}", transport);
            }
        }
    }

    private static String epollUnavailabilityCause() {
        try {
            Throwable cause = io.netty.channel.epoll.Epoll.unavailabilityCause();
            if (cause == null) {
                return "unknown";
            }
            Throwable root = cause;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return String.valueOf(root.getMessage());
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            return "epoll classes not on the classpath";
        }
    }

    /**
     * Returns the server socket channel class that is <b>compatible with the given group</b>.
     * <p>
     * Unlike the removed flag-based methods, this derives the channel class from the
     * actual {@link EventLoopGroup} instance — so the two can never desync even when
     * {@link #newEventLoopGroup} fell back from epoll to NIO at runtime.
     *
     * @param group the {@link EventLoopGroup} the bootstrap will use
     * @return {@code EpollServerSocketChannel.class} when the group is an
     *         {@code EpollEventLoopGroup}, {@code NioServerSocketChannel.class} otherwise
     */
    public static Class<? extends ServerChannel> serverSocketChannelClassFor(EventLoopGroup group) {
        if (isEpollGroup(group)) {
            return io.netty.channel.epoll.EpollServerSocketChannel.class;
        }
        return NioServerSocketChannel.class;
    }

    /**
     * Returns the client socket channel class that is <b>compatible with the given group</b>.
     * <p>
     * This guarantees group/channel compatibility regardless of how the group was created
     * (config-driven, caller-supplied NIO, or epoll fallback to NIO).
     *
     * @param group the {@link EventLoopGroup} the bootstrap will use
     * @return {@code EpollSocketChannel.class} when the group is an
     *         {@code EpollEventLoopGroup}, {@code NioSocketChannel.class} otherwise
     */
    public static Class<? extends Channel> socketChannelClassFor(EventLoopGroup group) {
        if (isEpollGroup(group)) {
            return io.netty.channel.epoll.EpollSocketChannel.class;
        }
        return NioSocketChannel.class;
    }

    /**
     * Returns the datagram channel class that is <b>compatible with the given group</b>.
     * <p>
     * Used by the DNS mock server bootstrap to pick the correct datagram channel
     * for whatever event loop group it is registered with.
     *
     * @param group the {@link EventLoopGroup} the bootstrap will use
     * @return {@code EpollDatagramChannel.class} when the group is an
     *         {@code EpollEventLoopGroup}, {@code NioDatagramChannel.class} otherwise
     */
    public static Class<? extends DatagramChannel> datagramChannelClassFor(EventLoopGroup group) {
        if (isEpollGroup(group)) {
            return io.netty.channel.epoll.EpollDatagramChannel.class;
        }
        return NioDatagramChannel.class;
    }

    /**
     * Checks whether the given group is backed by the epoll transport. Matches both
     * {@code EpollEventLoopGroup} (a multi-thread group, e.g. the server boss/worker
     * groups) AND {@code EpollEventLoop} (a single event loop obtained from
     * {@code channel.eventLoop()}, as used by the proxy relay bootstrap) — an
     * {@code EpollEventLoop} is NOT a subtype of {@code EpollEventLoopGroup}, so both
     * must be checked or the relay path would pick a NIO channel for an epoll loop and
     * fail channel registration on Linux. The {@code instanceof} checks do not trigger
     * native library loading; {@link NoClassDefFoundError} is guarded defensively so the
     * class still works where the epoll API classes are absent from the classpath.
     */
    private static boolean isEpollGroup(EventLoopGroup group) {
        try {
            return group instanceof io.netty.channel.epoll.EpollEventLoopGroup
                || group instanceof io.netty.channel.epoll.EpollEventLoop;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Probes whether the Netty epoll transport is available. Guards against
     * {@link NoClassDefFoundError} and {@link UnsatisfiedLinkError} so this
     * class loads cleanly on macOS/Windows where the native library is absent.
     */
    private static boolean probeEpollAvailable() {
        try {
            boolean available = io.netty.channel.epoll.Epoll.isAvailable();
            if (available) {
                LOG.debug("Netty epoll transport is available; will use native transport when enabled");
            } else {
                LOG.debug("Netty epoll transport is not available on this platform; using NIO");
            }
            return available;
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            LOG.debug("Netty epoll classes not loadable: {}; using NIO", e.getMessage());
            return false;
        }
    }
}
