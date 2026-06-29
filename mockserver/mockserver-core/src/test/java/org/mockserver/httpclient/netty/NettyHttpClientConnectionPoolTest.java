package org.mockserver.httpclient.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.httpclient.SocketConnectionException;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Behaviour tests for the opt-in upstream connection pool ({@code forwardConnectionPoolEnabled}).
 * <p>
 * A small counting HTTP/1.1 upstream server records how many TCP connections it accepts so the
 * tests can assert that pooling reuses a single keep-alive connection across a burst, that a
 * {@code Connection: close} response is never reused, that a server-closed channel is never reused,
 * and that pooling does not corrupt responses under concurrent same-upstream load.
 */
public class NettyHttpClientConnectionPoolTest {

    private static EventLoopGroup clientEventLoopGroup;
    private CountingUpstreamServer upstream;
    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @BeforeClass
    public static void startEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(NettyHttpClientConnectionPoolTest.class.getSimpleName() + "-eventLoop"));
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    @Before
    public void startUpstream() {
        upstream = new CountingUpstreamServer();
    }

    @After
    public void stopUpstream() {
        if (upstream != null) {
            upstream.stop();
        }
    }

    private NettyHttpClient pooledClient() {
        Configuration configuration = configuration().forwardConnectionPoolEnabled(true);
        return new NettyHttpClient(configuration, mockServerLogger, clientEventLoopGroup, null, false);
    }

    private NettyHttpClient unpooledClient() {
        Configuration configuration = configuration().forwardConnectionPoolEnabled(false);
        return new NettyHttpClient(configuration, mockServerLogger, clientEventLoopGroup, null, false);
    }

    /**
     * A pooled client with the opt-in keep-warm retention enabled and a small maxIdlePerKey (1) so
     * that, were retention NOT raised by keep-warm, surplus channels would be closed back to 1 under
     * concurrent load -> churn. With keep-warm on, channels are retained up to maxTotalPerKey and
     * reused, so a sustained burst reuses a bounded warm set rather than churning fresh connections.
     */
    private NettyHttpClient keepWarmClient() {
        Configuration configuration = configuration()
            .forwardConnectionPoolEnabled(true)
            .forwardConnectionPoolMaxIdlePerKey(1)
            .forwardConnectionPoolKeepAlive(true)
            .forwardConnectionPoolMaxTotalPerKey(64);
        return new NettyHttpClient(configuration, mockServerLogger, clientEventLoopGroup, null, false);
    }

    @Test
    public void shouldReuseSingleConnectionForSequentialBurstWhenPoolingEnabled() throws Exception {
        // given
        NettyHttpClient client = pooledClient();

        // when - 5 sequential requests to the same upstream
        for (int i = 0; i < 5; i++) {
            HttpResponse response = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode(), is(200));
        }

        // then - the upstream accepted only one connection (keep-alive reuse)
        assertThat(upstream.acceptedConnections(), is(1));
    }

    @Test
    public void shouldOpenFreshConnectionPerRequestWhenPoolingDisabled() throws Exception {
        // given
        NettyHttpClient client = unpooledClient();

        // when - 5 sequential requests to the same upstream
        for (int i = 0; i < 5; i++) {
            HttpResponse response = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode(), is(200));
        }

        // then - each request opened its own connection (historical behaviour preserved)
        assertThat(upstream.acceptedConnections(), is(5));
    }

    @Test
    public void shouldNotReuseConnectionWhenUpstreamReturnsConnectionClose() throws Exception {
        // given
        upstream.respondWithConnectionClose();
        NettyHttpClient client = pooledClient();

        // when - 3 sequential requests, each receives Connection: close
        for (int i = 0; i < 3; i++) {
            HttpResponse response = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode(), is(200));
        }

        // then - no reuse: a fresh connection per request
        assertThat(upstream.acceptedConnections(), is(3));
    }

    @Test
    public void shouldNotReuseChannelTheServerClosedAfterResponding() throws Exception {
        // given - upstream sends keep-alive header but then closes the socket
        upstream.closeAfterResponding();
        NettyHttpClient client = pooledClient();

        // when - first request returns and the server then drops the connection
        HttpResponse first = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
            .get(10, TimeUnit.SECONDS);
        assertThat(first.getStatusCode(), is(200));

        // wait for the server side to fully tear down its end of the connection
        assertThat("server connection closed within timeout", upstream.awaitConnectionClosed(10, TimeUnit.SECONDS), is(true));

        // A subsequent request must succeed on a FRESH connection - the dead channel is never reused.
        //
        // There is an inherent race the test must not depend on: the server closing its socket and the
        // CLIENT actually observing the FIN (so the pooled channel reports !isActive()) are independent
        // events. If the second request is acquired and dispatched in the window after the server closed
        // but before the client's event loop has read the FIN, the pool hands back the about-to-die
        // channel; the write then fails as the FIN arrives and the request completes with a clean
        // SocketConnectionException. This is the deliberate, documented production behaviour (no silent
        // auto-retry, to avoid double-sending non-idempotent requests) - the caller is expected to retry.
        //
        // We mirror that contract: retry the second request, tolerating only the clean
        // SocketConnectionException, until it succeeds on a fresh connection. The retry loop is bounded
        // and deterministic - once the client observes the FIN, acquire() discards the dead channel and
        // opens a fresh one, so the loop always terminates quickly without sleeps.
        HttpResponse second = sendUntilFreshConnection(client, upstream.port());
        assertThat(second.getStatusCode(), is(200));

        // then - the server-closed channel was never reused. Had the dead keep-alive channel been reused
        // and the request silently succeeded on it, only one connection would ever have been accepted, so
        // the count is at least 2. The EXACT total is race-dependent (see the comment above): when the
        // second request is dispatched on the dead channel before the client observes the FIN, the
        // failed-then-retried attempt opens an additional fresh connection, so a correct run legitimately
        // accepts 2 - or, when that race fires, 3 - connections. We therefore assert the invariant that
        // matters (never reused => >= 2) rather than the race-sensitive exact count, which previously made
        // this test intermittently fail with "expected 2 but was 3".
        assertThat(upstream.acceptedConnections(), is(greaterThanOrEqualTo(2)));
    }

    /**
     * Sends requests to the upstream until one succeeds on a fresh connection, tolerating only the
     * failures that signal "the pool raced a server-side close on a reused keep-alive channel". This
     * is the documented production contract: when a request is dispatched on a pooled channel that the
     * server has just closed (but whose FIN the client has not yet observed), the request fails cleanly
     * rather than being silently auto-retried (which could double-send a non-idempotent request) - the
     * caller is expected to retry. That failure surfaces either as a {@link SocketConnectionException}
     * (handler removed before a response) or as a {@code Connection reset}/{@code Broken pipe}
     * {@link java.net.SocketException} (the OS rejected the write to the half-closed socket). Both are
     * the same race and are retried here; any other failure is propagated. Bounded so the loop cannot
     * hang if the contract is broken.
     */
    private HttpResponse sendUntilFreshConnection(NettyHttpClient client, int port) throws Exception {
        Throwable lastRace = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                return client.sendRequest(request().withHeader("Host", "127.0.0.1:" + port))
                    .get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (isServerClosedReuseRace(e.getCause())) {
                    // raced the server-side close on the reused channel - retry on a fresh connection
                    lastRace = e.getCause();
                } else {
                    throw e;
                }
            }
        }
        throw new AssertionError("request never succeeded on a fresh connection after a server-closed reuse race", lastRace);
    }

    /**
     * True when the cause is the clean failure produced by dispatching a request on a pooled keep-alive
     * channel the server has already closed - either the pool's own {@link SocketConnectionException} or
     * a {@code Connection reset}/{@code Broken pipe} socket error from writing to the half-closed socket.
     */
    private static boolean isServerClosedReuseRace(Throwable cause) {
        if (cause instanceof SocketConnectionException) {
            return true;
        }
        if (cause instanceof java.net.SocketException) {
            String message = cause.getMessage();
            return message != null
                && (message.contains("Connection reset") || message.contains("Broken pipe") || message.contains("broken pipe"));
        }
        return false;
    }

    @Test
    public void shouldNotCorruptResponsesUnderConcurrentSameUpstreamLoad() throws Exception {
        // given
        NettyHttpClient client = pooledClient();
        int threads = 8;
        int requestsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger failures = new AtomicInteger();

        // when - many concurrent requests to the same upstream
        Future<?>[] futures = new Future<?>[threads];
        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            futures[t] = executor.submit(() -> {
                for (int i = 0; i < requestsPerThread; i++) {
                    try {
                        String marker = "thread-" + threadIndex + "-req-" + i;
                        HttpResponse response = client.sendRequest(
                            request()
                                .withHeader("Host", "127.0.0.1:" + upstream.port())
                                .withHeader("X-Marker", marker)
                        ).get(20, TimeUnit.SECONDS);
                        if (response.getStatusCode() != 200 || !marker.equals(response.getFirstHeader("X-Marker"))) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                }
            });
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // then - every response is correct and correlated to its own request (no cross-talk)
        assertThat("no corrupted/cross-talked responses", failures.get(), is(0));
        // and reuse actually happened: fewer connections were opened than total requests
        assertThat(upstream.acceptedConnections(), lessThan(threads * requestsPerThread));
    }

    /**
     * Opt-in keep-warm retention end-to-end over real sockets. With keep-warm OFF and a small
     * {@code maxIdlePerKey} of 1, a burst of concurrent same-upstream requests would close every
     * channel except one back down on release (the churn): the warm set never grows past 1, so the
     * next burst re-opens connections. With keep-warm ON the released channels are RETAINED up to
     * {@code maxTotalPerKey}, so after the first warm-up burst subsequent sustained bursts reuse the
     * warm set and open NO further connections. The test asserts that the total connections accepted
     * is bounded (does not grow per request) across many sustained bursts -> requests-per-connection
     * is high, i.e. no churn.
     */
    @Test
    public void shouldRetainAndReuseWarmConnectionsUnderSustainedLoadWhenKeepAliveOn() throws Exception {
        // given - keep-warm on with a deliberately tiny maxIdlePerKey (1) so that only keep-warm
        // retention (raising the ceiling to maxTotalPerKey=64) can prevent churn
        NettyHttpClient client = keepWarmClient();
        int concurrency = 8;
        int bursts = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        // when - many sustained bursts of concurrent requests to the same upstream
        for (int burst = 0; burst < bursts; burst++) {
            Future<?>[] futures = new Future<?>[concurrency];
            for (int t = 0; t < concurrency; t++) {
                futures[t] = executor.submit(() -> {
                    try {
                        HttpResponse response = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
                            .get(20, TimeUnit.SECONDS);
                        assertThat(response.getStatusCode(), is(200));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        executor.shutdown();

        // then - the total connections accepted is bounded by the peak concurrency, NOT by the number
        // of requests: the warm set is reused across every burst with no per-request churn. (With
        // keep-warm OFF and maxIdlePerKey=1 this would re-open connections every burst, accepting far
        // more.) We allow a little slack for cold-start scheduling but assert strong reuse.
        int totalRequests = concurrency * bursts;
        assertThat("warm set bounded by concurrency, not by request count",
            upstream.acceptedConnections(), lessThanOrEqualTo(concurrency * 2));
        double requestsPerConnection = (double) totalRequests / upstream.acceptedConnections();
        assertThat("requests-per-connection is high under sustained load (no churn)",
            requestsPerConnection, greaterThan(4.0));
    }

    /**
     * Regression for the connection-pool default-on forward callback/error desync: a malformed,
     * non-HTTP upstream reply (as produced by MockServer's {@code error()} / HttpError action —
     * raw bytes, not a valid HTTP response) must NEVER be returned to the pool. Such a channel's
     * decoder is left corrupted; reusing it silently swallows the next request's response, so the
     * caller blocks until the forward timeout. The fix keeps {@code forwardConnectionPoolEnabled}
     * off by default and, when it IS enabled, refuses to pool a channel whose reply did not parse as
     * a valid HTTP response (status outside 100–599). Here the upstream replies with a single garbage
     * line on its first connection; the SECOND request must therefore open a FRESH connection (the
     * poisoned channel was not reused) and complete normally on the upstream's now-clean responses.
     */
    @Test
    public void shouldNotReuseChannelAfterMalformedNonHttpResponse() throws Exception {
        // given - the upstream returns a non-HTTP garbage line on its first connection only
        MalformedThenValidUpstreamServer rawUpstream = new MalformedThenValidUpstreamServer();
        try {
            NettyHttpClient client = pooledClient();

            // when - first request receives the malformed reply (surfaces as an out-of-range status)
            HttpResponse first = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + rawUpstream.port()))
                .get(10, TimeUnit.SECONDS);
            // the malformed reply is not a clean HTTP response - its status is outside the valid range
            assertThat(first.getStatusCode() == null || first.getStatusCode() < 100 || first.getStatusCode() > 599, is(true));

            // and - a SECOND request must succeed; if the poisoned channel had been pooled and reused
            // this would hang and time out (the regression). A fresh connection is opened instead.
            HttpResponse second = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + rawUpstream.port()))
                .get(10, TimeUnit.SECONDS);

            // then - the second exchange completed cleanly on a fresh connection
            assertThat(second.getStatusCode(), is(200));
            // the malformed channel was never reused, so at least two connections were opened
            assertThat(rawUpstream.acceptedConnections(), is(greaterThanOrEqualTo(2)));
        } finally {
            rawUpstream.stop();
        }
    }

    /**
     * Regression for the "valid in-range status but dirty channel" case that the status-range gate
     * alone does NOT catch. The upstream returns, on its FIRST connection, a perfectly valid
     * {@code 200 OK} whose framing is wrong: it declares {@code Content-Length: 2} but writes extra
     * trailing bytes after the body. The aggregator emits a clean status-200 {@link FullHttpResponse}
     * (so the status gate would happily pool the channel), yet the extra bytes sit undecoded in the
     * client codec's buffer. Reusing that channel would feed the leftover bytes into the parse of the
     * next response — silently swallowing it so the caller blocks until the forward timeout.
     * <p>
     * With the {@link org.mockserver.httpclient.ChannelCleanliness#isQuiescent} gate in place the
     * dirty channel is NOT pooled, so the SECOND request opens a FRESH connection and completes
     * normally. Were the dirty channel reused, this test would hang and time out.
     */
    @Test
    public void shouldNotReuseChannelAfterInRangeStatusButDirtyFraming() throws Exception {
        // given - the upstream returns a valid 200 with trailing junk on its first connection only
        DirtyFramingThenValidUpstreamServer dirtyUpstream = new DirtyFramingThenValidUpstreamServer();
        try {
            NettyHttpClient client = pooledClient();

            // when - first request receives a valid status 200 (the status gate would pool it) but the
            // channel is left dirty by the extra trailing bytes
            HttpResponse first = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + dirtyUpstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(first.getStatusCode(), is(200));

            // and - a SECOND request must succeed; if the dirty channel had been pooled and reused this
            // would hang and time out (the desync). A fresh connection is opened instead.
            HttpResponse second = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + dirtyUpstream.port()))
                .get(10, TimeUnit.SECONDS);

            // then - the second exchange completed cleanly on a fresh connection
            assertThat(second.getStatusCode(), is(200));
            // the dirty channel was never reused, so at least two connections were opened
            assertThat(dirtyUpstream.acceptedConnections(), is(greaterThanOrEqualTo(2)));
        } finally {
            dirtyUpstream.stop();
        }
    }

    /**
     * Clean-channel gate edge case: a {@code 204 No Content} response carries no body and no
     * {@code Content-Length}, so the codec returns to a clean message boundary with zero leftover
     * bytes. {@link org.mockserver.httpclient.ChannelCleanliness#isQuiescent} must therefore report
     * the channel as quiescent and it must be POOLED — three sequential 204s reuse a single
     * connection. This locks the "true" side of the quiescence boundary for a no-body status.
     */
    @Test
    public void shouldReuseChannelAfterNoBody204Response() throws Exception {
        // given - the upstream always replies 204 No Content (no body, keep-alive)
        ScriptedFirstResponseUpstreamServer upstream204 = new ScriptedFirstResponseUpstreamServer(
            "HTTP/1.1 204 No Content\r\n\r\n", true);
        try {
            NettyHttpClient client = pooledClient();

            // when - three sequential requests, each receiving a clean no-body 204
            for (int i = 0; i < 3; i++) {
                HttpResponse response = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream204.port()))
                    .get(10, TimeUnit.SECONDS);
                assertThat(response.getStatusCode(), is(204));
            }

            // then - the no-body channel is clean and was reused: only one connection accepted
            assertThat(upstream204.acceptedConnections(), is(1));
        } finally {
            upstream204.stop();
        }
    }

    /**
     * Clean-channel gate edge case: a chunked {@code 200 OK} with a proper {@code 0\r\n\r\n}
     * terminator leaves the codec at a clean message boundary with zero leftover bytes, so the
     * channel is quiescent and must be POOLED. Three sequential chunked responses reuse one
     * connection. This locks the "true" side of the boundary for chunked transfer-encoding.
     */
    @Test
    public void shouldReuseChannelAfterProperlyTerminatedChunkedResponse() throws Exception {
        // given - a chunked 200 OK with a correct terminating zero-length chunk
        ScriptedFirstResponseUpstreamServer chunkedUpstream = new ScriptedFirstResponseUpstreamServer(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nOK\r\n0\r\n\r\n", true);
        try {
            NettyHttpClient client = pooledClient();

            // when - three sequential requests, each receiving a cleanly-terminated chunked body
            for (int i = 0; i < 3; i++) {
                HttpResponse response = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + chunkedUpstream.port()))
                    .get(10, TimeUnit.SECONDS);
                assertThat(response.getStatusCode(), is(200));
            }

            // then - the cleanly-terminated channel was reused: only one connection accepted
            assertThat(chunkedUpstream.acceptedConnections(), is(1));
        } finally {
            chunkedUpstream.stop();
        }
    }

    /**
     * Clean-channel gate edge case (dirty, "false" side of the boundary): the upstream's FIRST reply
     * declares a {@code Content-Length} LARGER than its intended body and immediately pipelines a
     * second response, so the codec satisfies the inflated length by borrowing the leading bytes of the
     * next response and leaves the remainder as undecoded leftover. The first response still surfaces as
     * a status-200 (so the status-range gate alone would happily pool the channel), but the codec is
     * desynchronised with leftover bytes — {@link org.mockserver.httpclient.ChannelCleanliness} reports
     * it dirty and it must NOT be pooled. The SECOND request therefore opens a FRESH connection; were
     * the desynced channel reused this would hang until the forward timeout. This exercises a wrong
     * (overstated) {@code Content-Length} distinctly from the pure-trailing-junk case.
     */
    @Test
    public void shouldNotReuseChannelAfterWrongContentLengthResponse() throws Exception {
        // given - first reply declares Content-Length 6 but the body is only "OK"; a second response is
        // pipelined immediately so the codec borrows "HTTP" to fill the inflated length, leaving the rest
        // of the second response as undecoded leftover (a desynced, dirty channel)
        ScriptedFirstResponseUpstreamServer wrongLengthUpstream = new ScriptedFirstResponseUpstreamServer(
            "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nOK"
                + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK", false);
        try {
            NettyHttpClient client = pooledClient();

            // when - first request completes (with corrupted framing) but leaves the channel dirty
            HttpResponse first = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + wrongLengthUpstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(first.getStatusCode(), is(200));

            // and - a SECOND request must still succeed on a FRESH connection
            HttpResponse second = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + wrongLengthUpstream.port()))
                .get(10, TimeUnit.SECONDS);

            // then - second exchange completed cleanly; the dirty channel was not reused (>= 2 connections)
            assertThat(second.getStatusCode(), is(200));
            assertThat(wrongLengthUpstream.acceptedConnections(), is(greaterThanOrEqualTo(2)));
        } finally {
            wrongLengthUpstream.stop();
        }
    }

    /**
     * Clean-channel gate edge case (dirty, "false" side of the boundary): the upstream PIPELINES an
     * extra, unsolicited HTTP response after the first one on its FIRST connection. The first response
     * decodes as a clean 200, but the bytes of the SECOND (pipelined) response are left sitting in the
     * codec's cumulation buffer. {@link org.mockserver.httpclient.ChannelCleanliness#isQuiescent} sees
     * the leftover bytes and reports the channel dirty, so it must NOT be pooled — otherwise those
     * leftover bytes would be misread as the reply to the next request, desynchronising the codec. The
     * SECOND request therefore opens a FRESH connection.
     */
    @Test
    public void shouldNotReuseChannelAfterPipelinedExtraResponse() throws Exception {
        // given - first connection writes TWO back-to-back responses (the extra one is leftover)
        ScriptedFirstResponseUpstreamServer pipelinedUpstream = new ScriptedFirstResponseUpstreamServer(
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK"
                + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK", true);
        try {
            NettyHttpClient client = pooledClient();

            // when - first request decodes the first response; the pipelined second response is leftover
            HttpResponse first = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + pipelinedUpstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(first.getStatusCode(), is(200));

            // and - a SECOND request must succeed on a FRESH connection (the dirty channel is not reused)
            HttpResponse second = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + pipelinedUpstream.port()))
                .get(10, TimeUnit.SECONDS);

            // then - second exchange completed cleanly; channel with leftover bytes was not reused
            assertThat(second.getStatusCode(), is(200));
            assertThat(pipelinedUpstream.acceptedConnections(), is(greaterThanOrEqualTo(2)));
        } finally {
            pipelinedUpstream.stop();
        }
    }

    /**
     * Regression for the pooled in-flight read-timeout gap: a REUSED keep-alive connection whose
     * upstream has silently stalled (it answered the first request, was pooled, then never replies to
     * the next) must TIME OUT promptly, not hang. Pooled channels carry no read timeout while idle in
     * the pool (a blanket one would fire during legitimate idle keep-alive), so before the fix a request
     * dispatched on such a stalled-on-reuse connection had nothing to bound it and blocked until the far
     * larger forward future timeout (or indefinitely). The fix arms an in-flight read timeout when a
     * request is dispatched on a pooled channel and removes it again on return to the pool, so the
     * stalled reuse fails via the read timeout in ~{@code maxSocketTimeoutInMillis}. The request still
     * reused the pooled connection (no fresh accept), proving the timeout — not a fresh-connection
     * fallback — is what unblocked it.
     */
    @Test
    public void shouldTimeOutAStalledReusedPooledConnectionInsteadOfHanging() throws Exception {
        // given - upstream answers the first request (so the keep-alive channel is pooled) then never
        // replies to the second request on that same reused connection
        StallOnReusedRequestUpstreamServer stallingUpstream = new StallOnReusedRequestUpstreamServer();
        try {
            // a short socket timeout so the in-flight read timeout fires quickly on the stalled reuse
            Configuration configuration = configuration()
                .forwardConnectionPoolEnabled(true)
                .maxSocketTimeoutInMillis(1500L);
            NettyHttpClient client = new NettyHttpClient(configuration, mockServerLogger, clientEventLoopGroup, null, false);

            // first request succeeds; the keep-alive channel is returned to the pool
            HttpResponse first = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + stallingUpstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(first.getStatusCode(), is(200));
            assertThat("first connection was pooled (only one accepted)", stallingUpstream.acceptedConnections(), is(1));

            // when - the second request reuses that pooled connection but the upstream never responds
            long start = System.currentTimeMillis();
            try {
                client.sendRequest(request().withHeader("Host", "127.0.0.1:" + stallingUpstream.port()))
                    .get(10, TimeUnit.SECONDS);
                org.junit.Assert.fail("expected the stalled reused pooled connection to time out, not hang");
            } catch (ExecutionException timedOut) {
                // then - it failed via the in-flight read timeout, promptly (well under the 10s get bound,
                // and not via the much larger forward future timeout), on the SAME reused connection
                long elapsed = System.currentTimeMillis() - start;
                assertThat("the in-flight read timeout fired promptly", elapsed, lessThan(8000L));
                assertThat("the stalled request reused the pooled connection (no fresh accept)",
                    stallingUpstream.acceptedConnections(), is(1));
            }
        } finally {
            stallingUpstream.stop();
        }
    }

    /**
     * A minimal raw TCP upstream that, on a single accepted keep-alive connection, replies with a
     * clean {@code 200 OK} to the FIRST request (so the client pools the channel) and then NEVER
     * replies to any subsequent request on that same connection (it reads the bytes and stalls),
     * simulating an upstream that has silently gone away on a reused keep-alive connection. Counts
     * accepted connections so the test can assert the stalled request reused the pooled channel rather
     * than opening a fresh one.
     */
    private static final class StallOnReusedRequestUpstreamServer {

        private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        private final AtomicInteger accepted = new AtomicInteger();
        private final Channel serverChannel;

        StallOnReusedRequestUpstreamServer() {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        accepted.incrementAndGet();
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                            private int requestsOnThisConnection;

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
                                requestsOnThisConnection++;
                                if (requestsOnThisConnection == 1) {
                                    // answer the first request and keep the connection alive so it pools
                                    byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                                    FullHttpResponse response = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1,
                                        io.netty.handler.codec.http.HttpResponseStatus.OK,
                                        ctx.alloc().buffer().writeBytes(body)
                                    );
                                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                    ctx.writeAndFlush(response);
                                }
                                // every subsequent request on this reused connection: read and STALL (no reply)
                            }
                        });
                    }
                });
            serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        int acceptedConnections() {
            return accepted.get();
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * A minimal raw TCP upstream that, on its FIRST accepted connection, writes a fixed raw byte
     * script verbatim (allowing exact control over framing — short Content-Length, pipelined extras,
     * chunked terminators, no-body statuses) and optionally keeps the socket open; on every subsequent
     * connection it speaks valid, cleanly-framed HTTP/1.1 (200 OK, keep-alive). Counts accepted
     * connections so a test can assert whether the first channel was reused. This is the flexible
     * sibling of {@link DirtyFramingThenValidUpstreamServer} / {@link MalformedThenValidUpstreamServer}
     * for the clean-channel gate edge cases.
     */
    private static final class ScriptedFirstResponseUpstreamServer {

        private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        private final AtomicInteger accepted = new AtomicInteger();
        private final Channel serverChannel;
        private final String firstResponseScript;
        private final boolean replyToEveryRequestOnFirstConnection;

        /**
         * @param firstResponseScript raw bytes written verbatim in response to the first request on the
         *                             first connection
         * @param replyOncePerRequest when true the script is (re)written for every request received on
         *                             the first connection (so a CLEAN keep-alive channel can serve a
         *                             whole sequential burst from one connection); when false the script
         *                             is written once and the socket left as-is
         */
        ScriptedFirstResponseUpstreamServer(String firstResponseScript, boolean replyOncePerRequest) {
            this.firstResponseScript = firstResponseScript;
            this.replyToEveryRequestOnFirstConnection = replyOncePerRequest;
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        boolean firstConnection = accepted.incrementAndGet() == 1;
                        if (firstConnection) {
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<io.netty.buffer.ByteBuf>() {
                                private boolean written;

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, io.netty.buffer.ByteBuf msg) {
                                    if (replyToEveryRequestOnFirstConnection || !written) {
                                        written = true;
                                        ctx.writeAndFlush(Unpooled.copiedBuffer(firstResponseScript, StandardCharsets.UTF_8));
                                    }
                                }
                            });
                        } else {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
                                    byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                                    FullHttpResponse response = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1,
                                        io.netty.handler.codec.http.HttpResponseStatus.OK,
                                        ctx.alloc().buffer().writeBytes(body)
                                    );
                                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                    ctx.writeAndFlush(response);
                                }
                            });
                        }
                    }
                });
            serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        int acceptedConnections() {
            return accepted.get();
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * A minimal raw TCP upstream that, on its FIRST accepted connection, writes a valid
     * {@code HTTP/1.1 200 OK} response whose declared {@code Content-Length} is shorter than the bytes
     * it actually writes (leaving trailing junk undecoded on the channel) and keeps the socket open;
     * on every subsequent connection it speaks valid, cleanly-framed HTTP/1.1. Counts accepted
     * connections so the test can assert the dirty channel was not reused.
     */
    private static final class DirtyFramingThenValidUpstreamServer {

        private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        private final AtomicInteger accepted = new AtomicInteger();
        private final Channel serverChannel;

        DirtyFramingThenValidUpstreamServer() {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        boolean firstConnection = accepted.incrementAndGet() == 1;
                        if (firstConnection) {
                            // Valid status line + headers (so the aggregator emits a clean 200), but
                            // Content-Length: 2 understates the body, leaving "EXTRA" undecoded on the
                            // channel. The socket stays open (keep-alive) so the channel would be a
                            // pool candidate were it not for the leftover-bytes cleanliness gate.
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<io.netty.buffer.ByteBuf>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, io.netty.buffer.ByteBuf msg) {
                                    ctx.writeAndFlush(Unpooled.copiedBuffer(
                                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOKEXTRA",
                                        StandardCharsets.UTF_8));
                                }
                            });
                        } else {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
                                    byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                                    FullHttpResponse response = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1,
                                        io.netty.handler.codec.http.HttpResponseStatus.OK,
                                        ctx.alloc().buffer().writeBytes(body)
                                    );
                                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                    ctx.writeAndFlush(response);
                                }
                            });
                        }
                    }
                });
            serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        int acceptedConnections() {
            return accepted.get();
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * A minimal raw TCP upstream that, on its FIRST accepted connection, replies with a single
     * non-HTTP garbage line and keeps the socket open (mimicking an {@code error()} raw-bytes reply
     * on a keep-alive connection); on every subsequent connection it speaks valid HTTP/1.1. Counts
     * accepted connections so the test can assert the poisoned channel was not reused.
     */
    private static final class MalformedThenValidUpstreamServer {

        private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        private final AtomicInteger accepted = new AtomicInteger();
        private final Channel serverChannel;

        MalformedThenValidUpstreamServer() {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        boolean firstConnection = accepted.incrementAndGet() == 1;
                        if (firstConnection) {
                            // Raw, non-HTTP reply: a single garbage line, no HTTP framing, socket
                            // stays open. This is the shape of an HttpError raw-bytes response.
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<io.netty.buffer.ByteBuf>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, io.netty.buffer.ByteBuf msg) {
                                    ctx.writeAndFlush(Unpooled.copiedBuffer("some_random_bytes\r\n", StandardCharsets.UTF_8));
                                }
                            });
                        } else {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
                                    byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                                    FullHttpResponse response = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1,
                                        io.netty.handler.codec.http.HttpResponseStatus.OK,
                                        ctx.alloc().buffer().writeBytes(body)
                                    );
                                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                    ctx.writeAndFlush(response);
                                }
                            });
                        }
                    }
                });
            serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        int acceptedConnections() {
            return accepted.get();
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * A minimal HTTP/1.1 upstream that counts accepted TCP connections and echoes the request's
     * {@code X-Marker} header back. By default it keeps connections alive; it can be configured to
     * send {@code Connection: close} on every response, or to close the socket after the response on
     * the FIRST connection only (so the replacement connection is stably reusable).
     */
    private static final class CountingUpstreamServer {

        private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        private final AtomicInteger accepted = new AtomicInteger();
        private final CountDownLatch connectionClosedLatch = new CountDownLatch(1);
        private final Channel serverChannel;
        private volatile boolean connectionClose;
        /**
         * When set, the upstream sends a keep-alive header then closes the socket after responding,
         * but does so for the FIRST accepted connection only. This deterministically exercises
         * "server closed a pooled keep-alive channel" without making every replacement connection
         * close too (which would make the accepted-connection count non-deterministic under retries).
         */
        private final java.util.concurrent.atomic.AtomicBoolean closeNextAfterResponding = new java.util.concurrent.atomic.AtomicBoolean(false);

        CountingUpstreamServer() {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        accepted.incrementAndGet();
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                connectionClosedLatch.countDown();
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
                                byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                                FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    io.netty.handler.codec.http.HttpResponseStatus.OK,
                                    ctx.alloc().buffer().writeBytes(body)
                                );
                                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                                String marker = msg.headers().get("X-Marker");
                                if (marker != null) {
                                    response.headers().set("X-Marker", marker);
                                }
                                // close-after-responding fires once (for the first connection that
                                // serves a request); the replacement connection then stays keep-alive
                                boolean closeThisConnection = closeNextAfterResponding.getAndSet(false);
                                boolean close = connectionClose || closeThisConnection;
                                response.headers().set(
                                    HttpHeaderNames.CONNECTION,
                                    connectionClose ? HttpHeaderValues.CLOSE : HttpHeaderValues.KEEP_ALIVE
                                );
                                if (close) {
                                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                                } else {
                                    ctx.writeAndFlush(response);
                                }
                            }
                        });
                    }
                });
            serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        int acceptedConnections() {
            return accepted.get();
        }

        boolean awaitConnectionClosed(long timeout, TimeUnit unit) throws InterruptedException {
            return connectionClosedLatch.await(timeout, unit);
        }

        void respondWithConnectionClose() {
            this.connectionClose = true;
        }

        void closeAfterResponding() {
            this.closeNextAfterResponding.set(true);
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }
}
