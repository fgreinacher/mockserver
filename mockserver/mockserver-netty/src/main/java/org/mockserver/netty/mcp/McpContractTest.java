package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic conformance / contract tester for a target MCP (Model Context Protocol)
 * server reachable over Streamable HTTP.
 *
 * <p>It exercises the required JSON-RPC handshake and core methods — {@code initialize},
 * {@code notifications/initialized}, {@code ping}, {@code tools/list}, unknown-method
 * rejection, and optionally a single {@code tools/call} — and reports per-check conformance.
 *
 * <p>This validates only the <em>shape</em> of responses (the JSON-RPC 2.0 envelope and the
 * required result fields of each method), never the semantics of any tool. No LLM and no
 * MockServer state are involved, so the outcome is fully deterministic.
 *
 * <p>The HTTP transport is injected as a {@link JsonRpcExchange} so the orchestration is unit
 * testable without a network, mirroring {@code org.mockserver.openapi.OpenApiContractTest}.
 */
public class McpContractTest {

    /**
     * Default MCP protocol version advertised by the client when the caller supplies none.
     * Corresponds to the 2025-06-18 MCP specification (the latest this project targets); callers
     * can override it via the {@code protocolVersion} argument to test a server against an older
     * revision (e.g. {@code 2025-03-26}). A conformant server may negotiate a different revision
     * back — the report captures whichever version the server actually echoes.
     */
    public static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    private static final String JSON_RPC_VERSION = "2.0";
    private static final int METHOD_NOT_FOUND = -32601;

    /** One JSON-RPC exchange against the target server. */
    @FunctionalInterface
    public interface JsonRpcExchange {
        /**
         * Send a single JSON-RPC message (request or notification) to the target server.
         *
         * @param message   the JSON-RPC message to POST
         * @param sessionId the MCP session id to echo back in the {@code Mcp-Session-Id} header, or null
         * @return the parsed exchange result (never null)
         */
        ExchangeResult send(ObjectNode message, String sessionId);
    }

    /** Result of a single JSON-RPC exchange. */
    public static class ExchangeResult {
        private final int statusCode;
        private final String sessionId;       // session id returned by the server, may be null
        private final JsonNode body;           // parsed JSON-RPC response, may be null
        private final String transportError;   // non-null when the request could not be completed

        public ExchangeResult(int statusCode, String sessionId, JsonNode body, String transportError) {
            this.statusCode = statusCode;
            this.sessionId = sessionId;
            this.body = body;
            this.transportError = transportError;
        }

        public static ExchangeResult transportError(String message) {
            return new ExchangeResult(0, null, null, message);
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getSessionId() {
            return sessionId;
        }

        public JsonNode getBody() {
            return body;
        }

        public String getTransportError() {
            return transportError;
        }
    }

    /** Outcome of a single named conformance check. */
    public static class CheckResult {
        private final String check;
        private boolean passed;
        private Integer statusCode;
        private String detail;
        private final List<String> validationErrors = new ArrayList<>();

        CheckResult(String check) {
            this.check = check;
        }

        public String getCheck() {
            return check;
        }

        public boolean isPassed() {
            return passed;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public String getDetail() {
            return detail;
        }

        public List<String> getValidationErrors() {
            return validationErrors;
        }
    }

    /** Full conformance report. */
    public static class Report {
        private final List<CheckResult> checks = new ArrayList<>();
        private String protocolVersion;
        private String serverName;
        private String serverVersion;

        public List<CheckResult> getChecks() {
            return checks;
        }

        public String getProtocolVersion() {
            return protocolVersion;
        }

        public String getServerName() {
            return serverName;
        }

        public String getServerVersion() {
            return serverVersion;
        }
    }

    private final ObjectMapper mapper;

