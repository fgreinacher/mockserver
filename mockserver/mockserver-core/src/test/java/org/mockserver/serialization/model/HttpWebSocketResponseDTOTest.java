package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.*;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class HttpWebSocketResponseDTOTest {

    @Test
    public void shouldReturnValuesSetInConstructor() {
        Delay delay = new Delay(TimeUnit.HOURS, 1);
        String subprotocol = "graphql-ws";
        Boolean closeConnection = Boolean.TRUE;
        WebSocketMessage message1 = WebSocketMessage.webSocketMessage().withText("hello");
        WebSocketMessage message2 = WebSocketMessage.webSocketMessage().withText("world");

        HttpWebSocketResponse httpWebSocketResponse = HttpWebSocketResponse.webSocketResponse()
            .withDelay(delay)
            .withSubprotocol(subprotocol)
            .withCloseConnection(closeConnection)
            .withMessages(message1, message2);

        HttpWebSocketResponseDTO dto = new HttpWebSocketResponseDTO(httpWebSocketResponse);

        assertThat(dto.getDelay(), is(new DelayDTO(delay)));
        assertThat(dto.getSubprotocol(), is(subprotocol));
        assertThat(dto.getCloseConnection(), is(closeConnection));
        assertThat(dto.getMessages(), is(Arrays.asList(
            new WebSocketMessageModelDTO(message1),
            new WebSocketMessageModelDTO(message2)
        )));
    }

    @Test
    public void shouldBuildObject() {
        Delay delay = new Delay(TimeUnit.HOURS, 1);
        String subprotocol = "graphql-ws";
        Boolean closeConnection = Boolean.TRUE;
        WebSocketMessage message1 = WebSocketMessage.webSocketMessage().withText("hello");
        WebSocketMessage message2 = WebSocketMessage.webSocketMessage().withText("world");

        HttpWebSocketResponse httpWebSocketResponse = HttpWebSocketResponse.webSocketResponse()
            .withDelay(delay)
            .withSubprotocol(subprotocol)
            .withCloseConnection(closeConnection)
            .withMessages(message1, message2);

        HttpWebSocketResponse builtResponse = new HttpWebSocketResponseDTO(httpWebSocketResponse).buildObject();

        assertThat(builtResponse.getDelay(), is(delay));
        assertThat(builtResponse.getSubprotocol(), is(subprotocol));
        assertThat(builtResponse.getCloseConnection(), is(closeConnection));
        assertThat(builtResponse.getMessages(), is(Arrays.asList(message1, message2)));
    }

    @Test
    public void shouldReturnValuesSetInSetter() {
        DelayDTO delay = new DelayDTO(new Delay(TimeUnit.HOURS, 1));
        String subprotocol = "graphql-ws";
        Boolean closeConnection = Boolean.TRUE;
        List<WebSocketMessageModelDTO> messages = Arrays.asList(
            new WebSocketMessageModelDTO(WebSocketMessage.webSocketMessage().withText("hello")),
            new WebSocketMessageModelDTO(WebSocketMessage.webSocketMessage().withText("world"))
        );

        HttpWebSocketResponseDTO dto = new HttpWebSocketResponseDTO(null);
        dto.setDelay(delay);
        dto.setSubprotocol(subprotocol);
        dto.setCloseConnection(closeConnection);
        dto.setMessages(messages);

        assertThat(dto.getDelay(), is(delay));
        assertThat(dto.getSubprotocol(), is(subprotocol));
        assertThat(dto.getCloseConnection(), is(closeConnection));
        assertThat(dto.getMessages(), is(messages));
    }

    @Test
    public void shouldHandleNullObjectInput() {
        HttpWebSocketResponseDTO dto = new HttpWebSocketResponseDTO(null);

        assertThat(dto.getDelay(), is(nullValue()));
        assertThat(dto.getSubprotocol(), is(nullValue()));
        assertThat(dto.getCloseConnection(), is(nullValue()));
        assertThat(dto.getMessages(), is(nullValue()));
    }

    @Test
    public void shouldHandleNullFieldInput() {
        HttpWebSocketResponseDTO dto = new HttpWebSocketResponseDTO(HttpWebSocketResponse.webSocketResponse());

        assertThat(dto.getDelay(), is(nullValue()));
        assertThat(dto.getSubprotocol(), is(nullValue()));
        assertThat(dto.getCloseConnection(), is(nullValue()));
        assertThat(dto.getMessages(), is(nullValue()));
        assertThat(dto.getMatchers(), is(nullValue()));
    }

    /**
     * The DTO used to collapse the matcher's NottableString to a bare String, dropping negation.
     * buildObject() re-parsed a leading '!', which recovers the flag for most values but silently
     * corrupts any literal value that itself begins with '!'.
     */
    @Test
    public void shouldPreserveTextMatcherNegationThroughDTORoundTrip() {
        HttpWebSocketResponse original = HttpWebSocketResponse.webSocketResponse()
            .withMatcher(WebSocketMessageMatcher.webSocketMessageMatcher()
                .withTextMatcher(NottableString.string("ping", true))
                .withResponses(WebSocketMessage.webSocketMessage("pong")));

        HttpWebSocketResponse rebuilt = new HttpWebSocketResponseDTO(original).buildObject();

        assertThat(rebuilt.getMatchers().get(0).getTextMatcher().getValue(), is("ping"));
        assertThat(rebuilt.getMatchers().get(0).getTextMatcher().isNot(), is(true));
    }

    /** A literal leading '!' must stay literal rather than being re-read as a negation. */
    @Test
    public void shouldPreserveLiteralNotCharacterInTextMatcherThroughJsonWire() throws Exception {
        HttpWebSocketResponse original = HttpWebSocketResponse.webSocketResponse()
            .withMatcher(WebSocketMessageMatcher.webSocketMessageMatcher()
                .withTextMatcher(NottableString.string("!ping", false))
                .withResponses(WebSocketMessage.webSocketMessage("pong")));

        String json = ObjectMapperFactory.createObjectMapper()
            .writeValueAsString(new HttpWebSocketResponseDTO(original));
        HttpWebSocketResponse rebuilt = ObjectMapperFactory.createObjectMapper()
            .readValue(json, HttpWebSocketResponseDTO.class).buildObject();

        assertThat("literal '!' inverted by wire form " + json,
            rebuilt.getMatchers().get(0).getTextMatcher().isNot(), is(false));
        assertThat(rebuilt.getMatchers().get(0).getTextMatcher().getValue(), is("!ping"));
    }

    @Test
    public void shouldSerializeMatchers() {
        WebSocketMessageMatcher matcher = WebSocketMessageMatcher.webSocketMessageMatcher()
            .withText("ping")
            .withResponses(WebSocketMessage.webSocketMessage("pong"));

        HttpWebSocketResponse httpWebSocketResponse = HttpWebSocketResponse.webSocketResponse()
            .withMatcher(matcher);

        HttpWebSocketResponseDTO dto = new HttpWebSocketResponseDTO(httpWebSocketResponse);

        assertThat(dto.getMatchers().size(), is(1));
        assertThat(dto.getMatchers().get(0).getTextMatcher().getValue(), is("ping"));
        assertThat(dto.getMatchers().get(0).getFrameType(), is(WebSocketFrameType.TEXT));
        assertThat(dto.getMatchers().get(0).getResponses().size(), is(1));
    }

    @Test
    public void shouldDeserializeMatchers() {
        WebSocketMessageMatcher matcher = WebSocketMessageMatcher.webSocketMessageMatcher()
            .withText("ping")
            .withResponses(WebSocketMessage.webSocketMessage("pong"));

        HttpWebSocketResponse httpWebSocketResponse = HttpWebSocketResponse.webSocketResponse()
            .withMatcher(matcher);

        HttpWebSocketResponse builtResponse = new HttpWebSocketResponseDTO(httpWebSocketResponse).buildObject();

        assertThat(builtResponse.getMatchers().size(), is(1));
        assertThat(builtResponse.getMatchers().get(0).getTextMatcher().getValue(), is("ping"));
        assertThat(builtResponse.getMatchers().get(0).getFrameType(), is(WebSocketFrameType.TEXT));
        assertThat(builtResponse.getMatchers().get(0).getResponses().size(), is(1));
        assertThat(builtResponse.getMatchers().get(0).getResponses().get(0).getText(), is("pong"));
    }

    @Test
    public void shouldRoundTripMatchersAndMessages() {
        WebSocketMessageMatcher matcher = WebSocketMessageMatcher.webSocketMessageMatcher()
            .withText("ping")
            .withResponses(WebSocketMessage.webSocketMessage("pong"));
        WebSocketMessage initialMessage = WebSocketMessage.webSocketMessage("welcome");

        HttpWebSocketResponse original = HttpWebSocketResponse.webSocketResponse()
            .withMessage(initialMessage)
            .withMatcher(matcher)
            .withCloseConnection(false);

        HttpWebSocketResponse roundTripped = new HttpWebSocketResponseDTO(original).buildObject();

        assertThat(roundTripped.getMessages().size(), is(1));
        assertThat(roundTripped.getMessages().get(0).getText(), is("welcome"));
        assertThat(roundTripped.getMatchers().size(), is(1));
        assertThat(roundTripped.getMatchers().get(0).getTextMatcher().getValue(), is("ping"));
        assertThat(roundTripped.getCloseConnection(), is(false));
    }

    @Test
    public void shouldSetMatchersViaSetter() {
        WebSocketMessageMatcherDTO matcherDTO = new WebSocketMessageMatcherDTO();
        matcherDTO.setFrameType(WebSocketFrameType.TEXT);
        matcherDTO.setTextMatcher(NottableString.string("ping"));

        HttpWebSocketResponseDTO dto = new HttpWebSocketResponseDTO(null);
        dto.setMatchers(List.of(matcherDTO));

        assertThat(dto.getMatchers().size(), is(1));
        assertThat(dto.getMatchers().get(0).getTextMatcher().getValue(), is("ping"));
    }
}
