package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Runs {@link McpContractTest} -- MockServer's own MCP conformance checker, which ships in
 * {@code src/main} and is offered to users as the {@code run_mcp_contract_test} tool -- against
 * MockServer's OWN MCP endpoint.
 * <p>
 * {@link McpContractTestTest} only exercises the checker against hand-written fake exchanges, so
 * until now the checker had never been pointed at the server it lives beside: MockServer asserted
 * other people's MCP conformance while its own went unverified. The transport here is a real
 * {@link McpStreamableHttpHandler} on an {@link EmbeddedChannel}, so this needs no network and no
 * port binding while still exercising genuine request framing, session handling and dispatch.
 */
public class McpContractTestAgainstMockServerTest {

    private EmbeddedChannel channel;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1080));
        when(server.isRunning()).thenReturn(true);

        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        McpSessionManager sessionManager = new McpSessionManager(httpState.getMockServerLogger());
        channel = new EmbeddedChannel(new McpStreamableHttpHandler(httpState, server, sessionManager));
        objectMapper = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
    }

    /** Wires the contract test's transport to the real MCP handler over the embedded channel. */
    private McpContractTest.JsonRpcExchange liveExchange() {
        return (message, sessionId) -> {
            try {
                FullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/mockserver/mcp",
                    Unpooled.copiedBuffer(objectMapper.writeValueAsBytes(message)));
                request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                if (sessionId != null) {
                    request.headers().set("Mcp-Session-Id", sessionId);
                }
                channel.writeInbound(request);

                FullHttpResponse response = null;
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline && response == null) {
                    response = channel.readOutbound();
                    if (response == null) {
                        Thread.sleep(10);
                    }
                }
                if (response == null) {
                    return McpContractTest.ExchangeResult.transportError("no response within 5s");
                }
                try {
                    String content = response.content().toString(StandardCharsets.UTF_8);
                    JsonNode body = content.isEmpty() ? null : objectMapper.readTree(content);
                    return new McpContractTest.ExchangeResult(
                        response.status().code(), response.headers().get("Mcp-Session-Id"), body, null);
                } finally {
                    response.release();
                }
            } catch (Exception e) {
                return McpContractTest.ExchangeResult.transportError(e.getMessage());
            }
        };
    }

    @Test
    public void shouldPassItsOwnMcpConformanceSuite() {
        McpContractTest.Report report =
            new McpContractTest(objectMapper).run(null, "get_status", liveExchange());

        List<String> failures = new ArrayList<>();
        for (McpContractTest.CheckResult check : report.getChecks()) {
            if (!check.isPassed()) {
                failures.add(check.getCheck() + " -> " + check.getValidationErrors());
            }
        }

        assertThat("MockServer's own MCP endpoint failed its own conformance checks: " + failures,
            failures.isEmpty(), is(true));
        // exact, not a floor: a floor would not notice the tools/call check silently disappearing,
        // which is the same over-wide tolerance this unit exists to remove
        assertThat("the suite must run every check, not a subset",
            report.getChecks().size(), is(6));
        assertThat(report.getServerName(), is("MockServer"));
        assertThat(report.getServerVersion(), notNullValue());
    }

    /**
     * The server negotiates the protocol version rather than hardcoding it -- an older client is
     * echoed its own supported revision, not silently upgraded.
     */
    @Test
    public void shouldNegotiateAnOlderProtocolVersionDuringConformanceRun() {
        McpContractTest.Report report =
            new McpContractTest(objectMapper).run("2025-03-26", null, liveExchange());

        assertThat(report.getProtocolVersion(), is("2025-03-26"));
        for (McpContractTest.CheckResult check : report.getChecks()) {
            assertThat(check.getCheck() + " failed: " + check.getValidationErrors(),
                check.isPassed(), is(true));
        }
    }

    /**
     * An unsupported revision must fall back to the server's latest rather than being echoed back,
     * which would falsely advertise support.
     */
    @Test
    public void shouldFallBackToLatestProtocolVersionForUnsupportedRevision() {
        McpContractTest.Report report =
            new McpContractTest(objectMapper).run("1999-01-01", null, liveExchange());

        assertThat(report.getProtocolVersion(), is(McpContractTest.DEFAULT_PROTOCOL_VERSION));
    }

    // ---- session semantics (MCP 2025-06-18 basic/transports) ----
    //
    // The shipped checker performs only shape checks and never exercises session handling, so
    // these cover it directly. The 404 is load-bearing: the spec says a client receiving 404 for a
    // session id "MUST start a new session", so answering 200 with a JSON-RPC error leaves a
    // conformant client unable to ever perform the mandated recovery.

    @Test
    public void shouldReturnNotFoundForUnknownSession() throws Exception {
        McpContractTest.ExchangeResult result = liveExchange().send(
            toolsListRequest(), "a-session-that-was-never-issued");

        assertThat(result.getStatusCode(), is(404));
    }

    @Test
    public void shouldReturnNotFoundForTerminatedSession() throws Exception {
        McpContractTest.ExchangeResult init = liveExchange().send(initializeRequest(), null);
        String sessionId = init.getSessionId();

        FullHttpRequest delete = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.DELETE, "/mockserver/mcp");
        delete.headers().set("Mcp-Session-Id", sessionId);
        channel.writeInbound(delete);
        FullHttpResponse deleteResponse = channel.readOutbound();
        assertThat(deleteResponse.status().code(), is(200));
        deleteResponse.release();

        McpContractTest.ExchangeResult afterTermination = liveExchange().send(toolsListRequest(), sessionId);

        assertThat("a terminated session must be 404 so the client knows to start a new one",
            afterTermination.getStatusCode(), is(404));
    }

    @Test
    public void shouldReturnBadRequestWhenSessionIdIsMissingOnNonInitialize() throws Exception {
        McpContractTest.ExchangeResult result = liveExchange().send(toolsListRequest(), null);

        assertThat("a missing session id is a malformed request, not a dead session",
            result.getStatusCode(), is(400));
    }

    /**
     * A blank session id must be treated as absent (400), not as an unknown session (404).
     * The transports disagree on how a missing header arrives at the processor: the HTTP/1.1 and
     * HTTP/2 handlers pass null, while the HTTP/3 handler uses {@code HttpRequest.getFirstHeader},
     * which returns {@code ""} by MockServer convention. Asserted directly against the
     * transport-neutral processor so both spellings are pinned in one place -- a null-only check
     * answered 400 over HTTP/1.1 and 404 over HTTP/3 for the identical request.
     */
    @Test
    public void shouldTreatBlankSessionIdAsMissingRatherThanUnknown() throws Exception {
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1080));
        when(server.isRunning()).thenReturn(true);
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        McpRequestProcessor processor = new McpRequestProcessor(
            httpState, server, new McpSessionManager(httpState.getMockServerLogger()));

        String toolsList = "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/list\",\"params\":{}}";

        assertThat("null session id (HTTP/1.1 and HTTP/2 spelling)",
            processor.handlePost(toolsList, null).getStatusCode(), is(400));
        assertThat("empty session id (HTTP/3 spelling via getFirstHeader)",
            processor.handlePost(toolsList, "").getStatusCode(), is(400));
        assertThat("whitespace-only session id",
            processor.handlePost(toolsList, "   ").getStatusCode(), is(400));
        assertThat("a genuinely unknown session is still 404, not collapsed into 400",
            processor.handlePost(toolsList, "never-issued").getStatusCode(), is(404));
    }

    private ObjectNode initializeRequest() {
        ObjectNode init = objectMapper.createObjectNode();
        init.put("jsonrpc", "2.0");
        init.put("id", 1);
        init.put("method", "initialize");
        init.putObject("params").put("protocolVersion", McpContractTest.DEFAULT_PROTOCOL_VERSION);
        return init;
    }

    private ObjectNode toolsListRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 99);
        request.put("method", "tools/list");
        request.putObject("params");
        return request;
    }

    /**
     * The notification response must be exactly 202 with no body, per MCP 2025-06-18
     * {@code basic/transports}. The shipped checker now enforces that rather than accepting
     * 200/202/204, so this pins the server side of the same contract.
     */
    @Test
    public void shouldReturnAcceptedWithNoBodyForNotification() {
        ObjectNode init = objectMapper.createObjectNode();
        init.put("jsonrpc", "2.0");
        init.put("id", 1);
        init.put("method", "initialize");
        init.putObject("params").put("protocolVersion", McpContractTest.DEFAULT_PROTOCOL_VERSION);
        McpContractTest.ExchangeResult initResult = liveExchange().send(init, null);

        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        McpContractTest.ExchangeResult notifResult =
            liveExchange().send(notification, initResult.getSessionId());

        assertThat(notifResult.getStatusCode(), is(202));
        assertThat("a notification response must have no JSON-RPC body", notifResult.getBody(), is((JsonNode) null));
    }
}
