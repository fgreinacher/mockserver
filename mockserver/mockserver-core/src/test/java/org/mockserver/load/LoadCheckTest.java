package org.mockserver.load;

import org.junit.Test;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpResponse.response;

/**
 * Unit tests for {@link LoadCheck} extraction (STATUS / HEADER / BODY_JSONPATH) and the comparator
 * semantics (string + numeric), independent of the orchestrator.
 */
public class LoadCheckTest {

    @Test
    public void statusEqualsPassesAndFails() {
        HttpResponse ok = response().withStatusCode(200);
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.STATUS)
            .withComparator(LoadCheck.Comparator.EQUALS).withValue("200").evaluate(ok), is(true));
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.STATUS)
            .withComparator(LoadCheck.Comparator.EQUALS).withValue("201").evaluate(ok), is(false));
    }

    @Test
    public void headerContainsAndMatches() {
        HttpResponse res = response().withStatusCode(200).withHeader("Content-Type", "application/json; charset=utf-8");
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.HEADER).withHeaderName("Content-Type")
            .withComparator(LoadCheck.Comparator.CONTAINS).withValue("application/json").evaluate(res), is(true));
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.HEADER).withHeaderName("Content-Type")
            .withComparator(LoadCheck.Comparator.MATCHES).withValue("application/json.*").evaluate(res), is(true));
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.HEADER).withHeaderName("Content-Type")
            .withComparator(LoadCheck.Comparator.MATCHES).withValue("text/plain").evaluate(res), is(false));
    }

    @Test
    public void bodyJsonPathEqualsAndNumericComparators() {
        HttpResponse res = response().withStatusCode(200).withBody("{\"status\":\"ok\",\"count\":42}");
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.BODY_JSONPATH).withJsonPath("$.status")
            .withComparator(LoadCheck.Comparator.EQUALS).withValue("ok").evaluate(res), is(true));
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.BODY_JSONPATH).withJsonPath("$.count")
            .withComparator(LoadCheck.Comparator.GTE).withValue("42").evaluate(res), is(true));
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.BODY_JSONPATH).withJsonPath("$.count")
            .withComparator(LoadCheck.Comparator.LT).withValue("42").evaluate(res), is(false));
    }

    @Test
    public void numericComparatorOnNonNumberFails() {
        HttpResponse res = response().withStatusCode(200).withBody("{\"status\":\"ok\"}");
        assertThat("a GT comparison against a non-numeric observed value fails",
            LoadCheck.loadCheck().withSource(LoadCheck.Source.BODY_JSONPATH).withJsonPath("$.status")
                .withComparator(LoadCheck.Comparator.GT).withValue("10").evaluate(res), is(false));
    }

    @Test
    public void notEqualsSemanticsWithMissingValue() {
        HttpResponse res = response().withStatusCode(200);
        // JSONPath with no match extracts null; NOT_EQUALS to a non-null expected is true (they differ).
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.HEADER).withHeaderName("X-Missing")
            .withComparator(LoadCheck.Comparator.NOT_EQUALS).withValue("x").evaluate(res), is(true));
        // EQUALS on a missing value is false (nothing equals the expected).
        assertThat(LoadCheck.loadCheck().withSource(LoadCheck.Source.HEADER).withHeaderName("X-Missing")
            .withComparator(LoadCheck.Comparator.EQUALS).withValue("x").evaluate(res), is(false));
    }

    @Test
    public void nullComparatorPasses() {
        assertThat("a check with no comparator asserts nothing and passes",
            LoadCheck.loadCheck().withSource(LoadCheck.Source.STATUS).evaluate(response().withStatusCode(500)),
            is(true));
    }
}
