package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.time.TimeService;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Lifecycle (TTL / {@link Times}) gating for OpenAPI-backed expectations.
 * <p>
 * {@link HttpRequestsPropertiesMatcher} delegates to a list of per-operation
 * {@link HttpRequestPropertiesMatcher} instances built with {@code update(HttpRequest)} rather
 * than {@code update(Expectation)}, so each delegate's own {@code expectation} field stays null
 * and its {@code isActive()} check is trivially true. The TTL and remaining-matches state lives
 * only on the <em>outer</em> matcher's expectation, and nothing on the serving path consulted it
 * -- so an expired OpenAPI expectation continued to be served indefinitely.
 */
public class HttpRequestsPropertiesMatcherLifecycleTest {

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger =
        new MockServerLogger(HttpRequestsPropertiesMatcherLifecycleTest.class);

    private static final String SPEC = "{" + System.lineSeparator() +
        "  \"openapi\": \"3.0.0\"," + System.lineSeparator() +
        "  \"info\": { \"title\": \"test\", \"version\": \"1.0\" }," + System.lineSeparator() +
        "  \"paths\": {" + System.lineSeparator() +
        "    \"/pets\": {" + System.lineSeparator() +
        "      \"get\": {" + System.lineSeparator() +
        "        \"operationId\": \"listPets\"," + System.lineSeparator() +
        "        \"responses\": { \"200\": { \"description\": \"ok\" } }" + System.lineSeparator() +
        "      }" + System.lineSeparator() +
        "    }" + System.lineSeparator() +
        "  }" + System.lineSeparator() +
        "}";

    private HttpRequestsPropertiesMatcher matcherFor(Expectation expectation) {
        HttpRequestsPropertiesMatcher matcher =
            new HttpRequestsPropertiesMatcher(configuration, mockServerLogger);
        matcher.update(expectation);
        return matcher;
    }

    /**
     * Sanity check: a live OpenAPI expectation matches a request its spec describes.
     */
    @Test
    public void shouldMatchWhileExpectationIsActive() {
        // given
        Expectation expectation = new Expectation(
            new OpenAPIDefinition().withSpecUrlOrPayload(SPEC),
            Times.unlimited(),
            TimeToLive.exactly(TimeUnit.HOURS, 1L),
            0
        );

        // when / then
        assertThat(matcherFor(expectation).matches(null, request().withMethod("GET").withPath("/pets")),
            is(true));
    }

    /**
     * An OpenAPI expectation whose TTL has elapsed must stop matching, exactly as a plain
     * {@link HttpRequestPropertiesMatcher}-backed expectation does.
     */
    @Test
    public void shouldNotMatchOnceTimeToLiveHasExpired() {
        // given -- a TTL whose end date is pinned in the past, so expiry is deterministic and
        // needs no sleep
        Expectation expectation = new Expectation(
            new OpenAPIDefinition().withSpecUrlOrPayload(SPEC),
            Times.unlimited(),
            TimeToLive.exactly(TimeUnit.HOURS, 1L).setEndDate(TimeService.currentTimeMillis() - 1_000L),
            0
        );
        assertThat("precondition: the expectation must have expired", expectation.isActive(), is(false));

        // when / then
        assertThat("an expired OpenAPI expectation must not be served",
            matcherFor(expectation).matches(null, request().withMethod("GET").withPath("/pets")),
            is(false));
    }

    /**
     * An OpenAPI expectation whose remaining match count is exhausted must stop matching.
     */
    @Test
    public void shouldNotMatchOnceRemainingMatchesAreExhausted() {
        // given
        Expectation expectation = new Expectation(
            new OpenAPIDefinition().withSpecUrlOrPayload(SPEC),
            Times.exactly(1),
            TimeToLive.unlimited(),
            0
        );
        expectation.decrementRemainingMatches();
        assertThat("precondition: the expectation must be exhausted", expectation.isActive(), is(false));

        // when / then
        assertThat("an exhausted OpenAPI expectation must not be served",
            matcherFor(expectation).matches(null, request().withMethod("GET").withPath("/pets")),
            is(false));
    }

    /**
     * The more dangerous half: when no per-operation matchers could be derived -- a blank or
     * absent spec leaves the delegate list null rather than empty -- the matcher must NOT fall
     * through to matching every request. A data-plane expectation that matches everything would
     * hijack the whole server.
     * <p>
     * Note a spec that fails to <em>parse</em> is already safe: the list is assigned an empty
     * ArrayList before parsing, so it ends up empty (no match) rather than null.
     */
    @Test
    public void shouldNotMatchEveryRequestWhenNoMatchersCouldBeDerived() {
        // given -- an OpenAPI definition with a blank spec, so no operation matchers are built
        Expectation expectation = new Expectation(
            new OpenAPIDefinition().withSpecUrlOrPayload(""),
            Times.unlimited(),
            TimeToLive.unlimited(),
            0
        );

        // when / then
        assertThat("an OpenAPI expectation with no derivable operations must not match every request",
            matcherFor(expectation).matches(null, request().withMethod("GET").withPath("/anything-at-all")),
            is(false));
    }

    /**
     * The other half of the split: on the CONTROL plane an unpopulated matcher is an empty
     * filter, and matching everything is the intended "no restriction" semantic for
     * {@code clear} / {@code retrieve} / {@code verify} by request definition. Only
     * {@code update(RequestDefinition)} marks a matcher control-plane, which is the seam the fix
     * splits on -- so this behaviour is deliberately preserved while the data-plane case above is
     * not.
     */
    @Test
    public void shouldMatchEveryRequestOnControlPlaneWhenNoMatchersCouldBeDerived() {
        // given -- update(RequestDefinition), the control-plane entry point
        HttpRequestsPropertiesMatcher matcher =
            new HttpRequestsPropertiesMatcher(configuration, mockServerLogger);
        matcher.update(new OpenAPIDefinition().withSpecUrlOrPayload(""));

        // when / then
        assertThat("a control-plane OpenAPI filter with no derivable operations is an empty "
                + "filter and must still match everything",
            matcher.matches(null, request().withMethod("GET").withPath("/anything-at-all")),
            is(true));
    }
}
