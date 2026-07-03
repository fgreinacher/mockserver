package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for the force-response-variant affordance: an incoming request carrying the
 * {@link Expectation#FORCE_RESPONSE_INDEX_HEADER} (0-based) header forces which entry of a
 * multi-response {@code httpResponses} sequence is served for that single request, overriding the
 * configured {@link ResponseMode} without advancing the ongoing sequential/switch rotation (peek
 * semantics). Invalid / out-of-bounds / absent values fall through to normal selection — never an error.
 *
 * @see Expectation#getPrimaryAction(Integer)
 * @see Expectation#parseForcedResponseIndex(org.mockserver.model.HttpRequest)
 */
public class ForceResponseIndexSelectionTest {

    private Expectation sequentialExpectation(HttpResponse... responses) {
        return new Expectation(request().withPath("/seq"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(responses))
            .withResponseMode(ResponseMode.SEQUENTIAL);
    }

    /**
     * Mirrors the runtime path: {@link Expectation#consumeMatch()} runs once per matched request
     * (during matching) before the primary action is resolved. Returns the served status code.
     */
    private int serveNormal(Expectation expectation) {
        expectation.consumeMatch();
        return ((HttpResponse) expectation.getAction()).getStatusCode();
    }

    private int serveForced(Expectation expectation, int forcedIndex) {
        // mirror the runtime: the forced index is plumbed into consumeMatch at match time so the
        // rotation counter is NOT advanced, then the same index selects the variant at resolution time
        expectation.consumeMatch((Integer) forcedIndex);
        return ((HttpResponse) expectation.getAction((Integer) forcedIndex)).getStatusCode();
    }

    @Test
    public void shouldServeForcedIndexOverridingSequential() {
        // given - sequential [200, 500, 418]; a fresh expectation would normally serve index 0 first
        Expectation expectation = sequentialExpectation(
            response().withStatusCode(200),
            response().withStatusCode(500),
            response().withStatusCode(418)
        );

        // when - the first request forces index 2
        int served = serveForced(expectation, 2);

        // then - the forced variant is returned, not the sequential head
        assertThat(served, is(418));
    }

    @Test
    public void shouldNotAdvanceSequenceWhenForced() {
        // given - sequential [200, 500, 418]
        Expectation expectation = sequentialExpectation(
            response().withStatusCode(200),
            response().withStatusCode(500),
            response().withStatusCode(418)
        );

        // when / then - two normal requests advance the rotation
        assertThat(serveNormal(expectation), is(200)); // index 0
        assertThat(serveNormal(expectation), is(500)); // index 1

        // a forced request peeks index 0 WITHOUT consuming a rotation slot
        assertThat(serveForced(expectation, 0), is(200));

        // the next normal request continues exactly where the rotation left off (index 2),
        // as if the forced request had never happened
        assertThat(serveNormal(expectation), is(418)); // index 2, not shifted by the forced peek
        // and rotation keeps cycling normally
        assertThat(serveNormal(expectation), is(200)); // index 0 again
        assertThat(serveNormal(expectation), is(500)); // index 1
    }

    @Test
    public void shouldOverrideWeightedModeDeterministically() {
        // given - weighted selection heavily skewed toward index 0
        Expectation expectation = new Expectation(request().withPath("/weighted"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(503)
            ))
            .withResponseMode(ResponseMode.WEIGHTED)
            .withResponseWeights(Arrays.asList(1000, 1));

        // when / then - forcing index 1 deterministically bypasses the weighting every time
        for (int i = 0; i < 20; i++) {
            assertThat(serveForced(expectation, 1), is(503));
        }
    }

    @Test
    public void shouldIgnoreOutOfBoundsIndex() {
        // given - sequential [200, 500]
        Expectation expectation = sequentialExpectation(
            response().withStatusCode(200),
            response().withStatusCode(500)
        );

        // when - forcing an index past the end falls back to normal selection (index 0 on first match)
        int served = serveForced(expectation, 5);

        // then
        assertThat(served, is(200));
    }

    @Test
    public void shouldIgnoreNegativeIndex() {
        // given - sequential [200, 500]
        Expectation expectation = sequentialExpectation(
            response().withStatusCode(200),
            response().withStatusCode(500)
        );

        // when - a negative index is invalid and ignored (normal selection applies)
        int served = serveForced(expectation, -1);

        // then
        assertThat(served, is(200));
    }

    @Test
    public void shouldLeaveSingleResponseExpectationUnaffected() {
        // given - a plain single-response expectation (the httpResponse field, not a sequence)
        Expectation expectation = new Expectation(request().withPath("/plain"), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(response().withStatusCode(201));

        // when - a forced index has no sequence to select from
        expectation.consumeMatch();
        int served = ((HttpResponse) expectation.getAction((Integer) 3)).getStatusCode();

        // then - the single response is served unchanged
        assertThat(served, is(201));
    }

    @Test
    public void shouldFallBackToNormalSelectionWhenForcedIndexNull() {
        // given - sequential [200, 500]
        Expectation expectation = sequentialExpectation(
            response().withStatusCode(200),
            response().withStatusCode(500)
        );

        // when - a null forced index (header absent) selects normally
        expectation.consumeMatch();
        int served = ((HttpResponse) expectation.getAction((Integer) null)).getStatusCode();

        // then
        assertThat(served, is(200));
    }

    // --- header parsing ---------------------------------------------------------------------

    @Test
    public void shouldParseValidForcedResponseIndexHeader() {
        assertThat(Expectation.parseForcedResponseIndex(
            request().withHeader(Expectation.FORCE_RESPONSE_INDEX_HEADER, "2")), is(2));
    }

    @Test
    public void shouldTrimWhitespaceWhenParsingHeader() {
        assertThat(Expectation.parseForcedResponseIndex(
            request().withHeader(Expectation.FORCE_RESPONSE_INDEX_HEADER, "  4 ")), is(4));
    }

    @Test
    public void shouldReturnNullForAbsentHeader() {
        assertThat(Expectation.parseForcedResponseIndex(request().withPath("/x")), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForBlankHeader() {
        assertThat(Expectation.parseForcedResponseIndex(
            request().withHeader(Expectation.FORCE_RESPONSE_INDEX_HEADER, "   ")), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForNonIntegerHeader() {
        assertThat(Expectation.parseForcedResponseIndex(
            request().withHeader(Expectation.FORCE_RESPONSE_INDEX_HEADER, "abc")), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForNullRequest() {
        assertThat(Expectation.parseForcedResponseIndex(null), is(nullValue()));
    }

    // --- Times / peek bookkeeping -----------------------------------------------------------

    @Test
    public void shouldStillCountForcedRequestAgainstMatchCountAndTimes() {
        // given - a limited-Times sequential expectation
        Expectation expectation = new Expectation(request().withPath("/seq"), Times.exactly(3), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200),
                response().withStatusCode(500)
            ))
            .withResponseMode(ResponseMode.SEQUENTIAL);

        // when - a forced request is served (peek): it must still consume a Times unit and bump matchCount,
        // but must NOT advance the rotation (a subsequent normal request still gets index 0)
        assertThat(serveForced(expectation, 1), is(500));
        assertThat(expectation.getMatchCount(), is(1));

        // then - the following normal request is the FIRST rotation position (index 0), unshifted by the peek
        assertThat(serveNormal(expectation), is(200));
        assertThat(expectation.getMatchCount(), is(2));
    }

    // --- outbound forward strip (recordings retain header; forwards never carry it) ----------

    @Test
    public void shouldOmitControlHeaderFromOutboundForwardRequestButRetainOnModel() {
        // given - a model request carrying the control header plus an ordinary header
        org.mockserver.model.HttpRequest modelRequest = request()
            .withPath("/downstream")
            .withHeader("Host", "example.com")
            .withHeader("x-keep", "yes")
            .withHeader(Expectation.FORCE_RESPONSE_INDEX_HEADER, "1");

        // when - the outbound (wire) request is built by the forward/proxy mapper
        io.netty.handler.codec.http.FullHttpRequest outbound =
            new org.mockserver.mappers.MockServerHttpRequestToFullHttpRequest(new org.mockserver.logging.MockServerLogger(), null)
                .mapMockServerRequestToNettyRequest(modelRequest);
        try {
            // then - the control header is filtered out of the wire request, ordinary headers survive
            assertThat(outbound.headers().contains(Expectation.FORCE_RESPONSE_INDEX_HEADER), is(false));
            assertThat(outbound.headers().contains("x-keep"), is(true));
            // and the model object is NOT mutated (recorded traffic deterministically retains the header)
            assertThat(modelRequest.containsHeader(Expectation.FORCE_RESPONSE_INDEX_HEADER), is(true));
        } finally {
            outbound.release();
        }
    }
}
