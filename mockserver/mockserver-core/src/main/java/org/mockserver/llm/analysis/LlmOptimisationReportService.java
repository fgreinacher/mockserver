package org.mockserver.llm.analysis;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Application service that turns captured {@code REQUEST_RESPONSES} log entries
 * into an {@link LlmOptimisationReport} (JSON bundle) or a rendered Markdown
 * brief, applying the optional {@code session}/{@code host}/{@code provider}
 * filters, redaction, and the {@code mockserver.llmOptimisationMaxCalls} bound.
 * <p>
 * Lives in mockserver-core so both the control-plane REST endpoint and the
 * {@code export_optimisation_report} MCP tool (mockserver-netty) share one
 * implementation. Pure of transport: callers pass already-retrieved
 * request/response pairs.
 */
public class LlmOptimisationReportService {

    private final LlmOptimisationReportBuilder builder = new LlmOptimisationReportBuilder();
    private final LlmOptimisationBriefRenderer renderer = new LlmOptimisationBriefRenderer();
    private final LlmOptimisationCsvRenderer csvRenderer = new LlmOptimisationCsvRenderer();

    /** Optional filters; null/blank means "no filter". */
    public static final class Filter {
        private final String session;
        private final String host;
        private final String provider;

        public Filter(String session, String host, String provider) {
            this.session = blankToNull(session);
            this.host = blankToNull(host);
            this.provider = blankToNull(provider);
        }

        private static String blankToNull(String s) {
            return s == null || s.trim().isEmpty() ? null : s.trim();
        }
    }

    /** The built report plus the markdown brief, so callers render once. */
    public static final class Result {
        private final LlmOptimisationReport report;
        private final List<CapturedExchange> includedExchanges;

        Result(LlmOptimisationReport report, List<CapturedExchange> includedExchanges) {
            this.report = report;
            this.includedExchanges = includedExchanges;
        }

        public LlmOptimisationReport getReport() {
            return report;
        }

        public List<CapturedExchange> getIncludedExchanges() {
            return includedExchanges;
        }
    }

    /**
     * Build a report from the given recorded pairs and filter.
     */
    public Result build(List<LogEventRequestAndResponse> pairs, Filter filter) {
        Filter f = filter != null ? filter : new Filter(null, null, null);

        List<String> redactedBodyFields = bodyFields();
        List<String> redactedHeaders = new ArrayList<>(FixtureRedactor.defaultSensitiveHeaders());

        // Filter to LLM traffic matching the host/provider filter; collect exchanges + group key.
        List<CapturedExchange> exchanges = new ArrayList<>();
        String groupingKey = null;
        if (pairs != null) {
            for (LogEventRequestAndResponse pair : pairs) {
                if (pair == null || pair.getHttpRequest() == null) {
                    continue;
                }
                // Skip response-less entries. The LLM/SSE dispatch path writes an
                // informational request-only EXPECTATION_RESPONSE pre-log (no httpResponse)
                // alongside the real one, so counting both would double-count every mocked
                // LLM call and falsely trip DUPLICATE_CONSECUTIVE_CALL. The report only
                // summarises completed exchanges that carry a real response/usage.
                if (pair.getHttpResponse() == null) {
                    continue;
                }
                HttpRequest request = pair.getHttpRequest();
                // detectForAnalysis (not sniff) so MOCKED LLM traffic on localhost — e.g. the
                // demo, and any mocked conversations — is included, matching the Sessions view.
                // Pass the response so detection can fall back to the body shape (the resilient
                // signal) when an unknown host / non-standard path is used by a coding CLI.
                Optional<Provider> providerOpt =
                    LlmProviderSniffer.detectForAnalysis(request, pair.getHttpResponse());
                if (!providerOpt.isPresent()) {
                    continue; // not LLM traffic
                }
                Provider provider = providerOpt.get();
                String host = upstreamHost(request);

                if (f.host != null && (host == null || !host.equalsIgnoreCase(f.host))) {
                    continue;
                }
                if (f.provider != null && !provider.name().equalsIgnoreCase(f.provider)) {
                    continue;
                }
                String key = "host:" + (host != null ? host : "unknown");
                // Accept either the composite grouping key ("host:<host>") or the
                // bare host the dashboard's session picker sends. The picker may
                // include a port from the raw Host header, so strip it before
                // comparing (the server-side host is already port-stripped).
                if (f.session != null
                    && !f.session.equalsIgnoreCase(key)
                    && !(host != null && stripPort(f.session).equalsIgnoreCase(host))) {
                    continue;
                }
                if (groupingKey == null) {
                    groupingKey = key;
                }
                HttpResponse response = pair.getHttpResponse();
                // Per-call upstream latency is carried on the LOGGED forwarded response via the
                // internal x-mockserver-response-time-ms header (see HttpActionHandler.RESPONSE_TIME_HEADER).
                // It is never sent to the real client; only the event-log clone carries it.
                Long latencyMs = null;
                if (response != null) {
                    String h = response.getFirstHeader("x-mockserver-response-time-ms");
                    if (h != null && !h.isEmpty()) {
                        try {
                            latencyMs = Long.parseLong(h.trim());
                        } catch (NumberFormatException ignored) {
                            // malformed value: leave latency unset (report shows 0)
                        }
                    }
                }
                exchanges.add(new CapturedExchange(request, response, latencyMs));
            }
        }

        // Bound the report size: keep the most recent N calls.
        int maxCalls = ConfigurationProperties.llmOptimisationMaxCalls();
        if (maxCalls > 0 && exchanges.size() > maxCalls) {
            exchanges = new ArrayList<>(exchanges.subList(exchanges.size() - maxCalls, exchanges.size()));
        }

        if (groupingKey == null) {
            groupingKey = f.session != null ? f.session : "all";
        }

        LlmOptimisationReport report = builder.build(exchanges, groupingKey,
            LlmOptimisationReport.GroupingBasis.PROXY_HOST, redactedHeaders, redactedBodyFields);
        // Cache the latest verdict/totals figures so the optimisation Prometheus
        // gauges can read scrape-time-correct values without re-building the
        // (potentially large, log-retrieving) report on every scrape. Updated on
        // every build — REST, MCP, or an explicit refresh — so the gauge always
        // reflects the most recent report a caller produced.
        org.mockserver.metrics.Metrics.updateLlmOptimisationSnapshot(
            report.getVerdict() != null ? report.getVerdict().getTotalEstimatedSavingUsd() : 0.0,
            report.getTotals() != null ? report.getTotals().getCacheHitRatio() : 0.0,
            report.getTotals() != null ? report.getTotals().getOneShotRate() : 0.0);
        return new Result(report, exchanges);
    }

