package org.mockserver.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.llm.realtime.RealtimeModality;
import org.mockserver.llm.realtime.RealtimeTurn;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpWebSocketResponse;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.model.WebSocketMessageMatcher;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class RealtimeMockBuilderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    @Test
    public void shouldBuildOpenAiRealtimeExpectation() throws Exception {
        Expectation expectation = RealtimeMockBuilder.openAiRealtime()
            .withModel("gpt-realtime")
            .respondingWith("Hello from the mock")
            .build();

        assertThat(((HttpRequest) expectation.getHttpRequest()).getMethod().getValue(), is("GET"));
        assertThat(((HttpRequest) expectation.getHttpRequest()).getPath().getValue(), is("/v1/realtime"));

        HttpWebSocketResponse ws = expectation.getHttpWebSocketResponse();
        assertThat(ws, notNullValue());

        // session.created is pushed on connect
        List<WebSocketMessage> pushed = ws.getMessages();
        assertThat(pushed, hasSize(1));
        assertThat(parse(pushed.get(0).getText()).path("type").asText(), is("session.created"));

        // three matchers: session.update, conversation.item.create, response.create
        List<WebSocketMessageMatcher> matchers = ws.getMatchers();
        assertThat(matchers, hasSize(3));

        WebSocketMessageMatcher responseCreate = matchers.get(2);
        List<WebSocketMessage> responses = responseCreate.getResponses();
        assertThat(parse(responses.get(0).getText()).path("type").asText(), is("response.created"));
        assertThat(parse(responses.get(responses.size() - 1).getText()).path("type").asText(), is("response.done"));
    }

    @Test
    public void shouldBuildGeminiLiveExpectationWithNoConnectPush() throws Exception {
        Expectation expectation = RealtimeMockBuilder.geminiLive()
            .withModality(RealtimeModality.TEXT)
            .respondingWith(RealtimeTurn.realtimeTurn("Hi").withInputTokens(3).withOutputTokens(1))
            .build();

        assertThat(((HttpRequest) expectation.getHttpRequest()).getPath().getValue(),
            is(RealtimeMockBuilder.DEFAULT_GEMINI_LIVE_PATH));

        HttpWebSocketResponse ws = expectation.getHttpWebSocketResponse();
        // Gemini does not push on connect (setupComplete answers the client's setup)
        assertThat(ws.getMessages(), anyOf(nullValue(), empty()));

        List<WebSocketMessageMatcher> matchers = ws.getMatchers();
        assertThat(matchers, hasSize(2));
        assertThat(parse(matchers.get(0).getResponses().get(0).getText()).has("setupComplete"), is(true));
        JsonNode lastClientContent = parse(matchers.get(1).getResponses()
            .get(matchers.get(1).getResponses().size() - 1).getText());
        assertThat(lastClientContent.path("serverContent").path("turnComplete").asBoolean(), is(true));
    }

    @Test
    public void shouldApplyStreamingDelaysToDeltaFrames() {
        Expectation expectation = RealtimeMockBuilder.openAiRealtime()
            .withModality(RealtimeModality.TEXT)
            .withTokensPerSecond(20) // 50ms per token
            .respondingWith("alpha beta gamma")
            .build();

        List<WebSocketMessage> responses = expectation.getHttpWebSocketResponse().getMatchers().get(2).getResponses();
        boolean sawDelay = false;
        for (WebSocketMessage message : responses) {
            if (message.getDelay() != null && message.getDelay().getValue() >= 50) {
                sawDelay = true;
            }
        }
        assertThat(sawDelay, is(true));
    }

    @Test
    public void shouldHonourCustomPathAndSubprotocol() {
        Expectation expectation = RealtimeMockBuilder.openAiRealtime("/custom/realtime")
            .withSubprotocol("realtime")
            .respondingWith("hi")
            .build();
        assertThat(((HttpRequest) expectation.getHttpRequest()).getPath().getValue(), is("/custom/realtime"));
        assertThat(expectation.getHttpWebSocketResponse().getSubprotocol(), is("realtime"));
    }
}
