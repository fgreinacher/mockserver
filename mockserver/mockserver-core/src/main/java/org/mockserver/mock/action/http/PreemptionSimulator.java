package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.PreemptionRequest;
import org.mockserver.time.TimeService;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Process-wide state machine for a simulated server <em>preemption</em> (node drain / Spot
 * reclamation / pre-SIGTERM): when {@link #start(PreemptionRequest)} is called the server becomes
 * <em>cordoned</em> — new data-plane exchanges are turned away (503 + Connection: close on HTTP/1.1,
 * GOAWAY on HTTP/2, per {@link PreemptionRequest.Mode}) while in-flight requests are allowed to drain
 * for a bounded window. It is a <b>simulation only</b>: it never calls {@code stop()} / {@code
 * stopAsync()} and never tears down the event loops. The cordon clears on an explicit
 * {@link #uncordon()} or automatically after {@code ttlMillis} (a dead-man's switch so a forgotten
 * simulation self-heals).
 *
 * <p>The design mirrors {@link ServiceChaosRegistry}'s singleton + controllable-clock + TTL pattern,
 * but is server-scoped (a single cordon, no host keying). It holds no Netty references: the in-flight
 * count is read through an injected {@link IntSupplier} (wired from {@code LifeCycle.getRequestsInFlight()}),
 * keeping mockserver-core free of a netty dependency. GOAWAY emission is performed lazily on the netty
 * side when an HTTP/2 client hits a cordoned connection (see {@code HttpRequestHandler}); this class is
 * the authoritative cordon state.
 *
 * <p>State is cleared on server reset (see {@code HttpState.reset()}).
 */
public class PreemptionSimulator {

    private static final PreemptionSimulator INSTANCE = new PreemptionSimulator(TimeService::currentTimeMillis);

    private final LongSupplier clock;

    // Whole state is mutated under `this` so a concurrent start/uncordon/expiry can't tear the fields.
    private volatile boolean cordoned;
    private volatile PreemptionRequest request;
    private volatile long startedAtMillis;
    private volatile long drainDeadlineMillis; // start + clamped drainMillis
    private volatile long ttlExpiryMillis;      // start + clamped ttlMillis; 0 = no auto-uncordon
    // Supplier of the current in-flight data-plane request count; defaults to "none" until wired.
    private volatile IntSupplier inFlightSupplier = () -> 0;

    public PreemptionSimulator(LongSupplier clock) {
        this.clock = clock;
    }

    public static PreemptionSimulator getInstance() {
        return INSTANCE;
    }

    /**
     * Wire the supplier used to read the current in-flight data-plane request count (from
     * {@code LifeCycle.getRequestsInFlight()}). Null is ignored.
     */
    public void setInFlightSupplier(IntSupplier inFlightSupplier) {
        if (inFlightSupplier != null) {
            this.inFlightSupplier = inFlightSupplier;
        }
    }

    /**
     * Begin (or replace) a preemption simulation. The {@code drainMillis} and {@code ttlMillis} are
     * each clamped to {@code preemptionSimulationMaxDrainMillis}. When {@code drainMillis} is null it
     * defaults to {@code stopDrainMillis} (the same drain budget a real graceful shutdown uses). When
     * {@code mode} is null it defaults to {@link PreemptionRequest.Mode#both}.
     *
     * @return the effective request actually applied (with defaults and clamping resolved)
     */
    public synchronized PreemptionRequest start(PreemptionRequest req) {
        return start(null, req);
    }

    /**
     * As {@link #start(PreemptionRequest)}, but resolves {@code preemptionSimulationMaxDrainMillis}
     * from the supplied {@link Configuration} instance when one is given, falling back to the static
     * {@link ConfigurationProperties} store otherwise. The instance is preferred so a cap set
     * programmatically or via {@code PUT /mockserver/configuration} actually clamps the drain/TTL;
     * the static fallback keeps system-property/env/file users unaffected.
     *
     * @param configuration the server configuration, may be null
     * @return the effective request actually applied (with defaults and clamping resolved)
     */
    public synchronized PreemptionRequest start(Configuration configuration, PreemptionRequest req) {
        if (req == null) {
            req = PreemptionRequest.preemptionRequest();
        }
        long cap = maxDrainMillis(configuration);

        PreemptionRequest.Mode mode = req.getMode() != null ? req.getMode() : PreemptionRequest.Mode.both;

        long drainMillis = req.getDrainMillis() != null ? req.getDrainMillis() : stopDrainMillis(configuration);
        if (drainMillis < 0) {
            drainMillis = 0;
        }
        if (cap > 0 && drainMillis > cap) {
            drainMillis = cap;
        }

        long ttlMillis = req.getTtlMillis() != null ? req.getTtlMillis() : 0L;
        if (ttlMillis < 0) {
            ttlMillis = 0;
        }
        if (cap > 0 && ttlMillis > cap) {
            ttlMillis = cap;
        }

        PreemptionRequest effective = PreemptionRequest.preemptionRequest()
            .withMode(mode)
            .withDrainMillis(drainMillis)
            .withTtlMillis(ttlMillis)
            .withLastStreamId(req.getLastStreamId());

        long now = clock.getAsLong();
        this.request = effective;
        this.startedAtMillis = now;
        this.drainDeadlineMillis = saturatingAdd(now, drainMillis);
        this.ttlExpiryMillis = ttlMillis > 0 ? saturatingAdd(now, ttlMillis) : 0L;
        this.cordoned = true;
        return effective;
    }

    /**
     * The effective drain/TTL cap: the {@link Configuration} instance value when one is supplied,
     * otherwise the static {@link ConfigurationProperties} store.
     */
    private static long maxDrainMillis(Configuration configuration) {
        return configuration != null
            ? configuration.preemptionSimulationMaxDrainMillis()
            : ConfigurationProperties.preemptionSimulationMaxDrainMillis();
    }

    /**
     * The default drain budget when the request omits {@code drainMillis}: the {@link Configuration}
     * instance value when one is supplied, otherwise the static {@link ConfigurationProperties} store.
     */
    private static long stopDrainMillis(Configuration configuration) {
        return configuration != null
            ? configuration.stopDrainMillis()
            : ConfigurationProperties.stopDrainMillis();
    }

    private static long saturatingAdd(long now, long delta) {
        long sum = now + delta;
        return sum < now ? Long.MAX_VALUE : sum;
    }

    /**
     * Whether the server is currently cordoned. Auto-uncordons (lazily, here) once the TTL has
     * elapsed, so a forgotten simulation self-heals without an explicit uncordon.
     */
    public boolean isCordoned() {
        if (!cordoned) {
            return false;
        }
        long expiry = ttlExpiryMillis;
        if (expiry > 0 && clock.getAsLong() >= expiry) {
            synchronized (this) {
                if (cordoned && ttlExpiryMillis > 0 && clock.getAsLong() >= ttlExpiryMillis) {
                    clearState();
                }
            }
            return false;
        }
        return true;
    }

    /** The current in-flight data-plane request count, via the wired supplier. */
    public int inFlight() {
        return inFlightSupplier.getAsInt();
    }

    /** The active preemption request (effective, post-clamp), or null when not cordoned. */
    public PreemptionRequest getRequest() {
        return isCordoned() ? request : null;
    }

    /** The active mode, or null when not cordoned. */
    public PreemptionRequest.Mode getMode() {
        PreemptionRequest active = getRequest();
        return active != null ? active.getMode() : null;
    }

    /**
     * {@code true} when the active simulation rejects new data-plane exchanges with a 503 (modes
     * {@code reject503} and {@code both}). When the mode is {@code goaway} only, new exchanges are
     * <em>not</em> rejected with a 503 — the HTTP/2 GOAWAY alone signals clients to drain — so this
     * returns false. Returns false when not cordoned.
     */
    public boolean rejectsNewExchanges() {
        PreemptionRequest active = getRequest();
        return active != null && active.rejectsNew();
    }

    /**
     * {@code true} when the active simulation signals HTTP/2 clients to drain via a GOAWAY frame
     * (modes {@code goaway} and {@code both}). Returns false when not cordoned. Read by the netty
     * runtime to decide whether to emit a lazy GOAWAY on a cordoned HTTP/2 connection. Thread-safe:
     * reads the active request through the same path as {@link #isCordoned()}.
     */
    public boolean emitsGoAway() {
        PreemptionRequest active = getRequest();
        return active != null && active.emitsGoAway();
    }

    /**
     * The {@code lastStreamId} to advertise on a preemption GOAWAY, or {@code -1} when not cordoned or
     * unset ({@code -1} tells {@code Http2GoAwayEmitter} to use the connection's current last stream).
     */
    public long goAwayLastStreamId() {
        PreemptionRequest active = getRequest();
        Long lastStreamId = active != null ? active.getLastStreamId() : null;
        return lastStreamId != null ? lastStreamId : -1L;
    }

    /**
     * Milliseconds remaining in the drain window (until {@code drainDeadlineMillis}), floored at 0.
     * Returns 0 when not cordoned. This reflects the drain budget, not the TTL.
     */
    public long drainRemainingMillis() {
        if (!isCordoned()) {
            return 0L;
        }
        long remaining = drainDeadlineMillis - clock.getAsLong();
        return Math.max(0L, remaining);
    }

    /** {@code true} once the drain window has elapsed while still cordoned (stragglers may remain). */
    public boolean drainDeadlinePassed() {
        return isCordoned() && clock.getAsLong() >= drainDeadlineMillis;
    }

    /**
     * A human-readable state string for the status endpoint: {@code "inactive"} when not cordoned,
     * {@code "draining"} while within the drain window, or {@code "drained"} once the drain deadline
     * has passed (the server stays cordoned until TTL/uncordon).
     */
    public String state() {
        if (!isCordoned()) {
            return "inactive";
        }
        return clock.getAsLong() < drainDeadlineMillis ? "draining" : "drained";
    }

    /** Clear the cordon explicitly (idempotent). */
    public synchronized void uncordon() {
        clearState();
    }

    private void clearState() {
        this.cordoned = false;
        this.request = null;
        this.startedAtMillis = 0L;
        this.drainDeadlineMillis = 0L;
        this.ttlExpiryMillis = 0L;
    }

    /** Reset all state. Called on server reset and for test isolation. Does not clear the wired supplier. */
    public synchronized void reset() {
        clearState();
    }
}
