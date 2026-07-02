package org.mockserver.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.mock.action.http.LoadScenarioOrchestrator.CheckResult;
import org.mockserver.mock.action.http.LoadScenarioOrchestrator.LoadScenarioStatus;
import org.mockserver.mock.action.http.LoadScenarioOrchestrator.ThresholdResult;
import org.mockserver.serialization.ObjectMapperFactory;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Unit tests for {@link LoadScenarioReport}. Pure transformation of a {@link LoadScenarioStatus}
 * snapshot into JSON / JUnit-XML — no global state is mutated, so this runs in the parallel phase.
 */
public class LoadScenarioReportTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private LoadScenarioStatus status(String name, LoadScenarioState state, String verdict,
                                      boolean abortedByThreshold, long requestsSent, long succeeded,
                                      long failed, List<ThresholdResult> thresholdResults) {
        return status(name, state, verdict, abortedByThreshold, requestsSent, succeeded, failed,
            thresholdResults, Collections.emptyList());
    }

    private LoadScenarioStatus status(String name, LoadScenarioState state, String verdict,
                                      boolean abortedByThreshold, long requestsSent, long succeeded,
                                      long failed, List<ThresholdResult> thresholdResults,
                                      List<CheckResult> checkResults) {
        LoadScenario scenario = LoadScenario.loadScenario(name);
        return new LoadScenarioStatus(
            name,
            state,
            12_000L,        // elapsedMillis
            5,              // currentVus
            requestsSent,
            succeeded,
            failed,
            10L,            // p50
            42L,            // p95
            88L,            // p99
            175L,           // p999
            3L,             // droppedIterations
            "run-123",      // runId
            1_000L,         // startedAtEpochMillis
            13_000L,        // endedAtEpochMillis
            null,           // labels
            scenario,
            -1,             // stageIndex
            null,           // stageType
            0,              // currentTarget
            0L,             // startDelayMillis
            verdict,
            abortedByThreshold,
            thresholdResults,
            checkResults
        );
    }

    @Test
    public void jsonContainsExpectedFieldsForFinishedRun() throws Exception {
        List<ThresholdResult> thresholds = Arrays.asList(
            new ThresholdResult("LATENCY_P95", "LESS_THAN", 250.0, 42.0, true),
            new ThresholdResult("ERROR_RATE", "LESS_THAN", 0.01, 0.05, false)
        );
        LoadScenarioStatus status = status("checkout", LoadScenarioState.COMPLETED, "FAIL", false, 200, 190, 10, thresholds);

        JsonNode report = objectMapper.readTree(LoadScenarioReport.toJson(status));

        assertThat(report.get("scenario").asText(), is("checkout"));
        assertThat(report.get("runId").asText(), is("run-123"));
        assertThat(report.get("state").asText(), is("COMPLETED"));
        assertThat(report.get("verdict").asText(), is("FAIL"));
        assertThat(report.get("abortedByThreshold").asBoolean(), is(false));

        assertThat(report.get("timing").get("startedAtEpochMillis").asLong(), is(1_000L));
        assertThat(report.get("timing").get("endedAtEpochMillis").asLong(), is(13_000L));
        assertThat(report.get("timing").get("durationMillis").asLong(), is(12_000L));

        assertThat(report.get("counts").get("requestsSent").asLong(), is(200L));
        assertThat(report.get("counts").get("succeeded").asLong(), is(190L));
        assertThat(report.get("counts").get("failed").asLong(), is(10L));
        assertThat(report.get("counts").get("droppedIterations").asLong(), is(3L));
        assertThat(report.get("counts").get("errorRate").asDouble(), closeTo(0.05, 1e-9));

        assertThat(report.get("latencyMillis").get("p50").asLong(), is(10L));
        assertThat(report.get("latencyMillis").get("p95").asLong(), is(42L));
        assertThat(report.get("latencyMillis").get("p99").asLong(), is(88L));
        assertThat(report.get("latencyMillis").get("p999").asLong(), is(175L));

        JsonNode results = report.get("thresholdResults");
        assertThat(results.size(), is(2));
        assertThat(results.get(0).get("metric").asText(), is("LATENCY_P95"));
        assertThat(results.get(0).get("satisfied").asBoolean(), is(true));
        assertThat(results.get(1).get("metric").asText(), is("ERROR_RATE"));
        assertThat(results.get(1).get("observed").asDouble(), closeTo(0.05, 1e-9));
        assertThat(results.get(1).get("satisfied").asBoolean(), is(false));
    }

    @Test
    public void jsonContainsCheckResults() throws Exception {
        List<CheckResult> checks = Arrays.asList(
            new CheckResult("assert", "STATUS", "", "EQUALS", "200", 190L, 0L),
            new CheckResult("assert", "BODY_JSONPATH", "$.status", "EQUALS", "ok", 150L, 40L)
        );
        LoadScenarioStatus status = status("checkout", LoadScenarioState.COMPLETED, null, false, 200, 190, 10,
            Collections.emptyList(), checks);

        JsonNode report = objectMapper.readTree(LoadScenarioReport.toJson(status));
        JsonNode results = report.get("checkResults");
        assertThat(results.size(), is(2));
        assertThat(results.get(0).get("source").asText(), is("STATUS"));
        assertThat(results.get(0).get("passed").asLong(), is(190L));
        assertThat(results.get(0).get("failed").asLong(), is(0L));
        assertThat(results.get(1).get("source").asText(), is("BODY_JSONPATH"));
        assertThat(results.get(1).get("detail").asText(), is("$.status"));
        assertThat(results.get(1).get("failed").asLong(), is(40L));
    }

    @Test
    public void junitFailedCheckProducesFailureTestcase() throws Exception {
        List<CheckResult> checks = Arrays.asList(
            new CheckResult("assert", "STATUS", "", "EQUALS", "200", 190L, 0L),
            new CheckResult("assert", "BODY_JSONPATH", "$.status", "EQUALS", "ok", 150L, 40L)
        );
        LoadScenarioStatus status = status("checkout", LoadScenarioState.COMPLETED, "PASS", false, 200, 200, 0,
            Collections.emptyList(), checks);

        String xml = LoadScenarioReport.toJUnitXml(status);

        assertWellFormed(xml);
        // 0 thresholds + 2 checks + run-completed = 3 tests; one check with failures = 1 failure.
        assertThat(xml, containsString("tests=\"3\""));
        assertThat(xml, containsString("failures=\"1\""));
        assertThat(xml, containsString("check: step assert BODY_JSONPATH $.status EQUALS ok"));
        assertThat(xml, containsString("40 of 190 evaluations failed"));
    }

    @Test
    public void errorRateIsZeroForZeroRequestRun() {
        LoadScenarioStatus status = status("idle", LoadScenarioState.COMPLETED, null, false, 0, 0, 0, Collections.emptyList());
        assertThat(LoadScenarioReport.errorRate(status), is(0.0));
    }

    @Test
    public void junitBreachedThresholdProducesFailureTestcase() throws Exception {
        List<ThresholdResult> thresholds = Arrays.asList(
            new ThresholdResult("LATENCY_P95", "LESS_THAN", 250.0, 42.0, true),
            new ThresholdResult("LATENCY_P99", "LESS_THAN", 50.0, 88.0, false)
        );
        LoadScenarioStatus status = status("checkout", LoadScenarioState.COMPLETED, "FAIL", false, 200, 190, 10, thresholds);

        String xml = LoadScenarioReport.toJUnitXml(status);

        assertWellFormed(xml);
        assertThat(xml, containsString("name=\"load:checkout\""));
        // 2 thresholds + run-completed = 3 tests; one breached threshold + failed run = 2 failures
        assertThat(xml, containsString("tests=\"3\""));
        assertThat(xml, containsString("failures=\"2\""));
        assertThat(xml, containsString("<testcase name=\"threshold: LATENCY_P99 LESS_THAN 50\">"));
        assertThat(xml, containsString("observed 88 LESS_THAN 50 not satisfied"));
    }

    @Test
    public void junitCleanRunHasNoFailures() throws Exception {
        List<ThresholdResult> thresholds = Collections.singletonList(
            new ThresholdResult("LATENCY_P95", "LESS_THAN", 250.0, 42.0, true)
        );
        LoadScenarioStatus status = status("clean", LoadScenarioState.COMPLETED, "PASS", false, 100, 100, 0, thresholds);

        String xml = LoadScenarioReport.toJUnitXml(status);

        assertWellFormed(xml);
        assertThat(xml, containsString("failures=\"0\""));
        assertThat(xml, not(containsString("<failure")));
    }

    @Test
    public void junitAbortedRunFailsRunCompletedTestcase() throws Exception {
        LoadScenarioStatus status = status("aborted", LoadScenarioState.STOPPED, "FAIL", true, 50, 40, 10, Collections.emptyList());

        String xml = LoadScenarioReport.toJUnitXml(status);

        assertWellFormed(xml);
        // no thresholds -> 1 test (run completed), which fails because abortedByThreshold
        assertThat(xml, containsString("tests=\"1\""));
        assertThat(xml, containsString("failures=\"1\""));
        assertThat(xml, containsString("abortOnFail threshold breach"));
    }

    @Test
    public void xmlSpecialCharsInScenarioNameAreEscapedAndWellFormed() throws Exception {
        LoadScenarioStatus status = status("a & b < c > \"d\"", LoadScenarioState.COMPLETED, "PASS", false, 10, 10, 0, Collections.emptyList());

        String xml = LoadScenarioReport.toJUnitXml(status);

        assertWellFormed(xml);
        assertThat(xml, containsString("load:a &amp; b &lt; c &gt; &quot;d&quot;"));
        assertThat(xml, not(containsString("load:a & b")));
    }

    private static void assertWellFormed(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        // parse to prove the document is well-formed; throws on malformed XML
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
