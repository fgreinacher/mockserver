package org.mockserver.socket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Random;

/**
 * @author jamesdbloom
 */
public class PortFactory {

    /**
     * Opt-in test-port band. When BOTH properties are set to a valid range, {@link #findFreePort()}
     * and {@link #findFreePorts(int)} draw their candidates by explicitly binding ports inside
     * {@code [start, end]} rather than by {@code bind(0)}.
     *
     * <p><strong>Why this exists.</strong> {@code bind(0)} asks the OS for an ephemeral port, drawn
     * from the same range every other {@code bind(0)} on the machine uses (macOS
     * {@code net.inet.ip.portrange.hifirst}..{@code hilast}, typically 49152-65535) - including
     * unrelated applications, IDE helpers, and other JVMs. Two problems follow on a busy developer
     * machine: a foreign process listening in that range can occupy a number we are about to choose
     * (a {@code BindException}), and, worse, a test that connects to a just-selected-but-not-yet-bound
     * port can reach a foreign listener instead of the server under test - producing responses from
     * software that is not MockServer at all. Binding inside a band the OS does <em>not</em> itself
     * hand out to {@code bind(0)} avoids that whole class of cross-application collision.
     *
     * <p><strong>Opt-in on purpose.</strong> Both properties are unset by default, so CI and any
     * machine that does not set them keep the original {@code bind(0)} behaviour byte-for-byte. A
     * machine that suffers ephemeral-range collisions sets, for example,
     * {@code -Dmockserver.testPortRangeStart=20000 -Dmockserver.testPortRangeEnd=40000}. In a
     * surefire/failsafe fork this must reach the fork, e.g. via
     * {@code -Dmockserver.testArgLine="-Dmockserver.testPortRangeStart=20000 -Dmockserver.testPortRangeEnd=40000"}.
     *
     * <p>Setting exactly one of the two, or an unparseable / out-of-order / too-narrow range, is a
     * misconfiguration and is raised loudly rather than silently ignored.
     */
    public static final String PORT_RANGE_START_PROPERTY = "mockserver.testPortRangeStart";
    public static final String PORT_RANGE_END_PROPERTY = "mockserver.testPortRangeEnd";

    private static final Random random = new Random();

    public static int findFreePort() {
        int[] freePorts = findAvailablePorts(1);
        return freePorts[random.nextInt(freePorts.length)];
    }

