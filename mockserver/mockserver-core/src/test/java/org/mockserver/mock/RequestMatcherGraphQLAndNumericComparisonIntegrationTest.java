package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.GraphQLBody.graphQL;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End-to-end tests exercising the GraphQL body matcher and the numeric-comparison operator matcher
 * through the in-memory mock engine ({@link RequestMatchers}) as a running server would — a request
 * is either matched or not matched by a registered expectation, asserting server-side discrimination
 * rather than schema/object-level acceptance.
 *
 * <p>These mirror {@link RequestMatcherJwtAndAllOfBodyIntegrationTest}: prior coverage for both
 * matcher types was object-level only ({@code GraphQLAstMatcherTest} / {@code NumericComparisonMatcherTest}
 * drive the matcher classes directly), leaving no assertion that a request actually routes through the
 * {@link RequestMatchers} engine and is discriminated on these matcher types.
 *
 * @author jamesdbloom
 */
public class RequestMatcherGraphQLAndNumericComparisonIntegrationTest {

    private RequestMatchers requestMatchers;

    @Before
    public void prepareTestFixture() {
        Scheduler scheduler = mock(Scheduler.class);
        WebSocketClientRegistry webSocketClientRegistry = mock(WebSocketClientRegistry.class);
        requestMatchers = new RequestMatchers(configuration(), new MockServerLogger(), scheduler, webSocketClientRegistry);
    }

    @Test
    public void graphQLExpectationMatchesRequestCarryingMatchingQuery() {
        // given
        Expectation expectation = new Expectation(
            request().withPath("/graphql").withBody(graphQL("{ hero { name } }")))
            .thenRespond(response().withBody("hero-body"));
        requestMatchers.add(expectation, API);

        // then
        HttpRequest matching = request().withPath("/graphql").withBody("{\"query\":\"{ hero { name } }\"}");
        HttpRequest nonMatching = request().withPath("/graphql").withBody("{\"query\":\"{ villain { name } }\"}");
        assertThat(requestMatchers.firstMatchingExpectation(matching), is(expectation));
        assertThat(requestMatchers.firstMatchingExpectation(nonMatching), is(nullValue()));
    }

    @Test
    public void numericComparisonExpectationMatchesRequestSatisfyingTheComparison() {
        // given — numeric comparison applies to header / cookie / query-string values (e.g. "> 60")
        Expectation expectation = new Expectation(
            request().withPath("/age").withHeader("age", "> 60"))
            .thenRespond(response().withBody("adult-body"));
        requestMatchers.add(expectation, API);

        // then
        HttpRequest matching = request().withPath("/age").withHeader("age", "70");
        HttpRequest nonMatching = request().withPath("/age").withHeader("age", "50");
        assertThat(requestMatchers.firstMatchingExpectation(matching), is(expectation));
        assertThat(requestMatchers.firstMatchingExpectation(nonMatching), is(nullValue()));
    }
}
