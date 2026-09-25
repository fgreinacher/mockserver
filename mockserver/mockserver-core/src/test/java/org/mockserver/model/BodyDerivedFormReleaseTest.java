package org.mockserver.model;

import org.junit.Test;
import org.mockserver.serialization.Base64Converter;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.matchers.MatchType.ONLY_MATCHING_FIELDS;

/**
 * The raw bytes are the canonical body; the decoded String view is a cache that
 * {@link Body#releaseDerivedForms()} drops once a body-bearing log entry is retained, then re-derives
 * identically on the next read. These tests cover that release/re-derive contract directly on the model,
 * including the fidelity guard that a body whose canonical value differs from its raw bytes (a re-imported
 * recording, see #2374) is NEVER released, so no information is ever lost.
 */
public class BodyDerivedFormReleaseTest {

    private static final Base64Converter BASE_64 = new Base64Converter();

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- JsonBody ----

    @Test
    public void shouldReleaseAndReDeriveJsonBodyWhenValueMatchesRawBytes() {
        String json = "{ \"name\" : \"value\" }";
        byte[] rawBytes = json.getBytes(StandardCharsets.UTF_8);
        // the live-traffic shape: value == decode(rawBytes), built exactly as BodyDecoderEncoder does
        JsonBody body = new JsonBody(new String(rawBytes, StandardCharsets.UTF_8), rawBytes, JsonBody.DEFAULT_JSON_CONTENT_TYPE, ONLY_MATCHING_FIELDS);

        // warm the caches the way request-matching would
        assertThat(body.getValue(), is(json));
        body.get("name");
        assertThat(field(body, "json"), is(notNullValue()));
        assertThat(field(body, "jsonNode"), is(notNullValue()));
        assertThat(body.retainedDerivedFormBytes(), is(greaterThan(0L)));

        body.releaseDerivedForms();

        // both caches dropped, but no bytes lost
        assertThat(field(body, "json"), is(nullValue()));
        assertThat(field(body, "jsonNode"), is(nullValue()));
        assertThat(body.retainedDerivedFormBytes(), is(0L));
        assertThat(body.getRawBytes(), is(rawBytes));

        // rendering still shows the readable String, re-derived identically - never the base64 form
        assertThat(body.getValue(), is(json));
        assertThat(body.toString(), is(json));
        assertThat(body.toString(), is(not(BASE_64.bytesToBase64String(rawBytes))));
        assertThat(body.get("name").asText(), is("value"));
    }

    @Test
    public void shouldNotReleaseJsonBodyWhenCanonicalValueDiffersFromRawBytes() {
        // #2374 shape: canonical value (no original spacing) differs from the raw wire bytes
        String canonical = "{\"name\":\"value\"}";
        byte[] rawBytes = "{ \"name\" : \"value\" }".getBytes(StandardCharsets.UTF_8);
        JsonBody body = new JsonBody(canonical, rawBytes, JsonBody.DEFAULT_JSON_CONTENT_TYPE, ONLY_MATCHING_FIELDS);
        assertThat(body.getValue(), is(canonical));

        body.releaseDerivedForms();

        // the String is NOT re-derivable from the raw bytes, so it must be kept - never lost
        assertThat(field(body, "json"), is(notNullValue()));
        assertThat(body.getValue(), is(canonical));
        assertThat(body.retainedDerivedFormBytes(), is(greaterThan(0L)));
    }

    @Test
    public void releasedJsonBodyStaysEqualAndKeepsHashCode() {
        String json = "{ \"a\" : 1 }";
        byte[] rawBytes = json.getBytes(StandardCharsets.UTF_8);
        JsonBody retained = new JsonBody(new String(rawBytes, StandardCharsets.UTF_8), rawBytes, JsonBody.DEFAULT_JSON_CONTENT_TYPE, ONLY_MATCHING_FIELDS);
        JsonBody live = new JsonBody(new String(rawBytes, StandardCharsets.UTF_8), rawBytes, JsonBody.DEFAULT_JSON_CONTENT_TYPE, ONLY_MATCHING_FIELDS);
        int hashBeforeRelease = retained.hashCode();

        retained.releaseDerivedForms();

        assertThat(retained.hashCode(), is(hashBeforeRelease));
        assertThat(retained.hashCode(), is(live.hashCode()));
        assertThat(retained, is(live));
        assertThat(live, is(retained));
    }

    // ---- StringBody ----

    @Test
    public void shouldReleaseAndReDeriveStringBody() {
        String value = "some plain text body";
        byte[] rawBytes = value.getBytes(StandardCharsets.UTF_8);
        StringBody body = new StringBody(new String(rawBytes, StandardCharsets.UTF_8), rawBytes, false, StringBody.DEFAULT_CONTENT_TYPE.withCharset(StandardCharsets.UTF_8));
        assertThat(body.getValue(), is(value));
        assertThat(field(body, "value"), is(notNullValue()));

        body.releaseDerivedForms();

        assertThat(field(body, "value"), is(nullValue()));
        assertThat(body.retainedDerivedFormBytes(), is(0L));
        assertThat(body.getValue(), is(value));
        assertThat(body.toString(), is(value));
        assertThat(body.toString(), is(not(BASE_64.bytesToBase64String(rawBytes))));
    }

    // ---- XmlBody ----

    @Test
    public void shouldReleaseAndReDeriveXmlBody() {
        String xml = "<a><b>value</b></a>";
        byte[] rawBytes = xml.getBytes(StandardCharsets.UTF_8);
        XmlBody body = new XmlBody(new String(rawBytes, StandardCharsets.UTF_8), rawBytes, XmlBody.DEFAULT_XML_CONTENT_TYPE);
        assertThat(body.getValue(), is(xml));
        assertThat(field(body, "xml"), is(notNullValue()));

        body.releaseDerivedForms();

        assertThat(field(body, "xml"), is(nullValue()));
        assertThat(body.retainedDerivedFormBytes(), is(0L));
        assertThat(body.getValue(), is(xml));
        assertThat(body.toString(), is(xml));
        assertThat(body.toString(), is(not(BASE_64.bytesToBase64String(rawBytes))));
    }
}
