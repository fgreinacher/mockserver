package org.mockserver.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.client.LlmBackend;
import org.mockserver.llm.client.LlmCompletionService;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.scheduler.Scheduler;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end through {@code /generateExpectation}: the prompt that reaches the LLM
 * backend is redacted, the served request object is not, and the configured
 * data-plane API-key header name is redacted too.
 */
public class HttpStateGenerateExpectationRedactionTest {

    private Configuration configuration;
    private HttpState httpState;
    private ScheduledExecutorService schedulerExecutor;

    private static final class PromptCapturingService extends LlmCompletionService {
        final AtomicReference<String> prompt = new AtomicReference<>();

        PromptCapturingService() {
            super((org.mockserver.llm.client.LlmTransport) null);
        }

        @Override
        public Optional<Completion> complete(LlmBackend backend, ParsedConversation conversation) {
            prompt.set(conversation.getMessages().get(0).getTextContent());
            return Optional.of(new Completion().withText(
                "[{\"httpRequest\":{\"method\":\"GET\",\"path\":\"/api\"},\"httpResponse\":{\"statusCode\":200}}]"));
        }
    }

    @Before
    public void prepare() {
        configuration = configuration().dataPlaneApiKeyAuthenticationHeader("X-Company-Auth");
        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = Executors.newScheduledThreadPool(2);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
    }

    @After
    public void cleanup() {
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
        }
    }

    private HttpResponse invokeGenerateExpectation(HttpRequest request) throws Exception {
        Method method = HttpState.class.getDeclaredMethod("handleGenerateExpectation", HttpRequest.class);
        method.setAccessible(true);
        return (HttpResponse) method.invoke(httpState, request);
    }

    @Test
    public void shouldRedactPromptButNotServedRequest() throws Exception {
        PromptCapturingService service = new PromptCapturingService();
        httpState.setLlmCompletionService(service, LlmBackend.of(Provider.OPENAI, "test-key"));

        HttpRequest generateExpectationRequest = HttpRequest.request()
            .withMethod("PUT")
            .withPath("/mockserver/generateExpectation")
            .withBody("{\"preview\":true,\"request\":{" +
                "\"method\":\"POST\",\"path\":\"/api/login\"," +
                "\"headers\":{\"Authorization\":[\"Bearer sk-e2e-secret\"],\"X-Company-Auth\":[\"company-e2e-secret\"]}," +
                "\"body\":\"{\\\"password\\\":\\\"body-e2e-secret\\\"}\"}}");

        HttpResponse response = invokeGenerateExpectation(generateExpectationRequest);

        String capturedPrompt = service.prompt.get();
        assertThat(capturedPrompt, notNullValue());
        assertThat(capturedPrompt, containsString("Authorization"));
        assertThat(capturedPrompt, containsString("X-Company-Auth"));
        assertThat(capturedPrompt, containsString("***REDACTED***"));
        assertThat(capturedPrompt, not(containsString("sk-e2e-secret")));
        assertThat(capturedPrompt, not(containsString("company-e2e-secret")));
        assertThat(capturedPrompt, not(containsString("body-e2e-secret")));

        assertThat(response.getStatusCode(), is(200));
    }
}
