package org.mockserver.lifecycle;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;

import java.io.IOException;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Checks that a newly bound listening channel really receives the connections made to the loopback
 * address(es) it covers.
 * <p>
 * On macOS (BSD sockets) a wildcard bind succeeds on a port that another socket already holds on
 * 127.0.0.1 specifically — both for an explicit port and, because the IPv6 ephemeral allocator ignores
 * IPv4-specific listeners, for port 0. The kernel then routes connections to 127.0.0.1:port to the more
 * specific listener, so the wildcard server "starts" but never sees its localhost traffic. Linux refuses
 * such a bind, so there the probe always succeeds straight away.
 * <p>
 * Each probe connects from a pre-bound loopback source address, so the accepted connection is
 * recognised by its exact remote address. It is taken off the listening channel's pipeline ahead of
 * Netty's {@code ServerBootstrapAcceptor} and closed, so it never reaches the child pipeline, the event
 * log or any metrics.
 */
final class LoopbackShadowProbe {

    static final String HANDLER_NAME = "mockServerLoopbackShadowProbe";
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;
    private static final int EVENT_LOOP_RESPONSE_TIMEOUT_MILLIS = 1000;
    private static final int ACCEPT_GRACE_MILLIS = 100;
    private static final InetAddress IPV4_LOOPBACK = byAddress(new byte[]{127, 0, 0, 1});
    private static final InetAddress IPV6_LOOPBACK = byAddress(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});

    private LoopbackShadowProbe() {
    }

    /**
     * @param serverChannel       a bound, listening server channel
     * @param acceptTimeoutMillis how long a probe connection that the kernel has already established may
     *                            take to show up on {@code serverChannel} before it is judged to have
     *                            been accepted by another listener
     * @return the loopback endpoint whose connections reach a different listener, or {@code null} if every
     * covered loopback address reaches {@code serverChannel} (or the check could not be made)
     */
    static InetSocketAddress findShadowedLoopback(Channel serverChannel, long acceptTimeoutMillis) throws InterruptedException {
        return findShadowedLoopback(serverChannel, acceptTimeoutMillis, () -> {
        });
    }

    /**
     * @param afterInstall run once the probe handler is installed and before any probe connects (a test seam)
     */
    static InetSocketAddress findShadowedLoopback(Channel serverChannel, long acceptTimeoutMillis, Runnable afterInstall) throws InterruptedException {
        if (!(serverChannel.localAddress() instanceof InetSocketAddress)) {
            return null;
        }
        InetSocketAddress boundAddress = (InetSocketAddress) serverChannel.localAddress();
        List<InetAddress> targets = loopbackTargets(boundAddress);
        if (targets.isEmpty()) {
            return null;
        }
        AcceptInterceptor interceptor = new AcceptInterceptor();
        Future<?> install = null;
        try {
            // added from the event loop so the handler is fully active before any probe connects
            install = serverChannel.eventLoop().submit(() -> serverChannel.pipeline().addFirst(HANDLER_NAME, interceptor));
            install.get(CONNECT_TIMEOUT_MILLIS, MILLISECONDS);
        } catch (InterruptedException e) {
            abandonInstall(serverChannel, install, interceptor);
            throw e;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            abandonInstall(serverChannel, install, interceptor);
            return null;
        }
        try {
            afterInstall.run();
            for (InetAddress loopback : targets) {
                InetSocketAddress target = new InetSocketAddress(loopback, boundAddress.getPort());
                if (reachesAnotherListener(serverChannel, target, interceptor, acceptTimeoutMillis)) {
                    return target;
                }
            }
            return null;
        } finally {
            removeQuietly(serverChannel, interceptor);
        }
    }

    private static void abandonInstall(Channel serverChannel, Future<?> install, AcceptInterceptor interceptor) {
        if (install != null && !install.cancel(false)) {
            // it already ran (or is running): queue the removal behind it on the same event loop
            try {
                serverChannel.eventLoop().execute(() -> removeQuietly(serverChannel, interceptor));
            } catch (RuntimeException eventLoopShutDown) {
                // the channel is going away with its event loop
            }
        }
    }

    private static void removeQuietly(Channel serverChannel, AcceptInterceptor interceptor) {
        try {
            if (serverChannel.pipeline().context(interceptor) != null) {
                serverChannel.pipeline().remove(interceptor);
            }
        } catch (RuntimeException ignore) {
            // channel already closed
        }
    }

    static List<InetAddress> loopbackTargets(InetSocketAddress boundAddress) {
        InetAddress address = boundAddress.getAddress();
        if (address == null) {
            return Collections.emptyList();
        }
        if (address.isAnyLocalAddress()) {
            List<InetAddress> targets = new ArrayList<>();
            targets.add(IPV4_LOOPBACK);
            // only an IPv6 wildcard socket (the JDK's dual-stack default) listens on ::1 as well
            if (address instanceof Inet6Address) {
                targets.add(IPV6_LOOPBACK);
            }
            return targets;
        }
        if (address.isLoopbackAddress()) {
            return Collections.singletonList(address);
        }
        return Collections.emptyList();
    }

    private static boolean reachesAnotherListener(Channel serverChannel, InetSocketAddress target, AcceptInterceptor interceptor, long acceptTimeoutMillis) throws InterruptedException {
        // NO_PROXY: a JVM-wide ProxySelector or socksProxyHost would otherwise relay the probe from a
        // different source address, which this channel would not recognise (a false "shadowed" verdict)
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.setSoLinger(true, 0);
            socket.bind(new InetSocketAddress(target.getAddress(), 0));
            CompletableFuture<Void> accepted = interceptor.expect((InetSocketAddress) socket.getLocalSocketAddress());
            socket.connect(target, CONNECT_TIMEOUT_MILLIS);
            try {
                accepted.get(acceptTimeoutMillis, MILLISECONDS);
                return false;
            } catch (TimeoutException notAcceptedInTime) {
                return notAcceptedByALiveEventLoop(serverChannel, accepted);
            }
        } catch (IOException | ExecutionException e) {
            // address family unavailable or nothing listening for it: nothing is being shadowed
            return false;
        } finally {
            interceptor.clear();
        }
    }

    /**
     * A probe that has not arrived in time was taken by another listener only if this channel's event loop
     * is still running: a stalled loop cannot accept anything, so its verdict is inconclusive and, as when
     * the handler cannot be installed, reported as not shadowed.
     */
    private static boolean notAcceptedByALiveEventLoop(Channel serverChannel, CompletableFuture<Void> accepted) throws InterruptedException {
        try {
            serverChannel.eventLoop().submit(() -> {
            }).get(EVENT_LOOP_RESPONSE_TIMEOUT_MILLIS, MILLISECONDS);
        } catch (TimeoutException | ExecutionException | RuntimeException stalledOrShutDown) {
            return false;
        }
        try {
            // the loop is running, so a probe still waiting in this channel's accept queue is taken within one select
            accepted.get(ACCEPT_GRACE_MILLIS, MILLISECONDS);
            return false;
        } catch (TimeoutException acceptedElsewhere) {
            return true;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /**
     * Unlike the IPv6 wildcard allocator, the IPv4 wildcard allocator does not hand out a port that
     * another socket holds on 127.0.0.1, so it makes a better next candidate after a shadowed ephemeral
     * bind (the port is released before it is rebound, so the caller must still verify it).
     *
     * @return a port that was free on every IPv4 address, or 0 if none could be obtained
     */
    static int portFreeOnIpv4() {
        try (ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.INET)) {
            channel.bind(new InetSocketAddress(0));
            return channel.socket().getLocalPort();
        } catch (IOException | UnsupportedOperationException e) {
            return 0;
        }
    }

    private static InetAddress byAddress(byte[] address) {
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Expectation {
        private final InetSocketAddress source;
        private final CompletableFuture<Void> accepted = new CompletableFuture<>();

        private Expectation(InetSocketAddress source) {
            this.source = source;
        }
    }

    private static final class AcceptInterceptor extends ChannelInboundHandlerAdapter {

        private final AtomicReference<Expectation> expectation = new AtomicReference<>();

        CompletableFuture<Void> expect(InetSocketAddress source) {
            Expectation next = new Expectation(source);
            expectation.set(next);
            return next.accepted;
        }

        void clear() {
            expectation.set(null);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Expectation current = expectation.get();
            if (current != null && msg instanceof Channel && sameEndpoint(((Channel) msg).remoteAddress(), current.source) && expectation.compareAndSet(current, null)) {
                discard((Channel) msg);
                current.accepted.complete(null);
                return;
            }
            ctx.fireChannelRead(msg);
        }

        private static void discard(Channel probeConnection) {
            try {
                probeConnection.config().setOption(ChannelOption.SO_LINGER, 0);
            } catch (RuntimeException ignore) {
                // best effort: only avoids a TIME_WAIT entry
            }
            probeConnection.unsafe().closeForcibly();
        }
    }

    static boolean sameEndpoint(SocketAddress remote, InetSocketAddress expected) {
        if (!(remote instanceof InetSocketAddress)) {
            return false;
        }
        InetSocketAddress actual = (InetSocketAddress) remote;
        return actual.getPort() == expected.getPort() && Objects.equals(unmapped(actual.getAddress()), unmapped(expected.getAddress()));
    }

    private static InetAddress unmapped(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            boolean v4Mapped = bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            for (int i = 0; v4Mapped && i < 10; i++) {
                v4Mapped = bytes[i] == 0;
            }
            if (v4Mapped) {
                return byAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
            }
        }
        return address;
    }
}
