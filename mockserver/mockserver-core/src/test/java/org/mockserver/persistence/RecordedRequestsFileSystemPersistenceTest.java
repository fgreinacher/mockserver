package org.mockserver.persistence;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.imports.RecordedTrafficImporter;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpRequestAndHttpResponseDTO;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.XmlBody.xml;

public class RecordedRequestsFileSystemPersistenceTest {

    private boolean originalRedactValue;

    @Before
    public void setUp() {
        // capture and reset the global mockserver.redactSecretsInLog state so each test starts
        // from the default (off) and we can restore it cleanly without leaking into other tests
        originalRedactValue = ConfigurationProperties.redactSecretsInLog();
        ConfigurationProperties.redactSecretsInLog(false);
    }

    @After
    public void tearDown() {
        // restore through the setter: System.clearProperty leaves ConfigurationProperties' cached value in
        // place, which kept redaction on for every later test in the same JVM
        ConfigurationProperties.redactSecretsInLog(originalRedactValue);
    }

    @Test
    public void shouldAppendOneCompactJsonLinePerExchange() throws Exception {
        // given
        File persistedFile = File.createTempFile("persistedRecordedRequests", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(persistedFile.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsFileSystemPersistenceTest.class);
        HttpRequestAndHttpResponseSerializer serializer = new HttpRequestAndHttpResponseSerializer(logger);

        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);
        try {
            // when
            persistence.append(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/first").withMethod("GET"))
                .setHttpResponse(response().withStatusCode(200).withBody("first response")));
            persistence.append(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/second").withMethod("POST").withBody("second request"))
                .setHttpResponse(response().withStatusCode(201).withBody("second response")));
        } finally {
            persistence.stop();
        }

        // then — exactly one line per exchange, each a single (newline-free) compact JSON object
        List<String> lines = Files.readAllLines(persistedFile.toPath(), StandardCharsets.UTF_8);
        assertThat(lines.size(), is(2));

        HttpRequestAndHttpResponse first = serializer.deserialize(lines.get(0));
        assertThat(first, notNullValue());
        assertThat(first.getHttpRequest().getPath().getValue(), is("/api/first"));
        assertThat(first.getHttpRequest().getMethod().getValue(), is("GET"));
        assertThat(first.getHttpResponse().getBodyAsString(), is("first response"));

        HttpRequestAndHttpResponse second = serializer.deserialize(lines.get(1));
        assertThat(second, notNullValue());
        assertThat(second.getHttpRequest().getPath().getValue(), is("/api/second"));
        assertThat(second.getHttpRequest().getBodyAsString(), is("second request"));
        assertThat(second.getHttpResponse().getBodyAsString(), is("second response"));
    }

