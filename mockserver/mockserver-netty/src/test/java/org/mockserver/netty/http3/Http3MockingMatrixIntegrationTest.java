package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpClassCallback.callback;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Runs the core mocking-action matrix — respond, forward, forward-overridden-request, class-callback
 * and error — over real HTTP/3 (QUIC), driven by a live Netty {@link Http3} client, asserting on the
 * bytes the client actually receives on its own request stream.
 * <p>
 * Motivation (test-coverage audit gap #4): the existing HTTP/3 integration tests cover trace-context
 * propagation, mTLS client-certificate capture, gRPC, streaming, MCP and lifecycle — but none drives
 * the forward / forwardOverride / callback / error action matrix over QUIC, and none asserts that the
 * forwarded/callback response <em>body</em> reaches the client over HTTP/3. The shared integration
 * harness ({@code AbstractExtendedNettyMockingIntegrationTest} and friends) cannot cover this because
 * its {@code NettyHttpClient} has no HTTP/3 request path, so a subclass would silently assert HTTP/1.1
 * or HTTP/2 behaviour while claiming HTTP/3 coverage. This focused IT instead uses the same real Netty
 * QUIC client the other HTTP/3 tests use and asserts the response body arrived on the client stream.
 * <p>
 * This is the HTTP/3 sibling of {@link org.mockserver.netty.integration.mock.H2cMockingMatrixIntegrationTest}
 * and of gRPC issue #2419: a server that writes a response without routing it back onto the request's
 * own stream logs success while the client's stream receives an empty body — a body-received assertion
 * over a real QUIC stream is what catches that class of defect.
 * <p>
 * Docker/QUIC-gated exactly like the other HTTP/3 integration tests via {@link #assumeQuicAvailable()}
 * so it SKIPS cleanly (rather than errors) on any platform without the native QUIC transport
 * (BoringSSL). In CI the {@code assert-suite-ran.sh} surefire guard fails the build loudly if a
 * QUIC-capable agent skips the whole suite, so a fail-safe skip off-CI does not become a silent
 * false-green on-CI.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3MockingMatrixIntegrationTest {

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer upstreamServer;
    private static MockServerClient upstreamClient;
    private static int http3Port;

    private NioEventLoopGroup clientGroup;

    @BeforeClass
    public static void startServer() {
        // upstream target for the forward / forward-overridden actions (plain HTTP/1.1 over TCP)
        upstreamServer = new MockServer();
        upstreamClient = new MockServerClient("127.0.0.1", upstreamServer.getLocalPort());

        // front MockServer with HTTP/3 (QUIC) enabled on an ephemeral UDP port
        int udpPort = org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .http3MaxIdleTimeout(30000L);
        mockServer = new MockServer(config, 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        http3Port = mockServer.getHttp3Port();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        stopQuietly(upstreamClient);
        stopQuietly(upstreamServer);
    }

    @Before
    public void reset() {
        assumeQuicAvailable();
        // http3Port(0) means "HTTP/3 disabled"; if the H3 server never started there is nothing to
        // drive over QUIC, so skip rather than assert against a server that is not listening.
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);
        mockServerClient.reset();
        upstreamClient.reset();
    }

    @After
    public void tearDown() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    @Test
    public void shouldReceiveRespondBodyOverHttp3() throws Exception {
        // given - a plain respond action (baseline / positive-control anchor for the body path)
        mockServerClient
            .when(request().withPath("/h3_respond"))
            .respond(
                response()
                    .withStatusCode(201)
                    .withBody("respond_body_over_http3")
            );

        // when - a real QUIC client sends the request over HTTP/3
        Http3Result result = sendHttp3Request("GET", "/h3_respond", null);

        // then - the response body arrived on the client's own QUIC request stream
        assertThat("status over http3: <" + result.status + ">", result.status, is("201"));
        assertThat("body received over http3: <" + result.body + ">", result.body, is("respond_body_over_http3"));
    }

    @Test
    public void shouldReceiveForwardedBodyOverHttp3() throws Exception {
        // given - an upstream server returning a body, and a forward expectation pointing at it
        upstreamClient
            .when(request().withPath("/h3_forward"))
            .respond(response().withBody("forwarded_body_over_http3"));
        mockServerClient
            .when(request().withPath("/h3_forward"))
            .forward(forward().withHost("127.0.0.1").withPort(upstreamServer.getLocalPort()));

        // when
        Http3Result result = sendHttp3Request("GET", "/h3_forward", null);

        // then - the upstream body was relayed back to the QUIC client on its own stream
        assertThat("body received over http3: <" + result.body + ">", result.body, is("forwarded_body_over_http3"));
    }

    @Test
    public void shouldReceiveForwardOverriddenBodyOverHttp3() throws Exception {
        // given - an upstream that echoes the (overridden) request body, and a forward-overridden-request
        // action that both retargets the request at the upstream (via Host) and rewrites its body
        upstreamClient
            .when(request().withPath("/h3_forward_override"))
            .respond(callback().withCallbackClass(EchoBodyCallback.class));
        mockServerClient
            .when(request().withPath("/h3_forward_override"))
            .forward(
                forwardOverriddenRequest()
                    .withRequestOverride(
                        request()
                            .withHeader("Host", "127.0.0.1:" + upstreamServer.getLocalPort())
                            .withBody("overridden_body_over_http3")
                    )
            );

        // when - the client sends its own body; the override should replace it before forwarding
        Http3Result result = sendHttp3Request("POST", "/h3_forward_override", "original_client_body");

        // then - the overridden body was forwarded, echoed by upstream, and relayed to the QUIC client
        assertThat("body received over http3: <" + result.body + ">", result.body, is("overridden_body_over_http3"));
    }

    @Test
    public void shouldReceiveCallbackBodyOverHttp3() throws Exception {
        // given - a class callback that echoes the request body back
        mockServerClient
            .when(request().withPath("/h3_callback"))
            .respond(callback().withCallbackClass(EchoBodyCallback.class));

        // when
        Http3Result result = sendHttp3Request("POST", "/h3_callback", "callback_echo_body_over_http3");

        // then - the callback-produced body arrived over HTTP/3
        assertThat("body received over http3: <" + result.body + ">", result.body, is("callback_echo_body_over_http3"));
    }

    @Test
    public void shouldResetStreamForErrorOverHttp3() throws Exception {
        // given - an error action that resets the matched QUIC request stream (RFC 9114 RESET_STREAM)
        mockServerClient
            .when(request().withPath("/h3_error"))
            .error(error().withStreamError(HttpError.StreamErrorCode.H3_REQUEST_CANCELLED));

        // when
        Http3Result result = sendHttp3Request("GET", "/h3_error", null);

        // then - the client received NO response headers and the stream was reset / closed without a
        // normal response (proving the error action fired over QUIC rather than a 200 being returned)
        assertThat("no response headers should be received when the stream is reset",
            result.receivedHeaders, is(false));
        assertThat("the stream should have been reset or closed without a response",
            result.resetOrClosedWithoutResponse(), is(true));
    }

    /**
     * Response callback that echoes the request body back, so the received body over HTTP/3 proves the
     * callback (or forward-override) action actually ran and its response reached the client's stream.
     */
    @SuppressWarnings("unused")
    public static class EchoBodyCallback implements ExpectationResponseCallback {
        @Override
        public HttpResponse handle(HttpRequest request) {
            return response().withBody(request.getBodyAsString());
        }
    }

    /**
     * What a real QUIC client observed on its own request stream: the {@code :status} pseudo-header,
     * the concatenated DATA-frame body, and — for the error path — whether headers were ever received
     * and whether the stream was reset / closed without a response.
     */
    private static class Http3Result {
        volatile String status = "null";
        volatile String body = "";
        volatile boolean receivedHeaders = false;
        volatile boolean inputClosed = false;
        volatile boolean exceptionRaised = false;

        boolean resetOrClosedWithoutResponse() {
            return !receivedHeaders && (exceptionRaised || inputClosed);
        }
    }

    /**
     * Send a single request over HTTP/3 (QUIC) on a dedicated request stream and collect what the
     * client receives on THAT stream: the response status, its DATA-frame body, and whether the stream
     * was reset / closed without a response. Completes on end-of-stream, on reset, or on connection
     * close, so a dropped body reports "" rather than an opaque timeout.
     */
    private Http3Result sendHttp3Request(String method, String path, String requestBody) throws Exception {
        clientGroup = new NioEventLoopGroup(1);

        QuicSslContext clientSslContext = QuicSslContextBuilder.forClient()
            .trustManager(trustAllManager())
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        Channel clientChannel = new Bootstrap()
            .group(clientGroup)
            .channel(NioDatagramChannel.class)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(clientSslContext)
                .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0)
            .sync()
            .channel();

        QuicChannel quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", http3Port))
            .connect()
            .get(15, TimeUnit.SECONDS);

        Http3Result result = new Http3Result();
        StringBuilder collected = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    result.receivedHeaders = true;
                    CharSequence status = headersFrame.headers().status();
                    if (status != null) {
                        result.status = status.toString();
                    }
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    collected.append(content.toString(StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    result.inputClosed = true;
                    done.countDown();
                    ctx.close();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    result.exceptionRaised = true;
                    done.countDown();
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    done.countDown();
                }
            }
        ).sync().getNow();

        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method(method);
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + http3Port);

        if (requestBody != null) {
            requestStream.write(requestHeaders).sync();
            requestStream.writeAndFlush(new DefaultHttp3DataFrame(
                    Unpooled.wrappedBuffer(requestBody.getBytes(StandardCharsets.UTF_8))))
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                .sync();
        } else {
            requestStream.writeAndFlush(requestHeaders)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                .sync();
        }

        done.await(20, TimeUnit.SECONDS);
        result.body = collected.toString();

        quicChannel.close().sync();
        clientChannel.close().sync();

        return result;
    }

    // ---- assume / trust helpers ----

    private static void assumeQuicAvailable() {
        try {
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 mocking-matrix test",
                io.netty.handler.codec.quic.Quic.isAvailable()
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 mocking-matrix test", t
            );
        }
    }

    @SuppressWarnings("TrustAllX509TrustManager")
    private static TrustManager trustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        };
    }
}
