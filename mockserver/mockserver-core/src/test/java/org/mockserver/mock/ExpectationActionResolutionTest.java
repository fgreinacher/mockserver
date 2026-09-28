package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.Action;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Pins the action-resolution fast path and the expectation-id stamping introduced by performance unit
 * U4. {@code getPrimaryAction()} resolves the single configured action without allocating the
 * {@code getAllActions()} list, and the id is written to the shared {@link Action} only when it changes
 * — but the served action's id must stay correct across every builder mutation path (hazard 2). These
 * tests assert the observable contract, not the allocation, so they fail if the fast path returns the
 * wrong action or the id-write conditional caches a stale id.
 */
public class ExpectationActionResolutionTest {

    private Expectation expectation(HttpResponse response) {
        return new Expectation(request().withPath("/path"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(response);
    }

    @Test
    public void shouldResolveSingleActionAndStampExpectationId() {
        HttpResponse response = response().withStatusCode(200);
        Expectation expectation = expectation(response).withId("fixed-id");

        Action action = expectation.getAction();

        assertThat(action, sameInstance(response));
        assertThat(action.getExpectationId(), is("fixed-id"));
    }

    @Test
    public void shouldStampLazilyGeneratedIdWhenNoExplicitIdSet() {
        HttpResponse response = response().withStatusCode(200);
        Expectation expectation = expectation(response);

        Action action = expectation.getAction();

        // getId() lazily generates a UUID; the resolved action must carry that same id
        assertThat(action.getExpectationId(), notNullValue());
        assertThat(action.getExpectationId(), is(expectation.getId()));
    }

    /**
     * Hazard 2 (mutable builders): the id can be assigned or changed AFTER the action was assigned and
     * already resolved once. The conditional id-write must re-stamp on the next resolve, never cache the
     * first value — a "stamp once at assignment" design would fail this.
     */
    @Test
    public void shouldReStampActionIdAfterWithIdChangesItPostResolution() {
        HttpResponse response = response().withStatusCode(200);
        Expectation expectation = expectation(response).withId("first");

        assertThat(expectation.getAction().getExpectationId(), is("first"));

        expectation.withId("second");

        assertThat(expectation.getAction().getExpectationId(), is("second"));
    }

    @Test
    public void shouldResolveSingleForwardActionByPrecedence() {
        HttpForward forward = HttpForward.forward().withHost("localhost").withPort(1080);
        Expectation expectation = new Expectation(request().withPath("/f"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenForward(forward)
            .withId("fwd");

        Action action = expectation.getAction();

        assertThat(action, sameInstance(forward));
        assertThat(action.getExpectationId(), is("fwd"));
    }

    @Test
    public void shouldResolveSingleErrorActionByPrecedence() {
        HttpError error = HttpError.error().withDropConnection(true);
        Expectation expectation = new Expectation(request().withPath("/e"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenError(error)
            .withId("err");

        Action action = expectation.getAction();

        assertThat(action, sameInstance(error));
        assertThat(action.getExpectationId(), is("err"));
    }

    @Test
    public void shouldReturnNullWhenNoActionConfigured() {
        Expectation expectation = new Expectation(request().withPath("/none"), Times.unlimited(), TimeToLive.unlimited(), 0);

        assertThat(expectation.getAction(), is(nullValue()));
        assertThat(expectation.getSecondaryActions(), hasSize(0));
    }

    @Test
    public void shouldReturnEmptySecondaryActionsForSingleActionExpectation() {
        Expectation expectation = expectation(response().withStatusCode(200)).withId("single");

        assertThat(expectation.getSecondaryActions(), hasSize(0));
    }

    @Test
    public void shouldStampSecondaryActionsForMultiActionExpectation() {
        HttpResponse primary = (HttpResponse) response().withStatusCode(200).withPrimary(true);
        HttpForward secondary = HttpForward.forward().withHost("localhost").withPort(1080);
        Expectation expectation = new Expectation(request().withPath("/multi"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(primary)
            .thenForward(secondary)
            .withId("multi");

        assertThat(expectation.getAction(), sameInstance(primary));
        List<Action> secondaryActions = expectation.getSecondaryActions();
        assertThat(secondaryActions, contains((Action) secondary));
        assertThat(secondary.getExpectationId(), is("multi"));
    }

    @Test
    public void shouldStampForcedIndexResponse() {
        Expectation expectation = new Expectation(request().withPath("/seq"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(response().withStatusCode(200), response().withStatusCode(500)))
            .withResponseMode(ResponseMode.SEQUENTIAL)
            .withId("seq");

        expectation.consumeMatch((Integer) 1);
        Action forced = expectation.getAction((Integer) 1);

        assertThat(((HttpResponse) forced).getStatusCode(), is(500));
        assertThat(forced.getExpectationId(), is("seq"));
    }

    @Test
    public void shouldStampSelectedSequentialResponse() {
        Expectation expectation = new Expectation(request().withPath("/seq"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(response().withStatusCode(200), response().withStatusCode(500)))
            .withResponseMode(ResponseMode.SEQUENTIAL)
            .withId("seq");

        expectation.consumeMatch();
        Action action = expectation.getAction();

        assertThat(((HttpResponse) action).getStatusCode(), is(200));
        assertThat(action.getExpectationId(), is("seq"));
    }
}
