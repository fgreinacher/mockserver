package org.mockserver.log;

import com.lmax.disruptor.AlertException;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.WaitStrategy;

import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * Wait strategy for the event log's single consumer that coalesces producer wake-ups.
 * <p>
 * With {@code BlockingWaitStrategy} every request's first publish woke the consumer (a futex wake paid on
 * the Netty worker loop), because the consumer processes an entry far faster than they arrive and goes
 * back to sleep. Here a recently-active consumer instead polls with a timed park that backs off from
 * {@code minParkNanos} to {@code maxParkNanos}, so producers only wake it when:
 * <ul>
 *     <li>it has been idle for {@code deepIdleNanos} and parked without a timeout ("deep"), so an idle
 *     server spends no CPU on the consumer and the first entry after idle is processed at once;</li>
 *     <li>at least {@code wakeBacklog} entries are waiting, so the ring is not left to fill during a poll; or</li>
 *     <li>the publisher calls {@link #wakeConsumer()} — every control-plane operation does, so verify,
 *     retrieve, clear and reset never wait for a poll, and so does a publish that takes the in-flight
 *     bytes past a quarter of the budget.</li>
 * </ul>
 * So an entry published while the consumer is polling waits at most {@code maxParkNanos} for it; one
 * published while it is deep-parked wakes it at once.
 * <p>
 * <b>Lost-wakeup freedom</b> (deep park and {@link #wakeConsumer()}): the consumer writes {@code parkState}
 * (volatile), issues a full fence, then reads the cursor; a producer advances the cursor with a volatile
 * read-modify-write in {@code next()/tryNext()} and only then reads {@code parkState}. So either the
 * consumer sees the new cursor and does not park, or the producer sees the consumer's park state and
 * unparks it; an unpark that precedes the park leaves a permit, so the park returns immediately. The
 * fence is needed because {@link Sequence#get()} is a plain read followed by an acquire fence, which
 * alone may be reordered before the preceding volatile store.
 * <p>
 * Supports exactly one consumer thread, for the lifetime of the strategy: a second thread calling
 * {@link #waitFor} fails with {@link IllegalStateException}, because a producer only ever unparks one thread.
 */
final class CoalescingWakeWaitStrategy implements WaitStrategy {

    private static final int RUNNING = 0;
    private static final int POLLING = 1;
    private static final int DEEP = 2;

    private final long minParkNanos;
    private final long maxParkNanos;
    private final long wakeBacklog;
    private final long deepIdleNanos;

    // written by the consumer before it publishes parkState, read by producers after reading parkState
    private Thread consumer;
    private Sequence cursor;
    private long waitingFor;
    private volatile int parkState = RUNNING;

    CoalescingWakeWaitStrategy(long minParkNanos, long maxParkNanos, long wakeBacklog, long deepIdleNanos) {
        if (minParkNanos <= 0 || maxParkNanos < minParkNanos || wakeBacklog < 1 || deepIdleNanos < 0) {
            throw new IllegalArgumentException("invalid coalescing wait strategy parameters");
        }
        this.minParkNanos = minParkNanos;
        this.maxParkNanos = maxParkNanos;
        this.wakeBacklog = wakeBacklog;
        this.deepIdleNanos = deepIdleNanos;
    }

    @Override
    public long waitFor(long sequence, Sequence cursorSequence, Sequence dependentSequence, SequenceBarrier barrier) throws AlertException, InterruptedException {
        final Thread me = Thread.currentThread();
        if (consumer != me) {
            claimConsumer(me);
        }
        if (cursorSequence.get() < sequence) {
            cursor = cursorSequence;
            waitingFor = sequence;
            long parkNanos = minParkNanos;
            final long idleSince = System.nanoTime();
            try {
                while (true) {
                    final boolean deep = System.nanoTime() - idleSince >= deepIdleNanos;
                    parkState = deep ? DEEP : POLLING;
                    VarHandle.fullFence();
                    if (cursorSequence.get() >= sequence) {
                        break;
                    }
                    barrier.checkAlert();
                    if (deep) {
                        LockSupport.park(this);
                    } else {
                        LockSupport.parkNanos(this, parkNanos);
                        parkNanos = Math.min(parkNanos << 1, maxParkNanos);
                    }
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
            } finally {
                parkState = RUNNING;
            }
        }
        long availableSequence;
        while ((availableSequence = dependentSequence.get()) < sequence) {
            barrier.checkAlert();
            Thread.onSpinWait();
        }
        return availableSequence;
    }

    private synchronized void claimConsumer(Thread me) {
        if (consumer == null) {
            consumer = me;
        } else if (consumer != me) {
            throw new IllegalStateException("CoalescingWakeWaitStrategy supports one consumer thread, already used by " + consumer.getName() + ", now called from " + me.getName());
        }
    }

    /**
     * Called by the ring after every publish (and by {@code alert()} on halt): wakes the consumer only if it is
     * deep-parked or the backlog has reached {@code wakeBacklog}; otherwise its timed poll will pick the entry up.
     */
    @Override
    public void signalAllWhenBlocking() {
        final int state = parkState;
        if (state == DEEP || (state == POLLING && cursor.get() - waitingFor + 1 >= wakeBacklog)) {
            LockSupport.unpark(consumer);
        }
    }

    /**
     * Wake the consumer now if it is parked; call after publishing an entry that someone is waiting on.
     */
    void wakeConsumer() {
        if (parkState != RUNNING) {
            LockSupport.unpark(consumer);
        }
    }

    /**
     * The consumer thread once it has first waited, else {@code null}; for tests that must publish only
     * after the consumer is inside its park.
     */
    synchronized Thread consumerThread() {
        return consumer;
    }

    @Override
    public String toString() {
        return "CoalescingWakeWaitStrategy{minParkNanos=" + minParkNanos + ", maxParkNanos=" + maxParkNanos
            + ", wakeBacklog=" + wakeBacklog + ", deepIdleNanos=" + deepIdleNanos + '}';
    }
}
