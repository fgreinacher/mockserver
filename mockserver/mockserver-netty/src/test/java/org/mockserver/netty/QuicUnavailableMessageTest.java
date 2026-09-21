package org.mockserver.netty;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Covers the HTTP/3 fail-fast message on every platform.
 * <p>
 * The throw it belongs to is only reachable where the QUIC native is absent, which is no CI agent we
 * run and no developer machine here - so {@code Http3LifecycleTest.shouldFailFastWhenQuicUnavailable}
 * is assume-skipped everywhere and proves nothing in practice. Since the message IS the feature (it is
 * the only thing standing between a user and a dead end), it is asserted directly here, where it runs.
 */
public class QuicUnavailableMessageTest {

    @Test
    public void shouldNameEveryRouteToTheNativeLibrary() {
        String message = MockServer.quicUnavailableMessage(8443);

        assertThat("must name the standalone-jar classifier", message, containsString("jar-with-dependencies-http3"));
        assertThat("must name the Maven/Gradle coordinate", message, containsString("io.netty:netty-codec-native-quic"));
        assertThat("must name the container mount point", message, containsString("/libs"));
        assertThat("must offer the do-nothing way out", message, containsString("remove http3Port"));
    }

    @Test
    public void shouldReportThePortThatTriggeredTheFailure() {
        // the port is what ties the message to the user's own configuration line
        assertThat(MockServer.quicUnavailableMessage(8443), containsString("http3Port=8443"));
        assertThat(MockServer.quicUnavailableMessage(1081), containsString("http3Port=1081"));
        assertThat(MockServer.quicUnavailableMessage(8443), not(containsString("http3Port=1081")));
    }

    @Test
    public void shouldSayWhatStillWorksWithoutIt() {
        // a user hitting this has a server that refuses to start; the message must not read as though
        // MockServer itself is broken
        assertThat(MockServer.quicUnavailableMessage(8443), containsString("HTTP/1.1 and HTTP/2"));
    }
}
