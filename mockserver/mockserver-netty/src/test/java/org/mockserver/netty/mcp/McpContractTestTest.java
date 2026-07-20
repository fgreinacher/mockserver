package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class McpContractTestTest {

    private final ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
    private final McpContractTest contractTest = new McpContractTest(mapper);

    /** A fully conformant MCP server simulation. */
    private final McpContractTest.JsonRpcExchange conformant = (message, sessionId) -> {
        String method = message.path("method").asText();
        JsonNode id = message.get("id");
        switch (method) {
            case "initialize": {
                ObjectNode body = envelope(id);
                ObjectNode result = body.putObject("result");
                result.put("protocolVersion", "2025-03-26");
                result.putObject("capabilities");
                result.putObject("serverInfo").put("name", "StubServer").put("version", "9.9");
                return new McpContractTest.ExchangeResult(200, "session-1", body, null);
            }
            case "notifications/initialized":
                return new McpContractTest.ExchangeResult(202, null, null, null);
            case "ping": {
                ObjectNode body = envelope(id);
                body.putObject("result");
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            case "tools/list": {
                ObjectNode body = envelope(id);
                ArrayNode tools = body.putObject("result").putArray("tools");
                ObjectNode tool = tools.addObject();
                tool.put("name", "do_thing");
                tool.putObject("inputSchema").put("type", "object");
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            case "tools/call": {
                ObjectNode body = envelope(id);
                ObjectNode result = body.putObject("result");
                result.putArray("content").addObject().put("type", "text").put("text", "hi");
                result.put("isError", false);
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            default:
                return methodNotFound(id);
        }
    };

    private ObjectNode envelope(JsonNode id) {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.set("id", id);
        return body;
    }

    private McpContractTest.ExchangeResult methodNotFound(JsonNode id) {
        ObjectNode body = envelope(id);
        body.putObject("error").put("code", -32601).put("message", "Method not found");
        return new McpContractTest.ExchangeResult(200, null, body, null);
    }

    private McpContractTest.CheckResult check(McpContractTest.Report report, String name) {
        return report.getChecks().stream()
            .filter(c -> c.getCheck().equals(name) || c.getCheck().startsWith(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no check named " + name));
    }

    @Test
    public void conformantServerPassesAllCoreChecks() {
        McpContractTest.Report report = contractTest.run(null, null, conformant);

        assertThat(report.getChecks().size(), is(5)); // no tools/call without a toolName
        for (McpContractTest.CheckResult c : report.getChecks()) {
            assertThat(c.getCheck() + " should pass: " + c.getValidationErrors(), c.isPassed(), is(true));
        }
        assertThat(report.getProtocolVersion(), is("2025-03-26"));
        assertThat(report.getServerName(), is("StubServer"));
        assertThat(report.getServerVersion(), is("9.9"));
        assertThat(check(report, "initialize").getDetail(), is("session established"));
        assertThat(check(report, "tools/list").getDetail(), is("1 tools advertised"));
    }

    @Test
    public void exercisesToolsCallWhenToolNameProvided() {
        McpContractTest.Report report = contractTest.run(null, "do_thing", conformant);

        assertThat(report.getChecks().size(), is(6));
        McpContractTest.CheckResult call = check(report, "tools/call");
        assertThat(call.getCheck(), containsString("do_thing"));
        assertThat(call.isPassed(), is(true));
        assertThat(call.getDetail(), is("isError=false"));
    }

    @Test
    public void usesSuppliedProtocolVersionWhenServerEchoesNone() {
        McpContractTest.JsonRpcExchange noVersionEcho = (message, sessionId) -> {
            if (message.path("method").asText().equals("initialize")) {
                ObjectNode body = envelope(message.get("id"));
                ObjectNode result = body.putObject("result");
                // protocolVersion intentionally omitted
                result.putObject("capabilities");
                result.putObject("serverInfo").put("name", "StubServer");
                return new McpContractTest.ExchangeResult(200, "s", body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run("2025-06-18", null, noVersionEcho);

        assertThat(report.getProtocolVersion(), is("2025-06-18"));
        assertThat(check(report, "initialize").isPassed(), is(false));
        assertThat(check(report, "initialize").getValidationErrors(), hasItem(containsString("protocolVersion is missing")));
    }

    @Test
    public void transportErrorOnInitializeReportsOnlyInitialize() {
        McpContractTest.JsonRpcExchange dead = (message, sessionId) ->
            McpContractTest.ExchangeResult.transportError("connection refused");

        McpContractTest.Report report = contractTest.run(null, null, dead);

        assertThat(report.getChecks().size(), is(1));
        assertThat(check(report, "initialize").isPassed(), is(false));
        assertThat(check(report, "initialize").getValidationErrors(), hasItem(containsString("could not connect")));
    }

    @Test
    public void initializeMissingServerInfoFails() {
        McpContractTest.JsonRpcExchange noServerInfo = (message, sessionId) -> {
            if (message.path("method").asText().equals("initialize")) {
                ObjectNode body = envelope(message.get("id"));
                ObjectNode result = body.putObject("result");
                result.put("protocolVersion", "2025-03-26");
                result.putObject("capabilities");
                // serverInfo omitted
                return new McpContractTest.ExchangeResult(200, "s", body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run(null, null, noServerInfo);

        assertThat(check(report, "initialize").isPassed(), is(false));
        assertThat(check(report, "initialize").getValidationErrors(), hasItem(containsString("serverInfo.name is missing")));
    }

    @Test
    public void toolsListWithMalformedToolFails() {
        McpContractTest.JsonRpcExchange badTool = (message, sessionId) -> {
            if (message.path("method").asText().equals("tools/list")) {
                ObjectNode body = envelope(message.get("id"));
                ArrayNode tools = body.putObject("result").putArray("tools");
                tools.addObject().put("name", "missing_schema"); // no inputSchema
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run(null, null, badTool);

        McpContractTest.CheckResult list = check(report, "tools/list");
        assertThat(list.isPassed(), is(false));
        assertThat(list.getValidationErrors(), hasItem(containsString("inputSchema")));
    }

    @Test
    public void unknownMethodWithWrongErrorCodeFails() {
        McpContractTest.JsonRpcExchange wrongCode = (message, sessionId) -> {
            String method = message.path("method").asText();
            if (!isKnownMethod(method)) {
                ObjectNode body = envelope(message.get("id"));
                body.putObject("error").put("code", -32000).put("message", "Server error");
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run(null, null, wrongCode);

        McpContractTest.CheckResult unknown = check(report, "rejects unknown method");
        assertThat(unknown.isPassed(), is(false));
        assertThat(unknown.getValidationErrors(), hasItem(containsString("expected error code -32601")));
    }

    @Test
    public void unknownMethodAcceptedWithoutErrorFails() {
        McpContractTest.JsonRpcExchange acceptsEverything = (message, sessionId) -> {
            String method = message.path("method").asText();
            if (!isKnownMethod(method)) {
                ObjectNode body = envelope(message.get("id"));
                body.putObject("result"); // wrongly returns a result for an unknown method
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run(null, null, acceptsEverything);

        assertThat(check(report, "rejects unknown method").isPassed(), is(false));
    }

    @Test
    public void toolsListAcceptsInputSchemaWithoutTypeKeyword() {
        McpContractTest.JsonRpcExchange noTypeKeyword = (message, sessionId) -> {
            if (message.path("method").asText().equals("tools/list")) {
                ObjectNode body = envelope(message.get("id"));
                ArrayNode tools = body.putObject("result").putArray("tools");
                ObjectNode tool = tools.addObject();
                tool.put("name", "do_thing");
                tool.putObject("inputSchema").putObject("properties"); // object schema, no "type" keyword
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run(null, null, noTypeKeyword);

        assertThat(check(report, "tools/list").isPassed(), is(true));
    }

    @Test
    public void toolsCallAcceptsOmittedIsError() {
        McpContractTest.JsonRpcExchange noIsError = (message, sessionId) -> {
            if (message.path("method").asText().equals("tools/call")) {
                ObjectNode body = envelope(message.get("id"));
                ObjectNode result = body.putObject("result");
                result.putArray("content").addObject().put("type", "text").put("text", "hi");
                // isError intentionally omitted (defaults to false per the MCP spec)
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run(null, "do_thing", noIsError);

        McpContractTest.CheckResult call = check(report, "tools/call");
        assertThat(call.isPassed(), is(true));
        assertThat(call.getDetail(), is("isError=false"));
    }

    @Test
    public void reportsNegotiated2025_06_18VersionAndValidatesNewCapabilityShapes() {
        // A conformant 2025-06-18 server: echoes the version and returns structuredContent plus a
        // resource_link content item on tools/call.
        McpContractTest.JsonRpcExchange server2025_06_18 = (message, sessionId) -> {
            String method = message.path("method").asText();
            JsonNode id = message.get("id");
            switch (method) {
                case "initialize": {
                    ObjectNode body = envelope(id);
                    ObjectNode result = body.putObject("result");
                    result.put("protocolVersion", "2025-06-18");
                    result.putObject("capabilities");
                    result.putObject("serverInfo").put("name", "StubServer").put("version", "9.9");
                    return new McpContractTest.ExchangeResult(200, "session-1", body, null);
                }
                case "tools/call": {
                    ObjectNode body = envelope(id);
                    ObjectNode result = body.putObject("result");
                    ArrayNode content = result.putArray("content");
                    content.addObject().put("type", "text").put("text", "done");
                    content.addObject().put("type", "resource_link").put("uri", "file:///out.txt").put("name", "out");
                    result.putObject("structuredContent").put("ok", true);
                    result.put("isError", false);
                    return new McpContractTest.ExchangeResult(200, null, body, null);
                }
                default:
                    return conformant.send(message, sessionId);
            }
        };

        McpContractTest.Report report = contractTest.run("2025-06-18", "do_thing", server2025_06_18);

        assertThat(report.getProtocolVersion(), is("2025-06-18"));
        McpContractTest.CheckResult call = check(report, "tools/call");
        assertThat(call.getValidationErrors().toString(), call.isPassed(), is(true));
    }

    @Test
    public void resourceLinkMissingUriFails() {
        McpContractTest.JsonRpcExchange badLink = (message, sessionId) -> {
            if (message.path("method").asText().equals("tools/call")) {
                ObjectNode body = envelope(message.get("id"));
                ObjectNode result = body.putObject("result");
                result.putArray("content").addObject().put("type", "resource_link"); // no uri
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run("2025-06-18", "do_thing", badLink);

        McpContractTest.CheckResult call = check(report, "tools/call");
        assertThat(call.isPassed(), is(false));
        assertThat(call.getValidationErrors(), hasItem(containsString("resource_link content item is missing 'uri'")));
    }

    @Test
    public void nonObjectStructuredContentFails() {
        McpContractTest.JsonRpcExchange badStructured = (message, sessionId) -> {
            if (message.path("method").asText().equals("tools/call")) {
                ObjectNode body = envelope(message.get("id"));
                ObjectNode result = body.putObject("result");
                result.putArray("content").addObject().put("type", "text").put("text", "hi");
                result.put("structuredContent", "not-an-object");
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run("2025-06-18", "do_thing", badStructured);

        McpContractTest.CheckResult call = check(report, "tools/call");
        assertThat(call.isPassed(), is(false));
        assertThat(call.getValidationErrors(), hasItem(containsString("structuredContent is present but not a JSON object")));
    }

    /**
     * The checker must FAIL a server that answers a notification with 200 and a JSON-RPC result
     * body. MCP 2025-06-18 {@code basic/transports} makes 202-with-no-body a MUST, and this exact
     * shape -- {@code 200} plus {@code {"jsonrpc":"2.0","result":{},"id":null}} -- is what
     * MockServer's own mock builders emit. The checker previously accepted 200/202/204 and only
     * rejected a body containing an "error" member, so it certified this as conformant: the
     * conformance tool could not detect the conformance defect it exists to detect.
     */
    @Test
    public void failsNotificationCheckForTwoHundredWithResultBody() {
        McpContractTest.JsonRpcExchange lenientServer = (message, sessionId) -> {
            String method = message.path("method").asText();
            if (method.equals("notifications/initialized")) {
                ObjectNode body = mapper.createObjectNode();
                body.put("jsonrpc", "2.0");
                body.putObject("result");
                body.putNull("id");
                return new McpContractTest.ExchangeResult(200, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.Report report = contractTest.run(null, null, lenientServer);

        McpContractTest.CheckResult notification = check(report, "notifications/initialized");
        assertThat(notification.isPassed(), is(false));
        assertThat(notification.getValidationErrors(), hasItem(containsString("expected HTTP 202 Accepted")));
        // the body objection is deliberately NOT raised here: the body rule applies to a 202, and the
        // spec permits a body on a non-202 rejection, so only the status is at fault
        assertThat(notification.getValidationErrors().size(), is(1));
    }

    /**
     * The body rule bites where it applies: a 202 carrying a JSON-RPC body. The spec requires
     * "202 Accepted with no body", so the status alone is not sufficient.
     */
    @Test
    public void failsNotificationCheckForAcceptedWithBody() {
        McpContractTest.JsonRpcExchange bodyOnAccepted = (message, sessionId) -> {
            if (message.path("method").asText().equals("notifications/initialized")) {
                ObjectNode body = mapper.createObjectNode();
                body.put("jsonrpc", "2.0");
                body.putObject("result");
                body.putNull("id");
                return new McpContractTest.ExchangeResult(202, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.CheckResult notification =
            check(contractTest.run(null, null, bodyOnAccepted), "notifications/initialized");

        assertThat(notification.isPassed(), is(false));
        assertThat(notification.getValidationErrors(), hasItem(containsString("expected no body")));
    }

    /**
     * A server that rejects the notification with an HTTP error and a JSON-RPC error body must not
     * be told "expected no body" -- MCP 2025-06-18 basic/transports permits exactly that shape on
     * the rejection path. Only the status is reported.
     */
    @Test
    public void doesNotObjectToABodyOnTheNotificationRejectionPath() {
        McpContractTest.JsonRpcExchange rejectingServer = (message, sessionId) -> {
            if (message.path("method").asText().equals("notifications/initialized")) {
                ObjectNode body = mapper.createObjectNode();
                body.put("jsonrpc", "2.0");
                body.putObject("error").put("code", -32600).put("message", "cannot accept");
                body.putNull("id");
                return new McpContractTest.ExchangeResult(400, null, body, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.CheckResult notification =
            check(contractTest.run(null, null, rejectingServer), "notifications/initialized");

        assertThat(notification.getValidationErrors(), hasItem(containsString("expected HTTP 202 Accepted")));
        for (String error : notification.getValidationErrors()) {
            assertThat("the spec permits a body on the rejection path", error, not(containsString("expected no body")));
        }
    }

    /**
     * A 200 with no body is still non-conformant on the status alone. This also pins that the
     * body check does not fire here: there is no body to object to, so the status error must be
     * the only finding.
     */
    @Test
    public void failsNotificationCheckForTwoHundredWithNoBody() {
        McpContractTest.JsonRpcExchange lenientServer = (message, sessionId) -> {
            if (message.path("method").asText().equals("notifications/initialized")) {
                return new McpContractTest.ExchangeResult(200, null, null, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.CheckResult notification =
            check(contractTest.run(null, null, lenientServer), "notifications/initialized");

        assertThat(notification.isPassed(), is(false));
        assertThat(notification.getValidationErrors(), hasItem(containsString("expected HTTP 202 Accepted")));
        assertThat(notification.getValidationErrors().size(), is(1));
    }

    /** A 204 is also non-conformant: the spec names 202 specifically. */
    @Test
    public void failsNotificationCheckForNoContentStatus() {
        McpContractTest.JsonRpcExchange noContentServer = (message, sessionId) -> {
            if (message.path("method").asText().equals("notifications/initialized")) {
                return new McpContractTest.ExchangeResult(204, null, null, null);
            }
            return conformant.send(message, sessionId);
        };

        McpContractTest.CheckResult notification =
            check(contractTest.run(null, null, noContentServer), "notifications/initialized");

        assertThat(notification.isPassed(), is(false));
        assertThat(notification.getValidationErrors(), hasItem(containsString("expected HTTP 202 Accepted")));
    }

    private boolean isKnownMethod(String method) {
        return method.equals("initialize")
            || method.equals("notifications/initialized")
            || method.equals("ping")
            || method.equals("tools/list")
            || method.equals("tools/call");
    }
}
