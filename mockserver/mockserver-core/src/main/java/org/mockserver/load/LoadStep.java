package org.mockserver.load;

import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.ObjectWithJsonToString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One ordered step of a {@link LoadScenario}: the request to fire and the optional
 * think-time to wait before firing the <em>next</em> step in the same iteration.
 *
 * <p>The request reuses {@link HttpRequest}; per-iteration template placeholders
 * (e.g. {@code $iteration.index}, {@code $uuid}) live in its fields (path, body,
 * headers, query) and are rendered fresh each iteration by the orchestrator using
 * the scenario's {@code templateType}.
 *
 * <p>{@code thinkTime} is inter-step pacing only — the orchestrator schedules the
 * next step after {@code thinkTime.sampleValueMillis()} on its scheduler thread and
 * never calls {@link Delay#applyDelay()} (which would block a worker thread). A null
 * {@code thinkTime} means no pause.
 *
 * <p>Cross-step correlation is declarative: {@link #getCaptures()} extracts values from this step's
 * response into the iteration's mutable captured-variable map, which a subsequent step references from
 * its templated fields via {@code $iteration.captured.<name>}. See {@link LoadCapture} for scope.
 */
public class LoadStep extends ObjectWithJsonToString {

    private HttpRequest request;
    private Delay thinkTime;
    /**
     * Optional human label for this step. When set it is used as the {@code step} metric label
     * (otherwise the step index is used) and may be used as the low-cardinality {@code route} label
     * in place of a templatised request path.
     */
    private String name;
    /**
     * Step-level custom annotation labels. Merged on top of the scenario's labels for this step
     * (step keys win on conflict). See {@link LoadScenario#getLabels()} for how they are exported.
     */
    private Map<String, String> labels;
    /**
     * Cross-step capture rules applied to this step's response. Each binds an extracted value to a
     * variable name visible to subsequent steps in the same iteration. See {@link LoadCapture}.
     */
    private List<LoadCapture> captures;
    /**
     * Optional per-step response assertions (the load equivalent of k6 {@code check}s). Each check
     * extracts a value from this step's response and compares it against an expected value; the
     * pass/fail outcome feeds the {@code mock_server_load_checks} metric, the end-of-run report, and
     * the {@link LoadThreshold.Metric#CHECK_FAILURE_RATE} threshold. Observational — a failing check
     * never fails an individual request. Null/empty (the default) leaves behaviour unchanged. See
     * {@link LoadCheck}.
     */
    private List<LoadCheck> checks;
    /**
     * Relative selection weight, used only when the scenario's
     * {@link LoadScenario#getStepSelection() stepSelection} is
     * {@link LoadScenario.StepSelection#WEIGHTED WEIGHTED}: each iteration runs exactly ONE step chosen
     * at random with probability proportional to its weight (e.g. weights {@code 7 / 2 / 1} model a
     * 70% / 20% / 10% mixed workload). Nullable — an absent weight is treated as {@code 1.0} in
     * WEIGHTED mode; a non-positive weight is rejected by validation. Ignored entirely under the default
     * {@link LoadScenario.StepSelection#SEQUENTIAL SEQUENTIAL} mode (all steps run in order regardless
     * of any weights present).
     */
    private Double weight;

    public static LoadStep loadStep() {
        return new LoadStep();
    }

    public static LoadStep loadStep(HttpRequest request) {
        return new LoadStep().withRequest(request);
    }

    public HttpRequest getRequest() {
        return request;
    }

    public LoadStep withRequest(HttpRequest request) {
        this.request = request;
        return this;
    }

    public Delay getThinkTime() {
        return thinkTime;
    }

    public LoadStep withThinkTime(Delay thinkTime) {
        this.thinkTime = thinkTime;
        return this;
    }

    /**
     * Optional human label for this step (may be null). See {@link #name}.
     */
    public String getName() {
        return name;
    }

    public LoadStep withName(String name) {
        this.name = name;
        return this;
    }

    /**
     * Step-level custom labels (may be null/empty). See {@link #labels}.
     */
    public Map<String, String> getLabels() {
        return labels;
    }

    public LoadStep withLabels(Map<String, String> labels) {
        this.labels = labels != null ? new LinkedHashMap<>(labels) : null;
        return this;
    }

    public LoadStep withLabel(String name, String value) {
        if (this.labels == null) {
            this.labels = new LinkedHashMap<>();
        }
        this.labels.put(name, value);
        return this;
    }

    /**
     * Cross-step capture rules for this step (may be null/empty). See {@link #captures}.
     */
    public List<LoadCapture> getCaptures() {
        return captures;
    }

    public LoadStep withCaptures(List<LoadCapture> captures) {
        this.captures = captures != null ? new ArrayList<>(captures) : null;
        return this;
    }

    public LoadStep withCapture(LoadCapture capture) {
        if (this.captures == null) {
            this.captures = new ArrayList<>();
        }
        this.captures.add(capture);
        return this;
    }

    /**
     * Per-step response assertions for this step (may be null/empty). See {@link #checks}.
     */
    public List<LoadCheck> getChecks() {
        return checks;
    }

    public LoadStep withChecks(List<LoadCheck> checks) {
        this.checks = checks != null ? new ArrayList<>(checks) : null;
        return this;
    }

    public LoadStep withCheck(LoadCheck check) {
        if (this.checks == null) {
            this.checks = new ArrayList<>();
        }
        this.checks.add(check);
        return this;
    }

    /**
     * Relative selection weight for WEIGHTED step selection (may be null = treated as 1.0). See {@link #weight}.
     */
    public Double getWeight() {
        return weight;
    }

    public LoadStep withWeight(Double weight) {
        this.weight = weight;
        return this;
    }
}
