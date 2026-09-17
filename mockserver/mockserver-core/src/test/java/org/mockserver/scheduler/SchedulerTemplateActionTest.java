package org.mockserver.scheduler;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Unit + measurement guard for {@link Scheduler#scheduleTemplateAction} — the fix that moves the
 * synchronous, CPU-bound RESPONSE_TEMPLATE / FORWARD_TEMPLATE render off the server worker event loop and
 * off the bounded shared scheduler pool onto a dedicated bounded template pool, so a burst of slow renders
 * can no longer stall unrelated match/forward traffic (the observed all-arm contagion).
 */
public class SchedulerTemplateActionTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    /**
     * Asynchronous mode: the template action must NOT run inline on the calling (worker) thread; it must run
     * on the dedicated {@code MockServer-TemplateAction} pool — the whole point of the isolation.
     */
    @Test(timeout = 10_000)
    public void shouldRunTemplateActionOffTheCallingThreadInAsyncMode() throws Exception {
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, false);
        try {
            Thread callingThread = Thread.currentThread();
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            scheduler.scheduleTemplateAction(() -> {
                ranOn.set(Thread.currentThread());
                latch.countDown();
            }, false);

            assertThat("template action ran", latch.await(5, TimeUnit.SECONDS), is(true));
            assertThat(ranOn.get(), is(notNullValue()));
            assertThat("did not run inline on the calling (worker) thread", ranOn.get() == callingThread, is(false));
            assertThat("ran on the dedicated template pool", ranOn.get().getName(), startsWith("MockServer-TemplateAction"));
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * Synchronous mode (WAR/servlet semantics): the template action must run INLINE on the calling thread so
     * the response is produced before the caller returns.
     */
    @Test(timeout = 10_000)
    public void shouldRunTemplateActionInlineInSynchronousMode() {
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, true);
        try {
            Thread callingThread = Thread.currentThread();
            AtomicReference<Thread> ranOn = new AtomicReference<>();

            scheduler.scheduleTemplateAction(() -> ranOn.set(Thread.currentThread()), false);

            assertThat("ran inline on the calling thread", ranOn.get() == callingThread, is(true));
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * DELAY semantics: a configured response delay on a templated action must still be honoured. Fails if the
     * delay is dropped when the action is routed onto the dedicated template pool.
     */
    @Test(timeout = 10_000)
    public void shouldHonourConfiguredDelayForTemplateActionInAsyncMode() throws Exception {
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, false);
        try {
            long delayMillis = 300;
            CountDownLatch latch = new CountDownLatch(1);
            AtomicLong elapsed = new AtomicLong();
            long start = System.nanoTime();

            scheduler.scheduleTemplateAction(() -> {
                elapsed.set(System.nanoTime() - start);
                latch.countDown();
            }, false, Delay.milliseconds(delayMillis));

            assertThat("delayed template action ran", latch.await(5, TimeUnit.SECONDS), is(true));
            long elapsedMillis = elapsed.get() / 1_000_000L;
            // allow a small scheduling-jitter tolerance below the configured delay
            assertThat("configured delay was honoured (elapsed " + elapsedMillis + "ms)",
                elapsedMillis, greaterThanOrEqualTo(delayMillis - 40));
        } finally {
            scheduler.shutdown();
        }
    }

    /** Synchronous mode honours the delay inline too. */
    @Test(timeout = 10_000)
    public void shouldHonourConfiguredDelayForTemplateActionInSynchronousMode() {
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, true);
        try {
            long delayMillis = 200;
            long start = System.nanoTime();
            scheduler.scheduleTemplateAction(() -> {
            }, false, Delay.milliseconds(delayMillis));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertThat("inline delay was honoured (elapsed " + elapsedMillis + "ms)",
                elapsedMillis, greaterThanOrEqualTo(delayMillis - 40));
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * CONTAGION measurement (before/after, deterministic). A tiny action-handler pool is saturated with slow
     * "renders". We then measure how long a burst of unrelated probe tasks (standing in for plain
     * match/forward dispatch, which run on the shared scheduler pool) wait from submit to start:
     * <ul>
     *   <li><b>BEFORE</b> — the slow renders are dispatched onto the SAME shared scheduler pool via
     *       {@link Scheduler#submit}, exactly as {@code scheduler.schedule} routed template rendering before
     *       this fix. The probes queue behind them and wait a long time.</li>
     *   <li><b>AFTER</b> — the slow renders are dispatched via {@link Scheduler#scheduleTemplateAction} onto
     *       the dedicated bounded template pool, leaving the shared scheduler pool free. The probes run
     *       almost immediately.</li>
     * </ul>
     * The assertion is a large, robust margin (AFTER at least 5x faster than BEFORE) so it is not flaky, and
     * the real numbers are printed for the record.
     */
    @Test(timeout = 60_000)
    public void shouldIsolateSlowTemplateRendersFromUnrelatedSchedulerWork() throws Exception {
        int poolSize = 2;                 // model a small shared pool (the incident box was starved likewise)
        int slowTasks = 40;               // saturate it
        long renderCostMillis = 100;      // each "render" blocks a pool thread for this long
        int probes = 20;                  // unrelated match/forward dispatches arriving during the burst

        long before = measureUnrelatedProbeWait(false, poolSize, slowTasks, renderCostMillis, probes);
        long after = measureUnrelatedProbeWait(true, poolSize, slowTasks, renderCostMillis, probes);

        System.out.printf("[template-isolation] unrelated-probe median wait: BEFORE(shared pool)=%dms  AFTER(dedicated pool)=%dms%n",
            before, after);

        assertThat("dispatching slow renders onto the shared pool starves unrelated work", before, greaterThanOrEqualTo(renderCostMillis));
        assertThat("dedicated template pool keeps unrelated work fast", after, lessThan(before / 5));
    }

    /**
     * Returns the MEDIAN submit->start wait (ms) of {@code probes} unrelated tasks submitted onto the shared
     * scheduler pool while {@code slowTasks} slow "renders" run — either on the shared pool ({@code false}) or
     * on the dedicated template pool ({@code true}).
     */
    private long measureUnrelatedProbeWait(boolean useTemplatePool, int poolSize, int slowTasks,
                                           long renderCostMillis, int probes) throws Exception {
        Configuration configuration = configuration().actionHandlerThreadCount(poolSize);
        Scheduler scheduler = new Scheduler(configuration, mockServerLogger, false);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch rendersDone = new CountDownLatch(slowTasks);
            CountDownLatch probesDone = new CountDownLatch(probes);
            long[] probeWaitNanos = new long[probes];

            Runnable slowRender = () -> {
                await(startGate);
                sleep(renderCostMillis);
                rendersDone.countDown();
            };

            List<Long> probeSubmitNanos = new ArrayList<>();
            int probeEvery = Math.max(1, slowTasks / probes);
            int probeIdx = 0;
            for (int i = 0; i < slowTasks; i++) {
                if (useTemplatePool) {
                    scheduler.scheduleTemplateAction(slowRender, false);
                } else {
                    scheduler.submit(slowRender);
                }
                if (i % probeEvery == 0 && probeIdx < probes) {
                    final int p = probeIdx++;
                    long submitNanos = System.nanoTime();
                    probeSubmitNanos.add(submitNanos);
                    // probes always go on the shared scheduler pool (they stand in for plain match/forward)
                    scheduler.submit(() -> {
                        probeWaitNanos[p] = System.nanoTime() - submitNanos;
                        probesDone.countDown();
                    });
                }
            }
            startGate.countDown();

            assertThat("all renders completed", rendersDone.await(45, TimeUnit.SECONDS), is(true));
            assertThat("all probes completed", probesDone.await(45, TimeUnit.SECONDS), is(true));

            long[] waits = Arrays.copyOf(probeWaitNanos, probeIdx);
            Arrays.sort(waits);
            return waits[waits.length / 2] / 1_000_000L;
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * THREAD-LEAK guard: repeatedly starting and stopping a Scheduler must not leak template-pool threads
     * across the JVM (test suites start and stop many instances in one JVM). After shutdown the daemon
     * template threads must terminate; the live {@code MockServer-TemplateAction} thread count must return to
     * baseline rather than growing with each iteration.
     */
    @Test(timeout = 30_000)
    public void shouldNotLeakTemplatePoolThreadsAcrossStartStop() throws Exception {
        int baseline = countTemplateThreads();
        for (int i = 0; i < 20; i++) {
            Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, false);
            CountDownLatch latch = new CountDownLatch(1);
            scheduler.scheduleTemplateAction(latch::countDown, false);
            assertThat("action ran", latch.await(5, TimeUnit.SECONDS), is(true));
            scheduler.shutdown();
        }
        // daemon threads terminate shortly after shutdown(); give them a brief moment then assert no growth
        long deadline = System.currentTimeMillis() + 5_000;
        int live;
        while ((live = countTemplateThreads()) > baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat("template pool threads returned to baseline (no leak); live=" + live + " baseline=" + baseline,
            countTemplateThreads(), lessThan(baseline + 3));
    }

    private static int countTemplateThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("MockServer-TemplateAction")) {
                count++;
            }
        }
        return count;
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
