package org.mockserver.netty.integration.proxy.http;

import com.google.common.collect.ImmutableList;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.scheduler.Scheduler;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * Exercises HTTP/2 requests made through MockServer's own HTTPS forward-proxy (HTTP CONNECT) port.
 * Before issue #2260 was fixed an HTTP/2 request through the CONNECT proxy hung for ~30s and then
 * emitted a GOAWAY; the same request over HTTP/1.1 worked. The {@code @Test(timeout = ...)} guard
 * plus the bounded {@code .get(15, SECONDS)} make the regression fail fast rather than stall.
 *
 * @author jamesdbloom
 */
public class NettyHttpsProxyHttp2IntegrationTest {

    private static int mockServerPort;
    private static EchoServer secureEchoServer;
    private static MockServerClient mockServerClient;
    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void setupFixture() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(NettyHttpsProxyHttp2IntegrationTest.class.getSimpleName() + "-eventLoop"));
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
        secureEchoServer = new EchoServer(true);
    }

    @AfterClass
    public static void stopFixture() {
        stopQuietly(secureEchoServer);
        stopQuietly(mockServerClient);
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Before
    public void reset() {
        mockServerClient.reset();
        secureEchoServer.mockServerEventLog().reset();
    }

    private HttpResponse sendViaConnectProxy(HttpRequest request) throws Exception {
        return new NettyHttpClient(
            configuration(),
            new MockServerLogger(),
            clientEventLoopGroup,
            ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.HTTPS, "127.0.0.1:" + mockServerPort)),
            false
        ).sendRequest(request).get(15, SECONDS);
    }

    @Test(timeout = 30000)
    public void shouldReturnMockedResponseForHttp2RequestViaConnectProxy() throws Exception {
        // given - protocol discriminated expectations so the response body proves which protocol matched
        mockServerClient
            .when(request().withPath("/mocked").withProtocol(Protocol.HTTP_1_1))
            .respond(response().withStatusCode(200).withBody("mocked_via_http1"));
        mockServerClient
            .when(request().withPath("/mocked").withProtocol(Protocol.HTTP_2))
            .respond(response().withStatusCode(201).withBody("mocked_via_http2"));

        // when - an HTTP/2 request is made through MockServer's HTTPS CONNECT proxy
        HttpResponse response = sendViaConnectProxy(
            request()
                .withMethod("GET")
                .withPath("/mocked")
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withHeader(HOST.toString(), "127.0.0.1:" + secureEchoServer.getPort())
        );

        // then - the HTTP/2 expectation matched, proving h2 was negotiated end to end through the tunnel
        assertThat(response.getStatusCode(), is(201));
        assertThat(response.getBodyAsString(), is("mocked_via_http2"));
        mockServerClient.verify(request().withPath("/mocked"), exactly(1));
    }

    @Test(timeout = 30000)
    public void shouldForwardHttp2RequestViaConnectProxyToSecureTarget() throws Exception {
        // given - no expectation, so MockServer proxies the request through to the target echo server

        // when
        HttpResponse response = sendViaConnectProxy(
            request()
                .withMethod("POST")
                .withPath("/forwarded_h2")
                .withBody("an_example_http2_body")
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withHeader(HOST.toString(), "127.0.0.1:" + secureEchoServer.getPort())
        );

        // then - the echo server received and echoed the forwarded request
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("an_example_http2_body"));
        mockServerClient.verify(request().withPath("/forwarded_h2").withBody("an_example_http2_body"), exactly(1));
    }

    @Test(timeout = 30000)
    public void shouldForwardHttp2RequestWithLargeBodyViaConnectProxy() throws Exception {
        // given - a body large enough to span multiple HTTP/2 DATA frames in both directions
        String largeBody = StringUtils.repeat("abcdefghij", 5000);

        // when
        HttpResponse response = sendViaConnectProxy(
            request()
                .withMethod("POST")
                .withPath("/large_h2")
                .withBody(largeBody)
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withHeader(HOST.toString(), "127.0.0.1:" + secureEchoServer.getPort())
        );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is(largeBody));
    }

    @Test(timeout = 30000)
    public void shouldHandleMultipleSequentialHttp2RequestsViaConnectProxy() throws Exception {
        for (int i = 0; i < 3; i++) {
            HttpResponse response = sendViaConnectProxy(
                request()
                    .withMethod("POST")
                    .withPath("/sequential_h2")
                    .withBody("body_" + i)
                    .withSecure(true)
                    .withProtocol(Protocol.HTTP_2)
                    .withHeader(HOST.toString(), "127.0.0.1:" + secureEchoServer.getPort())
            );

            assertThat(response.getStatusCode(), is(200));
            assertThat(response.getBodyAsString(), is("body_" + i));
        }
    }

    @Test(timeout = 30000)
    public void shouldForwardHttp2RequestViaConnectProxyToHttp2Upstream() throws Exception {
        // given - a proxy MockServer whose forward leg PRESERVES HTTP/2 (forwardProxyHttp2Enabled), so
        // BOTH legs are HTTP/2: the client -> proxy CONNECT tunnel AND the proxy -> upstream forward. The
        // default proxy in the other forward tests leaves this flag off, so its forward leg drops to
        // HTTP/1.1 and Netty's InboundHttp2ToHttpAdapter never injects the synthetic x-http2-stream-id
        // extension header that this fix strips (see FullHttpResponseToMockServerHttpResponse and its unit
        // tests, which are the airtight regression lock for the leak). This end-to-end case guards the
        // both-legs-HTTP/2 CONNECT-proxy path in general.
        //
        // NOTE: this IT does NOT by itself reproduce the original hang. The hang requires the inbound
        // client stream id to DIVERGE from the upstream forward-leg stream id (e.g. inbound stream 5 vs
        // upstream stream 3). With NettyHttpClient each request opens a fresh connection, so both legs
        // always land on the same first-stream id and the leaked id coincidentally matches; the mismatch
        // (and PROTOCOL_ERROR / GOAWAY) only appears when several requests share one inbound HTTP/2
        // connection against fresh forward legs, which this client does not do. The unit-level write-back
        // test locks that root cause deterministically. The @Test(timeout) plus bounded .get(15, SECONDS)
        // still make any regression fail fast rather than stall.
        MockServer http2ForwardProxy = new MockServer(configuration().forwardProxyHttp2Enabled(true));
        try {
            // when - an HTTP/2 request is proxied (no expectation => transparent forward) to the secure
            // h2-capable echo upstream
            HttpResponse response = new NettyHttpClient(
                configuration(),
                new MockServerLogger(),
                clientEventLoopGroup,
                ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.HTTPS, "127.0.0.1:" + http2ForwardProxy.getLocalPort())),
                false
            ).sendRequest(
                request()
                    .withMethod("POST")
                    .withPath("/forwarded_h2_both_legs")
                    .withBody("an_example_h2_both_legs_body")
                    .withSecure(true)
                    .withProtocol(Protocol.HTTP_2)
                    .withHeader(HOST.toString(), "127.0.0.1:" + secureEchoServer.getPort())
            ).get(15, SECONDS);

            // then - the HTTP/2 client received the real echoed 200 body within the timeout, i.e. the
            // response was written back on the correct (inbound) stream and no leaked upstream stream id
            // corrupted the client leg
            assertThat(response.getStatusCode(), is(200));
            assertThat(response.getBodyAsString(), is("an_example_h2_both_legs_body"));
        } finally {
            stopQuietly(http2ForwardProxy);
        }
    }

    @Test(timeout = 30000)
    public void shouldForwardHttp1RequestViaConnectProxyToSecureTarget() throws Exception {
        // when - the same driver but forced to HTTP/1.1, to confirm the relay still works for h1
        HttpResponse response = sendViaConnectProxy(
            request()
                .withMethod("POST")
                .withPath("/forwarded_h1")
                .withBody("an_example_http1_body")
                .withSecure(true)
                .withProtocol(Protocol.HTTP_1_1)
                .withHeader(HOST.toString(), "127.0.0.1:" + secureEchoServer.getPort())
        );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("an_example_http1_body"));
        mockServerClient.verify(request().withPath("/forwarded_h1").withBody("an_example_http1_body"), exactly(1));
    }
}
