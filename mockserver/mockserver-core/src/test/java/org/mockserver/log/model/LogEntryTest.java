package org.mockserver.log.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests that the lazily-materialized cached fields of {@link LogEntry}
 * ({@code httpUpdatedRequests}, {@code httpUpdatedResponse}, {@code message} and
 * the cached {@code hashCode}) are invalidated by {@link LogEntry#clear()} and by
 * the setters that feed them.
 * <p>
 * This matters because {@link LogEntry} instances are reused via the Disruptor ring
 * buffer ({@link LogEntry#translateTo(LogEntry, long)} reuses slots and calls
 * {@code clear()}). Without invalidation, stale redacted/templated request/response
 * data or a stale cached {@code hashCode} could bleed from a previous logical entry
 * into a reused slot — and because {@link LogEntry#equals(Object)} short-circuits on
 * {@link LogEntry#hashCode()}, a stale hash can make two equal entries compare unequal.
 *
 * @author jamesdbloom
 */
public class LogEntryTest {

    @Test
    public void shouldReturnFreshUpdatedRequestsAfterClearAndRepopulate() {
        // given - a LogEntry whose updated-requests cache has been materialized
        LogEntry logEntry = new LogEntry()
            .setHttpRequest(request().withPath("/original").withBody("original-body"));
        RequestDefinition[] firstUpdated = logEntry.getHttpUpdatedRequests();
        assertThat(firstUpdated, is(arrayWithSize(1)));
        assertThat(firstUpdated[0].toString(), containsString("/original"));

        // when - the slot is cleared and repopulated with a different request
        logEntry.clear();
        logEntry.setHttpRequest(request().withPath("/replacement").withBody("replacement-body"));

        // then - the getter returns the NEW value, not the stale cached one
        RequestDefinition[] secondUpdated = logEntry.getHttpUpdatedRequests();
        assertThat(secondUpdated, is(arrayWithSize(1)));
        assertThat(secondUpdated[0].toString(), containsString("/replacement"));
        assertThat(secondUpdated[0].toString(), not(containsString("/original")));
    }

    @Test
    public void shouldReturnFreshUpdatedRequestsAfterSetterMutation() {
        // given - cache materialized via getter
        LogEntry logEntry = new LogEntry()
            .setHttpRequest(request().withPath("/original"));
        assertThat(logEntry.getHttpUpdatedRequests()[0].toString(), containsString("/original"));

        // when - the underlying request is changed via the setter (no clear)
        logEntry.setHttpRequest(request().withPath("/changed"));

        // then - the setter invalidated the cache, so the new value is returned
        assertThat(logEntry.getHttpUpdatedRequests()[0].toString(), containsString("/changed"));
        assertThat(logEntry.getHttpUpdatedRequests()[0].toString(), not(containsString("/original")));
    }

    @Test
    public void shouldReturnFreshUpdatedResponseAfterClearAndRepopulate() {
        // given - a LogEntry whose updated-response cache has been materialized
        LogEntry logEntry = new LogEntry()
            .setHttpResponse(response().withBody("original-response"));
        HttpResponse firstUpdated = logEntry.getHttpUpdatedResponse();
        assertThat(firstUpdated.getBodyAsString(), containsString("original-response"));

        // when - the slot is cleared and repopulated with a different response
        logEntry.clear();
        logEntry.setHttpResponse(response().withBody("replacement-response"));

        // then - the getter returns the NEW value, not the stale cached one
        HttpResponse secondUpdated = logEntry.getHttpUpdatedResponse();
        assertThat(secondUpdated.getBodyAsString(), containsString("replacement-response"));
        assertThat(secondUpdated.getBodyAsString(), not(containsString("original-response")));
    }

    @Test
    public void shouldReturnFreshUpdatedResponseAfterSetterMutation() {
        // given - cache materialized via getter
        LogEntry logEntry = new LogEntry()
            .setHttpResponse(response().withBody("original-response"));
        assertThat(logEntry.getHttpUpdatedResponse().getBodyAsString(), containsString("original-response"));

        // when - the underlying response is changed via the setter (no clear)
        logEntry.setHttpResponse(response().withBody("changed-response"));

        // then - the setter invalidated the cache, so the new value is returned
        assertThat(logEntry.getHttpUpdatedResponse().getBodyAsString(), containsString("changed-response"));
        assertThat(logEntry.getHttpUpdatedResponse().getBodyAsString(), not(containsString("original-response")));
    }

    @Test
    public void shouldReturnFreshMessageAfterClearAndRepopulate() {
        // given - a LogEntry whose message cache has been materialized
        LogEntry logEntry = new LogEntry().setMessageFormat("first message");
        assertThat(logEntry.getMessage(), is("first message"));

        // when - the slot is cleared and repopulated with a different message format
        logEntry.clear();
        logEntry.setMessageFormat("second message");

        // then - the getter returns the NEW value, not the stale cached one
        assertThat(logEntry.getMessage(), is("second message"));
    }

    @Test
    public void shouldReturnFreshMessageAfterSetterMutation() {
        // given - cache materialized via getter
        LogEntry logEntry = new LogEntry().setMessageFormat("first message");
        assertThat(logEntry.getMessage(), is("first message"));

        // when - the message format is changed via the setter (no clear)
        logEntry.setMessageFormat("updated message");

        // then - the setter invalidated the cache, so the new value is returned
        assertThat(logEntry.getMessage(), is("updated message"));
    }

    @Test
    public void shouldRecomputeHashCodeAfterClearAndRepopulate() {
        // given - two distinct logical entries
        LogEntry first = new LogEntry()
            .setEpochTime(1000L)
            .setMessageFormat("first")
            .setHttpRequest(request().withPath("/first"));
        LogEntry second = new LogEntry()
            .setEpochTime(2000L)
            .setMessageFormat("second")
            .setHttpRequest(request().withPath("/second"));

        // and - a reusable slot whose hashCode is materialized as the first entry
        LogEntry reused = new LogEntry()
            .setEpochTime(1000L)
            .setMessageFormat("first")
            .setHttpRequest(request().withPath("/first"));
        int firstHash = reused.hashCode();
        assertThat(firstHash, is(first.hashCode()));

        // when - the slot is cleared and repopulated to mirror the second entry
        reused.clear();
        reused
            .setEpochTime(2000L)
            .setMessageFormat("second")
            .setHttpRequest(request().withPath("/second"));

        // then - hashCode is recomputed for the new state (no stale hash)
        assertThat(reused.hashCode(), is(second.hashCode()));
        assertThat(reused.hashCode(), is(not(firstHash)));
        // and - equals reflects the new state (equals short-circuits on hashCode)
        assertThat(reused.equals(second), is(true));
        assertThat(reused.equals(first), is(false));
    }

    @Test
    public void shouldRecomputeHashCodeAfterSetterMutation() {
        // given - an entry whose hashCode has been materialized
        LogEntry logEntry = new LogEntry()
            .setEpochTime(1000L)
            .setMessageFormat("before");
        int beforeHash = logEntry.hashCode();

        // when - an equality-relevant field is mutated via a setter (no clear)
        logEntry.setMessageFormat("after");

        // then - hashCode is recomputed, matching a freshly-built equivalent entry
        LogEntry equivalent = new LogEntry()
            .setEpochTime(1000L)
            .setMessageFormat("after");
        assertThat(logEntry.hashCode(), is(equivalent.hashCode()));
        assertThat(logEntry.hashCode(), is(not(beforeHash)));
        assertThat(logEntry.equals(equivalent), is(true));
    }

    // The two-argument setExpectation(request, response) used on the serving path no longer allocates and
    // retains a synthetic Expectation per entry; getExpectation() derives it lazily. These tests pin that
    // (a) getExpectation() returns the same object the eager code used to build, (b) it is derived, not
    // retained, until first read, (c) a real expectation supplied via the single-argument form is stored
    // and returned as-is, and (d) equals/hashCode still distinguish synthetic / real / absent.

    private static Expectation eagerSynthetic(RequestDefinition request, HttpResponse response) {
        return new Expectation(request, Times.once(), TimeToLive.unlimited(), 0).thenRespond(response);
    }

    @Test
    public void syntheticExpectationDerivedEqualsEagerlyBuiltOne() {
        LogEntry logEntry = new LogEntry()
            .setHttpRequest(request().withPath("/x"))
            .setHttpResponse(response().withStatusCode(200).withBody("hi"))
            .setExpectation(request().withPath("/x"), response().withStatusCode(200).withBody("hi"));

        Expectation derived = logEntry.getExpectation();
        assertThat(derived, is(notNullValue()));
        // Expectation.equals ignores the random id/created, so a derived synthetic must equal the object
        // the eager code produced from the same request/response.
        assertThat(derived, is(eagerSynthetic(request().withPath("/x"), response().withStatusCode(200).withBody("hi"))));
    }

    @Test
    public void syntheticExpectationIsNotRetainedButMemoizedOnFirstRead() throws Exception {
        LogEntry logEntry = new LogEntry()
            .setHttpRequest(request().withPath("/x"))
            .setHttpResponse(response().withStatusCode(200).withBody("hi"))
            .setExpectation(request().withPath("/x"), response().withStatusCode(200).withBody("hi"));

        // Nothing is retained on the serving path: the real-expectation field is null and the derived
        // cache has not been materialized until getExpectation() is called.
        java.lang.reflect.Field expectationField = LogEntry.class.getDeclaredField("expectation");
        expectationField.setAccessible(true);
        java.lang.reflect.Field derivedField = LogEntry.class.getDeclaredField("derivedSyntheticExpectation");
        derivedField.setAccessible(true);
        assertThat(expectationField.get(logEntry), is(nullValue()));
        assertThat(derivedField.get(logEntry), is(nullValue()));

        // First read materializes and memoizes, so repeated reads return the same instance (stable id and
        // therefore byte-identical serialized output across serializations of one entry).
        Expectation first = logEntry.getExpectation();
        Expectation second = logEntry.getExpectation();
        assertThat(first, is(sameInstance(second)));
        assertThat(expectationField.get(logEntry), is(nullValue()));
    }

    @Test
    public void realExpectationStoredAndReturnedAsIs() throws Exception {
        Expectation real = new Expectation(request().withPath("/real")).withId("fixed-id");
        LogEntry logEntry = new LogEntry()
            .setHttpRequest(request().withPath("/real"))
            .setExpectation(real);

        assertThat(logEntry.getExpectation(), is(sameInstance(real)));
        java.lang.reflect.Field expectationField = LogEntry.class.getDeclaredField("expectation");
        expectationField.setAccessible(true);
        assertThat(expectationField.get(logEntry), is(sameInstance(real)));
    }

    @Test
    public void serializedSyntheticExpectationMatchesEagerlyBuiltOne() throws Exception {
        ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();

        LogEntry synthetic = new LogEntry()
            .setEpochTime(1000L)
            .setHttpRequest(request().withPath("/x"))
            .setHttpResponse(response().withStatusCode(200).withBody("hi"))
            .setExpectation(request().withPath("/x"), response().withStatusCode(200).withBody("hi"));

        LogEntry eager = new LogEntry()
            .setEpochTime(1000L)
            .setHttpRequest(request().withPath("/x"))
            .setHttpResponse(response().withStatusCode(200).withBody("hi"))
            .setExpectation(eagerSynthetic(request().withPath("/x"), response().withStatusCode(200).withBody("hi")));

        String syntheticJson = mapper.writeValueAsString(synthetic);
        String eagerJson = mapper.writeValueAsString(eager);

        // The negative control targets this assertion: if getExpectation() stops deriving the synthetic
        // expectation, the "expectation" object disappears from the serialized entry and this fails.
        assertThat(syntheticJson, containsString("\"expectation\""));
        // The only difference between the two serializations is the random expectation id; strip it and
        // the JSON is byte-identical, proving behaviour (serialized output) is unchanged.
        assertThat(stripExpectationId(syntheticJson), is(stripExpectationId(eagerJson)));
    }

    private static String stripExpectationId(String json) {
        return json.replaceAll("\"id\":\"[0-9a-fA-F-]+\"", "\"id\":\"<id>\"");
    }

    @Test
    public void equalsAndHashCodeDistinguishSyntheticRealAndAbsentExpectations() {
        LogEntry synthetic = new LogEntry()
            .setEpochTime(1000L)
            .setHttpRequest(request().withPath("/x"))
            .setHttpResponse(response().withStatusCode(200))
            .setExpectation(request().withPath("/x"), response().withStatusCode(200));
        LogEntry syntheticEquivalent = new LogEntry()
            .setEpochTime(1000L)
            .setHttpRequest(request().withPath("/x"))
            .setHttpResponse(response().withStatusCode(200))
            .setExpectation(request().withPath("/x"), response().withStatusCode(200));
        LogEntry noExpectation = new LogEntry()
            .setEpochTime(1000L)
            .setHttpRequest(request().withPath("/x"))
            .setHttpResponse(response().withStatusCode(200));

        // two synthetic entries built from equal request/response are equal
        assertThat(synthetic.equals(syntheticEquivalent), is(true));
        assertThat(synthetic.hashCode(), is(syntheticEquivalent.hashCode()));

        // a synthetic entry is NOT equal to an otherwise-identical entry that carries no expectation
        // (previously the field was non-null vs null); the synthetic flag preserves that distinction
        assertThat(synthetic.equals(noExpectation), is(false));

        // a synthetic entry is not equal to one carrying a real, unrelated expectation
        LogEntry real = new LogEntry()
            .setEpochTime(1000L)
            .setHttpRequest(request().withPath("/x"))
            .setHttpResponse(response().withStatusCode(200))
            .setExpectation(new Expectation(request().withPath("/other")));
        assertThat(synthetic.equals(real), is(false));
    }

    @Test
    public void cloneOfSyntheticEntryDoesNotForceMaterialisationAndStaysEqual() throws Exception {
        LogEntry original = new LogEntry()
            .setType(LogEntry.LogMessageType.FORWARDED_REQUEST)
            .setEpochTime(1000L)
            .setHttpRequest(request().withPath("/x"))
            .setHttpResponse(response().withStatusCode(200).withBody("hi"))
            .setExpectation(request().withPath("/x"), response().withStatusCode(200).withBody("hi"));

        LogEntry clone = original.clone();

        // cloning must not turn the lazy synthetic into a retained real expectation
        java.lang.reflect.Field expectationField = LogEntry.class.getDeclaredField("expectation");
        expectationField.setAccessible(true);
        assertThat(expectationField.get(clone), is(nullValue()));

        assertThat(clone.equals(original), is(true));
        assertThat(clone.hashCode(), is(original.hashCode()));
        assertThat(clone.getExpectation(), is(original.getExpectation()));
    }

    /**
     * getExpectation() now materializes the synthetic expectation lazily, and one retained LogEntry is read
     * concurrently by many off-consumer threads (logQueryExecutor scans, parallel /retrieve serializations).
     * The memoizing field is volatile so a reader that sees a non-null reference also sees a fully-built
     * Expectation. This drives many threads at one fresh entry per iteration and fails on any torn read
     * (null request/response or wrong values). Per docs/code/optimisation-safety.md hazard class 4 it is run
     * over many iterations; a race here is intermittent, so a single green run would prove little. With the
     * volatile removed this is a smoke check (it may not fail on every run) - the correctness guarantee
     * rests on the JMM safe-publication argument, and this test guards against a regression that drops it.
     */
    @Test
    public void concurrentGetExpectationAlwaysReturnsFullyPublishedExpectation() throws Exception {
        final int threads = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        final int iterations = 500;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < iterations; i++) {
                // a FRESH synthetic entry each iteration, so its cache starts null and the threads race the
                // first materialization rather than all reading an already-published value
                final LogEntry entry = new LogEntry()
                    .setType(LogEntry.LogMessageType.FORWARDED_REQUEST)
                    .setHttpRequest(request().withPath("/race"))
                    .setHttpResponse(response().withStatusCode(202).withBody("body-" + i))
                    .setExpectation(request().withPath("/race"), response().withStatusCode(202).withBody("body-" + i));
                final String expectedBody = "body-" + i;

                final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
                java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        Expectation exp = entry.getExpectation();
                        if (exp == null) {
                            return "null expectation";
                        }
                        RequestDefinition req = exp.getHttpRequest();
                        HttpResponse resp = exp.getHttpResponse();
                        if (req == null) {
                            return "null request";
                        }
                        if (resp == null) {
                            return "null response";
                        }
                        if (!resp.getBodyAsString().equals(expectedBody)) {
                            return "torn response body: " + resp.getBodyAsString();
                        }
                        if (resp.getStatusCode() == null || resp.getStatusCode() != 202) {
                            return "torn status: " + resp.getStatusCode();
                        }
                        return "ok";
                    }));
                }
                for (java.util.concurrent.Future<String> f : futures) {
                    assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS), is("ok"));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
