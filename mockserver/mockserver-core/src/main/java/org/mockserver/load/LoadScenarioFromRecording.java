package org.mockserver.load;

import org.mockserver.metrics.MetricLabels;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Seeds an editable, runnable {@link LoadScenario} from traffic previously recorded by MockServer in
 * proxy/recording mode (the {@code RECEIVED_REQUEST} entries returned by
 * {@link org.mockserver.log.MockServerEventLog#retrieveRequests}).
 *
 * <p>This is the "record-to-load" flagship: capture real traffic through the proxy, then replay its
 * <em>shape</em> as a load test. Two modes control how the recorded requests become {@link LoadStep}s:
 *
 * <ul>
 *   <li><b>{@link Mode#VERBATIM} (default)</b> — one {@link LoadStep} per recorded request, in recorded
 *       order, preserving the concrete path, body and headers. An optional {@code maxSteps} truncates to
 *       the first N recorded requests (the orchestrator's {@code loadGenerationMaxSteps} also caps).</li>
 *   <li><b>{@link Mode#TEMPLATIZED}</b> — recorded requests are deduplicated by
 *       {@code (method, templatised-path)} using the SHARED {@link MetricLabels#routeOf(String)}
 *       templatizer (e.g. {@code /orders/123} and {@code /orders/456} collapse to one
 *       {@code /orders/{id}} route), keeping one representative example per unique route, ordered by
 *       descending hit frequency (most-hit routes first). One {@link LoadStep} is emitted per unique
 *       route, each carrying that route's observed hit COUNT as its {@link LoadStep#getWeight() weight},
 *       and the generated scenario is marked {@link LoadScenario.StepSelection#WEIGHTED WEIGHTED} so each
 *       iteration picks a route in proportion to its recorded frequency — reproducing the recorded
 *       traffic MIX.</li>
 * </ul>
 *
 * <p><b>Target precedence</b> for where each generated request is sent (carried as the request's
 * {@code Host} header and {@code secure} flag, the routing surface every load step uses):
 * <ol>
 *   <li>an explicit {@link Target} supplied by the caller (applied to every step), else</li>
 *   <li>each recorded request's existing routing is left untouched — recorded proxied requests already
 *       carry their upstream target (Host header / secure flag).</li>
 * </ol>
 *
 * <p>When the caller supplies no {@link LoadProfile} the same conservative default as
 * {@link LoadScenarioFromOpenAPI} is applied — a single short constant-VU stage — so the generated
 * scenario is immediately runnable yet safe; the operator is expected to edit the profile afterward.
 *
 * <p>This is a pure generator: it produces a {@link LoadScenario} object and drives no traffic.
 */
public class LoadScenarioFromRecording {

    /** How recorded requests are turned into load steps. */
    public enum Mode {
        /** One step per recorded request, concrete and in recorded order (default). */
        VERBATIM,
        /**
         * One weighted step per unique (method, templatised-path) route, ordered by descending
         * frequency, with each step's weight set to its hit count and the scenario marked
         * {@link LoadScenario.StepSelection#WEIGHTED WEIGHTED} to reproduce the recorded traffic mix.
         */
        TEMPLATIZED
    }

    /**
     * An explicit network target for the generated steps, applied to every step. Any field may be
     * null/0; a null host means "leave each recorded request's own routing untouched", and a 0/absent
     * port means the scheme default.
     */
    public static class Target {
        private final String host;
        private final Integer port;
        private final String scheme;

        public Target(String host, Integer port, String scheme) {
            this.host = host;
            this.port = port;
            this.scheme = scheme;
        }

        public String getHost() {
            return host;
        }

        public Integer getPort() {
            return port;
        }

        public String getScheme() {
            return scheme;
        }

        public boolean hasHost() {
            return isNotBlank(host);
        }
    }

    /**
     * Raised when there is no recorded traffic to convert into a load scenario.
     *
     * <p>This is a <strong>state</strong> condition, not a malformed request: the caller's body is
     * well-formed and parsed, there is simply nothing recorded yet. The control plane maps it to
     * {@code 409 CONFLICT} rather than {@code 400 BAD_REQUEST} so a caller can tell "record some
     * traffic first" apart from "your request body is wrong". It extends
     * {@link IllegalArgumentException} so existing callers that catch that keep working.
     */
    public static class NoRecordedTrafficException extends IllegalArgumentException {
        public NoRecordedTrafficException() {
            super("no recorded requests to convert into a load scenario (record traffic through the proxy first)");
        }
    }

    /**
     * Generates a {@link LoadScenario} from recorded requests.
     *
     * @param name             the generated scenario name
     * @param recordedRequests the recorded requests, as returned by
     *                         {@link org.mockserver.log.MockServerEventLog#retrieveRequests}
     * @param mode             {@link Mode#VERBATIM} (default when null) or {@link Mode#TEMPLATIZED}
     * @param maxSteps         optional cap on VERBATIM steps — keeps the first N recorded requests
     *                         (ignored when null/&lt;=0; TEMPLATIZED is naturally bounded by route count)
     * @param target           explicit network target applied to every step (may be null — see class-level precedence)
     * @param profile          explicit load profile (may be null — a conservative default is applied)
     * @return the generated, editable {@link LoadScenario}
     * @throws NoRecordedTrafficException if there are no recorded requests to convert
     */
    public static LoadScenario generate(String name, List<? extends RequestDefinition> recordedRequests, Mode mode, Integer maxSteps, Target target, LoadProfile profile) {
        List<HttpRequest> httpRequests = toHttpRequests(recordedRequests);
        if (httpRequests.isEmpty()) {
            throw new NoRecordedTrafficException();
        }

        Mode effectiveMode = mode != null ? mode : Mode.VERBATIM;
        LoadScenario scenario = LoadScenario.loadScenario(name);

        if (effectiveMode == Mode.TEMPLATIZED) {
            buildTemplatizedSteps(scenario, httpRequests, target);
        } else {
            buildVerbatimSteps(scenario, httpRequests, maxSteps, target);
        }

        if (scenario.getSteps().isEmpty()) {
            // Defensive: only reachable if every recorded entry had no usable request, which the
            // toHttpRequests filter already excludes.
            throw new NoRecordedTrafficException();
        }

        scenario.withProfile(profile != null ? profile : LoadScenarioFromOpenAPI.defaultProfile());
        return scenario;
    }

    /** VERBATIM: one step per recorded request (in recorded order), optionally truncated to {@code maxSteps}. */
    private static void buildVerbatimSteps(LoadScenario scenario, List<HttpRequest> httpRequests, Integer maxSteps, Target target) {
        int limit = maxSteps != null && maxSteps > 0 ? Math.min(maxSteps, httpRequests.size()) : httpRequests.size();
        for (int i = 0; i < limit; i++) {
            HttpRequest httpRequest = httpRequests.get(i).clone();
            applyTarget(httpRequest, target);
            scenario.withSteps(LoadStep.loadStep(httpRequest));
        }
    }

    /**
     * TEMPLATIZED: dedupe by (method, templatised-path), keep the first representative per route, and
     * order the emitted steps by descending hit frequency (ties keep first-seen order via the
     * insertion-ordered map). The step name is set to the route key so the orchestrator surfaces the
     * templatised route directly as the {@code route} metric label.
     *
     * <p>Each emitted step's {@link LoadStep#getWeight() weight} is set to its route's observed hit
     * count and the scenario's {@link LoadScenario#getStepSelection() stepSelection} is set to
     * {@link LoadScenario.StepSelection#WEIGHTED WEIGHTED}, so each iteration runs a single route chosen
     * in proportion to its recorded frequency — reproducing the recorded traffic mix. Counts are always
     * {@code >= 1}, so the resulting scenario satisfies the WEIGHTED positive-weight validation.
     */
    private static void buildTemplatizedSteps(LoadScenario scenario, List<HttpRequest> httpRequests, Target target) {
        Map<String, RouteAggregate> byRoute = new LinkedHashMap<>();
        for (HttpRequest httpRequest : httpRequests) {
            String method = httpRequest.getMethod() != null ? httpRequest.getMethod().getValue() : "";
            String path = httpRequest.getPath() != null ? httpRequest.getPath().getValue() : null;
            String routePath = MetricLabels.routeOf(path);
            String key = method + " " + routePath;
            RouteAggregate aggregate = byRoute.computeIfAbsent(key, k -> new RouteAggregate(httpRequest, routePath));
            aggregate.count++;
        }
        List<RouteAggregate> ordered = new ArrayList<>(byRoute.values());
        // Descending frequency; stable sort preserves first-seen order for equal counts.
        ordered.sort((a, b) -> Integer.compare(b.count, a.count));
        for (RouteAggregate aggregate : ordered) {
            HttpRequest httpRequest = aggregate.representative.clone();
            applyTarget(httpRequest, target);
            LoadStep step = LoadStep.loadStep(httpRequest);
            String method = httpRequest.getMethod() != null ? httpRequest.getMethod().getValue() : "";
            step.withName((isNotBlank(method) ? method + " " : "") + aggregate.routePath);
            // Weight the step by its recorded hit count so WEIGHTED selection reproduces the traffic mix.
            step.withWeight((double) aggregate.count);
            scenario.withSteps(step);
        }
        // Pick one route per iteration in proportion to recorded frequency (the weights set above).
        scenario.withStepSelection(LoadScenario.StepSelection.WEIGHTED);
    }

    private static List<HttpRequest> toHttpRequests(List<? extends RequestDefinition> recordedRequests) {
        List<HttpRequest> httpRequests = new ArrayList<>();
        if (recordedRequests != null) {
            for (RequestDefinition requestDefinition : recordedRequests) {
                if (requestDefinition instanceof HttpRequest) {
                    httpRequests.add((HttpRequest) requestDefinition);
                }
            }
        }
        return httpRequests;
    }

    /**
     * Applies the target to the request as a {@code Host} header (host[:port]) and {@code secure} flag —
     * the routing surface the load orchestrator reads. A null/host-less target leaves the request's own
     * recorded routing untouched.
     */
    private static void applyTarget(HttpRequest httpRequest, Target target) {
        if (target == null || !target.hasHost()) {
            return;
        }
        boolean secure = "https".equalsIgnoreCase(target.getScheme());
        httpRequest.withSecure(secure);
        String hostHeader = target.getHost();
        if (target.getPort() != null && target.getPort() > 0) {
            hostHeader = hostHeader + ":" + target.getPort();
        }
        httpRequest.replaceHeader(org.mockserver.model.Header.header("Host", hostHeader));
    }

    /** Mutable per-route accumulator used while deduplicating TEMPLATIZED routes. */
    private static final class RouteAggregate {
        private final HttpRequest representative;
        private final String routePath;
        private int count;

        private RouteAggregate(HttpRequest representative, String routePath) {
            this.representative = representative;
            this.routePath = routePath;
        }
    }
}
