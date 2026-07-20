package org.mockserver.mock.listeners;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests MockServerEventLogNotifier register/unregister/notify behaviour
 * using a real synchronous Scheduler (no Mockito).
 */
public class MockServerEventLogNotifierTest {

    /**
     * Concrete subclass to expose the protected notifyListeners method for testing.
     */
    private static class TestableEventLogNotifier extends MockServerEventLogNotifier {
        TestableEventLogNotifier(Scheduler scheduler) {
            super(scheduler);
        }

        void fireNotification(MockServerEventLog notifier, boolean synchronous) {
            notifyListeners(notifier, synchronous);
        }

        void stop() {
            stopNotifications();
        }
    }

    /**
     * Counting listener for the async coalescing tests.
     */
    private static class CountingLogListener implements MockServerLogListener {
        final AtomicInteger updateCount = new AtomicInteger(0);

        @Override
        public void updated(MockServerEventLog mockServerLog) {
            updateCount.incrementAndGet();
        }
    }

    /**
     * A ScheduledExecutorService with a virtual clock, so the debounce window can be advanced
     * deterministically instead of slept through. Only the two methods the notifier uses are
     * implemented: schedule(Runnable, delay, unit) and isShutdown(). advanceBy(ms) runs every task
     * whose deadline has arrived — and honours tasks that re-schedule themselves during a tick
     * (the notifier's re-arm), because a task scheduled at the new clock value only fires on a
     * later advance, exactly as a real fixed-window debounce behaves.
     */
    private static final class ManualTickExecutor implements ScheduledExecutorService {
        private long nowMillis = 0L;
        private final List<Scheduled> tasks = new ArrayList<>();
        private boolean shutdown = false;

        private static final class Scheduled {
            final long dueMillis;
            final Runnable task;
            Scheduled(long dueMillis, Runnable task) {
                this.dueMillis = dueMillis;
                this.task = task;
            }
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(new Scheduled(nowMillis + unit.toMillis(delay), command));
            return new NoopScheduledFuture();
        }

        /** Advance the virtual clock and run every task now due, in deadline order. */
        void advanceBy(long millis, TimeUnit unit) {
            nowMillis += unit.toMillis(millis);
            boolean progressed = true;
            while (progressed) {
                progressed = false;
                Scheduled due = null;
                for (Scheduled s : tasks) {
                    if (s.dueMillis <= nowMillis) {
                        due = s;
                        break;
                    }
                }
                if (due != null) {
                    tasks.remove(due);
                    due.task.run();
                    progressed = true;
                }
            }
        }

        int pendingTaskCount() {
            return tasks.size();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        // --- unused ScheduledExecutorService surface: the notifier never calls these ---
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> c, long d, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable c, long i, long p, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable c, long i, long d, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return new ArrayList<>(); }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
        @Override public <T> java.util.concurrent.Future<T> submit(Callable<T> t) { throw new UnsupportedOperationException(); }
        @Override public <T> java.util.concurrent.Future<T> submit(Runnable t, T r) { throw new UnsupportedOperationException(); }
        @Override public java.util.concurrent.Future<?> submit(Runnable t) { throw new UnsupportedOperationException(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> t) { throw new UnsupportedOperationException(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> t, long to, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> t) { throw new UnsupportedOperationException(); }
        @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> t, long to, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public void execute(Runnable command) { command.run(); }

        private static final class NoopScheduledFuture implements ScheduledFuture<Object> {
            @Override public long getDelay(TimeUnit unit) { return 0; }
            @Override public int compareTo(Delayed o) { return 0; }
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return false; }
            @Override public Object get() { return null; }
            @Override public Object get(long timeout, TimeUnit unit) { return null; }
        }
    }

    /**
     * A Scheduler whose executor is the manual-tick one, so the notifier's debounce path is driven
     * by advanceBy(...) rather than the wall clock. Uses the synchronous super-constructor so no
     * real thread pool is allocated, then overrides getExecutorService() to hand back the manual
     * executor.
     */
    private static final class ManualScheduler extends Scheduler {
        private final ManualTickExecutor executor;
        ManualScheduler(ManualTickExecutor executor) {
            super(Configuration.configuration(), new MockServerLogger(), true);
            this.executor = executor;
        }
        @Override
        public ScheduledExecutorService getExecutorService() {
            return executor;
        }
    }

    private TestableEventLogNotifier createAsyncNotifier(Scheduler scheduler) {
        return new TestableEventLogNotifier(scheduler);
    }

    /**
     * Real listener that records calls for assertion.
     */
    private static class RecordingLogListener implements MockServerLogListener {
        private final List<MockServerEventLog> receivedLogs = new ArrayList<>();

        @Override
        public void updated(MockServerEventLog mockServerLog) {
            receivedLogs.add(mockServerLog);
        }

        List<MockServerEventLog> getReceivedLogs() {
            return receivedLogs;
        }
    }

    private TestableEventLogNotifier createNotifier() {
        Configuration configuration = Configuration.configuration();
        MockServerLogger logger = new MockServerLogger();
        Scheduler scheduler = new Scheduler(configuration, logger, true);
        return new TestableEventLogNotifier(scheduler);
    }

    @Test
    public void shouldNotifyRegisteredListener() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener = new RecordingLogListener();

        notifier.registerListener(listener);
        notifier.fireNotification(null, true);

