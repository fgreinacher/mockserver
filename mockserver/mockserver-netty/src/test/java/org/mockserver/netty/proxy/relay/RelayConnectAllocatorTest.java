package org.mockserver.netty.proxy.relay;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.NettyAllocator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;

/**
 * The CONNECT/SOCKS relay opens its own outbound connection to the loopback, and every byte the
 * upstream returns is read into buffers from that connection's allocator. It must be the shared
 * pooled allocator; an unpinned {@code Bootstrap} silently uses Netty 4.2's adaptive default.
 * <p>
 * The test drives a real tunnel over loopback sockets and inspects the aggregated upstream
 * response as the relay hands it to the proxy client: its content was allocated on the relay's
 * outbound channel.
 */
public class RelayConnectAllocatorTest {

    private static final String UPSTREAM_BODY = "relayed";

    private EventLoopGroup eventLoopGroup;
    private Channel proxyServerChannel;

    @Before
    public void createEventLoop() {
        eventLoopGroup = new NioEventLoopGroup(2, new Scheduler.SchedulerThreadFactory(RelayConnectAllocatorTest.class.getSimpleName() + "-eventLoop"));
    }

    @After
    public void stopEventLoop() {
        if (proxyServerChannel != null) {
            proxyServerChannel.close().syncUninterruptibly();
        }
        eventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Test(timeout = 30000)
    public void shouldReadUpstreamBytesWithSharedPooledAllocator() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> upstreamDone = CompletableFuture.runAsync(() -> serveLoopback(upstream));
            InetSocketAddress upstreamAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), upstream.getLocalPort());
            CompletableFuture<ByteBufAllocator> relayedResponseAllocator = new CompletableFuture<>();

            proxyServerChannel = new ServerBootstrap()
                .group(eventLoopGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.ALLOCATOR, NettyAllocator.ALLOCATOR)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.attr(REMOTE_SOCKET).set(upstreamAddress);
                        PortUnificationHandler.deferTlsDetection(ch);
                        ch.pipeline().addLast(new ConnectTriggerAndCapture(relayedResponseAllocator));
                        ch.pipeline().addLast(new TestRelayConnectHandler("localhost", upstreamAddress.getPort()));
                    }
                })
                .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .syncUninterruptibly()
                .channel();

            try (Socket proxyClient = new Socket(InetAddress.getLoopbackAddress(), ((InetSocketAddress) proxyServerChannel.localAddress()).getPort())) {
                proxyClient.setSoTimeout(15_000);
                InputStream in = proxyClient.getInputStream();
                OutputStream out = proxyClient.getOutputStream();

                assertThat(readUntil(in, "\r\n\r\n"), containsString("200"));
                out.write("GET /allocator HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                assertThat(readUntil(in, UPSTREAM_BODY), containsString(UPSTREAM_BODY));
            }

            assertThat(relayedResponseAllocator.get(10, SECONDS), sameInstance(NettyAllocator.ALLOCATOR));
            upstreamDone.get(10, SECONDS);
        }
    }

    /**
     * Plays MockServer's side of the relay loopback: acknowledges the PROXIED preamble, then
     * answers the single tunnelled request.
     */
    private static void serveLoopback(ServerSocket upstream) {
        try (Socket socket = upstream.accept()) {
            socket.setSoTimeout(15_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            readUntil(in, "localhost:" + upstream.getLocalPort());
            out.write(RelayConnectHandler.PROXIED_RESPONSE.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            readUntil(in, "\r\n\r\n");
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + UPSTREAM_BODY.length() + "\r\n\r\n" + UPSTREAM_BODY).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // hold the connection open until the proxy client has read the response
            readUntil(in, "\u0000");
        } catch (IOException ignore) {
            // the relay closes the loopback when the proxy client disconnects
        }
    }

    private static String readUntil(InputStream in, String marker) throws IOException {
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            read.write(b);
            if (read.toString(StandardCharsets.US_ASCII.name()).endsWith(marker)) {
                break;
            }
        }
        return read.toString(StandardCharsets.US_ASCII.name());
    }

    /**
     * Starts the relay as soon as the proxy client connects, and once the tunnel's HTTP pipelines
     * exist, appends an outbound probe that records which allocator produced the relayed response.
     */
    private static class ConnectTriggerAndCapture extends ChannelDuplexHandler {

        private final CompletableFuture<ByteBufAllocator> relayedResponseAllocator;

        ConnectTriggerAndCapture(CompletableFuture<ByteBufAllocator> relayedResponseAllocator) {
            this.relayedResponseAllocator = relayedResponseAllocator;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.fireChannelActive();
            ctx.fireChannelRead("CONNECT");
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.fireChannelRead(msg);
            if (ctx.pipeline().get(UpstreamProxyRelayHandler.class) != null && ctx.pipeline().get("allocatorProbe") == null) {
                ctx.pipeline().addLast("allocatorProbe", new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext probeCtx, Object message, ChannelPromise promise) {
                        if (message instanceof FullHttpResponse) {
                            relayedResponseAllocator.complete(((FullHttpResponse) message).content().alloc());
                        }
                        probeCtx.write(message, promise);
                    }
                });
            }
        }
    }

    private static class TestRelayConnectHandler extends RelayConnectHandler<String> {

        TestRelayConnectHandler(String host, int port) {
            super(configuration(), null, new MockServerLogger(), host, port);
        }

        @Override
        protected void removeCodecSupport(ChannelHandlerContext ctx) {
            ctx.pipeline().remove(this);
        }

        @Override
        protected Object successResponse(Object request) {
            return Unpooled.copiedBuffer("HTTP/1.1 200 Connection established\r\n\r\n", StandardCharsets.US_ASCII);
        }

        @Override
        protected Object failureResponse(Object request) {
            return Unpooled.copiedBuffer("HTTP/1.1 502 Bad Gateway\r\n\r\n", StandardCharsets.US_ASCII);
        }
    }
}
