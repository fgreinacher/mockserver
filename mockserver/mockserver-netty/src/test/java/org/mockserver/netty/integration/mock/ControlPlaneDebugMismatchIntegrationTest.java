package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.MockServer;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end tests for the {@code PUT /mockserver/debugMismatch} control-plane endpoint (and its
 * bare {@code PUT /debugMismatch} alias), driving a real MockServer over a real socket.
 */
public class ControlPlaneDebugMismatchIntegrationTest {

    private static MockServer mockServer;
    private static NettyHttpClient httpClient;
    private static EventLoopGroup clientEventLoopGroup;
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger();
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    @BeforeClass
    public static void startServerAndClient() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(ControlPlaneDebugMismatchIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);
        mockServer = new MockServer();
    }

    @AfterClass
    public static void stopServerAndClient() {
        stopQuietly(mockServer);
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Before
    public void resetBefore() throws Exception {
        send("PUT", "/mockserver/reset", null);
    }

    @After
    public void resetAfter() throws Exception {
        send("PUT", "/mockserver/reset", null);
    }

    private HttpResponse send(String method, String path, String body) throws Exception {
        org.mockserver.model.HttpRequest httpRequest = request()
            .withMethod(method)
            .withHeader(HOST.toString(), "localhost:" + mockServer.getLocalPort())
            .withPath(path);
        if (body != null) {
            httpRequest = httpRequest.withBody(body);
        }
        return httpClient.sendRequest(httpRequest).get(15, TimeUnit.SECONDS);
    }

    private static JsonNode json(HttpResponse response) throws Exception {
        return OBJECT_MAPPER.readTree(response.getBodyAsString());
    }

    /**
     * Registers two expectations: {@code nearMiss} differs from the probe request by a single field
     * (a header), {@code farMiss} differs by several.
     */
    private void givenTwoExpectations() throws Exception {
        Expectation[] expectations = {
            new Expectation(
                request()
                    .withMethod("GET")
                    .withPath("/debugMismatch/users")
                    .withHeader("X-Token", "abc")
            ).withId("nearMiss").thenRespond(response().withBody("near")),
            new Expectation(
                request()
                    .withMethod("POST")
                    .withPath("/debugMismatch/completely/different")
                    .withHeader("X-Other", "zzz")
                    .withQueryStringParameter("q", "1")
            ).withId("farMiss").thenRespond(response().withBody("far"))
        };
        HttpResponse added = send("PUT", "/mockserver/expectation", new ExpectationSerializer(MOCK_SERVER_LOGGER).serialize(expectations));
        assertThat(added.getStatusCode(), is(201));
    }

    private static JsonNode resultForExpectation(JsonNode body, String expectationId) {
        for (JsonNode result : body.get("results")) {
            if (result.has("expectationId") && result.get("expectationId").asText().equals(expectationId)) {
                return result;
            }
        }
        throw new AssertionError("no result found for expectation id " + expectationId + " in " + body);
    }

    // ------------------------------------------------------------------
    // mismatch diagnostics
    // ------------------------------------------------------------------

    @Test
    public void shouldReportPerExpectationDifferencesAndIdentifyClosestMatch() throws Exception {
        // given
        givenTwoExpectations();

        // when - a probe request that matches neither expectation
        HttpResponse response = send("PUT", "/mockserver/debugMismatch",
            "{\"method\":\"GET\",\"path\":\"/debugMismatch/users\"}");

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("content-type"), containsString("application/json"));
        JsonNode body = json(response);
        assertThat(body.get("totalExpectations").asInt(), is(2));
        assertThat(body.get("evaluatedExpectations").asInt(), is(2));
        assertThat(body.get("correlationId"), is(not(nullValue())));
        assertThat(body.get("timestamp"), is(not(nullValue())));
        assertThat(body.get("results").size(), is(2));

        // then - the near miss differs on headers only, and reports the offending field
        JsonNode nearMiss = resultForExpectation(body, "nearMiss");
        assertThat(nearMiss.get("matches").asBoolean(), is(false));
        assertThat(nearMiss.get("expectationPath").asText(), is("/debugMismatch/users"));
        assertThat(nearMiss.get("expectationMethod").asText(), is("GET"));
        assertThat("headers is the field that failed to match", nearMiss.get("differences").has("headers"), is(true));
        assertThat("method matched so must not be reported as a difference", nearMiss.get("differences").has("method"), is(false));
        assertThat("path matched so must not be reported as a difference", nearMiss.get("differences").has("path"), is(false));

        // then - the far miss reports method as a differing field (it is evaluated before path, so it
        // is reported whether or not the matcher goes on to evaluate the remaining fields). See
        // shouldReportTheFailingFieldForAMismatchedExpectation.
        JsonNode farMiss = resultForExpectation(body, "farMiss");
        assertThat(farMiss.get("matches").asBoolean(), is(false));
        assertThat(farMiss.get("differences").has("method"), is(true));
        assertThat(nearMiss.get("totalFieldCount").asInt(), is(farMiss.get("totalFieldCount").asInt()));

        // then - a closest match is reported, carrying the same counts as its result entry
        JsonNode closestMatch = body.get("closestMatch");
        assertThat("a closest match must be reported when nothing matched", closestMatch, is(not(nullValue())));
        JsonNode closest = resultForExpectation(body, closestMatch.get("expectationId").asText());
        assertThat(closestMatch.get("matchedFields").asInt(), is(closest.get("matchedFieldCount").asInt()));
        assertThat(closestMatch.get("totalFields").asInt(), is(closest.get("totalFieldCount").asInt()));
    }

    /**
     * Asserts what {@code /debugMismatch} must report for a mismatched expectation regardless of how
     * the matcher is implemented internally: the first field that failed is named, and the field
     * counts are self-consistent.
     * <p>
     * Deliberately does NOT assert the number of differences, nor that a near miss and a far miss
     * report the same {@code matchedFieldCount}. Both are true today only because
     * {@code HttpRequestPropertiesMatcher} fails fast on the first non-matching field, so every
     * mismatch records exactly one difference and {@code matchedFieldCount} is
     * {@code totalFieldCount - 1} however badly the expectation missed. That is a defect, not a
     * contract — it is the same root cause that makes {@code closestMatch} report registration order
     * (see {@link #shouldIdentifyClosestMatchIndependentOfExpectationOrder}) — and it is being fixed.
     * Pinning it here would encode the bug as intended behaviour and turn the fix into a test failure,
     * inviting whoever hits it to "repair" the test and preserve the defect.
     */
    @Test
    public void shouldReportTheFailingFieldForAMismatchedExpectation() throws Exception {
        // given
        givenTwoExpectations();

        // when
        JsonNode body = json(send("PUT", "/mockserver/debugMismatch",
            "{\"method\":\"GET\",\"path\":\"/debugMismatch/users\"}"));

        // then - the field that failed is named, and at least one difference is reported
        JsonNode farMiss = resultForExpectation(body, "farMiss");
        assertThat("a mismatched expectation must report at least one difference",
            farMiss.get("differences").size(), is(greaterThanOrEqualTo(1)));
        assertThat("the differing method must be reported", farMiss.get("differences").has("method"), is(true));

        // then - the reported counts are self-consistent for every mismatched expectation
        for (String expectationId : new String[]{"farMiss", "nearMiss"}) {
            JsonNode result = resultForExpectation(body, expectationId);
            assertThat(expectationId + " must not have matched", result.get("matches").asBoolean(), is(false));
            assertThat(expectationId + " matchedFieldCount must be below its totalFieldCount",
                result.get("matchedFieldCount").asInt() < result.get("totalFieldCount").asInt(), is(true));
        }
    }

    /**
     * {@code closestMatch} must identify the expectation that came NEAREST to matching, independently
     * of the order expectations were registered in.
     * <p>
     * This is the only test that discriminates the two possible semantics, and it is deliberately
     * built so registration order gives the WRONG answer: the far miss is registered first, so a
     * ranking that merely reports the first mismatched expectation returns {@code farMiss} and fails
     * here. It was written against, and initially failed on, the defect where
     * {@code failures < closestMatchFailures} in {@code HttpState.debugMismatch} could only ever be
     * true for the first mismatched expectation — because fail-fast matching gave every mismatch
     * exactly one difference, so there was no ranking signal to compare. That defect is now fixed and
     * this test passes; keep it as the guard against the ranking regressing to registration order.
     * <p>
     * Note {@code totalFieldCount} is derived from a broader enum than the set of gates actually
     * evaluated, so it is an upper bound rather than an exact count. This test deliberately asserts
     * only the relative ordering of the two candidates, never absolute field counts.
     */
    @Test
    public void shouldIdentifyClosestMatchIndependentOfExpectationOrder() throws Exception {
        // given - the FAR miss is registered first, the near miss second
        Expectation[] expectations = {
            new Expectation(
                request()
                    .withMethod("POST")
                    .withPath("/debugMismatch/completely/different")
                    .withHeader("X-Other", "zzz")
                    .withQueryStringParameter("q", "1")
            ).withId("farMiss").thenRespond(response().withBody("far")),
            new Expectation(
                request()
                    .withMethod("GET")
                    .withPath("/debugMismatch/users")
                    .withHeader("X-Token", "abc")
            ).withId("nearMiss").thenRespond(response().withBody("near"))
        };
        assertThat(send("PUT", "/mockserver/expectation",
            new ExpectationSerializer(MOCK_SERVER_LOGGER).serialize(expectations)).getStatusCode(), is(201));

        // when - a probe differing from nearMiss by a single header
        JsonNode body = json(send("PUT", "/mockserver/debugMismatch",
            "{\"method\":\"GET\",\"path\":\"/debugMismatch/users\"}"));

        // then
        assertThat(body.get("closestMatch").get("expectationId").asText(), is("nearMiss"));
    }

    @Test
    public void shouldReportMatchWhenProbeRequestSatisfiesAnExpectation() throws Exception {
        // given
        givenTwoExpectations();

        // when - a probe request that fully matches the nearMiss expectation
        HttpResponse response = send("PUT", "/mockserver/debugMismatch",
            "{\"method\":\"GET\",\"path\":\"/debugMismatch/users\",\"headers\":{\"X-Token\":[\"abc\"]}}");

        // then
        assertThat(response.getStatusCode(), is(200));
        JsonNode body = json(response);
        JsonNode nearMiss = resultForExpectation(body, "nearMiss");
        assertThat(nearMiss.get("matches").asBoolean(), is(true));
        assertThat("a matching expectation reports no differences", nearMiss.has("differences"), is(false));
        assertThat(
            "a matching expectation matched every field",
            nearMiss.get("matchedFieldCount").asInt(),
            is(nearMiss.get("totalFieldCount").asInt())
        );
        assertThat(resultForExpectation(body, "farMiss").get("matches").asBoolean(), is(false));
    }

    @Test
    public void shouldReportNoExpectationsWhenNoneRegistered() throws Exception {
        // when
        HttpResponse response = send("PUT", "/mockserver/debugMismatch",
            "{\"method\":\"GET\",\"path\":\"/debugMismatch/nothing\"}");

        // then
        assertThat(response.getStatusCode(), is(200));
        JsonNode body = json(response);
        assertThat(body.get("totalExpectations").asInt(), is(0));
        assertThat(body.get("evaluatedExpectations").asInt(), is(0));
        assertThat(body.get("results").size(), is(0));
        assertThat("no closest match can exist with no expectations", body.has("closestMatch"), is(false));
    }

    // ------------------------------------------------------------------
    // bare alias
    // ------------------------------------------------------------------

    @Test
    public void shouldDebugMismatchViaBareAlias() throws Exception {
        // given
        givenTwoExpectations();

        // when - the bare alias, with no /mockserver prefix
        HttpResponse response = send("PUT", "/debugMismatch",
            "{\"method\":\"GET\",\"path\":\"/debugMismatch/users\"}");

        // then
        assertThat("PUT /debugMismatch (bare alias) must be handled by the control plane", response.getStatusCode(), is(200));
        JsonNode body = json(response);
        assertThat(body.get("totalExpectations").asInt(), is(2));
        // NOTE: this assertion does NOT distinguish closestMatch's two possible semantics, and is not
        // intended to. "nearMiss" is registered first AND is the nearer miss, so it is the expected
        // answer both under a true closeness ranking and under plain registration order. This test
        // exists to pin the bare-alias ROUTE; read shouldIdentifyClosestMatchIndependentOfExpectationOrder
        // below for the test that actually discriminates between the two.
        assertThat(body.get("closestMatch").get("expectationId").asText(), is("nearMiss"));
        assertThat(resultForExpectation(body, "nearMiss").get("differences").has("headers"), is(true));
    }

    // ------------------------------------------------------------------
    // error paths
    // ------------------------------------------------------------------

    @Test
    public void shouldRejectOpenApiDefinition() throws Exception {
        // when - an OpenAPI definition rather than an HttpRequest
        HttpResponse response = send("PUT", "/mockserver/debugMismatch",
            "{\"specUrlOrPayload\":\"org/mockserver/openapi/openapi_petstore_example.json\",\"operationId\":\"listPets\"}");

        // then
        assertThat(response.getStatusCode(), is(400));
        JsonNode body = json(response);
        assertThat(body.get("error").asText(), is("debugMismatch only supports HttpRequest definitions"));
        assertThat(body.get("correlationId"), is(not(nullValue())));
        assertThat(body.get("timestamp"), is(not(nullValue())));
    }

    @Test
    public void shouldReturnBadRequestForMalformedRequestDefinition() throws Exception {
        // when
        HttpResponse response = send("PUT", "/mockserver/debugMismatch", "{\"path\":12345}");

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(json(response).get("error").asText(), containsString("failed to debug request mismatch"));
    }

    @Test
    public void shouldTreatEmptyBodyAsAnEmptyRequestDefinition() throws Exception {
        // given
        givenTwoExpectations();

        // when - no body at all
        HttpResponse response = send("PUT", "/mockserver/debugMismatch", null);

        // then - an empty request definition is evaluated rather than rejected
        assertThat(response.getStatusCode(), is(200));
        JsonNode body = json(response);
        assertThat(body.get("totalExpectations").asInt(), is(2));
        assertThat(body.get("results").size(), is(2));
    }
}
