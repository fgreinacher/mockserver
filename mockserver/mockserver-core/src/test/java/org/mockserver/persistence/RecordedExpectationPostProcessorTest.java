package org.mockserver.persistence;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class RecordedExpectationPostProcessorTest {

    private static Expectation recorded(String method, String path, HttpResponse response) {
        return new Expectation(request().withMethod(method).withPath(path)).thenRespond(response);
    }

    @Test
    public void shouldTemplatizeNumericIdSegmentsToSingleExpectation() {
        // given - three recorded GETs to /users/1, /users/2, /users/3 with same response shape
        HttpResponse sharedResponse = response().withStatusCode(200).withBody("{\"name\":\"x\"}");
        List<Expectation> recorded = Arrays.asList(
            recorded("GET", "/users/1", sharedResponse.clone()),
            recorded("GET", "/users/2", sharedResponse.clone()),
            recorded("GET", "/users/3", sharedResponse.clone())
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then - collapses to a single templated expectation matching /users/{id}
        assertThat(result.size(), is(1));
        Expectation templated = result.get(0);
        assertThat(templated.getHttpRequest().toString(), containsString("/users/{id}"));
        // path parameter declared so MockServer validation/matching treats it as a wildcard segment
        assertThat(((org.mockserver.model.HttpRequest) templated.getHttpRequest()).getPathParameters().isEmpty(), is(false));
        // response preserved
        assertThat(templated.getHttpResponse().getBodyAsString(), containsString("\"name\":\"x\""));
    }

    @Test
    public void shouldTemplatizeUuidSegments() {
        // given
        HttpResponse sharedResponse = response().withStatusCode(200);
        List<Expectation> recorded = Arrays.asList(
            recorded("GET", "/orders/123e4567-e89b-12d3-a456-426614174000", sharedResponse.clone()),
            recorded("GET", "/orders/123e4567-e89b-12d3-a456-426614174001", sharedResponse.clone())
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then
        assertThat(result.size(), is(1));
        assertThat(result.get(0).getHttpRequest().toString(), containsString("/orders/{id}"));
    }

    @Test
    public void shouldNotMergeGenuinelyDifferentPaths() {
        // given - different fixed path segments must never collapse
        HttpResponse sharedResponse = response().withStatusCode(200);
        List<Expectation> recorded = Arrays.asList(
            recorded("GET", "/users/1", sharedResponse.clone()),
            recorded("GET", "/orders/2", sharedResponse.clone()),
            recorded("GET", "/products/3", sharedResponse.clone())
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then - three distinct path templates remain
        assertThat(result.size(), is(3));
    }

    @Test
    public void shouldNotMergeDifferentMethodsUnderSameTemplate() {
        // given
        List<Expectation> recorded = Arrays.asList(
            recorded("GET", "/users/1", response().withStatusCode(200)),
            recorded("DELETE", "/users/2", response().withStatusCode(204))
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then
        assertThat(result.size(), is(2));
    }

    @Test
    public void shouldNotCollapseDifferingResponsesUnderOneTemplate() {
        // given - same /users/{id} shape but the responses genuinely differ
        List<Expectation> recorded = Arrays.asList(
            recorded("GET", "/users/1", response().withStatusCode(200).withBody("alice")),
            recorded("GET", "/users/2", response().withStatusCode(200).withBody("bob")),
            recorded("GET", "/users/3", response().withStatusCode(404))
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then - three distinct responses -> three expectations (no lossy merge)
        assertThat(result.size(), is(3));
        // each response partition has a single id, so it is NOT over-widened to /users/{id} —
        // the concrete path is kept (templatizing here would collapse three distinct matchers
        // into one, and only the first would ever fire).
        assertThat(result.get(0).getHttpRequest().toString(), containsString("/users/1"));
        assertThat(result.get(1).getHttpRequest().toString(), containsString("/users/2"));
        assertThat(result.get(2).getHttpRequest().toString(), containsString("/users/3"));
        for (Expectation expectation : result) {
            assertThat(expectation.getHttpRequest().toString(), not(containsString("{id}")));
        }
        // each partition keeps its own distinct response, in first-seen order
        assertThat(result.get(0).getHttpResponse().getBodyAsString(), is("alice"));
        assertThat(result.get(1).getHttpResponse().getBodyAsString(), is("bob"));
        assertThat(result.get(2).getHttpResponse().getStatusCode(), is(404));
    }

    @Test
    public void shouldPreserveOriginalOrderWithMixedEligibleAndIneligible() {
        // given - A (eligible) then B (ineligible) then C (eligible, same group as A).
        // The collapsed A/C group must be emitted at A's original position, ahead of B.
        Expectation a = recorded("GET", "/users/1", response().withStatusCode(200).withBody("shared"));
        Expectation ineligibleB = new Expectation(request().withMethod("GET").withPath("/forward/path"));
        Expectation c = recorded("GET", "/users/2", response().withStatusCode(200).withBody("shared"));
        List<Expectation> recorded = Arrays.asList(a, ineligibleB, c);

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then - [A+C collapsed to /users/{id}, then B] -> B is NOT reordered ahead of the group
        assertThat(result.size(), is(2));
        assertThat(result.get(0).getHttpRequest().toString(), containsString("/users/{id}"));
        assertThat(result.get(1), is(ineligibleB));
    }

    @Test
    public void shouldNotOverWidenSingleIdPartitionWhenGroupSpansMultipleIds() {
        // given - the group spans two ids (/users/1, /users/2) but the 404 partition
        // contains only a single concrete id (/users/1); it must stay concrete.
        List<Expectation> recorded = Arrays.asList(
            recorded("GET", "/users/1", response().withStatusCode(200)),
            recorded("GET", "/users/2", response().withStatusCode(200)),
            recorded("GET", "/users/1", response().withStatusCode(404))
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then - the two 200s collapse to /users/{id}; the single-id 404 keeps /users/1
        assertThat(result.size(), is(2));
        assertThat(result.get(0).getHttpResponse().getStatusCode(), is(200));
        assertThat(result.get(0).getHttpRequest().toString(), containsString("/users/{id}"));
        assertThat(result.get(1).getHttpResponse().getStatusCode(), is(404));
        assertThat(result.get(1).getHttpRequest().toString(), containsString("/users/1"));
        assertThat(result.get(1).getHttpRequest().toString(), not(containsString("{id}")));
    }

    @Test
    public void shouldDeduplicateExactDuplicatesToOne() {
        // given - identical request + identical response recorded multiple times
        HttpResponse sharedResponse = response().withStatusCode(200).withBody("same");
        List<Expectation> recorded = Arrays.asList(
            recorded("GET", "/health", sharedResponse.clone()),
            recorded("GET", "/health", sharedResponse.clone()),
            recorded("GET", "/health", sharedResponse.clone())
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then - collapse to a single expectation, no templatization (no variable segment)
        assertThat(result.size(), is(1));
        assertThat(result.get(0).getHttpRequest().toString(), containsString("/health"));
        assertThat(result.get(0).getHttpRequest().toString(), not(containsString("{id}")));
    }

    @Test
    public void shouldNotTemplatizeSingleRecordedIdWithNoOtherIds() {
        // given - only one concrete id seen; over-widening to {id} gives no dedup benefit
        List<Expectation> recorded = Collections.singletonList(
            recorded("GET", "/users/1", response().withStatusCode(200))
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then - left as-is (conservative)
        assertThat(result.size(), is(1));
        assertThat(result.get(0).getHttpRequest().toString(), containsString("/users/1"));
        assertThat(result.get(0).getHttpRequest().toString(), not(containsString("{id}")));
    }

    @Test
    public void shouldPassThroughNonHttpRequestExpectationsUnchanged() {
        // given - an expectation with no concrete response is ineligible
        Expectation forwardOnly = new Expectation(request().withMethod("GET").withPath("/users/1"));
        List<Expectation> recorded = Arrays.asList(
            forwardOnly,
            recorded("GET", "/users/2", response().withStatusCode(200)),
            recorded("GET", "/users/3", response().withStatusCode(200))
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        // then - the ineligible expectation is preserved verbatim, the eligible ones templatize
        assertThat(result.contains(forwardOnly), is(true));
        // /users/2 + /users/3 collapse to one templated expectation
        assertThat(result.size(), is(2));
    }

    @Test
    public void shouldReturnEmptyListForNullOrEmptyInput() {
        assertThat(RecordedExpectationPostProcessor.deduplicateAndTemplatize(null).isEmpty(), is(true));
        assertThat(RecordedExpectationPostProcessor.deduplicateAndTemplatize(Collections.emptyList()).isEmpty(), is(true));
    }

    // ----- value templatization (opt-in) ------------------------------------

    private static Expectation recordedRequest(org.mockserver.model.HttpRequest request, HttpResponse response) {
        return new Expectation(request).thenRespond(response);
    }

    @Test
    public void shouldNotTemplatizeValuesWhenFlagOff() {
        // given - a UUID query param; default (path-only) overload must keep it verbatim
        List<Expectation> recorded = Collections.singletonList(recordedRequest(
            request().withMethod("GET").withPath("/orders")
                .withQueryStringParameter("traceId", "123e4567-e89b-12d3-a456-426614174000"),
            response().withStatusCode(200)));

        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded);

        assertThat(result.size(), is(1));
        org.mockserver.model.HttpRequest req = (org.mockserver.model.HttpRequest) result.get(0).getHttpRequest();
        assertThat(req.getFirstQueryStringParameter("traceId"), is("123e4567-e89b-12d3-a456-426614174000"));
    }

    @Test
    public void shouldTemplatizeVolatileQueryParameterValue() {
        // given - a UUID query param value (volatile) and a stable one
        List<Expectation> recorded = Collections.singletonList(recordedRequest(
            request().withMethod("GET").withPath("/orders")
                .withQueryStringParameter("traceId", "123e4567-e89b-12d3-a456-426614174000")
                .withQueryStringParameter("page", "2"),
            response().withStatusCode(200)));

        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded, true);

        org.mockserver.model.HttpRequest req = (org.mockserver.model.HttpRequest) result.get(0).getHttpRequest();
        // volatile UUID becomes a regex wildcard
        assertThat(req.getFirstQueryStringParameter("traceId"), is(".+"));
        // stable small numeric page is preserved
        assertThat(req.getFirstQueryStringParameter("page"), is("2"));
    }

    @Test
    public void shouldTemplatizeVolatileHeaderValuesAndByVolatileHeaderName() {
        List<Expectation> recorded = Collections.singletonList(recordedRequest(
            request().withMethod("GET").withPath("/orders")
                .withHeader("Authorization", "Bearer eyJhbGci.payloadsegment.signaturesegment")
                .withHeader("X-Request-Id", "987654321098765")
                .withHeader("Accept", "application/json"),
            response().withStatusCode(200)));

        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded, true);

        org.mockserver.model.HttpRequest req = (org.mockserver.model.HttpRequest) result.get(0).getHttpRequest();
        // Authorization is a known-volatile header name -> generalised
        assertThat(req.getFirstHeader("Authorization"), is(".+"));
        // numeric correlation id -> generalised by value heuristic
        assertThat(req.getFirstHeader("X-Request-Id"), is(".+"));
        // stable content-negotiation header preserved
        assertThat(req.getFirstHeader("Accept"), is("application/json"));
    }

    @Test
    public void shouldTemplatizeVolatileJsonBodyLeavesAndPreserveStableOnes() {
        String body = "{\"id\":\"123e4567-e89b-12d3-a456-426614174000\","
            + "\"createdAt\":\"2026-06-20T12:34:56Z\","
            + "\"status\":\"ACTIVE\","
            + "\"count\":3}";
        List<Expectation> recorded = Collections.singletonList(recordedRequest(
            request().withMethod("POST").withPath("/orders").withBody(body),
            response().withStatusCode(200)));

        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded, true);

        org.mockserver.model.HttpRequest req = (org.mockserver.model.HttpRequest) result.get(0).getHttpRequest();
        assertThat(req.getBody(), is(org.hamcrest.CoreMatchers.instanceOf(org.mockserver.model.JsonBody.class)));
        String generalised = req.getBodyAsString();
        // volatile UUID and timestamp leaves become json-unit regex placeholders
        assertThat(generalised, containsString("${json-unit.regex}"));
        // stable enum and numeric leaves preserved
        assertThat(generalised, containsString("ACTIVE"));
        assertThat(generalised, containsString("\"count\":3"));
        // raw volatile values no longer pinned
        assertThat(generalised, not(containsString("123e4567-e89b-12d3-a456-426614174000")));
        assertThat(generalised, not(containsString("2026-06-20T12:34:56Z")));
    }

    @Test
    public void shouldLeaveStableValuesAndNonJsonBodyUntouched() {
        List<Expectation> recorded = Collections.singletonList(recordedRequest(
            request().withMethod("POST").withPath("/orders")
                .withQueryStringParameter("type", "standard")
                .withBody("plain text body, not json"),
            response().withStatusCode(200)));

        List<Expectation> result = RecordedExpectationPostProcessor.deduplicateAndTemplatize(recorded, true);

        org.mockserver.model.HttpRequest req = (org.mockserver.model.HttpRequest) result.get(0).getHttpRequest();
        assertThat(req.getFirstQueryStringParameter("type"), is("standard"));
        assertThat(req.getBodyAsString(), is("plain text body, not json"));
    }

    @Test
    public void volatilityHeuristicClassifiesValuesConservatively() {
        // volatile
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("123e4567-e89b-12d3-a456-426614174000"), is(true));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("1718885696123"), is(true));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("2026-06-20T12:34:56Z"), is(true));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("2026-06-20"), is(true));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("123456789"), is(true));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("Bearer aaaaaaaa.bbbbbbbb.cccccccc"), is(true));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("abcdefABCDEF0123456789xyzw"), is(true));
        // stable
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("42"), is(false));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("200"), is(false));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("ACTIVE"), is(false));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("application/json"), is(false));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("true"), is(false));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("standard"), is(false));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue(null), is(false));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue(""), is(false));
        // long SCREAMING_SNAKE_CASE enum constants and all-uppercase codes are stable, not tokens
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("SUBSCRIPTION_CANCELLED_BY_USER"), is(false));
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("ANOTHERVERYLONGUPPERCASEWORD"), is(false));
        // a lowercase hex digest / git SHA is volatile (high-entropy, mixed)
        assertThat(RecordedExpectationPostProcessor.isVolatileValue("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b"), is(true));
    }

    // ----- consolidate (record -> mock engine) ------------------------------

    @Test
    public void shouldConsolidateFiftyIdenticalHitsToOneUnlimitedExpectation() {
        // given - 50 identical recorded hits to GET /users/123 (as raw recording would emit
        // 50 brittle Times.once() expectations)
        HttpResponse sharedResponse = response().withStatusCode(200).withBody("{\"name\":\"x\"}");
        java.util.List<Expectation> recorded = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            recorded.add(recorded("GET", "/users/123", sharedResponse.clone()));
        }

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.consolidate(recorded, false);

        // then - a single unlimited-times expectation
        assertThat(result.size(), is(1));
        Expectation consolidated = result.get(0);
        assertThat(consolidated.getTimes().isUnlimited(), is(true));
        assertThat(consolidated.getTimeToLive().isUnlimited(), is(true));
        assertThat(consolidated.getHttpResponse().getBodyAsString(), containsString("\"name\":\"x\""));
        // single distinct response -> no sequential list
        assertThat(consolidated.getHttpResponses(), is(org.hamcrest.CoreMatchers.nullValue()));
    }

    @Test
    public void shouldParameterizeVaryingPathSegmentAcrossManyIds() {
        // given - the same endpoint hit with three different numeric ids
        HttpResponse sharedResponse = response().withStatusCode(200);
        List<Expectation> recorded = Arrays.asList(
            recorded("GET", "/users/123", sharedResponse.clone()),
            recorded("GET", "/users/456", sharedResponse.clone()),
            recorded("GET", "/users/789", sharedResponse.clone())
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.consolidate(recorded, false);

        // then - one expectation matching /users/{id}, unlimited times
        assertThat(result.size(), is(1));
        assertThat(result.get(0).getHttpRequest().toString(), containsString("/users/{id}"));
        assertThat(result.get(0).getTimes().isUnlimited(), is(true));
    }

    @Test
    public void shouldStripVolatileRequestHeaders() {
        // given - a recorded request carrying volatile headers (from HarImporter's set)
        List<Expectation> recorded = Collections.singletonList(recordedRequest(
            request().withMethod("GET").withPath("/users/1")
                .withHeader("Authorization", "Bearer secret-token")
                .withHeader("User-Agent", "curl/8.0")
                .withHeader("X-Tenant", "acme"),
            response().withStatusCode(200)));

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.consolidate(recorded, false);

        // then - volatile headers gone, meaningful header kept
        org.mockserver.model.HttpRequest req = (org.mockserver.model.HttpRequest) result.get(0).getHttpRequest();
        assertThat(req.getFirstHeader("Authorization"), is(""));
        assertThat(req.getFirstHeader("User-Agent"), is(""));
        assertThat(req.getFirstHeader("X-Tenant"), is("acme"));
    }

    @Test
    public void shouldSequenceDifferingResponsesForSameRequestShape() {
        // given - same GET /users/1 returned two different bodies over time
        List<Expectation> recorded = Arrays.asList(
            recorded("GET", "/users/1", response().withStatusCode(200).withBody("first")),
            recorded("GET", "/users/1", response().withStatusCode(200).withBody("second")),
            // an exact duplicate of the first response should collapse, not add a third step
            recorded("GET", "/users/1", response().withStatusCode(200).withBody("first"))
        );

        // when
        List<Expectation> result = RecordedExpectationPostProcessor.consolidate(recorded, false);

        // then - ONE expectation with a SEQUENTIAL two-response list (deduped, first-seen order)
        assertThat(result.size(), is(1));
        Expectation consolidated = result.get(0);
        assertThat(consolidated.getResponseMode(), is(org.mockserver.mock.ResponseMode.SEQUENTIAL));
        assertThat(consolidated.getHttpResponses().size(), is(2));
        assertThat(consolidated.getHttpResponses().get(0).getBodyAsString(), is("first"));
        assertThat(consolidated.getHttpResponses().get(1).getBodyAsString(), is("second"));
        assertThat(consolidated.getTimes().isUnlimited(), is(true));
    }

    @Test
    public void shouldParameterizeVolatileValuesWhenFlagSet() {
        // given - a volatile UUID query param on a consolidated request
        List<Expectation> recorded = Collections.singletonList(recordedRequest(
            request().withMethod("GET").withPath("/orders")
                .withQueryStringParameter("traceId", "123e4567-e89b-12d3-a456-426614174000"),
            response().withStatusCode(200)));

        // when - parameterize=true
        List<Expectation> result = RecordedExpectationPostProcessor.consolidate(recorded, true);

        org.mockserver.model.HttpRequest req = (org.mockserver.model.HttpRequest) result.get(0).getHttpRequest();
        assertThat(req.getFirstQueryStringParameter("traceId"), is(".+"));
    }

    @Test
    public void shouldPassThroughIneligibleAndReturnEmptyForNull() {
        Expectation forwardOnly = new Expectation(request().withMethod("GET").withPath("/proxy/x"));
        List<Expectation> recorded = Arrays.asList(
            forwardOnly,
            recorded("GET", "/users/1", response().withStatusCode(200)),
            recorded("GET", "/users/2", response().withStatusCode(200))
        );

        List<Expectation> result = RecordedExpectationPostProcessor.consolidate(recorded, false);
        assertThat(result.contains(forwardOnly), is(true));
        assertThat(result.size(), is(2)); // forwardOnly + one consolidated /users/{id}

        assertThat(RecordedExpectationPostProcessor.consolidate(null, false).isEmpty(), is(true));
        assertThat(RecordedExpectationPostProcessor.consolidate(Collections.emptyList(), true).isEmpty(), is(true));
    }
}
