package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.mcp.McpToolRegistry;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Data-plane REPLAY tests for VCR-style cassettes loaded through the LLM MCP
 * {@code load_expectations_from_file} tool, driving a real {@link MockServer} over a real socket.
 * <p>
 * A cassette is a fixture file of recorded {@code request -> response} pairs. The existing tests
 * ({@code LlmMcpToolsTest}, {@code RecordLlmFixturesIntegrationTest}) load a cassette and assert only
 * the control-plane echo ({@code ACTIVE_EXPECTATIONS}) — they never drive a real request and check
 * that the recorded response body is actually <em>served</em>. These tests pin that serve path:
 * <ul>
 *     <li>a live request that matches a recorded entry is answered with the recorded response body
 *         (not a control-plane echo — the assertion is on the bytes the client received),</li>
 *     <li>a live request that matches no recorded entry is <em>not</em> served — it falls through to
 *         {@code 404 Not Found} rather than borrowing another entry's response,</li>
 *     <li>and when the cassette normalises a volatile request-body field (e.g. {@code request_id}),
 *         a live request carrying a <em>different</em> volatile value still matches and is served the
 *         recorded body — the whole point of replay normalisation.</li>
 * </ul>
 * The cassette is loaded through a {@link McpToolRegistry} bound to the running server's own
 * {@link HttpState}, so the loaded expectations are served by the same matcher the socket drives.
 */
public class CassetteReplayIntegrationTest {

    /** Exposes the running server's own {@link HttpState} so a cassette can be loaded into it. */
    private static final class TestableMockServer extends MockServer {
        HttpState httpState() {
            return httpState;
        }
    }

    private static TestableMockServer mockServer;
    private static NettyHttpClient httpClient;
    private static EventLoopGroup clientEventLoopGroup;
    private static McpToolRegistry toolRegistry;
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
        ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();

    @BeforeClass
    public static void startServerAndClient() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(
            CassetteReplayIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);
        mockServer = new TestableMockServer();
        toolRegistry = new McpToolRegistry(mockServer.httpState(), mockServer);
    }

    @AfterClass
    public static void stopServerAndClient() {
        stopQuietly(mockServer);
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Before
    public void resetBefore() throws Exception {
        reset();
    }

    @After
    public void resetAfter() throws Exception {
        reset();
    }

    private void reset() throws Exception {
        HttpResponse response = send("PUT", "/mockserver/reset", null);
        assertThat("reset must succeed so each test starts from an empty server", response.getStatusCode(), is(200));
    }

    /** Loads a cassette JSON string through the real MCP load path into the running server. */
    private JsonNode loadCassette(String cassetteJson, String... normalizeRequestBodyFields) throws Exception {
        Path file = Files.createTempFile("cassette-replay", ".json");
        try {
            Files.write(file, cassetteJson.getBytes(StandardCharsets.UTF_8));
            com.fasterxml.jackson.databind.node.ObjectNode params = OBJECT_MAPPER.createObjectNode();
            params.put("path", file.toString());
            // strict off so a non-matching request falls through to a real 404 rather than a strict guard
            params.put("strict", false);
            if (normalizeRequestBodyFields.length > 0) {
                com.fasterxml.jackson.databind.node.ArrayNode fields = params.putArray("normalizeRequestBodyFields");
                for (String f : normalizeRequestBodyFields) {
                    fields.add(f);
                }
            }
            JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);
            assertThat("cassette must load", result.path("status").asText(), is("loaded"));
            return result;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private HttpResponse send(String method, String path, String body) throws Exception {
        org.mockserver.model.HttpRequest httpRequest = request()
            .withMethod(method)
            .withHeader(HOST.toString(), "localhost:" + mockServer.getLocalPort())
            .withPath(path);
        if (body != null) {
            httpRequest = httpRequest.withBody(body);
        }
        return httpClient.sendRequest(httpRequest).get(15, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // positive replay — a matching live request is served the recorded body
    // ------------------------------------------------------------------

    @Test
    public void shouldServeRecordedResponseBodyForMatchingLiveRequest() throws Exception {
        // given - a cassette with one recorded request -> response pair
        loadCassette("[{" +
            "\"httpRequest\":{\"method\":\"POST\",\"path\":\"/replay/hello\"}," +
            "\"httpResponse\":{\"statusCode\":200,\"body\":\"the recorded reply\"}}]");

        // when - a real request over the wire matches the recorded entry
        HttpResponse response = send("POST", "/replay/hello", null);

        // then - the CLIENT receives the recorded response body, not a control-plane echo
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("the recorded reply"));
    }

    // ------------------------------------------------------------------
    // negative - a live request matching no recorded entry is not served
    // ------------------------------------------------------------------

    @Test
    public void shouldReturnNotFoundForLiveRequestMatchingNoRecordedEntry() throws Exception {
        // given - a cassette that only records /replay/hello
        loadCassette("[{" +
            "\"httpRequest\":{\"method\":\"POST\",\"path\":\"/replay/hello\"}," +
            "\"httpResponse\":{\"statusCode\":200,\"body\":\"the recorded reply\"}}]");

        // when - a real request over the wire matches no recorded entry
        HttpResponse response = send("GET", "/replay/not-recorded", null);

        // then - it is not served the recorded body; it falls through to 404
        assertThat("an unrecorded request must not borrow another entry's response",
            response.getStatusCode(), is(404));
    }

    // ------------------------------------------------------------------
    // replay normalisation - a different volatile field value still matches
    // ------------------------------------------------------------------

    @Test
    public void shouldServeRecordedBodyWhenVolatileFieldDiffersAfterNormalization() throws Exception {
        // given - a cassette whose recorded request body carries a volatile request_id,
        // loaded with request_id normalised away so only the stable fields must match
        loadCassette("[{" +
            "\"httpRequest\":{\"method\":\"POST\",\"path\":\"/v1/messages\"," +
            "\"body\":{\"type\":\"STRING\",\"string\":\"{\\\"request_id\\\":\\\"req_ORIGINAL\\\",\\\"q\\\":\\\"weather\\\"}\"}}," +
            "\"httpResponse\":{\"statusCode\":200,\"body\":\"it is sunny\"}}]",
            "request_id");

        // when - a live request carries a DIFFERENT request_id but the same stable fields
        HttpResponse matching = send("POST", "/v1/messages",
            "{\"request_id\":\"req_COMPLETELY_DIFFERENT\",\"q\":\"weather\"}");

        // then - normalisation drops the volatile field so the entry still matches and is served
        assertThat(matching.getStatusCode(), is(200));
        assertThat(matching.getBodyAsString(), is("it is sunny"));

        // and - a live request whose STABLE field differs must NOT be served the recorded body
        HttpResponse nonMatching = send("POST", "/v1/messages",
            "{\"request_id\":\"req_X\",\"q\":\"stocks\"}");
        assertThat("normalisation must not collapse into matching every body",
            nonMatching.getStatusCode(), is(404));
    }
}
