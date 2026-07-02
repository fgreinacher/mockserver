package org.mockserver.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.mock.action.http.LoadScenarioOrchestrator.CheckResult;
import org.mockserver.mock.action.http.LoadScenarioOrchestrator.LoadScenarioStatus;
import org.mockserver.mock.action.http.LoadScenarioOrchestrator.ThresholdResult;
import org.mockserver.serialization.ObjectMapperFactory;

/**
 * Builds an end-of-run summary report for a load scenario from a single
 * {@link LoadScenarioStatus} snapshot (the live snapshot for a running run, or the retained terminal
 * snapshot for a finished one).
 *
 * <p>Two equivalent renderings are produced from the same data:
 * <ul>
 *   <li>{@link #toJson(LoadScenarioStatus)} — a machine-readable JSON document (built with the shared
 *       {@link ObjectMapperFactory} mapper) carrying counts, latency percentiles, the threshold verdict
 *       and per-threshold results.</li>
 *   <li>{@link #toJUnitXml(LoadScenarioStatus)} — a JUnit-XML {@code <testsuite>} so a load run becomes
 *       a first-class CI artifact: one {@code <testcase>} per threshold (a breach produces a
 *       {@code <failure>}), plus a {@code run completed} testcase that fails on an error-caused terminal
 *       state or an {@code abortOnFail} abort. p95/p99/error-rate are surfaced as {@code <properties>}.</li>
 * </ul>
 *
 * <p>The XML is hand-built and XML-escaped (no new dependency) so a scenario name containing
 * {@code & < > " '} is rendered safely.
 *
 * <p>The report is derived purely from existing {@link LoadScenarioStatus} fields; it adds no new
 * tracking to the hot path.
 */
public final class LoadScenarioReport {

    private LoadScenarioReport() {
    }

    /**
     * Error rate for a run: failed requests as a fraction of the requests that have actually
     * <em>completed</em> ({@code failed / max(1, succeeded + failed)}). The denominator is the completed
     * count, not requestsSent, so an in-flight (dispatched-but-not-yet-completed) request — which can
     * exist in a snapshot taken while the run is still draining — never dilutes the rate as if it were a
     * success. A run with no completed request reports 0 rather than dividing by zero. This matches the
     * ERROR_RATE threshold metric computed in {@code LoadScenarioOrchestrator.evaluateThresholds}.
     */
    public static double errorRate(LoadScenarioStatus status) {
        long completed = Math.max(1L, status.succeeded + status.failed);
        return (double) status.failed / (double) completed;
    }

    /**
     * True when the run is in a terminal state that should be treated as a CI failure: it was aborted
     * by an {@code abortOnFail} threshold breach, it carries a {@code FAIL} verdict, or it stopped while
     * having recorded request failures. A clean COMPLETED/STOPPED run with no failures is a pass.
     */
    public static boolean runFailed(LoadScenarioStatus status) {
        if (status.abortedByThreshold) {
            return true;
        }
        if ("FAIL".equals(status.verdict)) {
            return true;
        }
        boolean terminal = status.state == LoadScenarioState.COMPLETED || status.state == LoadScenarioState.STOPPED;
        return terminal && status.failed > 0;
    }

    /** Build the JSON summary report for a run. */
    public static String toJson(LoadScenarioStatus status) {
        ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scenario", status.name);
        node.put("runId", status.runId);
        node.put("state", status.state != null ? status.state.name() : null);
        if (status.verdict != null) {
            node.put("verdict", status.verdict);
        }
        node.put("abortedByThreshold", status.abortedByThreshold);

        ObjectNode timing = node.putObject("timing");
        timing.put("startedAtEpochMillis", status.startedAtEpochMillis);
        if (status.endedAtEpochMillis != null) {
            timing.put("endedAtEpochMillis", status.endedAtEpochMillis);
        } else {
            timing.putNull("endedAtEpochMillis");
        }
        timing.put("durationMillis", status.elapsedMillis);

        ObjectNode counts = node.putObject("counts");
        counts.put("requestsSent", status.requestsSent);
        counts.put("succeeded", status.succeeded);
        counts.put("failed", status.failed);
        counts.put("droppedIterations", status.droppedIterations);
        counts.put("errorRate", errorRate(status));

        ObjectNode latency = node.putObject("latencyMillis");
        latency.put("p50", status.p50Millis);
        latency.put("p95", status.p95Millis);
        latency.put("p99", status.p99Millis);
        latency.put("p999", status.p999Millis);

        ArrayNode thresholds = node.putArray("thresholdResults");
        if (status.thresholdResults != null) {
            for (ThresholdResult result : status.thresholdResults) {
                ObjectNode r = thresholds.addObject();
                if (result.metric != null) {
                    r.put("metric", result.metric);
                }
                if (result.comparator != null) {
                    r.put("comparator", result.comparator);
                }
                r.put("threshold", result.threshold);
                r.put("observed", result.observed);
                r.put("satisfied", result.satisfied);
            }
        }

        ArrayNode checks = node.putArray("checkResults");
        if (status.checkResults != null) {
            for (CheckResult result : status.checkResults) {
                ObjectNode c = checks.addObject();
                if (result.step != null) {
                    c.put("step", result.step);
                }
                if (result.source != null) {
                    c.put("source", result.source);
                }
                if (result.detail != null && !result.detail.isEmpty()) {
                    c.put("detail", result.detail);
                }
                if (result.comparator != null) {
                    c.put("comparator", result.comparator);
                }
                if (result.value != null) {
                    c.put("value", result.value);
                }
                c.put("passed", result.passed);
                c.put("failed", result.failed);
            }
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize load scenario report to JSON", e);
        }
    }

