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
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests the HTTP/3 server integration with MockServer's expectation pipeline.
 * <p>
 * The tests skip gracefully when the native QUIC transport (BoringSSL) is not
 * available on the build platform.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3ServerTest {

    private MockServer mockServer;
    private Http3Server standaloneServer;
    private NioEventLoopGroup clientGroup;

    @Before
    public void setUp() {
        assumeQuicAvailable();
    }

    @After
    public void tearDown() {
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
        if (standaloneServer != null) {
            standaloneServer.stop();
            standaloneServer = null;
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    @Test
    public void shouldServeHttp3EchoResponse() throws Exception {
        // start server in echo-only mode (legacy constructor)
        standaloneServer = new Http3Server();
        int port = standaloneServer.start(0);
        assertThat("server should bind to a port", port > 0, is(true));
        assertThat("getPort should return the bound port", standaloneServer.getPort(), is(port));

        // verify echo response
        String[] result = sendHttp3Request(port, "GET", "/hello", null);
        assertThat("status should be 200", result[0], is("200"));
        assertThat("body should contain method", result[1], containsString("method: GET"));
        assertThat("body should contain path", result[1], containsString("path: /hello"));
    }

    @Test
    public void shouldRouteRequestThroughExpectationPipeline() throws Exception {
        // start MockServer with HTTP/3 on ephemeral port
        // use port 0 via the Http3Server directly, but trigger lifecycle via config
        // We set http3Port to a non-zero value; MockServer will bind it
        Configuration config = configuration().http3Port(0);
        // http3Port(0) means disabled. We need to use a real port.
        // Use a helper to find an available UDP port.
        int udpPort = findAvailableUdpPort();
        config.http3Port(udpPort).http3MaxIdleTimeout(30000L);

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start (QUIC unavailable or port conflict)", http3Port > 0);

        // add an expectation via the client API (connecting to the existing server)
        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("GET").withPath("/greet")
        ).respond(
            response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"message\":\"Hello from HTTP/3!\"}")
        );

        // send HTTP/3 request and verify it matches the expectation
        String[] result = sendHttp3Request(http3Port, "GET", "/greet", null);
        assertThat("status should be 200", result[0], is("200"));
        assertThat("body should contain expectation response",
            result[1], containsString("Hello from HTTP/3!"));
    }

    @Test
    public void shouldReturnNotFoundForUnmatchedRequest() throws Exception {
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .http3MaxIdleTimeout(30000L)
            .attemptToProxyIfNoMatchingExpectation(false); // disable proxy attempt for clean 404

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        // send request with no matching expectation
        String[] result = sendHttp3Request(http3Port, "GET", "/nonexistent", null);
        assertThat("status should be 404 for unmatched request", result[0], is("404"));
    }

    @Test
    public void shouldHandlePostRequestWithBody() throws Exception {
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("POST").withPath("/api/data")
        ).respond(
            response()
                .withStatusCode(201)
                .withBody("created")
        );

        String[] result = sendHttp3Request(http3Port, "POST", "/api/data",
            "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8));
        assertThat("status should be 201", result[0], is("201"));
        assertThat("body should be 'created'", result[1], is("created"));
    }

    @Test
    public void shouldApplyConfiguredTransportParameters() throws Exception {
        // verify that custom QUIC transport parameters are applied and requests still work
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .http3MaxIdleTimeout(10000L)          // 10 seconds (non-default)
            .http3InitialMaxData(5000000L)         // 5 MB (non-default)
            .http3InitialMaxStreamDataBidirectional(500000L) // 500 KB (non-default)
            .http3InitialMaxStreamsBidirectional(50L)        // 50 streams (non-default)
            .http3QpackMaxTableCapacity(4096L);    // 4 KB QPACK dynamic table

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("GET").withPath("/config-test")
        ).respond(
            response()
                .withStatusCode(200)
                .withBody("config-applied")
        );

        // verify requests still round-trip with the custom transport parameters
        String[] result = sendHttp3Request(http3Port, "GET", "/config-test", null);
        assertThat("status should be 200 with custom transport params", result[0], is("200"));
        assertThat("body should be 'config-applied'", result[1], is("config-applied"));
    }

    @Test
    public void shouldTrackActiveConnectionCount() throws Exception {
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        // before any connection, count should be 0
        assertThat("initial active connections should be 0",
            mockServer.getHttp3ActiveConnectionCount(), is(0));

        // send a request (which creates a QUIC connection)
        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("GET").withPath("/count-test")
        ).respond(
            response().withStatusCode(200).withBody("ok")
        );

        String[] result = sendHttp3Request(http3Port, "GET", "/count-test", null);
        assertThat("request should succeed", result[0], is("200"));

        // connection count should return to 0 after client closes
        // (the client group shutdown in sendHttp3Request forces close)
        // allow a brief moment for the close event to propagate
        Thread.sleep(200);
        assertThat("active connections should return to 0 after client disconnect",
            mockServer.getHttp3ActiveConnectionCount(), is(0));
    }

    @Test
    public void shouldPreserveDefaultBehaviourWhenNoTransportParamsConfigured() throws Exception {
        // verify that the server starts and works with default config (no transport param overrides)
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("GET").withPath("/defaults")
        ).respond(
            response().withStatusCode(200).withBody("default-config")
        );

        String[] result = sendHttp3Request(http3Port, "GET", "/defaults", null);
        assertThat("status should be 200", result[0], is("200"));
        assertThat("body should be 'default-config'", result[1], is("default-config"));
    }

    @Test
    public void shouldDisableQpackDynamicTableByDefault() throws Exception {
        // when QPACK max table capacity is 0 (the default), the QPACK dynamic table
        // should be disabled — matching the old 1-arg Http3ServerConnectionHandler
        // constructor behaviour. Verify the server starts and serves requests without
        // creating QPACK encoder/decoder streams.
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);

        // assert the default is 0
        assertThat("default QPACK max table capacity should be 0", config.http3QpackMaxTableCapacity(), equalTo(0L));

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("GET").withPath("/qpack-default")
        ).respond(
            response().withStatusCode(200).withBody("qpack-disabled")
        );

        // verify the request completes successfully with default (disabled) QPACK dynamic table
        String[] result = sendHttp3Request(http3Port, "GET", "/qpack-default", null);
        assertThat("status should be 200 with QPACK dynamic table disabled", result[0], is("200"));
        assertThat("body should be 'qpack-disabled'", result[1], is("qpack-disabled"));
    }

    @Test
    public void shouldEnableQpackDynamicTableWithNonZeroCapacity() throws Exception {
        // when QPACK max table capacity is set to a non-zero value, the QPACK dynamic
        // table should be enabled. Verify the server starts and serves requests.
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .http3MaxIdleTimeout(30000L)
            .http3QpackMaxTableCapacity(4096L);

        // assert the capacity is non-zero
        assertThat("QPACK max table capacity should be 4096", config.http3QpackMaxTableCapacity(), equalTo(4096L));

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("GET").withPath("/qpack-enabled")
        ).respond(
            response().withStatusCode(200).withBody("qpack-enabled")
        );

        // verify the request completes successfully with QPACK dynamic table enabled
        String[] result = sendHttp3Request(http3Port, "GET", "/qpack-enabled", null);
        assertThat("status should be 200 with QPACK dynamic table enabled", result[0], is("200"));
        assertThat("body should be 'qpack-enabled'", result[1], is("qpack-enabled"));
    }

    @Test
    public void shouldReturnHttp3StatusEndpoint() throws Exception {
        // start MockServer with HTTP/3 enabled
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        // issue GET /mockserver/http3status via HTTP/1.1 on the control plane port
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .sslContext(trustAllSslContext())
            .build();
        java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://127.0.0.1:" + mockServer.getLocalPort() + "/mockserver/http3status"))
            .GET()
            .build();
        java.net.http.HttpResponse<String> httpResponse = httpClient.send(httpRequest,
            java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat("HTTP status should be 200", httpResponse.statusCode(), is(200));

        String body = httpResponse.body();
        // parse the JSON contract: {"enabled":true,"port":<N>,"activeConnections":<N>}
        assertThat("response should contain 'enabled' field", body, containsString("\"enabled\""));
        assertThat("response should contain 'port' field", body, containsString("\"port\""));
        assertThat("response should contain 'activeConnections' field", body, containsString("\"activeConnections\""));
        assertThat("enabled should be true when H3 is running", body, containsString("\"enabled\":true"));
        assertThat("port should match getHttp3Port()", body, containsString("\"port\":" + http3Port));
        assertThat("activeConnections should be a non-negative integer", body, containsString("\"activeConnections\":0"));
    }

    @Test
    public void shouldReturnHttp3StatusDisabledWhenNotRunning() throws Exception {
        // start MockServer WITHOUT HTTP/3 (http3Port=0, the default)
        Configuration config = configuration();

        mockServer = new MockServer(config, 0);

        // issue GET /mockserver/http3status via HTTP/1.1 on the control plane port
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .sslContext(trustAllSslContext())
            .build();
        java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://127.0.0.1:" + mockServer.getLocalPort() + "/mockserver/http3status"))
            .GET()
            .build();
        java.net.http.HttpResponse<String> httpResponse = httpClient.send(httpRequest,
            java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat("HTTP status should be 200", httpResponse.statusCode(), is(200));

        String body = httpResponse.body();
        assertThat("enabled should be false when H3 is not running", body, containsString("\"enabled\":false"));
        assertThat("port should be -1 when H3 is not running", body, containsString("\"port\":-1"));
        assertThat("activeConnections should be 0", body, containsString("\"activeConnections\":0"));
    }

    // ---- helper methods ----

    /**
     * Find a free UDP port for the HTTP/3 (QUIC) server.
     *
     * <p>Delegates to the shared {@link org.mockserver.testing.socket.TestPortFactory} so the probing
     * strategy lives in one place. A failure to find a port is now raised rather than reported as port
     * {@code 0}: {@code http3Port(0)} means "HTTP/3 disabled", which would silently turn an
     * infrastructure failure into a test that skips or asserts against a server that never started.
     */
    private static int findAvailableUdpPort() {
        return org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort();
    }

    /**
     * Send an HTTP/3 request to the given port and return [status, body].
     */
    private String[] sendHttp3Request(int port, String method, String path, byte[] body) throws Exception {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().sync();
        }
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
            .remoteAddress(new InetSocketAddress("127.0.0.1", port))
            .connect()
            .get(15, TimeUnit.SECONDS);

        BlockingQueue<String> statusQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> bodyQueue = new LinkedBlockingQueue<>();

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                private final StringBuilder bodyBuilder = new StringBuilder();

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    CharSequence status = headersFrame.headers().status();
                    statusQueue.offer(status != null ? status.toString() : "null");
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    bodyBuilder.append(content.toString(StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    bodyQueue.offer(bodyBuilder.toString());
                    ctx.close();
                }
            }
        ).sync().getNow();

        // send request headers
        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method(method);
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);

        if (body != null && body.length > 0) {
            requestHeaders.headers().add("content-type", "application/json");
            requestStream.write(requestHeaders).sync();
            requestStream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(body)))
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                .sync();
        } else {
            requestStream.writeAndFlush(requestHeaders)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                .sync();
        }

        // wait for response
        String status = statusQueue.poll(20, TimeUnit.SECONDS);
        String responseBody = bodyQueue.poll(20, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        return new String[]{
            status != null ? status : "null",
            responseBody != null ? responseBody : ""
        };
    }

    /**
     * Assume-skip the test when the native QUIC library is not loadable.
     */
    private static void assumeQuicAvailable() {
        try {
            boolean available = io.netty.handler.codec.quic.Quic.isAvailable();
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 test",
                available
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 test",
                t
            );
        }
    }

    /**
     * Creates a trust-all SSLContext for the java.net.http.HttpClient used in
     * http3status endpoint tests.
     */
    private static javax.net.ssl.SSLContext trustAllSslContext() {
        try {
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{(TrustManager) trustAllManager()}, new java.security.SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all SSLContext", e);
        }
    }

    /**
     * Creates a trust-all TrustManager for the self-signed certificate.
     * <p>
     * TEST-ONLY: this trust-all TrustManager is used solely by the in-JVM test HTTP client
     * to call the test server's {@code /mockserver/http3status} endpoint over the server's
     * ephemeral self-signed certificate on loopback. It is not production code and has no
     * effect on MockServer's runtime TLS trust, which is governed by configuration (e.g.
     * {@code forwardProxyTLSX509CertificatesTrustManagerType}). The corresponding CodeQL
     * alert (java/insecure-trustmanager) is dismissed as "used in tests".
     */
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
