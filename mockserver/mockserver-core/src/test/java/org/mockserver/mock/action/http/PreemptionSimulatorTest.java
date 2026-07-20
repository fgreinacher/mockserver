package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.PreemptionRequest;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * State-machine tests for {@link PreemptionSimulator}: cordon/uncordon, drain-window accounting,
 * TTL dead-man's-switch auto-uncordon, and the hard cap on drain/TTL. Uses a controllable clock and
 * a process-wide singleton, and mutates {@code preemptionSimulationMaxDrainMillis}, so it runs in the
 * sequential Surefire phase.
 */
public class PreemptionSimulatorTest {

    private long originalCap;
    private long originalStopDrainMillis;

    @Before
    public void setUp() {
        originalCap = ConfigurationProperties.preemptionSimulationMaxDrainMillis();
        originalStopDrainMillis = ConfigurationProperties.stopDrainMillis();
        PreemptionSimulator.getInstance().reset();
    }

    @After
    public void tearDown() {
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(originalCap);
        ConfigurationProperties.stopDrainMillis(originalStopDrainMillis);
        PreemptionSimulator.getInstance().reset();
    }

    @Test
    public void shouldCordonAndUncordon() {
        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);
        assertThat(simulator.isCordoned(), is(false));
        assertThat(simulator.state(), is("inactive"));

        simulator.start(PreemptionRequest.preemptionRequest().withDrainMillis(5_000L));

        assertThat(simulator.isCordoned(), is(true));
        assertThat(simulator.state(), is("draining"));
        assertThat(simulator.rejectsNewExchanges(), is(true)); // default mode = both

