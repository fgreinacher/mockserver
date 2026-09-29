package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.tls.KeyStoreFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * The inbound W3C trace context belongs to one request, not to the connection it arrived on. With
 * {@code otelPropagateTraceContext} on and {@code otelGenerateTraceId} off, a request that carries no
 * {@code traceparent} must get a response with no {@code traceparent}, even when an earlier request on
 * the same keep-alive connection (or HTTP/2 connection) carried one.
 */
public class TraceContextKeepAliveIntegrationTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String TRACESTATE = "rojo=00f067aa0ba902b7";

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServer() {
        mockServer = new MockServer(
            configuration()
                .useNativeTransport(false)
                .otelPropagateTraceContext(true)
                .otelGenerateTraceId(false),
            0
        );
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient
            .when(request().withPath("/traced"))
            .respond(response().withStatusCode(200).withBody("ok"));
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test(timeout = 30000)
    public void shouldNotLeakTraceContextToLaterRequestOnSameHttp11KeepAliveConnection() throws Exception {
        try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
            socket.setSoTimeout(15000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // request A carries a trace context
            out.write(("GET /traced HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: keep-alive\r\n"
                + "traceparent: " + TRACEPARENT + "\r\n"
                + "tracestate: " + TRACESTATE + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String responseAHead = readResponseHead(in);
            assertThat(responseAHead, containsString("200"));
            assertThat("control: request A's trace context must be propagated, head:\n" + responseAHead,
                responseAHead.toLowerCase(Locale.ROOT), containsString("traceparent: " + TRACEPARENT));

            // request B, same connection, carries none
            out.write(("GET /traced HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String responseBHead = readResponseHead(in);
            assertThat(responseBHead, containsString("200"));
            assertThat("request B sent no traceparent so its response must carry none, head:\n" + responseBHead,
                responseBHead.toLowerCase(Locale.ROOT), not(containsString("traceparent")));
            assertThat(responseBHead.toLowerCase(Locale.ROOT), not(containsString("tracestate")));
        }
    }

    @Test(timeout = 30000)
    public void shouldNotLeakTraceContextToLaterStreamOnSameHttp2Connection() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(new KeyStoreFactory(configuration(), new MockServerLogger()).sslContext())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        URI uri = URI.create("https://localhost:" + mockServer.getLocalPort() + "/traced");

        HttpResponse<String> responseA = client.send(
            HttpRequest.newBuilder(uri).header("traceparent", TRACEPARENT).timeout(Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(responseA.version(), is(HttpClient.Version.HTTP_2));
        assertThat(responseA.headers().firstValue("traceparent"), is(Optional.of(TRACEPARENT)));

        HttpResponse<String> responseB = client.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(responseB.version(), is(HttpClient.Version.HTTP_2));
        assertThat(responseB.statusCode(), is(200));
        assertThat(responseB.headers().firstValue("traceparent"), is(Optional.empty()));
    }

    /**
     * Reads one response's status line and headers, then consumes exactly its {@code Content-Length} body
     * so the next response on the connection starts at a clean boundary.
     */
    private static String readResponseHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (matched < terminator.length) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("connection closed before response head completed: " + head);
            }
            head.write(b);
            matched = b == terminator[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String headString = head.toString(StandardCharsets.US_ASCII);
        int contentLength = 0;
        for (String line : headString.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        for (int i = 0; i < contentLength; i++) {
            if (in.read() < 0) {
                throw new IOException("connection closed before response body completed");
            }
        }
        return headString;
    }
}
