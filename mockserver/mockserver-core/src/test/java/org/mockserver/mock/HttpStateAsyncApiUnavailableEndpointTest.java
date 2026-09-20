package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.async.AsyncApiControlPlaneRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * What a user actually sees when they call the AsyncAPI control plane on a server without the
 * optional {@code mockserver-async} module: a 501 whose body tells them how to get it.
 * <p>
 * mockserver-async is deliberately not on this module's classpath — the Maven module boundary is
 * what makes that true, not anything in the test — so every one of these routes takes its
 * not-available branch here. Each test re-asserts that precondition rather than assuming it, so
 * adding the module to this module's test scope would fail these loudly instead of quietly turning
 * them into tests of the delegating path. The four routes previously each carried their own copy of
 * the text; they now read the one constant, and these tests exist so that stays true — a copy that
 * drifts is still a valid 501 and would otherwise fail nothing.
 */
public class HttpStateAsyncApiUnavailableEndpointTest {

    private HttpState httpState;
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private static class FakeResponseWriter extends ResponseWriter {
        private HttpResponse response;

        protected FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }

    @Before
    public void setUp() {
        Configuration configuration = configuration();
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateAsyncApiUnavailableEndpointTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateAsyncApiUnavailableEndpointTest.class), scheduler);
    }

    private String errorFrom(String method, String path) {
        assertThat("mockserver-async must not be on the classpath for this test to mean anything",
            AsyncApiControlPlaneRegistry.getInstance().isAvailable(), is(false));

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest httpRequest = request(path).withMethod(method).withBody("{}");
        assertThat("route handled: " + method + " " + path,
            httpState.handle(httpRequest, responseWriter, false), is(true));
        assertThat("501 Not Implemented for " + method + " " + path,
            responseWriter.response.getStatusCode(), is(501));
        try {
            JsonNode body = objectMapper.readTree(responseWriter.response.getBodyAsString());
            return body.get("error").asText();
        } catch (Exception e) {
            throw new AssertionError("501 body was not JSON with an error field: "
                + responseWriter.response.getBodyAsString(), e);
        }
    }

    @Test
    public void loadSpecTellsTheUserHowToEnableTheModule() {
        assertThat(errorFrom("PUT", "/mockserver/asyncapi"), is(AsyncApiControlPlaneRegistry.NOT_AVAILABLE));
    }

    @Test
    public void statusTellsTheUserHowToEnableTheModule() {
        assertThat(errorFrom("GET", "/mockserver/asyncapi"), is(AsyncApiControlPlaneRegistry.NOT_AVAILABLE));
    }

    @Test
    public void httpImportTellsTheUserHowToEnableTheModule() {
        assertThat(errorFrom("PUT", "/mockserver/asyncapi/http"), is(AsyncApiControlPlaneRegistry.NOT_AVAILABLE));
    }

    @Test
    public void verifyTellsTheUserHowToEnableTheModule() {
        assertThat(errorFrom("PUT", "/mockserver/asyncapi/verify"), is(AsyncApiControlPlaneRegistry.NOT_AVAILABLE));
    }

    /**
     * Asserting equality with the constant above would still pass if the constant itself were
     * reduced to "not available", so pin the parts a user needs to act on at the HTTP boundary too.
     */
    @Test
    public void theMessageOnTheWireIsActionable() {
        String error = errorFrom("GET", "/mockserver/asyncapi");

        assertThat(error, containsString("org.mock-server:mockserver-async"));
        assertThat(error, containsString("mockserver-bom"));
        assertThat(error, containsString("/libs"));
    }
}
