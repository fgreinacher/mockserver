package org.mockserver.model;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.mockserver.matchers.MatchType.ONLY_MATCHING_FIELDS;

/**
 * {@link Body#getValueWithoutCaching()} and {@link Body#toStringWithoutCaching()} must return exactly what
 * {@link Body#getValue()} / {@link Body#toString()} return, and must never re-attach a released decoded
 * String: a read after {@link Body#releaseDerivedForms()} leaves {@link Body#retainedDerivedFormBytes()} at 0.
 */
public class BodyReadWithoutCachingTest {

    private static final String TEXT = "şarəs été {\"k\":\"v\"} <a>b</a>";

    private static List<Body<String>> releasableBodies() {
        byte[] utf8 = TEXT.getBytes(StandardCharsets.UTF_8);
        return Arrays.asList(
            new StringBody(new String(utf8, StandardCharsets.UTF_8), utf8, false, StringBody.DEFAULT_CONTENT_TYPE.withCharset(StandardCharsets.UTF_8)),
            new JsonBody(new String(utf8, StandardCharsets.UTF_8), utf8, JsonBody.DEFAULT_JSON_CONTENT_TYPE, ONLY_MATCHING_FIELDS),
            new XmlBody(new String(utf8, StandardCharsets.UTF_8), utf8, MediaType.APPLICATION_XML_UTF_8)
        );
    }

    @Test
    public void shouldReadReleasedBodyWithoutReCachingIt() {
        for (Body<String> body : releasableBodies()) {
            String cached = body.getValue();
            body.releaseDerivedForms();
            assertThat(body.getClass().getSimpleName(), body.retainedDerivedFormBytes(), is(0L));

            assertThat(body.getClass().getSimpleName(), body.getValueWithoutCaching(), is(cached));
            assertThat(body.getClass().getSimpleName(), body.toStringWithoutCaching(), is(cached));
            assertThat(body.getClass().getSimpleName(), body.retainedDerivedFormBytes(), is(0L));

            // the caching read still caches, which is what makes the non-caching read necessary
            assertThat(body.getClass().getSimpleName(), body.getValue(), is(cached));
            assertThat(body.getClass().getSimpleName(), body.retainedDerivedFormBytes(), is(greaterThan(0L)));
        }
    }

    @Test
    public void shouldReturnTheCachedInstanceWhenOneIsPresent() {
        for (Body<String> body : releasableBodies()) {
            String cached = body.getValue();
            assertThat(body.getClass().getSimpleName(), body.getValueWithoutCaching(), sameInstance(cached));
            assertThat(body.getClass().getSimpleName(), body.toStringWithoutCaching(), sameInstance(cached));
        }
    }

    @Test
    public void shouldMatchToStringForBodiesWithoutADerivedForm() {
        List<Body<?>> bodies = Arrays.asList(
            new BinaryBody(new byte[]{1, 2, 3}),
            new ParameterBody(new Parameter("a", "b")),
            new RegexBody("a.*b"),
            new StringBody("")
        );
        for (Body<?> body : bodies) {
            assertThat(body.getClass().getSimpleName(), body.toStringWithoutCaching(), is(body.toString()));
            assertThat(body.getClass().getSimpleName(), body.getValueWithoutCaching(), is(body.getValue()));
        }
    }
}
