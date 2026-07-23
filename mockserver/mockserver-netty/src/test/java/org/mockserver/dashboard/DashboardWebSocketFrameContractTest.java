package org.mockserver.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.dashboard.DashboardWebSocketHandlerTest.MockChannelHandlerContext;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.uuid.UUIDService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Cross-boundary STRUCTURAL contract between the server {@link DashboardWebSocketHandler} and the
 * dashboard UI (store + panels).
 * <p>
 * This drives the REAL handler across all four dashboard panels, captures the frame it emits, and
 * asserts — for every field the UI store/panels actually read — that the field is PRESENT with the
 * correct JSON TYPE and that the server-assigned key correlations hold (a recorded request and its
 * originating RECEIVED_REQUEST log entry share the same server log id). It deliberately does NOT
 * assert exact dynamic values, timestamps, UUIDs or array ordering.
 * <p>
 * The required-field list is read from the SHARED, checked-in contract
 * {@code mockserver-ui/src/__fixtures__/dashboardFrameContract.json}, the same file the UI test
 * {@code dashboardFrameContract.test.ts} reads. Both sides are therefore pinned to one field
 * contract: renaming or removing a required field name in that file reddens BOTH tests (the
 * cross-boundary drift bite); a server-side field rename reddens this test alone.
 * <p>
 * Why this is drift-proof where the previous byte-equal-golden attempt was not:
 * <ul>
 *   <li>The contract check is a per-field SUBSET assertion (required field present + type), never a
 *       whole-frame equality — so it is immune to non-deterministic emission ORDERING, to
 *       UUID/timestamp/port/hostname VALUES (never compared), and to environment-dependent EXTRA
 *       fields (ignored). Those are exactly what made the local-capture golden drift in CI.</li>
 *   <li>{@link #frameStructureIsDeterministicAcrossCaptures()} additionally proves determinism the
 *       last attempt skipped: it captures the frame twice and asserts a value-blind, order-independent
 *       structural fingerprint is identical across the two captures. Both captures run in the SAME
 *       JVM/environment, so this can only fail on genuine ordering/shape non-determinism — never on a
 *       local-vs-CI environment difference.</li>
 * </ul>
 */
public class DashboardWebSocketFrameContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONTRACT_RELATIVE_PATH = "mockserver-ui/src/__fixtures__/dashboardFrameContract.json";

    @Test
    public void frameSatisfiesSharedFieldContractAcrossRepeatedCaptures() throws Exception {
        JsonNode contract = loadSharedContract();

        // Validate the shared representative frame first: this keeps the checked-in fixture the UI
        // test feeds through applyMessage honest against the SAME required-field contract, so a
        // fixture edit that breaks the shape is caught on the server side too.
        validateFrameAgainstContract(contract.get("representativeFrame"), contract, "shared representative frame");

        // Run the real capture several times to prove the structural contract holds and is not flaky.
        for (int i = 0; i < 5; i++) {
            JsonNode frame = JSON.readTree(captureRealFrame());

            // every panel populated, so the contract is genuinely exercised (not vacuously satisfied)
            assertThat("logMessages populated", frame.get("logMessages").size(), is(greaterThan(0)));
            assertThat("activeExpectations populated", frame.get("activeExpectations").size(), is(greaterThan(0)));
            assertThat("recordedRequests populated", frame.get("recordedRequests").size(), is(greaterThan(0)));
            assertThat("proxiedRequests populated", frame.get("proxiedRequests").size(), is(greaterThan(0)));

            validateFrameAgainstContract(frame, contract, "real captured frame (iteration " + i + ")");
        }
    }

    @Test
    public void frameStructureIsDeterministicAcrossCaptures() throws Exception {
        // Two independent captures of the same logical input. Ids, timestamps, ports and map/disruptor
        // ordering may all differ between them — the value-blind, order-independent fingerprint must
        // still be identical. This is the ordering non-determinism that only surfaced in CI last time;
        // proving the canonicalisation collapses the two captures to the same value catches it locally.
        String fingerprintA = structuralFingerprint(JSON.readTree(captureRealFrame()));
        String fingerprintB = structuralFingerprint(JSON.readTree(captureRealFrame()));
        assertThat(
            "two captures of the same input must canonicalise to the same value-blind structural shape",
            fingerprintB, is(fingerprintA)
        );
    }

    // ------------------------------------------------------------------------------------------------
    // Capture: drive the REAL DashboardWebSocketHandler across all four panels and return its frame.
    // ------------------------------------------------------------------------------------------------

    private String captureRealFrame() throws InterruptedException {
        MockServerLogger mockServerLogger = new MockServerLogger(DashboardWebSocketFrameContractTest.class);
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, true);
        HttpState httpState = new HttpState(configuration(), mockServerLogger, scheduler);

        String sharedCorrelationId = UUIDService.getUUID();
        List<LogEntry> logEntries = Arrays.asList(
            // -> recordedRequests panel + a plain logMessages entry, correlated by the server log id
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/some/path"))
                .setMessageFormat("received request"),
            // -> proxiedRequests panel + a plain logMessages entry
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/proxied/path"))
                .setHttpResponse(response("proxied"))
                .setMessageFormat("forwarded request"),
            // -> a logMessages GROUP (two entries sharing one correlation id roll up)
            new LogEntry()
                .setHttpRequest(request("/general/one").withLogCorrelationId(sharedCorrelationId))
                .setMessageFormat("general one"),
            new LogEntry()
                .setHttpRequest(request("/general/two").withLogCorrelationId(sharedCorrelationId))
                .setMessageFormat("general two")
        );
        Expectation[] expectations = {
            // -> activeExpectations panel
            new Expectation(request("/some/path")).thenRespond(response("hello"))
        };

        // Populate the event log and matchers off a scheduler thread, mirroring the production path
        // (and the sibling DashboardWebSocketHandlerTest harness).
        new Scheduler.SchedulerThreadFactory("MockServer Test " + getClass().getSimpleName()).newThread(() -> {
            MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
            for (LogEntry logEntry : logEntries) {
                mockServerEventLog.add(logEntry);
            }
            RequestMatchers requestMatchers = httpState.getRequestMatchers();
            requestMatchers.update(expectations, MockServerMatcherNotifier.Cause.API);
        }).start();
        SECONDS.sleep(1);

        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, true).registerListeners();
        MockChannelHandlerContext mockChannelHandlerContext = new MockChannelHandlerContext();
        handler.getClientRegistry().put(mockChannelHandlerContext, request());

        handler.sendUpdate(mockChannelHandlerContext, request());
        SECONDS.sleep(1);

        assertThat("handler must have emitted a frame", mockChannelHandlerContext.textWebSocketFrame, is(org.hamcrest.CoreMatchers.notNullValue()));
        return mockChannelHandlerContext.textWebSocketFrame.text();
    }

    // ------------------------------------------------------------------------------------------------
    // Contract validation: SUBSET check of required fields + key correlations. Value-blind by design.
    // ------------------------------------------------------------------------------------------------

    private void validateFrameAgainstContract(JsonNode frame, JsonNode contract, String description) {
        JsonNode panels = contract.get("panels");
        Iterator<String> panelNames = panels.fieldNames();
        while (panelNames.hasNext()) {
            String panel = panelNames.next();
            if (panel.startsWith("_")) {
                continue;
            }
            assertThat(description + ": missing panel '" + panel + "'", frame.has(panel), is(true));
            assertThat(description + ": panel '" + panel + "' must be an array", frame.get(panel).isArray(), is(true));
            JsonNode panelSpec = panels.get(panel);
            for (JsonNode item : frame.get(panel)) {
                JsonNode requiredFields = requiredFieldsFor(panelSpec, item);
                assertItemHasRequiredFields(description, panel, item, requiredFields);
            }
        }
        validateCorrelations(frame, contract.get("correlations"), description);
    }

    /**
     * Resolve which required-field set applies to an item. A panel is either flat (a single
     * {@code requiredFields}) or discriminated ({@code variants} keyed by the presence of a
     * {@code discriminator} field on the item, e.g. logMessages entry vs group).
     */
    private JsonNode requiredFieldsFor(JsonNode panelSpec, JsonNode item) {
        if (panelSpec.has("variants")) {
            String discriminator = panelSpec.get("discriminator").asText();
            JsonNode variants = panelSpec.get("variants");
            // Pick the variant whose requiredFields list the discriminator when the item carries it;
            // otherwise the variant that does not — never hard-codes the variant names.
            String chosen = null;
            String fallback = null;
            Iterator<String> variantNames = variants.fieldNames();
            while (variantNames.hasNext()) {
                String variantName = variantNames.next();
                if (variantName.startsWith("_")) {
                    continue;
                }
                JsonNode required = variants.get(variantName).get("requiredFields");
                if (required.has(discriminator)) {
                    if (item.has(discriminator)) {
                        chosen = variantName;
                    }
                } else {
                    fallback = variantName;
                }
            }
            String variant = chosen != null ? chosen : fallback;
            return variants.get(variant).get("requiredFields");
        }
        return panelSpec.get("requiredFields");
    }

    private void assertItemHasRequiredFields(String description, String panel, JsonNode item, JsonNode requiredFields) {
        Iterator<Map.Entry<String, JsonNode>> fields = requiredFields.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (name.startsWith("_")) {
                continue;
            }
            String expectedType = field.getValue().asText();
            assertThat(
                description + ": " + panel + " item missing required field '" + name + "' (contract drift) in " + item,
                item.has(name), is(true)
            );
            assertThat(
                description + ": " + panel + " item field '" + name + "' expected type '" + expectedType + "' in " + item,
                jsonType(item.get(name)), is(expectedType)
            );
        }
    }

    private void validateCorrelations(JsonNode frame, JsonNode correlations, String description) {
        if (correlations == null) {
            return;
        }
        for (JsonNode correlation : correlations) {
            String producerPanel = correlation.get("producerPanel").asText();
            String producerKeySuffix = correlation.get("producerKeySuffix").asText();
            String correlatedPanel = correlation.get("correlatedPanel").asText();
            String correlatedKeySuffix = correlation.get("correlatedKeySuffix").asText();
            for (JsonNode producer : frame.get(producerPanel)) {
                String key = producer.get("key").asText();
                if (!key.endsWith(producerKeySuffix)) {
                    continue;
                }
                String serverId = key.substring(0, key.length() - producerKeySuffix.length());
                String expectedCorrelatedKey = serverId + correlatedKeySuffix;
                boolean found = false;
                for (JsonNode correlated : frame.get(correlatedPanel)) {
                    if (correlated.has("key") && correlated.get("key").asText().equals(expectedCorrelatedKey)) {
                        found = true;
                        break;
                    }
                }
                assertThat(
                    description + ": key correlation broken — " + producerPanel + " key '" + key
                        + "' has no matching " + correlatedPanel + " key '" + expectedCorrelatedKey + "'",
                    found, is(true)
                );
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Value-blind, order-independent structural fingerprint (determinism proof).
    // Objects: field-name -> child shape, sorted. Arrays: multiset of child shapes, sorted (so element
    // ORDER and any id embedded in keys are irrelevant). Scalars collapse to their TYPE name (so
    // timestamp/UUID/port/hostname VALUES are irrelevant).
    // ------------------------------------------------------------------------------------------------

    private String structuralFingerprint(JsonNode node) {
        if (node.isObject()) {
            List<String> parts = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                parts.add(field.getKey() + ":" + structuralFingerprint(field.getValue()));
            }
            Collections.sort(parts);
            return "{" + String.join(",", parts) + "}";
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode child : node) {
                parts.add(structuralFingerprint(child));
            }
            Collections.sort(parts);
            return "[" + String.join(",", parts) + "]";
        }
        return jsonType(node);
    }

    private String jsonType(JsonNode node) {
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return "null";
    }

    // ------------------------------------------------------------------------------------------------
    // Locate the SHARED contract file by walking up from the module working directory to the monorepo
    // root. Fail-closed with a clear message if it cannot be found (never silently pass).
    // ------------------------------------------------------------------------------------------------

    private JsonNode loadSharedContract() throws IOException {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, CONTRACT_RELATIVE_PATH);
            if (candidate.isFile()) {
                return JSON.readTree(candidate);
            }
            dir = dir.getParentFile();
        }
        throw new FileNotFoundException(
            "could not locate shared cross-boundary contract '" + CONTRACT_RELATIVE_PATH
                + "' by walking up from " + System.getProperty("user.dir")
                + " — the Java and UI dashboard-frame contract tests must read the same file"
        );
    }
}