    /**
     * Find multiple free ports. Ports are selected from a larger pool of recently-available
     * ports to reduce the chance of collisions. Callers should handle {@code BindException}
     * as the returned ports may be claimed by another process before the caller binds them.
     *
     * @param count the number of free ports to find (must be between 1 and 1000 inclusive)
     * @return an array of {@code count} distinct port numbers that were recently free
     * @throws IllegalArgumentException if count is not between 1 and 1000
     */
    public static int[] findFreePorts(int count) {
        if (count <= 0 || count > 1000) {
            throw new IllegalArgumentException("count must be between 1 and 1000, was: " + count);
        }
        int[] candidates = findAvailablePorts(count);
        int ratio = candidates.length / count;
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = candidates[i * ratio];
        }
        return result;
    }

    private static int[] findAvailablePorts(int number) {
        int arraySize = number + random.nextInt(60);
        int[] range = configuredPortRange();
        if (range != null) {
            return findAvailablePortsInRange(arraySize, range[0], range[1]);
        }
        return findAvailableEphemeralPorts(arraySize);
    }

    private static int[] findAvailableEphemeralPorts(int arraySize) {
        // Hold all sockets open simultaneously before closing any, so the OS cannot recycle a port
        // number to a second socket in the same batch (they are bound sequentially but all kept open
        // until every port is recorded), then release them in a finally block. SO_REUSEADDR is set so
        // a caller can re-bind a just-released port without waiting for lingering TIME_WAIT state.
        // There is deliberately no sleep after closing: a delay between releasing the ports and
        // returning them only widens the window in which another process can claim a port, so callers
        // must still handle BindException - binding the real socket directly is the only fully
        // race-free option.
        int[] port = new int[arraySize];
        ServerSocket[] serverSockets = new ServerSocket[arraySize];
        try {
            for (int i = arraySize - 1; i >= 0; i--) {
                ServerSocket serverSocket = new ServerSocket();
                // store immediately so the finally block closes it even if setReuseAddress/bind throws
                serverSockets[i] = serverSocket;
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(0));
                port[i] = serverSocket.getLocalPort();
            }
            return port;
        } catch (IOException e) {
            throw new RuntimeException("Exception while trying to find a free port", e);
        } finally {
            for (ServerSocket serverSocket : serverSockets) {
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException ignore) {
                        // best effort - the port has already been recorded
                    }
                }
            }
        }
    }

    private static int[] findAvailablePortsInRange(int arraySize, int start, int end) {
        // Same "hold every socket open until all ports are recorded" invariant as the ephemeral path
        // (so the batch never hands out the same number twice), but candidates come from explicit
        // binds inside [start, end] instead of from bind(0). Each port is TRIED once - a bind failure
        // means another process (or an earlier socket in this batch) holds it, so we skip to the next.
        // Scanning starts at a random offset and wraps, so parallel JVMs do not all begin at the same
        // number. SO_REUSEADDR matches the ephemeral path so a caller can re-bind a just-released port
        // without waiting for TIME_WAIT; it does NOT let us bind over a live listener, so a genuinely
        // occupied port still fails and is skipped.
        int width = end - start + 1;
        if (arraySize > width) {
            throw new IllegalStateException("test port range " + start + "-" + end + " is too narrow to supply "
                + arraySize + " ports; widen " + PORT_RANGE_START_PROPERTY + "/" + PORT_RANGE_END_PROPERTY);
        }
        int[] port = new int[arraySize];
        ServerSocket[] serverSockets = new ServerSocket[arraySize];
        int found = 0;
        int candidate = start + random.nextInt(width);
        try {
            // At most one attempt per port in the band before giving up.
            for (int attempt = 0; attempt < width && found < arraySize; attempt++) {
                int portToTry = candidate;
                candidate = candidate < end ? candidate + 1 : start;
                ServerSocket serverSocket = new ServerSocket();
                boolean bound = false;
                try {
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(portToTry));
                    bound = true;
                } catch (IOException portInUse) {
                    // this number is taken - fall through to close the probe and try the next candidate
                } finally {
                    if (!bound) {
                        try {
                            serverSocket.close();
                        } catch (IOException ignore) {
                            // best effort
                        }
                    }
                }
                if (bound) {
                    // store immediately so the finally block closes it even if a later iteration throws
                    serverSockets[found] = serverSocket;
                    port[found] = serverSocket.getLocalPort();
                    found++;
                }
            }
            if (found < arraySize) {
                throw new IllegalStateException("could only find " + found + " of " + arraySize
                    + " free ports in range " + start + "-" + end + "; widen "
                    + PORT_RANGE_START_PROPERTY + "/" + PORT_RANGE_END_PROPERTY);
            }
            return port;
        } catch (IOException e) {
            // creating the probe socket itself failed (e.g. file-descriptor exhaustion) - not a
            // port-in-use skip, so surface it rather than continue
            throw new RuntimeException("Exception while trying to find a free port in range " + start + "-" + end, e);
        } finally {
            for (ServerSocket serverSocket : serverSockets) {
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException ignore) {
                        // best effort - the port has already been recorded
                    }
                }
            }
        }
    }

    private static int[] configuredPortRange() {
        String startProperty = System.getProperty(PORT_RANGE_START_PROPERTY);
        String endProperty = System.getProperty(PORT_RANGE_END_PROPERTY);
        if (isBlank(startProperty) && isBlank(endProperty)) {
            return null;
        }
        if (isBlank(startProperty) || isBlank(endProperty)) {
            throw new IllegalStateException("both " + PORT_RANGE_START_PROPERTY + " and " + PORT_RANGE_END_PROPERTY
                + " must be set together, or neither");
        }
        int start = parsePort(PORT_RANGE_START_PROPERTY, startProperty);
        int end = parsePort(PORT_RANGE_END_PROPERTY, endProperty);
        if (start < 1 || start > 65535 || end < 1 || end > 65535) {
            throw new IllegalStateException("test port range " + start + "-" + end + " must be within 1-65535");
        }
        if (end <= start) {
            throw new IllegalStateException("test port range end (" + end + ") must be greater than start (" + start + ")");
        }
        return new int[]{start, end};
    }

    private static int parsePort(String property, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(property + " is not a valid port number: " + value, e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
