package org.mockserver.client;

import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.SocketCommunicationException;
import org.mockserver.socket.PortFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Deterministic proof that {@link MockServerClient#hasStopped()} distinguishes a MockServer that has
 * genuinely stopped from one that is still bound but too slow to answer.
 *
 * <p><strong>The defect this pins down.</strong> {@code hasStopped()} used to probe with
 * {@code ignoreErrors=true}, which collapses <em>both</em> a connection refusal and a read timeout
 * into a {@code null} response, and then treated that {@code null} as "stopped". That is a fail-open:
 * a MockServer that was alive and still holding its port - merely paused by GC or starved of CPU -
 * reported itself stopped. Callers then rebound the port and got a {@code BindException} raised far
 * away from the real cause, which is what made the failure look like a port race.
 *
 * <p>These tests exercise the two cases against real sockets, so they are deterministic rather than
 * timing-dependent: an unresponsive-but-bound server always times out, and a closed port always
 * refuses. {@code shouldNotReportStoppedWhenStillBoundButUnresponsive} fails against the old
 * fail-open implementation and passes against the fixed one.
 */
public class MockServerClientHasStoppedTest {

    /**
     * Short enough to keep the test fast, and set on a per-client {@link Configuration} rather than
     * via the global {@code ConfigurationProperties} so this test does not mutate shared state and
     * can keep running in parallel with the rest of the suite.
     */
    private static final long SOCKET_TIMEOUT_MILLIS = 1000L;

    private ServerSocket unresponsiveServer;
    private final List<Socket> acceptedConnections = new ArrayList<>();
    private MockServerClient mockServerClient;

    @Before
    public void startUnresponsiveServer() throws IOException {
        unresponsiveServer = new ServerSocket(0);
        Thread acceptThread = new Thread(() -> {
            try {
                while (!unresponsiveServer.isClosed()) {
                    // accept the connection and then deliberately never write a response, which is
                    // what a MockServer paused by a long GC or starved of CPU looks like to a client
                    synchronized (acceptedConnections) {
                        acceptedConnections.add(unresponsiveServer.accept());
                    }
                }
            } catch (IOException ignore) {
                // expected once the socket is closed during teardown
            }
        }, "unresponsive-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @After
    public void tearDown() throws IOException {
        // close the listening socket FIRST so that the client's own shutdown probe is refused
        // rather than timing out, which keeps teardown fast
        if (unresponsiveServer != null) {
            unresponsiveServer.close();
        }
        synchronized (acceptedConnections) {
            for (Socket socket : acceptedConnections) {
                try {
                    socket.close();
                } catch (IOException ignore) {
                    // best effort
                }
            }
            acceptedConnections.clear();
        }
        if (mockServerClient != null) {
            mockServerClient.stop();
            mockServerClient = null;
        }
    }

    @Test
    public void shouldNotReportStoppedWhenStillBoundButUnresponsive() {
        // given - a server that accepts connections but never responds, i.e. still holding its port
        Configuration configuration = configuration().maxSocketTimeoutInMillis(SOCKET_TIMEOUT_MILLIS);
        mockServerClient = new MockServerClient(configuration, "localhost", unresponsiveServer.getLocalPort());

        // then - a read timeout must not be reported as "stopped": the port is demonstrably still held
        assertThat(
            "a read timeout against a still-bound MockServer must NOT be reported as stopped, "
                + "otherwise callers rebind a port that is still in use",
            mockServerClient.hasStopped(),
            is(false)
        );
    }

    /**
     * Pins the {@code ReadTimeoutException} arm of {@link MockServerClient#isTimeoutFailure(Throwable)}.
     *
     * <p>A timeout reaches the client in two shapes and which one wins is a race between two
     * independent timers, so an end-to-end socket test cannot reliably reach both. This asserts the
     * discrimination directly, as a pure function, so the arm that only fires under load - the one
     * that would otherwise silently restore the fail-open - cannot be deleted without a red build.
     */
    @Test
    public void shouldRecogniseBothShapesOfTimeoutFailure() {
        assertThat(
            "the future.get(timeout) shape must be recognised as a timeout",
            MockServerClient.isTimeoutFailure(new SocketCommunicationException("timed out", null)),
            is(true)
        );
        assertThat(
            "Netty's ReadTimeoutException arrives wrapped in a plain RuntimeException and must still "
                + "be recognised as a timeout, otherwise the fail-open returns under load",
            MockServerClient.isTimeoutFailure(new RuntimeException(
                "Exception while sending request - io.netty.handler.timeout.ReadTimeoutException",
                new ExecutionException(ReadTimeoutException.INSTANCE)
            )),
            is(true)
        );
        assertThat(
            "an unrelated failure must not be mistaken for a timeout",
            MockServerClient.isTimeoutFailure(new RuntimeException("something else", new IllegalStateException())),
            is(false)
        );
    }

    /**
     * The wait budget must be derived from the shutdown it is waiting for. A fixed budget shorter than
     * {@code stopDrainMillis} would abandon the wait while the server was still draining and still
     * holding its port - reintroducing the very BindException this change removes.
     */
    @Test
    public void shouldSizeStopWaitFromDrainAndSocketTimeout() {
        assertThat(
            "a long drain must extend the wait beyond the floor, not be ignored",
            MockServerClient.stopWaitTimeoutMillis(60_000L, 20_000L),
            is(80_000L)
        );
        assertThat(
            "the wait must never drop below the 30s floor for small drains",
            MockServerClient.stopWaitTimeoutMillis(0L, 1_000L),
            is(30_000L)
        );
        assertThat(
            "the default drain plus the default socket timeout must exceed the old hardcoded 10s bound",
            MockServerClient.stopWaitTimeoutMillis(15_000L, 20_000L) > SECONDS.toMillis(10),
            is(true)
        );
    }

    /**
     * The blocking {@code stop()} must scale with the drain too, and must outlast the background wait.
     *
     * <p>Sizing the loop correctly while leaving the wrapper on a fixed budget would just move the
     * premature return up a level: {@code stop()} would abandon the future while the server was still
     * draining and still holding its port. And a wrapper budget that did not exceed the loop's would
     * always time out before the loop could conclude, which would be a new defect rather than a fix.
     */
    @Test
    public void shouldSizeBlockingStopAboveTheBackgroundWait() {
        assertThat(
            "the blocking wait must exceed the background wait, or it always abandons it early",
            MockServerClient.stopBlockingTimeoutMillis(15_000L, 20_000L)
                > MockServerClient.stopWaitTimeoutMillis(15_000L, 20_000L),
            is(true)
        );
        assertThat(
            "a long drain must extend the blocking wait, not be ignored",
            MockServerClient.stopBlockingTimeoutMillis(60_000L, 20_000L),
            is(100_000L)
        );
        assertThat(
            "the default drain must push the blocking wait well beyond the old hardcoded 10s",
            MockServerClient.stopBlockingTimeoutMillis(15_000L, 20_000L) > SECONDS.toMillis(10),
            is(true)
        );
    }

    @Test
    public void shouldReportStoppedWhenNothingIsListening() throws IOException {
        // given - a port with nothing listening on it, so connections are refused
        int freePort = PortFactory.findFreePort();
        Configuration configuration = configuration().maxSocketTimeoutInMillis(SOCKET_TIMEOUT_MILLIS);
        mockServerClient = new MockServerClient(configuration, "localhost", freePort);

        // then - a refused connection is unambiguous, so this really is stopped
        assertThat(
            "a refused connection means MockServer has genuinely stopped",
            mockServerClient.hasStopped(),
            is(true)
        );
    }
}
