package org.mockserver.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.mockserver.lifecycle.LifeCycle;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Per-request in-flight token used by the WS7.2 graceful-shutdown connection drain.
 *
 * <p>A token is created and {@link LifeCycle#requestProcessingStarted()} is incremented exactly once
 * when a data-plane HTTP request begins processing in {@link HttpRequestHandler#channelRead0}. The
 * matching {@link LifeCycle#requestProcessingComplete()} decrement is driven by {@link #complete()},
 * which is invoked from whichever of these fires first:</p>
 *
 * <ul>
 *   <li>the response funnel — {@code NettyResponseWriter.sendResponse(...)}, through which every
 *       data-plane response flows (normal, streaming, chunked, forward/proxy, error/exception,
 *       breakpoint-modified); or</li>
 *   <li>the channel {@code closeFuture} safety net — covers requests that never produce a response
 *       (connection drop or pipeline-killing exception mid-processing).</li>
 * </ul>
 *
 * <p>A single compare-and-set guard guarantees the decrement fires <em>exactly once</em> regardless
 * of how many of those hooks fire, so the in-flight counter can never leak (which would make
 * {@code stop()} always wait the full drain timeout) nor be decremented twice.</p>
 *
 * <p>The token is its own {@link ChannelFutureListener} (rather than capturing a separate lambda per
 * request), so the only per-request allocation for the safety net is this object itself.</p>
 *
 * <p><strong>Listener lifecycle.</strong> The {@code closeFuture} safety net is registered via
 * {@link #trackConnectionClose(Channel)}. On an HTTP/1.1 keep-alive connection the handler sits on
 * the <em>connection</em> channel, whose {@code closeFuture} completes only when the whole
 * connection closes — potentially after thousands of requests. A listener added to a future is
 * retained by that future until it completes, so if the safety-net listener were never removed,
 * every completed request on a long-lived connection would leave its listener (the token) pinned for
 * the life of the connection — an unbounded, per-request heap leak. To prevent that, this token
 * remembers the {@code closeFuture} it registered on and, the first time {@link #complete()} wins the
 * CAS, <em>removes</em> itself from that future. After that only a genuine no-response close
 * (connection drop / pipeline-killing exception) still relies on the listener firing, and in that
 * case the listener has already run. Under HTTP/2 the handler sits on a per-stream child channel
 * whose {@code closeFuture} completes per request, so the listener would be freed naturally there
 * anyway; removing it on completion is correct on both paths and simply frees it sooner on
 * HTTP/1.1.</p>
 *
 * @author jamesdbloom
 */
public final class InFlightRequest implements ChannelFutureListener {

    private final LifeCycle server;
    // 0 = not yet completed, 1 = completed. A field updater rather than an AtomicBoolean instance so the
    // exactly-once guard costs no per-request object allocation; the CAS has identical volatile semantics.
    private volatile int completed;
    private static final AtomicIntegerFieldUpdater<InFlightRequest> COMPLETED =
        AtomicIntegerFieldUpdater.newUpdater(InFlightRequest.class, "completed");
    // The channel closeFuture safety-net was registered on, remembered so complete() can deregister this
    // listener and let the token be freed rather than pinned to a long-lived HTTP/1.1 connection's
    // closeFuture. Written once by trackConnectionClose() before any completion can occur (same
    // event-loop thread, before the response path runs); read under the completed CAS in complete().
    // volatile so the cross-thread completion (response path on a scheduler thread) sees the registration.
    private volatile ChannelFuture closeFuture;

    private InFlightRequest(LifeCycle server) {
        this.server = server;
    }

    /**
     * Increment the in-flight counter and return a token whose {@link #complete()} will decrement it
     * exactly once. Returns {@code null} when no {@link LifeCycle} is available (so callers can
     * no-op safely).
     */
    public static InFlightRequest started(LifeCycle server) {
        if (server == null) {
            return null;
        }
        InFlightRequest inFlightRequest = new InFlightRequest(server);
        server.requestProcessingStarted();
        return inFlightRequest;
    }

    /**
     * Register the channel {@code closeFuture} safety net for this token: if the channel closes
     * before a response is produced, {@link #complete()} still fires so the drain counter
     * decrements. The future is remembered so {@link #complete()} can remove this listener on the
     * normal response path, keeping it from accumulating on a long-lived keep-alive connection's
     * {@code closeFuture}.
     *
     * <p>Called once, on the event-loop thread, immediately after {@link #started(LifeCycle)} and
     * before any response processing, so the future reference is visible to the later
     * {@link #complete()} regardless of which thread completes the token.</p>
     */
    public void trackConnectionClose(Channel channel) {
        // A null channel leaves this token with no close-future safety net: complete() must then be
        // driven by the response path alone. The sole caller passes ctx.channel() (never null), so
        // this is a defensive guard rather than an expected branch.
        if (channel == null) {
            return;
        }
        ChannelFuture future = channel.closeFuture();
        // Publish closeFuture BEFORE addListener so a synchronous notification (addListener may fire the
        // listener immediately if the channel is already closed) finds closeFuture already set and can
        // remove this listener. The listener itself is `this`, always visible, so there is no second
        // field to order against.
        this.closeFuture = future;
        future.addListener(this);
    }

    @Override
    public void operationComplete(ChannelFuture future) {
        complete();
    }

    /**
     * Decrement the in-flight counter, but only the first time this is called for this token.
     * Safe to call from any thread and from multiple completion hooks.
     *
     * <p>On the winning call, also removes this token from the {@code closeFuture} it registered on in
     * {@link #trackConnectionClose(Channel)} so it is not retained by a long-lived connection's
     * {@code closeFuture}. {@code removeListener} is a no-op when the future has already completed
     * (the channel closed first, so the listener has already been notified and cleared) and when
     * called from within that listener's own notification, so this is safe on every completion
     * path.</p>
     */
    public void complete() {
        if (COMPLETED.compareAndSet(this, 0, 1)) {
            server.requestProcessingComplete();
            ChannelFuture future = closeFuture;
            if (future != null) {
                future.removeListener(this);
                // Release the reference so the token is not retained after completion (the token itself
                // becomes unreachable once its callers drop it).
                closeFuture = null;
            }
        }
    }
}
