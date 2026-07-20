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
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.PortFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for WS7.2 graceful shutdown connection drain.
 *
 * The first group exercises the in-flight request counter and the drain mechanism through the
 * public {@link LifeCycle} API (exposed on {@link MockServer}) — these isolate the drain logic.
 * The end-to-end group at the bottom drives a REAL running server through the production request
 * path (channelRead0 / NettyResponseWriter) to prove the counter is actually wired in: the drain
 * waits for a real delayed response, and the counter returns to zero after both normal and
 * dropped requests with no leak.
 *
 * @author jamesdbloom
 */
public class StopDrainIntegrationTest {

    private static EventLoopGroup clientEventLoopGroup;
    private static NettyHttpClient httpClient;

    @BeforeClass
    public static void createClient() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(StopDrainIntegrationTest.class.getSimpleName() + "-eventLoop"));
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

    private MockServer newServer() {
        originalDrainMillis = ConfigurationProperties.stopDrainMillis();
        return new MockServer(PortFactory.findFreePort());
    }

    @Test
    public void stopReturnsPromptlyWhenNothingInFlight() {
        // given
        ConfigurationProperties.stopDrainMillis(15_000L);
        MockServer mockServer = newServer();
        assertThat(mockServer.getRequestsInFlight(), is(0));

        // when
        long start = System.currentTimeMillis();
        mockServer.stop();
        long elapsed = System.currentTimeMillis() - start;

        // then - no in-flight requests, so drain must not block for the full timeout
        assertThat(mockServer.isRunning(), is(false));
        assertThat("stop with nothing in flight should return promptly, took " + elapsed + "ms",
            elapsed, is(lessThan(10_000L)));
    }

    @Test
    public void stopWaitsForInFlightRequestUntilItCompletes() throws Exception {
        // given - a generous drain timeout so completion (not timeout) ends the wait
        ConfigurationProperties.stopDrainMillis(30_000L);
        MockServer mockServer = newServer();

        // and - simulate one request that has started processing but not finished
        mockServer.requestProcessingStarted();
        assertThat(mockServer.getRequestsInFlight(), is(1));

        // when - stopAsync is triggered while a request is still in flight
        CompletableFuture<String> stopFuture = mockServer.stopAsync();

        // then - stop must NOT complete while the request is still in flight
        try {
            stopFuture.get(750, TimeUnit.MILLISECONDS);
            fail("stop should block while a request is still in flight");
        } catch (TimeoutException expected) {
            // expected - drain is still waiting
        }
        assertThat(mockServer.isRunning(), is(true));

        // when - the in-flight request completes
        mockServer.requestProcessingComplete();
        assertThat(mockServer.getRequestsInFlight(), is(0));

        // then - stop now finishes promptly (drain loop polls at ~10ms)
        stopFuture.get(10, TimeUnit.SECONDS);
        assertThat(mockServer.isRunning(), is(false));
    }

    @Test
    public void stopProceedsAfterDrainTimeoutWhenRequestNeverCompletes() throws Exception {
        // given - a short drain timeout and a request that never completes
        ConfigurationProperties.stopDrainMillis(500L);
        MockServer mockServer = newServer();
        mockServer.requestProcessingStarted();
        assertThat(mockServer.getRequestsInFlight(), is(1));

        // when - stop is triggered; drain should give up after the timeout and shut down anyway
        long start = System.currentTimeMillis();
        mockServer.stopAsync().get(15, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        // then - it waited at least the drain timeout but still completed (did not hang)
        assertThat("should have waited at least the drain timeout, took " + elapsed + "ms",
            elapsed, is(greaterThanOrEqualTo(400L)));
        assertThat(mockServer.isRunning(), is(false));
    }

    @Test
    public void drainDisabledWhenTimeoutZero() throws Exception {
        // given - draining disabled (pre-7.2 behaviour) but a request marked in flight
        ConfigurationProperties.stopDrainMillis(0L);
        MockServer mockServer = newServer();
        mockServer.requestProcessingStarted();
        assertThat(mockServer.getRequestsInFlight(), is(1));

        // when - stop is triggered
        long start = System.currentTimeMillis();
        mockServer.stopAsync().get(10, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        // then - drain disabled, so it does not wait for the in-flight request
        assertThat("drain disabled should not wait, took " + elapsed + "ms",
            elapsed, is(lessThan(5_000L)));
        assertThat(mockServer.isRunning(), is(false));
    }

    @Test
    public void counterReturnsToZeroAfterStartAndComplete() {
        // given
        ConfigurationProperties.stopDrainMillis(15_000L);
        MockServer mockServer = newServer();
        try {
            assertThat(mockServer.getRequestsInFlight(), is(0));

            // when - a number of requests start and complete (as the request path would drive it)
            for (int i = 0; i < 25; i++) {
                mockServer.requestProcessingStarted();
            }
            assertThat(mockServer.getRequestsInFlight(), is(25));
            for (int i = 0; i < 25; i++) {
                mockServer.requestProcessingComplete();
            }

            // then - counter is back to zero
            assertThat(mockServer.getRequestsInFlight(), is(0));
        } finally {
            mockServer.stop();
        }
    }

    @Test
    public void counterNeverGoesNegativeOnDoubleComplete() {
        // given
        MockServer mockServer = newServer();
        try {
            mockServer.requestProcessingStarted();
            assertThat(mockServer.getRequestsInFlight(), is(1));

            // when - completed twice (guard against double-decrement / double callback)
            mockServer.requestProcessingComplete();
            mockServer.requestProcessingComplete();

            // then - counter is clamped at zero, never negative
            assertThat(mockServer.getRequestsInFlight(), is(0));

            // and - a subsequent start still tracks correctly
            mockServer.requestProcessingStarted();
            assertThat(mockServer.getRequestsInFlight(), is(1));
            mockServer.requestProcessingComplete();
            assertThat(mockServer.getRequestsInFlight(), is(0));
        } finally {
            mockServer.stop();
        }
    }

    @Test
    public void drainUnblocksWhenRequestCompletesFromAnotherThread() throws Exception {
        // given - generous timeout, request in flight, completed asynchronously after a delay
        ConfigurationProperties.stopDrainMillis(30_000L);
        MockServer mockServer = newServer();
        mockServer.requestProcessingStarted();

        final AtomicBoolean completed = new AtomicBoolean(false);
        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            completed.set(true);
            mockServer.requestProcessingComplete();
        }, "drain-test-completer");
        completer.setDaemon(true);

        // when
        CompletableFuture<String> stopFuture = mockServer.stopAsync();
        completer.start();

        // then - stop completes only after the async completion fired, and well before the timeout
        stopFuture.get(10, TimeUnit.SECONDS);
        assertThat(completed.get(), is(true));
        assertThat(mockServer.isRunning(), is(false));
    }

    // ---------------------------------------------------------------------------------------------
    // End-to-end tests through a REAL running server and the production request path. These prove
    // the in-flight counter is actually wired into channelRead0 / NettyResponseWriter (not just the
    // public API), so the drain genuinely waits for a real request, and the counter never leaks.
    // ---------------------------------------------------------------------------------------------

    /**
     * The drain must wait for a REAL in-flight request: an expectation with a response delay holds
     * the request open while stop() runs on another thread. stop() must not complete until that
     * delayed response has been written, and the client must still receive it.
     */
    @Test
    public void drainWaitsForRealInFlightRequestWithResponseDelay() throws Exception {
        // given - a generous drain timeout and a server with a slow (delayed) expectation
        ConfigurationProperties.stopDrainMillis(30_000L);
        MockServer mockServer = newServer();
        int port = mockServer.getLocalPort();
        MockServerClient mockServerClient = new MockServerClient("localhost", port);
        try {
            mockServerClient
                .when(request().withPath("/slow"))
                .respond(response().withBody("drained").withDelay(TimeUnit.SECONDS, 2));

            // when - fire the slow request on its own thread (it will be in flight for ~2s)
            final CountDownLatch requestSent = new CountDownLatch(1);
            final AtomicReference<HttpResponse> received = new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread requester = new Thread(() -> {
                try {
                    requestSent.countDown();
                    HttpResponse response = httpClient
                        .sendRequest(request().withPath("/slow").withHeader("Host", "localhost:" + port), new InetSocketAddress("localhost", port))
                        .get(20, TimeUnit.SECONDS);
                    received.set(response);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "drain-real-requester");
            requester.setDaemon(true);
            requester.start();

            // wait until the request is genuinely counted in-flight on the server
            requestSent.await(5, TimeUnit.SECONDS);
            long waitStart = System.currentTimeMillis();
            while (mockServer.getRequestsInFlight() == 0 && System.currentTimeMillis() - waitStart < 5_000) {
                Thread.sleep(5);
            }
            assertThat("request should be in flight before stop", mockServer.getRequestsInFlight(), is(greaterThanOrEqualTo(1)));

            // and - stop on another thread while the delayed response is still pending
            long stopStart = System.currentTimeMillis();
            CompletableFuture<String> stopFuture = mockServer.stopAsync();

            // then - stop blocks until the in-flight request drains (the ~2s delay)
            stopFuture.get(20, TimeUnit.SECONDS);
            long stopElapsed = System.currentTimeMillis() - stopStart;
            assertThat("stop should have waited for the ~2s delayed response to drain, took " + stopElapsed + "ms",
                stopElapsed, is(greaterThanOrEqualTo(1_000L)));

            // and - the client still received the response, and the counter is back to zero
            requester.join(20_000);
            assertThat("requester failed: " + failure.get(), failure.get(), is((Throwable) null));
            assertThat(received.get(), is(org.hamcrest.Matchers.notNullValue()));
            assertThat(received.get().getBodyAsString(), is("drained"));
            assertThat(mockServer.getRequestsInFlight(), is(0));
            assertThat(mockServer.isRunning(), is(false));
        } finally {
            if (mockServer.isRunning()) {
                mockServer.stop();
            }
        }
    }

    /**
     * A normal request through the real server must increment and then decrement the counter, so it
     * returns to zero with no leak (which would otherwise make stop() always wait the full timeout).
     */
    @Test
    public void counterReturnsToZeroAfterRealNormalRequest() throws Exception {
        ConfigurationProperties.stopDrainMillis(15_000L);
        MockServer mockServer = newServer();
        int port = mockServer.getLocalPort();
        MockServerClient mockServerClient = new MockServerClient("localhost", port);
        try {
            mockServerClient
                .when(request().withPath("/fast"))
                .respond(response().withBody("ok"));

            HttpResponse response = httpClient
                .sendRequest(request().withPath("/fast").withHeader("Host", "localhost:" + port), new InetSocketAddress("localhost", port))
                .get(10, TimeUnit.SECONDS);
            assertThat(response.getBodyAsString(), is("ok"));

            // then - the counter must settle back to zero once the response has been dispatched
            long waitStart = System.currentTimeMillis();
            while (mockServer.getRequestsInFlight() != 0 && System.currentTimeMillis() - waitStart < 5_000) {
                Thread.sleep(5);
            }
            assertThat("counter should return to zero after a normal request", mockServer.getRequestsInFlight(), is(0));
        } finally {
            mockServer.stop();
        }
    }

    /**
     * PUT /stop writes its response directly via ctx.writeAndFlush (bypassing NettyResponseWriter),
     * so before the fix its in-flight token was only released when the keep-alive channel closed —
     * meaning the drain triggered by that very /stop would wait the full stopDrainMillis. With the
     * explicit completeInFlight in the /stop branch the token clears promptly, so shutdown completes
     * well before the (large) drain timeout. The connection is kept alive (keepAlive=true) so the
     * channel does not close on its own and only the explicit completion can release the token.
     */
    @Test
    public void stopOverKeepAliveConnectionDrainsPromptly() throws Exception {
        // given - a large drain timeout: if /stop's own token were left in flight, stop() would
        // block for ~30s. With the fix it completes promptly.
        ConfigurationProperties.stopDrainMillis(30_000L);
        MockServer mockServer = newServer();
        int port = mockServer.getLocalPort();
        try {
            assertThat(mockServer.getRequestsInFlight(), is(0));

            // when - send PUT /stop over a keep-alive connection through the real request path
            long start = System.currentTimeMillis();
            HttpResponse response = httpClient
                .sendRequest(
                    request()
                        .withMethod("PUT")
                        .withPath("/mockserver/stop")
                        .withHeader("Host", "localhost:" + port)
                        .withHeader("Connection", "keep-alive"),
                    new InetSocketAddress("localhost", port))
                .get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode(), is(200));

            // then - the server shuts down promptly (well under the 30s drain timeout), proving the
            // /stop request's own in-flight token was completed rather than lingering on the
            // keep-alive connection and stalling the drain it triggered.
            long waitStart = System.currentTimeMillis();
            while (mockServer.isRunning() && System.currentTimeMillis() - waitStart < 10_000) {
                Thread.sleep(10);
            }
            long elapsed = System.currentTimeMillis() - start;
            assertThat("stop over a keep-alive connection should drain promptly, took " + elapsed + "ms",
                elapsed, is(lessThan(15_000L)));
            assertThat(mockServer.isRunning(), is(false));
            assertThat("the /stop request's own in-flight token must clear promptly",
                mockServer.getRequestsInFlight(), is(0));
        } finally {
            if (mockServer.isRunning()) {
                mockServer.stop();
            }
        }
    }

    /**
     * A request whose action drops the connection writes no response, so the decrement is driven by
     * the channel-close safety net rather than NettyResponseWriter. The counter must still return to
     * zero (the exactly-once guard prevents both a leak and a double-decrement).
     */
    @Test
    public void counterReturnsToZeroAfterDroppedRequest() throws Exception {
        ConfigurationProperties.stopDrainMillis(15_000L);
        MockServer mockServer = newServer();
        int port = mockServer.getLocalPort();
        MockServerClient mockServerClient = new MockServerClient("localhost", port);
        try {
            mockServerClient
                .when(request().withPath("/drop"))
                .error(error().withDropConnection(true));

            try {
                httpClient
                    .sendRequest(request().withPath("/drop").withHeader("Host", "localhost:" + port), new InetSocketAddress("localhost", port))
                    .get(10, TimeUnit.SECONDS);
            } catch (Exception expected) {
                // expected - the server dropped the connection without a response
            }

            // then - the close-future net must release the in-flight request so the counter is zero
            long waitStart = System.currentTimeMillis();
            while (mockServer.getRequestsInFlight() != 0 && System.currentTimeMillis() - waitStart < 5_000) {
                Thread.sleep(5);
            }
            assertThat("counter should return to zero after a dropped request", mockServer.getRequestsInFlight(), is(0));
        } finally {
            mockServer.stop();
        }
    }

    // ---------------------------------------------------------------------
    // Configuration-instance precedence for stopDrainMillis.
    //
    // stopDrainMillis previously existed ONLY on the static ConfigurationProperties store, so a
    // value set programmatically or via PUT /mockserver/configuration was silently ignored by the
    // shutdown drain. These tests pin the instance value as authoritative, and pin that the static
    // store is still honoured when the instance leaves the property unset.
    //
    // Each test sets the static store to a value that DIFFERS from the instance value, so a
    // regression to reading the static store is unambiguously observable in the elapsed time.
    // ---------------------------------------------------------------------

    @Test
    public void instanceStopDrainMillisIsHonouredByShutdownDrain() throws Exception {
        // given - a long static budget that must NOT be used, and a short instance budget that must
        originalDrainMillis = ConfigurationProperties.stopDrainMillis();
        ConfigurationProperties.stopDrainMillis(30_000L);
        MockServer mockServer = new MockServer(configuration().stopDrainMillis(500L), PortFactory.findFreePort());

        // and - a request that starts but never completes, so only the timeout can end the drain
        mockServer.requestProcessingStarted();
        assertThat(mockServer.getRequestsInFlight(), is(1));

        // when
        long start = System.currentTimeMillis();
        mockServer.stopAsync().get(15, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        // then - it waited the INSTANCE budget, not the static store's 30s
        assertThat("should have waited at least the instance drain budget, took " + elapsed + "ms",
            elapsed, is(greaterThanOrEqualTo(400L)));
        assertThat("should NOT have waited the static store's 30s budget, took " + elapsed + "ms",
            elapsed, is(lessThan(10_000L)));
        assertThat(mockServer.isRunning(), is(false));
    }

    /**
     * The mirror of the test above: the instance ENABLES draining while the static store disables
     * it. This rules out a false pass from "the shorter value happens to win" — here honouring the
     * instance means waiting LONGER than the static store would.
     */
    @Test
    public void instanceStopDrainMillisIsHonouredWhenStaticStoreDisablesDraining() throws Exception {
        // given - draining disabled in the static store but enabled on the instance
        originalDrainMillis = ConfigurationProperties.stopDrainMillis();
        ConfigurationProperties.stopDrainMillis(0L);
        MockServer mockServer = new MockServer(configuration().stopDrainMillis(1_000L), PortFactory.findFreePort());

        mockServer.requestProcessingStarted();
        assertThat(mockServer.getRequestsInFlight(), is(1));

        // when
        long start = System.currentTimeMillis();
        mockServer.stopAsync().get(15, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        // then - the drain ran for the instance budget; reading the static store would have
        // returned immediately with draining disabled
        assertThat("instance budget must enable draining even though the static store disables it, took " + elapsed + "ms",
            elapsed, is(greaterThanOrEqualTo(800L)));
        assertThat(mockServer.isRunning(), is(false));
    }

    /**
     * Fallback: a Configuration that leaves stopDrainMillis unset must fall through to the static
     * store, so system-property / environment-variable / property-file users are unaffected.
     */
    @Test
    public void staticStopDrainMillisIsHonouredWhenConfigurationLeavesItUnset() throws Exception {
        // given - the static store is the ONLY place the budget is set
        originalDrainMillis = ConfigurationProperties.stopDrainMillis();
        ConfigurationProperties.stopDrainMillis(500L);
        MockServer mockServer = new MockServer(configuration(), PortFactory.findFreePort());

        mockServer.requestProcessingStarted();
        assertThat(mockServer.getRequestsInFlight(), is(1));

        // when
        long start = System.currentTimeMillis();
        mockServer.stopAsync().get(15, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        // then - the static budget still bounds the drain
        assertThat("static store must still bound the drain, took " + elapsed + "ms",
            elapsed, is(greaterThanOrEqualTo(400L)));
        assertThat("static store must not be ignored in favour of a hard-coded default, took " + elapsed + "ms",
            elapsed, is(lessThan(10_000L)));
        assertThat(mockServer.isRunning(), is(false));
    }
}
