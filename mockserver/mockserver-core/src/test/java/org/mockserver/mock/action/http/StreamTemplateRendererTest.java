package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpTemplate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * T1.2: shared streaming-payload template rendering.
 * <p>
 * Verifies the {@link StreamTemplateRenderer} seam used by the SSE, WebSocket and gRPC server-stream
 * handlers: a payload with no {@code templateType} is returned verbatim (static, byte-for-byte
 * unchanged), while a payload with a {@code VELOCITY} / {@code MUSTACHE} / {@code JAVASCRIPT} template
 * type is rendered against the triggering request (request fields available via the same helpers the
 * response-template path uses).
 */
public class StreamTemplateRendererTest {

    private final StreamTemplateRenderer renderer =
        new StreamTemplateRenderer(new MockServerLogger(StreamTemplateRendererTest.class), configuration());

    @Test
    public void shouldReturnPayloadUnchangedWhenNoTemplateType() {
        String payload = "{\"greeting\": \"Hello $jsonPath.find(\\\"$.name\\\")\"}";

        assertThat(renderer.render(null, payload, request().withBody("{\"name\":\"Alice\"}")), is(payload));
    }

    @Test
    public void shouldReturnNullPayloadUnchanged() {
        assertThat(renderer.render(HttpTemplate.TemplateType.VELOCITY, null, request()), is((String) null));
    }

    @Test
    public void shouldRenderVelocityTemplateAgainstRequest() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.VELOCITY,
            "{\"greeting\": \"Hello $jsonPath.find(\"$.name\")\"}",
            request().withBody("{\"name\": \"Alice\"}"));

        assertThat(rendered, is("{\"greeting\": \"Hello Alice\"}"));
    }

    @Test
    public void shouldRenderMustacheTemplateAgainstRequest() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.MUSTACHE,
            "{\"greeting\": \"Hello {{#jsonPath}}$.name{{/jsonPath}}{{jsonPathResult}}\"}",
            request().withBody("{\"name\": \"Carol\"}"));

        assertThat(rendered, is("{\"greeting\": \"Hello Carol\"}"));
    }

    @Test
    public void shouldRenderJavaScriptTemplateAsTextAgainstRequest() {
        // JavaScript streaming templates return a text fragment: a returned string is emitted verbatim.
        String rendered = renderer.render(
            HttpTemplate.TemplateType.JAVASCRIPT,
            "return 'Hello ' + request.body;",
            request().withBody("Bob"));

        assertThat(rendered, is("Hello Bob"));
    }

    @Test
    public void shouldJsonStringifyNonStringJavaScriptReturnValue() {
        // A JavaScript template returning a non-string value is JSON.stringify'd.
        String rendered = renderer.render(
            HttpTemplate.TemplateType.JAVASCRIPT,
            "return { greeting: 'Hi ' + request.body };",
            request().withBody("Dave"));

        assertThat(rendered, is("{\"greeting\":\"Hi Dave\"}"));
    }
}