        simulator.uncordon();
        assertThat(simulator.isCordoned(), is(false));
        assertThat(simulator.state(), is("inactive"));
        assertThat(simulator.getRequest(), is(nullValue()));
    }

    @Test
    public void shouldReportDrainRemainingAndTransitionToDrained() {
        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);
        simulator.start(PreemptionRequest.preemptionRequest().withDrainMillis(5_000L).withTtlMillis(60_000L));

        assertThat(simulator.drainRemainingMillis(), is(5_000L));
        assertThat(simulator.drainDeadlinePassed(), is(false));

        now.addAndGet(2_000L);
        assertThat(simulator.drainRemainingMillis(), is(3_000L));
        assertThat(simulator.state(), is("draining"));

        // advance past the drain deadline but within TTL — drained, still cordoned
        now.addAndGet(4_000L);
        assertThat(simulator.drainRemainingMillis(), is(0L));
        assertThat(simulator.drainDeadlinePassed(), is(true));
        assertThat(simulator.state(), is("drained"));
        assertThat(simulator.isCordoned(), is(true));
    }

    @Test
    public void shouldAutoUncordonAfterTtl() {
        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);
        simulator.start(PreemptionRequest.preemptionRequest().withDrainMillis(1_000L).withTtlMillis(10_000L));
        assertThat(simulator.isCordoned(), is(true));

        // just before TTL
        now.set(1_000L + 9_999L);
        assertThat(simulator.isCordoned(), is(true));

        // at/after TTL — dead-man's switch self-heals
        now.set(1_000L + 10_000L);
        assertThat(simulator.isCordoned(), is(false));
        assertThat(simulator.state(), is("inactive"));
        assertThat(simulator.getRequest(), is(nullValue()));
    }

    @Test
    public void shouldClampDrainAndTtlToHardCap() {
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(5_000L);
        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        PreemptionRequest effective = simulator.start(PreemptionRequest.preemptionRequest()
            .withDrainMillis(1_000_000L)
            .withTtlMillis(2_000_000L));

        assertThat(effective.getDrainMillis(), is(5_000L));
        assertThat(effective.getTtlMillis(), is(5_000L));
        // drain window honours the clamp
        assertThat(simulator.drainRemainingMillis(), is(5_000L));
    }

    @Test
    public void shouldDefaultModeToBothAndReflectGoAwayOnlyMode() {
        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        PreemptionRequest effective = simulator.start(PreemptionRequest.preemptionRequest());
        assertThat(effective.getMode(), is(PreemptionRequest.Mode.both));
        assertThat(simulator.rejectsNewExchanges(), is(true));

        // goaway-only mode does NOT reject new exchanges with a 503 — GOAWAY alone signals drain
        simulator.start(PreemptionRequest.preemptionRequest().withMode(PreemptionRequest.Mode.goaway));
        assertThat(simulator.getMode(), is(PreemptionRequest.Mode.goaway));
        assertThat(simulator.rejectsNewExchanges(), is(false));
    }

    @Test
    public void shouldReportGoAwayModeAndLastStreamId() {
        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        // not cordoned: no GOAWAY, default last-stream-id sentinel
        assertThat(simulator.emitsGoAway(), is(false));
        assertThat(simulator.goAwayLastStreamId(), is(-1L));

        // reject503-only mode does not emit GOAWAY
        simulator.start(PreemptionRequest.preemptionRequest().withMode(PreemptionRequest.Mode.reject503));
        assertThat(simulator.emitsGoAway(), is(false));

        // goaway mode emits GOAWAY; unset lastStreamId yields the -1 sentinel
        simulator.start(PreemptionRequest.preemptionRequest().withMode(PreemptionRequest.Mode.goaway));
        assertThat(simulator.emitsGoAway(), is(true));
        assertThat(simulator.goAwayLastStreamId(), is(-1L));

        // both mode emits GOAWAY and carries an explicit lastStreamId
        simulator.start(PreemptionRequest.preemptionRequest().withMode(PreemptionRequest.Mode.both).withLastStreamId(7L));
        assertThat(simulator.emitsGoAway(), is(true));
        assertThat(simulator.rejectsNewExchanges(), is(true));
        assertThat(simulator.goAwayLastStreamId(), is(7L));

        simulator.uncordon();
        assertThat(simulator.emitsGoAway(), is(false));
        assertThat(simulator.goAwayLastStreamId(), is(-1L));
    }

    @Test
    public void shouldReadInFlightThroughWiredSupplier() {
        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);
        AtomicLong inFlight = new AtomicLong(3L);
        simulator.setInFlightSupplier(() -> (int) inFlight.get());
        assertThat(simulator.inFlight(), is(3));
        inFlight.set(0L);
        assertThat(simulator.inFlight(), is(0));
    }

    // ---------------------------------------------------------------------
    // Configuration-instance precedence: a cap set on a Configuration instance
    // (as PUT /mockserver/configuration does) must actually clamp the drain/TTL,
    // not be silently ignored in favour of the static ConfigurationProperties store.
    // ---------------------------------------------------------------------

    @Test
    public void shouldClampDrainMillisToCapFromConfigurationInstance() {
        // given - a generous static cap but a tight instance cap
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(600_000L);
        Configuration configuration = Configuration.configuration()
            .preemptionSimulationMaxDrainMillis(5_000L);

        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        PreemptionRequest effective = simulator.start(configuration,
            PreemptionRequest.preemptionRequest().withDrainMillis(60_000L));

        assertThat("instance cap must clamp the drain window",
            effective.getDrainMillis(), is(5_000L));
        assertThat(simulator.drainRemainingMillis(), is(5_000L));
    }

    @Test
    public void shouldClampTtlMillisToCapFromConfigurationInstance() {
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(600_000L);
        Configuration configuration = Configuration.configuration()
            .preemptionSimulationMaxDrainMillis(5_000L);

        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        PreemptionRequest effective = simulator.start(configuration,
            PreemptionRequest.preemptionRequest().withDrainMillis(1_000L).withTtlMillis(60_000L));

        assertThat("instance cap must clamp the TTL", effective.getTtlMillis(), is(5_000L));

        // and - the clamped TTL is the one that actually auto-uncordons
        assertThat(simulator.isCordoned(), is(true));
        now.set(1_000L + 5_000L);
        assertThat("cordon must self-heal at the instance-clamped TTL",
            simulator.isCordoned(), is(false));
    }

    @Test
    public void shouldFallBackToStaticCapWhenConfigurationIsNull() {
        // given - no instance supplied, so system-property/env/file users are unaffected
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(5_000L);

        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        PreemptionRequest effective = simulator.start(
            PreemptionRequest.preemptionRequest().withDrainMillis(60_000L));

        assertThat(effective.getDrainMillis(), is(5_000L));
    }

    // ---------------------------------------------------------------------
    // stopDrainMillis is the DEFAULT drain budget when the request omits
    // drainMillis. A value set on a Configuration instance (as
    // PUT /mockserver/configuration does) must supply that default, not be
    // silently ignored in favour of the static ConfigurationProperties store.
    // ---------------------------------------------------------------------

    @Test
    public void shouldDefaultDrainMillisToStopDrainMillisFromConfigurationInstance() {
        // given - a generous cap so nothing is clamped, and a static store value that DIFFERS from
        // the instance value so the two paths cannot be confused
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(600_000L);
        ConfigurationProperties.stopDrainMillis(30_000L);
        Configuration configuration = Configuration.configuration()
            .stopDrainMillis(3_000L);

        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        // when - the request omits drainMillis, so the default must be resolved from configuration
        PreemptionRequest effective = simulator.start(configuration, PreemptionRequest.preemptionRequest());

        // then - the INSTANCE value supplies the default, not the static store's 30_000
        assertThat("instance stopDrainMillis must supply the default drain budget",
            effective.getDrainMillis(), is(3_000L));

        // and - it is the value that actually bounds the drain window
        assertThat(simulator.drainRemainingMillis(), is(3_000L));
        now.set(1_000L + 3_000L);
        assertThat("drain must end at the instance-supplied budget",
            simulator.drainRemainingMillis(), is(0L));
    }

    @Test
    public void shouldDefaultDrainMillisToZeroWhenConfigurationInstanceDisablesDraining() {
        // given - draining disabled on the instance while the static store still has a long budget,
        // proving the instance value is genuinely read rather than merely being the smaller number
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(600_000L);
        ConfigurationProperties.stopDrainMillis(30_000L);
        Configuration configuration = Configuration.configuration()
            .stopDrainMillis(0L);

        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        PreemptionRequest effective = simulator.start(configuration, PreemptionRequest.preemptionRequest());

        assertThat("instance stopDrainMillis of 0 must disable the default drain",
            effective.getDrainMillis(), is(0L));
    }

    @Test
    public void shouldClampNegativeStopDrainMillisFromConfigurationInstanceToZero() {
        // given - a negative instance value; Configuration.stopDrainMillis() mirrors the clamp
        // ConfigurationProperties.stopDrainMillis() applies, so it can never be read back negative
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(600_000L);
        ConfigurationProperties.stopDrainMillis(30_000L);
        Configuration configuration = Configuration.configuration()
            .stopDrainMillis(-5_000L);

        assertThat("Configuration must clamp a negative drain budget to zero",
            configuration.stopDrainMillis(), is(0L));

        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        PreemptionRequest effective = simulator.start(configuration, PreemptionRequest.preemptionRequest());

        assertThat(effective.getDrainMillis(), is(0L));
    }

    @Test
    public void shouldFallBackToStaticStopDrainMillisWhenConfigurationIsNull() {
        // given - no instance supplied, so system-property/env/file users are unaffected
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(600_000L);
        ConfigurationProperties.stopDrainMillis(7_000L);

        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        // when - the request omits drainMillis and there is no Configuration instance
        PreemptionRequest effective = simulator.start(PreemptionRequest.preemptionRequest());

        // then - the static store still supplies the default
        assertThat("static store must still supply the default when no Configuration is given",
            effective.getDrainMillis(), is(7_000L));
    }

    @Test
    public void shouldFallBackToStaticStopDrainMillisWhenConfigurationLeavesItUnset() {
        // given - a Configuration instance that does NOT set stopDrainMillis; the getter must fall
        // through to the static store rather than returning a hard-coded default
        ConfigurationProperties.preemptionSimulationMaxDrainMillis(600_000L);
        ConfigurationProperties.stopDrainMillis(7_000L);
        Configuration configuration = Configuration.configuration();

        AtomicLong now = new AtomicLong(1_000L);
        PreemptionSimulator simulator = new PreemptionSimulator(now::get);

        PreemptionRequest effective = simulator.start(configuration, PreemptionRequest.preemptionRequest());

        assertThat(effective.getDrainMillis(), is(7_000L));
    }
}
