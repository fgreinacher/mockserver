package org.mockserver.socket;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

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
        java.util.Set<Integer> unique = new java.util.HashSet<>();
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
        java.util.Set<Integer> unique = new java.util.HashSet<>();
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
        occupied.bind(new java.net.InetSocketAddress(24550));
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
