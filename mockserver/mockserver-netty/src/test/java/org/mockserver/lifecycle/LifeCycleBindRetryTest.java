package org.mockserver.lifecycle;

import io.netty.channel.Channel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.PortFactory;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;

/**
 * Drives {@code LifeCycle.bindPort}'s shadow handling through the package-private {@link LifeCycle.BindVerifier}
 * seam, so the retry and failure paths run on every operating system, not only where the kernel allows a
 * shadowed bind.
 */
public class LifeCycleBindRetryTest {

    private MockServer mockServer;
    private int originalPort;

    @Before
    public void startServer() {
        mockServer = new MockServer();
        originalPort = mockServer.getLocalPort();
    }

    @After
    public void stopServer() {
        mockServer.stop();
    }

    @Test
    public void shouldGiveUpOnEphemeralPortAfterMaxAttemptsWithEscalatingTimeoutsAndCloseEveryShadowedChannel() throws Exception {
        List<Channel> probedChannels = new CopyOnWriteArrayList<>();
        List<Long> acceptTimeouts = new CopyOnWriteArrayList<>();
        ((LifeCycle) mockServer).bindVerifier = verifier(probedChannels, acceptTimeouts, Integer.MAX_VALUE, Collections.emptyList());

        try {
            mockServer.bindServerPorts(Collections.singletonList(0));
            fail("expected ephemeral bind to give up after " + LifeCycle.SHADOW_PROBE_MAX_EPHEMERAL_ATTEMPTS + " shadowed attempts");
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage(), is("Exception while binding MockServer to port 0"));
            assertThat(expected.getCause(), instanceOf(BindException.class));
            assertThat(expected.getCause().getMessage(), startsWith("no usable free port found after " + LifeCycle.SHADOW_PROBE_MAX_EPHEMERAL_ATTEMPTS + " attempts, the last one because port "));
        }

