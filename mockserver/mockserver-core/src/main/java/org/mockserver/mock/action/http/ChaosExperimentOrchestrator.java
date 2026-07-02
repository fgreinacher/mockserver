package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpChaosProfile;
import org.mockserver.model.TcpChaosProfile;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpChaosProfileDTO;
import org.mockserver.serialization.model.SloCriteriaDTO;
import org.mockserver.serialization.model.TcpChaosProfileDTO;
import org.mockserver.slo.SloCriteria;
import org.mockserver.slo.SloEvaluator;
import org.mockserver.slo.SloVerdict;
import org.mockserver.slo.SloWindow;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Orchestrates scheduled multi-stage chaos experiments. An experiment is an
 * ordered sequence of stages, each applying a service-scoped chaos profile to
 * one or more hosts for a specified duration, progressing automatically. When
 * the last stage completes, chaos is cleared (or the experiment loops if
 * configured).
 *
 * <p><b>C1 auto-halt integration:</b> If {@link ChaosAutoHaltMonitor} halts
 * chaos mid-experiment (by calling {@link ServiceChaosRegistry#reset()}), the
 * orchestrator detects the empty registry at the next stage advance and stops
 * the experiment. An experiment stopped by auto-halt is reported with status
 * {@code "halted_by_auto_halt"}.
 *
 * <p><b>Safety limits:</b>
 * <ul>
 *   <li>Maximum {@value #MAX_STAGES} stages per experiment</li>
 *   <li>Maximum stage duration: {@value #MAX_STAGE_DURATION_MILLIS} ms (24 hours)</li>
 *   <li>Only one experiment may be active at a time</li>
 *   <li>Stopping an experiment is idempotent</li>
 * </ul>
 *
 * <p>The orchestrator uses a single-thread {@link ScheduledExecutorService}
 * for non-blocking stage advancement. It never blocks the Netty event loop.
 * Time is measured via a pluggable {@link LongSupplier} clock (defaults to
 * {@link TimeService#currentTimeMillis()}) so tests can drive advancement
 * deterministically without wall-clock sleeps.
 *
 * <p><b>Shared-registry exclusivity:</b> A running experiment takes exclusive ownership
 * of {@link ServiceChaosRegistry}. Manual service-chaos registrations are overwritten
 * at the next stage advance (which calls {@code registry.reset()} then re-applies the
 * stage profiles). A manual {@code reset()} of the registry is detected as an auto-halt
 * condition at the next stage boundary (see the {@code entries().isEmpty()} check in
 * {@link #advanceStage(RunningExperiment)}). Users should stop the experiment before
 * making manual service-chaos changes.
 *
 * <p>The singleton instance is shared process-wide, consistent with
 * {@link ServiceChaosRegistry}'s singleton pattern.
 */
public class ChaosExperimentOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(ChaosExperimentOrchestrator.class);

    /** Maximum number of stages in an experiment definition (DoS bound). */
    static final int MAX_STAGES = 50;
    /** Maximum duration for a single stage: 24 hours in milliseconds. */
    static final long MAX_STAGE_DURATION_MILLIS = 24L * 60 * 60 * 1000;
    /** Maximum deferred-start delay: 7 days in milliseconds (runaway-timer bound). */
    static final long MAX_START_DELAY_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** Interval between live SLO-breach probes for an experiment with {@code sloCriteria}. */
    static final long SLO_PROBE_INTERVAL_MILLIS = 1000L;
    /** Maximum number of terminated-experiment records retained in the bounded history ring. */
    public static final int MAX_HISTORY = 50;

    private static final ChaosExperimentOrchestrator INSTANCE = new ChaosExperimentOrchestrator(
        TimeService::currentTimeMillis,
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chaos-experiment-scheduler");
            t.setDaemon(true);
            return t;
        }),
        new SloEvaluator()
    );

    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    private final SloEvaluator sloEvaluator;
    private final AtomicReference<RunningExperiment> current = new AtomicReference<>(null);
    /** Terminal status of the last experiment after it detaches from {@link #current}.
     *  Allows {@link #getStatus()} to report {@code halted_by_auto_halt}, {@code completed},
     *  {@code halted_by_slo_breach}, or {@code stopped} for a short window after the
     *  experiment ends (instead of null). */
    private volatile String lastTerminatedStatus;
    /** Terminal SLO verdict of the last experiment after it detaches from {@link #current}.
     *  {@code null} when the last experiment had no {@code sloCriteria}. */
    private volatile SloVerdict lastTerminatedVerdict;
    /** Bounded LRU ring of terminated-experiment records (oldest evicted first), exposed at
     *  {@code GET /chaosExperiment/history}. Every terminal transition (completed / stopped /
     *  halted_by_auto_halt / halted_by_slo_breach / aborted_baseline_unhealthy) appends one
     *  entry; each recurring run appends its own. Follows the {@code DriftStore} LRU-ring pattern. */
    private final Deque<ExperimentHistoryEntry> history = new ArrayDeque<>();
    private final Object historyLock = new Object();

    ChaosExperimentOrchestrator(LongSupplier clock, ScheduledExecutorService scheduler) {
        this(clock, scheduler, new SloEvaluator());
    }

    ChaosExperimentOrchestrator(LongSupplier clock, ScheduledExecutorService scheduler, SloEvaluator sloEvaluator) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.sloEvaluator = sloEvaluator;
    }

    public static ChaosExperimentOrchestrator getInstance() {
        return INSTANCE;
    }

    /**
     * Starts an experiment. Returns a validation error message if the definition
     * is invalid, or {@code null} on success. Only one experiment may be active
     * at a time; starting a new one while one is running stops the previous one.
     */
    public String start(ExperimentDefinition definition) {
        // Validate
        String error = validate(definition);
        if (error != null) {
            return error;
        }

        // Steady-state baseline pre-check: before disturbing anything (including a
        // currently-running experiment), assert the experiment's sloCriteria over the
        // pre-experiment lookback window. If the steady state does not already hold,
        // refuse to start so a pre-existing problem is not mistaken for the experiment's
        // effect. Reported as terminal status "aborted_baseline_unhealthy" (queryable via
        // GET /chaosExperiment and recorded in history), and returned to the caller.
        if (definition.baselineWindowMillis > 0 && definition.sloCriteria != null) {
            long baselineNow = clock.getAsLong();
            SloVerdict baseline = evaluateBaseline(definition, baselineNow);
            if (baseline == null || baseline.getResult() != SloVerdict.Result.PASS) {
                SloVerdict.Result result = baseline != null ? baseline.getResult() : null;
                lastTerminatedStatus = "aborted_baseline_unhealthy";
                lastTerminatedVerdict = baseline;
                recordHistory(definition.name, "aborted_baseline_unhealthy", baseline, baselineNow);
                LOG.warn("chaos experiment '{}' refused to start: steady-state baseline unhealthy "
                    + "over {} ms window (verdict {})", definition.name, definition.baselineWindowMillis, result);
                return "aborted_baseline_unhealthy: steady-state SLO did not hold over the "
                    + definition.baselineWindowMillis + " ms baseline window (verdict " + result + ")";
            }
        }

        // Stop any existing experiment
        stopInternal(false);

        long now = clock.getAsLong();
        RunningExperiment experiment = new RunningExperiment(definition, now);
        current.set(experiment);

        long startDelay = resolveStartDelayMillis(definition, now);
        if (startDelay > 0) {
            // Deferred start: stage 0 is not applied until the delay elapses.
            // The registry is left untouched until then (back-compat: no schedule = immediate).
            experiment.status = "scheduled";
            scheduleStart(experiment, startDelay);
            LOG.info("chaos experiment '{}' scheduled to start in {} ms ({} stage(s), loop={})",
                definition.name, startDelay, definition.stages.size(), definition.loop);
        } else {
            beginExperiment(experiment);
        }

        return null;
    }

    /**
     * Applies stage 0 and schedules the first stage advance. Shared by the
     * immediate-start path and the deferred-start timer.
     */
    private void beginExperiment(RunningExperiment experiment) {
        // Apply stage 0
        applyStage(experiment, 0);

        // Schedule advancement to stage 1 (if there are more stages)
        scheduleNextStage(experiment, 0);

        // A2: for an experiment with sloCriteria, probe the live SLO periodically so a
        // breach halts the experiment between stage boundaries (not only at advance).
        scheduleSloProbe(experiment);

        LOG.info("chaos experiment '{}' started with {} stage(s), loop={}",
            experiment.definition.name, experiment.definition.stages.size(), experiment.definition.loop);
    }

    /**
     * Computes the delay (in ms) before stage 0 should be applied, from the
     * definition's {@code startDelayMillis} and/or {@code cronSchedule}. Returns
     * {@code 0} (immediate start) when neither is set. When both are set, the
     * later of the two wins so an explicit delay can never start before its cron
     * boundary.
     */
    private long resolveStartDelayMillis(ExperimentDefinition definition, long now) {
        long delay = Math.max(0L, definition.startDelayMillis);
        if (definition.cronSchedule != null && !definition.cronSchedule.isBlank()) {
            long cronDelay = CronSchedule.parse(definition.cronSchedule).millisUntilNext(now);
            delay = Math.max(delay, cronDelay);
        }
        return delay;
    }

    private void scheduleStart(RunningExperiment experiment, long delayMillis) {
        experiment.scheduledStartAtMillis = clock.getAsLong() + delayMillis;
        ScheduledFuture<?> future = scheduler.schedule(() -> startNow(experiment), delayMillis, TimeUnit.MILLISECONDS);
        experiment.pendingAdvance = future;
    }

    private void startNow(RunningExperiment experiment) {
        // Ignore if the experiment was stopped or replaced before the timer fired.
        if (current.get() != experiment || !"scheduled".equals(experiment.status)) {
            return;
        }
        beginExperiment(experiment);
        LOG.info("chaos experiment '{}' deferred start fired", experiment.definition.name);
    }

    /**
     * Forces an immediately-scheduled (deferred-start) experiment to begin,
     * bypassing the scheduler. Used by tests to drive the deferred-start
     * transition deterministically without wall-clock sleeps. No-op unless an
     * experiment is currently in the {@code "scheduled"} state.
     */
    void triggerScheduledStartNow() {
        RunningExperiment exp = current.get();
        if (exp != null && "scheduled".equals(exp.status)) {
            ScheduledFuture<?> pending = exp.pendingAdvance;
            if (pending != null) {
                pending.cancel(false);
            }
            startNow(exp);
        }
    }

    /**
     * Stops the current experiment and clears all chaos from the registry.
     * Idempotent: no-op if no experiment is running.
     */
    public void stop() {
        stopInternal(false);
    }

    /**
     * Resets the orchestrator: stops any running experiment, clears chaos,
     * and clears the terminal status so {@link #getStatus()} returns null.
     * Called on server reset.
     */
    public void reset() {
        stopInternal(false);
        lastTerminatedStatus = null;
        lastTerminatedVerdict = null;
        synchronized (historyLock) {
            history.clear();
        }
    }

    /**
     * Returns the current experiment status. If no experiment is currently running
     * but one recently terminated, returns a status with the terminal status
     * ({@code halted_by_auto_halt}, {@code halted_by_slo_breach}, {@code completed},
     * or {@code stopped}).
     * Returns {@code null} only when no experiment has ever run (or after reset).
     */
    public ExperimentStatus getStatus() {
        RunningExperiment exp = current.get();
        if (exp == null) {
            String terminated = lastTerminatedStatus;
            if (terminated != null) {
                return new ExperimentStatus(null, terminated, 0, 0, 0, 0, 0, 0, null, 0, lastTerminatedVerdict);
            }
            return null;
        }
        long now = clock.getAsLong();
        if ("scheduled".equals(exp.status)) {
            // Deferred start: no stage has been applied yet, so stage elapsed/remaining
            // are not meaningful. Report startRemainingMillis instead.
            long startRemaining = Math.max(0, exp.scheduledStartAtMillis - now);
            return new ExperimentStatus(
                exp.definition.name, exp.status, 0, exp.definition.stages.size(),
                0, 0, exp.loopIteration, now - exp.startedAtMillis, exp.definition, startRemaining, exp.experimentVerdict);
        }
        long stageElapsed = now - exp.stageStartedAtMillis;
        Stage currentStage = exp.currentStageIndex < exp.definition.stages.size()
            ? exp.definition.stages.get(exp.currentStageIndex) : null;
        long stageRemaining = currentStage != null
            ? Math.max(0, currentStage.durationMillis - stageElapsed) : 0;
        return new ExperimentStatus(
            exp.definition.name,
            exp.status,
            exp.currentStageIndex,
            exp.definition.stages.size(),
            stageElapsed,
            stageRemaining,
            exp.loopIteration,
            now - exp.startedAtMillis,
            exp.definition,
            0,
            exp.experimentVerdict
        );
    }

    // -- Internal methods --

    /** Cancels an experiment's live SLO probe (if any). Idempotent. */
    private static void cancelProbe(RunningExperiment experiment) {
        ScheduledFuture<?> probe = experiment.sloProbe;
        if (probe != null) {
            probe.cancel(false);
            experiment.sloProbe = null;
        }
    }

    private void stopInternal(boolean autoHalted) {
        RunningExperiment exp = current.getAndSet(null);
        if (exp != null) {
            ScheduledFuture<?> pending = exp.pendingAdvance;
            if (pending != null) {
                pending.cancel(false);
            }
            cancelProbe(exp);
            exp.status = autoHalted ? "halted_by_auto_halt" : "stopped";
            exp.endedAtMillis = clock.getAsLong();
            finalizeVerdict(exp, autoHalted);
            lastTerminatedStatus = exp.status;
            // Clear chaos from the registries this experiment owned
            clearChaos(exp);
            recordHistory(exp);
            LOG.info("chaos experiment '{}' {}", exp.definition.name, exp.status);
        }
    }

    private void applyStage(RunningExperiment experiment, int stageIndex) {
        Stage stage = experiment.definition.stages.get(stageIndex);
        experiment.currentStageIndex = stageIndex;
        experiment.stageStartedAtMillis = clock.getAsLong();
        experiment.status = "running";

        // Clear previous stage's chaos, then apply new stage's profiles
        ServiceChaosRegistry registry = ServiceChaosRegistry.getInstance();
        registry.reset();

        for (Map.Entry<String, HttpChaosProfile> entry : stage.profiles.entrySet()) {
            registry.put(entry.getKey(), entry.getValue());
        }

        // Stage TCP / connection-lifecycle faults with the same discipline as HTTP profiles.
        // Only experiments that actually use TCP profiles take exclusive ownership of the
        // TcpChaosRegistry — an HTTP-only experiment never touches it (back-compatible).
        if (experiment.definition.usesTcpProfiles()) {
            TcpChaosRegistry tcpRegistry = TcpChaosRegistry.getInstance();
            tcpRegistry.reset();
            for (Map.Entry<String, TcpChaosProfile> entry : stage.tcpProfiles.entrySet()) {
                tcpRegistry.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void scheduleNextStage(RunningExperiment experiment, int currentStageIndex) {
        Stage stage = experiment.definition.stages.get(currentStageIndex);
        long delayMillis = stage.durationMillis;

        ScheduledFuture<?> future = scheduler.schedule(() -> advanceStage(experiment), delayMillis, TimeUnit.MILLISECONDS);
        experiment.pendingAdvance = future;
    }

    private void advanceStage(RunningExperiment experiment) {
        // Check if experiment was stopped or replaced
        if (current.get() != experiment) {
            return;
        }

        // C1 auto-halt integration: if the chaos this stage applied was cleared by auto-halt
        // (or a manual reset) while this stage was running, stop the experiment. Auto-halt
        // resets BOTH the ServiceChaosRegistry and the TcpChaosRegistry, so a stage that
        // applied HTTP and/or TCP faults is considered halted only when everything it applied
        // is now gone. Note: a running experiment takes exclusive ownership of the registries
        // it uses — manual chaos registrations are overwritten at the next stage advance.
        if (experiment.status.equals("running") && stageChaosCleared(experiment)) {
            LOG.warn("chaos experiment '{}' halted by auto-halt safety circuit-breaker at stage {}",
                experiment.definition.name, experiment.currentStageIndex);
            cancelProbe(experiment);
            experiment.status = "halted_by_auto_halt";
            experiment.endedAtMillis = clock.getAsLong();
            finalizeVerdict(experiment, true);
            lastTerminatedStatus = experiment.status;
            recordHistory(experiment);
            current.compareAndSet(experiment, null);
            return;
        }

        // SLO-breach auto-halt (A2): for an experiment with sloCriteria, an actual SLO
        // FAIL over the live window halts the experiment with a FAIL verdict, in addition
        // to the raw destructive-fault-volume circuit-breaker above.
        if (checkSloBreachAndHalt(experiment)) {
            return;
        }

        int nextStageIndex = experiment.currentStageIndex + 1;

        if (nextStageIndex >= experiment.definition.stages.size()) {
            // End of stages
            if (experiment.definition.loop) {
                // Loop back to stage 0
                experiment.loopIteration++;
                applyStage(experiment, 0);
                scheduleNextStage(experiment, 0);
                LOG.info("chaos experiment '{}' looping, iteration {}",
                    experiment.definition.name, experiment.loopIteration);
            } else {
                // Experiment complete
                cancelProbe(experiment);
                experiment.status = "completed";
                experiment.endedAtMillis = clock.getAsLong();
                finalizeVerdict(experiment, false);
                lastTerminatedStatus = experiment.status;
                clearChaos(experiment);
                recordHistory(experiment);
                LOG.info("chaos experiment '{}' completed after {} stage(s)",
                    experiment.definition.name, experiment.definition.stages.size());
                // Recurring cron experiment: re-arm from this terminal transition for the next
                // cron occurrence, retaining per-run verdict history. A one-shot experiment (the
                // default) simply detaches from current.
                if (experiment.definition.recurring && hasCron(experiment.definition)) {
                    rearmForNextOccurrence(experiment);
                } else {
                    current.compareAndSet(experiment, null);
                }
            }
        } else {
            // Advance to next stage
            applyStage(experiment, nextStageIndex);
            scheduleNextStage(experiment, nextStageIndex);
            LOG.info("chaos experiment '{}' advanced to stage {}/{}",
                experiment.definition.name, nextStageIndex + 1, experiment.definition.stages.size());
        }
    }

    /**
     * Forces immediate advancement to the next stage, bypassing the scheduler.
     * Used by tests to drive stage transitions deterministically without
     * wall-clock sleeps. No-op if no experiment is running.
     */
    void advanceNow() {
        RunningExperiment exp = current.get();
        if (exp != null) {
            ScheduledFuture<?> pending = exp.pendingAdvance;
            if (pending != null) {
                pending.cancel(false);
            }
            advanceStage(exp);
        }
    }

    /**
     * Schedules the next live SLO-breach probe for an experiment with {@code sloCriteria}.
     * Each probe is a one-shot that re-arms itself only while the experiment is still
     * {@link #current} and running; this self-cancels the moment the experiment terminates
     * (or is replaced), so a stale probe can never touch the shared registries. No-op when
     * the experiment has no {@code sloCriteria}.
     */
    private void scheduleSloProbe(RunningExperiment experiment) {
        if (experiment.definition.sloCriteria == null) {
            return;
        }
        experiment.sloProbe = scheduler.schedule(() -> {
            // Only act while this experiment is still the live, running one.
            if (current.get() != experiment || !"running".equals(experiment.status)) {
                return;
            }
            if (!checkSloBreachAndHalt(experiment)) {
                // Not breached (or no criteria) — re-arm the next probe.
                scheduleSloProbe(experiment);
            }
        }, SLO_PROBE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Evaluates the running experiment's SLO over its live window and halts it if
     * an objective is breached. No-op unless an experiment with {@code sloCriteria}
     * is currently {@code "running"}. Used by tests to drive the SLO-breach halt
     * deterministically; in production the same check runs from the live probe and at
     * every stage boundary (see {@link #advanceStage(RunningExperiment)}).
     *
     * @return {@code true} if the experiment was halted by an SLO breach
     */
    boolean checkSloNow() {
        RunningExperiment exp = current.get();
        return exp != null && checkSloBreachAndHalt(exp);
    }

    /**
     * For an experiment with {@code sloCriteria}, evaluates the SLO over the live
     * window {@code [start, now]}. On a FAIL verdict the experiment is halted with
     * status {@code "halted_by_slo_breach"} and the FAIL verdict is attached. Other
     * verdicts (PASS/INCONCLUSIVE) do not halt — the experiment runs to completion
     * where the terminal verdict is produced. No-op when there is no {@code sloCriteria}.
     *
     * @return {@code true} when the experiment was halted by an SLO breach
     */
    private boolean checkSloBreachAndHalt(RunningExperiment experiment) {
        if (experiment.definition.sloCriteria == null || !"running".equals(experiment.status)) {
            return false;
        }
        SloVerdict verdict = evaluateSlo(experiment, clock.getAsLong());
        if (verdict == null || verdict.getResult() != SloVerdict.Result.FAIL) {
            return false;
        }
        // Claim the experiment atomically BEFORE mutating any shared state. A stale
        // probe (or a probe racing a stop) that loses this CAS performs no global
        // mutation, so it can never clear a registry that now belongs to a different
        // experiment or test.
        if (!current.compareAndSet(experiment, null)) {
            return false;
        }
        LOG.warn("chaos experiment '{}' halted by SLO breach at stage {} (verdict {})",
            experiment.definition.name, experiment.currentStageIndex, verdict.getResult());
        cancelProbe(experiment);
        ScheduledFuture<?> pending = experiment.pendingAdvance;
        if (pending != null) {
            pending.cancel(false);
        }
        experiment.status = "halted_by_slo_breach";
        experiment.endedAtMillis = clock.getAsLong();
        experiment.experimentVerdict = verdict;
        lastTerminatedStatus = experiment.status;
        lastTerminatedVerdict = verdict;
        clearChaos(experiment);
        recordHistory(experiment);
        return true;
    }

    /**
     * Produces the terminal SLO verdict for a finished experiment. No-op (verdict
     * stays null) when the experiment has no {@code sloCriteria} — back-compatible.
     * When {@code autoHalted} is true the verdict is forced to {@link SloVerdict.Result#FAIL}
     * (an auto-halted experiment did not hold its steady state), but the per-objective
     * detail is still computed over the experiment window so the response is informative.
     */
    private void finalizeVerdict(RunningExperiment experiment, boolean autoHalted) {
        if (experiment.definition.sloCriteria == null) {
            return;
        }
        long end = clock.getAsLong();
        experiment.endedAtMillis = end;
        SloVerdict verdict = evaluateSlo(experiment, end);
        if (verdict != null && autoHalted && verdict.getResult() != SloVerdict.Result.FAIL) {
            // STRICT semantics: an auto-halted experiment is always a FAIL regardless of
            // what the samples in the window happen to show.
            verdict = verdict.withResult(SloVerdict.Result.FAIL);
        }
        experiment.experimentVerdict = verdict;
        lastTerminatedVerdict = verdict;
    }

    /**
     * Evaluates the experiment's {@code sloCriteria} over an EXPLICIT window scoped
     * to the experiment: {@code [startedAtMillis, toEpochMillis]}. Scoping the window
     * to the experiment's own start/end epoch (rather than the criteria's own window)
     * makes the verdict strictly about what happened during the experiment. Returns
     * {@code null} when there is no {@code sloCriteria}.
     */
    private SloVerdict evaluateSlo(RunningExperiment experiment, long toEpochMillis) {
        SloCriteria criteria = experiment.definition.sloCriteria;
        if (criteria == null) {
            return null;
        }
        // Scope evaluation to the experiment window via an EXPLICIT window, overriding
        // whatever window the criteria was submitted with. SloEvaluator uses EXPLICIT
        // bounds verbatim, so the evaluation never reaches outside the experiment.
        // Propagate minimumSampleCount unconditionally: an explicit null (guard disabled)
        // must survive rather than being silently re-defaulted to the model default of 1.
        // SloEvaluator null-handles the guard.
        SloCriteria scoped = new SloCriteria()
            .withName(criteria.getName())
            .withObjectives(criteria.getObjectives())
            .withUpstreamHosts(criteria.getUpstreamHosts())
            .withWindow(SloWindow.explicit(experiment.startedAtMillis, toEpochMillis))
            .withMinimumSampleCount(criteria.getMinimumSampleCount());
        return sloEvaluator.evaluate(scoped);
    }

    /**
     * Clears the chaos this experiment owned. The {@link ServiceChaosRegistry} is always
     * reset (existing behaviour); the {@link TcpChaosRegistry} is reset only when the
     * experiment actually staged TCP profiles, so an HTTP-only experiment never disturbs
     * manually-registered TCP chaos (back-compatible).
     */
    private void clearChaos(RunningExperiment experiment) {
        ServiceChaosRegistry.getInstance().reset();
        if (experiment.definition.usesTcpProfiles()) {
            TcpChaosRegistry.getInstance().reset();
        }
    }

    /**
     * Returns {@code true} when everything the current stage applied has been cleared from
     * under the experiment (the auto-halt / manual-reset condition). A stage that applied
     * HTTP faults requires an empty {@link ServiceChaosRegistry}; a stage that applied TCP
     * faults requires an empty {@link TcpChaosRegistry}; a stage applying both requires both
     * empty. This preserves the pre-existing HTTP-only semantics exactly while letting a
     * TCP-only stage detect its own auto-halt.
     */
    private boolean stageChaosCleared(RunningExperiment experiment) {
        Stage stage = experiment.currentStageIndex < experiment.definition.stages.size()
            ? experiment.definition.stages.get(experiment.currentStageIndex) : null;
        if (stage == null) {
            return false;
        }
        boolean stageHadHttp = !stage.profiles.isEmpty();
        boolean stageHadTcp = !stage.tcpProfiles.isEmpty();
        if (!stageHadHttp && !stageHadTcp) {
            return false;
        }
        boolean httpCleared = !stageHadHttp || ServiceChaosRegistry.getInstance().entries().isEmpty();
        boolean tcpCleared = !stageHadTcp || TcpChaosRegistry.getInstance().entries().isEmpty();
        return httpCleared && tcpCleared;
    }

    /**
     * Evaluates the experiment's {@code sloCriteria} over the pre-experiment steady-state
     * lookback window {@code [now - baselineWindowMillis, now]} via an EXPLICIT window. Used
     * only by the baseline pre-check. Never {@code null} here — callers guard on a non-null
     * {@code sloCriteria}.
     */
    private SloVerdict evaluateBaseline(ExperimentDefinition definition, long now) {
        SloCriteria criteria = definition.sloCriteria;
        SloCriteria scoped = new SloCriteria()
            .withName(criteria.getName())
            .withObjectives(criteria.getObjectives())
            .withUpstreamHosts(criteria.getUpstreamHosts())
            .withWindow(SloWindow.explicit(now - definition.baselineWindowMillis, now))
            .withMinimumSampleCount(criteria.getMinimumSampleCount());
        return sloEvaluator.evaluate(scoped);
    }

    /**
     * Re-arms a completed recurring experiment for its next cron occurrence, replacing the
     * finished run in {@link #current} with a fresh {@link RunningExperiment} in the
     * {@code "scheduled"} state. Per-run verdict history is already retained via
     * {@link #recordHistory(RunningExperiment)} at completion.
     */
    private void rearmForNextOccurrence(RunningExperiment finished) {
        long now = clock.getAsLong();
        long delay = CronSchedule.parse(finished.definition.cronSchedule).millisUntilNext(now);
        if (delay <= 0) {
            // Defensive: a cron that validated as satisfiable should always yield a positive
            // delay; if it somehow does not, stop cleanly rather than busy-loop.
            current.compareAndSet(finished, null);
            LOG.info("chaos experiment '{}' recurring: no further cron occurrence, stopping",
                finished.definition.name);
            return;
        }
        RunningExperiment next = new RunningExperiment(finished.definition, now);
        next.recurrenceIteration = finished.recurrenceIteration + 1;
        if (current.compareAndSet(finished, next)) {
            next.status = "scheduled";
            scheduleStart(next, delay);
            LOG.info("chaos experiment '{}' recurring: re-armed for the next cron occurrence in {} ms (run #{})",
                finished.definition.name, delay, next.recurrenceIteration);
        }
    }

    /** Appends a terminated-experiment record for a running experiment to the bounded ring. */
    private void recordHistory(RunningExperiment experiment) {
        long ended = experiment.endedAtMillis > 0 ? experiment.endedAtMillis : clock.getAsLong();
        recordHistory(experiment.definition.name, experiment.status, experiment.experimentVerdict, ended);
    }

    /** Appends a terminated-experiment record to the bounded ring (oldest evicted first). */
    private void recordHistory(String name, String status, SloVerdict verdict, long terminatedAtMillis) {
        synchronized (historyLock) {
            if (history.size() >= MAX_HISTORY) {
                history.pollFirst();
            }
            history.addLast(new ExperimentHistoryEntry(name, status, verdict, terminatedAtMillis));
        }
    }

    /**
     * Returns up to {@code limit} most-recently-terminated experiment records, newest first.
     * Exposed via {@code GET /chaosExperiment/history}.
     */
    public List<ExperimentHistoryEntry> getHistory(int limit) {
        synchronized (historyLock) {
            List<ExperimentHistoryEntry> result = new ArrayList<>();
            java.util.Iterator<ExperimentHistoryEntry> it = history.descendingIterator();
            while (it.hasNext() && result.size() < limit) {
                result.add(it.next());
            }
            return result;
        }
    }

    private String validate(ExperimentDefinition definition) {
        if (definition == null) {
            return "'experiment' is required";
        }
        if (definition.name == null || definition.name.isBlank()) {
            return "'name' is required";
        }
        if (definition.stages == null || definition.stages.isEmpty()) {
            return "'stages' must contain at least one stage";
        }
        if (definition.stages.size() > MAX_STAGES) {
            return "'stages' exceeds maximum of " + MAX_STAGES;
        }
        if (definition.startDelayMillis < 0) {
            return "'startDelayMillis' must be >= 0";
        }
        if (definition.baselineWindowMillis < 0) {
            return "'baselineWindowMillis' must be >= 0";
        }
        if (definition.baselineWindowMillis > MAX_STAGE_DURATION_MILLIS) {
            return "'baselineWindowMillis' exceeds maximum of " + MAX_STAGE_DURATION_MILLIS + " ms (24h)";
        }
        if (definition.baselineWindowMillis > 0 && definition.sloCriteria == null) {
            return "'baselineWindowMillis' requires 'sloCriteria' to evaluate the steady state against";
        }
        if (definition.recurring && !hasCron(definition)) {
            return "'recurring' requires a 'cronSchedule' to re-arm from";
        }
        if (definition.startDelayMillis > MAX_START_DELAY_MILLIS) {
            return "'startDelayMillis' exceeds maximum of " + MAX_START_DELAY_MILLIS + " ms (7 days)";
        }
        if (definition.cronSchedule != null && !definition.cronSchedule.isBlank()) {
            CronSchedule cron;
            try {
                cron = CronSchedule.parse(definition.cronSchedule);
            } catch (IllegalArgumentException e) {
                return "'cronSchedule' is invalid: " + e.getMessage();
            }
            // A satisfiable cron always matches some minute within the search horizon, so the
            // next match is at least one minute away (> 0). A return of 0 means the expression
            // can never fire (e.g. an impossible date like "0 0 30 2 *") — reject it rather than
            // silently starting immediately.
            if (cron.millisUntilNext(clock.getAsLong()) == 0L) {
                return "'cronSchedule' never matches a valid time";
            }
        }
        for (int i = 0; i < definition.stages.size(); i++) {
            Stage stage = definition.stages.get(i);
            if (stage.durationMillis <= 0) {
                return "stage[" + i + "].durationMillis must be > 0";
            }
            if (stage.durationMillis > MAX_STAGE_DURATION_MILLIS) {
                return "stage[" + i + "].durationMillis exceeds maximum of " + MAX_STAGE_DURATION_MILLIS + " ms (24h)";
            }
            boolean noHttp = stage.profiles == null || stage.profiles.isEmpty();
            boolean noTcp = stage.tcpProfiles == null || stage.tcpProfiles.isEmpty();
            if (noHttp && noTcp) {
                return "stage[" + i + "] must have at least one host/profile entry";
            }
        }
        return null;
    }

    private static boolean hasCron(ExperimentDefinition definition) {
        return definition.cronSchedule != null && !definition.cronSchedule.isBlank();
    }

    // -- Data classes --

    /**
     * An experiment definition: name, ordered stages, whether to loop, and an
     * optional deferred start ({@code startDelayMillis} and/or {@code cronSchedule}).
     * When no scheduling fields are set the experiment starts immediately
     * (back-compatible default).
     */
    public static class ExperimentDefinition {
        public final String name;
        public final List<Stage> stages;
        public final boolean loop;
        /** Fixed delay before stage 0 is applied; {@code 0} = start immediately. */
        public final long startDelayMillis;
        /** Standard 5-field cron expression for the start time; {@code null}/blank = none. */
        public final String cronSchedule;
        /**
         * Optional SLO asserted over the experiment window. When {@code null} the
         * experiment behaves exactly as before (no verdict). When present, a terminal
         * {@code experimentVerdict} is produced over the experiment window when the
         * experiment terminates.
         */
        public final SloCriteria sloCriteria;
        /**
         * When {@code true} and a {@code cronSchedule} is set, the experiment re-arms itself for
         * the next cron occurrence after it completes naturally (retaining per-run verdict
         * history), rather than terminating after a single run. Default {@code false} = the
         * pre-existing one-shot behaviour. Ignored (rejected at validation) without a cron.
         */
        public final boolean recurring;
        /**
         * When {@code > 0} (and {@code sloCriteria} is set), the orchestrator evaluates the SLO
         * over the pre-experiment lookback window {@code [now - baselineWindowMillis, now]} before
         * applying stage 0. If the steady state does not already hold (verdict not PASS) the
         * experiment refuses to start ({@code aborted_baseline_unhealthy}). Default {@code 0} =
         * no baseline pre-check (pre-existing behaviour).
         */
        public final long baselineWindowMillis;

        public ExperimentDefinition(String name, List<Stage> stages, boolean loop) {
            this(name, stages, loop, 0L, null, null);
        }

        public ExperimentDefinition(String name, List<Stage> stages, boolean loop,
                                    long startDelayMillis, String cronSchedule) {
            this(name, stages, loop, startDelayMillis, cronSchedule, null);
        }

        public ExperimentDefinition(String name, List<Stage> stages, boolean loop,
                                    long startDelayMillis, String cronSchedule, SloCriteria sloCriteria) {
            this(name, stages, loop, startDelayMillis, cronSchedule, sloCriteria, false, 0L);
        }

        public ExperimentDefinition(String name, List<Stage> stages, boolean loop,
                                    long startDelayMillis, String cronSchedule, SloCriteria sloCriteria,
                                    boolean recurring, long baselineWindowMillis) {
            this.name = name;
            this.stages = stages != null ? Collections.unmodifiableList(new ArrayList<>(stages)) : Collections.emptyList();
            this.loop = loop;
            this.startDelayMillis = startDelayMillis;
            this.cronSchedule = cronSchedule;
            this.sloCriteria = sloCriteria;
            this.recurring = recurring;
            this.baselineWindowMillis = baselineWindowMillis;
        }

        /** {@code true} when any stage stages one or more TCP chaos profiles. */
        public boolean usesTcpProfiles() {
            for (Stage stage : stages) {
                if (!stage.tcpProfiles.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Deserializes an experiment definition from a JSON node.
         */
        public static ExperimentDefinition fromJson(JsonNode node) {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            String name = node.path("name").asText(null);
            boolean loop = node.path("loop").asBoolean(false);
            boolean recurring = node.path("recurring").asBoolean(false);
            long startDelayMillis = node.path("startDelayMillis").asLong(0);
            long baselineWindowMillis = node.path("baselineWindowMillis").asLong(0);
            JsonNode cronNode = node.path("cronSchedule");
            String cronSchedule = cronNode.isTextual() ? cronNode.asText() : null;
            List<Stage> stages = new ArrayList<>();
            JsonNode stagesNode = node.path("stages");
            if (stagesNode.isArray()) {
                for (JsonNode stageNode : stagesNode) {
                    long durationMillis = stageNode.path("durationMillis").asLong(0);
                    Map<String, HttpChaosProfile> profiles = new java.util.LinkedHashMap<>();
                    JsonNode profilesNode = stageNode.path("profiles");
                    if (profilesNode.isObject()) {
                        var fields = profilesNode.fields();
                        while (fields.hasNext()) {
                            var entry = fields.next();
                            try {
                                HttpChaosProfileDTO dto = mapper.treeToValue(entry.getValue(), HttpChaosProfileDTO.class);
                                profiles.put(entry.getKey(), dto.buildObject());
                            } catch (Exception e) {
                                throw new IllegalArgumentException(
                                    "invalid chaos profile for host '" + entry.getKey() + "': " + e.getMessage(), e);
                            }
                        }
                    }
                    Map<String, TcpChaosProfile> tcpProfiles = new java.util.LinkedHashMap<>();
                    JsonNode tcpProfilesNode = stageNode.path("tcpProfiles");
                    if (tcpProfilesNode.isObject()) {
                        var fields = tcpProfilesNode.fields();
                        while (fields.hasNext()) {
                            var entry = fields.next();
                            try {
                                TcpChaosProfileDTO dto = mapper.treeToValue(entry.getValue(), TcpChaosProfileDTO.class);
                                tcpProfiles.put(entry.getKey(), dto.buildObject());
                            } catch (Exception e) {
                                throw new IllegalArgumentException(
                                    "invalid TCP chaos profile for host '" + entry.getKey() + "': " + e.getMessage(), e);
                            }
                        }
                    }
                    stages.add(new Stage(durationMillis, profiles, tcpProfiles));
                }
            }
            SloCriteria sloCriteria = null;
            JsonNode sloNode = node.path("sloCriteria");
            if (sloNode.isObject()) {
                try {
                    SloCriteriaDTO dto = mapper.treeToValue(sloNode, SloCriteriaDTO.class);
                    sloCriteria = dto != null ? dto.buildObject() : null;
                } catch (Exception e) {
                    throw new IllegalArgumentException("invalid sloCriteria: " + e.getMessage(), e);
                }
            }
            return new ExperimentDefinition(name, stages, loop, startDelayMillis, cronSchedule, sloCriteria,
                recurring, baselineWindowMillis);
        }

        /**
         * Serializes this experiment definition to a JSON node.
         */
        public ObjectNode toJson() {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.put("loop", loop);
            if (recurring) {
                node.put("recurring", true);
            }
            if (startDelayMillis > 0) {
                node.put("startDelayMillis", startDelayMillis);
            }
            if (baselineWindowMillis > 0) {
                node.put("baselineWindowMillis", baselineWindowMillis);
            }
            if (cronSchedule != null && !cronSchedule.isBlank()) {
                node.put("cronSchedule", cronSchedule);
            }
            ArrayNode stagesArray = node.putArray("stages");
            for (Stage stage : stages) {
                ObjectNode stageNode = mapper.createObjectNode();
                stageNode.put("durationMillis", stage.durationMillis);
                ObjectNode profilesNode = stageNode.putObject("profiles");
                for (Map.Entry<String, HttpChaosProfile> entry : stage.profiles.entrySet()) {
                    profilesNode.set(entry.getKey(), mapper.valueToTree(new HttpChaosProfileDTO(entry.getValue())));
                }
                if (!stage.tcpProfiles.isEmpty()) {
                    ObjectNode tcpProfilesNode = stageNode.putObject("tcpProfiles");
                    for (Map.Entry<String, TcpChaosProfile> entry : stage.tcpProfiles.entrySet()) {
                        tcpProfilesNode.set(entry.getKey(), mapper.valueToTree(new TcpChaosProfileDTO(entry.getValue())));
                    }
                }
                stagesArray.add(stageNode);
            }
            if (sloCriteria != null) {
                node.set("sloCriteria", mapper.valueToTree(new SloCriteriaDTO(sloCriteria)));
            }
            return node;
        }
    }

    /**
     * A single stage: HTTP and/or TCP chaos profiles to apply to specific hosts for a duration.
     * A stage may carry HTTP profiles ({@link ServiceChaosRegistry}), TCP / connection-lifecycle
     * profiles ({@link TcpChaosRegistry}), or both; at least one entry across the two is required.
     */
    public static class Stage {
        public final long durationMillis;
        public final Map<String, HttpChaosProfile> profiles;
        public final Map<String, TcpChaosProfile> tcpProfiles;

        public Stage(long durationMillis, Map<String, HttpChaosProfile> profiles) {
            this(durationMillis, profiles, null);
        }

        public Stage(long durationMillis, Map<String, HttpChaosProfile> profiles,
                     Map<String, TcpChaosProfile> tcpProfiles) {
            this.durationMillis = durationMillis;
            this.profiles = profiles != null
                ? Collections.unmodifiableMap(new java.util.LinkedHashMap<>(profiles))
                : Collections.emptyMap();
            this.tcpProfiles = tcpProfiles != null
                ? Collections.unmodifiableMap(new java.util.LinkedHashMap<>(tcpProfiles))
                : Collections.emptyMap();
        }
    }

    /**
     * Snapshot of the current experiment status.
     */
    public static class ExperimentStatus {
        public final String name;
        public final String status;
        public final int currentStageIndex;
        public final int totalStages;
        public final long stageElapsedMillis;
        public final long stageRemainingMillis;
        public final int loopIteration;
        public final long totalElapsedMillis;
        public final ExperimentDefinition definition;
        /** Milliseconds until a deferred-start experiment begins; {@code 0} unless status is {@code "scheduled"}. */
        public final long startRemainingMillis;
        /**
         * Terminal SLO verdict over the experiment window; {@code null} when the
         * experiment had no {@code sloCriteria} or has not yet produced a verdict.
         */
        public final SloVerdict experimentVerdict;

        public ExperimentStatus(String name, String status, int currentStageIndex, int totalStages,
                                long stageElapsedMillis, long stageRemainingMillis,
                                int loopIteration, long totalElapsedMillis,
                                ExperimentDefinition definition, long startRemainingMillis) {
            this(name, status, currentStageIndex, totalStages, stageElapsedMillis, stageRemainingMillis,
                loopIteration, totalElapsedMillis, definition, startRemainingMillis, null);
        }

        public ExperimentStatus(String name, String status, int currentStageIndex, int totalStages,
                                long stageElapsedMillis, long stageRemainingMillis,
                                int loopIteration, long totalElapsedMillis,
                                ExperimentDefinition definition, long startRemainingMillis,
                                SloVerdict experimentVerdict) {
            this.name = name;
            this.status = status;
            this.currentStageIndex = currentStageIndex;
            this.totalStages = totalStages;
            this.stageElapsedMillis = stageElapsedMillis;
            this.stageRemainingMillis = stageRemainingMillis;
            this.loopIteration = loopIteration;
            this.totalElapsedMillis = totalElapsedMillis;
            this.definition = definition;
            this.startRemainingMillis = startRemainingMillis;
            this.experimentVerdict = experimentVerdict;
        }

        public ObjectNode toJson() {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.put("status", status);
            node.put("currentStageIndex", currentStageIndex);
            node.put("totalStages", totalStages);
            node.put("stageElapsedMillis", stageElapsedMillis);
            node.put("stageRemainingMillis", stageRemainingMillis);
            node.put("loopIteration", loopIteration);
            node.put("totalElapsedMillis", totalElapsedMillis);
            if ("scheduled".equals(status)) {
                node.put("startRemainingMillis", startRemainingMillis);
            }
            if (definition != null) {
                node.set("experiment", definition.toJson());
            }
            if (experimentVerdict != null) {
                node.set("experimentVerdict", mapper.valueToTree(experimentVerdict));
            }
            return node;
        }
    }

    /**
     * Mutable state for a running experiment.
     */
    private static final class RunningExperiment {
        final ExperimentDefinition definition;
        final long startedAtMillis;
        volatile int currentStageIndex;
        volatile long stageStartedAtMillis;
        volatile String status;
        volatile int loopIteration;
        volatile ScheduledFuture<?> pendingAdvance;
        /** Live SLO-breach probe; {@code null} unless the experiment has {@code sloCriteria}. */
        volatile ScheduledFuture<?> sloProbe;
        /** Absolute clock time (ms) when a deferred-start experiment is due to begin. */
        volatile long scheduledStartAtMillis;
        /** Absolute clock time (ms) when the experiment terminated; {@code 0} until then. */
        volatile long endedAtMillis;
        /** Terminal SLO verdict over the experiment window; {@code null} until finalized
         *  (and always {@code null} when the experiment has no {@code sloCriteria}). */
        volatile SloVerdict experimentVerdict;
        /** 0 for the first run; incremented each time a recurring experiment re-arms. */
        volatile int recurrenceIteration;

        RunningExperiment(ExperimentDefinition definition, long startedAtMillis) {
            this.definition = definition;
            this.startedAtMillis = startedAtMillis;
            this.status = "starting";
            this.loopIteration = 0;
        }
    }

    /**
     * An immutable record of a terminated experiment, retained in the bounded history ring and
     * exposed at {@code GET /chaosExperiment/history}. Captures the experiment name, its terminal
     * status, the terminal SLO verdict (if any), and when it terminated.
     */
    public static final class ExperimentHistoryEntry {
        public final String name;
        public final String status;
        /** Terminal SLO verdict; {@code null} when the experiment had no {@code sloCriteria}. */
        public final SloVerdict verdict;
        public final long terminatedAtMillis;

        ExperimentHistoryEntry(String name, String status, SloVerdict verdict, long terminatedAtMillis) {
            this.name = name;
            this.status = status;
            this.verdict = verdict;
            this.terminatedAtMillis = terminatedAtMillis;
        }

        public ObjectNode toJson() {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.put("status", status);
            node.put("terminatedAtMillis", terminatedAtMillis);
            if (verdict != null) {
                node.set("verdict", mapper.valueToTree(verdict));
            }
            return node;
        }
    }
}
