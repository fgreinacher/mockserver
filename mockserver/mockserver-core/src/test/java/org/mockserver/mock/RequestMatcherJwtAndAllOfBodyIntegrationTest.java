package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.AllOfBody.allOf;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonPathBody.jsonPath;
import static org.mockserver.model.Jwt.jwt;
import static org.mockserver.model.RegexBody.regex;

/**
 * End-to-end tests exercising the JWT-claims matcher and the {@code allOf} body composition through
 * the in-memory mock engine ({@link RequestMatchers}) rather than a single property matcher.
 *
 * @author jamesdbloom
 */
public class RequestMatcherJwtAndAllOfBodyIntegrationTest {

    private RequestMatchers requestMatchers;

    @Before
    public void prepareTestFixture() {
        Scheduler scheduler = mock(Scheduler.class);
        WebSocketClientRegistry webSocketClientRegistry = mock(WebSocketClientRegistry.class);
        requestMatchers = new RequestMatchers(configuration(), new MockServerLogger(), scheduler, webSocketClientRegistry);
    }

    private static String token(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    @Test
    public void jwtExpectationMatchesRequestCarryingMatchingClaims() {
        // given
        Expectation expectation = new Expectation(
            request().withPath("/admin").withJwt(jwt().withClaim("scope", ".*admin.*")))
            .thenRespond(response().withBody("admin-body"));
        requestMatchers.add(expectation, API);

        // then
        HttpRequest matching = request().withPath("/admin").withHeader("authorization", "Bearer " + token("{\"scope\":\"read admin\"}"));
        HttpRequest nonMatching = request().withPath("/admin").withHeader("authorization", "Bearer " + token("{\"scope\":\"read\"}"));
        assertThat(requestMatchers.firstMatchingExpectation(matching), is(expectation));
        assertThat(requestMatchers.firstMatchingExpectation(nonMatching), is(nullValue()));
    }

    @Test
    public void allOfBodyExpectationMatchesRequestSatisfyingEveryComponent() {
        // given
        Expectation expectation = new Expectation(
            request().withPath("/orders").withBody(allOf(jsonPath("$.name"), regex(".*value.*"))))
            .thenRespond(response().withBody("orders-body"));
        requestMatchers.add(expectation, API);

        // then
        HttpRequest matching = request().withPath("/orders").withBody("{\"name\":\"value\"}");
        HttpRequest nonMatching = request().withPath("/orders").withBody("{\"other\":\"value\"}");
        assertThat(requestMatchers.firstMatchingExpectation(matching), is(expectation));
        assertThat(requestMatchers.firstMatchingExpectation(nonMatching), is(nullValue()));
    }
}
