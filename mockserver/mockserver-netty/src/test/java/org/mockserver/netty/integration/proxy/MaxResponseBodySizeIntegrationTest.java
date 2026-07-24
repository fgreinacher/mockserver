package org.mockserver.netty.integration.proxy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.PortFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural guard that the configuration property {@code maxResponseBodySize} is actually enforced
 * against a body received from an <em>upstream</em> while MockServer is forwarding, and that the
 * <em>configured</em> value — not an unbounded aggregator — is the one that decides.
 * <p>
 * Before this test the property had no behavioural coverage at all: it is read when a forward-client
 * pipeline is built, so a regression that dropped the wiring — or passed {@code Integer.MAX_VALUE} instead
 * of the configured value — would have removed a documented, memory-protecting bound with nothing turning
 * red. Only the inbound analogue {@code maxRequestBodySize} was verified end-to-end.
 * <p>
 * <strong>Scope of this test.</strong> The property has three read sites, and this test covers the first:
 * <ul>
 *   <li>{@code HttpClientInitializer.configureHttp1Pipeline} sizes the HTTP/1.1 forward
 *       {@code StreamingAwareHttpObjectAggregator} from it — <em>covered here</em>;</li>
 *   <li>{@code Http2ForwardStreamChildInitializer} sizes the HTTP/2 <em>per-stream</em> forward aggregator
 *       from the same property — <em>not covered</em>; and</li>
 *   <li>{@code HttpClientInitializer.configureHttp2Pipeline} derives the HTTP/2 client's
 *       {@code maxFrameSize} from it — <em>not covered</em>.</li>
 * </ul>
 * So the property is proven to be enforced on the HTTP/1.1 forward path only; nothing here proves the
 * HTTP/2 forward path honours it.
 * <p>
 * <strong>What the limit actually does, observed end-to-end.</strong> The forward-client aggregator is a
 * Netty {@code HttpObjectAggregator} sized from {@code maxResponseBodySize}. When the upstream response
 * body exceeds it, Netty closes the upstream channel and raises a {@code TooLongFrameException}; that
 * propagates to {@code HttpClientHandler.exceptionCaught}, which completes the forward's response future
 * exceptionally, and {@code HttpActionHandler} converts a failed forward into a
 * <strong>502 Bad Gateway</strong> returned to the client. The oversized upstream body is therefore never
 * relayed — the client sees a gateway error, not a truncated or a complete payload. That is the behaviour
 * pinned here; it is not a documented contract elsewhere, so it is asserted as observed.
 * <p>
 * The test drives a real socket against a real forwarding MockServer, with a raw {@link ServerSocket}
 * upstream that returns a body whose size is chosen by the request path:
 * <ul>
 *   <li><strong>control</strong> — a body comfortably under the configured limit is forwarded intact
 *       (200, every byte present), proving the limit does not simply break forwarding; and</li>
 *   <li><strong>over-limit, {@code Content-Length}</strong> — a body far over the limit, declared by
 *       {@code Content-Length}, is rejected with a 502 and none of the payload reaches the client; and</li>
 *   <li><strong>over-limit, chunked</strong> — the same oversized payload sent with
 *       {@code Transfer-Encoding: chunked} and no {@code Content-Length}, so the limit is proven to be
 *       enforced against the bytes actually accumulated and not merely against a declared header.</li>
 * </ul>
 * The oversized body (64KB) is far below the 50MB product default, so the test is a genuine positive
 * control: restoring an unbounded aggregator (or falling back to the default) lets the oversized body
 * through with a 200 and turns the over-limit assertions red.
 *
 * @author jamesdbloom
 */
public class MaxResponseBodySizeIntegrationTest {

