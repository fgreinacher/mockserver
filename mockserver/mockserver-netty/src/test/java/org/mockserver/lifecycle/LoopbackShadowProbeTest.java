package org.mockserver.lifecycle;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.netty.MockServer;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.nio.channels.ServerSocketChannel;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class LoopbackShadowProbeTest {

    private static EventLoopGroup group;

    @BeforeClass
    public static void createEventLoopGroup() {
        group = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void shutdownEventLoopGroup() {
        group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    public void shouldFindNoShadowForAnUncontestedWildcardServerWithoutDisturbingItsChildPipeline() throws Exception {
        AtomicInteger childConnections = new AtomicInteger();
        Channel server = bind(new InetSocketAddress(0), childConnections);
        try {
            assertThat(LoopbackShadowProbe.findShadowedLoopback(server, 5000), is(nullValue()));

            // the probe connections are consumed before the acceptor, and the probe handler is removed
            assertThat(childConnections.get(), is(0));
            assertThat(server.pipeline().get(LoopbackShadowProbe.HANDLER_NAME), is(nullValue()));
            try (Socket ignored = new Socket(InetAddress.getByName("127.0.0.1"), port(server))) {
                long deadline = System.currentTimeMillis() + 5000;
                while (childConnections.get() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
            }
            assertThat(childConnections.get(), is(1));
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @Test
    public void shouldFindShadowWhenAnotherListenerHoldsTheSamePortOnIpv4Loopback() throws Exception {
        try (ServerSocketChannel other = ServerSocketChannel.open(StandardProtocolFamily.INET)) {
            other.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            int heldPort = other.socket().getLocalPort();
            Channel server;
            try {
                server = bind(new InetSocketAddress(heldPort), new AtomicInteger());
            } catch (Exception bindRefused) {
                // Linux refuses the wildcard bind outright, so the shadow cannot arise there
                Assume.assumeNoException("operating system refused a wildcard bind over a 127.0.0.1 listener", bindRefused);
                return;
            }
            try {
                long start = System.currentTimeMillis();
                InetSocketAddress shadowed = LoopbackShadowProbe.findShadowedLoopback(server, 200);
                assertThat(shadowed, is(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), heldPort)));
                assertThat(System.currentTimeMillis() - start, lessThan(3000L));
                assertThat(server.pipeline().get(LoopbackShadowProbe.HANDLER_NAME), is(nullValue()));
            } finally {
                server.close().syncUninterruptibly();
            }
        }
    }

    @Test
    public void shouldReportShadowWhenTheConnectionIsEstablishedButNeverAcceptedByThisChannel() throws Exception {
        // with AUTO_READ off the server channel never accepts, which is what a shadowing listener looks like
        // from this channel's side, on every operating system
        Channel server = new ServerBootstrap()
            .group(group, group)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.AUTO_READ, false)
            .childHandler(new CountingChildHandler(new AtomicInteger()))
            .bind(new InetSocketAddress(0))
            .sync()
            .channel();
        try {
            long start = System.currentTimeMillis();
            InetSocketAddress shadowed = LoopbackShadowProbe.findShadowedLoopback(server, 300);

            assertThat(shadowed, is(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(server))));
            assertThat(System.currentTimeMillis() - start, greaterThanOrEqualTo(300L));
            assertThat(server.pipeline().get(LoopbackShadowProbe.HANDLER_NAME), is(nullValue()));
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @Test
    public void shouldNotReportShadowWhenTheEventLoopStallsAfterTheHandlerIsInstalled() throws Exception {
        // a stalled loop cannot accept anything, so a missing accept proves nothing about another listener
        EventLoopGroup stalledGroup = new NioEventLoopGroup(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Channel server = new ServerBootstrap()
                .group(stalledGroup, stalledGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new CountingChildHandler(new AtomicInteger()))
                .bind(new InetSocketAddress(0))
                .sync()
                .channel();
            Runnable stallEventLoop = () -> {
                CountDownLatch stalled = new CountDownLatch(1);
                server.eventLoop().execute(() -> {
                    stalled.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                try {
                    assertThat(stalled.await(5, TimeUnit.SECONDS), is(true));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            assertThat(LoopbackShadowProbe.findShadowedLoopback(server, 300, stallEventLoop), is(nullValue()));

            release.countDown();
            server.close().syncUninterruptibly();
        } finally {
            release.countDown();
            stalledGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldNotLeaveTheProbeHandlerInstalledWhenTheEventLoopIsTooBusyToInstallIt() throws Exception {
        EventLoopGroup busyGroup = new NioEventLoopGroup(1);
        try {
            Channel server = new ServerBootstrap()
                .group(busyGroup, busyGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new CountingChildHandler(new AtomicInteger()))
                .bind(new InetSocketAddress(0))
                .sync()
                .channel();
            CountDownLatch release = new CountDownLatch(1);
            server.eventLoop().execute(() -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // the check cannot be made, so it reports no shadow rather than a false conflict
            assertThat(LoopbackShadowProbe.findShadowedLoopback(server, 300), is(nullValue()));

            release.countDown();
            server.eventLoop().submit(() -> { }).sync();
            assertThat(server.pipeline().get(LoopbackShadowProbe.HANDLER_NAME), is(nullValue()));
            server.close().syncUninterruptibly();
        } finally {
            busyGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldNotRouteTheProbeThroughAJvmWideProxy() throws Exception {
        // a relayed probe arrives from the proxy's address, not the probe's, so it would look shadowed
        MockServer socksProxy = new MockServer();
        Channel server = bind(new InetSocketAddress(0), new AtomicInteger());
        ProxySelector originalProxySelector = ProxySelector.getDefault();
        try {
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return Collections.singletonList(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksProxy.getLocalPort())));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException exception) {
                }
            });

            assertThat(LoopbackShadowProbe.findShadowedLoopback(server, 1000), is(nullValue()));
        } finally {
            ProxySelector.setDefault(originalProxySelector);
            server.close().syncUninterruptibly();
            socksProxy.stop();
        }
    }

    @Test
    public void shouldProbeTheLoopbackAddressesTheBoundAddressCovers() throws Exception {
        InetAddress ipv4Loopback = InetAddress.getByName("127.0.0.1");
        InetAddress ipv6Loopback = InetAddress.getByName("::1");
        assertThat(LoopbackShadowProbe.loopbackTargets(new InetSocketAddress(InetAddress.getByName("::"), 1080)), contains(ipv4Loopback, ipv6Loopback));
        assertThat(LoopbackShadowProbe.loopbackTargets(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 1080)), contains(ipv4Loopback));
        assertThat(LoopbackShadowProbe.loopbackTargets(new InetSocketAddress(ipv4Loopback, 1080)), contains(ipv4Loopback));
        assertThat(LoopbackShadowProbe.loopbackTargets(new InetSocketAddress(ipv6Loopback, 1080)), contains(ipv6Loopback));
        assertThat(LoopbackShadowProbe.loopbackTargets(new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 1080)), is(empty()));
    }

    @Test
    public void shouldMatchTheProbeSourceIncludingIpv4MappedIpv6Form() throws Exception {
        InetSocketAddress probeSource = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 50123);
        byte[] mapped = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1};
        InetSocketAddress mappedSource = new InetSocketAddress(Inet6Address.getByAddress(null, mapped, -1), 50123);

        assertThat(LoopbackShadowProbe.sameEndpoint(probeSource, probeSource), is(true));
        assertThat(LoopbackShadowProbe.sameEndpoint(mappedSource, probeSource), is(true));
        assertThat(LoopbackShadowProbe.sameEndpoint(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 50124), probeSource), is(false));
        assertThat(LoopbackShadowProbe.sameEndpoint(new InetSocketAddress(InetAddress.getByName("::1"), 50123), probeSource), is(false));
    }

    private static Channel bind(InetSocketAddress address, AtomicInteger childConnections) throws InterruptedException {
        return new ServerBootstrap()
            .group(group, group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new CountingChildHandler(childConnections))
            .bind(address)
            .sync()
            .channel();
    }

    @ChannelHandler.Sharable
    private static final class CountingChildHandler extends ChannelInboundHandlerAdapter {
        private final AtomicInteger childConnections;

        private CountingChildHandler(AtomicInteger childConnections) {
            this.childConnections = childConnections;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            childConnections.incrementAndGet();
            ctx.fireChannelActive();
        }
    }

    private static int port(Channel channel) {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }
}
