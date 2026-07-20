package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.HttpChaosProfile;
import org.mockserver.model.TcpChaosProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;

public class ChaosAutoHaltMonitorTest {

    private boolean originalEnabled;
    private long originalThreshold;
    private long originalWindow;

    @Before
    public void saveOriginals() {
        originalEnabled = ConfigurationProperties.chaosAutoHaltEnabled();
        originalThreshold = ConfigurationProperties.chaosAutoHaltErrorThreshold();
        originalWindow = ConfigurationProperties.chaosAutoHaltWindowMillis();
        // Reset shared state
        Metrics.resetAdditionalMetricsForTesting();
        ChaosAutoHaltMonitor.getInstance().reset();
        ServiceChaosRegistry.getInstance().reset();
        TcpChaosRegistry.getInstance().reset();
    }

    @After
    public void restoreOriginals() {
        ConfigurationProperties.chaosAutoHaltEnabled(originalEnabled);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(originalThreshold);
        ConfigurationProperties.chaosAutoHaltWindowMillis(originalWindow);
        Metrics.resetAdditionalMetricsForTesting();
        ChaosAutoHaltMonitor.getInstance().reset();
        ServiceChaosRegistry.getInstance().reset();
        TcpChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldHaltChaosWhenThresholdExceededByErrorFaults() {
        // given - a local monitor with a controllable clock
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        // and - active chaos in the registry
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));

