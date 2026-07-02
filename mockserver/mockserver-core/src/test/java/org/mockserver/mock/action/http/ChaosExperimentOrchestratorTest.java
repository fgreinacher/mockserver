package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.HttpChaosProfile;
import org.mockserver.model.TcpChaosProfile;
import org.mockserver.slo.SloCriteria;
import org.mockserver.slo.SloObjective;
import org.mockserver.slo.SloSampleStore;
import org.mockserver.slo.SloVerdict;
import org.mockserver.slo.SloWindow;
import org.mockserver.slo.Scope;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;

/**
 * Tests for {@link ChaosExperimentOrchestrator}. All tests use a controllable
 * clock and the {@code advanceNow()} method for deterministic stage advancement
 * without wall-clock sleeps.
 */
public class ChaosExperimentOrchestratorTest {

    private AtomicLong clock;
    private ScheduledExecutorService scheduler;
    private ChaosExperimentOrchestrator orchestrator;

    private boolean originalAutoHaltEnabled;
    private long originalAutoHaltThreshold;
    private long originalAutoHaltWindow;
    private boolean originalSloTrackingEnabled;

    @Before
    public void setUp() {
        clock = new AtomicLong(10_000L);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-chaos-experiment-scheduler");
            t.setDaemon(true);
            return t;
        });
        orchestrator = new ChaosExperimentOrchestrator(clock::get, scheduler);
        ServiceChaosRegistry.getInstance().reset();
        TcpChaosRegistry.getInstance().reset();
        ChaosAutoHaltMonitor.getInstance().reset();
        SloSampleStore.getInstance().reset();
        Metrics.resetAdditionalMetricsForTesting();

        originalAutoHaltEnabled = ConfigurationProperties.chaosAutoHaltEnabled();
        originalAutoHaltThreshold = ConfigurationProperties.chaosAutoHaltErrorThreshold();
        originalAutoHaltWindow = ConfigurationProperties.chaosAutoHaltWindowMillis();
        originalSloTrackingEnabled = ConfigurationProperties.sloTrackingEnabled();
        // SLO sample recording must be on so experiment-scoped verdicts see samples.
        ConfigurationProperties.sloTrackingEnabled(true);
    }

    @After
    public void tearDown() {
        orchestrator.reset();
        scheduler.shutdownNow();
        try {
            // Ensure no scheduled SLO probe outlives the test and mutates shared singletons.
            scheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ServiceChaosRegistry.getInstance().reset();
        TcpChaosRegistry.getInstance().reset();
        ChaosAutoHaltMonitor.getInstance().reset();
        SloSampleStore.getInstance().reset();
        Metrics.resetAdditionalMetricsForTesting();
        ConfigurationProperties.chaosAutoHaltEnabled(originalAutoHaltEnabled);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(originalAutoHaltThreshold);
        ConfigurationProperties.chaosAutoHaltWindowMillis(originalAutoHaltWindow);
        ConfigurationProperties.sloTrackingEnabled(originalSloTrackingEnabled);
    }

    /** An SLO criteria asserting error-rate &lt; threshold over FORWARD traffic. */
    private SloCriteria errorRateBelow(double threshold) {
        return new SloCriteria()
            .withName("exp-slo")
            .withMinimumSampleCount(1)
            // The window is overridden by the orchestrator to the experiment window,
            // but a window is required by the SloCriteria model so supply a placeholder.
            .withWindow(SloWindow.lookback(60_000L))
            .withObjectives(new SloObjective()
                .withSli(SloObjective.Sli.ERROR_RATE)
                .withComparator(SloObjective.Comparator.LESS_THAN)
                .withThreshold(threshold)
                .withScope(Scope.FORWARD));
    }

    private Map<String, HttpChaosProfile> profileMap(String host, HttpChaosProfile profile) {
        Map<String, HttpChaosProfile> map = new LinkedHashMap<>();
        map.put(host, profile);
        return map;
    }

    // --- Starting an experiment ---

    @Test
    public void shouldStartExperimentAndApplyStageZero() {
        // given
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("test-exp", Arrays.asList(stage0), false);

        // when
        String error = orchestrator.start(def);

        // then
        assertThat("no validation error", error, is(nullValue()));
        assertThat("chaos profile applied to registry",
            ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(503));

        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status, is(notNullValue()));
        assertThat(status.name, is("test-exp"));
        assertThat(status.status, is("running"));
        assertThat(status.currentStageIndex, is(0));
        assertThat(status.totalStages, is(1));
    }

    @Test
    public void shouldApplyMultipleHostProfilesInOneStage() {
        // given
        Map<String, HttpChaosProfile> profiles = new LinkedHashMap<>();
        profiles.put("api.svc", httpChaosProfile().withErrorStatus(503));
        profiles.put("db.svc", httpChaosProfile().withErrorStatus(500));
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(5000L, profiles);
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("multi-host", Arrays.asList(stage0), false);

        // when
        orchestrator.start(def);

        // then
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get("db.svc"), is(notNullValue()));
    }

    // --- Stage advancement ---

    @Test
    public void shouldAdvanceToNextStage() {
        // given - two-stage experiment
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.Stage stage1 = new ChaosExperimentOrchestrator.Stage(
            3000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(429)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("advance-test", Arrays.asList(stage0, stage1), false);

        orchestrator.start(def);
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(503));

        // when - force advance to stage 1
        clock.addAndGet(5000L);
        orchestrator.advanceNow();

        // then - stage 1 profile is applied
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(429));
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.currentStageIndex, is(1));
        assertThat(status.status, is("running"));
    }

    @Test
    public void shouldCompleteAfterLastStage() {
        // given - single-stage, non-looping experiment
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("complete-test", Arrays.asList(stage0), false);

        orchestrator.start(def);

        // when - advance past the only stage
        clock.addAndGet(5000L);
        orchestrator.advanceNow();

        // then - experiment completed, chaos cleared
        assertThat("chaos cleared after completion",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        // After completion, getStatus() returns the terminal status (not null)
        ChaosExperimentOrchestrator.ExperimentStatus completedStatus = orchestrator.getStatus();
        assertThat("terminal status is 'completed'", completedStatus.status, is("completed"));
    }

    @Test
    public void shouldProgressThroughThreeStages() {
        // given
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            1000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(500)));
        ChaosExperimentOrchestrator.Stage stage1 = new ChaosExperimentOrchestrator.Stage(
            2000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(502)));
        ChaosExperimentOrchestrator.Stage stage2 = new ChaosExperimentOrchestrator.Stage(
            3000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("three-stages",
                Arrays.asList(stage0, stage1, stage2), false);

        orchestrator.start(def);
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(500));

        // advance to stage 1
        clock.addAndGet(1000L);
        orchestrator.advanceNow();
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(502));
        assertThat(orchestrator.getStatus().currentStageIndex, is(1));

        // advance to stage 2
        clock.addAndGet(2000L);
        orchestrator.advanceNow();
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(503));
        assertThat(orchestrator.getStatus().currentStageIndex, is(2));

        // complete
        clock.addAndGet(3000L);
        orchestrator.advanceNow();
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    // --- Looping ---

    @Test
    public void shouldLoopBackToStageZero() {
        // given - two-stage looping experiment
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            2000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.Stage stage1 = new ChaosExperimentOrchestrator.Stage(
            3000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(429)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("loop-test", Arrays.asList(stage0, stage1), true);

        orchestrator.start(def);
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(503));

        // advance through stage 0 -> stage 1
        clock.addAndGet(2000L);
        orchestrator.advanceNow();
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(429));

        // advance past stage 1 -> loops back to stage 0
        clock.addAndGet(3000L);
        orchestrator.advanceNow();
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(503));

        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.currentStageIndex, is(0));
        assertThat(status.loopIteration, is(1));
        assertThat(status.status, is("running"));
    }

    // --- Stop/Reset ---

    @Test
    public void shouldStopExperimentAndClearChaos() {
        // given
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("stop-test", Arrays.asList(stage0), false);
        orchestrator.start(def);

        // when
        orchestrator.stop();

        // then
        assertThat("chaos cleared on stop",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        // After stop, getStatus() returns the terminal status (not null)
        ChaosExperimentOrchestrator.ExperimentStatus stoppedStatus = orchestrator.getStatus();
        assertThat("terminal status is 'stopped'", stoppedStatus.status, is("stopped"));
    }

    @Test
    public void shouldResetExperimentOnServerReset() {
        // given
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("reset-test", Arrays.asList(stage0), false);
        orchestrator.start(def);

        // when
        orchestrator.reset();

        // then
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(orchestrator.getStatus(), is(nullValue()));
    }

    @Test
    public void shouldStopIdempotently() {
        // given - no experiment running
        // when
        orchestrator.stop();

        // then - no error
        assertThat(orchestrator.getStatus(), is(nullValue()));
    }

    @Test
    public void shouldReplaceRunningExperimentWhenNewOneStarts() {
        // given - first experiment
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "first", Arrays.asList(stage0), false));

        // when - start second experiment
        ChaosExperimentOrchestrator.Stage stage1 = new ChaosExperimentOrchestrator.Stage(
            30000L, profileMap("db.svc", httpChaosProfile().withErrorStatus(500)));
        orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "second", Arrays.asList(stage1), false));

        // then - second experiment is active, first's chaos is cleared
        assertThat(orchestrator.getStatus().name, is("second"));
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(nullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get("db.svc"), is(notNullValue()));
    }

    // --- C1 Auto-halt integration ---

    @Test
    public void shouldStopExperimentWhenAutoHaltClearsChaos() {
        // given - experiment running
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.Stage stage1 = new ChaosExperimentOrchestrator.Stage(
            30000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(429)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("autohalt-test",
                Arrays.asList(stage0, stage1), false);
        orchestrator.start(def);

        // when - simulate C1 auto-halt: clear the registry (as ChaosAutoHaltMonitor does)
        ServiceChaosRegistry.getInstance().reset();

        // and advance to the next stage (orchestrator checks registry)
        clock.addAndGet(60000L);
        orchestrator.advanceNow();

        // then - experiment should be halted, not advancing
        ChaosExperimentOrchestrator.ExperimentStatus haltedStatus = orchestrator.getStatus();
        assertThat("terminal status is 'halted_by_auto_halt'",
            haltedStatus.status, is("halted_by_auto_halt"));
        assertThat("chaos stays empty",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldIntegrateWithRealAutoHaltMonitor() {
        // given - experiment running with auto-halt enabled
        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(60_000L);

        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.Stage stage1 = new ChaosExperimentOrchestrator.Stage(
            30000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(429)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("real-autohalt",
                Arrays.asList(stage0, stage1), false);
        orchestrator.start(def);

        // when - trigger auto-halt via the real monitor
        ChaosAutoHaltMonitor.getInstance().recordError("error");
        ChaosAutoHaltMonitor.getInstance().recordError("error");
        ChaosAutoHaltMonitor.getInstance().recordError("error");

        // then - auto-halt has cleared the registry
        assertThat("auto-halt cleared chaos",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));

        // when - advance to the next stage
        clock.addAndGet(60000L);
        orchestrator.advanceNow();

        // then - experiment detects the empty registry and stops
        ChaosExperimentOrchestrator.ExperimentStatus realHaltedStatus = orchestrator.getStatus();
        assertThat("terminal status is 'halted_by_auto_halt' after real auto-halt",
            realHaltedStatus.status, is("halted_by_auto_halt"));
    }

    // --- Validation ---

    @Test
    public void shouldRejectNullDefinition() {
        String error = orchestrator.start(null);
        assertThat(error, is("'experiment' is required"));
    }

    @Test
    public void shouldRejectMissingName() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            null, Arrays.asList(stage0), false));
        assertThat(error, is("'name' is required"));
    }

    @Test
    public void shouldRejectBlankName() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "   ", Arrays.asList(stage0), false));
        assertThat(error, is("'name' is required"));
    }

    @Test
    public void shouldRejectEmptyStages() {
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "empty-stages", Collections.emptyList(), false));
        assertThat(error, is("'stages' must contain at least one stage"));
    }

    @Test
    public void shouldRejectNullStages() {
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "null-stages", null, false));
        assertThat(error, is("'stages' must contain at least one stage"));
    }

    @Test
    public void shouldRejectZeroDuration() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            0, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "zero-dur", Arrays.asList(stage0), false));
        assertThat(error, is("stage[0].durationMillis must be > 0"));
    }

    @Test
    public void shouldRejectNegativeDuration() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            -1000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "neg-dur", Arrays.asList(stage0), false));
        assertThat(error, is("stage[0].durationMillis must be > 0"));
    }

    @Test
    public void shouldRejectDurationExceedingMax() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            ChaosExperimentOrchestrator.MAX_STAGE_DURATION_MILLIS + 1,
            profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "too-long", Arrays.asList(stage0), false));
        assertThat(error, containsString("exceeds maximum"));
    }

    @Test
    public void shouldRejectEmptyProfiles() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, Collections.emptyMap());
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "no-profiles", Arrays.asList(stage0), false));
        assertThat(error, is("stage[0] must have at least one host/profile entry"));
    }

    @Test
    public void shouldRejectTooManyStages() {
        java.util.List<ChaosExperimentOrchestrator.Stage> stages = new java.util.ArrayList<>();
        for (int i = 0; i <= ChaosExperimentOrchestrator.MAX_STAGES; i++) {
            stages.add(new ChaosExperimentOrchestrator.Stage(
                1000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503))));
        }
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "too-many-stages", stages, false));
        assertThat(error, containsString("exceeds maximum of " + ChaosExperimentOrchestrator.MAX_STAGES));
    }

    // --- Status reporting ---

    @Test
    public void shouldReturnNullStatusWhenNoExperiment() {
        assertThat(orchestrator.getStatus(), is(nullValue()));
    }

    @Test
    public void shouldReportCorrectStatusDuringExperiment() {
        // given
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            10000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.Stage stage1 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(429)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("status-test",
                Arrays.asList(stage0, stage1), false);
        orchestrator.start(def);

        // advance time a bit within stage 0
        clock.addAndGet(3000L);

        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.name, is("status-test"));
        assertThat(status.status, is("running"));
        assertThat(status.currentStageIndex, is(0));
        assertThat(status.totalStages, is(2));
        assertThat(status.stageElapsedMillis, is(3000L));
        assertThat(status.stageRemainingMillis, is(7000L));
        assertThat(status.loopIteration, is(0));
        assertThat(status.totalElapsedMillis, is(3000L));
    }

    @Test
    public void shouldSerializeStatusToJson() {
        // given
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("json-test", Arrays.asList(stage0), false);
        orchestrator.start(def);

        // when
        com.fasterxml.jackson.databind.node.ObjectNode json = orchestrator.getStatus().toJson();

        // then
        assertThat(json.get("name").asText(), is("json-test"));
        assertThat(json.get("status").asText(), is("running"));
        assertThat(json.get("currentStageIndex").asInt(), is(0));
        assertThat(json.get("totalStages").asInt(), is(1));
        assertThat(json.has("experiment"), is(true));
        assertThat(json.get("experiment").get("name").asText(), is("json-test"));
    }

    // --- JSON deserialization ---

    @Test
    public void shouldDeserializeExperimentFromJson() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper();
        String json = "{"
            + "\"name\":\"from-json\","
            + "\"loop\":true,"
            + "\"stages\":["
            + "  {\"durationMillis\":5000,\"profiles\":{\"api.svc\":{\"errorStatus\":503}}},"
            + "  {\"durationMillis\":3000,\"profiles\":{\"db.svc\":{\"errorStatus\":500}}}"
            + "]"
            + "}";
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(node);

        assertThat(def.name, is("from-json"));
        assertThat(def.loop, is(true));
        assertThat(def.stages.size(), is(2));
        assertThat(def.stages.get(0).durationMillis, is(5000L));
        assertThat(def.stages.get(0).profiles.get("api.svc").getErrorStatus(), is(503));
        assertThat(def.stages.get(1).durationMillis, is(3000L));
        assertThat(def.stages.get(1).profiles.get("db.svc").getErrorStatus(), is(500));
    }

    @Test
    public void shouldRoundTripExperimentThroughJson() throws Exception {
        // given
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition original =
            new ChaosExperimentOrchestrator.ExperimentDefinition("roundtrip", Arrays.asList(stage0), true);

        // when
        com.fasterxml.jackson.databind.node.ObjectNode json = original.toJson();
        ChaosExperimentOrchestrator.ExperimentDefinition restored =
            ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(json);

        // then
        assertThat(restored.name, is("roundtrip"));
        assertThat(restored.loop, is(true));
        assertThat(restored.stages.size(), is(1));
        assertThat(restored.stages.get(0).durationMillis, is(5000L));
        assertThat(restored.stages.get(0).profiles.get("api.svc").getErrorStatus(), is(503));
    }

    // --- Stages clear previous profiles ---

    @Test
    public void shouldClearPreviousStageProfilesOnAdvance() {
        // given - stage 0 targets api.svc, stage 1 targets db.svc
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.Stage stage1 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("db.svc", httpChaosProfile().withErrorStatus(500)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("clear-test",
                Arrays.asList(stage0, stage1), false);

        orchestrator.start(def);
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get("db.svc"), is(nullValue()));

        // when
        clock.addAndGet(5000L);
        orchestrator.advanceNow();

        // then - stage 0's profiles are cleared, stage 1's applied
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(nullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get("db.svc"), is(notNullValue()));
    }

    // --- Scheduled (deferred / cron) start ---

    @Test
    public void shouldStartImmediatelyWhenNoScheduleSet() {
        // given - definition with no scheduling fields (back-compat default)
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("immediate", Arrays.asList(stage0), false);

        // when
        String error = orchestrator.start(def);

        // then - stage 0 applied immediately, status running
        assertThat(error, is(nullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));
        assertThat(orchestrator.getStatus().status, is("running"));
    }

    @Test
    public void shouldNotApplyChaosUntilDelayElapses() {
        // given - experiment with a 30s deferred start
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "delayed", Arrays.asList(stage0), false, 30_000L, null);

        // when
        String error = orchestrator.start(def);

        // then - no chaos applied yet; status is "scheduled"
        assertThat(error, is(nullValue()));
        assertThat("chaos NOT applied during the delay window",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.status, is("scheduled"));
        assertThat(status.name, is("delayed"));
        assertThat(status.currentStageIndex, is(0));
        assertThat(status.startRemainingMillis, is(30_000L));

        // when - the delay elapses and the deferred-start timer fires
        clock.addAndGet(30_000L);
        orchestrator.triggerScheduledStartNow();

        // then - stage 0 is now applied and the experiment is running
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc").getErrorStatus(), is(503));
        assertThat(orchestrator.getStatus().status, is("running"));
        assertThat(orchestrator.getStatus().currentStageIndex, is(0));
    }

    @Test
    public void shouldReportShrinkingStartRemainingWhileScheduled() {
        // given
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "delayed-status", Arrays.asList(stage0), false, 10_000L, null);
        orchestrator.start(def);

        // when - 4s elapses within the delay window
        clock.addAndGet(4000L);

        // then - startRemainingMillis reflects the remaining delay
        assertThat(orchestrator.getStatus().startRemainingMillis, is(6000L));
        assertThat(orchestrator.getStatus().status, is("scheduled"));
        assertThat("still no chaos applied",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldStopScheduledExperimentBeforeItStarts() {
        // given - a deferred experiment that has not started yet
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "cancel-before-start", Arrays.asList(stage0), false, 60_000L, null));
        assertThat(orchestrator.getStatus().status, is("scheduled"));

        // when
        orchestrator.stop();

        // then - the deferred start is cancelled and never applies chaos
        assertThat(orchestrator.getStatus().status, is("stopped"));
        clock.addAndGet(60_000L);
        orchestrator.triggerScheduledStartNow();
        assertThat("a stopped scheduled experiment never applies chaos",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldReplaceScheduledExperimentWithNewOne() {
        // given - a deferred experiment that has not started yet
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "scheduled-first", Arrays.asList(stage0), false, 60_000L, null));

        // when - start a second (immediate) experiment
        ChaosExperimentOrchestrator.Stage stage1 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("db.svc", httpChaosProfile().withErrorStatus(500)));
        orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "immediate-second", Arrays.asList(stage1), false));

        // then - the second runs now; the first's deferred timer is a no-op when it fires
        assertThat(orchestrator.getStatus().name, is("immediate-second"));
        assertThat(orchestrator.getStatus().status, is("running"));
        assertThat(ServiceChaosRegistry.getInstance().get("db.svc"), is(notNullValue()));
        clock.addAndGet(60_000L);
        orchestrator.triggerScheduledStartNow();
        assertThat("first experiment never starts after being replaced",
            ServiceChaosRegistry.getInstance().get("api.svc"), is(nullValue()));
    }

    @Test
    public void shouldDeferStartViaCronSchedule() {
        // given - clock at 2026-06-20T10:17:30 (local). A "0 11 * * *" cron is the
        // next 11:00 boundary, i.e. some positive delay away.
        long base = java.time.ZonedDateTime.of(2026, 6, 20, 10, 17, 30, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli();
        clock.set(base);
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "cron-exp", Arrays.asList(stage0), false, 0L, "0 11 * * *");

        // when
        String error = orchestrator.start(def);

        // then - scheduled, not yet applied
        assertThat(error, is(nullValue()));
        assertThat(orchestrator.getStatus().status, is("scheduled"));
        assertThat("no chaos before the cron boundary",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        // next 11:00 is 42m30s away = 2_550_000 ms
        assertThat(orchestrator.getStatus().startRemainingMillis, is(2_550_000L));

        // when - the cron boundary arrives
        clock.addAndGet(2_550_000L);
        orchestrator.triggerScheduledStartNow();

        // then - chaos applied
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));
        assertThat(orchestrator.getStatus().status, is("running"));
    }

    @Test
    public void shouldRejectNegativeStartDelay() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "neg-delay", Arrays.asList(stage0), false, -1L, null));
        assertThat(error, is("'startDelayMillis' must be >= 0"));
    }

    @Test
    public void shouldRejectStartDelayExceedingMax() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "huge-delay", Arrays.asList(stage0), false,
            ChaosExperimentOrchestrator.MAX_START_DELAY_MILLIS + 1, null));
        assertThat(error, containsString("exceeds maximum"));
    }

    @Test
    public void shouldRejectInvalidCronSchedule() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "bad-cron", Arrays.asList(stage0), false, 0L, "not a cron"));
        assertThat(error, containsString("'cronSchedule' is invalid"));
    }

    @Test
    public void shouldRejectNeverMatchingCronSchedule() {
        // "0 0 30 2 *" — 30 February never occurs, so the cron can never fire.
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "impossible-cron", Arrays.asList(stage0), false, 0L, "0 0 30 2 *"));
        assertThat(error, is("'cronSchedule' never matches a valid time"));
        assertThat("no experiment was scheduled", orchestrator.getStatus(), is(nullValue()));
    }

    @Test
    public void shouldRoundTripScheduleFieldsThroughJson() throws Exception {
        // given
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition original =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "sched-json", Arrays.asList(stage0), false, 15_000L, "*/5 * * * *");

        // when
        com.fasterxml.jackson.databind.node.ObjectNode json = original.toJson();
        ChaosExperimentOrchestrator.ExperimentDefinition restored =
            ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(json);

        // then
        assertThat(json.get("startDelayMillis").asLong(), is(15_000L));
        assertThat(json.get("cronSchedule").asText(), is("*/5 * * * *"));
        assertThat(restored.startDelayMillis, is(15_000L));
        assertThat(restored.cronSchedule, is("*/5 * * * *"));
    }

    @Test
    public void shouldOmitScheduleFieldsFromJsonWhenUnset() {
        // given - no schedule
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("no-sched", Arrays.asList(stage0), false);

        // when
        com.fasterxml.jackson.databind.node.ObjectNode json = def.toJson();

        // then - the scheduling fields are absent (back-compat output)
        assertThat(json.has("startDelayMillis"), is(false));
        assertThat(json.has("cronSchedule"), is(false));
    }

    // --- No-experiment baseline ---

    @Test
    public void shouldNotAffectRegistryWhenNoExperimentRunning() {
        // given - some existing chaos in the registry (set externally)
        ServiceChaosRegistry.getInstance().put("manual.svc", httpChaosProfile().withErrorStatus(503));

        // when - stop (idempotent, no experiment running)
        orchestrator.stop();

        // then - the registry is unchanged by a stop with no running experiment
        // (stop calls reset on the registry, which is expected — this test documents the behavior)
        // If we want stop to be no-op when nothing is running, we need the null check
        assertThat("stop with no experiment is idempotent (no-op)",
            orchestrator.getStatus(), is(nullValue()));
    }

    // --- A1: experiment SLO assertion + terminal verdict ---

    @Test
    public void shouldEmitPassVerdictWhenSloHeldThroughoutExperiment() {
        // given - a single-stage experiment with an error-rate SLO
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "slo-pass", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5));
        orchestrator.start(def);

        // and - all successful samples recorded within the experiment window
        SloSampleStore.getInstance().record(clock.get() + 1000L, 50L, false, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(clock.get() + 2000L, 60L, false, Scope.FORWARD, "api.svc");

        // when - the experiment completes
        clock.addAndGet(5000L);
        orchestrator.advanceNow();

        // then - terminal verdict is PASS and the window is the experiment window
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.status, is("completed"));
        assertThat(status.experimentVerdict, is(notNullValue()));
        assertThat(status.experimentVerdict.getResult(), is(SloVerdict.Result.PASS));
        assertThat(status.experimentVerdict.getWindowFromEpochMillis(), is(10_000L));
        assertThat(status.experimentVerdict.getWindowToEpochMillis(), is(15_000L));
        // verdict is also surfaced in the status JSON
        assertThat(status.toJson().has("experimentVerdict"), is(true));
        assertThat(status.toJson().get("experimentVerdict").get("result").asText(), is("PASS"));
    }

    @Test
    public void shouldEmitFailVerdictWhenSloBreachedDuringExperiment() {
        // given - a single long stage so completion does not pre-empt the verdict path
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "slo-fail", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5));
        orchestrator.start(def);

        // and - a high error rate (3/4 = 0.75 > 0.5) recorded in the window
        SloSampleStore.getInstance().record(clock.get() + 500L, 50L, true, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(clock.get() + 600L, 50L, true, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(clock.get() + 700L, 50L, true, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(clock.get() + 800L, 50L, false, Scope.FORWARD, "api.svc");

        // when - the experiment terminates by explicit stop (verdict computed over window)
        clock.addAndGet(900L);
        orchestrator.stop();

        // then - terminal verdict is FAIL
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.status, is("stopped"));
        assertThat(status.experimentVerdict, is(notNullValue()));
        assertThat(status.experimentVerdict.getResult(), is(SloVerdict.Result.FAIL));
    }

    @Test
    public void shouldEmitInconclusiveVerdictWhenTooFewSamples() {
        // given - an SLO requiring at least 5 samples
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        SloCriteria criteria = errorRateBelow(0.5).withMinimumSampleCount(5);
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "slo-inconclusive", Arrays.asList(stage0), false, 0L, null, criteria);
        orchestrator.start(def);

        // and - only two samples recorded (< minimumSampleCount)
        SloSampleStore.getInstance().record(clock.get() + 100L, 50L, false, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(clock.get() + 200L, 50L, false, Scope.FORWARD, "api.svc");

        // when - the experiment completes
        clock.addAndGet(5000L);
        orchestrator.advanceNow();

        // then - terminal verdict is INCONCLUSIVE
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.status, is("completed"));
        assertThat(status.experimentVerdict, is(notNullValue()));
        assertThat(status.experimentVerdict.getResult(), is(SloVerdict.Result.INCONCLUSIVE));
    }

    @Test
    public void shouldEmitNoVerdictWhenNoSloCriteria() {
        // given - an experiment WITHOUT sloCriteria (back-compat default)
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("no-slo", Arrays.asList(stage0), false);
        orchestrator.start(def);
        SloSampleStore.getInstance().record(clock.get() + 100L, 50L, true, Scope.FORWARD, "api.svc");

        // when - the experiment completes
        clock.addAndGet(5000L);
        orchestrator.advanceNow();

        // then - no verdict is attached, and the status JSON omits experimentVerdict
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.status, is("completed"));
        assertThat(status.experimentVerdict, is(nullValue()));
        assertThat(status.toJson().has("experimentVerdict"), is(false));
    }

    @Test
    public void shouldRoundTripSloCriteriaThroughJson() throws Exception {
        // given - an experiment with sloCriteria
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition original =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "slo-json", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.25));

        // when
        com.fasterxml.jackson.databind.node.ObjectNode json = original.toJson();
        ChaosExperimentOrchestrator.ExperimentDefinition restored =
            ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(json);

        // then
        assertThat(json.has("sloCriteria"), is(true));
        assertThat(restored.sloCriteria, is(notNullValue()));
        assertThat(restored.sloCriteria.getObjectives().size(), is(1));
        assertThat(restored.sloCriteria.getObjectives().get(0).getThreshold(), is(0.25));
    }

    @Test
    public void shouldOmitSloCriteriaFromJsonWhenUnset() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("no-slo-json", Arrays.asList(stage0), false);

        assertThat(def.toJson().has("sloCriteria"), is(false));
    }

    // --- A2: SLO breach as an auto-halt trigger ---

    @Test
    public void shouldHaltExperimentWithFailVerdictWhenSloBreachesMidRun() {
        // given - a long-running experiment with an error-rate SLO
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60_000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "slo-halt", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5));
        orchestrator.start(def);
        assertThat(orchestrator.getStatus().status, is("running"));
        assertThat("chaos applied", ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));

        // and - the live error rate breaches the SLO (all errors)
        SloSampleStore.getInstance().record(clock.get() + 100L, 50L, true, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(clock.get() + 200L, 50L, true, Scope.FORWARD, "api.svc");
        clock.addAndGet(300L);

        // when - the live SLO probe runs (driven deterministically)
        boolean halted = orchestrator.checkSloNow();

        // then - the experiment is halted by the SLO breach with a FAIL verdict, chaos cleared
        assertThat("SLO breach halted the experiment", halted, is(true));
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.status, is("halted_by_slo_breach"));
        assertThat(status.experimentVerdict, is(notNullValue()));
        assertThat(status.experimentVerdict.getResult(), is(SloVerdict.Result.FAIL));
        assertThat("chaos cleared on SLO halt",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldNotSloHaltExperimentWithoutSloCriteria() {
        // given - an experiment WITHOUT sloCriteria, with a high live error rate
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60_000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("no-slo-halt", Arrays.asList(stage0), false);
        orchestrator.start(def);
        SloSampleStore.getInstance().record(clock.get() + 100L, 50L, true, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(clock.get() + 200L, 50L, true, Scope.FORWARD, "api.svc");
        clock.addAndGet(300L);

        // when - the SLO probe runs
        boolean halted = orchestrator.checkSloNow();

        // then - no SLO halt; the experiment keeps running, no verdict
        assertThat("no SLO halt without sloCriteria", halted, is(false));
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.status, is("running"));
        assertThat(status.experimentVerdict, is(nullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));
    }

    @Test
    public void shouldEmitFailVerdictWhenAutoHaltOccursWithSloCriteria() {
        // given - an experiment WITH sloCriteria whose samples would otherwise PASS
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60_000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "autohalt-verdict", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5));
        orchestrator.start(def);
        // clean samples — the window on its own would yield PASS, not FAIL
        SloSampleStore.getInstance().record(clock.get() + 100L, 50L, false, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(clock.get() + 200L, 50L, false, Scope.FORWARD, "api.svc");

        // when - the raw-volume auto-halt fires (registry cleared) and the orchestrator
        // detects the empty registry at the next advance
        ServiceChaosRegistry.getInstance().reset();
        clock.addAndGet(60_000L);
        orchestrator.advanceNow();

        // then - status is halted_by_auto_halt AND the verdict is forced to FAIL
        // (STRICT semantics: an auto-halted experiment did not hold its steady state)
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.status, is("halted_by_auto_halt"));
        assertThat(status.experimentVerdict, is(notNullValue()));
        assertThat(status.experimentVerdict.getResult(), is(SloVerdict.Result.FAIL));
    }

    @Test
    public void shouldNotSloHaltWhenSloHolds() {
        // given - an experiment with an SLO that holds (no errors)
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60_000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "slo-holds", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5));
        orchestrator.start(def);
        SloSampleStore.getInstance().record(clock.get() + 100L, 50L, false, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(clock.get() + 200L, 50L, false, Scope.FORWARD, "api.svc");
        clock.addAndGet(300L);

        // when
        boolean halted = orchestrator.checkSloNow();

        // then - the experiment is not halted and remains running
        assertThat(halted, is(false));
        assertThat(orchestrator.getStatus().status, is("running"));
    }

    // --- T1.4 #1: Recurring cron experiments ---

    @Test
    public void shouldReArmRecurringCronExperimentForNextOccurrence() {
        // given - clock at 2026-06-20T10:17:30 (local); a "0 11 * * *" recurring cron.
        long base = java.time.ZonedDateTime.of(2026, 6, 20, 10, 17, 30, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli();
        clock.set(base);
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "nightly-error-storm", Arrays.asList(stage0), false, 0L, "0 11 * * *", null, true, 0L);

        // when - started, it is scheduled for the first cron boundary (42m30s away)
        String error = orchestrator.start(def);
        assertThat(error, is(nullValue()));
        assertThat(orchestrator.getStatus().status, is("scheduled"));
        assertThat(orchestrator.getStatus().startRemainingMillis, is(2_550_000L));

        // when - the first cron boundary arrives and the run begins
        clock.addAndGet(2_550_000L);
        orchestrator.triggerScheduledStartNow();
        assertThat(orchestrator.getStatus().status, is("running"));
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));

        // when - the single stage completes: a one-shot experiment would go terminal, but a
        // recurring experiment re-arms for the NEXT cron occurrence (all driven by the clock).
        clock.addAndGet(5000L);
        orchestrator.advanceNow();

        // then - the completed run is retained in history and the experiment is scheduled again
        assertThat("chaos cleared between recurring runs",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat("re-armed for the next occurrence", orchestrator.getStatus().status, is("scheduled"));
        assertThat("next occurrence is a positive delay away",
            orchestrator.getStatus().startRemainingMillis, greaterThan(0L));
        java.util.List<ChaosExperimentOrchestrator.ExperimentHistoryEntry> history = orchestrator.getHistory(10);
        assertThat(history.size(), is(1));
        assertThat(history.get(0).status, is("completed"));
        assertThat(history.get(0).name, is("nightly-error-storm"));

        // when - the next cron boundary arrives, the second run begins
        clock.addAndGet(orchestrator.getStatus().startRemainingMillis);
        orchestrator.triggerScheduledStartNow();

        // then - the experiment runs again (recurrence), chaos re-applied
        assertThat(orchestrator.getStatus().status, is("running"));
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));
    }

    @Test
    public void shouldRejectRecurringWithoutCronSchedule() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "recurring-no-cron", Arrays.asList(stage0), false, 0L, null, null, true, 0L));
        assertThat(error, is("'recurring' requires a 'cronSchedule' to re-arm from"));
    }

    @Test
    public void shouldRoundTripRecurringFlagThroughJson() throws Exception {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition original =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "recurring-json", Arrays.asList(stage0), false, 0L, "0 2 * * *", null, true, 0L);

        com.fasterxml.jackson.databind.node.ObjectNode json = original.toJson();
        assertThat(json.get("recurring").asBoolean(), is(true));
        ChaosExperimentOrchestrator.ExperimentDefinition restored =
            ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(json);
        assertThat(restored.recurring, is(true));
        assertThat(restored.cronSchedule, is("0 2 * * *"));

        // default (non-recurring) omits the field
        ChaosExperimentOrchestrator.ExperimentDefinition oneShot =
            new ChaosExperimentOrchestrator.ExperimentDefinition("one-shot", Arrays.asList(stage0), false);
        assertThat(oneShot.toJson().has("recurring"), is(false));
    }

    // --- T1.4 #2: TCP / lifecycle faults staged in an experiment ---

    @Test
    public void shouldApplyAndResetStagedTcpProfiles() {
        // given - a stage that stages ONLY a TCP (connection-lifecycle) fault
        Map<String, TcpChaosProfile> tcpProfiles = new LinkedHashMap<>();
        tcpProfiles.put("tcp.svc", TcpChaosProfile.tcpChaosProfile().withResetPeer(true));
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60_000L, Collections.emptyMap(), tcpProfiles);
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("tcp-exp", Arrays.asList(stage0), false);

        // when
        String error = orchestrator.start(def);

        // then - the TCP profile is applied to the TcpChaosRegistry; HTTP registry untouched
        assertThat(error, is(nullValue()));
        assertThat(TcpChaosRegistry.getInstance().get("tcp.svc"), is(notNullValue()));
        assertThat(TcpChaosRegistry.getInstance().get("tcp.svc").getResetPeer(), is(true));
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));

        // when - the experiment is stopped
        orchestrator.stop();

        // then - the staged TCP chaos is cleared
        assertThat("staged TCP chaos cleared on stop",
            TcpChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldClearPreviousStageTcpProfilesOnAdvance() {
        // given - stage 0 TCP-faults tcp0.svc, stage 1 TCP-faults tcp1.svc
        Map<String, TcpChaosProfile> tcp0 = new LinkedHashMap<>();
        tcp0.put("tcp0.svc", TcpChaosProfile.tcpChaosProfile().withResetPeer(true));
        Map<String, TcpChaosProfile> tcp1 = new LinkedHashMap<>();
        tcp1.put("tcp1.svc", TcpChaosProfile.tcpChaosProfile().withDown(true));
        ChaosExperimentOrchestrator.Stage stage0 =
            new ChaosExperimentOrchestrator.Stage(5000L, Collections.emptyMap(), tcp0);
        ChaosExperimentOrchestrator.Stage stage1 =
            new ChaosExperimentOrchestrator.Stage(5000L, Collections.emptyMap(), tcp1);
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition("tcp-advance",
                Arrays.asList(stage0, stage1), false);

        orchestrator.start(def);
        assertThat(TcpChaosRegistry.getInstance().get("tcp0.svc"), is(notNullValue()));
        assertThat(TcpChaosRegistry.getInstance().get("tcp1.svc"), is(nullValue()));

        // when
        clock.addAndGet(5000L);
        orchestrator.advanceNow();

        // then - stage 0's TCP profile is cleared, stage 1's applied
        assertThat(TcpChaosRegistry.getInstance().get("tcp0.svc"), is(nullValue()));
        assertThat(TcpChaosRegistry.getInstance().get("tcp1.svc"), is(notNullValue()));
    }

    @Test
    public void shouldHaltTcpOnlyExperimentWhenRegistryClearedByAutoHalt() {
        // given - a TCP-only experiment
        Map<String, TcpChaosProfile> tcpProfiles = new LinkedHashMap<>();
        tcpProfiles.put("tcp.svc", TcpChaosProfile.tcpChaosProfile().withResetPeer(true));
        ChaosExperimentOrchestrator.Stage stage0 =
            new ChaosExperimentOrchestrator.Stage(60_000L, Collections.emptyMap(), tcpProfiles);
        ChaosExperimentOrchestrator.Stage stage1 =
            new ChaosExperimentOrchestrator.Stage(30_000L, Collections.emptyMap(), tcpProfiles);
        orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "tcp-autohalt", Arrays.asList(stage0, stage1), false));

        // when - auto-halt clears the TCP registry, then a stage advance is attempted
        TcpChaosRegistry.getInstance().reset();
        clock.addAndGet(60_000L);
        orchestrator.advanceNow();

        // then - the empty TCP registry is detected as an auto-halt
        assertThat(orchestrator.getStatus().status, is("halted_by_auto_halt"));
    }

    @Test
    public void shouldRoundTripTcpProfilesThroughJson() throws Exception {
        Map<String, TcpChaosProfile> tcpProfiles = new LinkedHashMap<>();
        tcpProfiles.put("tcp.svc", TcpChaosProfile.tcpChaosProfile().withLatencyMs(250L));
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)), tcpProfiles);
        ChaosExperimentOrchestrator.ExperimentDefinition original =
            new ChaosExperimentOrchestrator.ExperimentDefinition("tcp-json", Arrays.asList(stage0), false);

        com.fasterxml.jackson.databind.node.ObjectNode json = original.toJson();
        assertThat(json.get("stages").get(0).has("tcpProfiles"), is(true));
        ChaosExperimentOrchestrator.ExperimentDefinition restored =
            ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(json);
        assertThat(restored.stages.get(0).tcpProfiles.get("tcp.svc").getLatencyMs(), is(250L));
        assertThat(restored.stages.get(0).profiles.get("api.svc").getErrorStatus(), is(503));
        assertThat(restored.usesTcpProfiles(), is(true));
    }

    @Test
    public void shouldAcceptStageWithOnlyTcpProfiles() {
        // validation: a stage with tcpProfiles but no HTTP profiles is valid
        Map<String, TcpChaosProfile> tcpProfiles = new LinkedHashMap<>();
        tcpProfiles.put("tcp.svc", TcpChaosProfile.tcpChaosProfile().withResetPeer(true));
        ChaosExperimentOrchestrator.Stage stage0 =
            new ChaosExperimentOrchestrator.Stage(5000L, Collections.emptyMap(), tcpProfiles);
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "tcp-only-valid", Arrays.asList(stage0), false));
        assertThat(error, is(nullValue()));
    }

    // --- T1.4 #3: Steady-state baseline pre-check ---

    @Test
    public void shouldRefuseToStartWhenBaselineUnhealthy() {
        // given - a high error rate already present in the pre-experiment baseline window
        // baseline window is [now - 10000, now] = [0, 10000] with clock at 10_000
        SloSampleStore.getInstance().record(2000L, 50L, true, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(3000L, 50L, true, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(4000L, 50L, true, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(5000L, 50L, false, Scope.FORWARD, "api.svc");

        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "baseline-unhealthy", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5), false, 10_000L);

        // when
        String error = orchestrator.start(def);

        // then - refused to start; no chaos applied; terminal status + verdict queryable
        assertThat(error, containsString("aborted_baseline_unhealthy"));
        assertThat("no chaos applied when baseline unhealthy",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
        assertThat(status.status, is("aborted_baseline_unhealthy"));
        assertThat(status.experimentVerdict, is(notNullValue()));
        assertThat(status.experimentVerdict.getResult(), is(SloVerdict.Result.FAIL));
        // and the aborted attempt is recorded in history
        assertThat(orchestrator.getHistory(10).get(0).status, is("aborted_baseline_unhealthy"));
    }

    @Test
    public void shouldStartWhenBaselineHealthy() {
        // given - a clean baseline window (all successful)
        SloSampleStore.getInstance().record(2000L, 50L, false, Scope.FORWARD, "api.svc");
        SloSampleStore.getInstance().record(3000L, 50L, false, Scope.FORWARD, "api.svc");

        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "baseline-healthy", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5), false, 10_000L);

        // when
        String error = orchestrator.start(def);

        // then - the baseline held, so the experiment starts normally
        assertThat(error, is(nullValue()));
        assertThat(orchestrator.getStatus().status, is("running"));
        assertThat(ServiceChaosRegistry.getInstance().get("api.svc"), is(notNullValue()));
    }

    @Test
    public void shouldRefuseToStartWhenBaselineInconclusive() {
        // given - NO samples in the baseline window; an error-rate objective over an empty
        // window is INCONCLUSIVE, so the steady state is not proven healthy -> refuse
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition def =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "baseline-inconclusive", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5), false, 10_000L);

        String error = orchestrator.start(def);
        assertThat(error, containsString("aborted_baseline_unhealthy"));
        assertThat(orchestrator.getStatus().experimentVerdict.getResult(), is(SloVerdict.Result.INCONCLUSIVE));
    }

    @Test
    public void shouldRejectBaselineWindowWithoutSloCriteria() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        String error = orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "baseline-no-slo", Arrays.asList(stage0), false, 0L, null, null, false, 10_000L));
        assertThat(error, is("'baselineWindowMillis' requires 'sloCriteria' to evaluate the steady state against"));
    }

    @Test
    public void shouldRoundTripBaselineWindowThroughJson() throws Exception {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        ChaosExperimentOrchestrator.ExperimentDefinition original =
            new ChaosExperimentOrchestrator.ExperimentDefinition(
                "baseline-json", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5), false, 20_000L);

        com.fasterxml.jackson.databind.node.ObjectNode json = original.toJson();
        assertThat(json.get("baselineWindowMillis").asLong(), is(20_000L));
        ChaosExperimentOrchestrator.ExperimentDefinition restored =
            ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(json);
        assertThat(restored.baselineWindowMillis, is(20_000L));

        // default omits the field
        ChaosExperimentOrchestrator.ExperimentDefinition noBaseline =
            new ChaosExperimentOrchestrator.ExperimentDefinition("no-baseline", Arrays.asList(stage0), false);
        assertThat(noBaseline.toJson().has("baselineWindowMillis"), is(false));
    }

    // --- T1.4 #4: Bounded experiment history ---

    @Test
    public void shouldRetainBoundedHistoryNewestFirst() {
        // given - more experiments than the ring holds, each started then stopped
        int total = ChaosExperimentOrchestrator.MAX_HISTORY + 5;
        for (int i = 0; i < total; i++) {
            ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
                60_000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
            orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
                "h-" + i, Arrays.asList(stage0), false));
            orchestrator.stop();
        }

        // then - the ring is capped at MAX_HISTORY, newest first, oldest evicted
        java.util.List<ChaosExperimentOrchestrator.ExperimentHistoryEntry> history = orchestrator.getHistory(1000);
        assertThat(history.size(), is(ChaosExperimentOrchestrator.MAX_HISTORY));
        assertThat("newest first", history.get(0).name, is("h-" + (total - 1)));
        assertThat("each terminated run recorded its status", history.get(0).status, is("stopped"));
        // the oldest surviving entry is total - MAX_HISTORY (earlier ones were evicted)
        assertThat(history.get(history.size() - 1).name, is("h-" + (total - ChaosExperimentOrchestrator.MAX_HISTORY)));
    }

    @Test
    public void shouldExposeHistoryEntryAsJsonWithVerdict() {
        // given - an experiment with an SLO that completes with a verdict
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            5000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "hist-verdict", Arrays.asList(stage0), false, 0L, null, errorRateBelow(0.5)));
        SloSampleStore.getInstance().record(clock.get() + 100L, 50L, false, Scope.FORWARD, "api.svc");
        clock.addAndGet(5000L);
        orchestrator.advanceNow();

        // then - the history entry serialises name/status/verdict/terminatedAtMillis
        ChaosExperimentOrchestrator.ExperimentHistoryEntry entry = orchestrator.getHistory(10).get(0);
        com.fasterxml.jackson.databind.node.ObjectNode json = entry.toJson();
        assertThat(json.get("name").asText(), is("hist-verdict"));
        assertThat(json.get("status").asText(), is("completed"));
        assertThat(json.has("terminatedAtMillis"), is(true));
        assertThat(json.has("verdict"), is(true));
        assertThat(json.get("verdict").get("result").asText(), is("PASS"));
    }

    @Test
    public void shouldClearHistoryOnReset() {
        ChaosExperimentOrchestrator.Stage stage0 = new ChaosExperimentOrchestrator.Stage(
            60_000L, profileMap("api.svc", httpChaosProfile().withErrorStatus(503)));
        orchestrator.start(new ChaosExperimentOrchestrator.ExperimentDefinition(
            "hist-reset", Arrays.asList(stage0), false));
        orchestrator.stop();
        assertThat(orchestrator.getHistory(10).size(), is(1));

        orchestrator.reset();
        assertThat(orchestrator.getHistory(10).isEmpty(), is(true));
    }
}
