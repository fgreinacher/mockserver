package org.mockserver.netty.telemetry;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.After;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.netty.MockServer;
import org.mockserver.telemetry.GenAiSpanExporter;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End-to-end behavioural guard for the forward/proxy-path GenAI span emission that
 * {@code HttpActionHandler.emitForwardGenAiSpan} performs. Unlike
 * {@code ForwardPathGenAiSpansTest} in mockserver-core (which hand-reproduces the
 * sniff/parse/record sequence with a per-test tracer and never touches the running
 * server), this test drives a REAL forwarding {@link MockServer}: a POST is forwarded to
 * an upstream MockServer stubbed as an OpenAI-shaped chat-completions endpoint, and the
 * assertion reads the span PRODUCED BY THE PRODUCTION forward path back out of an
 * in-process {@link InMemorySpanExporter}.
 *
 * <p>The exporter is installed through the supported {@link GenAiSpanExporter#startWithProcessor}
 * seam, which wires the process-wide tracer that production
 * {@code GenAiSpans.recordCompletion(provider, model, completion)} emits into — so the span
 * asserted here can only exist if the running server executed {@code emitForwardGenAiSpan} on
 * the forward path, parsed the upstream OpenAI response into a {@code Completion}, and recorded
 * the span. It is not reconstructed in the test.
 *
 * <p>Provider detection on the forward path is host-gated with a configured-provider fallback:
 * the upstream is a localhost MockServer (not a well-known LLM host), so the request is
 * classified via the {@code mockserver.llmProvider=OPENAI} fallback, gated on the LLM-looking
 * {@code /v1/chat/completions} path — the same path users take when proxying to an
 * OpenAI-compatible endpoint.
 *
 * <p>Mutates process-wide static state (the {@code GenAiSpans}/{@code RequestSpans} tracer via
 * the exporter, and {@code mockserver.llmProvider}); all of it is restored in {@link #tearDown()}.
 * Named {@code *Test} so it runs in the fast surefire phase (mockserver-netty surefire runs
 * classes sequentially in a single fork, so the global-state mutation is safe).
 */
public class ForwardPathGenAiSpanEmissionTest {

    private GenAiSpanExporter exporter;
    private MockServer upstream;
    private MockServer forwardServer;
    private String previousLlmProvider;

    @After
    public void tearDown() {
        if (exporter != null) {
            // restores both the GenAiSpans and RequestSpans process-wide tracers to null
            exporter.stop();
            exporter = null;
        }
        if (forwardServer != null) {
            forwardServer.stop();
            forwardServer = null;
        }
        if (upstream != null) {
            upstream.stop();
            upstream = null;
        }
        ConfigurationProperties.llmProvider(previousLlmProvider == null ? "" : previousLlmProvider);
        LlmProviderSniffer.resetConfiguration();
    }

    @Test
    public void shouldEmitGenAiSpanFromProductionForwardPathForOpenAiUpstream() throws Exception {
        // given - the configured-provider fallback classifies localhost /v1/chat/completions traffic
        previousLlmProvider = ConfigurationProperties.llmProvider();
        ConfigurationProperties.llmProvider("OPENAI");

        // and - an in-memory exporter wired into the process-wide tracer that the production
        // forward path emits GenAI spans through (SimpleSpanProcessor exports synchronously on end)
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        exporter = GenAiSpanExporter.startWithProcessor(SimpleSpanProcessor.create(spanExporter));

        // and - an upstream MockServer stubbed as an OpenAI chat-completions endpoint
        upstream = new MockServer();
        int upstreamPort = upstream.getLocalPort();
        new MockServerClient("localhost", upstreamPort)
            .when(request().withMethod("POST").withPath("/v1/chat/completions"))
            .respond(response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion\","
                    + "\"model\":\"gpt-4o-2024-08-06\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Hello! How can I help?\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8,\"total_tokens\":20}}"));

        // and - a forwarding MockServer that proxies the completion request to the upstream
        forwardServer = new MockServer();
        int forwardPort = forwardServer.getLocalPort();
        new MockServerClient("localhost", forwardPort)
            .when(request().withMethod("POST").withPath("/v1/chat/completions"))
            .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

        // when - a client POSTs a chat-completion request through the forwarding server
        int status = postJson("http://localhost:" + forwardPort + "/v1/chat/completions",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        assertThat("forwarded request must succeed", status, is(200));

        // then - the production forward path emitted exactly one GenAI span, visible via the exporter
        SpanData span = awaitSingleGenAiSpan(spanExporter);
        assertThat(span.getName(), is("chat gpt-4o-2024-08-06"));
        assertThat(span.getAttributes().get(stringKey("gen_ai.system")), is("openai"));
        assertThat(span.getAttributes().get(stringKey("gen_ai.request.model")), is("gpt-4o-2024-08-06"));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.input_tokens")), is(12L));
        assertThat(span.getAttributes().get(longKey("gen_ai.usage.output_tokens")), is(8L));
    }

    /**
     * Poll the exporter for up to 5s for exactly one GenAI span (identified by the
     * {@code gen_ai.system} attribute, which only {@code GenAiSpans.recordCompletion} sets — the
     * exporter also receives SERVER request spans from {@code RequestSpans}, which carry no
     * {@code gen_ai.*} attributes). Fails if zero or more than one is emitted.
     */
    private static SpanData awaitSingleGenAiSpan(InMemorySpanExporter spanExporter) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        List<SpanData> genAiSpans;
        do {
            genAiSpans = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getAttributes().get(stringKey("gen_ai.system")) != null)
                .collect(Collectors.toList());
            if (genAiSpans.size() == 1) {
                return genAiSpans.get(0);
            }
            if (genAiSpans.size() > 1) {
                break;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        assertThat("exactly one GenAI span must be emitted by the production forward path",
            genAiSpans.size(), is(1));
        return genAiSpans.get(0);
    }

    private static int postJson(String url, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            // drain the response so the exchange (and the server-side span emission) completes
            connection.getInputStream().readAllBytes();
            return status;
        } finally {
            connection.disconnect();
        }
    }
}
