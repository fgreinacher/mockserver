package org.mockserver.socket;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * @author jamesdbloom
 */
public class PortFactoryTest {

    @After
    public void clearPortRangeProperties() {
        System.clearProperty(PortFactory.PORT_RANGE_START_PROPERTY);
        System.clearProperty(PortFactory.PORT_RANGE_END_PROPERTY);
    }

    @Test
    public void shouldFindFreePort() throws IOException {
        // when
        int freePort = PortFactory.findFreePort();

        // then
        ServerSocket serverSocket = new ServerSocket(freePort);
        assertThat(serverSocket.isBound(), is(true));
        serverSocket.close();
    }

    @Test
    public void shouldFindMultipleFreePorts() throws IOException {
        // when
        int[] freePorts = PortFactory.findFreePorts(3);

        // then
        assertThat(freePorts.length, is(3));
        for (int freePort : freePorts) {
            ServerSocket serverSocket = new ServerSocket(freePort);
            assertThat(serverSocket.isBound(), is(true));
            serverSocket.close();
        }
    }

    @Test
    public void shouldNotReturnPortsOtherProcessesHoldOnLoopbackAddresses() throws IOException {
        // macOS hands out ephemeral ports sequentially, so hold every port just ahead of the allocator:
        // even offsets on 127.0.0.1 and odd offsets on [::1] (where IPv6 is available)
        List<ServerSocketChannel> otherApplications = new ArrayList<>();
        Set<Integer> heldPorts = new HashSet<>();
        try {
            int allocatorPosition;
            try (ServerSocketChannel probe = ServerSocketChannel.open(StandardProtocolFamily.INET)) {
                probe.bind(new InetSocketAddress(0));
                allocatorPosition = probe.socket().getLocalPort();
            }
            for (int offset = 1; offset <= 60 && allocatorPosition + offset <= 65535; offset++) {
                boolean ipv4 = offset % 2 == 0;
                int port = allocatorPosition + offset;
                try {
                    ServerSocketChannel listener = ServerSocketChannel.open(ipv4 ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6);
                    otherApplications.add(listener);
                    listener.bind(new InetSocketAddress(InetAddress.getByName(ipv4 ? "127.0.0.1" : "::1"), port));
                    heldPorts.add(port);
                } catch (IOException | UnsupportedOperationException unavailable) {
                    // port in use or no IPv6: it only matters that most ports ahead of the allocator are held
                }
            }

            // when
            int[] freePorts = PortFactory.findFreePorts(20);

            // then
            for (int freePort : freePorts) {
                assertThat("port " + freePort + " is held by another listener on a loopback address", heldPorts.contains(freePort), is(false));
            }
        } finally {
            for (ServerSocketChannel listener : otherApplications) {
                listener.close();
            }
        }
    }

    @Test
    public void shouldSkipTheIpv6CheckWhenTheAddressCannotBeBoundRatherThanTreatEveryPortAsTaken() throws IOException {
        // 2001:db8::/32 is reserved for documentation, so no interface has it: every bind fails with
        // "Cannot assign requested address", like [::1] on a host whose IPv6 loopback is disabled
        InetAddress unassignable = InetAddress.getByName("2001:db8::1");
        IntPredicate inUse = PortFactory.inUseOn(unassignable);

        try (PortFactory.EphemeralPortBatch batch = new PortFactory.EphemeralPortBatch(inUse)) {
            for (int i = 0; i < 20; i++) {
                batch.nextPort();
            }

            // then - no candidate was skipped
            assertThat(batch.candidatesBound(), is(20));
        }
    }

    @Test
    public void shouldCapSkippedCandidatesPerBatchAndNeverReturnAPortReportedInUse() throws IOException {
        Set<Integer> reportedInUse = new HashSet<>();
        Set<Integer> returned = new HashSet<>();

        try (PortFactory.EphemeralPortBatch batch = new PortFactory.EphemeralPortBatch(port -> reportedInUse.add(port))) {
            for (int i = 0; i < 10; i++) {
                returned.add(batch.nextPort());
            }

            // then - the [::1] check is dropped after the cap instead of holding ever more ports
            assertThat(batch.candidatesBound(), is(10 + PortFactory.MAX_SKIPPED_CANDIDATES_PER_BATCH));
            assertThat(reportedInUse.size(), is(PortFactory.MAX_SKIPPED_CANDIDATES_PER_BATCH));
            for (int port : returned) {
                assertThat("port " + port + " was reported in use but still returned", reportedInUse.contains(port), is(false));
            }
        }
    }