    public McpContractTest(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Run the conformance suite against the target server.
     *
     * @param protocolVersion protocol version to advertise (null/blank → {@link #DEFAULT_PROTOCOL_VERSION})
     * @param toolName        optional tool to exercise via a {@code tools/call} shape check (null/blank → skipped)
     * @param exchange        the JSON-RPC transport
     * @return the conformance report
     */
    public Report run(String protocolVersion, String toolName, JsonRpcExchange exchange) {
        String version = (protocolVersion == null || protocolVersion.trim().isEmpty())
            ? DEFAULT_PROTOCOL_VERSION : protocolVersion.trim();
        Report report = new Report();
        report.protocolVersion = version;
        int id = 1;

        // 1. initialize — must return a well-formed result with protocolVersion, capabilities and serverInfo.name
        ObjectNode initParams = mapper.createObjectNode();
        initParams.put("protocolVersion", version);
        initParams.putObject("capabilities");
        ObjectNode clientInfo = initParams.putObject("clientInfo");
        clientInfo.put("name", "MockServer-contract-test");
        clientInfo.put("version", version);
        ExchangeResult initResult = exchange.send(request(id++, "initialize", initParams), null);

        CheckResult initCheck = new CheckResult("initialize");
        report.getChecks().add(initCheck);
        if (initResult.getTransportError() != null) {
            initCheck.validationErrors.add("could not connect to target: " + initResult.getTransportError());
            // cannot proceed without a successful initialize — report only this check
            return report;
        }
        initCheck.statusCode = initResult.getStatusCode();
        JsonNode initBody = initResult.getBody();
        validateJsonRpcResult(initCheck, initResult.getStatusCode(), initBody);
        if (initBody != null && initBody.has("result")) {
            JsonNode result = initBody.get("result");
            if (textOrNull(result, "protocolVersion") == null) {
                initCheck.validationErrors.add("result.protocolVersion is missing");
            } else {
                report.protocolVersion = result.get("protocolVersion").asText();
            }
            if (!result.path("capabilities").isObject()) {
                initCheck.validationErrors.add("result.capabilities object is missing");
            }
            JsonNode serverInfo = result.path("serverInfo");
            if (textOrNull(serverInfo, "name") == null) {
                initCheck.validationErrors.add("result.serverInfo.name is missing");
            } else {
                report.serverName = serverInfo.get("name").asText();
                report.serverVersion = textOrNull(serverInfo, "version");
            }
        }
        initCheck.passed = initCheck.validationErrors.isEmpty();
        String sessionId = initResult.getSessionId();
        if (initCheck.passed) {
            initCheck.detail = sessionId != null ? "session established" : "no session id returned";
        } else {
            initCheck.detail = "initialize failed";
        }

        // 2. notifications/initialized — a notification (no id); the server should accept it without a JSON-RPC error
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", JSON_RPC_VERSION);
        notification.put("method", "notifications/initialized");
        ExchangeResult notifResult = exchange.send(notification, sessionId);
        CheckResult notifCheck = new CheckResult("notifications/initialized");
        report.getChecks().add(notifCheck);
        if (notifResult.getTransportError() != null) {
            notifCheck.validationErrors.add("transport error: " + notifResult.getTransportError());
        } else {
            notifCheck.statusCode = notifResult.getStatusCode();
            int status = notifResult.getStatusCode();
            if (status != 200 && status != 202 && status != 204) {
                notifCheck.validationErrors.add("expected HTTP 200, 202 or 204 for a notification, got " + status);
            }
            if (notifResult.getBody() != null && notifResult.getBody().has("error")) {
                notifCheck.validationErrors.add("server returned a JSON-RPC error for the notification");
            }
            if (notifResult.getSessionId() != null) {
                sessionId = notifResult.getSessionId();
            }
        }
        notifCheck.passed = notifCheck.validationErrors.isEmpty();

        // 3. ping — must return a JSON-RPC result (an often-empty object)
        ExchangeResult pingResult = exchange.send(request(id++, "ping", mapper.createObjectNode()), sessionId);
        CheckResult pingCheck = new CheckResult("ping");
        report.getChecks().add(pingCheck);
        if (pingResult.getTransportError() != null) {
            pingCheck.validationErrors.add("transport error: " + pingResult.getTransportError());
        } else {
            pingCheck.statusCode = pingResult.getStatusCode();
            validateJsonRpcResult(pingCheck, pingResult.getStatusCode(), pingResult.getBody());
        }
        pingCheck.passed = pingCheck.validationErrors.isEmpty();

        // 4. tools/list — result.tools must be an array of tools, each with a name and an object inputSchema
        ExchangeResult listResult = exchange.send(request(id++, "tools/list", mapper.createObjectNode()), sessionId);
        CheckResult listCheck = new CheckResult("tools/list");
        report.getChecks().add(listCheck);
        if (listResult.getTransportError() != null) {
            listCheck.validationErrors.add("transport error: " + listResult.getTransportError());
        } else {
            listCheck.statusCode = listResult.getStatusCode();
            JsonNode body = listResult.getBody();
            validateJsonRpcResult(listCheck, listResult.getStatusCode(), body);
            if (body != null && body.has("result")) {
                JsonNode toolsNode = body.get("result").path("tools");
                if (!toolsNode.isArray()) {
                    listCheck.validationErrors.add("result.tools is not an array");
                } else {
                    int malformed = 0;
                    for (JsonNode tool : toolsNode) {
                        JsonNode inputSchema = tool.path("inputSchema");
                        // inputSchema must be a JSON object; per JSON Schema "type" is optional,
                        // so only reject it when present and not "object" (avoids false failures
                        // against conformant servers that omit the redundant type keyword).
                        boolean badSchema = !inputSchema.isObject()
                            || (inputSchema.has("type") && !"object".equals(textOrNull(inputSchema, "type")));
                        if (textOrNull(tool, "name") == null || badSchema) {
                            malformed++;
                        }
                    }
                    if (malformed > 0) {
                        listCheck.validationErrors.add(malformed + " of " + toolsNode.size()
                            + " tools are missing a name or a valid object inputSchema");
                    }
                    listCheck.detail = toolsNode.size() + " tools advertised";
                }
            }
        }
        listCheck.passed = listCheck.validationErrors.isEmpty();

        // 5. unknown method — the server must reject it with a JSON-RPC error code -32601 (Method not found)
        ExchangeResult unknownResult = exchange.send(
            request(id++, "mockserver/contract-test/does-not-exist", mapper.createObjectNode()), sessionId);
        CheckResult unknownCheck = new CheckResult("rejects unknown method");
        report.getChecks().add(unknownCheck);
        if (unknownResult.getTransportError() != null) {
            unknownCheck.validationErrors.add("transport error: " + unknownResult.getTransportError());
        } else {
            unknownCheck.statusCode = unknownResult.getStatusCode();
            JsonNode body = unknownResult.getBody();
            JsonNode error = body == null ? null : body.get("error");
            if (error == null || !error.isObject()) {
                unknownCheck.validationErrors.add("expected a JSON-RPC error object for an unknown method");
            } else if (!error.path("code").isInt() && !error.path("code").isLong()) {
                unknownCheck.validationErrors.add("JSON-RPC error is missing an integer 'code'");
            } else if (error.get("code").asInt() != METHOD_NOT_FOUND) {
                unknownCheck.validationErrors.add("expected error code " + METHOD_NOT_FOUND
                    + " (Method not found), got " + error.get("code").asInt());
            } else {
                unknownCheck.detail = "responded with code " + METHOD_NOT_FOUND;
            }
        }
        unknownCheck.passed = unknownCheck.validationErrors.isEmpty();

        // 6. tools/call (optional) — shape check only; skipped unless the caller names a tool, since a call may have side effects
        if (toolName != null && !toolName.trim().isEmpty()) {
            String tool = toolName.trim();
            ObjectNode callParams = mapper.createObjectNode();
            callParams.put("name", tool);
            callParams.putObject("arguments");
            ExchangeResult callResult = exchange.send(request(id, "tools/call", callParams), sessionId);
            CheckResult callCheck = new CheckResult("tools/call (" + tool + ")");
            report.getChecks().add(callCheck);
            if (callResult.getTransportError() != null) {
                callCheck.validationErrors.add("transport error: " + callResult.getTransportError());
            } else {
                callCheck.statusCode = callResult.getStatusCode();
                JsonNode body = callResult.getBody();
                validateJsonRpcResult(callCheck, callResult.getStatusCode(), body);
                if (body != null && body.has("result")) {
                    JsonNode result = body.get("result");
                    if (!result.path("content").isArray()) {
                        callCheck.validationErrors.add("result.content is not an array");
                    } else {
                        for (JsonNode item : result.get("content")) {
                            String type = textOrNull(item, "type");
                            if (type == null) {
                                callCheck.validationErrors.add("a content item is missing 'type'");
                                break;
                            }
                            // Resource links (MCP 2025-06-18) are an optional content type; when a
                            // server returns one it must carry a 'uri'. Only validated when present,
                            // so servers on older revisions are unaffected.
                            if ("resource_link".equals(type) && textOrNull(item, "uri") == null) {
                                callCheck.validationErrors.add("a resource_link content item is missing 'uri'");
                                break;
                            }
                        }
                    }
                    // Structured tool output (MCP 2025-06-18) is optional; when present the
                    // 'structuredContent' field must be a JSON object.
                    if (result.has("structuredContent") && !result.path("structuredContent").isObject()) {
                        callCheck.validationErrors.add("result.structuredContent is present but not a JSON object");
                    }
                    // isError is optional in the MCP spec (absent means false); only flag a wrong type
                    if (result.has("isError") && !result.path("isError").isBoolean()) {
                        callCheck.validationErrors.add("result.isError is present but not a boolean");
                    }
                    callCheck.detail = "isError=" + result.path("isError").asBoolean(false);
                }
            }
            callCheck.passed = callCheck.validationErrors.isEmpty();
        }

        return report;
    }

    private ObjectNode request(int id, String method, ObjectNode params) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", JSON_RPC_VERSION);
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);
        return message;
    }

    /**
     * Validate the common JSON-RPC success envelope for a request that is expected to return a result:
     * HTTP 200, a parseable body, {@code jsonrpc} "2.0", and a {@code result} (and not an {@code error}).
     */
    private void validateJsonRpcResult(CheckResult check, int statusCode, JsonNode body) {
        if (statusCode != 200) {
            check.validationErrors.add("expected HTTP 200, got " + statusCode);
        }
        if (body == null) {
            check.validationErrors.add("response body was empty or not valid JSON");
            return;
        }
        if (!JSON_RPC_VERSION.equals(textOrNull(body, "jsonrpc"))) {
            check.validationErrors.add("missing or invalid 'jsonrpc' version (expected \"2.0\")");
        }
        if (body.has("error")) {
            check.validationErrors.add("server returned a JSON-RPC error: "
                + body.get("error").path("message").asText(""));
        } else if (!body.has("result")) {
            check.validationErrors.add("response has neither 'result' nor 'error'");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return (text == null || text.trim().isEmpty()) ? null : text;
    }
}