    /** Render the brief for a previously built result, redacting the appendix. */
    public String renderBrief(Result result) {
        FixtureRedactor redactor = redactor();
        return renderer.render(result.getReport(), result.getIncludedExchanges(), redactor);
    }

    /** Render the CSV export (per-call rows + totals/verdict summary) for a previously built result. */
    public String renderCsv(Result result) {
        return csvRenderer.render(result.getReport());
    }

    /**
     * Render the captured session as an eval / fine-tune / promptfoo dataset for a
     * previously built result. The exporter always redacts request and response
     * content via {@link FixtureRedactor} (default sensitive headers/query params
     * plus any configured {@code mockserver.fixtureBodyRedactFields}) before
     * emission, so secrets and configured PII never reach the dataset.
     */
    public String renderDataset(Result result, LlmDatasetExporter.DatasetFormat format) {
        return new LlmDatasetExporter().export(result.getIncludedExchanges(), format, redactor());
    }

    /**
     * The redactor used for the brief appendix, dataset exports and the agent-run
     * diff — default sensitive headers/query params plus any configured
     * {@code mockserver.fixtureBodyRedactFields}. Public so the diff callers
     * (REST {@code PUT /llm/diffRuns}, MCP {@code diff_agent_runs}) redact each
     * request the SAME way the export path does before surfacing decoded prompts.
     */
    public FixtureRedactor redactor() {
        List<String> bodyFields = bodyFields();
        return bodyFields.isEmpty()
            ? new FixtureRedactor()
            : new FixtureRedactor(FixtureRedactor.defaultSensitiveHeaders(), bodyFields);
    }

    private static List<String> bodyFields() {
        String configured = ConfigurationProperties.fixtureBodyRedactFields();
        List<String> result = new ArrayList<>();
        if (configured != null && !configured.trim().isEmpty()) {
            for (String field : configured.split(",")) {
                String trimmed = field.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static String upstreamHost(HttpRequest request) {
        if (request.getSocketAddress() != null && request.getSocketAddress().getHost() != null
            && !request.getSocketAddress().getHost().isEmpty()) {
            return stripPort(request.getSocketAddress().getHost());
        }
        String hostHeader = request.getFirstHeader("Host");
        if (hostHeader != null && !hostHeader.isEmpty()) {
            return stripPort(hostHeader);
        }
        return null;
    }

    private static String stripPort(String hostMaybeWithPort) {
        if (hostMaybeWithPort.startsWith("[")) {
            int closeBracket = hostMaybeWithPort.indexOf(']');
            if (closeBracket >= 0) {
                return hostMaybeWithPort.substring(1, closeBracket);
            }
        }
        int colon = hostMaybeWithPort.lastIndexOf(':');
        if (colon > 0) {
            return hostMaybeWithPort.substring(0, colon);
        }
        return hostMaybeWithPort;
    }

    /** The redacted-header list reported in the bundle, exposed for tests/callers. */
    public List<String> redactedHeaderNames() {
        return new ArrayList<>(FixtureRedactor.defaultSensitiveHeaders());
    }

    /** The configured redacted-body-field list, exposed for tests/callers. */
    public List<String> redactedBodyFieldNames() {
        return new ArrayList<>(bodyFields());
    }
}
