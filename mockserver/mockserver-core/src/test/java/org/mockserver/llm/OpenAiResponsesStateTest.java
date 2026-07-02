package org.mockserver.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.llm.codec.OpenAiResponsesCodec;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.HttpLlmResponseActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;

/**
 * OpenAI Responses API server-side state: {@code previous_response_id} chaining,
 * the {@code store} flag, and {@code GET /v1/responses/{id}} retrieval.
 */
public class OpenAiResponsesStateTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpLlmResponseActionHandler handler =
        new HttpLlmResponseActionHandler(new MockServerLogger());
    private final OpenAiResponsesCodec codec = new OpenAiResponsesCodec();

    @Before
    @After
    public void clearStore() {
        OpenAiResponsesStore.getInstance().reset();
    }

    /** Serve a POST /v1/responses turn through the handler and return the issued response id. */
    private String serveTurn(String requestBody, String assistantText) throws Exception {
        HttpRequest request = request().withMethod("POST").withPath("/v1/responses").withBody(requestBody);
        HttpResponse encoded = handler.handle(
            llmResponse()
                .withProvider(Provider.OPENAI_RESPONSES)
                .withModel("gpt-4o")
                .withCompletion(completion().withText(assistantText)),
            request);
        assertThat(encoded.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(encoded.getBodyAsString());
        return root.path("id").asText();
    }

    @Test
    public void storesIssuedResponseByDefaultAndRetrievesItViaGet() throws Exception {
        String id = serveTurn("{\"model\":\"gpt-4o\",\"input\":\"hello\"}", "Hi there");

        // stored with the full conversation (user input + assistant output)
        Optional<OpenAiResponsesStore.StoredResponse> stored = OpenAiResponsesStore.getInstance().get(id);
        assertThat(stored.isPresent(), is(true));
        List<ParsedMessage> messages = stored.get().getConversation().getMessages();
        assertThat(messages, hasSize(2));
        assertThat(messages.get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(messages.get(0).getTextContent(), is("hello"));
        assertThat(messages.get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(messages.get(1).getTextContent(), is("Hi there"));

        // GET /v1/responses/{id} returns the stored body verbatim
        HttpResponse retrieved = OpenAiResponsesStore.getInstance().retrievalResponseOrNull(
            request().withMethod("GET").withPath("/v1/responses/" + id));
        assertThat(retrieved, is(notNullValue()));
        assertThat(retrieved.getStatusCode(), is(200));
        JsonNode retrievedRoot = OBJECT_MAPPER.readTree(retrieved.getBodyAsString());
        assertThat(retrievedRoot.path("id").asText(), is(id));
    }

    @Test
    public void chainsConversationViaPreviousResponseId() throws Exception {
        String firstId = serveTurn("{\"model\":\"gpt-4o\",\"input\":\"what is the capital of France?\"}", "Paris");

        // A second turn references the first via previous_response_id and sends only the delta.
        String secondBody = "{\"model\":\"gpt-4o\",\"input\":[{\"role\":\"user\",\"content\":\"and its population?\"}],"
            + "\"previous_response_id\":\"" + firstId + "\"}";
        ParsedConversation chained = codec.decode(
            request().withMethod("POST").withPath("/v1/responses").withBody(secondBody));

        // decode reconstructs the full dialogue: user, assistant (prior turn) + new user turn
        List<ParsedMessage> messages = chained.getMessages();
        assertThat(messages, hasSize(3));
        assertThat(messages.get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(messages.get(0).getTextContent(), is("what is the capital of France?"));
        assertThat(messages.get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(messages.get(1).getTextContent(), is("Paris"));
        assertThat(messages.get(2).getRole(), is(ParsedMessage.Role.USER));
        assertThat(messages.get(2).getTextContent(), is("and its population?"));
    }

    @Test
    public void chainedTurnStoresTheCumulativeConversation() throws Exception {
        String firstId = serveTurn("{\"model\":\"gpt-4o\",\"input\":\"turn one\"}", "reply one");
        String secondBody = "{\"model\":\"gpt-4o\",\"input\":[{\"role\":\"user\",\"content\":\"turn two\"}],"
            + "\"previous_response_id\":\"" + firstId + "\"}";
        String secondId = serveTurn(secondBody, "reply two");

        // The second stored response carries the entire dialogue so a third turn can chain from it.
        List<ParsedMessage> messages = OpenAiResponsesStore.getInstance().get(secondId).get()
            .getConversation().getMessages();
        assertThat(messages, hasSize(4));
        assertThat(messages.get(0).getTextContent(), is("turn one"));
        assertThat(messages.get(1).getTextContent(), is("reply one"));
        assertThat(messages.get(2).getTextContent(), is("turn two"));
        assertThat(messages.get(3).getTextContent(), is("reply two"));
    }

    @Test
    public void storeFalseIsNeitherStoredNorRetrievable() throws Exception {
        String id = serveTurn("{\"model\":\"gpt-4o\",\"input\":\"secret\",\"store\":false}", "unstored");

        assertThat(OpenAiResponsesStore.getInstance().get(id).isPresent(), is(false));
        assertThat(OpenAiResponsesStore.getInstance().retrievalResponseOrNull(
            request().withMethod("GET").withPath("/v1/responses/" + id)), is(nullValue()));
    }

    @Test
    public void requestWithoutPreviousResponseIdIsNotChained() {
        // Back-compat: a stateless request decodes to exactly its own messages.
        ParsedConversation conversation = codec.decode(
            request().withMethod("POST").withPath("/v1/responses")
                .withBody("{\"model\":\"gpt-4o\",\"input\":\"standalone\"}"));
        assertThat(conversation.getMessages(), hasSize(1));
        assertThat(conversation.getMessages().get(0).getTextContent(), is("standalone"));
    }

    @Test
    public void retrievalReturnsNullForUnknownIdAndNonGet() {
        assertThat(OpenAiResponsesStore.getInstance().retrievalResponseOrNull(
            request().withMethod("GET").withPath("/v1/responses/resp_does_not_exist")), is(nullValue()));
        // A non-GET responses request is not a retrieval.
        assertThat(OpenAiResponsesStore.getInstance().retrievalResponseOrNull(
            request().withMethod("POST").withPath("/v1/responses")), is(nullValue()));
    }

    @Test
    public void resetClearsStoredResponses() throws Exception {
        String id = serveTurn("{\"model\":\"gpt-4o\",\"input\":\"hello\"}", "hi");
        assertThat(OpenAiResponsesStore.getInstance().get(id).isPresent(), is(true));
        OpenAiResponsesStore.getInstance().reset();
        assertThat(OpenAiResponsesStore.getInstance().get(id).isPresent(), is(false));
    }
}