    @Test
    public void shouldOnlyTreatAddressInUseAsTaken() {
        assertThat(PortFactory.isAddressInUse(new BindException("Address already in use")), is(true));
        assertThat(PortFactory.isAddressInUse(new BindException("Address already in use: bind")), is(true));
        assertThat(PortFactory.isAddressInUse(new BindException("Cannot assign requested address")), is(false));
        assertThat(PortFactory.isAddressInUse(new BindException()), is(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowForZeroCount() {
        PortFactory.findFreePorts(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowForNegativeCount() {
        PortFactory.findFreePorts(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowForExcessiveCount() {
        PortFactory.findFreePorts(1001);
    }

    @Test
    public void shouldFindMultipleFreePortsWithDistinctValues() throws IOException {
        // when
        int[] freePorts = PortFactory.findFreePorts(5);

        // then
        assertThat(freePorts.length, is(5));
        Set<Integer> unique = new HashSet<>();
        for (int port : freePorts) {
            unique.add(port);
        }
        assertThat(unique.size(), is(5));
    }

    @Test
    public void shouldAllocateWithinConfiguredRange() throws IOException {
        // given
        System.setProperty(PortFactory.PORT_RANGE_START_PROPERTY, "23000");
        System.setProperty(PortFactory.PORT_RANGE_END_PROPERTY, "24000");

        // when - many draws so a stray out-of-band value would surface
        for (int i = 0; i < 200; i++) {
            int freePort = PortFactory.findFreePort();

            // then - the port lands inside the band and is bindable
            assertThat(freePort, is(both(greaterThanOrEqualTo(23000)).and(lessThanOrEqualTo(24000))));
            ServerSocket serverSocket = new ServerSocket(freePort);
            assertThat(serverSocket.isBound(), is(true));
            serverSocket.close();
        }
    }

    @Test
    public void shouldAllocateMultipleDistinctPortsWithinConfiguredRange() {
        // given
        System.setProperty(PortFactory.PORT_RANGE_START_PROPERTY, "23000");
        System.setProperty(PortFactory.PORT_RANGE_END_PROPERTY, "24000");

        // when
        int[] freePorts = PortFactory.findFreePorts(10);

        // then
        assertThat(freePorts.length, is(10));
        Set<Integer> unique = new HashSet<>();
        for (int port : freePorts) {
            assertThat(port, is(both(greaterThanOrEqualTo(23000)).and(lessThanOrEqualTo(24000))));
            unique.add(port);
        }
        assertThat(unique.size(), is(10));
    }

    @Test
    public void shouldSkipOccupiedPortsWithinConfiguredRange() throws IOException {
        // given - a band wide enough for the padded internal batch, with one port held by a live listener
        System.setProperty(PortFactory.PORT_RANGE_START_PROPERTY, "24500");
        System.setProperty(PortFactory.PORT_RANGE_END_PROPERTY, "24599");
        ServerSocket occupied = new ServerSocket();
        occupied.setReuseAddress(true);
        occupied.bind(new InetSocketAddress(24550));
        try {
            // when - many draws so the occupied number would surface if it were ever handed out
            for (int i = 0; i < 100; i++) {
                int freePort = PortFactory.findFreePort();

                // then - the allocator skips the occupied number but stays in band
                assertThat(freePort, is(both(greaterThanOrEqualTo(24500)).and(lessThanOrEqualTo(24599))));
                assertThat(freePort, is(org.hamcrest.Matchers.not(24550)));
            }
        } finally {
            occupied.close();
        }
    }

    @Test
    public void shouldHonourPortRangeSuppliedToTheForkedJvm() {
        // Positive control for the -Dmockserver.testArgLine fork-propagation path: when THIS JVM was
        // launched with the band properties on its command line (as a surefire/failsafe fork is when
        // mockserver.testArgLine carries them), findFreePort() must return in-band. It reads the
        // values straight off System.getProperty (not via setProperty) so it proves the property
        // reached the running JVM. Skipped when the band is not configured (the normal CI case), so it
        // never fails there and is not a vacuous pass either — a skip is visible.
        String start = System.getProperty(PortFactory.PORT_RANGE_START_PROPERTY);
        String end = System.getProperty(PortFactory.PORT_RANGE_END_PROPERTY);
        org.junit.Assume.assumeTrue("no test port band configured on this JVM (set both "
                + PortFactory.PORT_RANGE_START_PROPERTY + " and " + PortFactory.PORT_RANGE_END_PROPERTY
                + " to exercise this control)",
            start != null && !start.trim().isEmpty() && end != null && !end.trim().isEmpty());
        int lo = Integer.parseInt(start.trim());
        int hi = Integer.parseInt(end.trim());
        for (int i = 0; i < 25; i++) {
            int port = PortFactory.findFreePort();
            assertThat("findFreePort must return a port inside the JVM-configured band",
                port, is(both(greaterThanOrEqualTo(lo)).and(lessThanOrEqualTo(hi))));
        }
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenOnlyStartIsSet() {
        System.setProperty(PortFactory.PORT_RANGE_START_PROPERTY, "23000");
        PortFactory.findFreePort();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenRangeIsReversed() {
        System.setProperty(PortFactory.PORT_RANGE_START_PROPERTY, "24000");
        System.setProperty(PortFactory.PORT_RANGE_END_PROPERTY, "23000");
        PortFactory.findFreePort();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenRangeIsNotNumeric() {
        System.setProperty(PortFactory.PORT_RANGE_START_PROPERTY, "not-a-port");
        System.setProperty(PortFactory.PORT_RANGE_END_PROPERTY, "24000");
        PortFactory.findFreePort();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenRangeTooNarrowForRequestedCount() {
        // The internal batch is always at least the requested count, so a band narrower than the
        // count (here width 2 for a count of 3) can never satisfy it and is raised loudly.
        System.setProperty(PortFactory.PORT_RANGE_START_PROPERTY, "25000");
        System.setProperty(PortFactory.PORT_RANGE_END_PROPERTY, "25001");
        PortFactory.findFreePorts(3);
    }

}
