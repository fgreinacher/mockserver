package org.mockserver.grpc;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enforces the client's {@code grpc-timeout} for a <strong>streaming</strong> RPC, terminating it
 * mid-stream with {@code DEADLINE_EXCEEDED} when the deadline elapses.
 * <p>
 * One instance per RPC invocation, created by the handler that begins the stream and threaded
 * through its emission recursion. It is deliberately <em>not</em> stored on a channel attribute:
 * on the HTTP/2 connection-adapter pipeline a single channel is shared by every multiplexed
 * stream, so a channel-scoped guard would be replaced by the next overlapping RPC and the first
 * stream would then consult the wrong one -- the same class of error as the single-slot
 * service/method attribute that this change set already had to fix twice.
 * <p>
 * <strong>The interleaving guarantee.</strong> A streaming RPC emits frames asynchronously (per
 * message delays, write-completion callbacks, breakpoint resumes), so the deadline can fire while a
 * write is in flight. {@link #tryTerminate()} is a compare-and-set, so exactly one of "the deadline
 * fired" and "the stream completed normally" wins, and the loser writes nothing. Emission points
 * additionally check {@link #isTerminated()} before writing, so no message can be emitted after the
 * terminal trailer. Both the timer and the emission callbacks run on the stream's own event loop,
 * so the CAS resolves an ordering rather than true parallelism -- the CAS is what makes that
 * ordering explicit instead of assumed.
 */
public class GrpcStreamDeadline {

    private final AtomicBoolean terminated = new AtomicBoolean();
    private volatile ScheduledFuture<?> deadlineFuture;
    private volatile long timeoutNanos;

    /**
     * Schedules deadline termination for a streaming RPC, if the client sent a {@code grpc-timeout}.
     *
     * @param ctx         the stream's channel context
     * @param request     the inbound request, read for {@code grpc-timeout}
     * @param onDeadline  invoked on the event loop when the deadline elapses and this guard wins the
     *                    CAS; it should write the DEADLINE_EXCEEDED trailer and stop the stream
     * @return this guard, for chaining
     */
    public GrpcStreamDeadline schedule(ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request, Runnable onDeadline) {
        Long parsed = GrpcTimeout.parseNanos(request);
        if (parsed == null) {
            return this;
        }
        this.timeoutNanos = parsed;
        this.deadlineFuture = ctx.channel().eventLoop().schedule(() -> {
            if (tryTerminate()) {
                onDeadline.run();
            }
        }, parsed, TimeUnit.NANOSECONDS);
        return this;
    }

    /**
     * Claims the stream for termination. Returns {@code true} only for the first caller, so a
     * trailer is never written twice.
     */
    public boolean tryTerminate() {
        return terminated.compareAndSet(false, true);
    }

    /**
     * Whether this stream has already been terminated (by the deadline or by normal completion).
     * Emission points check this so no message follows the terminal trailer.
     */
    public boolean isTerminated() {
        return terminated.get();
    }

    /**
     * Cancels the deadline timer. Called on normal completion and when the channel goes inactive,
     * so a timer cannot outlive its stream.
     */
    public void cancel() {
        ScheduledFuture<?> future = deadlineFuture;
        if (future != null) {
            future.cancel(false);
            deadlineFuture = null;
        }
    }

    /**
     * The {@code grpc-message} to report when this deadline elapses.
     */
    public String deadlineExceededMessage() {
        return GrpcTimeout.deadlineExceededMessage(timeoutNanos);
    }
}