    @Test
    public void shouldBeInertWhenDisabled() {
        // given — disabled persistence must not throw and must write nothing
        Configuration configuration = configuration()
            .persistRecordedRequestsToDisk(false)
            .persistedRecordedRequestsPath("target/should-not-be-created.ndjson");
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsFileSystemPersistenceTest.class);

        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);

        // when / then — no-ops, no exception
        persistence.append(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setHttpRequest(request("/ignored"))
            .setHttpResponse(response().withBody("ignored")));
        persistence.stop();

        assertThat(new File("target/should-not-be-created.ndjson").exists(), is(false));
    }

    @Test
    public void shouldMaskSensitiveDataInPersistedArchiveWhenRedactionOn() throws Exception {
        // given — redaction is enabled, so the persistent NDJSON archive must honour it exactly
        // like the in-memory retrieval path (it must NOT write secrets in cleartext to disk)
        ConfigurationProperties.redactSecretsInLog(true);
        File persistedFile = File.createTempFile("persistedRecordedRequestsRedacted", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(persistedFile.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsFileSystemPersistenceTest.class);

        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);
        try {
            // when — append an exchange whose request carries a sensitive Authorization header
            persistence.append(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/secured")
                    .withMethod("GET")
                    .withHeader("Authorization", "Bearer SECRET123")
                    .withHeader("Accept", "application/json"))
                .setHttpResponse(response().withStatusCode(200).withBody("ok")));
        } finally {
            persistence.stop();
        }

        // then — the secret value is masked on disk, the placeholder is present, and the
        // non-sensitive header is untouched
        String fileContents = new String(Files.readAllBytes(persistedFile.toPath()), StandardCharsets.UTF_8);
        assertThat(fileContents, not(containsString("SECRET123")));
        assertThat(fileContents, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat(fileContents, containsString("application/json"));
    }

    @Test
    public void shouldWriteSingleLineForBodyContainingEmbeddedNewline() throws Exception {
        // given
        File persistedFile = File.createTempFile("persistedRecordedRequestsNewline", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(persistedFile.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsFileSystemPersistenceTest.class);
        HttpRequestAndHttpResponseSerializer serializer = new HttpRequestAndHttpResponseSerializer(logger);

        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);
        try {
            // when — body carries a REAL embedded newline (e.g. SSE / multi-line JSON body)
            persistence.append(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/stream").withMethod("POST"))
                .setHttpResponse(response().withStatusCode(200).withBody("line1\nline2")));
        } finally {
            persistence.stop();
        }

        // then — exactly ONE NDJSON record despite the embedded newline (the escaped \n inside the
        // JSON string value is not a record separator), and it round-trips with the newline preserved
        String fileContents = new String(Files.readAllBytes(persistedFile.toPath()), StandardCharsets.UTF_8);
        List<String> records = List.of(fileContents.replaceAll("\\n$", "").split("\n", -1));
        assertThat(records.size(), is(1));

        HttpRequestAndHttpResponse roundTripped = serializer.deserialize(records.get(0));
        assertThat(roundTripped, notNullValue());
        assertThat(roundTripped.getHttpResponse().getBodyAsString(), is("line1\nline2"));
    }

    @Test
    public void shouldWriteLinesParsingToTheSameJsonAsThePrettyPrintedCollapsedForm() throws Exception {
        // given — a corpus adversarial in what the line format touches: formatting whitespace, raw
        // scalar JSON bodies, non-ASCII, whitespace inside strings, and lines either side of the
        // write buffer (so some cross a buffer boundary and some bypass it)
        File persistedFile = File.createTempFile("persistedRecordedRequestsEquivalence", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = capturingTo(persistedFile);
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsFileSystemPersistenceTest.class);
        HttpRequestAndHttpResponseSerializer prettySerializer = new HttpRequestAndHttpResponseSerializer(logger);
        List<HttpRequestAndHttpResponse> corpus = equivalenceCorpus();

        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);
        try {
            // when
            for (HttpRequestAndHttpResponse exchange : corpus) {
                persistence.append(new LogEntry()
                    .setType(FORWARDED_REQUEST)
                    .setHttpRequest(exchange.getHttpRequest())
                    .setHttpResponse(exchange.getHttpResponse()));
            }
        } finally {
            persistence.stop();
        }

        // then — one line per exchange, each parsing to exactly the JSON the previous pretty-print +
        // regex-collapse produced (only the formatting whitespace differs)
        String contents = new String(Files.readAllBytes(persistedFile.toPath()), StandardCharsets.UTF_8);
        List<String> lines = Arrays.asList(contents.split("\n", -1));
        assertThat(lines.get(lines.size() - 1), is(""));
        assertThat(lines.size() - 1, is(corpus.size()));
        ObjectMapper objectMapper = new ObjectMapper();
        for (int i = 0; i < corpus.size(); i++) {
            String previousFormat = prettySerializer.serialize(corpus.get(i)).replaceAll("\\s*\\n\\s*", " ").trim();
            assertThat("line " + i, objectMapper.readTree(lines.get(i)), is(objectMapper.readTree(previousFormat)));
        }
        RecordedTrafficImporter.Result imported = new RecordedTrafficImporter(logger).importRecordedTraffic(contents);
        assertThat(imported.getSkippedLineCount(), is(0));
        assertThat(imported.getPairs().size(), is(corpus.size()));
        // and — byte level: a non-BMP character stays raw UTF-8 (4 bytes, greppable) as the previous
        // String-based path wrote it, not a pair of escaped surrogates
        assertThat(lines.get(2), containsString("\ud83d\ude00"));
        assertThat(lines.get(2).toLowerCase(), not(containsString("\\ud83d")));
    }

    @Test
    public void shouldKeepOneRecordPerLineWhenScalarJsonBodyTextContainsRealLineBreaks() throws Exception {
        // given — a scalar JSON body is written verbatim (writeRawValue), so its text's line breaks (a
        // newline, and a lone carriage return that some readers also split on) reach the compact output;
        // prove the fixture really exercises that path
        HttpRequestAndHttpResponse exchange = new HttpRequestAndHttpResponse()
            .withHttpRequest(request("/api/scalar").withMethod("POST").withBody(json("\n  42\n", MediaType.APPLICATION_JSON)))
            .withHttpResponse(response().withStatusCode(200).withBody(json("\"done\"  \r\t ", MediaType.APPLICATION_JSON)));
        String compactOutput = ObjectMapperFactory.createObjectMapper(false, false).writeValueAsString(new HttpRequestAndHttpResponseDTO(exchange));
        assertThat(compactOutput, containsString("\n"));
        assertThat(compactOutput, containsString("\r"));
        File persistedFile = File.createTempFile("persistedRecordedRequestsScalarNewline", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = capturingTo(persistedFile);
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsFileSystemPersistenceTest.class);

        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);
        try {
            // when
            persistence.append(new LogEntry().setType(FORWARDED_REQUEST).setHttpRequest(exchange.getHttpRequest()).setHttpResponse(exchange.getHttpResponse()));
            persistence.append(new LogEntry().setType(FORWARDED_REQUEST).setHttpRequest(request("/api/next")).setHttpResponse(response().withBody("next")));
        } finally {
            persistence.stop();
        }

        // then — still exactly one record per exchange, and the scalar values survive
        String contents = new String(Files.readAllBytes(persistedFile.toPath()), StandardCharsets.UTF_8);
        assertThat(contents, not(containsString("\r")));
        String[] lines = contents.split("\n");
        assertThat(lines.length, is(2));
        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(lines[0]).at("/httpRequest/body/json").asInt(), is(42));
        assertThat(objectMapper.readTree(lines[0]).at("/httpResponse/body/json").asText(), is("done"));
        assertThat(objectMapper.readTree(lines[1]).at("/httpRequest/path").asText(), is("/api/next"));
    }

    @Test
    public void shouldWriteNothingForAnExchangeWhoseSerialisationFailsPartWay() throws Exception {
        // given — the response body is not valid JSON, so serialisation throws AFTER the request half
        // has already been serialised
        File persistedFile = File.createTempFile("persistedRecordedRequestsPartialFailure", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = capturingTo(persistedFile);
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsFileSystemPersistenceTest.class);

        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);
        try {
            // when
            persistence.append(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/failing").withBody("request-half-" + "x".repeat(20_000)))
                .setHttpResponse(response().withBody(json("{\"unterminated\": ", MediaType.APPLICATION_JSON))));
            persistence.append(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/after-failure"))
                .setHttpResponse(response().withBody("ok")));
        } finally {
            persistence.stop();
        }

        // then — no fragment of the failed exchange, and the next exchange is a clean line of its own
        String contents = new String(Files.readAllBytes(persistedFile.toPath()), StandardCharsets.UTF_8);
        assertThat(contents, not(containsString("request-half-")));
        List<String> lines = Files.readAllLines(persistedFile.toPath(), StandardCharsets.UTF_8);
        assertThat(lines.size(), is(1));
        assertThat(new ObjectMapper().readTree(lines.get(0)).at("/httpRequest/path").asText(), is("/api/after-failure"));
    }

    @Test
    public void shouldMakeAppendedLinesReadableOnFlushBeforeStop() throws Exception {
        // given
        File persistedFile = File.createTempFile("persistedRecordedRequestsFlush", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = capturingTo(persistedFile);
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsFileSystemPersistenceTest.class);
        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);
        try {
            // when
            persistence.append(new LogEntry().setType(FORWARDED_REQUEST).setHttpRequest(request("/api/flushed")).setHttpResponse(response().withBody("flushed")));
            persistence.flush();

            // then — readable while the persistence is still open
            List<String> lines = Files.readAllLines(persistedFile.toPath(), StandardCharsets.UTF_8);
            assertThat(lines.size(), is(1));
            assertThat(lines.get(0), containsString("/api/flushed"));
        } finally {
            persistence.stop();
        }
    }

    @Test
    public void shouldAppendInLinearTimeForLongWhitespaceRuns() throws Exception {
        // given — long whitespace runs: one inside a JSON string (no newline, the case a per-line
        // "\\s*\\n\\s*" regex scans quadratically) and one ending in a newline in a raw scalar body
        String spaces = " ".repeat(200_000);
        File persistedFile = File.createTempFile("persistedRecordedRequestsWhitespace", ".ndjson");
        persistedFile.deleteOnExit();
        Configuration configuration = capturingTo(persistedFile);
        MockServerLogger logger = new MockServerLogger(configuration, RecordedRequestsFileSystemPersistenceTest.class);
        RecordedRequestsFileSystemPersistence persistence = new RecordedRequestsFileSystemPersistence(configuration, logger);
        long elapsedMillis;
        try {
            // when
            long start = System.nanoTime();
            persistence.append(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/whitespace").withBody("a" + spaces + "b"))
                .setHttpResponse(response().withBody(json("\"c\"" + spaces + "\n", MediaType.APPLICATION_JSON))));
            elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        } finally {
            persistence.stop();
        }

        // then — bounded time, one line, and the in-string run is preserved exactly
        assertThat(elapsedMillis, lessThan(2_000L));
        List<String> lines = Files.readAllLines(persistedFile.toPath(), StandardCharsets.UTF_8);
        assertThat(lines.size(), is(1));
        HttpRequestAndHttpResponse persisted = new HttpRequestAndHttpResponseSerializer(logger).deserialize(lines.get(0));
        assertThat(persisted.getHttpRequest().getBodyAsString(), is("a" + spaces + "b"));
    }

    private static Configuration capturingTo(File persistedFile) {
        return configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(persistedFile.getAbsolutePath());
    }

    private static List<HttpRequestAndHttpResponse> equivalenceCorpus() {
        List<HttpRequestAndHttpResponse> corpus = new ArrayList<>();
        corpus.add(exchange(request("/"), null));
        corpus.add(exchange(request("/api/get").withMethod("GET").withHeader("Accept", "application/json", "text/plain"), response().withStatusCode(204)));
        corpus.add(exchange(
            request("/api/unicode").withMethod("POST")
                .withQueryStringParameter("q", "café crème", "second")
                .withCookie("session", "abc 123")
                .withBody("Café \u2615 \ud83d\ude00 \u2028 tab\there  spaced   out\r\nline two"),
            response().withStatusCode(200).withReasonPhrase("OK").withHeader("Set-Cookie", "a=b").withBody("üñîçødé")));
        corpus.add(exchange(
            request("/api/json").withMethod("PUT").withBody(json("{\n  \"a\" : [ 1, 2.50, { \"b\" : \"  keep  spaces \" } ],\n  \"c\" : null\n}", MediaType.APPLICATION_JSON)),
            response().withBody(json("[ true,\n false ]", MediaType.APPLICATION_JSON))));
        corpus.add(exchange(
            request("/api/scalar").withBody(json("\n  42\n", MediaType.APPLICATION_JSON)),
            response().withBody(json("\"text with\\nescaped newline\"  \n", MediaType.APPLICATION_JSON))));
        corpus.add(exchange(
            request("/api/xml").withBody(xml("<a>\n  <b>  text  </b>\n</a>")),
            response().withBody(binary(new byte[]{0, 1, 2, (byte) 0xff, '\n', ' ', '\n'}))));
        for (int size = 7_900; size <= 8_500; size += 150) {
            corpus.add(exchange(request("/api/boundary/" + size).withBody("x".repeat(size)), response().withBody("y")));
        }
        StringBuilder largeJson = new StringBuilder("{\"items\":[");
        for (int i = 0; largeJson.length() < 65_536; i++) {
            largeJson.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"item é ").append(i).append("\"}");
        }
        corpus.add(exchange(request("/api/large").withBody(json(largeJson.append("]}").toString(), MediaType.APPLICATION_JSON)), response().withBody("z".repeat(70_000))));
        return corpus;
    }

    private static HttpRequestAndHttpResponse exchange(HttpRequest request, HttpResponse response) {
        return new HttpRequestAndHttpResponse().withHttpRequest(request).withHttpResponse(response);
    }
}
