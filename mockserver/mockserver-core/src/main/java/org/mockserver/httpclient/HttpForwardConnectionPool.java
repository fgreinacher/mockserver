package org.mockserver.httpclient;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.model.Protocol;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A small keyed pool of idle, reusable upstream keep-alive connections used by
 * {@link NettyHttpClient} when {@code forwardConnectionPoolEnabled} is set.
 * <p>
 * Connections are keyed by {@code host:port:secure:protocol} so that a checked-out idle channel is
 * only ever reused for an identical upstream (same host, port, TLS setting and protocol). The pool is
 * deliberately conservative:
 * <ul>
 *     <li>Only HTTP/1.1 keep-alive channels and HTTP/2 parent connections (one stream per request)
 *     are offered to the pool — HTTP/3, binary, streaming and proxy-tunnelled channels are never
 *     pooled (the caller never releases them here).</li>
 *     <li>{@link #acquire(String)} only ever returns a channel that is still {@code isActive()};
 *     stale (server-closed) channels are discarded.</li>
 *     <li>On saturation (per-key idle limit reached) {@link #release(String, Channel)} returns
 *     {@code false} so the caller closes the surplus channel rather than blocking or failing.</li>
 *     <li>Idle channels are evicted after {@code idleTimeoutMillis} of inactivity via a one-shot
 *     close scheduled on the channel's own event loop (cancelled if the channel is re-acquired) —
 *     this reaper applies equally to keep-warm-retained channels, so a long-idle warm pool drains.</li>
 * </ul>
 * The opt-in keep-warm mode (off by default) only RAISES the per-key idle ceiling from
 * {@code maxIdleConnectionsPerKey} to {@code maxTotalConnectionsPerKey} so that, under sustained
 * high-rate low-latency forwarding, channels are retained on release instead of being closed back
 * down to the idle cap — eliminating the connection churn that otherwise caps single-instance
 * forward/inject throughput. With keep-warm off the close decision is byte-identical to the
 * historical behaviour.
 * The pool itself holds no event-loop threads and performs no I/O; it is a thread-safe index of
 * idle channels. All mutation of the per-key deques is guarded by the deque's own monitor.
 */
class HttpForwardConnectionPool {

    /**
     * The maximum number of idle channels retained per upstream key. Surplus channels are closed
     * rather than pooled (graceful degradation under saturation — never blocks).
     */
    private final int maxIdleConnectionsPerKey;

    /**
     * How long an idle pooled channel is retained before being closed and evicted.
     */
    private final long idleTimeoutMillis;

    /**
     * Opt-in keep-warm retention (default off). When false the pool behaves exactly as it always has:
     * {@link #release(String, Channel)} caps the idle set at {@link #maxIdleConnectionsPerKey} and the
     * caller closes any surplus. When true, surplus channels are RETAINED on release (up to
     * {@link #maxTotalConnectionsPerKey}) instead of being closed, so a sustained high-rate,
     * low-latency forward/inject workload accumulates a warm keep-alive set sized to its offered
     * concurrency and stops churning fresh connections. The acquire path is byte-identical in both
     * modes; only the release-time close decision changes.
     */
    private final boolean keepAlive;

    /**
     * Per-key ceiling on retained idle channels when {@link #keepAlive} is on. Channels offered beyond
     * this ceiling are rejected (the caller closes them), bounding the warm set so it cannot grow
     * without limit. Ignored entirely when {@link #keepAlive} is off.
     */
    private final int maxTotalConnectionsPerKey;

    private final Map<String, Deque<Channel>> idleChannels = new ConcurrentHashMap<>();

    /**
     * Marks a channel for which a close listener has already been registered, so the listener is
     * added exactly once over the channel's lifetime even though the channel may be pooled and
     * reused many times.
     */
    private static final AttributeKey<Boolean> CLOSE_LISTENER_ADDED = AttributeKey.valueOf("POOL_CLOSE_LISTENER_ADDED");

    HttpForwardConnectionPool(int maxIdleConnectionsPerKey, long idleTimeoutMillis) {
        this(maxIdleConnectionsPerKey, idleTimeoutMillis, false, 0);
    }

    HttpForwardConnectionPool(int maxIdleConnectionsPerKey, long idleTimeoutMillis, boolean keepAlive, int maxTotalConnectionsPerKey) {
        this.maxIdleConnectionsPerKey = Math.max(1, maxIdleConnectionsPerKey);
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.keepAlive = keepAlive;
        // When keep-warm is on the per-key idle ceiling is maxTotal (never below the plain idle cap,
        // since keep-warm only ever RAISES retention); clamped to at least 1 like the idle cap.
        this.maxTotalConnectionsPerKey = Math.max(this.maxIdleConnectionsPerKey, maxTotalConnectionsPerKey);
    }

    /**
     * The effective per-key idle ceiling: the plain idle cap by default, raised to the keep-warm
     * total ceiling when keep-warm retention is enabled. This is the ONLY behavioural divergence
     * between the default (off) and keep-warm (on) paths — when {@link #keepAlive} is false this
     * returns exactly {@link #maxIdleConnectionsPerKey}, so the release close-decision is unchanged.
     */
    private int effectiveIdleCeiling() {
        return keepAlive ? maxTotalConnectionsPerKey : maxIdleConnectionsPerKey;
    }

    /**
     * Builds the pool key for an upstream. {@code null} is returned only for a null address, in
     * which case the caller must not attempt to pool. The negotiated {@code protocol} is part of the
     * key so an HTTP/1.1 channel is never reused for an HTTP/2 request (or vice versa) — the two use
     * incompatible pipelines — even to the same host, port and TLS setting.
     */
    static String keyFor(InetSocketAddress remoteAddress, boolean secure, Protocol protocol) {
        if (remoteAddress == null) {
            return null;
        }
        String host = remoteAddress.getHostString();
        return host + ":" + remoteAddress.getPort() + ":" + secure + ":" + protocol;
    }

    /**
     * Returns an idle, still-active channel for the key, or {@code null} if none is available.
     * Any stale (inactive) channels encountered are discarded.
     */
    Channel acquire(String key) {
        if (key == null) {
            return null;
        }
        Deque<Channel> deque = idleChannels.get(key);
        if (deque == null) {
            return null;
        }
        synchronized (deque) {
            Channel channel;
            while ((channel = deque.pollFirst()) != null) {
                if (channel.isActive()) {
                    cancelIdleEviction(channel);
                    return channel;
                }
                // stale - drop it (its own close listener will also have removed it)
                channel.close();
            }
        }
        return null;
    }

    /**
     * Offers an idle, reusable channel back to the pool. Returns {@code true} if the channel was
     * accepted (the caller must NOT close it), or {@code false} if the pool is saturated for this
     * key and the caller should close the channel.
     */
    boolean release(String key, Channel channel) {
        // The isActive() check here is a fast best-effort guard; a channel can still go inactive
        // after this point, which is safe because acquire() re-checks isActive() and discards any
        // dead channel before returning it.
        if (key == null || channel == null || !channel.isActive()) {
            return false;
        }
        Deque<Channel> deque = idleChannels.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.size() >= effectiveIdleCeiling()) {
                return false;
            }
            deque.addLast(channel);
        }
        // remove from the pool if the server closes the connection while it sits idle (added once
        // per channel lifetime even though the channel may be pooled and reused repeatedly)
        if (channel.attr(CLOSE_LISTENER_ADDED).compareAndSet(null, Boolean.TRUE)) {
            channel.closeFuture().addListener(future -> removeIdle(key, channel));
        }
        scheduleIdleEviction(key, channel);
        return true;
    }

    private void scheduleIdleEviction(String key, Channel channel) {
        if (idleTimeoutMillis <= 0) {
            return;
        }
        ScheduledFuture<?> eviction = channel.eventLoop().schedule(() -> {
            if (removeIdle(key, channel)) {
                channel.close();
            }
        }, idleTimeoutMillis, TimeUnit.MILLISECONDS);
        channel.attr(NettyHttpClient.POOL_IDLE_EVICTION).set(eviction);
    }

    private void cancelIdleEviction(Channel channel) {
        ScheduledFuture<?> eviction = channel.attr(NettyHttpClient.POOL_IDLE_EVICTION).getAndSet(null);
        if (eviction != null) {
            eviction.cancel(false);
        }
    }

    /**
     * Removes a specific channel from the idle set for a key. Returns true if it was present (i.e.
     * still idle), false if it had already been acquired or evicted.
     */
    private boolean removeIdle(String key, Channel channel) {
        Deque<Channel> deque = idleChannels.get(key);
        if (deque == null) {
            return false;
        }
        synchronized (deque) {
            return deque.remove(channel);
        }
    }

}
