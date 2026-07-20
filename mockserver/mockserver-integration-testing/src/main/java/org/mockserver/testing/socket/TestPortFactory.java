package org.mockserver.testing.socket;

import java.io.IOException;
import java.net.DatagramSocket;
import java.util.Random;

/**
 * Test-harness UDP port allocation, the counterpart of {@link org.mockserver.socket.PortFactory}
 * (which covers TCP).
 *
 * <p><strong>The find-then-bind window.</strong> Finding a free port means binding port 0, recording
 * what the OS assigned, then closing the socket so the code under test can bind it. Between the close
 * and the real bind another process can take the port. Binding the real socket directly is the only
 * fully race-free option, so callers that can retry on {@code BindException} should.
 *
 * <p><strong>Why these probes deliberately do not set {@code SO_REUSEADDR}.</strong> {@code PortFactory}
 * sets it on its TCP probes so a caller can re-bind a just-released port without waiting for
 * {@code TIME_WAIT}. That reasoning does <em>not</em> carry over to UDP. On BSD-derived systems (macOS)
 * two UDP sockets that both set {@code SO_REUSEADDR} can bind the same port <em>simultaneously and
 * silently</em>, with no {@code BindException} - datagrams then reach only one of them. Setting it here
 * would let two concurrent probes "find" the same port and both believe they owned it, manufacturing
 * the collision this class exists to avoid. Netty's {@code NioDatagramChannel} also leaves it off by
 * default, so a genuine UDP collision surfaces as a {@code BindException} rather than as silent
 * traffic loss. Keep it off.
 */
public class TestPortFactory {

    private static final Random RANDOM = new Random();

    private TestPortFactory() {
        // static helper
    }

    /**
     * Find a free UDP port.
     *
     * <p>A failure to find a port is raised rather than reported as port {@code 0}. Returning
     * {@code 0} silently means "bind an ephemeral port", which for a configuration value such as
     * {@code http3Port} means "feature disabled" - turning an infrastructure failure into a test that
     * quietly skips, or asserts against a server that was never started.
     *
     * @return a port number that was free at the moment of probing
     * @throws IllegalStateException if no free UDP port could be found
     */
    public static int findFreeUdpPort() {
        // Probe a small batch and pick one at random, holding every socket open until all the port
        // numbers have been recorded so the OS cannot hand the same port to two sockets in the batch,
        // then release them all in the finally block. There is deliberately no sleep after closing:
        // delaying between releasing the ports and returning one only widens the window in which
        // another process can claim it.
        int count = 1 + RANDOM.nextInt(60);
        DatagramSocket[] sockets = new DatagramSocket[count];
        int[] ports = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                DatagramSocket socket = new DatagramSocket(0);
                // store immediately so the finally block closes it even if a later iteration throws
                sockets[i] = socket;
                ports[i] = socket.getLocalPort();
            }
            return ports[RANDOM.nextInt(count)];
        } catch (IOException e) {
            throw new IllegalStateException("Exception while trying to find a free UDP port", e);
        } finally {
            for (DatagramSocket socket : sockets) {
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }
}
