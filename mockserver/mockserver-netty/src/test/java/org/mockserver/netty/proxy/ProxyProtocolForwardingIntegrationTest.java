package org.mockserver.netty.proxy;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.PortFactory;
import org.mockserver.streams.IOStreamUtils;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Real-socket loopback integration test proving that the PROXY protocol v1 destination
 * (the {@code REMOTE_SOCKET} resolved by {@link ProxyProtocolOriginalDestinationHandler})
 * actually drives transparent-proxy forwarding end-to-end — not merely that the handler
 * sets a channel attribute in an {@link io.netty.channel.embedded.EmbeddedChannel}.
 * <p>
 * The wire flow exercised is:
 * <ol>
 *   <li>MockServer runs with {@code transparentProxyEnabled=true} on loopback, with NO
 *       fixed remote host/port — so the ONLY thing that can direct a forward to the
 *       {@link EchoServer} is the PROXY-protocol header.</li>
 *   <li>A raw socket writes a valid PROXY v1 {@code TCP4} header naming the EchoServer's
 *       loopback address and port as the destination, followed by a plain HTTP GET whose
 *       {@code Host} header points at an unrelated (closed) decoy port.</li>
 *   <li>The GET matches no expectation, so MockServer proxy-forwards it to the resolved
 *       {@code REMOTE_SOCKET} (the EchoServer), which reflects the request headers back in
 *       its response.</li>
 * </ol>
 * The test asserts the EchoServer's reflection of a unique request header comes back —
 * proving the PROXY-protocol REMOTE_SOCKET, and not the {@code Host} header, chose the
 * forward target.
 * <p>
 * This needs no {@code NET_ADMIN}/privileged capability: the PROXY-protocol header is an
 * application-level byte prefix, unlike the SO_ORIGINAL_DST / iptables path exercised by
 * {@link SoOriginalDstEndToEndIntegrationTest}.
 * <p>
 * <b>Positive control (manually verified):</b> deleting the
 * {@code ctx.channel().attr(REMOTE_SOCKET).set(originalDst)} line in
 * {@link ProxyProtocolOriginalDestinationHandler#applyOriginalDst} makes the forward fall
 * back to the decoy {@code Host} header (a closed port), so the EchoServer never receives
 * the request and the reflected-header assertion fails RED. Restoring the line returns it
 * to GREEN.
 */
public class ProxyProtocolForwardingIntegrationTest {

    private static EchoServer echoServer;
    private static MockServer mockServer;

    @BeforeClass
    public static void setupFixture() {
        echoServer = new EchoServer(false);
        // transparent proxy enabled, but NO fixed remote host/port: the PROXY header is the
        // only possible source of the forward destination.
        mockServer = new MockServer(configuration().transparentProxyEnabled(true), 0);
    }

    @AfterClass
    public static void shutdownFixture() {
        stopQuietly(echoServer);
        stopQuietly(mockServer);
    }

    @Test
    public void shouldForwardToDestinationFromProxyProtocolHeader() throws Exception {
        // given — a decoy Host that is NOT the EchoServer, so only the PROXY-protocol
        // destination can route the request to the EchoServer
        int decoyPort = PortFactory.findFreePort();
        String uniqueMarker = "proxy-proto-forward-" + System.nanoTime();

        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            OutputStream output = socket.getOutputStream();

            // when — PROXY v1 TCP4 header naming the EchoServer as destination, then a plain GET
            String proxyHeader =
                "PROXY TCP4 127.0.0.1 127.0.0.1 12345 " + echoServer.getPort() + "\r\n";
            String httpRequest = "" +
                "GET /proxy-protocol-forward HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + decoyPort + "\r\n" +
                "X-Proxy-Proto-Test: " + uniqueMarker + "\r\n" +
                "\r\n";

            output.write((proxyHeader + httpRequest).getBytes(StandardCharsets.UTF_8));
            output.flush();

            // then — the EchoServer received and reflected the request (proving the PROXY-protocol
            // REMOTE_SOCKET drove the forward, since the Host header pointed elsewhere)
            String response = IOStreamUtils.readHttpInputStreamToString(socket);
            assertThat(
                "request should have been forwarded to the EchoServer named by the PROXY header, "
                    + "so its reflected unique header should be present; response was:\n" + response,
                response, containsString(uniqueMarker));
        }
    }

    @Test
    public void shouldNotReachDecoyHostWhenProxyProtocolNamesEchoServer() throws Exception {
        // given — a distinct marker; the decoy Host names a closed port so, had the forward used
        // the Host header instead of the PROXY-protocol destination, the EchoServer (and thus the
        // reflected marker) would be absent from the response.
        int decoyPort = PortFactory.findFreePort();
        String uniqueMarker = "proxy-proto-routing-" + System.nanoTime();

        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            OutputStream output = socket.getOutputStream();

            String proxyHeader =
                "PROXY TCP4 10.9.8.7 127.0.0.1 6553 " + echoServer.getPort() + "\r\n";
            String httpRequest = "" +
                "GET /proxy-protocol-routing HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + decoyPort + "\r\n" +
                "X-Proxy-Proto-Routing: " + uniqueMarker + "\r\n" +
                "\r\n";

            output.write((proxyHeader + httpRequest).getBytes(StandardCharsets.UTF_8));
            output.flush();

            String response = IOStreamUtils.readHttpInputStreamToString(socket);
            // reached the EchoServer (reflected marker present) ...
            assertThat("response should be the EchoServer's reflection; was:\n" + response,
                response, containsString(uniqueMarker));
            // ... and it is the EchoServer's own response, not a MockServer 404/not-found for the decoy
            assertThat("forward should have reached the EchoServer, not returned a not-found; was:\n" + response,
                response, not(containsString("\"httpRequest\"")));
        }
    }
}
