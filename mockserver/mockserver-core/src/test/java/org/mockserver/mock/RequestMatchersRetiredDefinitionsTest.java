package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.Times;
import org.mockserver.mock.drift.PercentileTracker;
import org.mockserver.model.ExpectationId;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.InMemoryStateBackend;

import java.util.Collections;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * An expectation id stays resolvable (verify / retrieve / clear by id) after the expectation is
 * removed, but only the removed expectations' definitions are kept, and only within their bound.
 */
public class RequestMatchersRetiredDefinitionsTest {

    private RequestMatchers matchers;

    @Before
    public void setup() {
        Configuration configuration = configuration();
        matchers = new RequestMatchers(configuration, new MockServerLogger(), new Scheduler(configuration, new MockServerLogger(), true), mock(WebSocketClientRegistry.class));
        matchers.setStateBackend(new InMemoryStateBackend(configuration.maxExpectations()));
    }

    private RequestDefinition resolve(String id) {
        return matchers.retrieveRequestDefinitions(Collections.singletonList(new ExpectationId().withId(id))).findFirst().orElse(null);
    }

    @Test
    public void liveExpectationResolvesFromTheStoreAndRetainsNothingExtra() {
        matchers.add(new Expectation(request("/live")).withId("live").thenRespond(response()), API);

        assertThat(resolve("live"), is(request("/live")));
        assertThat(matchers.retiredRequestDefinitions.size(), is(0));
    }

    @Test
    public void timesExhaustedExpectationStaysResolvableAfterRemoval() {
        matchers.add(new Expectation(request("/once"), Times.once(), null, 0).withId("once").thenRespond(response()), API);

        Expectation served = matchers.firstMatchingExpectation(request("/once"));
        assertThat(served, is(notNullValue()));
        // once the response is written, the used-up expectation is removed
        matchers.postProcess(served);

        assertThat(matchers.size(), is(0));
        assertThat(resolve("once"), is(request("/once")));
    }

    @Test
    public void clearedExpectationStaysResolvableUntilReset() {
        matchers.add(new Expectation(request("/cleared")).withId("cleared").thenRespond(response()), API);
        matchers.clear(new ExpectationId().withId("cleared"), "c");

        assertThat(resolve("cleared"), is(request("/cleared")));

        matchers.reset();

        assertThat(matchers.retiredRequestDefinitions.size(), is(0));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> resolve("cleared"));
        assertThat(unknown.getMessage(), is("No expectation found with id cleared"));
    }

    @Test
    public void reAddingARetiredIdResolvesToTheNewDefinitionAndDropsTheRetiredCopy() {
        matchers.add(new Expectation(request("/v1")).withId("same").thenRespond(response()), API);
        matchers.clear(new ExpectationId().withId("same"), "c");
        matchers.add(new Expectation(request("/v2")).withId("same").thenRespond(response()), API);

        assertThat(resolve("same"), is(request("/v2")));
        assertThat(matchers.retiredRequestDefinitions.size(), is(0));
    }

    @Test
    public void removedDefinitionsAreBoundedByBytesNotOnlyByCount() {
        String body = new String(new char[100_000]).replace('\0', 'x');
        long perDefinition = Expectation.estimatedRequestDefinitionHeapSize(request("/p").withBody(body));
        // a budget of three definitions, far below the count bound (maxExpectations)
        matchers.retiredRequestDefinitions.setMaxBytes(perDefinition * 3);
        int removed = 10;
        for (int i = 0; i < removed; i++) {
            matchers.add(new Expectation(request("/p" + i).withBody(body)).withId("id" + i).thenRespond(response()), API);
            matchers.clear(new ExpectationId().withId("id" + i), "c");
        }

        assertThat(matchers.retiredRequestDefinitions.size(), is(3));
        assertThat(matchers.retiredRequestDefinitions.retainedBytes(), is(lessThanOrEqualTo(perDefinition * 3)));
        assertThrows(IllegalArgumentException.class, () -> resolve("id" + (removed - 4)));
        assertThat(resolve("id" + (removed - 3)), is(notNullValue()));
        assertThat(resolve("id" + (removed - 1)), is(notNullValue()));
    }

    @Test
    public void removingAnExpectationDropsItsDriftResponseTimeWindow() {
        String id = UUID.randomUUID().toString();
        matchers.add(new Expectation(request("/drift")).withId(id).thenRespond(response()), API);
        PercentileTracker.getInstance().record(id, 5);

        matchers.clear(new ExpectationId().withId(id), "c");

        assertThat(PercentileTracker.getInstance().count(id), is(0));
    }

    @Test
    public void evictingAnExpectationDropsItsDriftResponseTimeWindow() {
        Configuration configuration = configuration().maxExpectations(1);
        RequestMatchers bounded = new RequestMatchers(configuration, new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        bounded.setStateBackend(new InMemoryStateBackend(1));
        String first = UUID.randomUUID().toString();
        bounded.add(new Expectation(request("/first")).withId(first).thenRespond(response()), API);
        PercentileTracker.getInstance().record(first, 5);

        bounded.add(new Expectation(request("/second")).withId(UUID.randomUUID().toString()).thenRespond(response()), API);

        assertThat(PercentileTracker.getInstance().count(first), is(0));
    }

    @Test
    public void evictingAnExpectationWithoutABackendDropsItsDriftResponseTimeWindow() {
        RequestMatchers bounded = new RequestMatchers(configuration().maxExpectations(1), new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        String first = UUID.randomUUID().toString();
        bounded.add(new Expectation(request("/first")).withId(first).thenRespond(response()), API);
        PercentileTracker.getInstance().record(first, 5);

        bounded.add(new Expectation(request("/second")).withId(UUID.randomUUID().toString()).thenRespond(response()), API);

        assertThat(PercentileTracker.getInstance().count(first), is(0));
    }
}
