package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.model.StringBody;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

public class RetiredRequestDefinitionsTest {

    private static RequestDefinition withBody(String path, int bodyBytes) {
        return request(path).withBody(new String(new char[bodyBytes]).replace('\0', 'x'));
    }

    @Test
    public void evictsTheLongestRetiredDefinitionsOnceTheByteBudgetIsExceeded() {
        long perDefinition = Expectation.estimatedRequestDefinitionHeapSize(withBody("/0", 10_000));
        RetiredRequestDefinitions retired = new RetiredRequestDefinitions(1000, perDefinition * 3);

        for (int i = 0; i < 10; i++) {
            retired.retire("id" + i, withBody("/" + i, 10_000));
        }

        assertThat(retired.size(), is(3));
        assertThat(retired.retainedBytes(), is(lessThanOrEqualTo(perDefinition * 3)));
        assertThat(retired.get("id6"), is(nullValue()));
        assertThat(retired.get("id7"), is(notNullValue()));
        assertThat(retired.get("id9"), is(notNullValue()));
    }

    @Test
    public void evictsTheLongestRetiredDefinitionsOnceTheCountBoundIsExceeded() {
        RetiredRequestDefinitions retired = new RetiredRequestDefinitions(2, Long.MAX_VALUE);

        retired.retire("a", request("/a"));
        retired.retire("b", request("/b"));
        retired.retire("c", request("/c"));

        assertThat(retired.get("a"), is(nullValue()));
        assertThat(retired.get("b"), is(notNullValue()));
        assertThat(retired.get("c"), is(notNullValue()));
    }

    @Test
    public void keepsTheNewestDefinitionEvenWhenItAloneExceedsTheByteBudget() {
        RetiredRequestDefinitions retired = new RetiredRequestDefinitions(1000, 10);

        retired.retire("small", request("/small"));
        retired.retire("large", withBody("/large", 100_000));

        assertThat(retired.get("small"), is(nullValue()));
        assertThat(retired.get("large"), is(notNullValue()));
        assertThat(retired.size(), is(1));
    }

    @Test
    public void forgetAndClearReleaseTheirBytes() {
        RetiredRequestDefinitions retired = new RetiredRequestDefinitions(1000, Long.MAX_VALUE);
        retired.retire("a", withBody("/a", 1_000));
        retired.retire("b", withBody("/b", 1_000));
        long both = retired.retainedBytes();

        retired.forget("a");
        assertThat(retired.retainedBytes(), is(both / 2));
        assertThat(retired.get("a"), is(nullValue()));

        retired.clear();
        assertThat(retired.retainedBytes(), is(0L));
        assertThat(retired.size(), is(0));
    }

    @Test
    public void reRetiringAnIdReplacesItsEntryWithoutDoubleCounting() {
        RetiredRequestDefinitions retired = new RetiredRequestDefinitions(1000, Long.MAX_VALUE);
        retired.retire("a", withBody("/a", 1_000));
        long once = retired.retainedBytes();

        retired.retire("a", withBody("/a2", 1_000));

        assertThat(retired.retainedBytes(), is(once));
        assertThat(retired.size(), is(1));
    }

    @Test
    public void shrinkingTheCountBoundEvictsImmediately() {
        RetiredRequestDefinitions retired = new RetiredRequestDefinitions(10, Long.MAX_VALUE);
        retired.retire("a", request("/a"));
        retired.retire("b", request("/b"));

        retired.setMaxEntries(1);

        assertThat(retired.size(), is(1));
        assertThat(retired.get("b"), is(notNullValue()));
    }

    @Test
    public void defaultByteBudgetIsAFractionOfTheHeapWithAFallbackWhenTheHeapIsUndefined() {
        assertThat(RetiredRequestDefinitions.defaultMaxBytes(1_024_000L), is(1_024_000L * 1024 / RetiredRequestDefinitions.HEAP_FRACTION_DIVISOR));
        assertThat(RetiredRequestDefinitions.defaultMaxBytes(0L), is(RetiredRequestDefinitions.UNDEFINED_HEAP_FALLBACK_BYTES));
    }

    @Test
    public void weighsTheBodysCachedDecodedStringAlongsideItsRawBytes() {
        HttpRequest request = request("/p").withBody(new StringBody(new String(new char[10_000]).replace('\0', 'x')));
        StringBody body = (StringBody) request.getBody();
        long withDecodedString = Expectation.estimatedRequestDefinitionHeapSize(request);
        assertThat(body.retainedDerivedFormBytes(), is(20_000L));

        body.releaseDerivedForms();

        assertThat(Expectation.estimatedRequestDefinitionHeapSize(request), is(withDecodedString - 20_000L));
    }
}