    /**
     * Build the JUnit-XML summary report for a run. The suite has one testcase per threshold plus a
     * {@code run completed} testcase; breached thresholds and a failed run carry a {@code <failure>}.
     */
    public static String toJUnitXml(LoadScenarioStatus status) {
        int thresholdCount = status.thresholdResults != null ? status.thresholdResults.size() : 0;
        int checkCount = status.checkResults != null ? status.checkResults.size() : 0;
        int tests = thresholdCount + checkCount + 1;
        int failures = 0;
        if (status.thresholdResults != null) {
            for (ThresholdResult result : status.thresholdResults) {
                if (!result.satisfied) {
                    failures++;
                }
            }
        }
        if (status.checkResults != null) {
            for (CheckResult result : status.checkResults) {
                if (result.failed > 0) {
                    failures++;
                }
            }
        }
        boolean runFailed = runFailed(status);
        if (runFailed) {
            failures++;
        }
        double durationSeconds = status.elapsedMillis / 1000.0;

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"load:").append(escape(status.name)).append('"')
            .append(" tests=\"").append(tests).append('"')
            .append(" failures=\"").append(failures).append('"')
            .append(" time=\"").append(formatSeconds(durationSeconds)).append("\">\n");

        xml.append("  <properties>\n");
        appendProperty(xml, "runId", status.runId);
        appendProperty(xml, "state", status.state != null ? status.state.name() : "");
        appendProperty(xml, "verdict", status.verdict != null ? status.verdict : "");
        appendProperty(xml, "requestsSent", Long.toString(status.requestsSent));
        appendProperty(xml, "succeeded", Long.toString(status.succeeded));
        appendProperty(xml, "failed", Long.toString(status.failed));
        appendProperty(xml, "droppedIterations", Long.toString(status.droppedIterations));
        appendProperty(xml, "errorRate", Double.toString(errorRate(status)));
        appendProperty(xml, "p50Millis", Long.toString(status.p50Millis));
        appendProperty(xml, "p95Millis", Long.toString(status.p95Millis));
        appendProperty(xml, "p99Millis", Long.toString(status.p99Millis));
        appendProperty(xml, "p999Millis", Long.toString(status.p999Millis));
        xml.append("  </properties>\n");

        if (status.thresholdResults != null) {
            for (ThresholdResult result : status.thresholdResults) {
                String name = "threshold: " + result.metric + " " + result.comparator + " " + formatNumber(result.threshold);
                xml.append("  <testcase name=\"").append(escape(name)).append("\">\n");
                if (!result.satisfied) {
                    String message = "observed " + formatNumber(result.observed) + " "
                        + result.comparator + " " + formatNumber(result.threshold) + " not satisfied";
                    xml.append("    <failure message=\"").append(escape(message)).append("\"/>\n");
                }
                xml.append("  </testcase>\n");
            }
        }

        if (status.checkResults != null) {
            for (CheckResult result : status.checkResults) {
                String detail = result.detail != null && !result.detail.isEmpty() ? " " + result.detail : "";
                String name = "check: step " + result.step + " " + result.source + detail
                    + " " + result.comparator + " " + result.value;
                xml.append("  <testcase name=\"").append(escape(name)).append('"')
                    .append(" assertions=\"").append(result.passed + result.failed).append("\">\n");
                if (result.failed > 0) {
                    String message = result.failed + " of " + (result.passed + result.failed)
                        + " evaluations failed";
                    xml.append("    <failure message=\"").append(escape(message)).append("\"/>\n");
                }
                xml.append("  </testcase>\n");
            }
        }

        xml.append("  <testcase name=\"run completed\">\n");
        if (runFailed) {
            xml.append("    <failure message=\"").append(escape(runFailureMessage(status))).append("\"/>\n");
        }
        xml.append("    <system-out>")
            .append(escape("requestsSent=" + status.requestsSent
                + " succeeded=" + status.succeeded
                + " failed=" + status.failed
                + " errorRate=" + formatNumber(errorRate(status))
                + " p95Millis=" + status.p95Millis
                + " p99Millis=" + status.p99Millis))
            .append("</system-out>\n");
        xml.append("  </testcase>\n");

        xml.append("</testsuite>\n");
        return xml.toString();
    }

    private static String runFailureMessage(LoadScenarioStatus status) {
        if (status.abortedByThreshold) {
            return "run aborted early by an abortOnFail threshold breach";
        }
        if ("FAIL".equals(status.verdict)) {
            return "run finished with a FAIL threshold verdict";
        }
        return "run finished in state " + (status.state != null ? status.state.name() : "UNKNOWN")
            + " with " + status.failed + " failed request(s)";
    }

    private static void appendProperty(StringBuilder xml, String name, String value) {
        xml.append("    <property name=\"").append(escape(name))
            .append("\" value=\"").append(escape(value)).append("\"/>\n");
    }

    private static String formatSeconds(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", seconds);
    }

    /** Render a double without a trailing {@code .0} for whole numbers (e.g. thresholds like 250). */
    private static String formatNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** XML-escape text content / attribute values for the five predefined XML entities. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
