package org.mockserver.scheduler;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.slf4j.event.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.scheduler.Scheduler.delayOptional;
import static org.mockserver.scheduler.Scheduler.rejectable;
import static org.mockserver.scheduler.Scheduler.sheddable;

/**
 * Queue-full behaviour of the bounded dispatch paths: a {@link Scheduler#rejectable} task over
 * {@code maxPendingDelayedResponses} / {@code maxQueuedTemplateActions} runs its fallback instead of being
 * held, while tasks inside the bound keep their delay and ordering semantics.
 */
public class SchedulerOverloadTest {

    private static final Delay LONG_DELAY = Delay.seconds(30);

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @Test(timeout = 10_000)
    public void shouldRejectDelayedTaskOverTheLimitWithItsFallback() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(2), mockServerLogger, false);
        try {
            AtomicInteger ran = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();

            for (int i = 0; i < 5; i++) {
                scheduler.schedule(rejectable(ran::incrementAndGet, rejected::incrementAndGet), false, LONG_DELAY);
            }

            assertThat("fallback ran inline for every task over the limit", rejected.get(), is(3));
            assertThat("admitted tasks are still waiting for their delay", ran.get(), is(0));
            assertThat(scheduler.getPendingDelayedTaskCount(), is(2));
            assertThat("only admitted tasks occupy the scheduler queue", scheduler.getQueuedTaskCount(), is(2));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldAdmitAgainOnceDelayedTasksHaveFired() throws Exception {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(1), mockServerLogger, false);
        try {
            CountDownLatch firstRan = new CountDownLatch(1);
            AtomicInteger rejected = new AtomicInteger();
            // the first delay must outlast the second dispatch, or the slot frees before it is tested
            scheduler.schedule(rejectable(firstRan::countDown, rejected::incrementAndGet), false, Delay.milliseconds(1_500));
            scheduler.schedule(rejectable(() -> {
            }, rejected::incrementAndGet), false, Delay.milliseconds(100));
            assertThat(rejected.get(), is(1));

            assertThat("admitted task ran after its delay", firstRan.await(5, TimeUnit.SECONDS), is(true));
            awaitPendingDelayed(scheduler, 0);

            CountDownLatch secondRan = new CountDownLatch(1);
            scheduler.schedule(rejectable(secondRan::countDown, rejected::incrementAndGet), false, Delay.milliseconds(10));
            assertThat("admission reopened once the pending task fired", secondRan.await(5, TimeUnit.SECONDS), is(true));
            assertThat(rejected.get(), is(1));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldNotLetAnUnrefusableBacklogRefuseAnUnrelatedResponse() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(1), mockServerLogger, false);
        try {
            for (int i = 0; i < 5; i++) {
                scheduler.schedule(() -> {
                }, false, LONG_DELAY);
            }
            assertThat("unrefusable delays stay visible", scheduler.getPendingDelayedTaskCount(), is(5));

            AtomicInteger rejected = new AtomicInteger();
            scheduler.schedule(rejectable(() -> {
            }, rejected::incrementAndGet), false, LONG_DELAY);

            assertThat("they do not consume the response budget", rejected.get(), is(0));
            assertThat(scheduler.getPendingDelayedResponseCount(), is(1));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldShedSideActionsOnTheirOwnBudgetWithoutRefusingResponses() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(2), mockServerLogger, false);
        try {
            AtomicInteger sideActionsRan = new AtomicInteger();
            for (int i = 0; i < 5; i++) {
                scheduler.submitAsync(sheddable(sideActionsRan::incrementAndGet), LONG_DELAY);
            }
            assertThat("side actions over their budget are dropped, not held", scheduler.getPendingDelayedTaskCount(), is(2));
            assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.SIDE_ACTIONS), is(3L));

            AtomicInteger rejected = new AtomicInteger();
            scheduler.schedule(rejectable(() -> {
            }, rejected::incrementAndGet), false, LONG_DELAY);
            scheduler.schedule(rejectable(() -> {
            }, rejected::incrementAndGet), false, LONG_DELAY);

            assertThat("a side-action backlog never refuses a response", rejected.get(), is(0));
            assertThat(sideActionsRan.get(), is(0));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldRunADelayOptionalWriteAtOnceWhenTheResponseBudgetIsFull() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(1), mockServerLogger, false);
        try {
            scheduler.schedule(rejectable(() -> {
            }, () -> {
            }), false, LONG_DELAY);
            Thread caller = Thread.currentThread();
            AtomicReference<Thread> wroteOn = new AtomicReference<>();

            scheduler.schedule(delayOptional(() -> wroteOn.set(Thread.currentThread())), false, LONG_DELAY);

            assertThat("written immediately, without the delay, rather than discarded", wroteOn.get() == caller, is(true));
            assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.DELAY_SKIPPED), is(1L));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldRunUndelayedRejectableTaskInlineRegardlessOfTheLimit() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(1), mockServerLogger, false);
        try {
            scheduler.schedule(rejectable(() -> {
            }, () -> {
            }), false, LONG_DELAY);
            Thread caller = Thread.currentThread();
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            AtomicInteger rejected = new AtomicInteger();

            scheduler.schedule(rejectable(() -> ranOn.set(Thread.currentThread()), rejected::incrementAndGet), false);

            assertThat("no delay means nothing is queued, so nothing is refused", rejected.get(), is(0));
            assertThat(ranOn.get() == caller, is(true));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldNotBoundWhenLimitIsZero() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(0), mockServerLogger, false);
        try {
            AtomicInteger rejected = new AtomicInteger();
            for (int i = 0; i < 50; i++) {
                scheduler.schedule(rejectable(() -> {
                }, rejected::incrementAndGet), false, LONG_DELAY);
            }
            assertThat(rejected.get(), is(0));
            assertThat(scheduler.getPendingDelayedTaskCount(), is(50));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldApplyAChangedLimitAtRuntime() {
        org.mockserver.configuration.Configuration configuration = configuration().maxPendingDelayedResponses(10);
        Scheduler scheduler = new Scheduler(configuration, mockServerLogger, false);
        try {
            AtomicInteger rejected = new AtomicInteger();
            for (int i = 0; i < 3; i++) {
                scheduler.schedule(rejectable(() -> {
                }, rejected::incrementAndGet), false, LONG_DELAY);
            }
            assertThat(rejected.get(), is(0));

            configuration.maxPendingDelayedResponses(3);
            scheduler.applyConfigurationCapacity();
            scheduler.schedule(rejectable(() -> {
            }, rejected::incrementAndGet), false, LONG_DELAY);

            assertThat(rejected.get(), is(1));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldBoundDelayedLocalCallbacks() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(1), mockServerLogger, false);
        try {
            AtomicInteger rejected = new AtomicInteger();
            scheduler.scheduleLocalCallback(rejectable(() -> {
            }, rejected::incrementAndGet), false, LONG_DELAY);
            scheduler.scheduleLocalCallback(rejectable(() -> {
            }, rejected::incrementAndGet), false, LONG_DELAY);

            assertThat(rejected.get(), is(1));
            assertThat(scheduler.getPendingDelayedTaskCount(), is(1));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldRejectTemplateActionWhenTemplateQueueIsFull() throws Exception {
        Scheduler scheduler = new Scheduler(configuration().actionHandlerThreadCount(1).maxQueuedTemplateActions(2), mockServerLogger, false);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch blockerStarted = new CountDownLatch(1);
            scheduler.scheduleTemplateAction(rejectable(() -> {
                blockerStarted.countDown();
                awaitQuietly(release);
            }, () -> {
            }), false);
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS), is(true));

            CountDownLatch queuedRan = new CountDownLatch(2);
            AtomicInteger rejected = new AtomicInteger();
            Thread caller = Thread.currentThread();
            AtomicReference<Thread> rejectedOn = new AtomicReference<>();
            for (int i = 0; i < 4; i++) {
                scheduler.scheduleTemplateAction(rejectable(queuedRan::countDown, () -> {
                    rejectedOn.set(Thread.currentThread());
                    rejected.incrementAndGet();
                }), false);
            }

            assertThat("renders over the queue limit were refused", rejected.get(), is(2));
            assertThat("refusal is answered on the dispatching thread, not queued", rejectedOn.get() == caller, is(true));
            assertThat(scheduler.getQueuedTemplateActionCount(), is(2));

            release.countDown();
            assertThat("renders inside the bound still run", queuedRan.await(5, TimeUnit.SECONDS), is(true));
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldRejectDelayedTemplateActionWhenTemplateQueueIsFullOnceTheDelayElapses() throws Exception {
        Scheduler scheduler = new Scheduler(configuration().actionHandlerThreadCount(1).maxQueuedTemplateActions(1), mockServerLogger, false);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch blockerStarted = new CountDownLatch(1);
            scheduler.scheduleTemplateAction(() -> {
                blockerStarted.countDown();
                awaitQuietly(release);
            }, false);
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS), is(true));
            scheduler.scheduleTemplateAction(rejectable(() -> {
            }, () -> {
            }), false);

            CountDownLatch rejected = new CountDownLatch(1);
            AtomicReference<Thread> rejectedOn = new AtomicReference<>();
            scheduler.scheduleTemplateAction(rejectable(() -> {
            }, () -> {
                rejectedOn.set(Thread.currentThread());
                rejected.countDown();
            }), false, Delay.milliseconds(50));

            assertThat("delayed render refused after its delay because the queue was still full", rejected.await(5, TimeUnit.SECONDS), is(true));
            assertThat(rejectedOn.get().getName(), startsWith("MockServer-Scheduler"));
            assertThat(scheduler.getPendingDelayedTaskCount(), is(0));
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldLogOverloadAtMostOncePerIntervalWithTheRejectedCount() {
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(1), logger, false);
        try {
            for (int i = 0; i < 6; i++) {
                scheduler.schedule(rejectable(() -> {
                }, () -> {
                }), false, LONG_DELAY);
            }

            ArgumentCaptor<LogEntry> logEntry = ArgumentCaptor.forClass(LogEntry.class);
            verify(logger, times(1)).logEvent(logEntry.capture());
            assertThat(logEntry.getValue().getLogLevel(), is(Level.WARN));
            assertThat(logEntry.getValue().getMessageFormat(), containsString("overloaded"));
            assertThat("the exact total is kept without Prometheus", scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.DELAYED_RESPONSES), is(5L));
            assertThat(logEntry.getValue().getArguments(), is(new Object[]{1L, "responses waiting for a configured delay", "maxPendingDelayedResponses", 1, "answered 503 Service Unavailable", 1L}));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(timeout = 10_000)
    public void shouldKeepSynchronousModeUnbounded() {
        Scheduler scheduler = new Scheduler(configuration().maxPendingDelayedResponses(1), mockServerLogger, true);
        try {
            AtomicInteger ran = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            for (int i = 0; i < 3; i++) {
                scheduler.schedule(rejectable(ran::incrementAndGet, rejected::incrementAndGet), false, Delay.milliseconds(1));
            }
            assertThat("WAR/servlet mode delays inline on the request thread, so nothing is queued", ran.get(), is(3));
            assertThat(rejected.get(), is(0));
        } finally {
            scheduler.shutdown();
        }
    }

    private static void awaitPendingDelayed(Scheduler scheduler, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scheduler.getPendingDelayedTaskCount() != expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(scheduler.getPendingDelayedTaskCount(), is(expected));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
