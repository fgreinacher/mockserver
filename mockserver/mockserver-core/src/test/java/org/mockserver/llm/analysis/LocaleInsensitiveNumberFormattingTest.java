package org.mockserver.llm.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange;
import org.mockserver.mock.action.http.LlmCostBudgetMonitor;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Regression guard for locale-sensitive number formatting (comma-decimal locales).
 * <p>
 * {@code String.format("%.6f", x)} without an explicit {@link Locale} renders with
 * the JVM default locale's decimal separator. On a comma-decimal locale (de, fr,
 * es, it, pt-BR, ru — a Docker container inherits the host/env locale) {@code %.2f}
 * renders {@code 1,50} rather than {@code 1.50}. The severe instance is the
 * {@link LlmCostBudgetMonitor} 429 body, which hand-builds JSON: a comma decimal
 * would emit {@code "cumulative_cost_usd":1,500000,} — syntactically invalid JSON —
 * so clients get a parse error instead of a structured budget signal.
 * <p>
 * This test forces {@link Locale#GERMANY} as the default so it genuinely exercises
 * the "no locale argument passed" defect: a test that passed an explicit locale to a
 * helper would not catch a regression. Because it mutates the JVM-global default
 * locale it is a global-state test and is registered in BOTH the parallel-excludes
 * and the sequential-includes lists in {@code mockserver-core/pom.xml}.
 */
public class LocaleInsensitiveNumberFormattingTest {

    private Locale originalLocale;
    private double originalBudget;

    @Before
    public void setCommaDecimalLocale() {
        originalLocale = Locale.getDefault();
        // A comma-decimal locale, so an un-localised %.Nf would render "1,50" not "1.50".
        Locale.setDefault(Locale.GERMANY);
        originalBudget = ConfigurationProperties.llmCostBudgetUsd();
        LlmCostBudgetMonitor.getInstance().reset();
    }

    @After
    public void restore() {
        // Restore in a finally-safe order so a failure above cannot leak the mutated
        // default locale into other tests in the sequential phase.
        try {
            ConfigurationProperties.llmCostBudgetUsd(originalBudget);
            LlmCostBudgetMonitor.getInstance().reset();
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    public void costBudget429BodyIsValidJsonWithNumericCostUnderCommaDecimalLocale() throws Exception {
        // given - a $1.00 budget that is then exceeded by $1.50 of recorded cost
        ConfigurationProperties.llmCostBudgetUsd(1.0);
        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();
        monitor.recordCost(1.5);

        // when - the breaker trips and hand-builds the 429 JSON body
        HttpResponse errorResponse = monitor.checkBudgetOrNull();
        assertThat(errorResponse, is(notNullValue()));
        assertThat(errorResponse.getStatusCode(), is(429));

        // then - the body must PARSE as valid JSON even under a comma-decimal default locale
        // (before the fix this is "...\"cumulative_cost_usd\":1,500000,..." and readTree throws)
        String body = errorResponse.getBodyAsString();
        JsonNode error = new ObjectMapper().readTree(body).get("error");
        assertThat("error object present", error, is(notNullValue()));

        // ...and cumulative_cost_usd must be a JSON NUMBER with the expected value, not a
        // string or a broken token — that directly encodes the real-world failure.
        JsonNode cost = error.get("cumulative_cost_usd");
        assertThat("cumulative_cost_usd present", cost, is(notNullValue()));
        assertThat("cumulative_cost_usd must be a JSON number: " + body, cost.isNumber(), is(true));
        assertThat(cost.asDouble(), is(closeTo(1.5, 1e-9)));

        JsonNode budget = error.get("budget_usd");
        assertThat("budget_usd present", budget, is(notNullValue()));
        assertThat("budget_usd must be a JSON number: " + body, budget.isNumber(), is(true));
        assertThat(budget.asDouble(), is(closeTo(1.0, 1e-9)));

        // The human-readable message keeps a '.' decimal too.
        assertThat(body, containsString("cumulative $1.5000"));
    }

    @Test
    public void optimisationReportSavingTextUsesDotDecimalUnderCommaDecimalLocale() {
        // given - two identical priced calls -> the verdict rationale renders a "$x.xx" saving
        List<CapturedExchange> exchanges = Arrays.asList(
            new CapturedExchange(
                openAiRequest("gpt-4o-2024-08-06", "You are a helpful assistant with a long static brief.", "Weather in Paris?"),
                usageResponse("gpt-4o-2024-08-06", 8120, 540, "tool_calls"), 2300L),
            new CapturedExchange(
                openAiRequest("gpt-4o-2024-08-06", "You are a helpful assistant with a long static brief.", "And in London?"),
                usageResponse("gpt-4o-2024-08-06", 8200, 480, "stop"), 1900L));

        // when
        LlmOptimisationReport report = new LlmOptimisationReportBuilder().build(
            exchanges, "host:api.openai.com",
            LlmOptimisationReport.GroupingBasis.PROXY_HOST,
            Collections.emptyList(), Collections.emptyList());
        String rationale = report.getVerdict().getRationale();

        // then - we actually reached the String.format branch (findings present, so it is not
        // the empty "No optimisation opportunities detected." message)
        assertThat("rationale should reference a dollar saving: " + rationale,
            rationale.contains("$"), is(true));
        // the money must be rendered with a '.' decimal separator...
        assertThat("rationale should contain a dot-decimal dollar amount: " + rationale,
            Pattern.compile("\\$\\d+\\.\\d{2}").matcher(rationale).find(), is(true));
        // ...and never a comma-decimal one (before the fix this is "$0,30" under GERMANY)
        assertThat("rationale must not contain a comma-decimal dollar amount: " + rationale,
            Pattern.compile("\\$\\d+,\\d{2}").matcher(rationale).find(), is(false));
    }

    private static HttpRequest openAiRequest(String model, String systemPrompt, String userText) {
        return request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")
            .withBody("{\"model\":\"" + model + "\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + systemPrompt + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + userText + "\"}],"
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"description\":\"Get weather\"}}]}");
    }

    private static HttpResponse usageResponse(String model, int in, int out, String finish) {
        return response().withStatusCode(200)
            .withBody("{\"model\":\"" + model + "\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + "}}");
    }
}
