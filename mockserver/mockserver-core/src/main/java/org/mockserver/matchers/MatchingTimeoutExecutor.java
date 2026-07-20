package org.mockserver.matchers;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.slf4j.event.Level.WARN;

/**
 * Shared daemon-thread executor used by request matchers (regex, XPath) to
 * bound the runtime of pathological user-supplied expressions. Wrapping each
 * match in a Future-with-timeout protects MockServer from ReDoS / XPath DoS
 * attacks where a single malicious expectation or input would otherwise pin a
 * Netty worker thread.
 * <p>
 * The pool is multi-threaded (not single-thread) so concurrent matches do not serialize,
 * and daemon-flagged so it never blocks JVM shutdown.
 * <p>
 * The pool is <em>bounded</em> rather than unbounded-cached: an unbounded cached pool would spawn
 * one thread per concurrent match, so a burst of slow (ReDoS) patterns under load could create
 * thousands of threads and exhaust memory — turning the DoS protection into a DoS amplifier. The
 * bounded pool caps live evaluator threads at a generous {@code max(64, availableProcessors * 16)};
 * a {@link SynchronousQueue} means there is no work backlog, so a task either gets a thread
 * immediately (creating one up to the cap) or — only under extreme saturation beyond the cap — is
 * <em>rejected</em>.
 * <p>
 * <strong>Rejection must never corrupt a match result.</strong> A rejected submission is NOT
 * treated as a timeout (which would turn a would-be match into a silent non-match). Instead the
 * task is run <em>inline</em> on the calling thread and its real result returned. This deliberately
 * sacrifices the per-call timeout / DoS-isolation guarantee for that one call (the inline regex
 * could in theory pin the calling thread), but only when more than {@code max(64, cores*16)}
 * evaluations are already in flight — a regime where correctness of the result is far more
 * important than timeout isolation for a mock server. The generous cap makes this path effectively
 * unreachable under realistic concurrency; when it does fire it logs a distinct WARN so operators
 * can detect saturation.
 */
