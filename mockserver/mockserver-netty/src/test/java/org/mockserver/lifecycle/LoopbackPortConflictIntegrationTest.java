package org.mockserver.lifecycle;

import org.junit.Test;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URL;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;

/**
 * MockServer must never report a successful start on a port whose localhost traffic goes to another
 * application. On macOS a wildcard bind succeeds on a port another socket holds on 127.0.0.1 (and the
 * operating system may even choose such a port for an ephemeral bind); Linux refuses the bind instead.
 */
public class LoopbackPortConflictIntegrationTest {

    private static final int HELD_LOOPBACK_LISTENERS = 30;
    private static final int EPHEMERAL_STARTS = 5;
    private static final int MACOS_FIRST_EPHEMERAL_PORT = 49152;

    @Test
    public void shouldFailClearlyWhenExplicitPortIsHeldOnIpv4LoopbackByAnotherListener() throws Exception {
        try (ServerSocketChannel otherApplication = ServerSocketChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            int port = otherApplication.socket().getLocalPort();

            MockServer mockServer = null;
            try {
                mockServer = new MockServer(port);
                fail("MockServer reported a start on port " + port + " although another listener holds 127.0.0.1:" + port + ", so localhost requests reach that listener instead");
            } catch (RuntimeException expected) {
                assertThat(expected.getMessage(), containsString("Exception while binding MockServer to port " + port));
                assertThat(expected.getCause(), instanceOf(IOException.class));
                assertThat(expected.getCause().getMessage(), anyOf(
                    // macOS: the bind succeeded but the reachability probe found the conflict
                    allOf(
                        containsString("port " + port + " is already in use by another application listening on 127.0.0.1:" + port),
                        containsString("lsof -nP -iTCP:" + port + " -sTCP:LISTEN")
                    ),
                    // Linux: the operating system refuses the bind
                    containsStringIgnoringCase("address already in use")
                ));
            } finally {
                if (mockServer != null) {
                    mockServer.stop();
                }
            }
        }
    }

    @Test
    public void shouldOnlyStartOnEphemeralPortsThatReceiveLocalhostTraffic() throws Exception {
        List<ServerSocketChannel> otherApplications = new ArrayList<>();
        Set<Integer> heldPorts = new HashSet<>();
        try {
            // macOS hands out ephemeral ports sequentially, so hold every other port just ahead of the
            // allocator's position: without the reachability check most starts land on a held port
            int allocatorPosition;
            try (ServerSocketChannel wildcard = ServerSocketChannel.open()) {
                wildcard.bind(new InetSocketAddress(0));
                allocatorPosition = wildcard.socket().getLocalPort();
            }
            for (int offset = 1; offset <= 2 * HELD_LOOPBACK_LISTENERS; offset += 2) {
                int port = allocatorPosition + offset;
                if (port > 65535) {
                    port = MACOS_FIRST_EPHEMERAL_PORT + (port - 65536);
                }
                ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.INET);
                otherApplications.add(listener);
                try {
                    listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
                    heldPorts.add(port);
                } catch (IOException portInUse) {
                    // skipped: it only matters that most ports ahead of the allocator are held
                }
            }

            for (int i = 0; i < EPHEMERAL_STARTS; i++) {
                MockServer mockServer = new MockServer();
                try {
                    int port = mockServer.getLocalPort();
                    assertThat("start " + i + " chose port " + port + " which another listener holds on 127.0.0.1", heldPorts, not(hasItem(port)));
                    assertThat("start " + i + " on port " + port + " is not reachable on localhost", statusBody(port), allOf(containsString("ports"), containsString(String.valueOf(port))));
                } finally {
                    mockServer.stop();
                }
            }
        } finally {
            for (ServerSocketChannel listener : otherApplications) {
                listener.close();
            }
        }
    }

    private static String statusBody(int port) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http", "127.0.0.1", port, "/mockserver/status").openConnection();
        try {
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            try (InputStream inputStream = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                inputStream.transferTo(body);
                return body.toString(StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
