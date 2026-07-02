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
        RecordedTrafficImporter.Result result =
            new RecordedTrafficImporter(logger).importRecordedTraffic(ndjson, ImportRedaction.Options.disabled());

        // then
        List<HttpRequestAndHttpResponse> pairs = result.getPairs();
        assertThat(pairs.size(), is(2));
        assertThat(result.getSkippedLineCount(), is(0));
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

        RecordedTrafficImporter.Result result =
            new RecordedTrafficImporter(logger).importRecordedTraffic(ndjson, ImportRedaction.Options.disabled());

        // blank lines are not counted as skipped — only unparseable non-blank lines are
        assertThat(result.getPairs().size(), is(1));
        assertThat(result.getSkippedLineCount(), is(0));
        assertThat(result.getPairs().get(0).getHttpRequest().getPath().getValue(), is("/only"));
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
        RecordedTrafficImporter.Result result =
            new RecordedTrafficImporter(logger).importRecordedTraffic(line);

        // then — the secret is masked, the non-sensitive header is untouched
        List<HttpRequestAndHttpResponse> pairs = result.getPairs();
        assertThat(pairs.size(), is(1));
        String authValue = pairs.get(0).getHttpRequest().getFirstHeader("Authorization");
        assertThat(authValue, is(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat(pairs.get(0).getHttpRequest().getFirstHeader("Accept"), is("application/json"));
    }

    @Test
    public void shouldReturnEmptyForBlankArchive() {
        // blank / whitespace-only input is not an error — an enabled-but-never-written archive
        // legitimately holds zero exchanges
        RecordedTrafficImporter importer = new RecordedTrafficImporter(logger);
        assertThat(importer.importRecordedTraffic("   ").getPairs().size(), is(0));
        assertThat(importer.importRecordedTraffic("   ").getSkippedLineCount(), is(0));
        assertThat(importer.importRecordedTraffic((String) null).getPairs().size(), is(0));
    }

    @Test
    public void shouldSkipTruncatedTrailingLineAndImportTheRest() {
        // given — the real crash artefact: N intact lines then a final line truncated mid-JSON
        // (the write path flushes line + "\n" per exchange, so a hard kill leaves a half-written last line)
        String first = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/first").withMethod("GET"))
            .withHttpResponse(response().withStatusCode(200).withBody("first")));
        String second = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/second").withMethod("GET"))
            .withHttpResponse(response().withStatusCode(200).withBody("second")));
        String truncated = second.substring(0, second.length() / 2); // half a JSON object, no trailing newline
        String ndjson = first + "\n" + second + "\n" + truncated;

        // when
        RecordedTrafficImporter.Result result =
            new RecordedTrafficImporter(logger).importRecordedTraffic(ndjson, ImportRedaction.Options.disabled());

        // then — both intact exchanges import, the truncated line is skipped and counted (not fatal)
        assertThat(result.getPairs().size(), is(2));
        assertThat(result.getSkippedLineCount(), is(1));
        assertThat(result.getPairs().get(0).getHttpRequest().getPath().getValue(), is("/api/first"));
        assertThat(result.getPairs().get(1).getHttpRequest().getPath().getValue(), is("/api/second"));
    }

    @Test
    public void shouldSkipMalformedLineInTheMiddleAndImportTheRest() {
        String first = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/good1").withMethod("GET"))
            .withHttpResponse(response().withStatusCode(200)));
        String second = ndjsonLine(new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/good2").withMethod("GET"))
            .withHttpResponse(response().withStatusCode(200)));
        String ndjson = first + "\n" + "{not valid json}" + "\n" + second;

        RecordedTrafficImporter.Result result =
            new RecordedTrafficImporter(logger).importRecordedTraffic(ndjson, ImportRedaction.Options.disabled());

        assertThat(result.getPairs().size(), is(2));
        assertThat(result.getSkippedLineCount(), is(1));
    }

    @Test
    public void shouldThrowWhenNoLineParses() {
        // a body that is not a recorded-traffic archive at all (e.g. garbage or a HAR sent with
        // ?format=recording): every non-blank line fails, so fail loudly rather than import nothing silently
        RecordedTrafficImporter importer = new RecordedTrafficImporter(logger);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> importer.importRecordedTraffic("{not valid}\n[also not valid]", ImportRedaction.Options.disabled()));
        assertThat(ex.getMessage(), containsString("not a recorded-traffic NDJSON archive"));
    }

    @Test
    public void shouldRoundTripFromPersistenceSerializer() {
        // given — a line produced exactly as RecordedRequestsFileSystemPersistence would produce it
        HttpRequestAndHttpResponse original = new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/round/trip").withMethod("PUT").withBody("body\nwith\nnewlines"))
            .withHttpResponse(response().withStatusCode(202).withBody("resp"));
        String line = ndjsonLine(original);

        // when
        RecordedTrafficImporter.Result result =
            new RecordedTrafficImporter(logger).importRecordedTraffic(line, ImportRedaction.Options.disabled());

        // then — a single record with the embedded newlines preserved
        List<HttpRequestAndHttpResponse> pairs = result.getPairs();
        assertThat(pairs.size(), is(1));
        assertThat(pairs.get(0), notNullValue());
        assertThat(pairs.get(0).getHttpRequest().getBodyAsString(), is("body\nwith\nnewlines"));
        assertThat(pairs.get(0).getHttpRequest().getBodyAsString(), not(containsString("\\n")));
    }
}
