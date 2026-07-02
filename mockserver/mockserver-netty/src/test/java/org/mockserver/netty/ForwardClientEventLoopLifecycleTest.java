package org.mockserver.netty;

import io.netty.channel.EventLoopGroup;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Lifecycle / leak guard for the dedicated forward-client event-loop group introduced by the
 * "pool-on-by-default" fix. Starting and stopping a {@link MockServer} repeatedly must:
 * <ul>
 *   <li>shut the {@code forwardClientGroup} down on every stop (its termination future completes), and</li>
 *   <li>leave NO lingering {@code MockServer-forwardClientEventLoop} threads behind once the JVM has
 *       settled — otherwise each start/stop cycle would leak a thread pool.</li>
 * </ul>
 * Because the disjoint group is created in the {@code LifeCycle} constructor and torn down in
 * {@code stopAsync()}, a regression that forgets to shut it down (or shares it with a group that
 * outlives the server) would surface here as leaked threads after stop.
 */
public class ForwardClientEventLoopLifecycleTest {

    // The Scheduler thread factory names forward-client threads
    // "MockServer-<SimpleName>-forwardClientEventLoop<N>", so the stable token to match is the
    // distinctive "forwardClientEventLoop" infix rather than a brittle leading prefix.
    private static final String FORWARD_THREAD_TOKEN = "forwardClientEventLoop";

    @Test
    public void forwardClientGroupIsShutDownAndLeavesNoThreadsAcrossStartStopCycles() throws Exception {
        int baseline = countForwardClientThreads();

        for (int cycle = 0; cycle < 3; cycle++) {
            MockServer mockServer = new MockServer();

            // The group itself is now created lazily on first forward; force that creation via the
            // accessor, then force its thread to start, so we genuinely assert it gets cleaned up.
            EventLoopGroup forwardClientGroup = forceForwardClientGroup(mockServer);
            forwardClientGroup.submit(() -> { }).get();
            assertThat("forward client group is live while the server runs",
                forwardClientGroup.isShutdown(), is(false));

            mockServer.stop();

            // stop() blocks until stopAsync() has awaited terminationFuture() on all groups, so by the
            // time stop() returns the forward client group must be fully shut down (graceful, awaited).
            assertThat("forward client group is shut down after stop()",
                forwardClientGroup.isShutdown(), is(true));
            assertThat("forward client group is fully terminated after stop()",
                forwardClientGroup.isTerminated(), is(true));
        }

        // After all cycles, no forward-client threads should remain above the pre-test baseline. Netty
        // tears threads down asynchronously after terminationFuture() completes, so allow a brief,
        // bounded settle window rather than asserting instantaneously.
        long deadline = System.currentTimeMillis() + 10_000L;
        int remaining;
        while ((remaining = countForwardClientThreads()) > baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat("no MockServer-forwardClientEventLoop threads leaked across start/stop cycles "
                + "(baseline " + baseline + ", remaining " + remaining + ": " + currentForwardThreadNames() + ")",
            remaining <= baseline, is(true));
    }

    private static EventLoopGroup forceForwardClientGroup(LifeCycle lifeCycle) throws Exception {
        Method method = LifeCycle.class.getDeclaredMethod("getForwardClientEventLoopGroup");
        method.setAccessible(true);
        return (EventLoopGroup) method.invoke(lifeCycle);
    }

    private static int countForwardClientThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream()
            .map(Thread::getName)
            .filter(name -> name.contains(FORWARD_THREAD_TOKEN))
            .count();
    }

    private static Set<String> currentForwardThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
            .map(Thread::getName)
            .filter(name -> name.contains(FORWARD_THREAD_TOKEN))
            .collect(Collectors.toSet());
    }
}
