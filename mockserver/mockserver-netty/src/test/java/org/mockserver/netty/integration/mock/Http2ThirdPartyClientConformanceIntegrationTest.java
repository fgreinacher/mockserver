package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.test.Http2FlowControlBodies;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.tls.PEMToFile;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Drives MockServer's HTTP/2 responses with a <b>genuinely independent HTTP/2 client</b> —
 * {@link java.net.http.HttpClient}, the JDK's built-in stack — rather than with Netty, the library
 * MockServer itself is built on. A separate implementation of RFC 7540/9113 cannot share a bug with
 * the server, so it can observe defects the server's own client is blind to.
 *
 * <p>This is a sibling of {@link ThirdPartyStreamingClientConformanceIntegrationTest} (cleartext
 * HTTP/1.1 SSE and WebSocket) and closes the gap that test left open: HTTP/2, the CONNECT proxy, and
 * large bodies. It exists because four HTTP/2 defects — GitHub #2641, #2667, #2669 and #2683 — all
 * shipped while every HTTP/2 test was green, for one structural reason: <b>every HTTP/2 test used a
 * response body smaller than the 65,535-byte flow-control window</b>, including the one actually named
 * {@code shouldForwardHttp2RequestWithLargeBodyViaConnectProxy} at 50,000 bytes. Those tests looked
 * thorough and were incapable of failing, because the failure mode of the family is a <b>silent
 * hang</b> that only appears once a response crosses the window.
 *
 * <p>Every case here therefore:
 * <ul>
 *   <li>uses a body that <b>exceeds the flow-control window</b> ({@link #LARGE_BODY_SIZE}); and</li>
 *   <li>asserts the <b>full body arrives</b> (length AND content) <b>within a bounded timeout</b> —
 *       because the bug hangs rather than mis-answers, the time bound is the real assertion; and</li>
 *   <li>asserts the response was actually delivered over {@link HttpClient.Version#HTTP_2}, so a
 *       silent downgrade to HTTP/1.1 (which has no flow control and would not exercise the bug)
 *       fails the test rather than passing it as a false green.</li>
 * </ul>
 */
public class Http2ThirdPartyClientConformanceIntegrationTest {

    /**
     * The {@code jdk.httpclient.windowsize} value in force before the static block below pinned it,
     * restored in {@link #stopServer()} so the pin does not leak to later tests in the reused fork.
     */
    private static final String PREVIOUS_WINDOW_SIZE;

    static {
        // Pin java.net.http.HttpClient to the RFC 7540 / 9113 DEFAULT stream flow-control window
        // (65,535 bytes). This is load-bearing and MUST run before any HttpClient is constructed: the
        // JDK client's OWN default receive window is far larger than the RFC default, and it also
        // pre-emptively enlarges the connection-level window right after the preface. With that inflated
        // window a large response fits in the peer's first window and is delivered WITHOUT any
        // WINDOW_UPDATE, so the writePendingBytes() flush this suite exists to exercise never runs and
        // every case becomes a false green. Forcing 65,535 makes the client behave like the RFC default
        // — the same window MockServer's own Netty client uses — so a body over one window genuinely
        // crosses it and depends on the flush.
        //
        // DO NOT REMOVE. Without it, the direct (1) and mocked-CONNECT (3) cases pass even against the
        // unfixed #2683 code (verified: reverting the fix leaves them green when this is absent, red when
        // present). The forwarded-CONNECT case (5) reproduces regardless of window, so it remains the
        // fork-order-independent lock; this property is what additionally makes 1/3/4 real locks.
        PREVIOUS_WINDOW_SIZE = System.setProperty("jdk.httpclient.windowsize", "65535");
    }

    /**
     * A response body MUST exceed the HTTP/2 initial flow-control window, or the whole response fits in
     * the peer's first window and the {@code WINDOW_UPDATE}-driven {@code writePendingBytes()} flush is
     * never exercised — the sub-window blind spot that hid #2641/#2667/#2669/#2683 for four releases.
     * Sourced from {@link Http2FlowControlBodies.Size#OVER_WINDOW} so there is ONE definition of that
     * threshold for the whole repository; that class guards the value at class load. Do not replace this
     * with a local literal.
     */
    private static final int LARGE_BODY_SIZE = Http2FlowControlBodies.Size.OVER_WINDOW.bytes();

    private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(20);

    private static int mockServerPort;
    private static MockServerClient mockServerClient;
    private static EchoServer secureEchoServer;
    private static SSLContext caTrustingSslContext;


    @BeforeClass
    public static void startServer() throws Exception {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
        // a real HTTPS upstream for the forward/proxy leg (matches NettyHttpsProxyHttp2IntegrationTest)
        secureEchoServer = new EchoServer(true);
        caTrustingSslContext = caTrustingSslContext();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(secureEchoServer);
        stopQuietly(mockServerClient);
        // Restore the window-size property. Failsafe runs forkCount=1, reuseForks=true, so leaving it
        // set would apply the RFC-default receive window to every later test in the fork, including
        // the latency- and load-sensitive suites. The effect is spec-compliant and harmless in
        // itself, but unbounded global test state is how this repository has repeatedly acquired
        // order-dependent flakes, so bound it to this class.
        if (PREVIOUS_WINDOW_SIZE == null) {
            System.clearProperty("jdk.httpclient.windowsize");
        } else {
            System.setProperty("jdk.httpclient.windowsize", PREVIOUS_WINDOW_SIZE);
        }
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        secureEchoServer.mockServerEventLog().reset();
    }

    // ------------------------------------------------------------------
    // 1. Direct h2 over TLS + ALPN — large mocked response
    // ------------------------------------------------------------------

    /**
     * Direct HTTP/2 over TLS+ALPN, no proxy. On the direct path no mock-serving handler sits ahead of
     * the h2 codec swallowing {@code channelReadComplete}, so the flow-control flush already worked
     * before the #2683 fix: this is an <b>isolation control</b>. A regression that reds here points at
     * the shared h2 layer; one that reds only the CONNECT case points at the relay pipeline.
     */
    @Test(timeout = 60000)
    public void shouldDeliverLargeMockedResponseOverDirectHttp2() throws Exception {
        String body = largeBody("direct-tls-alpn");
        mockServerClient
            .when(request().withPath("/large_direct_h2"))
            .respond(response().withStatusCode(201).withBody(body));

        HttpClient client = directClient();
        HttpResponse<String> response = client.send(
            getRequest("https://localhost:" + mockServerPort + "/large_direct_h2"),
            HttpResponse.BodyHandlers.ofString());

        assertH2(response);
        assertThat(response.statusCode(), is(201));
        assertThat("full body length must arrive over direct h2", response.body().length(), is(body.length()));
        assertThat("full body content must arrive over direct h2", response.body(), is(body));
    }

    // ------------------------------------------------------------------
    // 2. Direct h2c (cleartext, prior knowledge) — SKIPPED, see javadoc
    // ------------------------------------------------------------------
    //
    // Deliberately NOT implemented. MockServer's PortUnificationHandler only recognises cleartext
    // HTTP/2 by PRIOR-KNOWLEDGE preface detection (isH2cPreface); it has no branch for the HTTP/1.1
    // "Upgrade: h2c" negotiation. java.net.http.HttpClient does the opposite: over a cleartext http://
    // URI with .version(HTTP_2) it attempts the HTTP/1.1 upgrade and, when the server does not answer
    // 101, silently falls back to HTTP/1.1 — it never sends the prior-knowledge preface. So the JDK
    // client cannot drive genuine prior-knowledge h2c against MockServer, and any test here would
    // either be HTTP/1.1 in disguise (a false green) or hang. Faking it is worse than skipping it.
    // Prior-knowledge h2c IS covered, with an independent prior-knowledge client, by
    // H2cMockingMatrixIntegrationTest.shouldReceiveLargeRespondBodyOverH2c.

    // ------------------------------------------------------------------
    // 3. h2 through the HTTPS CONNECT forward proxy — large mocked response (the #2683 regression)
    // ------------------------------------------------------------------

    /**
     * The #2683 regression, reproduced from a third-party client. An h2 response served through
     * MockServer's HTTPS CONNECT proxy travels back to the client through the client-facing pipeline,
     * which retains mock-serving handlers <em>ahead of</em> the h2 codec. Several of those overrode
     * {@code channelReadComplete} as {@code ctx.flush()} only, so the event never reached
     * {@code Http2ConnectionHandler.channelReadComplete}, {@code writePendingBytes()} never ran, and
     * any response over one flow-control window stalled at exactly 65,535 bytes until the client timed
     * out. The pre-fix behaviour is a HANG, so {@link #EXCHANGE_TIMEOUT} on the request is the real
     * assertion, with {@code @Test(timeout)} as the backstop.
     */
    @Test(timeout = 60000)
    public void shouldDeliverLargeMockedResponseOverHttp2ViaConnectProxy() throws Exception {
        String body = largeBody("connect-proxy-mocked");
        mockServerClient
            .when(request().withPath("/large_mocked_via_connect").withProtocol(Protocol.HTTP_2))
            .respond(response().withStatusCode(201).withBody(body));

        HttpClient client = proxyClient();
        // target is the real HTTPS upstream; MockServer intercepts the tunnel and, because the
        // expectation matches, mocks the response rather than forwarding it
        HttpResponse<String> response = client.send(
            getRequest("https://127.0.0.1:" + secureEchoServer.getPort() + "/large_mocked_via_connect"),
            HttpResponse.BodyHandlers.ofString());

        assertH2(response);
        assertThat(response.statusCode(), is(201));
        assertThat("full body length must cross the CONNECT tunnel", response.body().length(), is(body.length()));
        assertThat("full body content must cross the CONNECT tunnel", response.body(), is(body));
    }

    // ------------------------------------------------------------------
    // 4. Concurrent streams on one connection — the #2667 stream-id mis-routing family
    // ------------------------------------------------------------------

    /**
     * Many concurrent large responses multiplexed on a single HTTP/2 connection, each returning a body
     * whose filler is stamped with its own stream marker. If DATA is mis-routed to the wrong stream —
     * the #2667/#2669 stream-id family — a response body will not equal the body its request expected,
     * so the per-request content assertion fails. A warm-up request first establishes the pooled h2
     * connection so the concurrent batch genuinely multiplexes over one connection rather than opening
     * several.
     */
    @Test(timeout = 90000)
    public void shouldKeepConcurrentStreamsOnOneConnectionCorrectlyRouted() throws Exception {
        int streamCount = 8;
        Map<String, String> expectedBodyByPath = new LinkedHashMap<>();
        for (int i = 0; i < streamCount; i++) {
            String path = "/concurrent_stream_" + i;
            String body = largeBody("stream-" + i);
            expectedBodyByPath.put(path, body);
            mockServerClient
                .when(request().withPath(path))
                .respond(response().withStatusCode(200).withBody(body));
        }

        HttpClient client = directClient();

        // warm-up: force the h2 connection into the pool so the batch below reuses one connection
        HttpResponse<String> warmUp = client.send(
            getRequest("https://localhost:" + mockServerPort + "/concurrent_stream_0"),
            HttpResponse.BodyHandlers.ofString());
        assertH2(warmUp);

        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (String path : expectedBodyByPath.keySet()) {
            futures.add(client.sendAsync(
                getRequest("https://localhost:" + mockServerPort + path),
                HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture
            .allOf(futures.toArray(new CompletableFuture[0]))
            .get(60, SECONDS);

        for (CompletableFuture<HttpResponse<String>> future : futures) {
            HttpResponse<String> response = future.get();
            assertH2(response);
            String path = response.request().uri().getPath();
            String expected = expectedBodyByPath.get(path);
            assertThat("no expectation for responded path " + path, expected, is(notNullValue()));
            assertThat("body for " + path + " was mis-routed or truncated across concurrent streams",
                response.body(), is(expected));
        }
    }

    // ------------------------------------------------------------------
    // 5. Large FORWARDED (proxied) response — not just mocked
    // ------------------------------------------------------------------

    /**
     * A large response produced by a real upstream and <b>forwarded</b> through the CONNECT tunnel
     * (no matching expectation, so MockServer proxies to the HTTPS echo server, which echoes the
     * request body back as its response body). This exercises the forwarded-response leg over the
     * client-facing h2 pipeline with a body larger than the window — the sibling of
     * {@code NettyHttpsProxyHttp2IntegrationTest.shouldForwardHttp2RequestWithLargeBodyViaConnectProxy}
     * from an independent client.
     */
    @Test(timeout = 60000)
    public void shouldDeliverLargeForwardedResponseOverHttp2ViaConnectProxy() throws Exception {
        String body = largeBody("connect-proxy-forwarded");

        HttpClient client = proxyClient();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + secureEchoServer.getPort() + "/large_forwarded"))
                .timeout(EXCHANGE_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertH2(response);
        assertThat("full forwarded body length must cross the tunnel", response.body().length(), is(body.length()));
        assertThat("full forwarded body content must cross the tunnel", response.body(), is(body));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static HttpClient directClient() {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(caTrustingSslContext)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private static HttpClient proxyClient() {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(caTrustingSslContext)
            .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", mockServerPort)))
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private static HttpRequest getRequest(String uri) {
        return HttpRequest.newBuilder(URI.create(uri))
            .timeout(EXCHANGE_TIMEOUT)
            .GET()
            .build();
    }

    private static void assertH2(HttpResponse<?> response) {
        // a silent downgrade to HTTP/1.1 would not exercise flow control at all, so this guards against
        // the whole suite passing for the wrong reason
        assertThat("response was not delivered over HTTP/2 for " + response.request().uri(),
            response.version(), is(HttpClient.Version.HTTP_2));
    }

    /**
     * Distinguishable, deterministic body of exactly {@link #LARGE_BODY_SIZE} bytes whose filler is
     * stamped throughout with {@code marker}, so a mis-routed or truncated body cannot equal the
     * expected one anywhere along its length. Delegates to the shared helper rather than repeating the
     * generation here, so this suite and the rest of the HTTP/2 tests cannot drift apart.
     */
    private static String largeBody(String marker) {
        return Http2FlowControlBodies.body(Http2FlowControlBodies.Size.OVER_WINDOW, marker);
    }

    /**
     * A trust store containing MockServer's Certificate Authority certificate — proper CA-based trust,
     * not a blanket trust-all. MockServer signs its dynamically-generated leaf certificates with this
     * bundled CA by default (proxySetup and dynamicallyCreateCertificateAuthorityCertificate are both
     * off), and its default certificate SANs cover {@code localhost} and {@code 127.0.0.1}, so
     * hostname verification is left ON and passes for the origins used here.
     */
    private static SSLContext caTrustingSslContext() throws Exception {
        String caPem;
        try (InputStream in = Http2ThirdPartyClientConformanceIntegrationTest.class.getClassLoader()
            .getResourceAsStream(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)) {
            if (in == null) {
                throw new IllegalStateException("could not load MockServer CA certificate from classpath: "
                    + ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE);
            }
            caPem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        X509Certificate caCertificate = PEMToFile.x509FromPEM(caPem);

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("mockserver-ca", caCertificate);

        TrustManagerFactory trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return sslContext;
    }
}
