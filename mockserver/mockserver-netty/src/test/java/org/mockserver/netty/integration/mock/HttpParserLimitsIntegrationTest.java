package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.PortFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural guard that the HTTP parser limit {@code maxHeaderSize} is actually wired into the
 * Netty {@code HttpServerCodec} in the HTTP/1.1 request pipeline
 * ({@code PortUnificationHandler.switchToHttp}) and that the <em>configured</em> value — not Netty's
 * 8192-byte default — is the one enforced against a real socket.
 * <p>
 * Prior to this test the three parser limits ({@code maxInitialLineLength}, {@code maxHeaderSize},
 * {@code maxChunkSize}) had no behavioural coverage: a regression that dropped the configured value
 * and fell back to the default — or removed the wiring entirely — would have gone unnoticed.
 * <p>
 * What the limit actually does, observed end-to-end: MockServer does not send an error status for an
 * over-limit request — Netty's {@code HttpObjectDecoder} raises a {@code TooLongHttpHeaderException},
 * marks the request as a decode failure and stops parsing at the byte that crossed the limit, so
 * every header <em>after</em> that point is silently dropped;
 * {@code FullHttpRequestToMockServerHttpRequest} logs the failure but still serves the request from
 * the headers it did parse. The client-observable effect is therefore <strong>header truncation</strong>:
 * a header positioned beyond the configured {@code maxHeaderSize} boundary never reaches the matcher.
 * <p>
 * The test pins that effect with an expectation that only matches when a marker header is present, and
 * drives two raw HTTP/1.1 requests over a plain {@link Socket} against a server configured with
 * {@code maxHeaderSize=1024}:
 * <ul>
 *   <li>a <strong>control</strong> request whose marker header sits well within the limit — matched
 *       and answered with the mocked 200 response; and</li>
 *   <li>an <strong>over-limit</strong> request identical except that a ~2KB filler header is inserted
 *       ahead of the marker, pushing the marker past the 1024-byte boundary — the marker is dropped,
 *       the request no longer matches, and MockServer returns a 404.</li>
 * </ul>
 * The filler size (2KB) sits strictly between the configured limit (1024) and Netty's 8192-byte
 * default, so the test is a genuine positive control: reverting the wiring to ignore the configured
 * {@code maxHeaderSize} (using the default) lets the whole header block through, the marker survives,
 * the over-limit request matches and returns 200 — turning the over-limit assertion red.
 * <p>
 * {@code Connection: close} is placed ahead of the filler so it is always parsed regardless of the
 * limit, ensuring the server closes each connection promptly and the raw read terminates.
 *
 * @author jamesdbloom
 */
public class HttpParserLimitsIntegrationTest {

    private static final int MAX_HEADER_SIZE = 1024;
    // strictly between the configured limit (1024) and Netty's default header limit (8192): large
    // enough to push the marker header past the configured cap, small enough to fit under the default
    private static final int FILLER_LENGTH = 2048;
    private static final long READ_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServer() {
        Configuration configuration = configuration()
            .useNativeTransport(false)
            .maxHeaderSize(MAX_HEADER_SIZE);
        mockServer = new MockServer(configuration, PortFactory.findFreePort());
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        // only matches when the marker header survives parsing
        mockServerClient
            .when(request().withPath("/limits").withHeader(header("X-Marker", "present")))
            .respond(response().withBody("marker-seen"));
    }

    @After
    public void stopServer() {
        if (mockServerClient != null) {
            mockServerClient.close();
        }
        if (mockServer != null && mockServer.isRunning()) {
            mockServer.stop();
        }
    }

    @Test
    public void shouldParseMarkerHeaderWhenTotalHeadersAreUnderTheConfiguredMaxHeaderSize() throws Exception {
        // marker header well within the 1024-byte limit -> parsed -> expectation matches
        String rawRequest = "GET /limits HTTP/1.1\r\n"
            + "Host: localhost:" + mockServer.getLocalPort() + "\r\n"
            + "Connection: close\r\n"
            + "X-Marker: present\r\n"
            + "\r\n";

        String response = sendRawRequestAndReadResponse(rawRequest);

        assertThat("a marker header under maxHeaderSize must be parsed and match the expectation, "
                + "actual response:\n" + response,
            response, containsString("200"));
        assertThat(response, containsString("marker-seen"));
    }

    @Test
    public void shouldDropMarkerHeaderPushedBeyondTheConfiguredMaxHeaderSize() throws Exception {
        // a ~2KB filler header ahead of the marker pushes the cumulative header size past the
        // 1024-byte cap before the marker is reached, so the decoder drops the marker (and every
        // header after the overflow). Connection: close is ahead of the filler so it always parses.
        String rawRequest = "GET /limits HTTP/1.1\r\n"
            + "Host: localhost:" + mockServer.getLocalPort() + "\r\n"
            + "Connection: close\r\n"
            + "X-Filler: " + repeat('a', FILLER_LENGTH) + "\r\n"
            + "X-Marker: present\r\n"
            + "\r\n";

        String response = sendRawRequestAndReadResponse(rawRequest);

        // the marker is dropped by the enforced limit, so the header-conditional expectation does
        // not match and MockServer returns a 404 rather than the mocked 200 body. If the configured
        // maxHeaderSize were NOT enforced (the positive control reverts the wiring to Netty's 8192
        // default), the whole header block would parse, the marker would survive and this request
        // would be answered with "marker-seen".
        assertThat("an over-limit marker header must be dropped so the expectation does not match — "
                + "the configured maxHeaderSize was not enforced, actual response:\n" + response,
            response, not(containsString("marker-seen")));
        assertThat("an unmatched request (marker dropped by the header limit) must return 404, "
                + "actual response:\n" + response,
            response, containsString("404"));
    }

    private String sendRawRequestAndReadResponse(String rawRequest) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", mockServer.getLocalPort()), 2_000);
            socket.setSoTimeout((int) READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(rawRequest.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    response.append(buffer, 0, read);
                }
            } catch (SocketTimeoutException timedOut) {
                // return whatever was received so the assertion message shows the actual response
            }
            return response.toString();
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
