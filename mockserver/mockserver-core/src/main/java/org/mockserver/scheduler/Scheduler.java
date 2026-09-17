package org.mockserver.scheduler;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.SocketCommunicationException;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.HttpForwardActionResult;
import org.mockserver.model.BinaryMessage;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpResponse;
import org.slf4j.event.Level;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockserver.log.model.LogEntry.LogMessageType.WARN;
import static org.mockserver.mock.HttpState.getPort;
import static org.mockserver.mock.HttpState.setPort;

/**
 * @author jamesdbloom
 */
public class Scheduler {

    private final Configuration configuration;
    private final ScheduledExecutorService scheduler;
    // Dedicated, UNBOUNDED executor used solely to dispatch LOCAL (in-JVM) object/class callbacks off
    // the server worker event loop. A local callback may make a BLOCKING loopback call back to the same
    // server (e.g. registering a nested expectation via the same MockServerClient); running it inline on
    // the worker event loop, or on the bounded scheduler pool, risks a self-deadlock (the canonical
    // twice-burned regression) or pool starvation under recursion. An unbounded cached pool guarantees
    // that an inner (recursively-triggered) local callback always obtains a fresh thread even when every
    // outer callback thread is blocked waiting on its own loopback — so the wait is always bounded and
    // never a pool-exhaustion deadlock. Threads are reused and reaped after 60s idle, so steady-state
    // cost is the high-water mark of CONCURRENT in-flight blocking callbacks, not a fixed allocation.
    // Null in synchronous mode (callbacks then run inline, preserving WAR/servlet blocking semantics).
    private final ExecutorService localCallbackExecutor;

    // Dedicated, BOUNDED executor used solely to run RESPONSE_TEMPLATE / FORWARD_TEMPLATE rendering off the
    // server worker event loop and off the shared scheduler pool. Template rendering (Velocity/Mustache/
    // JavaScript) is a synchronous, CPU-bound computation that can take tens of milliseconds. Dispatched
    // through the ordinary Scheduler.schedule path it ran INLINE on the Netty worker event-loop thread when
    // there was no delay (see schedule(): zero delay => run() on the calling thread), or on the bounded
    // shared scheduler pool when a delay was configured. Either way a burst of slow renders monopolised a
    // resource shared with every other action type and stalled unrelated plain-match/forward traffic — the
    // single simultaneous all-arm collapse seen in the live incident. Giving templates their own pool
    // isolates that cost: when renders saturate this pool they queue among THEMSELVES, so the worker event
    // loop and the scheduler pool stay free to serve everything else.
    //
    // BOUNDED, unlike the unbounded localCallbackExecutor. That pool is unbounded to defeat a self-deadlock:
    // a local callback may make a BLOCKING loopback call back to this same server and recursively trigger
    // another local callback, so an inner callback must always obtain a fresh thread. Template rendering does
    // NONE of that — it is a self-contained computation with no loopback and no recursion — so the reason to
    // go unbounded simply does not apply here. For CPU-bound work an unbounded pool is actively worse: it
    // would spawn more concurrent render threads than cores (pure context-switch thrash) and, on a
    // memory-constrained SUT, each extra thread's stack brings the separately-diagnosed response-body
    // retention OOM SOONER rather than later. A fixed pool sized like the scheduler pool
    // (actionHandlerThreadCount() = max(5, cores)) gives full CPU parallelism with a fixed memory ceiling.
    // The task QUEUE is unbounded so a render task is never rejected back onto the worker event loop; it only
    // ever holds one pending closure per queued render. Each retains the inbound HttpRequest (body included),
    // the action, the response writer and the channel context until drained — so a sustained arrival rate above
    // render capacity grows this queue in proportion to in-flight requests. The large RENDERED body is produced
    // by the task and is never queued. Rejecting instead would push work back onto the event loop, which is the
    // pathology this exists to remove, so an unbounded queue is the least-bad choice rather than a free one.
    // Null in synchronous mode (renders then run inline, preserving WAR/servlet blocking semantics).
    private final ExecutorService templateActionExecutor;

    private final boolean synchronous;

    public static class SchedulerThreadFactory implements ThreadFactory {

        private final String name;
        private final boolean daemon;
        private static final AtomicInteger threadInitNumber = new AtomicInteger();

        public SchedulerThreadFactory(String name) {
            this.name = name;
            this.daemon = true;
        }

