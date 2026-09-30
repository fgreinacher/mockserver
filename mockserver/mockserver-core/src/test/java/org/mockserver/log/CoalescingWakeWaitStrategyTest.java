package org.mockserver.log;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.junit.After;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assume.assumeTrue;

/**
 * The consumer must always wake for published entries: never a lost wake-up on the deep (untimed) park,
 * and never more than the poll bound on the coalesced path. Each test owns its own ring and consumer.
 */
public class CoalescingWakeWaitStrategyTest {

    private static final long NEVER = TimeUnit.HOURS.toNanos(1);

    private Disruptor<long[]> disruptor;
    private CoalescingWakeWaitStrategy strategy;
    private final AtomicReference<Thread> consumerThread = new AtomicReference<>();
    private final AtomicLong consumed = new AtomicLong();

    @After
    public void stopDisruptor() {
        if (disruptor != null) {
            disruptor.halt();
            strategy.wakeConsumer();
        }
    }

    private CoalescingWakeWaitStrategy start(long minParkNanos, long maxParkNanos, long wakeBacklog, long deepIdleNanos) {
        strategy = new CoalescingWakeWaitStrategy(minParkNanos, maxParkNanos, wakeBacklog, deepIdleNanos);
        disruptor = new Disruptor<>(() -> new long[1], 1024, runnable -> {
            Thread thread = new Thread(runnable, "coalescing-wait-test-consumer");
            thread.setDaemon(true);
            consumerThread.set(thread);
            return thread;
        }, ProducerType.MULTI, strategy);
        disruptor.handleEventsWith((event, sequence, endOfBatch) -> consumed.incrementAndGet());
        disruptor.start();
        return strategy;
    }

    private void publish() {
        RingBuffer<long[]> ringBuffer = disruptor.getRingBuffer();
        ringBuffer.publishEvent((event, sequence) -> event[0] = sequence);
    }

