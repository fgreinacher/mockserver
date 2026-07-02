package org.mockserver.imports;

import org.junit.Test;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;

import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class RecordedTrafficImporterTest {

    private final MockServerLogger logger = new MockServerLogger();
    private final HttpRequestAndHttpResponseSerializer serializer = new HttpRequestAndHttpResponseSerializer(logger);

    private String ndjsonLine(HttpRequestAndHttpResponse pair) {
        // mirror the persistence side: one compact (newline-free) JSON object per line
        return serializer.serialize(pair).replaceAll("\\s*\\n\\s*", " ").trim();
    }

    @Test
    public void shouldParseMultipleNdjsonPairsForwardedAndMocked() {
        // given — an archive with both a forwarded and a mocked exchange (the importer does not care
        // which disposition produced them; both are re-imported as recorded traffic)
        String forwarded = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/forwarded").withMethod("GET"))
            .withHttpResponse(response().withStatusCode(200).withBody("forwarded-body")));
        String mocked = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/mocked").withMethod("POST").withBody("mocked-request"))
            .withHttpResponse(response().withStatusCode(201).withBody("mocked-body")));
        String ndjson = forwarded + "\n" + mocked + "\n";

        // when — redaction disabled so values round-trip verbatim
        List<HttpRequestAndHttpResponse> pairs =
            new RecordedTrafficImporter(logger).importRecordedTraffic(ndjson, ImportRedaction.Options.disabled());

        // then
        assertThat(pairs.size(), is(2));
        assertThat(pairs.get(0).getHttpRequest().getPath().getValue(), is("/api/forwarded"));
        assertThat(pairs.get(0).getHttpResponse().getBodyAsString(), is("forwarded-body"));
        assertThat(pairs.get(1).getHttpRequest().getPath().getValue(), is("/api/mocked"));
        assertThat(pairs.get(1).getHttpRequest().getBodyAsString(), is("mocked-request"));
        assertThat(pairs.get(1).getHttpResponse().getBodyAsString(), is("mocked-body"));
    }

    @Test
    public void shouldSkipBlankLines() {
        String line = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/only").withMethod("GET"))
            .withHttpResponse(response().withStatusCode(200)));
        String ndjson = "\n\n   \n" + line + "\n\n";

        List<HttpRequestAndHttpResponse> pairs =
            new RecordedTrafficImporter(logger).importRecordedTraffic(ndjson, ImportRedaction.Options.disabled());

        assertThat(pairs.size(), is(1));
        assertThat(pairs.get(0).getHttpRequest().getPath().getValue(), is("/only"));
    }

    @Test
    public void shouldRedactSensitiveDataByDefault() {
        // given — a persisted line whose request carries a sensitive Authorization header
        String line = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/secured")
                .withMethod("GET")
                .withHeader("Authorization", "Bearer SECRET123")
                .withHeader("Accept", "application/json"))
            .withHttpResponse(response().withStatusCode(200).withBody("ok")));

        // when — default (redaction enabled)
        List<HttpRequestAndHttpResponse> pairs =
            new RecordedTrafficImporter(logger).importRecordedTraffic(line);

        // then — the secret is masked, the non-sensitive header is untouched
        assertThat(pairs.size(), is(1));
        String authValue = pairs.get(0).getHttpRequest().getFirstHeader("Authorization");
        assertThat(authValue, is(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat(pairs.get(0).getHttpRequest().getFirstHeader("Accept"), is("application/json"));
    }

    @Test
    public void shouldThrowForBlankArchive() {
        RecordedTrafficImporter importer = new RecordedTrafficImporter(logger);
        assertThrows(IllegalArgumentException.class, () -> importer.importRecordedTraffic("   "));
        assertThrows(IllegalArgumentException.class, () -> importer.importRecordedTraffic(null));
    }

    @Test
    public void shouldThrowWithLineNumberForInvalidJson() {
        String valid = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/ok").withMethod("GET"))
            .withHttpResponse(response().withStatusCode(200)));
        String ndjson = valid + "\n" + "{not valid json}";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new RecordedTrafficImporter(logger).importRecordedTraffic(ndjson, ImportRedaction.Options.disabled()));
        assertThat(ex.getMessage(), containsString("line 2"));
    }

    @Test
    public void shouldRoundTripFromPersistenceSerializer() {
        // given — a line produced exactly as RecordedRequestsFileSystemPersistence would produce it
        HttpRequestAndHttpResponse original = new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/round/trip").withMethod("PUT").withBody("body\nwith\nnewlines"))
            .withHttpResponse(response().withStatusCode(202).withBody("resp"));
        String line = ndjsonLine(original);

        // when
        List<HttpRequestAndHttpResponse> pairs =
            new RecordedTrafficImporter(logger).importRecordedTraffic(line, ImportRedaction.Options.disabled());

        // then — a single record with the embedded newlines preserved
        assertThat(pairs.size(), is(1));
        assertThat(pairs.get(0), notNullValue());
        assertThat(pairs.get(0).getHttpRequest().getBodyAsString(), is("body\nwith\nnewlines"));
        assertThat(pairs.get(0).getHttpRequest().getBodyAsString(), not(containsString("\\n")));
    }
}