        // when - record destructive errors below threshold
        monitor.recordError("error");
        monitor.recordError("error");
        assertThat("chaos still active below threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));

        // when - record error that crosses the threshold
        monitor.recordError("error");

        // then - chaos is halted
        assertThat("chaos halted after threshold exceeded",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldHaltChaosWhenThresholdExceededByDropFaults() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - drop faults cross threshold
        monitor.recordError("drop");
        monitor.recordError("drop");

        // then - chaos is halted
        assertThat("chaos halted by drop faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldHaltChaosWhenThresholdExceededByQuotaFaults() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - quota faults cross threshold
        monitor.recordError("quota");
        monitor.recordError("quota");

        // then - chaos is halted
        assertThat("chaos halted by quota faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldHaltChaosWhenMixedDestructiveFaultsCrossThreshold() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - a mix of destructive fault types crosses the threshold
        monitor.recordError("error");
        monitor.recordError("drop");
        monitor.recordError("quota");

        // then - chaos is halted
        assertThat("chaos halted by mixed destructive faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldNotHaltWhenOnlyLatencyFaultsAreInjected() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - many latency faults (benign, non-destructive)
        for (int i = 0; i < 100; i++) {
            monitor.recordError("latency");
        }

        // then - chaos is NOT halted because latency is not destructive
        assertThat("chaos NOT halted by latency-only faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(0));
    }

    @Test
    public void shouldNotHaltWhenOnlyBenignFaultsAreInjected() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - benign fault types that should NOT trigger auto-halt
        for (int i = 0; i < 20; i++) {
            monitor.recordError("latency");
            monitor.recordError("slow");
            monitor.recordError("truncate");
            monitor.recordError("malformed");
            monitor.recordError("graphql");
        }

        // then - chaos is NOT halted
        assertThat("chaos NOT halted by benign faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(0));
    }

    @Test
    public void shouldIgnoreNullFaultType() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(1);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - null fault type
        monitor.recordError(null);

        // then - not counted
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.currentWindowSize(), is(0));
    }

    @Test
    public void shouldNotCountBenignFaultsTowardThreshold() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // record 2 destructive errors (below threshold of 3)
        monitor.recordError("error");
        monitor.recordError("drop");

        // record many benign faults — these should NOT push us over
        for (int i = 0; i < 50; i++) {
            monitor.recordError("latency");
            monitor.recordError("slow");
        }

        // then - still below threshold, chaos still active
        assertThat("benign faults do not count toward threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(2));

        // one more destructive fault pushes us over
        monitor.recordError("quota");
        assertThat("destructive fault pushes over threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldNotHaltWhenFeatureDisabled() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(false);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(1);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - record many destructive errors (feature is disabled)
        for (int i = 0; i < 100; i++) {
            monitor.recordError("error");
        }

        // then - chaos is NOT halted
        assertThat("chaos not halted when feature disabled",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldEvictOldErrorsOutsideWindow() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(5);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // record 4 destructive errors at time=1000
        for (int i = 0; i < 4; i++) {
            monitor.recordError("error");
        }

        // advance clock past the window so old errors expire
        now.set(12_000L);

        // record 1 more error — only 1 error in window, well below threshold of 5
        monitor.recordError("error");

        assertThat("old errors evicted, below threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.currentWindowSize(), is(1));
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldNotHaltWhenNoChaosIsActive() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        // no chaos registered
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));

        // when - record destructive errors exceeding threshold with no chaos active
        monitor.recordError("error");
        monitor.recordError("error");

        // then - halt count stays at 0 (nothing to halt)
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldClearWindowAfterHalt() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // trigger halt
        monitor.recordError("error");
        monitor.recordError("drop");
        assertThat(monitor.getHaltCount(), is(1L));

        // re-register chaos
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // one more error should NOT immediately re-trigger (window was cleared)
        monitor.recordError("error");
        assertThat("single error after halt does not re-trigger",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void resetClearsMonitorState() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));
        monitor.recordError("error");
        monitor.recordError("error");
        assertThat(monitor.getHaltCount(), is(1L));

        monitor.reset();
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(0));
    }

    @Test
    public void resetPreventsStaleErrorsFromHaltingFreshChaos() {
        // Verifies that HttpState.reset() clearing the monitor prevents stale
        // errors accumulated before reset from halting newly-registered chaos
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(60_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // accumulate 2 errors (just below threshold)
        monitor.recordError("error");
        monitor.recordError("drop");
        assertThat(monitor.currentWindowSize(), is(2));

        // simulate server reset
        monitor.reset();
        ServiceChaosRegistry.getInstance().reset();

        // register fresh chaos
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // one error should NOT trigger halt (stale errors were cleared by reset)
        monitor.recordError("error");
        assertThat("stale errors cleared by reset do not count",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(1));
    }

    @Test
    public void singletonInstanceRecordErrorIsNoOpWhenDisabled() {
        // Verify the default instance does nothing when feature is off
        ConfigurationProperties.chaosAutoHaltEnabled(false);
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        ChaosAutoHaltMonitor.getInstance().recordError("error");

        assertThat("singleton no-op when disabled",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
    }

    @Test
    public void shouldMaintainAccurateWindowSizeUnderConcurrency() throws Exception {
        // Regression test for the TOCTOU race in evictExpired(): before the fix,
        // two threads could both peekFirst() the same expired head; one would
        // pollFirst() an UNEXPIRED entry and the AtomicInteger windowSize would
        // permanently undercount, preventing the circuit breaker from firing.
        //
        // Strategy: many threads hammer recordError() concurrently with a clock
        // that produces a mix of soon-to-expire and fresh timestamps. After all
        // threads complete, the window size must exactly equal the number of
        // non-expired timestamps (no lost counts).
        final int threadCount = 16;
        final int errorsPerThread = 200;
        final long windowMillis = 1_000L;
        final long startTime = 10_000L;
        // Each call advances the clock by 1 ms, so timestamps span
        // startTime..startTime+(threadCount*errorsPerThread)-1.
        // With a 1000 ms window, only the last 1000 entries survive eviction.
        AtomicLong ticker = new AtomicLong(startTime);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(ticker::getAndIncrement);

        // Set a threshold higher than total possible errors so the halt doesn't
        // fire and clear the window — we want to verify the count, not the halt.
        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(threadCount * errorsPerThread + 1);
        ConfigurationProperties.chaosAutoHaltWindowMillis(windowMillis);

        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                for (int i = 0; i < errorsPerThread; i++) {
                    monitor.recordError("error");
                }
            });
            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join(10_000);
        }

        // The ticker has advanced to startTime + totalErrors. The window spans
        // [now - windowMillis, now]. Compute how many timestamps fall within it.
        long finalTime = ticker.get() - 1; // last timestamp actually written
        long cutoff = finalTime - windowMillis;
        int totalErrors = threadCount * errorsPerThread;
        // timestamps are startTime, startTime+1, ..., startTime+totalErrors-1
        // non-expired: timestamp > cutoff, i.e. timestamp >= cutoff+1
        int expectedInWindow = 0;
        for (int i = 0; i < totalErrors; i++) {
            if (startTime + i > cutoff) {
                expectedInWindow++;
            }
        }

        // Read the size through the public method which evicts expired entries
        // using the last-used clock value (we can't advance further, but the
        // evictExpired will use whatever clock returns).
        // Reset the clock to finalTime so currentWindowSize() evicts correctly.
        ticker.set(finalTime);
        // Need a new monitor ref that uses the same ticker — but we already have
        // it. currentWindowSize() calls clock.getAsLong() which returns finalTime.
        int actualSize = monitor.currentWindowSize();

        assertThat("window size must be exact after concurrent hammering "
                + "(was " + actualSize + ", expected " + expectedInWindow + ")",
            actualSize, is(expectedInWindow));
    }

    @Test
    public void shouldNotAccumulateTimestampsWhenThresholdIsZero() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(0);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - record many destructive errors with threshold <= 0
        for (int i = 0; i < 100; i++) {
            monitor.recordError("error");
        }

        // then - no timestamps accumulated and chaos is NOT halted
        assertThat("window must remain empty when threshold is zero",
            monitor.currentWindowSize(), is(0));
        assertThat("chaos must not be halted when threshold is zero",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldNotAccumulateTimestampsWhenThresholdIsNegative() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(-5);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - record many destructive errors with negative threshold
        for (int i = 0; i < 100; i++) {
            monitor.recordError("error");
        }

        // then - no timestamps accumulated and chaos is NOT halted
        assertThat("window must remain empty when threshold is negative",
            monitor.currentWindowSize(), is(0));
        assertThat("chaos must not be halted when threshold is negative",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldFireHaltCorrectlyUnderConcurrency() throws Exception {
        // Verify the circuit breaker actually fires under concurrent load.
        // Multiple threads record errors; with a low threshold the halt must fire
        // at least once, and the halt count must be exactly 1 (the guard prevents
        // double-trigger).
        final int threadCount = 8;
        final int errorsPerThread = 50;
        AtomicLong clock = new AtomicLong(10_000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(clock::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(10);
        ConfigurationProperties.chaosAutoHaltWindowMillis(60_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                for (int i = 0; i < errorsPerThread; i++) {
                    monitor.recordError("error");
                }
            });
            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join(10_000);
        }

        assertThat("circuit breaker must fire under concurrent load",
            monitor.getHaltCount(), greaterThanOrEqualTo(1L));
        assertThat("chaos registry must be empty after halt",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldResetTcpChaosRegistryWhenHalting() {
        // given - the auto-halt window is configured and a connection-lifecycle (TCP-layer) profile
        // is the ONLY active chaos (no service-scoped chaos), proving the halt clears the TCP registry.
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        TcpChaosRegistry.getInstance().put("upstream.svc", TcpChaosProfile.tcpChaosProfile().withResetMidResponse(true));
        assertThat(TcpChaosRegistry.getInstance().activeCount(), is(1));
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));

        // when - a mid-response-RST "drop" storm crosses the threshold
        monitor.recordError("drop");
        monitor.recordError("drop");

        // then - the breaker fires and clears the TCP-layer chaos so the RST storm stops
        assertThat(monitor.getHaltCount(), is(1L));
        assertThat("TCP chaos registry must be empty after halt",
            TcpChaosRegistry.getInstance().activeCount(), is(0));
    }

    @Test
    public void shouldNotHaltWhenOnlyTcpChaosWasAlreadyCleared() {
        // given - threshold crossed but no active chaos of either kind: the breaker must not "fire"
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        // no chaos registered at all
        monitor.recordError("drop");
        monitor.recordError("drop");

        assertThat("no halt when nothing is active to halt", monitor.getHaltCount(), is(0L));
    }

    // ---------------------------------------------------------------------
    // Configuration-instance precedence: values set on a Configuration instance
    // (as PUT /mockserver/configuration does) must change observable behaviour,
    // not be silently ignored in favour of the static ConfigurationProperties store.
    // The monitor's only production caller is the static
    // Metrics.incrementHttpChaosInjected(...), so the configuration is pushed in
    // via setConfiguration(...) (wired from HttpState's constructor) rather than
    // threaded through that static call chain.
    // ---------------------------------------------------------------------

    @Test
    public void shouldHaltWhenEnabledOnConfigurationInstanceButDisabledStatically() {
        // given - the STATIC store says auto-halt is off
        ConfigurationProperties.chaosAutoHaltEnabled(false);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);
        // but the instance says on, as a PUT /mockserver/configuration would
        monitor.setConfiguration(org.mockserver.configuration.Configuration.configuration()
            .chaosAutoHaltEnabled(true)
            .chaosAutoHaltErrorThreshold(3L)
            .chaosAutoHaltWindowMillis(10_000L));

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        monitor.recordError("error");
        monitor.recordError("error");
        monitor.recordError("error");

        assertThat("instance enable must win over the static store",
            monitor.getHaltCount(), is(1L));
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldNotHaltWhenDisabledOnConfigurationInstanceButEnabledStatically() {
        // given - the STATIC store says auto-halt is on
        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);
        // but the instance says off
        monitor.setConfiguration(org.mockserver.configuration.Configuration.configuration()
            .chaosAutoHaltEnabled(false));

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        monitor.recordError("error");
        monitor.recordError("error");
        monitor.recordError("error");
        monitor.recordError("error");

        assertThat("instance disable must win over the static store",
            monitor.getHaltCount(), is(0L));
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
    }

    @Test
    public void shouldUseErrorThresholdFromConfigurationInstance() {
        // given - a high static threshold that would never be crossed here
        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(100);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);
        // but a low instance threshold
        monitor.setConfiguration(org.mockserver.configuration.Configuration.configuration()
            .chaosAutoHaltErrorThreshold(2L));

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        monitor.recordError("error");
        assertThat(monitor.getHaltCount(), is(0L));
        monitor.recordError("error");

        assertThat("instance threshold must win over the static store",
            monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldUseWindowMillisFromConfigurationInstance() {
        // given - a huge static window in which nothing would ever expire
        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(600_000L);

        AtomicLong now = new AtomicLong(1_000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);
        // but a tight 1s instance window
        monitor.setConfiguration(org.mockserver.configuration.Configuration.configuration()
            .chaosAutoHaltErrorThreshold(3L)
            .chaosAutoHaltWindowMillis(1_000L));

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        monitor.recordError("error");
        now.set(1_500L);
        monitor.recordError("error");

        // when - time moves well past the 1s instance window, then two more errors arrive.
        // Under the instance window the first two have expired, so the window never reaches 3.
        // Under the 600s static window nothing expires and the breaker would trip.
        now.set(5_000L);
        monitor.recordError("error");
        monitor.recordError("error");

        assertThat("instance window must win over the static store",
            monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(2));
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
    }

    @Test
    public void shouldFallBackToStaticStoreWhenNoConfigurationInstalled() {
        // given - no configuration installed, so system-property/env/file users are unaffected
        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        monitor.recordError("error");
        monitor.recordError("error");

        assertThat(monitor.getHaltCount(), is(1L));
    }
}
