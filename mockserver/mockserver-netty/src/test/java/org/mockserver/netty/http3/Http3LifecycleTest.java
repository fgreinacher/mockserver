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
 *       or gracefully falls back (does not crash) if unavailable</li>
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
        // this test verifies lifecycle integration: if QUIC is available, the server starts;
        // if not, it gracefully falls back (no crash either way)
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

        MockServer server2 = null;
        try {
            server2 = new MockServer(configuration2, 0);
            assertThat("main server should be running", server2.getLocalPort(), is(greaterThan(0)));

            if (Http3Server.isQuicAvailable()) {
                assertThat("HTTP/3 should be started when QUIC is available",
                    server2.getHttp3Port(), is(greaterThan(0)));
            } else {
                assertThat("HTTP/3 port should be -1 when QUIC is unavailable",
                    server2.getHttp3Port(), is(-1));
            }
        } finally {
            if (server2 != null) {
                server2.stop();
            }
        }
    }

    @Test
    public void shouldNotCrashWhenQuicUnavailable() {
        // This test ensures that MockServer starts successfully even if http3Port
        // is configured but the native QUIC transport is not available. The fail-soft
        // path logs a warning and continues.
        int udpPort = findAvailableUdpPort();
        Configuration configuration = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);
        MockServer server = new MockServer(configuration, 0);
        try {
            assertThat("MockServer should start without crashing", server.isRunning(), is(true));
            assertThat("main server should be running", server.getLocalPort(), is(greaterThan(0)));
            // HTTP/3 may or may not be started depending on platform -- but no crash
        } finally {
            server.stop();
        }
    }

    @Test
    public void shouldStopHttp3ServerOnShutdown() {
        int udpPort = findAvailableUdpPort();
        Configuration configuration = configuration().http3Port(udpPort).http3MaxIdleTimeout(30000L);
        MockServer server = new MockServer(configuration, 0);

        // only assert port transition if QUIC actually started
        Assume.assumeTrue("HTTP/3 server did not start (QUIC unavailable)", server.getHttp3Port() > 0);

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
