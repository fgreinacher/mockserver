package org.mockserver.netty.integration.proxy.socks;

import com.google.common.primitives.Bytes;
import io.netty.util.NetUtil;
import org.apache.commons.codec.binary.Hex;
import org.junit.*;
import org.mockserver.client.MockServerClient;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.netty.MockServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.Verification.verification;

/**
 * Socket-level integration test proving SOCKS4 (RFC 1928 predecessor) CONNECT tunnelling
 * end-to-end over a real socket — mirrors {@link NettyHttpProxySOCKSIntegrationTest}'s
 * SOCKS5 raw-socket test but performs the SOCKS4 handshake instead.
 * <p>
 * A raw SOCKS4 CONNECT handshake is sent to a bound MockServer targeting a loopback
 * {@link EchoServer}; once the tunnel is granted a plain HTTP GET is written through it and
 * we assert the EchoServer receives the request and returns 200 (bytes relayed by
 * {@code Socks4ConnectHandler}). Unlike SOCKS5 there is no separate INIT/method-negotiation
 * round trip — the CONNECT request is the first and only handshake message.
 *
 * @author jamesdbloom
 */
public class NettyHttpProxySOCKS4IntegrationTest {

    private static Integer mockServerPort;
    private static EchoServer insecureEchoServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void setupEchoServer() {
        insecureEchoServer = new EchoServer(false);
    }

    @AfterClass
    public static void shutdownEchoServer() {
        stopQuietly(insecureEchoServer);
    }

    @Before
    public void setupMockServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @After
    public void shutdownMockServer() {
        stopQuietly(mockServerClient);
    }

    @Test
    public void shouldProxyRequestsUsingSocketViaSOCKS4() throws Exception {
        proxyRequestsUsingSocketViaSOCKS4(
            insecureEchoServer,
            new Socket("localhost", mockServerPort)
        );
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void proxyRequestsUsingSocketViaSOCKS4(EchoServer echoServer, Socket socket) throws Exception {
        try {
            // given
            int echoServerPort = echoServer.getPort();
            echoServer.clear();
            // fail fast (rather than block forever) if the tunnel is never established / bytes never relayed
            socket.setSoTimeout(15_000);
            OutputStream outputStream = socket.getOutputStream();
            InputStream inputStream = socket.getInputStream();

            byte[] portBytes = {
                (byte) ((echoServerPort >> 8) & 0xFF),
                (byte) (echoServerPort & 0xFF)
            };
            byte[] ipBytes = NetUtil.createByteArrayFromIpAddressString("127.0.0.1");

            // when - SOCKS4 CONNECT request (no separate INIT handshake, unlike SOCKS5)
            byte[] connectRequest = Bytes.concat(
                new byte[]{
                    (byte) 0x04,                                    // SOCKS4
                    (byte) 0x01,                                    // command type CONNECT
                },
                portBytes,                                         // destination port
                ipBytes,                                           // destination IPv4 address
                new byte[]{
                    (byte) 0x00                                     // empty user id, null terminated
                }
            );
            outputStream.write(connectRequest);
            outputStream.flush();

            // then - SOCKS4 CONNECT response (8 bytes): 0x00, status 0x5a (granted), port, ip
            byte[] expectedResponse = Bytes.concat(
                new byte[]{
                    (byte) 0x00,                                    // null version byte
                    (byte) 0x5a,                                    // request granted
                },
                portBytes,                                         // destination port
                ipBytes                                            // destination IPv4 address
            );
            byte[] connectResponse = new byte[expectedResponse.length];
            inputStream.read(connectResponse);
            assertThat(Hex.encodeHexString(connectResponse), is(Hex.encodeHexString(expectedResponse)));

            // when - a plain HTTP GET is written through the granted tunnel
            outputStream.write(("" +
                "GET /some_path HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"
            ).getBytes(StandardCharsets.UTF_8));
            outputStream.flush();

            // then - the EchoServer relays a 200 back through the tunnel
            byte[] echoServerResponse = new byte[125];
            inputStream.read(echoServerResponse);
            assertThat(new String(echoServerResponse, StandardCharsets.UTF_8), startsWith("" +
                "HTTP/1.1 200 OK\r\n" +
                "accept-encoding: gzip,deflate\r\n" +
                "connection: keep-alive\r\n" +
                "content-length: 0\r\n" +
                "\r\n"
            ));

            // and - the EchoServer actually received the tunnelled request
            assertThat(
                echoServer
                    .mockServerEventLog()
                    .verify(verification()
                        .withRequest(
                            request()
                                .withMethod("GET")
                                .withPath("/some_path")
                        ))
                    .get(5, SECONDS),
                is("")
            );
        } finally {
            socket.close();
        }
    }
}