        public SchedulerThreadFactory(String name, boolean daemon) {
            this.name = name;
            this.daemon = daemon;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MockServer-" + name + threadInitNumber.getAndIncrement());
            thread.setDaemon(daemon);
            return thread;
        }
    }

    private final MockServerLogger mockServerLogger;

    public Scheduler(Configuration configuration, MockServerLogger mockServerLogger) {
        this(configuration, mockServerLogger, false);
    }

    @VisibleForTesting
    public Scheduler(Configuration configuration, MockServerLogger mockServerLogger, boolean synchronous) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.synchronous = synchronous;
        if (!this.synchronous) {
            this.scheduler = new ScheduledThreadPoolExecutor(
                configuration.actionHandlerThreadCount(),
                new SchedulerThreadFactory("Scheduler"),
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            // Unbounded cached pool for local-callback dispatch (see field javadoc). Core size 0,
            // max Integer.MAX_VALUE, 60s keep-alive — grows on demand and shrinks back to zero when
            // idle, so it never deadlocks a recursive/nested blocking local callback the way a bounded
            // pool would, yet costs nothing at rest.
            ThreadPoolExecutor localCallbackPool = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new SchedulerThreadFactory("LocalCallback")
            );
            this.localCallbackExecutor = localCallbackPool;
            // Dedicated BOUNDED pool for template-action rendering (see field javadoc). Fixed size =
            // actionHandlerThreadCount() (max(5, cores)) with an unbounded LinkedBlockingQueue: full CPU
            // parallelism for renders, a fixed thread/memory ceiling, and no fallback onto the worker event
            // loop. Threads are daemon (via SchedulerThreadFactory) so they never block JVM shutdown.
            int templateThreads = configuration.actionHandlerThreadCount();
            this.templateActionExecutor = new ThreadPoolExecutor(
                templateThreads, templateThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new SchedulerThreadFactory("TemplateAction")
            );
        } else {
            this.scheduler = null;
            this.localCallbackExecutor = null;
            this.templateActionExecutor = null;
        }
    }

    /**
     * Returns the underlying executor service for use with CompletableFuture
     * async continuations (e.g. thenAcceptAsync). Returns null in synchronous
     * mode — callers must handle that case (run inline).
     */
    public ScheduledExecutorService getExecutorService() {
        return scheduler;
    }

    public synchronized void shutdown() {
        // Both executors are null in synchronous mode (WAR/servlet) — guard both so shutdown() is a
        // safe no-op there, matching the localCallbackExecutor guard below.
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(500, MILLISECONDS);
            } catch (InterruptedException ignore) {
                // ignore interrupted exception
            }
        }
        if (localCallbackExecutor != null && !localCallbackExecutor.isShutdown()) {
            localCallbackExecutor.shutdown();
            try {
                localCallbackExecutor.awaitTermination(500, MILLISECONDS);
            } catch (InterruptedException ignore) {
                // ignore interrupted exception
            }
        }
        if (templateActionExecutor != null && !templateActionExecutor.isShutdown()) {
            templateActionExecutor.shutdown();
            try {
                templateActionExecutor.awaitTermination(500, MILLISECONDS);
            } catch (InterruptedException ignore) {
                // ignore interrupted exception
            }
        }
    }

    private void run(Runnable command, Integer port) {
        setPort(port);
        try {
            command.run();
        } catch (Throwable throwable) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(WARN)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
        }
    }

    public void submitAsync(Runnable command, Delay... delays) {
        long delayMillis = sampleCombinedDelayMillis(delays);
        Integer port = getPort();
        if (scheduler != null) {
            if (delayMillis > 0) {
                scheduler.schedule(() -> run(command, port), delayMillis, MILLISECONDS);
            } else {
                scheduler.submit(() -> run(command, port));
            }
        } else {
            if (delayMillis > 0) {
                try {
                    MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("InterruptedException while applying delay", ie);
                }
            }
            run(command, port);
        }
    }

    public void schedule(Runnable command, boolean synchronous, Delay... delays) {
        long delayMillis = sampleCombinedDelayMillis(delays);
        Integer port = getPort();
        if (this.synchronous || synchronous) {
            if (delayMillis > 0) {
                try {
                    MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("InterruptedException while apply delay to response", ie);
                }
            }
            run(command, port);
        } else {
            if (delayMillis > 0) {
                scheduler.schedule(() -> run(command, port), delayMillis, MILLISECONDS);
            } else {
                run(command, port);
            }
        }
    }

    /**
     * Dispatch a LOCAL (in-JVM) object/class callback so that — in asynchronous (Netty) mode — its
     * potentially-BLOCKING body never runs on the server worker event loop and never consumes the
     * bounded scheduler pool.
     * <p>
     * In asynchronous mode the callback is run on the dedicated, unbounded {@link #localCallbackExecutor}
     * (see its field javadoc): this both moves the blocking loopback off the worker thread (so the
     * loopback's reply can be read on a now-free worker) and guarantees a recursively-triggered inner
     * local callback always gets its own thread, so the only failure mode is a BOUNDED wait, never a
     * pool-exhaustion deadlock. An optional delay is honoured on the shared scheduled executor first
     * (it only occupies a timer thread until it fires), after which the body hops to the cached pool.
     * <p>
     * In synchronous mode (WAR/servlet, or the unit-test {@code synchronous=true} path) the body runs
     * INLINE after any delay, exactly as the equivalent {@link #schedule} call would, so the response is
     * written before the caller returns and blocking-model deployments keep their semantics unchanged.
     * <p>
     * Whatever thread the body ends up on, it runs through {@link #run(Runnable, Integer)} with the
     * captured loop-prevention port restored, so response routing/{@code HttpState.getPort()} behave
     * identically to the existing scheduler paths.
     */
    public void scheduleLocalCallback(Runnable command, boolean synchronous, Delay... delays) {
        long delayMillis = sampleCombinedDelayMillis(delays);
        Integer port = getPort();
        if (this.synchronous || synchronous) {
            if (delayMillis > 0) {
                try {
                    MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("InterruptedException while applying delay to local callback", ie);
                }
            }
            run(command, port);
        } else if (delayMillis > 0) {
            scheduler.schedule(() -> localCallbackExecutor.execute(() -> run(command, port)), delayMillis, MILLISECONDS);
        } else {
            localCallbackExecutor.execute(() -> run(command, port));
        }
    }

    /**
     * Dispatch a template ACTION (RESPONSE_TEMPLATE / FORWARD_TEMPLATE render-and-write) so that — in
     * asynchronous (Netty) mode — its synchronous, CPU-bound render never runs on the server worker event
     * loop and never consumes the bounded shared scheduler pool.
     * <p>
     * In asynchronous mode the body runs on the dedicated, BOUNDED {@link #templateActionExecutor} (see its
     * field javadoc): unlike the ordinary {@link #schedule} path — which runs a zero-delay command inline on
     * the calling worker thread, and a delayed command on the shared scheduler pool — this always moves the
     * whole render off both of those shared resources, so a burst of slow renders can only ever back up
     * against other renders and never stalls unrelated match/forward traffic. This is deliberately the WHOLE
     * action dispatch, not just the render call: offloading only the render and then blocking the calling
     * thread on its result would leave the worker event-loop thread blocked for the render duration and buy
     * nothing. An optional delay is honoured on the shared scheduled executor first (it only occupies a timer
     * thread until it fires), after which the body hops to the bounded template pool — so a templated
     * response with a configured delay still honours that delay exactly.
     * <p>
     * In synchronous mode (WAR/servlet, or the unit-test {@code synchronous=true} path) the body runs INLINE
     * after any delay, exactly as the equivalent {@link #schedule} call would, so the response is written
     * before the caller returns and blocking-model deployments keep their semantics unchanged.
     * <p>
     * Whatever thread the body ends up on, it runs through {@link #run(Runnable, Integer)} with the captured
     * loop-prevention port restored, so response routing/{@code HttpState.getPort()} behave identically to
     * the existing scheduler paths.
     */
    public void scheduleTemplateAction(Runnable command, boolean synchronous, Delay... delays) {
        long delayMillis = sampleCombinedDelayMillis(delays);
        Integer port = getPort();
        if (this.synchronous || synchronous) {
            if (delayMillis > 0) {
                try {
                    MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("InterruptedException while applying delay to template action", ie);
                }
            }
            run(command, port);
        } else if (delayMillis > 0) {
            scheduler.schedule(() -> templateActionExecutor.execute(() -> run(command, port)), delayMillis, MILLISECONDS);
        } else {
            templateActionExecutor.execute(() -> run(command, port));
        }
    }

    private long sampleCombinedDelayMillis(Delay... delays) {
        if (delays == null || delays.length == 0) {
            return 0;
        } else if (delays.length == 1 && delays[0] != null) {
            return delays[0].sampleValueMillis();
        } else if (delays.length == 2 && delays[0] == delays[1]) {
            return delays[0] != null ? delays[0].sampleValueMillis() : 0;
        } else {
            long timeInMilliseconds = 0;
            for (Delay delay : delays) {
                if (delay != null) {
                    timeInMilliseconds = Math.min(Long.MAX_VALUE, timeInMilliseconds + delay.sampleValueMillis());
                }
            }
            return timeInMilliseconds;
        }
    }

    public void submit(Runnable command) {
        submit(command, false);
    }

    public void submit(Runnable command, boolean synchronous) {
        Integer port = getPort();
        if (this.synchronous || synchronous) {
            run(command, port);
        } else {
            scheduler.submit(() -> run(command, port));
        }
    }

    public void submit(HttpForwardActionResult future, Runnable command, boolean synchronous, Predicate<Throwable> logException) {
        Integer port = getPort();
        if (future != null) {
            if (this.synchronous || synchronous) {
                try {
                    future.getHttpResponse().get(configuration.maxSocketTimeoutInMillis(), MILLISECONDS);
                } catch (TimeoutException e) {
                    future.getHttpResponse().completeExceptionally(new SocketCommunicationException("Response was not received after " + configuration.maxSocketTimeoutInMillis() + " milliseconds, to make the proxy wait longer please use \"mockserver.maxSocketTimeout\" system property or configuration.maxSocketTimeout(long milliseconds)", e.getCause()));
                } catch (InterruptedException | ExecutionException ex) {
                    future.getHttpResponse().completeExceptionally(ex);
                }
                run(command, port);
            } else {
                future.getHttpResponse().whenCompleteAsync((httpResponse, throwable) -> {
                    if (throwable != null && mockServerLogger.isEnabledForInstance(Level.INFO) && logException.test(throwable)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(WARN)
                                .setLogLevel(Level.INFO)
                                .setMessageFormat(throwable.getMessage())
                                .setThrowable(throwable)
                        );
                    }
                    run(command, port);
                }, scheduler);
            }
        }
    }

    public void submit(CompletableFuture<BinaryMessage> future, Runnable command, boolean synchronous) {
        Integer port = getPort();
        if (future != null) {
            if (this.synchronous || synchronous) {
                try {
                    future.get(configuration.maxSocketTimeoutInMillis(), MILLISECONDS);
                } catch (TimeoutException e) {
                    future.completeExceptionally(new SocketCommunicationException("Response was not received after " + configuration.maxSocketTimeoutInMillis() + " milliseconds, to make the proxy wait longer please use \"mockserver.maxSocketTimeout\" system property or ConfigurationProperties.maxSocketTimeout(long milliseconds)", e.getCause()));
                } catch (InterruptedException | ExecutionException ex) {
                    future.completeExceptionally(ex);
                }
                run(command, port);
            } else {
                future.whenCompleteAsync((httpResponse, throwable) -> command.run(), scheduler);
            }
        }
    }

    public void submit(HttpForwardActionResult future, BiConsumer<HttpResponse, Throwable> consumer, boolean synchronous) {
        if (future != null) {
            if (this.synchronous || synchronous) {
                HttpResponse httpResponse = null;
                Throwable exception = null;
                try {
                    httpResponse = future.getHttpResponse().get(configuration.maxSocketTimeoutInMillis(), MILLISECONDS);
                } catch (TimeoutException e) {
                    exception = new SocketCommunicationException("Response was not received after " + configuration.maxSocketTimeoutInMillis() + " milliseconds, to make the proxy wait longer please use \"mockserver.maxSocketTimeout\" system property or ConfigurationProperties.maxSocketTimeout(long milliseconds)", e.getCause());
                } catch (InterruptedException | ExecutionException ex) {
                    exception = ex;
                }
                try {
                    consumer.accept(httpResponse, exception);
                } catch (Throwable throwable) {
                    if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(WARN)
                                .setLogLevel(Level.INFO)
                                .setMessageFormat(throwable.getMessage())
                                .setThrowable(throwable)
                        );
                    }
                }
            } else {
                future.getHttpResponse().whenCompleteAsync(consumer, scheduler);
            }
        }
    }

}