public final class MatchingTimeoutExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MatchingTimeoutExecutor.class);

    // Generous cap: high enough that rejection is effectively unreachable under realistic concurrent
    // load (so a would-be match is never lost to saturation), but still bounded so a ReDoS burst
    // cannot spawn unlimited threads. Core is 0 with a 60s keep-alive so idle evaluator threads reap.
    private static final int MAX_POOL_SIZE = Math.max(64, Runtime.getRuntime().availableProcessors() * 16);

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
        0,
        MAX_POOL_SIZE,
        60L,
        TimeUnit.SECONDS,
        // SynchronousQueue: no backlog — a task either gets a thread immediately (creating one up to
        // MAX_POOL_SIZE) or is rejected. We prefer direct hand-off with a large max over a sizeable
        // queue because any queueing time counts against future.get's timeout budget, distorting the
        // timeout. On rejection we run inline (never the unsafe non-match sentinel) — see callWithTimeout.
        new SynchronousQueue<>(),
        new ThreadFactoryBuilder()
            .setNameFormat("mockserver-match-eval-%d")
            .setDaemon(true)
            .build(),
        // AbortPolicy: throw RejectedExecutionException on saturation so the caller can run the task
        // inline (preserving the real match result) instead of blocking or corrupting the result.
        new ThreadPoolExecutor.AbortPolicy()
    );

    /**
     * Count of tasks actually submitted to {@link #EXECUTOR} (i.e. not skipped via a literal
     * short-circuit and not rejected on saturation). Exposed for tests to assert that the fast
     * literal path does not touch the pool, without depending on {@link ThreadPoolExecutor}'s
     * documented-as-approximate internal counters.
     */
    private static final AtomicLong SUBMITTED_TASK_COUNT = new AtomicLong();

    private MatchingTimeoutExecutor() {
    }

    /**
     * @return the number of tasks successfully submitted to the shared executor pool. Visible for
     * testing the literal short-circuit; not part of the public timeout contract.
     */
    static long submittedTaskCount() {
        return SUBMITTED_TASK_COUNT.get();
    }

    /**
     * Run a matching task with a millisecond timeout. A non-positive timeout
     * disables the timeout and runs the task on the calling thread (preserving
     * pre-timeout behaviour for users who opt out).
     *
     * @return the task's result, or {@code onTimeout} when the timeout fires
     * @throws Exception any checked exception thrown by the task (other than TimeoutException)
     */
    public static <T> T callWithTimeout(Callable<T> task, long timeoutMillis, T onTimeout, OnTimeout onTimeoutCallback) throws Exception {
        if (timeoutMillis <= 0) {
            return task.call();
        }
        // Wrap the user task so that any residual interrupt flag left on a recycled pool
        // thread (e.g. from a previous timed-out task whose long-running work ignored
        // future.cancel(true)) is cleared before this task starts. Without this, the
        // recycled worker can carry an interrupt flag that causes the next task to
        // observe spurious failure or incorrect results.
        Callable<T> cleanTask = () -> {
            Thread.interrupted();
            return task.call();
        };
        final Future<T> future;
        try {
            future = EXECUTOR.submit(cleanTask);
            SUBMITTED_TASK_COUNT.incrementAndGet();
        } catch (RejectedExecutionException ree) {
            // Extreme saturation: more than MAX_POOL_SIZE (max(64, cores*16)) evaluations already in
            // flight, and the SynchronousQueue has no backlog. We must NOT treat this like a timeout —
            // doing so would silently turn a would-be match into a non-match (an intermittent
            // correctness regression under legitimate concurrent load). Instead run the task inline on
            // the calling thread and return its REAL result. This sacrifices the per-call timeout /
            // DoS-isolation guarantee for this one call only (the inline regex could pin this thread),
            // which is the correct trade for a mock server: never corrupt a match result. This path is
            // effectively unreachable under realistic concurrency given the generous cap.
            LOGGER.warn("match evaluation pool saturated ({} threads in use); ran match inline without timeout isolation — raise mockserver.regexMatchingTimeoutMillis headroom or reduce concurrent matching load if this recurs", MAX_POOL_SIZE);
            return callInline(cleanTask);
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            if (onTimeoutCallback != null) {
                onTimeoutCallback.fired(timeoutMillis);
            }
            return onTimeout;
        } catch (InterruptedException ie) {
            // restore the caller-thread interrupt flag so cooperative shutdown is not lost,
            // and return the safe sentinel rather than propagating an unrelated exception type.
            future.cancel(true);
            Thread.currentThread().interrupt();
            return onTimeout;
        } catch (ExecutionException ee) {
            // unwrap and rethrow the actual user-thrown exception (e.g. PatternSyntaxException)
            // so callers can catch it precisely.
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw ee;
        }
    }

    /**
     * Run the (already interrupt-clearing) task on the calling thread when the pool rejects it.
     * Mirrors the normal path's exception contract: a checked Exception thrown by the task — notably
     * {@link java.util.regex.PatternSyntaxException} — propagates so callers can catch it precisely,
     * and any residual interrupt flag is cleared afterwards (as {@code cleanTask} does up front) so we
     * never leave the calling thread spuriously interrupted.
     */
    private static <T> T callInline(Callable<T> cleanTask) throws Exception {
        try {
            return cleanTask.call();
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Evaluate a user-supplied regular expression under the shared
     * {@code mockserver.regexMatchingTimeoutMillis} timeout, so a pathological (ReDoS) pattern cannot
     * pin a worker thread. A timeout or any error is treated as a non-match (returns {@code false}) and,
     * when a timeout fires and a logger is supplied, logs a WARN naming the pattern.
     *
     * @param mockServerLogger logger for the timeout warning (may be null)
     * @param description       short label for the log (e.g. "graphql operationName")
     * @param pattern           the compiled user regex (used only for the log message)
     * @param matchOperation    the actual match call (e.g. {@code () -> pattern.matcher(input).matches()})
     * @return the match result, or {@code false} on timeout/error
     */
    public static boolean matchesWithRegexTimeout(MockServerLogger mockServerLogger, String description, Pattern pattern, Callable<Boolean> matchOperation) {
        // Prefer the live Configuration carried by the logger (the matcher graph's existing
        // configuration carrier) so a timeout set over PUT /mockserver/configuration takes effect;
        // fall back to the static store when no instance is available.
        org.mockserver.configuration.Configuration configuration =
            mockServerLogger != null ? mockServerLogger.getConfiguration() : null;
        long timeoutMillis = configuration != null
            ? configuration.regexMatchingTimeoutMillis()
            : ConfigurationProperties.regexMatchingTimeoutMillis();
        try {
            return callWithTimeout(
                matchOperation,
                timeoutMillis,
                Boolean.FALSE,
                fired -> {
                    if (mockServerLogger != null) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(WARN)
                                .setMessageFormat(description + " regex evaluation timed out after {}ms for pattern:{}— treating as non-match (raise mockserver.regexMatchingTimeoutMillis or simplify the pattern to suppress this)")
                                .setArguments(fired, pattern.pattern())
                        );
                    } else {
                        // callers without a MockServerLogger still get an observable trace of the timeout
                        LOGGER.warn("{} regex evaluation timed out after {}ms for pattern:{} — treating as non-match (raise mockserver.regexMatchingTimeoutMillis or simplify the pattern to suppress this)", description, fired, pattern.pattern());
                    }
                });
        } catch (Exception e) {
            return false;
        }
    }

    @FunctionalInterface
    public interface OnTimeout {
        void fired(long timeoutMillis);
    }
}
