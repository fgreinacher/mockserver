package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Pins the {@code recordMatch} bookkeeping optimised by performance unit U4: the rotation counter is
 * advanced only for multi-response expectations, and the chaos first-match anchor is set exactly once.
 * The optimisations skip work only where nothing reads it, so every observable behaviour below is
 * unchanged from before the change.
 */
public class ExpectationRecordMatchTest {

    private Expectation sequential(HttpResponse... responses) {
        return new Expectation(request().withPath("/seq"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(responses))
            .withResponseMode(ResponseMode.SEQUENTIAL);
    }

    @Test
    public void shouldSetChaosFirstMatchAnchorOnceAndKeepItStable() {
        Expectation expectation = new Expectation(request().withPath("/x"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(response().withStatusCode(200));

        assertThat(expectation.getChaosFirstMatchEpochMillis(), is(0L));

        expectation.consumeMatch();
        long anchor = expectation.getChaosFirstMatchEpochMillis();
        assertThat(anchor, greaterThan(0L));

        // subsequent matches must never overwrite the anchor (the get()==0 pre-check keeps the CAS off)
        for (int i = 0; i < 5; i++) {
            expectation.consumeMatch();
            assertThat(expectation.getChaosFirstMatchEpochMillis(), is(anchor));
        }
    }

    @Test
    public void shouldCountMatchesAndServeSingleResponseWithoutRotation() {
        HttpResponse response = response().withStatusCode(201);
        Expectation expectation = new Expectation(request().withPath("/single"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(response);

        for (int i = 1; i <= 4; i++) {
            expectation.consumeMatch();
            assertThat(expectation.getMatchCount(), is(i));
            assertThat(((HttpResponse) expectation.getAction()).getStatusCode(), is(201));
        }
    }

    @Test
    public void shouldStillRotateSequentialResponses() {
        Expectation expectation = sequential(
            response().withStatusCode(200),
            response().withStatusCode(500),
            response().withStatusCode(418)
        );

        assertThat(serve(expectation), is(200));
        assertThat(serve(expectation), is(500));
        assertThat(serve(expectation), is(418));
        assertThat(serve(expectation), is(200));
        assertThat(expectation.getMatchCount(), is(4));
    }

    private int serve(Expectation expectation) {
        expectation.consumeMatch();
        return ((HttpResponse) expectation.getAction()).getStatusCode();
    }
}