        assertThat(probedChannels, hasSize(LifeCycle.SHADOW_PROBE_MAX_EPHEMERAL_ATTEMPTS));
        assertThat(acceptTimeouts, contains(250L, 500L, 1000L, 2000L, 4000L, 5000L, 5000L, 5000L, 5000L, 5000L));
        for (Channel channel : probedChannels) {
            assertThat(channel.closeFuture().await(5, TimeUnit.SECONDS), is(true));
        }
        assertThat(mockServer.getLocalPorts(), contains(originalPort));
    }

    @Test
    public void shouldRetryEphemeralBindWhenTheRetryCandidatePortIsAlreadyTaken() throws Exception {
        List<Channel> probedChannels = new CopyOnWriteArrayList<>();
        // first candidate is this server's own port, so binding it fails; the second lets the OS choose
        ((LifeCycle) mockServer).bindVerifier = verifier(probedChannels, new ArrayList<>(), 1, Arrays.asList(originalPort, 0));

        List<Integer> boundPorts = mockServer.bindServerPorts(Collections.singletonList(0));

        assertThat(boundPorts, hasSize(1));
        assertThat(boundPorts.get(0), not(originalPort));
        assertThat(probedChannels, hasSize(2));
        assertThat(probedChannels.get(0).closeFuture().await(5, TimeUnit.SECONDS), is(true));
        assertThat(probedChannels.get(1).isOpen(), is(true));
        assertThat(mockServer.getLocalPorts(), contains(originalPort, boundPorts.get(0)));
    }

    @Test
    public void shouldFailExplicitPortOnFirstShadowWithoutRetrying() throws Exception {
        List<Channel> probedChannels = new CopyOnWriteArrayList<>();
        List<Long> acceptTimeouts = new CopyOnWriteArrayList<>();
        AtomicInteger candidateRequests = new AtomicInteger();
        LifeCycle.BindVerifier shadowed = verifier(probedChannels, acceptTimeouts, Integer.MAX_VALUE, Collections.emptyList());
        ((LifeCycle) mockServer).bindVerifier = new LifeCycle.BindVerifier() {
            @Override
            public InetSocketAddress findShadowedLoopback(Channel serverChannel, long acceptTimeoutMillis) throws InterruptedException {
                return shadowed.findShadowedLoopback(serverChannel, acceptTimeoutMillis);
            }

            @Override
            public int nextCandidatePort() {
                candidateRequests.incrementAndGet();
                return 0;
            }
        };
        int explicitPort = PortFactory.findFreePort();

        try {
            mockServer.bindServerPorts(Collections.singletonList(explicitPort));
            fail("expected a shadowed explicit port to fail");
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage(), is("Exception while binding MockServer to port " + explicitPort));
            assertThat(expected.getCause(), instanceOf(BindException.class));
            assertThat(expected.getCause().getMessage(), is("port " + explicitPort + " is already in use by another application listening on 127.0.0.1:" + explicitPort
                + ", so requests to localhost:" + explicitPort + " would reach that application instead of MockServer; stop that application or choose a different port"
                + " (to find it run: lsof -nP -iTCP:" + explicitPort + " -sTCP:LISTEN)"));
        }

        assertThat(probedChannels, hasSize(1));
        assertThat(acceptTimeouts, contains(5000L));
        assertThat(candidateRequests.get(), is(0));
        assertThat(probedChannels.get(0).closeFuture().await(5, TimeUnit.SECONDS), is(true));
        assertThat(mockServer.getLocalPorts(), contains(originalPort));
    }

    @Test
    public void shouldCloseTheNewChannelAndRestoreTheInterruptWhenTheCheckIsInterrupted() throws Exception {
        List<Channel> probedChannels = new CopyOnWriteArrayList<>();
        ((LifeCycle) mockServer).bindVerifier = new LifeCycle.BindVerifier() {
            @Override
            public InetSocketAddress findShadowedLoopback(Channel serverChannel, long acceptTimeoutMillis) throws InterruptedException {
                probedChannels.add(serverChannel);
                throw new InterruptedException("interrupted while checking");
            }

            @Override
            public int nextCandidatePort() {
                return 0;
            }
        };

        try {
            mockServer.bindServerPorts(Collections.singletonList(0));
            fail("expected the interrupted check to fail the bind");
        } catch (RuntimeException expected) {
            assertThat(expected.getCause(), instanceOf(InterruptedException.class));
            assertThat(Thread.currentThread().isInterrupted(), is(true));
        } finally {
            Thread.interrupted();
        }

        assertThat(probedChannels, hasSize(1));
        assertThat(probedChannels.get(0).closeFuture().await(5, TimeUnit.SECONDS), is(true));
        assertThat(mockServer.getLocalPorts(), contains(originalPort));
    }

    @Test
    public void shouldCloseTheNewChannelWhenTheCheckFails() throws Exception {
        List<Channel> probedChannels = new CopyOnWriteArrayList<>();
        ((LifeCycle) mockServer).bindVerifier = new LifeCycle.BindVerifier() {
            @Override
            public InetSocketAddress findShadowedLoopback(Channel serverChannel, long acceptTimeoutMillis) {
                probedChannels.add(serverChannel);
                throw new IllegalStateException("check failed");
            }

            @Override
            public int nextCandidatePort() {
                return 0;
            }
        };
        int explicitPort = PortFactory.findFreePort();

        try {
            mockServer.bindServerPorts(Collections.singletonList(explicitPort));
            fail("expected the failed check to fail the bind");
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage(), is("Exception while binding MockServer to port " + explicitPort));
            assertThat(expected.getCause(), instanceOf(IllegalStateException.class));
        }

        assertThat(probedChannels, hasSize(1));
        assertThat(probedChannels.get(0).isOpen(), is(false));
        assertThat(mockServer.getLocalPorts(), contains(originalPort));
        // the port was released, so it can be bound again
        try (ServerSocket rebound = new ServerSocket(explicitPort)) {
            assertThat(rebound.getLocalPort(), is(explicitPort));
        }
    }

    @Test
    public void shouldBracketIpv6AddressesInTheConflictMessage() throws Exception {
        assertThat(LifeCycle.explicitPortConflict(1080, new InetSocketAddress(InetAddress.getByName("::1"), 1080)).getMessage(),
            startsWith("port 1080 is already in use by another application listening on [::1]:1080, so requests to localhost:1080 would reach that application instead of MockServer"));
        assertThat(LifeCycle.explicitPortConflict(1080, new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1080)).getMessage(),
            startsWith("port 1080 is already in use by another application listening on 127.0.0.1:1080, so"));
    }

    /**
     * @param shadowedCalls how many probes report a shadow before later probes report none
     * @param candidates    ports returned, in order, by nextCandidatePort (0 once exhausted)
     */
    private static LifeCycle.BindVerifier verifier(List<Channel> probedChannels, List<Long> acceptTimeouts, int shadowedCalls, List<Integer> candidates) {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger nextCandidate = new AtomicInteger();
        return new LifeCycle.BindVerifier() {
            @Override
            public InetSocketAddress findShadowedLoopback(Channel serverChannel, long acceptTimeoutMillis) {
                probedChannels.add(serverChannel);
                acceptTimeouts.add(acceptTimeoutMillis);
                if (calls.incrementAndGet() > shadowedCalls) {
                    return null;
                }
                return new InetSocketAddress("127.0.0.1", ((InetSocketAddress) serverChannel.localAddress()).getPort());
            }

            @Override
            public int nextCandidatePort() {
                int index = nextCandidate.getAndIncrement();
                return index < candidates.size() ? candidates.get(index) : 0;
            }
        };
    }
}
