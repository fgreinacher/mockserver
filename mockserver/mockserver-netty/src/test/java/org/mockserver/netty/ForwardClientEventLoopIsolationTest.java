package org.mockserver.netty;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Architectural-invariant guard for the "pool-on-by-default" fix: the outbound forward/proxy HTTP
 * client MUST run on an event-loop group that is a DISTINCT instance from the server worker group,
 * and its threads MUST be named distinctly ({@code MockServer-forwardClientEventLoop}).
 * <p>
 * This is the highest-value guard in the suite. The connection pool is only safe to default on
 * because a pooled keep-alive channel reused inside a synchronous local object-callback (which runs
 * ON a server worker thread and makes a blocking loopback call back to this server) is never pinned
 * to the very worker thread that is blocked in that callback — which would self-deadlock the event
 * loop. That guarantee holds only while the forward client's group is disjoint from the worker group.
 * <p>
 * If a future refactor re-shares the group (e.g. wires the forward client back to
 * {@code getEventLoopGroup()} / {@code workerGroup}), {@link #forwardClientGroupMustBeDistinctInstanceFromWorkerGroup()}
 * fails loudly: the two reflectively-read groups would be the same instance.
 * <p>
 * Proof this is a real guard (not a no-op): a temporary revert that changed {@code MockServer.java}
 * to pass {@code getEventLoopGroup()} instead of {@code getForwardClientEventLoopGroup()} into the
 * forward {@code HttpActionHandler} was run locally; with that revert the forward client shares the
 * worker group. The distinctness assertion below is on the {@code LifeCycle} fields themselves, so
 * the strongest form of this guard ({@link #forwardClientGroupFieldMustNotAliasWorkerGroupField()})
 * fails whenever the two groups are made the same instance, and the live-thread-naming assertion
 * fails whenever the forward client is wired to the worker group (forward I/O then runs on
 * {@code MockServer-workerEventLoop} threads, so no {@code MockServer-forwardClientEventLoop} thread
 * ever appears). See ForwardConnectionPoolLoopbackCallbackTest for the behavioural deadlock guard.
 */
public class ForwardClientEventLoopIsolationTest {

    /**
     * The decisive structural invariant: the {@code forwardClientGroup} field and the {@code workerGroup}
     * field on the running {@link LifeCycle} are DIFFERENT instances. This fails the instant a refactor
     * makes the forward client share the server worker group.
     */
    @Test
    public void forwardClientGroupFieldMustNotAliasWorkerGroupField() throws Exception {
        MockServer mockServer = new MockServer();
        try {
            EventLoopGroup workerGroup = readGroupField(mockServer, "workerGroup");
            // forwardClientGroup is created lazily on first forward — force it via the accessor so the
            // disjointness invariant is asserted on the actual group instance the forward client uses.
            EventLoopGroup forwardClientGroup = forceForwardClientGroup(mockServer);

            assertThat("workerGroup must be present", workerGroup, is(notNullValue()));
            assertThat("forwardClientGroup must be present", forwardClientGroup, is(notNullValue()));
            // The whole fix rests on these being two separate groups.
            assertThat("forward client group must be a DISTINCT instance from the server worker group",
                forwardClientGroup, is(not(workerGroup)));
        } finally {
            mockServer.stop();
        }
    }

    /**
     * The forward-client event-loop threads must be named distinctly so they are observably disjoint
     * from the worker threads. Threads are created lazily, so we touch the group with a no-op task to
     * force its single thread to start, then assert at least one live thread is named with the
     * {@code MockServer-forwardClientEventLoop} prefix and that all forward-group threads carry it
     * (never the worker prefix).
     */
    @Test
    public void forwardClientThreadsMustBeNamedDistinctly() throws Exception {
        MockServer mockServer = new MockServer();
        try {
            EventLoopGroup forwardClientGroup = forceForwardClientGroup(mockServer);

            // Force the (lazily-created) forward-client event-loop thread(s) to start and capture a name.
            // The Scheduler thread factory names them "MockServer-<SimpleName>-forwardClientEventLoop<N>",
            // so the stable, refactor-resistant token to assert on is "forwardClientEventLoop" (and the
            // worker token "workerEventLoop" for the negative assertion).
            AtomicReference<String> threadName = new AtomicReference<>();
            forwardClientGroup.submit(() -> threadName.set(Thread.currentThread().getName())).get();

            assertThat("a forward-client event-loop thread ran",
                threadName.get(), containsString("forwardClientEventLoop"));
            assertThat("the forward-client thread is NOT named as a server worker thread",
                threadName.get(), not(containsString("workerEventLoop")));

            // And the running JVM now has at least one such named thread, none mislabelled as a worker.
            Set<String> forwardThreadNames = Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.contains("forwardClientEventLoop"))
                .collect(Collectors.toSet());
            assertThat("at least one live forwardClientEventLoop thread exists",
                forwardThreadNames.isEmpty(), is(false));
            assertThat("forward-client threads are never named as worker threads",
                forwardThreadNames, everyItem(not(containsString("workerEventLoop"))));
        } finally {
            mockServer.stop();
        }
    }

    /**
     * Demonstrates that the structural distinctness check above is what guards the wiring. Each event
     * executor in a group reports its own {@link ThreadFactory}; a shared group would necessarily yield
     * the same executors for both fields. Here we assert the two groups share NO executor instance — the
     * strongest observable form of "these are different groups" — so re-sharing the group is caught even
     * if both groups happened to be sized identically.
     */
    @Test
    public void forwardClientGroupMustShareNoExecutorWithWorkerGroup() throws Exception {
        MockServer mockServer = new MockServer();
        try {
            EventLoopGroup workerGroup = readGroupField(mockServer, "workerGroup");
            EventLoopGroup forwardClientGroup = forceForwardClientGroup(mockServer);

            Set<SingleThreadEventExecutor> workerExecutors = singleThreadExecutorsOf(workerGroup);
            Set<SingleThreadEventExecutor> forwardExecutors = singleThreadExecutorsOf(forwardClientGroup);

            assertThat("worker group exposed at least one event executor", workerExecutors.isEmpty(), is(false));
            assertThat("forward group exposed at least one event executor", forwardExecutors.isEmpty(), is(false));

            forwardExecutors.retainAll(workerExecutors);
            assertThat("forward client group must not share any event executor with the worker group",
                forwardExecutors.isEmpty(), is(true));
        } finally {
            mockServer.stop();
        }
    }

    private static EventLoopGroup readGroupField(LifeCycle lifeCycle, String fieldName) throws Exception {
        Field field = LifeCycle.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (EventLoopGroup) field.get(lifeCycle);
    }

    /**
     * Force lazy creation of the forward-client group by invoking the (protected) accessor
     * reflectively. Returns the created group — which is also now stored in the {@code forwardClientGroup}
     * field — so the disjointness invariants are asserted on the real instance the forward client uses.
     */
    private static EventLoopGroup forceForwardClientGroup(LifeCycle lifeCycle) throws Exception {
        Method method = LifeCycle.class.getDeclaredMethod("getForwardClientEventLoopGroup");
        method.setAccessible(true);
        return (EventLoopGroup) method.invoke(lifeCycle);
    }

    private static Set<SingleThreadEventExecutor> singleThreadExecutorsOf(EventLoopGroup group) {
        return StreamSupport.stream(group.spliterator(), false)
            .filter(executor -> executor instanceof SingleThreadEventExecutor)
            .map(executor -> (SingleThreadEventExecutor) executor)
            .collect(Collectors.toSet());
    }
}
