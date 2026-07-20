package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Arrays;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Proves that a tool whose handler throws still produces a JSON-RPC error response.
 * <p>
 * MCP POST processing is handed to a separate executor; before the fix an exception escaping
 * {@code McpRequestProcessor.handlePost} propagated out of the executor task with no response
 * ever written, so the client waited until its own timeout rather than receiving the
 * {@code -32603 Internal error} envelope JSON-RPC requires.
 */
public class McpToolFailureTest {

    private EmbeddedChannel channel;
    private ObjectMapper objectMapper;

    /** A registry whose tools are all registered normally but always throw when invoked. */
    private static class ThrowingToolRegistry extends McpToolRegistry {
        ThrowingToolRegistry(HttpState httpState, LifeCycle server) {
            super(httpState, server);
        }

        @Override
        public JsonNode callTool(String name, JsonNode params) {
            throw new IllegalStateException("tool exploded: " + name);
        }
    }

    @Before
    public void setUp() {
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1080));
        when(server.isRunning()).thenReturn(true);

        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        McpSessionManager sessionManager = new McpSessionManager(httpState.getMockServerLogger());
        McpRequestProcessor processor = new McpRequestProcessor(
            httpState, server, sessionManager, new ThrowingToolRegistry(httpState, server));
        channel = new EmbeddedChannel(new McpStreamableHttpHandler(httpState, sessionManager, processor));
        objectMapper = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
    }

    private FullHttpResponse sendPost(String body, String sessionId) {
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/mockserver/mcp",
            Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        if (sessionId != null) {
            request.headers().set("Mcp-Session-Id", sessionId);
        }
        channel.writeInbound(request);
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            FullHttpResponse response = channel.readOutbound();
            if (response != null) {
                return response;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private String initializeAndGetSessionId() {
        FullHttpResponse response = sendPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = response.headers().get("Mcp-Session-Id");
        response.release();
        FullHttpResponse notification = sendPost("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId);
        notification.release();
        return sessionId;
    }

    @Test
    public void shouldReturnJsonRpcInternalErrorWhenToolThrows() throws Exception {
        String sessionId = initializeAndGetSessionId();

        FullHttpResponse response = sendPost(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"get_status\",\"arguments\":{}}}",
            sessionId);

        assertThat("a throwing tool must still produce a response, not hang the client", response, notNullValue());
        assertThat(response.status(), is(HttpResponseStatus.OK));

        JsonNode json = objectMapper.readTree(response.content().toString(StandardCharsets.UTF_8));
        assertThat(json.path("jsonrpc").asText(), is("2.0"));
        assertThat(json.path("error").path("code").asInt(), is(JsonRpcMessage.INTERNAL_ERROR));
        assertThat(json.path("id").asInt(), is(7));

        response.release();
    }

    @Test
    public void shouldNotLeakToolExceptionDetailToTheClient() throws Exception {
        String sessionId = initializeAndGetSessionId();

        FullHttpResponse response = sendPost(
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"get_status\",\"arguments\":{}}}",
            sessionId);

        assertThat(response, notNullValue());
        String content = response.content().toString(StandardCharsets.UTF_8);
        assertThat("the tool's exception message must not reach the client", content, is(not(containsString("tool exploded"))));
        assertThat(content, containsString("Internal error"));

        response.release();
    }

    @Test
    public void shouldReturnJsonRpcInternalErrorForEachEntryWhenToolThrowsInBatch() throws Exception {
        String sessionId = initializeAndGetSessionId();

        FullHttpResponse response = sendPost(
            "[{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"get_status\",\"arguments\":{}}},"
                + "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"get_status\",\"arguments\":{}}}]",
            sessionId);

        assertThat("a throwing tool inside a batch must still produce a response", response, notNullValue());

        JsonNode json = objectMapper.readTree(response.content().toString(StandardCharsets.UTF_8));
        assertThat(json.isArray(), is(true));
        assertThat(json.size(), is(2));
        assertThat(json.get(0).path("error").path("code").asInt(), is(JsonRpcMessage.INTERNAL_ERROR));
        assertThat(json.get(0).path("id").asInt(), is(11));
        assertThat(json.get(1).path("error").path("code").asInt(), is(JsonRpcMessage.INTERNAL_ERROR));
        assertThat(json.get(1).path("id").asInt(), is(12));

        response.release();
    }

    /**
     * Covers the handler's last-resort backstop: even a failure OUTSIDE per-method dispatch
     * (serialisation, session lookup) must not leave the executor task dead with no response.
     */
    @Test
    public void shouldReturnInternalErrorWhenProcessorItselfThrows() throws Exception {
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1080));
        when(server.isRunning()).thenReturn(true);
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        McpSessionManager sessionManager = new McpSessionManager(httpState.getMockServerLogger());
        McpRequestProcessor exploding = new McpRequestProcessor(httpState, server, sessionManager) {
            @Override
            public McpResult handlePost(String requestBody, String mcpSessionId, java.util.Set<String> scopes) {
                throw new IllegalStateException("processor exploded");
            }
        };
        channel = new EmbeddedChannel(new McpStreamableHttpHandler(httpState, sessionManager, exploding));

        FullHttpResponse response = sendPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null);

        assertThat("a throwing processor must still produce a response", response, notNullValue());
        JsonNode json = objectMapper.readTree(response.content().toString(StandardCharsets.UTF_8));
        assertThat(json.path("error").path("code").asInt(), is(JsonRpcMessage.INTERNAL_ERROR));

        response.release();
    }

    private static <T> org.hamcrest.Matcher<T> not(org.hamcrest.Matcher<T> matcher) {
        return org.hamcrest.core.IsNot.not(matcher);
    }
}
