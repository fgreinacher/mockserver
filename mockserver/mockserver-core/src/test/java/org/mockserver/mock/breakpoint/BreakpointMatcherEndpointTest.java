package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Tests the breakpoint matcher REST endpoints in HttpState:
 * register, list, remove, clear, and validation.
 */
public class BreakpointMatcherEndpointTest {

    private static Configuration staticConfiguration;
    private static HttpState staticHttpState;

    @BeforeClass
    public static void setupClass() {
        staticConfiguration = configuration();
        Scheduler scheduler = mock(Scheduler.class);
        staticHttpState = new HttpState(staticConfiguration, new MockServerLogger(staticConfiguration, MockServerLogger.class), scheduler);
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (staticHttpState != null) {
            staticHttpState.reset();
            staticHttpState.stop();
            staticHttpState = null;
        }
        // Final cleanup of all breakpoint singletons after the class.
        // Allow async resources (Disruptor, scheduled timeouts) to settle
        // before the next test class creates fresh singletons state.
        Thread.sleep(100);
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

    // Instance references for convenience
    private Configuration configuration;
    private HttpState httpState;

    @Before
    public void setup() {
        resetAllBreakpointSingletons();
        configuration = staticConfiguration;
        httpState = staticHttpState;
    }

    @After
    public void cleanup() {
        resetAllBreakpointSingletons();
    }

    private void resetAllBreakpointSingletons() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

    private static class FakeResponseWriter extends ResponseWriter {
        public HttpResponse response;

        protected FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }

    // --- register ---

    @Test
    public void shouldRegisterBreakpointMatcher() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/api/test\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response, is(notNullValue()));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"id\""));
        assertThat(body, containsString("\"REQUEST\""));
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));
    }

    @Test
    public void shouldRegisterWithMultiplePhases() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"method\":\"POST\"},\"phases\":[\"REQUEST\",\"RESPONSE\"],\"clientId\":\"test-client\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(201));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("REQUEST"));
        assertThat(body, containsString("RESPONSE"));
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));
    }

    @Test
    public void shouldRegisterWithSkipCount() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/api/cond\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\",\"skipCount\":2}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(201));
        // response echoes skipCount
        assertThat(responseWriter.response.getBodyAsString(), containsString("\"skipCount\""));
        assertThat(responseWriter.response.getBodyAsString(), containsString("2"));

        // the registered matcher carries the skipCount and pauses only from hit 3
        BreakpointMatcher entry = BreakpointMatcherRegistry.getInstance().entries().get(0);
        assertThat(entry.getSkipCount(), is(2));
    }

    @Test
    public void shouldListSkipCount() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/cond\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\",\"skipCount\":3}");

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("\"skipCount\""));
        assertThat(responseWriter.response.getBodyAsString(), containsString("3"));
    }

    @Test
    public void shouldOmitSkipCountWhenAbsent() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/plain\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), not(containsString("skipCount")));
        assertThat(BreakpointMatcherRegistry.getInstance().entries().get(0).getSkipCount(), is(nullValue()));
    }

    @Test
    public void shouldReturn400WhenSkipCountNegative() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/neg\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\",\"skipCount\":-1}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("skipCount"));
    }

    @Test
    public void shouldReturn400WhenSkipCountNotInteger() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/nan\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\",\"skipCount\":\"two\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("skipCount"));
    }

    // --- one-shot / bounded (maxHits) breakpoints ---

    @Test
    public void shouldRegisterWithMaxHits() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/api/oneshot\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\",\"maxHits\":1}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(201));
        assertThat(responseWriter.response.getBodyAsString(), containsString("\"maxHits\""));

        BreakpointMatcher entry = BreakpointMatcherRegistry.getInstance().entries().get(0);
        assertThat(entry.getMaxHits(), is(1));
    }

    @Test
    public void shouldListMaxHits() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/bounded\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\",\"maxHits\":5}");

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("\"maxHits\""));
        assertThat(responseWriter.response.getBodyAsString(), containsString("5"));
    }

    @Test
    public void shouldOmitMaxHitsWhenAbsent() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/plain\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), not(containsString("maxHits")));
        assertThat(BreakpointMatcherRegistry.getInstance().entries().get(0).getMaxHits(), is(nullValue()));
    }

    @Test
    public void shouldReturn400WhenMaxHitsZero() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/zero\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\",\"maxHits\":0}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("maxHits"));
    }

    @Test
    public void shouldReturn400WhenMaxHitsNegative() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/neg\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\",\"maxHits\":-1}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("maxHits"));
    }

    @Test
    public void shouldReturn400WhenMaxHitsNotInteger() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/nan\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\",\"maxHits\":\"one\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("maxHits"));
    }

    // --- response-content conditions ---

    @Test
    public void shouldRegisterWithResponseStatusAndBodyConditions() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/api/.*\"},\"phases\":[\"RESPONSE\"],\"clientId\":\"test-client\","
                + "\"responseStatusCodeMin\":500,\"responseStatusCodeMax\":599,\"responseBodyContains\":\"quota.*exceeded\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(201));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"responseStatusCodeMin\""));
        assertThat(body, containsString("\"responseStatusCodeMax\""));
        assertThat(body, containsString("\"responseBodyContains\""));

        BreakpointMatcher entry = BreakpointMatcherRegistry.getInstance().entries().get(0);
        assertThat(entry.getResponseStatusCodeMin(), is(500));
        assertThat(entry.getResponseStatusCodeMax(), is(599));
        assertThat(entry.getResponseBodyContains(), is("quota.*exceeded"));
        assertThat(entry.hasResponseCondition(), is(true));
    }

    @Test
    public void shouldListResponseConditions() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/x\"},\"phases\":[\"RESPONSE\"],\"clientId\":\"test-client\",\"responseStatusCodeMin\":500}");

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("\"responseStatusCodeMin\""));
        assertThat(responseWriter.response.getBodyAsString(), containsString("500"));
    }

    @Test
    public void shouldOmitResponseConditionsWhenAbsent() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/plain\"},\"phases\":[\"RESPONSE\"],\"clientId\":\"test-client\"}");

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), not(containsString("responseStatusCode")));
        assertThat(responseWriter.response.getBodyAsString(), not(containsString("responseBodyContains")));
        assertThat(BreakpointMatcherRegistry.getInstance().entries().get(0).hasResponseCondition(), is(false));
    }

    @Test
    public void shouldReturn400WhenResponseStatusCodeNotInteger() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/x\"},\"phases\":[\"RESPONSE\"],\"clientId\":\"test-client\",\"responseStatusCodeMin\":\"oops\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("responseStatusCodeMin"));
    }

    @Test
    public void shouldReturn400WhenResponseStatusCodeRangeInverted() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/x\"},\"phases\":[\"RESPONSE\"],\"clientId\":\"test-client\",\"responseStatusCodeMin\":599,\"responseStatusCodeMax\":500}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("responseStatusCodeMin"));
    }

    @Test
    public void shouldReturn400WhenResponseBodyContainsInvalidRegex() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/x\"},\"phases\":[\"RESPONSE\"],\"clientId\":\"test-client\",\"responseBodyContains\":\"[unclosed\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("responseBodyContains"));
    }

    // --- validation ---

    @Test
    public void shouldReturn400WhenMissingHttpRequest() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("httpRequest"));
    }

    @Test
    public void shouldReturn400WhenEmptyHttpRequest() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("httpRequest"));
    }

    @Test
    public void shouldReturn400WhenMissingPhases() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"}}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("phases"));
    }

    @Test
    public void shouldReturn400WhenEmptyPhases() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"},\"phases\":[]}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("phases"));
    }

    @Test
    public void shouldReturn400WhenUnknownPhase() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"},\"phases\":[\"INVALID_PHASE\"]}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("INVALID_PHASE"));
    }

    @Test
    public void shouldReturn400WhenEmptyBody() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldReturn400WhenMissingClientId() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"},\"phases\":[\"REQUEST\"]}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("clientId"));
    }

    @Test
    public void shouldReturn400WhenBlankClientId() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"},\"phases\":[\"REQUEST\"],\"clientId\":\"\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("clientId"));
    }

    // --- list (PUT and GET) ---

    @Test
    public void shouldListRegisteredMatchers() {
        // register two matchers
        registerMatcher("{\"httpRequest\":{\"path\":\"/a\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        registerMatcher("{\"httpRequest\":{\"path\":\"/b\"},\"phases\":[\"RESPONSE\",\"RESPONSE_STREAM\"],\"clientId\":\"test-client\"}");

        // list via PUT
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest listRequest = request("/mockserver/breakpoint/matchers").withMethod("PUT");
        httpState.handle(listRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"matchers\""));
        assertThat(body, containsString("/a"));
        assertThat(body, containsString("/b"));
        assertThat(body, containsString("REQUEST"));
        assertThat(body, containsString("RESPONSE"));
        assertThat(body, containsString("RESPONSE_STREAM"));
    }

    @Test
    public void shouldListViaGet() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/get-test\"},\"phases\":[\"INBOUND_STREAM\"],\"clientId\":\"test-client\"}");

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest getRequest = request("/mockserver/breakpoint/matchers").withMethod("GET");
        httpState.handle(getRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("/get-test"));
        assertThat(responseWriter.response.getBodyAsString(), containsString("INBOUND_STREAM"));
    }

    // --- remove ---

    @Test
    public void shouldRemoveMatcher() {
        String id = registerMatcherAndGetId("{\"httpRequest\":{\"path\":\"/removable\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest removeRequest = request("/mockserver/breakpoint/matcher/remove")
            .withMethod("PUT")
            .withBody("{\"id\":\"" + id + "\"}");
        httpState.handle(removeRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("removed"));
        assertThat(responseWriter.response.getBodyAsString(), containsString(id));
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(0));
    }

    @Test
    public void shouldReturn404WhenRemovingNonExistentMatcher() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest removeRequest = request("/mockserver/breakpoint/matcher/remove")
            .withMethod("PUT")
            .withBody("{\"id\":\"non-existent-id\"}");
        httpState.handle(removeRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(404));
        assertThat(responseWriter.response.getBodyAsString(), containsString("not found"));
    }

    @Test
    public void shouldReturn404OnReRemove() {
        String id = registerMatcherAndGetId("{\"httpRequest\":{\"path\":\"/remove-twice\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");

        // first remove succeeds
        FakeResponseWriter rw1 = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matcher/remove").withMethod("PUT")
            .withBody("{\"id\":\"" + id + "\"}"), rw1, false);
        assertThat(rw1.response.getStatusCode(), is(200));

        // second remove returns 404
        FakeResponseWriter rw2 = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matcher/remove").withMethod("PUT")
            .withBody("{\"id\":\"" + id + "\"}"), rw2, false);
        assertThat(rw2.response.getStatusCode(), is(404));
    }

    // --- clear ---

    @Test
    public void shouldClearAllMatchers() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/x\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        registerMatcher("{\"httpRequest\":{\"path\":\"/y\"},\"phases\":[\"RESPONSE\"],\"clientId\":\"test-client\"}");
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(2));

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clearRequest = request("/mockserver/breakpoint/matcher/clear").withMethod("PUT");
        httpState.handle(clearRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("cleared"));
        assertThat(responseWriter.response.getBodyAsString(), containsString("\"count\" : 2"));
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(0));
    }

    // --- reset clears ---

    @Test
    public void resetShouldClearMatcherRegistry() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/reset-test\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));

        // trigger reset
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest resetRequest = request("/mockserver/reset").withMethod("PUT");
        httpState.handle(resetRequest, responseWriter, false);

        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(0));
    }

    // --- integration: register -> list -> remove -> list empty ---

    @Test
    public void shouldSupportFullLifecycle() {
        // register
        String id = registerMatcherAndGetId("{\"httpRequest\":{\"path\":\"/lifecycle\"},\"phases\":[\"REQUEST\",\"RESPONSE\"],\"clientId\":\"test-client\"}");
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));

        // list shows it
        FakeResponseWriter listRw = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), listRw, false);
        assertThat(listRw.response.getBodyAsString(), containsString("/lifecycle"));

        // remove
        FakeResponseWriter removeRw = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matcher/remove").withMethod("PUT")
            .withBody("{\"id\":\"" + id + "\"}"), removeRw, false);
        assertThat(removeRw.response.getStatusCode(), is(200));

        // list is now empty
        FakeResponseWriter emptyListRw = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), emptyListRw, false);
        assertThat(emptyListRw.response.getBodyAsString(), containsString("\"matchers\" : [ ]"));
    }

    // --- helpers ---

    private void registerMatcher(String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/breakpoint/matcher").withMethod("PUT").withBody(body);
        httpState.handle(req, rw, false);
        assertThat("registration should succeed", rw.response.getStatusCode(), is(201));
    }

    private String registerMatcherAndGetId(String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/breakpoint/matcher").withMethod("PUT").withBody(body);
        httpState.handle(req, rw, false);
        assertThat("registration should succeed", rw.response.getStatusCode(), is(201));
        // extract id from JSON response
        String responseBody = rw.response.getBodyAsString();
        int idStart = responseBody.indexOf("\"id\" : \"") + 8;
        int idEnd = responseBody.indexOf("\"", idStart);
        return responseBody.substring(idStart, idEnd);
    }
}
