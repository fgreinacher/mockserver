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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class RecordedRequestsFileSystemPersistenceTest {

    private boolean originalRedactConfigured;
    private boolean originalRedactValue;

    @Before
    public void setUp() {
        // capture and reset the global mockserver.redactSecretsInLog state so each test starts
        // from the default (off) and we can restore it cleanly without leaking into other tests
        originalRedactConfigured = System.getProperty("mockserver.redactSecretsInLog") != null;
        originalRedactValue = ConfigurationProperties.redactSecretsInLog();
        ConfigurationProperties.redactSecretsInLog(false);
    }

    @After
    public void tearDown() {
        if (originalRedactConfigured) {
            ConfigurationProperties.redactSecretsInLog(originalRedactValue);
        } else {
            System.clearProperty("mockserver.redactSecretsInLog");
        }
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
}
