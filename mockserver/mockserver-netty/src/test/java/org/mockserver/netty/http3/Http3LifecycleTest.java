package org.mockserver.netty.http3;

import org.junit.Assume;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.http3.Http3Server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Tests for HTTP/3 lifecycle integration with MockServer.
 * <p>
 * These tests verify that:
 * <ul>
 *   <li>When {@code http3Port=0} (default), no HTTP/3 server is started</li>
 *   <li>When {@code http3Port>0}, the HTTP/3 server starts if native QUIC is available,
 *       and start-up FAILS with an actionable message if it is not</li>
 *   <li>The HTTP/3 server is stopped during MockServer shutdown</li>
 * </ul>
 */
public class Http3LifecycleTest {

    @Test
    public void shouldNotStartHttp3WhenPortIsZero() {
        Configuration configuration = configuration().http3Port(0);
        MockServer server = new MockServer(configuration, 0);
        try {
            assertThat("main server should be running", server.getLocalPort(), is(greaterThan(0)));
            assertThat("HTTP/3 should not be started when port is 0", server.getHttp3Port(), is(-1));
        } finally {
            server.stop();
        }
    }

    @Test
    public void shouldStartHttp3WhenPortConfiguredAndQuicAvailable() {
        // this test verifies lifecycle integration where the native IS available; the
        // unavailable case is a start-up failure, covered by shouldFailFastWhenQuicUnavailable
        Configuration configuration = configuration().http3Port(0); // use 0 first to get a baseline
        MockServer server = new MockServer(configuration, 0);
        try {
            assertThat("main server should be running", server.getLocalPort(), is(greaterThan(0)));
        } finally {
            server.stop();
        }

        // now try with a dynamic UDP port to trigger the HTTP/3 startup path
        int udpPort = findAvailableUdpPort();
        Configuration configuration2 = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);

        // the unavailable case is a start-up FAILURE now, and is asserted by
        // shouldFailFastWhenQuicUnavailable below rather than folded in as an else-branch here
        Assume.assumeTrue("native QUIC not available on this platform", Http3Server.isQuicAvailable());

        MockServer server2 = null;
        try {
            server2 = new MockServer(configuration2, 0);
            assertThat("main server should be running", server2.getLocalPort(), is(greaterThan(0)));

            assertThat("HTTP/3 should be started when QUIC is available",
                server2.getHttp3Port(), is(greaterThan(0)));
        } finally {
            if (server2 != null) {
                server2.stop();
            }
        }
    }

    /**
     * The QUIC natives no longer ship in the default artifacts, so "http3Port set, native missing" is
     * a REAL user state rather than a rare platform quirk. It used to log a warning and start anyway,
     * which left the UDP port the user asked for silently unserved; it is now a hard start-up failure
     * whose message names the artifact to use.
     * <p>
     * This assertion only runs where the native is genuinely absent. That is the honest scope - the
     * alternative (forcing unavailability by mangling the classpath from inside the test) would prove
     * something about the mangling rather than about the server.
     */
    @Test
    public void shouldFailFastWhenQuicUnavailable() {
        Assume.assumeFalse("native QUIC IS available on this platform", Http3Server.isQuicAvailable());

        int udpPort = findAvailableUdpPort();
        Configuration configuration = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);

        IllegalStateException thrown = null;
        MockServer server = null;
        try {
            server = new MockServer(configuration, 0);
        } catch (IllegalStateException ise) {
            thrown = ise;
        } finally {
            if (server != null) {
                server.stop();
            }
        }

        assertThat("configuring http3Port without the QUIC native must fail start-up", thrown, is(notNullValue()));
        // the message is the entire point of the failure - it has to name the way out
        assertThat(thrown.getMessage(), containsString("jar-with-dependencies-http3"));
        assertThat(thrown.getMessage(), containsString("netty-codec-native-quic"));
    }

    @Test
    public void shouldStopHttp3ServerOnShutdown() {
        // probe BEFORE constructing: without the native the constructor now throws, so an assume placed
        // after it would never be reached and a legitimate skip would surface as a test error
        Assume.assumeTrue("native QUIC not available on this platform", Http3Server.isQuicAvailable());

        int udpPort = findAvailableUdpPort();
        Configuration configuration = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);
        MockServer server = new MockServer(configuration, 0);

        Assume.assumeTrue("HTTP/3 server did not start", server.getHttp3Port() > 0);

        server.stop();

        // after stop, HTTP/3 port should no longer be accessible
        assertThat("HTTP/3 should be stopped after MockServer shutdown",
            server.getHttp3Port(), is(-1));
    }

    @Test
    public void shouldExposeHttp3PortAccessor() {
        // verify the getHttp3Port() accessor works correctly when HTTP/3 is not configured
        Configuration configuration = configuration();
        MockServer server = new MockServer(configuration, 0);
        try {
            assertThat("getHttp3Port should return -1 when not configured",
                server.getHttp3Port(), is(-1));
        } finally {
            server.stop();
        }
    }

    /**
     * Find a free UDP port for the HTTP/3 (QUIC) server.
     *
     * <p>Delegates to the shared {@link org.mockserver.testing.socket.TestPortFactory} so the probing
     * strategy lives in one place. A failure to find a port is now raised rather than reported as port
     * {@code 0}: {@code http3Port(0)} means "HTTP/3 disabled", which would silently turn an
     * infrastructure failure into a test that skips or asserts against a server that never started.
     */
    private static int findAvailableUdpPort() {
        return org.mockserver.testing.socket.TestPortFactory.findFreeUdpPort();
    }
}