    private static final int MAX_RESPONSE_BODY_SIZE = 4 * 1024;
    // comfortably under the configured limit
    private static final int UNDER_LIMIT_BODY_LENGTH = 2 * 1024;
    // far over the configured limit, and far under the 50MB product default so a lost/unbounded
    // wiring would let this body through rather than reject it
    private static final int OVER_LIMIT_BODY_LENGTH = 64 * 1024;
    private static final char UNDER_LIMIT_FILL = 'u';
    private static final char OVER_LIMIT_FILL = 'o';
    private static final long READ_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15);

    private FixedSizeBodyUpstream upstream;
    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServers() throws Exception {
        upstream = new FixedSizeBodyUpstream();
        Configuration configuration = configuration()
            .useNativeTransport(false)
            // the property under test — set on this server instance only, so the control-plane client's
            // own forward client keeps the product default and cannot mask the effect
            .maxResponseBodySize(MAX_RESPONSE_BODY_SIZE);
        mockServer = new MockServer(configuration, PortFactory.findFreePort());
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient
            .when(request().withPath("/upstream/.*"))
            .forward(
                forward()
                    .withHost("localhost")
                    .withPort(upstream.getPort())
            );
    }

    @After
    public void stopServers() {
        // stopQuietly + finally so a failure closing one resource cannot leak the others (a bound port and
        // a live accept thread) into the shared failsafe fork
        try {
            stopQuietly(mockServerClient);
            stopQuietly(mockServer);
        } finally {
            if (upstream != null) {
                upstream.close();
            }
        }
    }

    @Test
    public void shouldForwardUpstreamResponseBodyUnderTheConfiguredMaxResponseBodySizeIntact() throws Exception {
        assertBodyUnderTheLimitIsForwardedIntact();
    }

    @Test
    public void shouldRejectUpstreamResponseBodyOverTheConfiguredMaxResponseBodySize() throws Exception {
        // JUnit 4 builds a FRESH upstream and MockServer per test method, so the control assertion in the
        // sibling test proves nothing about THIS fixture: if this instance's upstream or MockServer were
        // unhealthy the client would see a 502 for the wrong reason and the test would pass green. Prove
        // this same pipeline forwards a small body first, so the 502 below can only be the limit.
        assertBodyUnderTheLimitIsForwardedIntact();

        String response = sendRawRequestAndReadResponse("/upstream/over");

        assertThat("an upstream response body over maxResponseBodySize must fail the forward and return "
                + "502 Bad Gateway — the configured maxResponseBodySize was not enforced, actual response head:\n"
                + head(response),
            response, containsString("502"));
        assertThat("none of the oversized upstream body may reach the client, actual response head:\n"
                + head(response),
            response, not(containsString(repeat(OVER_LIMIT_FILL, MAX_RESPONSE_BODY_SIZE))));
    }

    @Test
    public void shouldRejectChunkedUpstreamResponseBodyOverTheConfiguredMaxResponseBodySize() throws Exception {
        // per-fixture health control — see shouldRejectUpstreamResponseBodyOverTheConfiguredMaxResponseBodySize
        assertBodyUnderTheLimitIsForwardedIntact();

        // no Content-Length to declare the size up front, so the limit can only be enforced against the
        // bytes actually accumulated by the aggregator
        String response = sendRawRequestAndReadResponse("/upstream/over-chunked");

        assertThat("a chunked upstream response body over maxResponseBodySize must fail the forward and "
                + "return 502 Bad Gateway — the configured maxResponseBodySize was not enforced against "
                + "accumulated content, actual response head:\n" + head(response),
            response, containsString("502"));
        assertThat("none of the oversized chunked upstream body may reach the client, actual response head:\n"
                + head(response),
            response, not(containsString(repeat(OVER_LIMIT_FILL, MAX_RESPONSE_BODY_SIZE))));
    }

    /**
     * Asserts that <em>this test method's own</em> upstream and MockServer forward a body under the limit
     * intact. Used both as the control test in its own right and as a per-fixture health check at the head
     * of each over-limit test, so an over-limit test cannot pass on a 502 produced by a broken fixture.
     */
    private void assertBodyUnderTheLimitIsForwardedIntact() throws IOException {
        String response = sendRawRequestAndReadResponse("/upstream/under");

        assertThat("an upstream response body under maxResponseBodySize must be forwarded, actual response head:\n"
                + head(response),
            response, containsString("200"));
        assertThat("an upstream response body under maxResponseBodySize must be forwarded INTACT — "
                + "expected " + UNDER_LIMIT_BODY_LENGTH + " body bytes, actual response head:\n" + head(response),
            response, containsString(repeat(UNDER_LIMIT_FILL, UNDER_LIMIT_BODY_LENGTH)));
    }

    private String sendRawRequestAndReadResponse(String path) throws IOException {
        String rawRequest = "GET " + path + " HTTP/1.1\r\n"
            + "Host: localhost:" + mockServer.getLocalPort() + "\r\n"
            + "Connection: close\r\n"
            + "\r\n";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", mockServer.getLocalPort()), 5_000);
            socket.setSoTimeout((int) READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(rawRequest.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    response.append(buffer, 0, read);
                }
            } catch (SocketTimeoutException | SocketException terminated) {
                // return whatever was received so the assertion message shows the actual response
            }
            return response.toString();
        }
    }

    /**
     * The head of the response (status line and headers) for assertion messages, so a failure caused by a
     * multi-kilobyte body being forwarded does not dump the whole payload into the build log.
     */
    private static String head(String response) {
        int endOfHead = response.indexOf("\r\n\r\n");
        return endOfHead < 0 ? response : response.substring(0, endOfHead);
    }

    private static String repeat(char character, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(character);
        }
        return builder.toString();
    }

    /**
     * A raw HTTP/1.1 upstream that answers with a body whose size and framing are selected by the request
     * path: {@code /upstream/under} returns a small body, {@code /upstream/over} an oversized body with a
     * {@code Content-Length}, and {@code /upstream/over-chunked} the same oversized body with
     * {@code Transfer-Encoding: chunked}. Deliberately raw rather than a second MockServer so the upstream
     * cannot itself apply any MockServer body limit and mask the behaviour under test.
     */
    private static final class FixedSizeBodyUpstream implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private volatile boolean running = true;

        private FixedSizeBodyUpstream() throws IOException {
            serverSocket = new ServerSocket(0);
            acceptThread = new Thread(this::acceptLoop, "max-response-body-size-upstream");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private int getPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try (Socket socket = serverSocket.accept()) {
                    handle(socket);
                } catch (IOException closed) {
                    // socket closed during shutdown, or the client hung up — keep serving
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            // the request head is read byte-by-byte until CRLFCRLF or EOF on the single accept thread, so
            // without a read timeout a peer that connects and sends nothing would block the loop forever
            socket.setSoTimeout((int) READ_TIMEOUT_MILLIS);
            String requestLine = readRequestHeadAndReturnRequestLine(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            if (requestLine != null && requestLine.contains("/upstream/over-chunked")) {
                writeChunked(out, repeat(OVER_LIMIT_FILL, OVER_LIMIT_BODY_LENGTH));
            } else if (requestLine != null && requestLine.contains("/upstream/over")) {
                writeWithContentLength(out, repeat(OVER_LIMIT_FILL, OVER_LIMIT_BODY_LENGTH));
            } else {
                writeWithContentLength(out, repeat(UNDER_LIMIT_FILL, UNDER_LIMIT_BODY_LENGTH));
            }
            out.flush();
        }

        private String readRequestHeadAndReturnRequestLine(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            int character;
            while ((character = in.read()) != -1) {
                head.append((char) character);
                if (head.length() >= 4 && head.lastIndexOf("\r\n\r\n") == head.length() - 4) {
                    break;
                }
            }
            int endOfRequestLine = head.indexOf("\r\n");
            return endOfRequestLine < 0 ? null : head.substring(0, endOfRequestLine);
        }

        private void writeWithContentLength(OutputStream out, String body) throws IOException {
            out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                // close after each response so no pooled upstream connection carries state between tests
                + "Connection: close\r\n"
                + "\r\n"
                + body).getBytes(StandardCharsets.US_ASCII));
        }

        private void writeChunked(OutputStream out, String body) throws IOException {
            out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
            int chunkSize = 1024;
            for (int offset = 0; offset < body.length(); offset += chunkSize) {
                String chunk = body.substring(offset, Math.min(offset + chunkSize, body.length()));
                out.write((Integer.toHexString(chunk.length()) + "\r\n" + chunk + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignore) {
                // already closed
            }
        }
    }
}
