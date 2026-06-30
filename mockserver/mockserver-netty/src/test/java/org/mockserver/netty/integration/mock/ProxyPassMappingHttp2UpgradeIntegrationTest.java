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
import org.mockserver.model.ProxyPassMapping;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import java.util.Collections;

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
 * End-to-end check of the {@code forwardProxyHttp2Upgrade} feature on the <em>proxyPassMappings reverse-proxy</em>
 * forward path — i.e. the path taken when an inbound request matches no expectation but its path matches a
 * configured {@link ProxyPassMapping} prefix, so MockServer reverse-proxies it to the mapped upstream via
 * {@link org.mockserver.mock.action.http.HttpActionHandler}'s {@code handleProxyPass} logic.
 * <p>
 * Topology: an HTTP/1.1 client -> a "proxy-pass" MockServer (configured only with a {@code proxyPassMappings}
 * entry, no expectations) -> a TLS "upstream" MockServer that holds protocol-discriminated expectations. The
 * upstream's server pipeline stamps each received request with the ALPN-negotiated protocol, so an expectation
 * keyed on {@code withProtocol(HTTP_2)} vs {@code withProtocol(HTTP_1_1)} is a trusted proof of which protocol
 * the proxy-pass forward actually spoke to the upstream.
 * <p>
 * This is the sibling of {@link ForwardHttp2UpstreamIntegrationTest} (matched {@code forward()} action) and
 * {@link UnmatchedForwardHttp2UpgradeIntegrationTest} (transparent/unmatched forward); here the request is
 * reverse-proxied via an explicit path-prefix mapping, exercising the proxy-pass upgrade for streaming SSE
 * backends reached through a {@code proxyPassMappings} route (e.g. the OpenAI Codex endpoint).
 * <ul>
 *   <li>flag ON  + HTTPS target (secure) -> the proxy-pass forward to the upstream is upgraded to HTTP/2.</li>
 *   <li>flag OFF + HTTPS target           -> the proxy-pass forward stays HTTP/1.1 (default behaviour).</li>
 *   <li>flag ON  + HTTP target (non-secure) -> the proxy-pass forward stays HTTP/1.1 (upgrade is TLS-only).</li>
 * </ul>
 *
 * @author jamesdbloom
 */
public class ProxyPassMappingHttp2UpgradeIntegrationTest {

    private static EventLoopGroup clientEventLoopGroup;
    private static MockServer upstreamServer;
    private static MockServerClient upstreamClient;

    @BeforeClass
    public static void setupFixture() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(ProxyPassMappingHttp2UpgradeIntegrationTest.class.getSimpleName() + "-eventLoop"));
        // a TLS-capable upstream (MockServer) that negotiates HTTP/1.1 or HTTP/2 via ALPN and records the
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
        // the proxy-passed request used to reach the upstream
        upstreamClient
            .when(request().withPath("/forwarded").withProtocol(Protocol.HTTP_1_1))
            .respond(response().withStatusCode(200).withBody("upstream_saw_http1"));
        upstreamClient
            .when(request().withPath("/forwarded").withProtocol(Protocol.HTTP_2))
            .respond(response().withStatusCode(201).withBody("upstream_saw_http2"));
    }

    /**
     * Send an HTTP/1.1 inbound request whose path matches the proxy-pass prefix ("/proxy") but matches NO
     * expectation on the proxy-pass server, so it flows through the {@code handleProxyPass} reverse-proxy path
     * and is forwarded to the mapped upstream. The Host header points at the proxy-pass server itself just so
     * the client connects there; the mapping (not the Host header) selects the upstream.
     */
    private HttpResponse proxyPassThrough(MockServer proxyPassServer) throws Exception {
        return new NettyHttpClient(
            configuration(),
            new MockServerLogger(),
            clientEventLoopGroup,
            null,
            false
        ).sendRequest(
            request()
                .withMethod("GET")
                .withPath("/proxy/forwarded")
                .withSecure(false)
                .withProtocol(Protocol.HTTP_1_1)
                .withHeader(HOST.toString(), "127.0.0.1:" + proxyPassServer.getLocalPort())
        ).get(15, SECONDS);
    }

    private MockServer startProxyPassServer(boolean forwardProxyHttp2Upgrade, boolean targetSecure) {
        // map the "/proxy" prefix to the upstream; an HTTPS targetUri marks the target as secure
        // (ProxyPassMapping.isTargetSecure() == true), an HTTP targetUri marks it non-secure
        String scheme = targetSecure ? "https" : "http";
        ProxyPassMapping mapping = ProxyPassMapping.proxyPass("/proxy", scheme + "://127.0.0.1:" + upstreamServer.getLocalPort());
        return new MockServer(
            configuration()
                .forwardProxyHttp2Upgrade(forwardProxyHttp2Upgrade)
                .proxyPassMappings(Collections.singletonList(mapping))
        );
    }

    @Test(timeout = 30000)
    public void shouldUpgradeProxyPassSecureForwardToHttp2WhenFlagEnabled() throws Exception {
        // given - the upgrade flag is on and the proxy-pass target is HTTPS (secure)
        MockServer proxyPassServer = startProxyPassServer(true, true);
        try {
            // when - an inbound request is reverse-proxied to the secure upstream via the mapping
            HttpResponse response = proxyPassThrough(proxyPassServer);

            // then - the proxy-pass forward was upgraded to HTTP/2, so the upstream matched the HTTP/2 expectation
            // (without the fix this proxy-pass route stayed on HTTP/1.1 and the upstream would have seen HTTP/1.1)
            assertThat(response.getStatusCode(), is(201));
            assertThat(response.getBodyAsString(), is("upstream_saw_http2"));
            upstreamClient.verify(request().withPath("/forwarded").withProtocol(Protocol.HTTP_2), exactly(1));
        } finally {
            stopQuietly(proxyPassServer);
        }
    }

    @Test(timeout = 30000)
    public void shouldNotUpgradeProxyPassSecureForwardWhenFlagDisabled() throws Exception {
        // given - default behaviour: the upgrade flag is off (target still HTTPS)
        MockServer proxyPassServer = startProxyPassServer(false, true);
        try {
            // when - the same request is reverse-proxied with the flag off
            HttpResponse response = proxyPassThrough(proxyPassServer);

            // then - no upgrade, so the upstream matched the HTTP/1.1 expectation
            assertThat(response.getStatusCode(), is(200));
            assertThat(response.getBodyAsString(), is("upstream_saw_http1"));
            upstreamClient.verify(request().withPath("/forwarded").withProtocol(Protocol.HTTP_1_1), exactly(1));
        } finally {
            stopQuietly(proxyPassServer);
        }
    }

    @Test(timeout = 30000)
    public void shouldNotUpgradeProxyPassNonSecureForwardWhenFlagEnabled() throws Exception {
        // given - the upgrade flag is on but the proxy-pass target is HTTP (non-secure); HTTP/2 requires TLS+ALPN
        MockServer proxyPassServer = startProxyPassServer(true, false);
        try {
            // when - the request is reverse-proxied to the non-secure upstream
            HttpResponse response = proxyPassThrough(proxyPassServer);

            // then - the upgrade is TLS-only, so the non-secure proxy-pass forward stays HTTP/1.1
            assertThat(response.getStatusCode(), is(200));
            assertThat(response.getBodyAsString(), is("upstream_saw_http1"));
            upstreamClient.verify(request().withPath("/forwarded").withProtocol(Protocol.HTTP_1_1), exactly(1));
        } finally {
            stopQuietly(proxyPassServer);
        }
    }
}
