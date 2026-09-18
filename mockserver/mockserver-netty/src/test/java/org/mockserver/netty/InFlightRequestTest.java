package org.mockserver.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Coverage for {@link InFlightRequest} — the WS7.2 graceful-shutdown drain token and its channel
 * {@code closeFuture} safety net.
 *
 * <p>The load-bearing test is {@link #completedTokensAreNotRetainedOnAKeepAliveConnection()}: it
 * reproduces the per-request heap leak on the HTTP/1.1 connection-channel shape (a single channel
 * whose {@code closeFuture} never completes while many requests flow over it) and proves the fix
 * frees each completed token. On the pre-fix code — where the close-future listener was added but
 * never removed — the {@link WeakReference}s stay reachable through the channel's {@code closeFuture}
 * and the assertion fails.</p>
 */
public class InFlightRequestTest {

    /**
     * A {@link LifeCycle} whose in-flight counter is backed by a real {@link AtomicInteger}, so the
     * started/complete increments and decrements are observable. Mockito is used only to obtain a
     * concrete {@link LifeCycle}; the counter itself is real, so double-completion or a leaked
     * decrement would show up in {@link #inFlight}.
     */
    private LifeCycle counterBackedServer(AtomicInteger inFlight) {
        LifeCycle server = mock(LifeCycle.class);
        doAnswer(invocation -> {
            inFlight.incrementAndGet();
            return null;
        }).when(server).requestProcessingStarted();
        doAnswer(invocation -> {
            inFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
            return null;
        }).when(server).requestProcessingComplete();
        return server;
    }

    @Test
    public void completedTokensAreNotRetainedOnAKeepAliveConnection() {
        // given - one long-lived channel standing in for an HTTP/1.1 keep-alive connection, whose
        // closeFuture does NOT complete while requests flow over it
        EmbeddedChannel keepAliveConnection = new EmbeddedChannel();
        AtomicInteger inFlight = new AtomicInteger(0);
        LifeCycle server = counterBackedServer(inFlight);
        List<WeakReference<InFlightRequest>> tokens = new ArrayList<>();

        // when - 100 requests are processed over that single connection, each completing normally
        // (as NettyResponseWriter.sendResponse would) without the connection closing
        for (int i = 0; i < 100; i++) {
            InFlightRequest token = InFlightRequest.started(server);
            token.trackConnectionClose(keepAliveConnection);
            token.complete();
            tokens.add(new WeakReference<>(token));
            // token goes out of scope here - the only thing that could keep it alive is the
            // closeFuture listener, which complete() must have removed
        }

        // then - the drain counter has returned to baseline ...
        assertThat("every completed token decrements the drain counter", inFlight.get(), is(0));
        // ... and no completed token is still reachable (i.e. no listener is pinned to the
        // still-open connection's closeFuture). On the pre-fix code these references survive GC.
        assertThat("completed tokens are freed, not pinned to the keep-alive closeFuture",
            allCleared(tokens), is(true));

        // keep the channel strongly reachable to the end so the pre-fix leak has somewhere to hang
        assertThat(keepAliveConnection.isOpen(), is(true));
    }

    @Test
    public void closeFutureSafetyNetStillDecrementsWhenNoResponseIsProduced() {
        // given - a request whose channel closes before any response is written
        EmbeddedChannel connection = new EmbeddedChannel();
        AtomicInteger inFlight = new AtomicInteger(0);
        LifeCycle server = counterBackedServer(inFlight);

        InFlightRequest token = InFlightRequest.started(server);
        token.trackConnectionClose(connection);
        assertThat("started increments the drain counter", inFlight.get(), is(1));

        // when - the connection drops without the response funnel ever completing the token
        connection.close();

        // then - the close-future safety net still fires complete(), so the drain counter decrements
        assertThat("dropped connection still decrements the drain counter", inFlight.get(), is(0));
    }

    @Test
    public void completeIsIdempotentAcrossTheResponsePathAndTheCloseFuture() {
        // given
        EmbeddedChannel connection = new EmbeddedChannel();
        AtomicInteger inFlight = new AtomicInteger(0);
        LifeCycle server = counterBackedServer(inFlight);

        InFlightRequest token = InFlightRequest.started(server);
        token.trackConnectionClose(connection);

        // when - the response path completes the token first, then the connection later closes
        token.complete();
        connection.close();

        // then - the counter is decremented exactly once (never below zero, never double-counted)
        assertThat(inFlight.get(), is(0));
    }

    @Test
    public void completeIsANoOpAndSafeWithoutAnyTrackedConnection() {
        // given - started() with no trackConnectionClose (mirrors the null-channel / no-safety-net
        // branch); complete() must still decrement exactly once and not throw
        AtomicInteger inFlight = new AtomicInteger(0);
        LifeCycle server = counterBackedServer(inFlight);

        InFlightRequest token = InFlightRequest.started(server);
        assertThat(inFlight.get(), is(1));

        // when
        token.complete();
        token.complete();

        // then
        assertThat(inFlight.get(), is(0));
    }

    /**
     * Force garbage collection and report whether every referent has been cleared. Retries to
     * tolerate the non-determinism of a single {@link System#gc()} call.
     */
    private static boolean allCleared(List<WeakReference<InFlightRequest>> references) {
        for (int attempt = 0; attempt < 50; attempt++) {
            System.gc();
            if (references.stream().allMatch(reference -> reference.get() == null)) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return references.stream().allMatch(reference -> reference.get() == null);
    }
}
