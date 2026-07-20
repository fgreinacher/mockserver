package org.mockserver.lifecycle;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.PortFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Graceful shutdown must stop accepting NEW connections before it drains the in-flight ones.
 * <p>
 * These are <strong>regression guards for behaviour that is already correct</strong>, not tests
 * for a known defect. A suspected bug here -- that {@code LifeCycle} closing its server channels
 * with {@code disconnect()} left the listening socket open on NIO, because
 * {@code NioServerSocketChannel.doDisconnect()} throws {@link UnsupportedOperationException} --
 * was investigated and <strong>disproved</strong>. The pipeline translates disconnect into close
 * before reaching the channel: {@code AbstractChannelHandlerContext.disconnect()} opens with
 * {@code if (!channel().metadata().hasDisconnect()) return close(promise);}, and a server
 * channel's metadata is {@code new ChannelMetadata(false, 16)}. See the comment at the call site
 * in {@code LifeCycle}. There is no platform divergence: epoll reaches {@code close()} via
 * {@code doDisconnect() -> doClose()}, NIO via the pipeline translation.
 * <p>
 * The tests are kept because someone removing or altering the channel close is a real risk, and
 * this is what would catch it.
 * <p>
 * Two design points make them meaningful rather than decorative:
 * <ul>
 *   <li>They assert the <strong>observable</strong> -- can a fresh TCP client still connect --
 *       rather than the mechanism, so they stay valid however the socket comes to be closed.</li>
 *   <li>The refusal poll window is deliberately much SHORTER than the in-flight delay, and
 *       {@code stopFuture.isDone()} is asserted false. Without both, the test passes for the wrong
 *       reason: an earlier version used a 5s poll against a 3s drain and observed the socket
 *       closing during post-drain event-loop teardown, so it passed even when the listening socket
 *       was never closed at shutdown. It was validated with a positive control -- replacing the
 *       close with {@code newSucceededFuture()} so the socket genuinely never closes turns it
 *       red.</li>
 * </ul>
 * The test pins {@code useNativeTransport=false} so it exercises the NIO path explicitly on every
 * platform, including Linux CI.
 *
 * @author jamesdbloom
 */
public class StopAcceptingConnectionsIntegrationTest {

    private static EventLoopGroup clientEventLoopGroup;
    private static NettyHttpClient httpClient;

    @BeforeClass
    public static void createClient() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(
            StopAcceptingConnectionsIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);
    }

    @AfterClass
    public static void stopClient() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    private long originalDrainMillis;

    @After
    public void restoreDrainMillis() {
        ConfigurationProperties.stopDrainMillis(originalDrainMillis);
    }

    /**
     * Once {@code stopAsync()} has begun and the server is draining an in-flight request, the
     * listening socket must already be closed: a brand-new TCP connection must be refused.
     */
    @Test
    public void shouldRefuseNewConnectionsWhileDrainingInFlightRequests() throws Exception {
        originalDrainMillis = ConfigurationProperties.stopDrainMillis();
        // given -- a long drain window and an NIO (non-native) server with a slow expectation, so
        // there is a wide, deterministic window during which shutdown is draining
        ConfigurationProperties.stopDrainMillis(30_000L);
        MockServer mockServer = new MockServer(
            configuration().useNativeTransport(false),
            PortFactory.findFreePort()
        );
        int port = mockServer.getLocalPort();
        MockServerClient mockServerClient = new MockServerClient("localhost", port);
        try {
            // the delay must be comfortably longer than the refusal poll window below, so that
            // "refused" can only mean "refused WHILE still draining" and never "refused after the
            // drain finished and the event loops tore the socket down anyway"
            mockServerClient
                .when(request().withPath("/slow"))
                .respond(response().withBody("drained").withDelay(TimeUnit.SECONDS, 10));

            // and -- a request genuinely in flight, so the drain will block
            final CountDownLatch requestSent = new CountDownLatch(1);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread requester = new Thread(() -> {
                try {
                    requestSent.countDown();
                    httpClient
                        .sendRequest(request().withPath("/slow").withHeader("Host", "localhost:" + port),
                            new InetSocketAddress("localhost", port))
                        .get(30, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "stop-accepting-requester");
            requester.setDaemon(true);
            requester.start();

            requestSent.await(5, TimeUnit.SECONDS);
            long waitStart = System.currentTimeMillis();
            while (mockServer.getRequestsInFlight() == 0 && System.currentTimeMillis() - waitStart < 5_000) {
                Thread.sleep(5);
            }
            assertThat("precondition: a request must be in flight before stop begins",
                mockServer.getRequestsInFlight(), is(greaterThanOrEqualTo(1)));

            // when -- shutdown begins (it will block draining the 10s delayed response)
            CompletableFuture<String> stopFuture = mockServer.stopAsync();

            // then -- the listening socket must already be closed. Poll only for a window MUCH
            // shorter than the 10s in-flight delay: closing the server channels is asynchronous,
            // but it must happen at the START of shutdown, not as a side effect of the event
            // loops being torn down after the drain completes. A generous poll window is what
            // makes this test vacuous -- it would observe the post-drain teardown and pass
            // against the unfixed code (confirmed: it did).
            boolean refused = false;
            long deadline = System.currentTimeMillis() + 3_000;
            IOException refusal = null;
            while (System.currentTimeMillis() < deadline) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("localhost", port), 500);
                    // connection ACCEPTED - the server is still listening
                } catch (IOException connectionRefused) {
                    refused = true;
                    refusal = connectionRefused;
                    break;
                }
                Thread.sleep(25);
            }

            // the drain must still be running, or "refused" proves nothing about ordering
            assertThat("precondition: shutdown must still be draining when refusal is observed, "
                    + "otherwise this assertion cannot distinguish closing the listener at the "
                    + "start of shutdown from tearing it down at the end",
                stopFuture.isDone(), is(false));
            assertThat("the server must stop accepting new connections as soon as shutdown begins, "
                    + "but a new TCP connection was still accepted 3s into a 10s drain"
                    + (refusal != null ? " (" + refusal + ")" : ""),
                refused, is(true));

            // and -- the in-flight request still drains normally and shutdown completes
            stopFuture.get(40, TimeUnit.SECONDS);
            requester.join(30_000);
            assertThat("the in-flight request should still have completed: " + failure.get(),
                failure.get(), is((Throwable) null));
        } finally {
            if (mockServer.isRunning()) {
                mockServer.stop();
            }
        }
    }

    /**
     * After shutdown has fully completed the port must be free and connections refused. This is
     * the weaker, always-true-on-every-platform end state; the test above is the one that pins
     * the ordering relative to the drain.
     */
    @Test
    public void shouldRefuseNewConnectionsAfterShutdownCompletes() throws Exception {
        originalDrainMillis = ConfigurationProperties.stopDrainMillis();
        // given
        MockServer mockServer = new MockServer(
            configuration().useNativeTransport(false),
            PortFactory.findFreePort()
        );
        int port = mockServer.getLocalPort();

        // when
        mockServer.stopAsync().get(30, TimeUnit.SECONDS);

        // then
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 1_000);
            fail("a connection to port " + port + " should be refused after shutdown completes");
        } catch (IOException expected) {
            // connection refused as required
        }
    }
}
