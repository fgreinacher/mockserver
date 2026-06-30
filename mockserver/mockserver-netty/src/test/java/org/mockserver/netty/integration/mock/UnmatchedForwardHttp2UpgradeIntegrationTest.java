package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * End-to-end check of the {@code forwardProxyHttp2Upgrade} feature on the <em>unmatched / transparent-proxy</em>
 * forward path — i.e. the path the opencode CLI actually hits (an HTTP/1.1 secure request that matches no
 * expectation, so MockServer transparently forwards it upstream via
 * {@link org.mockserver.mock.action.http.HttpActionHandler}'s unmatched-forward logic).
 * <p>
 * Topology: an HTTP/1.1 client -> a "forward" MockServer in <b>port-forwarding mode</b> (no expectations, so
 * every request is transparently forwarded to a fixed remote) -> a TLS "upstream" MockServer that holds
 * protocol-discriminated expectations. The upstream's server pipeline stamps each received request with the
 * ALPN-negotiated protocol, so an expectation keyed on {@code withProtocol(HTTP_2)} vs {@code withProtocol(HTTP_1_1)}
 * is a trusted proof of which protocol the forward actually spoke to the upstream.
 * <p>
 * This is the sibling of {@link ForwardHttp2UpstreamIntegrationTest} (which covers the <em>matched</em>
 * {@code forward()} action path and {@code forwardProxyHttp2Enabled}); here the request is never matched, so it
 * exercises the unmatched-forward upgrade introduced for streaming SSE backends.
 * <ul>
 *   <li>flag ON  + HTTP/1.1 secure inbound -> the forward to the TLS upstream is upgraded to HTTP/2.</li>
 *   <li>flag OFF + HTTP/1.1 secure inbound -> the forward stays HTTP/1.1 (default behaviour, no upgrade).</li>
 *   <li>flag ON  + HTTP/1.1 <em>non-secure</em> inbound -> the forward stays HTTP/1.1 (upgrade is TLS-only).</li>
 * </ul>
 *
 * @author jamesdbloom
 */
public class UnmatchedForwardHttp2UpgradeIntegrationTest {

    private static EventLoopGroup clientEventLoopGroup;
    private static MockServer upstreamServer;
    private static MockServerClient upstreamClient;

    @BeforeClass
    public static void setupFixture() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(UnmatchedForwardHttp2UpgradeIntegrationTest.class.getSimpleName() + "-eventLoop"));
        // a TLS upstream (MockServer) that negotiates HTTP/1.1 or HTTP/2 via ALPN and records the
        // protocol it actually received on each request, so protocol-discriminated expectations work
        upstreamServer = new MockServer();
        upstreamClient = new MockServerClient("localhost", upstreamServer.getLocalPort());
    }

    @AfterClass
    public static void stopFixture() {
        stopQuietly(upstreamClient);
        stopQuietly(upstreamServer);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Before
    public void reset() {
        upstreamClient.reset();
        // protocol-discriminated expectations on the upstream: the response body proves which protocol
        // the forwarded request used to reach the upstream
        upstreamClient
            .when(request().withPath("/forwarded").withProtocol(Protocol.HTTP_1_1))
            .respond(response().withStatusCode(200).withBody("upstream_saw_http1"));
        upstreamClient
            .when(request().withPath("/forwarded").withProtocol(Protocol.HTTP_2))
            .respond(response().withStatusCode(201).withBody("upstream_saw_http2"));
    }

    /**
     * Send an HTTP/1.1 inbound request that matches NO expectation on the forward server, so it flows through
     * the unmatched / transparent-proxy forward path. The forward server is in port-forwarding mode, so it
     * forwards to the fixed upstream regardless of the Host header (which points at the forward server itself
     * just so the client connects there).
     */
    private HttpResponse forwardUnmatched(MockServer forwardServer, boolean secure) throws Exception {
        return new NettyHttpClient(
            configuration(),
            new MockServerLogger(),
            clientEventLoopGroup,
            null,
            false
        ).sendRequest(
            request()
                .withMethod("GET")
                .withPath("/forwarded")
                .withSecure(secure)
                .withProtocol(Protocol.HTTP_1_1)
                .withHeader(HOST.toString(), "127.0.0.1:" + forwardServer.getLocalPort())
        ).get(15, SECONDS);
    }

    private MockServer startForwardServer(boolean forwardProxyHttp2Upgrade) {
        // port-forwarding mode: no expectations, so every request is transparently forwarded to the
        // upstream via the unmatched-forward path (NOT a matched forward() action)
        return new MockServer(
            configuration().forwardProxyHttp2Upgrade(forwardProxyHttp2Upgrade),
            upstreamServer.getLocalPort(),
            "127.0.0.1"
        );
    }

    @Test(timeout = 30000)
    public void shouldUpgradeUnmatchedSecureHttp1ToHttp2WhenFlagEnabled() throws Exception {
        // given - the upgrade flag is on
        MockServer forwardServer = startForwardServer(true);
        try {
            // when - an HTTP/1.1 secure request that matches no expectation is transparently forwarded
            HttpResponse response = forwardUnmatched(forwardServer, true);

            // then - the unmatched forward was upgraded to HTTP/2, so the upstream matched the HTTP/2 expectation
            assertThat(response.getStatusCode(), is(201));
            assertThat(response.getBodyAsString(), is("upstream_saw_http2"));
            upstreamClient.verify(request().withPath("/forwarded").withProtocol(Protocol.HTTP_2), exactly(1));
        } finally {
            stopQuietly(forwardServer);
        }
    }

    @Test(timeout = 30000)
    public void shouldNotUpgradeUnmatchedSecureHttp1WhenFlagDisabled() throws Exception {
        // given - default behaviour: the upgrade flag is off
        MockServer forwardServer = startForwardServer(false);
        try {
            // when - the same HTTP/1.1 secure unmatched request is forwarded
            HttpResponse response = forwardUnmatched(forwardServer, true);

            // then - no upgrade, so the upstream matched the HTTP/1.1 expectation
            assertThat(response.getStatusCode(), is(200));
            assertThat(response.getBodyAsString(), is("upstream_saw_http1"));
            upstreamClient.verify(request().withPath("/forwarded").withProtocol(Protocol.HTTP_1_1), exactly(1));
        } finally {
            stopQuietly(forwardServer);
        }
    }

    @Test(timeout = 30000)
    public void shouldNotUpgradeUnmatchedNonSecureHttp1WhenFlagEnabled() throws Exception {
        // given - the upgrade flag is on but the inbound request is NOT secure (HTTP/2 requires TLS+ALPN)
        MockServer forwardServer = startForwardServer(true);
        try {
            // when - a non-secure HTTP/1.1 unmatched request is forwarded
            HttpResponse response = forwardUnmatched(forwardServer, false);

            // then - the upgrade is TLS-only, so the non-secure forward stays HTTP/1.1
            assertThat(response.getStatusCode(), is(200));
            assertThat(response.getBodyAsString(), is("upstream_saw_http1"));
            upstreamClient.verify(request().withPath("/forwarded").withProtocol(Protocol.HTTP_1_1), exactly(1));
        } finally {
            stopQuietly(forwardServer);
        }
    }
}
