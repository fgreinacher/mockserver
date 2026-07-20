package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.llm.codec.OpenAiChatCompletionsCodec;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Proves {@code llmInferUsageEnabled} is honoured when set on a {@link Configuration} <em>instance</em>.
 * <p>
 * {@link HttpLlmResponseActionHandler#withInferredUsageIfEnabled} previously read only the static
 * {@link ConfigurationProperties} store, so a value set over {@code PUT /mockserver/configuration}
 * round-tripped through the DTO and was silently ignored here.
 * <p>
 * Lives in this package (rather than alongside the other instance-enforcement tests) because
 * {@code withInferredUsageIfEnabled} is package-private; the static default is {@code false}, so
 * inferred usage can only come from the instance value.
 */
public class HttpLlmResponseActionHandlerInstanceConfigurationTest {

    private static final String PROMPT_BODY =
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello there\"}]}";

    @Test
    public void shouldInferLlmUsageFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().llmInferUsageEnabled(true);
        HttpLlmResponseActionHandler handler =
            new HttpLlmResponseActionHandler(new MockServerLogger(), configuration);

        Completion result = handler.withInferredUsageIfEnabled(
            new Completion().withText("a reply"), new OpenAiChatCompletionsCodec(), request());

        assertThat("llmInferUsageEnabled set on the instance must produce inferred usage",
            result.getUsage(), is(notNullValue()));
    }

    @Test
    public void shouldNotInferLlmUsageWhenNoConfigurationAndStaticDefaultIsOff() {
        HttpLlmResponseActionHandler handler =
            new HttpLlmResponseActionHandler(new MockServerLogger());

        Completion result = handler.withInferredUsageIfEnabled(
            new Completion().withText("a reply"), new OpenAiChatCompletionsCodec(), request());

        assertThat(result.getUsage(), is(nullValue()));
    }

    private static HttpRequest request() {
        return HttpRequest.request().withPath("/v1/chat/completions").withBody(PROMPT_BODY);
    }
}
