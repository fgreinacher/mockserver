package org.mockserver.mock.drift;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural coverage for the instance-set {@code alertSeverityThreshold} flag on
 * {@link DriftAlertNotifier}: it governs <em>which</em> drift severities actually fire a webhook alert.
 *
 * <p>Unlike {@link DriftAlertNotifierTest}, which pokes the notifier with hand-built {@link DriftRecord}s
 * at a fixed threshold, these tests drive real drift <b>analysis</b> ({@link DriftAnalyzer#analyse}) that
 * emits a mix of BREAKING, WARNING and INFORMATIONAL severities in a single pass, then <b>sweep</b> the
 * threshold to prove the <em>alerted set</em> (the severities that produced a send) grows as the threshold
 * is lowered and shrinks as it is raised. Assertions are on the captured webhook output set, never on a
 * getter or a re-implemented comparison.
 */
public class DriftAlertSeverityThresholdTest {

    private static final long NOW = 5000L;
    private static final String WEBHOOK_URL = "http://drift-hook/endpoint";

    /** Synchronous fake sender capturing every outbound webhook request. */
    private static final class CapturingSender implements Function<HttpRequest, CompletableFuture<HttpResponse>> {
        final List<HttpRequest> captured = new ArrayList<>();

        @Override
        public CompletableFuture<HttpResponse> apply(HttpRequest request) {
            captured.add(request);
            return CompletableFuture.completedFuture(response().withStatusCode(200));
        }

        /** The set of effective severities that actually produced a webhook send. */
        Set<String> firedSeverities() {
            Set<String> severities = new LinkedHashSet<>();
            for (HttpRequest req : captured) {
                try {
                    JsonNode envelope = ObjectMapperFactory.createObjectMapper().readTree(req.getBodyAsString());
                    severities.add(envelope.get("severity").asText());
                } catch (Exception e) {
                    throw new AssertionError("captured webhook body was not the expected JSON envelope", e);
                }
            }
            return severities;
        }
    }

    private CapturingSender sender;
    private DriftAnalyzer analyzer;

    @Before
    public void setUp() {
        resetNotifier();
        sender = new CapturingSender();
        DriftAlertNotifier.getInstance().setSender(sender);
        DriftAlertNotifier.getInstance().setClock(() -> NOW);
        // Each analyse() call uses a fresh local store, so records never leak between tests.
        analyzer = new DriftAnalyzer(new DriftStore(100));
    }

    @After
    public void tearDown() {
        resetNotifier();
    }

    private static void resetNotifier() {
        DriftAlertNotifier.getInstance().reset();
        DriftAlertNotifier.getInstance().setSender(null);
        DriftAlertNotifier.getInstance().setClock(System::currentTimeMillis);
        DriftAlertNotifier.getInstance().configure(false, "", SemanticSeverity.BREAKING, 60000);
    }

    /**
     * A single stub/real pair whose structural diff yields exactly three drifts spanning all three
     * severities:
     * <ul>
     *   <li>status 200 -&gt; 500        =&gt; {@link DriftType#STATUS} =&gt; BREAKING</li>
     *   <li>header {@code x-role} changed =&gt; {@link DriftType#HEADER_CHANGED} =&gt; WARNING</li>
     *   <li>body field {@code b} added   =&gt; {@link DriftType#SCHEMA_FIELD_ADDED} =&gt; INFORMATIONAL</li>
     * </ul>
     * The shared {@code x-common} header and unchanged {@code a} field keep the drift set to exactly these
     * three, so the alerted set is a clean function of the threshold alone.
     */
    private void analyseThreeSeverityDrift() {
        Expectation stub = new Expectation(request().withPath("/api"))
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("x-role", "admin")
                .withHeader("x-common", "v")
                .withBody("{\"a\":1}"));
        HttpResponse real = response()
            .withStatusCode(500)
            .withHeader("x-role", "user")
            .withHeader("x-common", "v")
            .withBody("{\"a\":1,\"b\":2}");
        analyzer.analyse(stub, real);
    }

    @Test
    public void warningThresholdAlertsBreakingAndWarningButNotInformational() {
        DriftAlertNotifier.getInstance().configure(true, WEBHOOK_URL, SemanticSeverity.WARNING, 60000);

        analyseThreeSeverityDrift();

        // Only severities at/above WARNING fire; the INFORMATIONAL schema-add is straddled out.
        assertThat(sender.firedSeverities(), containsInAnyOrder("BREAKING", "WARNING"));
    }

    @Test
    public void breakingThresholdAlertsOnlyBreaking() {
        DriftAlertNotifier.getInstance().configure(true, WEBHOOK_URL, SemanticSeverity.BREAKING, 60000);

        analyseThreeSeverityDrift();

        // The most severe threshold: WARNING and INFORMATIONAL are both below it.
        assertThat(sender.firedSeverities(), containsInAnyOrder("BREAKING"));
    }

    @Test
    public void informationalThresholdAlertsEverySeverity() {
        DriftAlertNotifier.getInstance().configure(true, WEBHOOK_URL, SemanticSeverity.INFORMATIONAL, 60000);

        analyseThreeSeverityDrift();

        // The least severe threshold: every drift fires.
        assertThat(sender.firedSeverities(), containsInAnyOrder("BREAKING", "WARNING", "INFORMATIONAL"));
    }

    @Test
    public void raisingThenLoweringTheThresholdChangesTheAlertedSet() {
        // Sweep the SAME drift analysis across the whole severity ladder and assert the alerted set
        // strictly grows as the threshold is lowered. configure() clears the de-dup cooldown, so each
        // phase re-fires cleanly; a fresh sender per phase isolates each alerted set.

        // Phase 1 — most severe threshold: only BREAKING.
        analyzer = new DriftAnalyzer(new DriftStore(100));
        CapturingSender breaking = new CapturingSender();
        DriftAlertNotifier.getInstance().setSender(breaking);
        DriftAlertNotifier.getInstance().configure(true, WEBHOOK_URL, SemanticSeverity.BREAKING, 60000);
        analyseThreeSeverityDrift();

        // Phase 2 — lower to WARNING: BREAKING + WARNING.
        analyzer = new DriftAnalyzer(new DriftStore(100));
        CapturingSender warning = new CapturingSender();
        DriftAlertNotifier.getInstance().setSender(warning);
        DriftAlertNotifier.getInstance().configure(true, WEBHOOK_URL, SemanticSeverity.WARNING, 60000);
        analyseThreeSeverityDrift();

        // Phase 3 — lower to INFORMATIONAL: all three.
        analyzer = new DriftAnalyzer(new DriftStore(100));
        CapturingSender informational = new CapturingSender();
        DriftAlertNotifier.getInstance().setSender(informational);
        DriftAlertNotifier.getInstance().configure(true, WEBHOOK_URL, SemanticSeverity.INFORMATIONAL, 60000);
        analyseThreeSeverityDrift();

        assertThat(breaking.firedSeverities(), containsInAnyOrder("BREAKING"));
        assertThat(warning.firedSeverities(), containsInAnyOrder("BREAKING", "WARNING"));
        assertThat(informational.firedSeverities(), containsInAnyOrder("BREAKING", "WARNING", "INFORMATIONAL"));

        // The alerted set strictly grows as the threshold is lowered (and strictly shrinks as it is raised).
        assertThat(breaking.captured.size() < warning.captured.size(), is(true));
        assertThat(warning.captured.size() < informational.captured.size(), is(true));
    }

    @Test
    public void disabledWebhookAlertsNothingRegardlessOfSeverity() {
        // Threshold at the least-severe setting would fire everything IF enabled; disabled must alert none.
        DriftAlertNotifier.getInstance().configure(false, WEBHOOK_URL, SemanticSeverity.INFORMATIONAL, 60000);

        analyseThreeSeverityDrift();

        assertThat(sender.captured, is(empty()));
    }
}