    private boolean awaitConsumed(long expected, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (consumed.get() < expected) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(0, 200_000);
        }
        return true;
    }

    private boolean awaitConsumerState(Thread.State state, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (consumerThread.get().getState() != state) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    /**
     * Rounds of short concurrent bursts from several producers, each followed by silence long enough to
     * straddle the deep-park threshold. The last entry of a round is only ever consumed if the producer's
     * signal reaches a deep-parked consumer, so a lost wake-up leaves a round unfinished and fails here.
     */
    @Test
    public void shouldConsumeEveryEntryFromSporadicProducers() throws Exception {
        start(MILLISECONDS.toNanos(1), MILLISECONDS.toNanos(4), 16, MILLISECONDS.toNanos(3));
        int producers = 6;
        int rounds = 300;
        CyclicBarrier roundStart = new CyclicBarrier(producers + 1);
        CyclicBarrier roundEnd = new CyclicBarrier(producers + 1);
        AtomicLong published = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int p = 0; p < producers; p++) {
            Thread producer = new Thread(() -> {
                try {
                    for (int round = 0; round < rounds; round++) {
                        roundStart.await();
                        int burst = ThreadLocalRandom.current().nextInt(0, 4);
                        for (int i = 0; i < burst; i++) {
                            publish();
                            published.incrementAndGet();
                            if (ThreadLocalRandom.current().nextBoolean()) {
                                Thread.onSpinWait();
                            }
                        }
                        roundEnd.await();
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }, "coalescing-wait-test-producer-" + p);
            producer.setDaemon(true);
            threads.add(producer);
            producer.start();
        }
        long worstRoundNanos = 0;
        for (int round = 0; round < rounds; round++) {
            Thread.sleep(0, ThreadLocalRandom.current().nextInt(0, 999_999));
            if (ThreadLocalRandom.current().nextInt(4) == 0) {
                Thread.sleep(ThreadLocalRandom.current().nextInt(2, 8));
            }
            roundStart.await(10, SECONDS);
            roundEnd.await(10, SECONDS);
            long roundPublished = published.get();
            long waitStart = System.nanoTime();
            assertThat("round " + round + " consumed " + consumed.get() + " of " + roundPublished, awaitConsumed(roundPublished, 5, SECONDS), is(true));
            worstRoundNanos = Math.max(worstRoundNanos, System.nanoTime() - waitStart);
        }
        for (Thread thread : threads) {
            thread.join(SECONDS.toMillis(10));
        }
        assertThat(failure.get() == null ? "" : failure.get().toString(), failure.get() == null, is(true));
        assertThat(consumed.get(), is(published.get()));
        // no round waited anywhere near the 5s lost-wake-up bound: the slowest is a poll, plus scheduling
        assertThat(worstRoundNanos, lessThan(SECONDS.toNanos(1)));
    }

    /**
     * Ping-pong with every wait a deep (untimed) park and no poll: each publish follows the previous entry's
     * consumption immediately, so it races the consumer's transition into the park. Any wake-up lost in that
     * window leaves the consumer parked forever and fails the iteration. Only a weakly ordered CPU (arm64)
     * exercises the full fence; on x86 the volatile store already orders the cursor read.
     */
    @Test
    public void shouldNeverLoseAWakeUpWhenPublishRacesThePark() {
        start(NEVER, NEVER, Long.MAX_VALUE, 0);
        int iterations = 100_000;
        for (int i = 1; i <= iterations; i++) {
            publish();
            long deadline = System.nanoTime() + SECONDS.toNanos(5);
            while (consumed.get() < i) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("lost wake-up: entry " + i + " not consumed within 5s");
                }
                Thread.onSpinWait();
            }
        }
    }

    @Test
    public void shouldParkWithoutTimeoutAndUseNoCpuWhenIdle() throws Exception {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMXBean.isThreadCpuTimeSupported());
        start(MILLISECONDS.toNanos(1), MILLISECONDS.toNanos(10), 256, MILLISECONDS.toNanos(50));
        publish();
        assertThat(awaitConsumed(1, 5, SECONDS), is(true));

        // past the deep-idle threshold the consumer parks with no timeout: no timer wake-ups while idle
        assertThat(awaitConsumerState(Thread.State.WAITING, 5, SECONDS), is(true));
        long cpuBefore = threadMXBean.getThreadCpuTime(consumerThread.get().getId());
        Thread.sleep(500);
        long idleCpuNanos = threadMXBean.getThreadCpuTime(consumerThread.get().getId()) - cpuBefore;
        assertThat(consumerThread.get().getState(), is(Thread.State.WAITING));
        assertThat(idleCpuNanos, lessThan(MILLISECONDS.toNanos(5)));

        // and the first entry after idle is processed at once, not after a poll
        publish();
        assertThat(awaitConsumed(2, 5, SECONDS), is(true));
    }

    /**
     * With a one-hour poll only a signal can deliver an entry, so the first entry is delivered explicitly once
     * the consumer is parked; the consumer may otherwise park before or after the first publish.
     */
    private void deliverFirstEntryToPollingConsumer() throws InterruptedException {
        assertThat(awaitConsumerState(Thread.State.TIMED_WAITING, 5, SECONDS), is(true));
        publish();
        strategy.wakeConsumer();
        assertThat(awaitConsumed(1, 5, SECONDS), is(true));
        assertThat(awaitConsumerState(Thread.State.TIMED_WAITING, 5, SECONDS), is(true));
    }

    @Test
    public void shouldCoalesceWakeUpsUntilBacklogThreshold() throws Exception {
        start(NEVER, NEVER, 4, NEVER);
        deliverFirstEntryToPollingConsumer();

        // below the threshold, publishing does not wake a polling consumer
        publish();
        publish();
        publish();
        assertThat(awaitConsumed(4, 300, MILLISECONDS), is(false));
        assertThat(consumed.get(), is(1L));

        // the fourth waiting entry reaches the threshold and wakes it
        publish();
        assertThat(awaitConsumed(5, 5, SECONDS), is(true));
    }

    @Test
    public void shouldWakePollingConsumerOnRequest() throws Exception {
        start(NEVER, NEVER, 1_000, NEVER);
        deliverFirstEntryToPollingConsumer();

        publish();
        assertThat(awaitConsumed(2, 300, MILLISECONDS), is(false));
        strategy.wakeConsumer();
        assertThat(awaitConsumed(2, 5, SECONDS), is(true));
    }

    @Test
    public void shouldRejectASecondConsumerThread() throws Exception {
        CoalescingWakeWaitStrategy singleConsumer = new CoalescingWakeWaitStrategy(1, 1, 1, 1);
        Sequence available = new Sequence(0);
        assertThat(singleConsumer.waitFor(0, available, available, null), is(0L));
        AtomicReference<Throwable> secondConsumer = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                singleConsumer.waitFor(0, available, available, null);
            } catch (Throwable throwable) {
                secondConsumer.set(throwable);
            }
        });
        other.start();
        other.join(SECONDS.toMillis(5));
        assertThat(secondConsumer.get() instanceof IllegalStateException, is(true));
    }

    @Test
    public void shouldDeliverPollBoundedLatencyWithoutAnySignal() throws Exception {
        start(MILLISECONDS.toNanos(1), MILLISECONDS.toNanos(20), 1_000, NEVER);
        publish();
        assertThat(awaitConsumed(1, 5, SECONDS), is(true));
        Thread.sleep(100);

        // backlog below threshold and never deep: only the timed poll (at most 20ms) picks this up
        publish();
        assertThat(awaitConsumed(2, 5, SECONDS), is(true));
    }

    @Test
    public void shouldStopPromptlyWhenDeepParked() throws Exception {
        start(MILLISECONDS.toNanos(1), MILLISECONDS.toNanos(10), 256, MILLISECONDS.toNanos(10));
        publish();
        assertThat(awaitConsumed(1, 5, SECONDS), is(true));
        assertThat(awaitConsumerState(Thread.State.WAITING, 5, SECONDS), is(true));

        CountDownLatch stopped = new CountDownLatch(1);
        Thread consumer = consumerThread.get();
        Thread joiner = new Thread(() -> {
            try {
                consumer.join();
                stopped.countDown();
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        });
        joiner.setDaemon(true);
        joiner.start();
        disruptor.halt();
        disruptor = null;
        assertThat(stopped.await(5, SECONDS), is(true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectZeroBacklogThreshold() {
        new CoalescingWakeWaitStrategy(1, 1, 0, 1);
    }
}
