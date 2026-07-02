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

    private boolean isKnownMethod(String method) {
        return method.equals("initialize")
            || method.equals("notifications/initialized")
            || method.equals("ping")
            || method.equals("tools/list")
            || method.equals("tools/call");
    }
}