        assertThat(listener.getReceivedLogs(), hasSize(1));
    }

    @Test
    public void shouldNotifyMultipleListeners() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener1 = new RecordingLogListener();
        RecordingLogListener listener2 = new RecordingLogListener();

        notifier.registerListener(listener1);
        notifier.registerListener(listener2);
        notifier.fireNotification(null, true);

        assertThat(listener1.getReceivedLogs(), hasSize(1));
        assertThat(listener2.getReceivedLogs(), hasSize(1));
    }

    @Test
    public void shouldNotNotifyWhenNoListenersRegistered() {
        TestableEventLogNotifier notifier = createNotifier();

        // should not throw
        notifier.fireNotification(null, true);
    }

    @Test
    public void shouldNotNotifyAfterUnregister() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener = new RecordingLogListener();

        notifier.registerListener(listener);
        notifier.unregisterListener(listener);
        notifier.fireNotification(null, true);

        assertThat(listener.getReceivedLogs(), is(empty()));
    }

    @Test
    public void shouldOnlyUnregisterSpecifiedListener() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener1 = new RecordingLogListener();
        RecordingLogListener listener2 = new RecordingLogListener();

        notifier.registerListener(listener1);
        notifier.registerListener(listener2);
        notifier.unregisterListener(listener1);
        notifier.fireNotification(null, true);

        assertThat(listener1.getReceivedLogs(), is(empty()));
        assertThat(listener2.getReceivedLogs(), hasSize(1));
    }

    @Test
    public void shouldNotifyListenerMultipleTimes() {
        TestableEventLogNotifier notifier = createNotifier();
        RecordingLogListener listener = new RecordingLogListener();

        notifier.registerListener(listener);
        notifier.fireNotification(null, true);
        notifier.fireNotification(null, true);
        notifier.fireNotification(null, true);

        assertThat(listener.getReceivedLogs(), hasSize(3));
    }

    @Test
    public void shouldCoalesceRapidAsynchronousNotifications() {
        // Manual-tick executor so the debounce window is advanced deterministically rather than
        // slept through: wall-clock-independent, and it asserts the actual coalescing property
        // (N calls suppressed to one per window) instead of a machine-speed-dependent count.
        ManualTickExecutor executor = new ManualTickExecutor();
        ManualScheduler scheduler = new ManualScheduler(executor);
        TestableEventLogNotifier notifier = createAsyncNotifier(scheduler);
        CountingLogListener listener = new CountingLogListener();
        notifier.registerListener(listener);

        // 1) a burst of 1000 async notifications, no time advanced: every one is suppressed. This
        //    is the property that matters — 0 dispatches, not "a small number". A single scheduled
        //    task is armed.
        for (int i = 0; i < 1000; i++) {
            notifier.fireNotification(null, false);
        }
        assertThat("1000 async adds within one window must all be suppressed until the window ends",
            listener.updateCount.get(), is(0));
        assertThat("only one coalescing task should be armed for the whole burst",
            executor.pendingTaskCount(), is(1));

        // 2) advance past one window: exactly one dispatch fires for the whole burst.
        executor.advanceBy(MockServerEventLogNotifier.COALESCE_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        assertThat("the burst must coalesce to exactly one notification",
            listener.updateCount.get(), is(1));

        // 3) a second burst after the first window arms a fresh task; advancing again fires once
        //    more. This proves the re-arm — that the notifier keeps working for the NEXT window,
        //    which nothing previously tested.
        for (int i = 0; i < 500; i++) {
            notifier.fireNotification(null, false);
        }
        assertThat("the second burst must not fire until its own window elapses",
            listener.updateCount.get(), is(1));
        executor.advanceBy(MockServerEventLogNotifier.COALESCE_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        assertThat("the debounce must re-arm and coalesce the second burst to one more notification",
            listener.updateCount.get(), is(2));
    }

    @Test
    public void shouldFireSynchronousNotificationsImmediatelyWithoutCoalescing() {
        // even with an async Scheduler, synchronous=true must fire immediately and per-call
        Scheduler scheduler = new Scheduler(Configuration.configuration(), new MockServerLogger(), false);
        try {
            TestableEventLogNotifier notifier = createAsyncNotifier(scheduler);
            CountingLogListener listener = new CountingLogListener();
            notifier.registerListener(listener);

            notifier.fireNotification(null, true);
            notifier.fireNotification(null, true);
            notifier.fireNotification(null, true);

            // immediate, no waiting required, no coalescing
            assertThat(listener.updateCount.get(), is(3));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void shouldNotLeakCoalescingTaskAfterStop() throws InterruptedException {
        Scheduler scheduler = new Scheduler(Configuration.configuration(), new MockServerLogger(), false);
        try {
            TestableEventLogNotifier notifier = createAsyncNotifier(scheduler);
            CountingLogListener listener = new CountingLogListener();
            notifier.registerListener(listener);

            // schedule a pending coalesced notification then immediately stop
            notifier.fireNotification(null, false);
            notifier.stop();

            // any further async notifications after stop must not be scheduled/dispatched
            notifier.fireNotification(null, false);

            // wait well beyond the debounce window: the cancelled task must never fire
            Thread.sleep(1500);
            assertThat("coalescing task must be cancelled on stop and not fire afterwards",
                listener.updateCount.get(), is(0));
        } finally {
            scheduler.shutdown();
        }
    }
}
