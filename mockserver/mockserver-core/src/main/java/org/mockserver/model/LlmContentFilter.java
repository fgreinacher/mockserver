package org.mockserver.model;

import java.util.Objects;

/**
 * Azure OpenAI content-filter severities for the four core harm categories
 * ({@code hate}, {@code sexual}, {@code violence}, {@code self_harm}). Azure
 * annotates every completion with these results — {@code content_filter_results}
 * on each choice and {@code prompt_filter_results} at the top level — which agents
 * read to detect when a response was filtered.
 * <p>
 * Each category carries a severity of {@code safe}, {@code low}, {@code medium} or
 * {@code high} (default {@code safe}). Azure's default policy filters content at
 * {@code medium} or {@code high}, so the derived {@code filtered} flag is true at
 * those severities. Used both to annotate a normal Azure response and, on the chaos
 * path, to shape a content-filter block.
 * <p>
 * Additive/opt-in — an absent {@link LlmContentFilter} leaves Azure responses
 * unchanged. Follows the model field/{@code withX}/getter convention so it
 * round-trips through the schema-validated expectation JSON.
 */
public class LlmContentFilter extends ObjectWithJsonToString {

    public static final String SAFE = "safe";
    public static final String LOW = "low";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";

    private int hashCode;
    private String hate;
    private String sexual;
    private String violence;
    private String selfHarm;

    public static LlmContentFilter llmContentFilter() {
        return new LlmContentFilter();
    }

    public LlmContentFilter withHate(String hate) {
        this.hate = hate;
        this.hashCode = 0;
        return this;
    }

    public String getHate() {
        return hate;
    }

    public LlmContentFilter withSexual(String sexual) {
        this.sexual = sexual;
        this.hashCode = 0;
        return this;
    }

    public String getSexual() {
        return sexual;
    }

    public LlmContentFilter withViolence(String violence) {
        this.violence = violence;
        this.hashCode = 0;
        return this;
    }

    public String getViolence() {
        return violence;
    }

    public LlmContentFilter withSelfHarm(String selfHarm) {
        this.selfHarm = selfHarm;
        this.hashCode = 0;
        return this;
    }

    public String getSelfHarm() {
        return selfHarm;
    }

    /** Severity for a category, defaulting to {@code safe} when unset. */
    public static String severityOrSafe(String severity) {
        return severity == null || severity.isEmpty() ? SAFE : severity;
    }

    /** Azure filters at {@code medium} or {@code high}; everything else is delivered. */
    public static boolean isFilteredSeverity(String severity) {
        String s = severityOrSafe(severity);
        return MEDIUM.equalsIgnoreCase(s) || HIGH.equalsIgnoreCase(s);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        LlmContentFilter that = (LlmContentFilter) o;
        return Objects.equals(hate, that.hate) &&
            Objects.equals(sexual, that.sexual) &&
            Objects.equals(violence, that.violence) &&
            Objects.equals(selfHarm, that.selfHarm);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(hate, sexual, violence, selfHarm);
        }
        return hashCode;
    }
}
