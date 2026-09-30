package org.mockserver.log;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.LiteBlockingWaitStrategy;
import com.lmax.disruptor.PhasedBackoffWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cost of handing an entry to the event-log consumer when the consumer is mostly idle, the regime of a
 * server below its ceiling: each producer (a Netty worker loop) does ~{@code thinkNanos} of other work
 * between publishes, so the consumer finishes each entry and waits before the next arrives.
 * <p>
 * The JMH score (think + publish per op) is secondary. Each iteration prints the figures that matter:
 * {@code publish_ns} (producer wall time inside {@code tryPublishEvent}), {@code consumer_cpu_ns} (consumer
 * thread CPU per entry, which includes its park/unpark cycles) and {@code batches_per_entry} (how often the
 * consumer came back from a wait).
 *
 * <pre>./run.sh EventLogPublishWaitStrategyBenchmark -p thinkNanos=20000,40000</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@Threads(5)
public class EventLogPublishWaitStrategyBenchmark {

    @Param({"blocking", "lite", "phased", "coalescing"})
    public String waitStrategy;

    @Param({"25000"})
    public long thinkNanos;

    private Disruptor<long[]> disruptor;
    private RingBuffer<long[]> ringBuffer;
    private volatile Thread consumerThread;
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final LongAdder publishNanos = new LongAdder();
    private final LongAdder published = new LongAdder();
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private long consumerCpuAtStart;
    private long consumedAtStart;
    private long batchesAtStart;
    private volatile long sink;

    private static final EventTranslator<long[]> TRANSLATOR = (event, sequence) -> event[0] = sequence;

    @Setup(Level.Trial)
    public void setup() {
        WaitStrategy strategy;
        switch (waitStrategy) {
            case "blocking":
                strategy = new BlockingWaitStrategy();
                break;
            case "lite":
                strategy = new LiteBlockingWaitStrategy();
                break;
            case "phased":
                strategy = PhasedBackoffWaitStrategy.withLock(1, 10, TimeUnit.MICROSECONDS);
                break;
            default:
                strategy = new CoalescingWakeWaitStrategy(TimeUnit.MILLISECONDS.toNanos(1), TimeUnit.MILLISECONDS.toNanos(10), 256, TimeUnit.MILLISECONDS.toNanos(50));
        }
        disruptor = new Disruptor<>(() -> new long[1], 16384, runnable -> {
            Thread thread = new Thread(runnable, "EventLog-benchmark-consumer");
            thread.setDaemon(true);
            consumerThread = thread;
            return thread;
        }, ProducerType.MULTI, strategy);
        disruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            // stand-in for processLogEntry at ERROR level: a little work per entry
            long h = event[0];
            for (int i = 0; i < 200; i++) {
                h = h * 31 + i;
            }
            sink = h;
            consumed.incrementAndGet();
            if (endOfBatch) {
                batches.incrementAndGet();
            }
        });
        disruptor.start();
        ringBuffer = disruptor.getRingBuffer();
    }

    @Setup(Level.Iteration)
    public void startIteration() {
        publishNanos.reset();
        published.reset();
        consumedAtStart = consumed.get();
        batchesAtStart = batches.get();
        consumerCpuAtStart = threadMXBean.getThreadCpuTime(consumerThread.getId());
    }

    @TearDown(Level.Iteration)
    public void endIteration() {
        long entries = Math.max(1, consumed.get() - consumedAtStart);
        long cpu = threadMXBean.getThreadCpuTime(consumerThread.getId()) - consumerCpuAtStart;
        long publishes = Math.max(1, published.sum());
        System.out.printf("%n[wait=%s think=%d] publish_ns=%.1f consumer_cpu_ns=%.1f batches_per_entry=%.3f entries=%d%n",
            waitStrategy, thinkNanos, (double) publishNanos.sum() / publishes, (double) cpu / entries,
            (double) (batches.get() - batchesAtStart) / entries, entries);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        disruptor.halt();
    }

    @Benchmark
    public boolean thinkThenPublish() {
        long thinkUntil = System.nanoTime() + thinkNanos;
        while (System.nanoTime() < thinkUntil) {
            Thread.onSpinWait();
        }
        long start = System.nanoTime();
        boolean ok = ringBuffer.tryPublishEvent(TRANSLATOR);
        publishNanos.add(System.nanoTime() - start);
        published.increment();
        return ok;
    }
}
