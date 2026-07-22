package org.mockserver.dashboard;

import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.Test;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.scheduler.Scheduler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Cross-boundary contract test that pins the EXACT WebSocket frame the
 * {@link DashboardWebSocketHandler} emits to the dashboard against a checked-in golden fixture that
 * the mockserver-ui store/hook tests also consume ({@code mockserver-ui/src/__tests__/fixtures/
 * dashboard-ws-frame.golden.json}).
 * <p>
 * Both sides of the dashboard WebSocket contract are otherwise tested with independently
 * hand-authored payloads (the Java handler test asserts a hand-written expected string; the UI
 * {@code useWebSocket}/store test feeds a hand-written {@code MockWebSocket} message). That lets the
 * two drift apart while both stay green — the #2419 "both sides of a contract mocked independently"
 * pattern. This test makes the SERVER produce the golden that the UI test reads, so a change to the
 * frame shape on either side breaks the build.
 * <p>
 * Dynamic tokens (expectation / log-entry UUIDs and capture timestamps) are normalised to stable
 * placeholders so the golden is deterministic across runs and machines. Run with
 * {@code -Dmockserver.dashboardGolden.update=true} to regenerate the golden after an intentional
 * frame-shape change.
 */
public class DashboardWebSocketFrameContractTest {

    private static final String UPDATE_GOLDEN_PROPERTY = "mockserver.dashboardGolden.update";

    @Test
    public void handlerFrameMatchesSharedUiGolden() throws InterruptedException, IOException {
        // given - a scenario that exercises all four dashboard panels: active expectations,
        // log messages, recorded (received) requests and proxied (forwarded) requests.
        Expectation expectation = new Expectation(request("/expected-path"))
            .thenRespond(response("expected-body"));

        LogEntry receivedRequestLog = new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setHttpRequest(request("/recorded-path"))
            .setMessageFormat("received request {}")
            .setArguments("/recorded-path");
        LogEntry forwardedRequestLog = new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/proxied-path"))
            .setHttpResponse(response("proxied-body"))
            .setMessageFormat("forwarded request {}")
            .setArguments("/proxied-path");
        LogEntry infoLog = new LogEntry()
            .setHttpRequest(request("/info-path"))
            .setMessageFormat("plain informational message");
        List<LogEntry> logEntries = Arrays.asList(receivedRequestLog, forwardedRequestLog, infoLog);

        MockServerLogger mockServerLogger = new MockServerLogger(DashboardWebSocketFrameContractTest.class);
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, true);
        HttpState httpState = new HttpState(configuration(), mockServerLogger, scheduler);
        new Scheduler.SchedulerThreadFactory("MockServer Test " + this.getClass().getSimpleName()).newThread(() -> {
            MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
            for (LogEntry logEntry : logEntries) {
                mockServerEventLog.add(logEntry);
            }
            RequestMatchers requestMatchers = httpState.getRequestMatchers();
            requestMatchers.update(new Expectation[]{expectation}, MockServerMatcherNotifier.Cause.API);
        }).start();
        SECONDS.sleep(1);

        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, true)
            .registerListeners();
        CapturingChannelHandlerContext ctx = new CapturingChannelHandlerContext();
        handler.getClientRegistry().put(ctx, request());

        // when
        handler.sendUpdate(ctx, request());
        SECONDS.sleep(1);

        // then
        TextWebSocketFrame frame = ctx.textWebSocketFrame;
        assertThat("handler produced no dashboard frame", frame != null, is(true));

        // Normalise dynamic tokens to stable placeholders so the golden is deterministic across
        // runs and machines while still pinning the frame SHAPE (field names, nesting, which
        // fields the UI reads).
        String actual = normalise(frame.text());

        File goldenFile = locateGoldenFile();
        if (Boolean.getBoolean(UPDATE_GOLDEN_PROPERTY)) {
            Files.write(goldenFile.toPath(), actual.getBytes(StandardCharsets.UTF_8));
            return;
        }

        assertThat("golden fixture not found at " + goldenFile.getAbsolutePath(), goldenFile.isFile(), is(true));
        String golden = new String(Files.readAllBytes(goldenFile.toPath()), StandardCharsets.UTF_8);
        assertThat(
            "The dashboard WebSocket frame drifted from the shared UI golden ("
                + goldenFile.getPath() + "). If this change is intentional, regenerate with "
                + "-D" + UPDATE_GOLDEN_PROPERTY + "=true and review the diff so the mockserver-ui "
                + "store/hook contract test is updated to match.",
            normaliseLineEndings(actual),
            is(normaliseLineEndings(golden))
        );
    }

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Replace every UUID (expectation ids, log-entry ids — which flow through a disruptor ring
     * buffer so are not the same reference the test holds) with a stable {@code <ID_n>} placeholder
     * assigned in order of FIRST appearance. This keeps distinct rows distinct (so the UI store can
     * still reconcile them by key) without depending on holding the server-side references. Then
     * regex-normalise the two timestamp forms the serializer emits (the full
     * {@code yyyy-MM-dd HH:mm:ss.SSS} and the {@code substringAfter("-")} form in the row
     * description) to a single placeholder — the timestamp value is opaque to the UI and two entries
     * can share a millisecond, which would otherwise make the golden flaky.
     */
    private static String normalise(String frameText) {
        Map<String, String> idPlaceholders = new LinkedHashMap<>();
        Matcher matcher = UUID_PATTERN.matcher(frameText);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String uuid = matcher.group();
            String placeholder = idPlaceholders.computeIfAbsent(uuid, u -> "<ID_" + idPlaceholders.size() + ">");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        String result = sb.toString();
        // Full timestamp first so the suffix regex below cannot match inside it.
        result = result.replaceAll("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}", "<TIMESTAMP>");
        // The row description uses substringAfter(timestamp, "-") == "MM-dd HH:mm:ss.SSS".
        result = result.replaceAll("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}", "<TIMESTAMP_SUFFIX>");
        return result;
    }

    private static String normaliseLineEndings(String value) {
        return value.replace("\r\n", "\n").trim();
    }

    /**
     * Resolve the shared golden file by walking up from the working directory until the repo root
     * (the directory that contains {@code mockserver-ui}) is found. Robust to being run from the
     * module dir (Maven surefire) or the repo root.
     */
    private static File locateGoldenFile() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, "mockserver-ui/src/__tests__/fixtures/dashboard-ws-frame.golden.json");
            if (new File(dir, "mockserver-ui").isDirectory()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        // Fall back to the conventional relative path from the module directory.
        return new File("../../mockserver-ui/src/__tests__/fixtures/dashboard-ws-frame.golden.json");
    }

    /**
     * Minimal channel context that captures the last {@link TextWebSocketFrame} the handler writes
     * (mirrors the harness in {@link DashboardWebSocketHandlerTest}).
     */
    private static class CapturingChannelHandlerContext extends EmbeddedChannel {

        private TextWebSocketFrame textWebSocketFrame;

        @Override
        public ChannelFuture writeAndFlush(Object msg) {
            if (msg instanceof TextWebSocketFrame) {
                textWebSocketFrame = (TextWebSocketFrame) msg;
            }
            return null;
        }
    }
}
