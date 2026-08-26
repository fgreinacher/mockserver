package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.slo.Scope;
import org.mockserver.slo.SloSampleStore;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * End-to-end routing tests for {@code PUT /mockserver/verifySLO} exercised through
 * {@link HttpState#handle}: authentication is required, a PASS/INCONCLUSIVE verdict
 * is 200, a FAIL verdict is 406, a malformed body is 400, and a disabled feature is
 * 400.
 *
 * <p>State-mutating: flips the static {@code sloTrackingEnabled} property and uses
 * the process-wide {@link SloSampleStore} singleton, so it must run in the
 * sequential Surefire phase.
 */
public class HttpStateVerifySloEndpointTest {

    private HttpState httpState;
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
    private final SloSampleStore store = SloSampleStore.getInstance();

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

    @Before
    public void setUp() {
        store.reset();
        ConfigurationProperties.sloTrackingEnabled(true);
        ConfigurationProperties.sloWindowMaxSamples(50_000);
        ConfigurationProperties.sloWindowRetentionMillis(600_000L);
        Configuration configuration = configuration().sloTrackingEnabled(true);
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateVerifySloEndpointTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateVerifySloEndpointTest.class), scheduler);
    }

    @After
    public void tearDown() {
        store.reset();
        ConfigurationProperties.sloTrackingEnabled(false);
    }

    private HttpResponse handle(String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/verifySLO").withMethod("PUT");
        if (body != null) {
            req.withBody(body);
        }
        boolean handled = httpState.handle(req, rw, false);
        assertThat("route handled", handled, is(true));
        return rw.response;
    }

    private static String criteria(String comparator, double threshold) {
        return "{\"name\":\"checkout\"," +
            "\"window\":{\"type\":\"EXPLICIT\",\"fromEpochMillis\":0,\"toEpochMillis\":10000}," +
            "\"minimumSampleCount\":1," +
            "\"objectives\":[{\"sli\":\"LATENCY_P95\",\"comparator\":\"" + comparator + "\",\"threshold\":" + threshold + "}]}";
    }

    @Test
    public void shouldReturn200AndPassVerdictWhenObjectivesHold() throws Exception {
        store.record(1000L, 100L, false, Scope.FORWARD, "a.svc");
        HttpResponse response = handle(criteria("LESS_THAN", 500));
        assertThat(response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("result").asText(), is("PASS"));
        assertThat(body.get("name").asText(), is("checkout"));
        assertThat(body.get("objectiveResults").get(0).get("observedValue").asDouble(), is(100.0));
    }

    @Test
    public void shouldReturn406WhenVerdictFails() throws Exception {
        store.record(1000L, 900L, false, Scope.FORWARD, "a.svc");
        HttpResponse response = handle(criteria("LESS_THAN", 500));
        assertThat(response.getStatusCode(), is(406));
        JsonNode body = objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("result").asText(), is("FAIL"));
    }

    @Test
    public void shouldReturn200WhenVerdictInconclusive() throws Exception {
        // no samples in window -> INCONCLUSIVE -> 200
        HttpResponse response = handle(criteria("LESS_THAN", 500));
        assertThat(response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("result").asText(), is("INCONCLUSIVE"));
    }

    @Test
    public void shouldReturn400ForMalformedBody() {
        HttpResponse response = handle("{not valid json");
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("invalid SLO criteria"));
    }

    @Test
    public void shouldReturn400ForBlankBody() {
        HttpResponse response = handle(null);
        assertThat(response.getStatusCode(), is(400));
    }

    @Test
    public void shouldReturn403WhenTrackingDisabled() throws Exception {
        // 403, not 400: the criteria are well-formed and only the feature flag is off, which is the
        // same situation as PUT /mockserver/loadScenario/start with load generation disabled. 400
        // told callers their criteria were malformed and forced every client library into a combined
        // "invalid criteria (or SLO tracking disabled)" message.
        ConfigurationProperties.sloTrackingEnabled(false);
        Configuration configuration = configuration().sloTrackingEnabled(false);
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateVerifySloEndpointTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateVerifySloEndpointTest.class), scheduler);

        HttpResponse response = handle(criteria("LESS_THAN", 500));
        assertThat(response.getStatusCode(), is(403));
        JsonNode body = objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("error").asText(), containsString("SLO tracking not enabled"));
    }

    @Test
    public void shouldStillReturn400ForMalformedCriteriaWhenTrackingEnabled() throws Exception {
        // the 403 above must not swallow the malformed-body case: with tracking ON a bad body is
        // still a 400, so the two failures stay distinguishable by status code alone
        HttpResponse response = handle("{\"objectives\": \"not-an-array\"}");
        assertThat(response.getStatusCode(), is(400));
    }

    @Test
    public void shouldReturn401WhenControlPlaneAuthenticationRequiredAndRequestUnauthenticated() {
        // mirror the control-plane auth-rejection behaviour exercised for verifySequence:
        // when a control-plane authentication handler is configured and the request is not
        // authenticated, the endpoint must be rejected before any SLO evaluation runs
        AuthenticationHandler rejectingHandler = req -> false;
        httpState.setControlPlaneAuthenticationHandler(rejectingHandler);

        HttpResponse response = handle(criteria("LESS_THAN", 500));
        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), containsString("Unauthorized for control plane"));
    }
}
