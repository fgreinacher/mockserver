package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

import static org.mockserver.model.HttpResponse.response;

/**
 * Cost-budget circuit-breaker for LLM forwarding. When the cumulative LLM cost
 * (tracked by the {@code mock_server_llm_cost_usd} metric) exceeds the
 * configured budget ({@code mockserver.llmCostBudgetUsd}), further LLM
 * forwarding is halted with a 429 response and a WARN log.
 *
 * <p>The monitor is deterministic and fail-open: a negative, unset, or
 * malformed budget never blocks traffic. It is reset by
 * {@link org.mockserver.mock.HttpState#reset()}.
 *
 * <p>Unlike the chaos auto-halt which removes registrations, this breaker
 * returns a clear error response rather than modifying state — the user
 * explicitly set a cost ceiling, so we respect it per-request.
 *
 * <p>The singleton pattern matches {@link ChaosAutoHaltMonitor} and
 * {@link ServiceChaosRegistry}.
 */
public class LlmCostBudgetMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(LlmCostBudgetMonitor.class);

    private static final LlmCostBudgetMonitor INSTANCE = new LlmCostBudgetMonitor();

    /**
     * Running cumulative cost tracked independently of the Prometheus counter
     * so the budget works even when metricsEnabled is false. Stored as
     * micro-USD (USD * 1e6) in a long for lock-free atomic adds.
     */
    private final AtomicLong cumulativeCostMicroUsd = new AtomicLong(0L);

    /** Counter of times the budget breaker has tripped (for metrics/testing). */
    private final AtomicLong tripCount = new AtomicLong(0L);

    private LlmCostBudgetMonitor() {
    }

    public static LlmCostBudgetMonitor getInstance() {
        return INSTANCE;
    }

    /**
     * Record a cost increment. Called after a completion is served or forwarded.
     *
     * @param costUsd the estimated cost in USD (null or non-positive is ignored)
     */
    public void recordCost(Double costUsd) {
        if (costUsd == null || costUsd <= 0.0) {
            return;
        }
        long microUsd = (long) (costUsd * 1_000_000.0);
        cumulativeCostMicroUsd.addAndGet(microUsd);
    }

    /**
     * The effective budget: the {@link Configuration} instance value when one is supplied,
     * otherwise the static {@link ConfigurationProperties} store. The instance is preferred so a
     * budget set programmatically or via {@code PUT /mockserver/configuration} is enforced;
     * the static fallback keeps system-property/env/file users unaffected.
     */
    private static double budgetUsd(Configuration configuration) {
        return configuration != null
            ? configuration.llmCostBudgetUsd()
            : ConfigurationProperties.llmCostBudgetUsd();
    }

    /**
     * Check whether the cost budget is exceeded. Returns {@code true} if the
     * budget is configured (positive) and the cumulative cost has exceeded it.
     * Returns {@code false} if the budget is unset, negative, or not yet
     * exceeded — fail-open on misconfig.
     * <p>
     * Prefer {@link #isBudgetExceeded(Configuration)} wherever a {@link Configuration} is in
     * scope: this overload consults only the static store.
     */
    public boolean isBudgetExceeded() {
        return isBudgetExceeded(null);
    }

    /**
     * @param configuration the effective server configuration (may be {@code null})
     * @see #isBudgetExceeded()
     */
    public boolean isBudgetExceeded(Configuration configuration) {
        double budgetUsd = budgetUsd(configuration);
        if (budgetUsd <= 0.0) {
            return false; // disabled / fail-open
        }
        long budgetMicroUsd = (long) (budgetUsd * 1_000_000.0);
        return cumulativeCostMicroUsd.get() >= budgetMicroUsd;
    }

    /**
     * Check the budget and return an error response if exceeded, or {@code null}
     * if the request should proceed. This is the choke-point guard called before
     * LLM forwarding.
     * <p>
     * Prefer {@link #checkBudgetOrNull(Configuration)} wherever a {@link Configuration} is in
     * scope: this overload consults only the static store.
     */
    public org.mockserver.model.HttpResponse checkBudgetOrNull() {
        return checkBudgetOrNull(null);
    }

    /**
     * @param configuration the effective server configuration (may be {@code null})
     * @see #checkBudgetOrNull()
     */
    public org.mockserver.model.HttpResponse checkBudgetOrNull(Configuration configuration) {
        if (!isBudgetExceeded(configuration)) {
            return null;
        }
        tripCount.incrementAndGet();
        double cumulativeUsd = cumulativeCostMicroUsd.get() / 1_000_000.0;
        double budgetUsd = budgetUsd(configuration);
        LOG.warn(
            "LLM cost budget exceeded: cumulative ${} >= budget ${} — blocking LLM forward",
            String.format("%.6f", cumulativeUsd),
            String.format("%.6f", budgetUsd)
        );
        Metrics.incrementLlmCostBudgetTripped();
        return response()
            .withStatusCode(429)
            .withHeader("content-type", "application/json")
            .withBody("{\"error\":{\"type\":\"cost_budget_exceeded\","
                + "\"message\":\"LLM cost budget exceeded (cumulative $"
                + String.format("%.4f", cumulativeUsd)
                + " >= budget $" + String.format("%.4f", budgetUsd) + ")\","
                + "\"cumulative_cost_usd\":" + String.format("%.6f", cumulativeUsd) + ","
                + "\"budget_usd\":" + String.format("%.6f", budgetUsd) + "}}");
    }

    /**
     * Return the cumulative cost in USD.
     */
    public double getCumulativeCostUsd() {
        return cumulativeCostMicroUsd.get() / 1_000_000.0;
    }

    /**
     * Return the number of times the budget breaker has tripped.
     */
    public long getTripCount() {
        return tripCount.get();
    }

    /**
     * Reset the monitor state. Called on server reset.
     */
    public void reset() {
        cumulativeCostMicroUsd.set(0L);
        tripCount.set(0L);
    }
}
