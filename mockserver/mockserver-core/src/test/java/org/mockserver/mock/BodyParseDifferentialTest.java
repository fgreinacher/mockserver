package org.mockserver.mock;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.MatchType;
import org.mockserver.matchers.MatcherBuilder;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.JsonBody;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.AllOfBody.allOf;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonPathBody.jsonPath;
import static org.mockserver.model.JsonSchemaBody.jsonSchema;
import static org.mockserver.model.Not.not;
import static org.mockserver.model.XPathBody.xpath;
import static org.mockserver.model.XmlBody.xml;

/**
 * Differential corpus for body parsing during matching: every JSON / JSONPath / JSON-schema / XPath
 * expectation in the corpus is matched against every probe body — valid and invalid JSON, arrays,
 * scalars, blank and absent bodies, XML with and without namespaces, a DTD, form data and non-ASCII
 * text — through the full candidate scan ({@link RequestMatchers#firstMatchingExpectation}), the
 * closest-match rescan, and one-matcher-at-a-time evaluation. The transcript (match verdicts, every
 * recorded difference and every rendered log entry) is compared, section by section, with digests
 * captured from the code BEFORE body parsing was shared across candidates, so any change in a verdict
 * or a message fails. Exception messages from the JDK and libraries are reduced to the exception class
 * first, so the digests do not depend on the JDK or library version.
 *
 * <p>The corpus is ordered by priority so a JSONPath {@code append()} — the one Jayway function that
 * writes to the document it reads — runs before a JSONPath that would see the appended element if the
 * parsed document were shared between candidates.
 *
 * <p>Regenerate (only when a transcript change is intended) with
 * {@code -Dmockserver.bodyParseDifferential.regenerate=true}.
 */
public class BodyParseDifferentialTest {

    private static final String GOLDEN = "org/mockserver/mock/BodyParseDifferentialTest.sha256.txt";
    private static final String TRANSCRIPT = "BodyParseDifferentialTest.transcript.txt";

    static final class CapturingLogger extends MockServerLogger {
        final List<String> rendered = new ArrayList<>();

        CapturingLogger(Configuration configuration) {
            super(configuration, LoggerFactory.getLogger(CapturingLogger.class));
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                rendered.add(logEntry.getLogLevel() + " " + logEntry.getType() + " " + logEntry.getMessage());
            }
        }
    }

    private static final String JSON_OBJECT = "{\"id\":1,\"name\":\"a\",\"items\":[{\"sku\":\"A\",\"qty\":1},{\"sku\":\"B\",\"qty\":2}]}";
    private static final String XML_PLAIN = "<order><id>1</id><item sku=\"A\"/><item sku=\"B\"/></order>";
    private static final String XML_NAMESPACED = "<ns:order xmlns:ns=\"urn:test\"><ns:id>1</ns:id><ns:item sku=\"A\"/></ns:order>";

    static List<Expectation> corpusExpectations() {
        List<Body<?>> bodies = Arrays.asList(
            // JSONPath append() writes to the document it reads; the conjunction never matches, so the
            // scan moves on to the next candidate after the append has run
            allOf(not(jsonPath("$.items.append(\"APPENDED\")")), jsonPath("$.neverPresent")),
            jsonPath("$.items[?(@ == 'APPENDED')]"),
            allOf(jsonPath("$.items.append(99).length()"), jsonPath("$.neverPresent")),
            jsonPath("$.items[?(@ == 99)]"),
            new JsonBody("{\"id\":1,\"name\":\"a\"}", MatchType.STRICT),
            new JsonBody("{\"id\":1}"),
            new JsonBody("{\"items\":[{\"sku\":\"B\"}]}"),
            new JsonBody("[1,2]"),
            new JsonBody("{\"name\":\"café ☃ 😀\"}"),
            jsonPath("$.items[?(@.sku == 'ZZZ')]"),
            jsonPath("$.items.length()"),
            jsonPath("$[0]"),
            jsonPath("$.name"),
            jsonPath("$["),
            jsonSchema("{\"type\":\"object\",\"required\":[\"id\"]}"),
            jsonSchema("{\"type\":\"array\"}"),
            allOf(jsonPath("$.id"), new JsonBody("{\"id\":1}"), jsonSchema("{\"type\":\"object\"}")),
            xpath("/order/id"),
            xpath("/order/item[@sku='B']"),
            xpath("count(/order/item) = 2"),
            xpath("/ns:order/ns:id", ImmutableMap.of("ns", "urn:test")),
            xpath("/order/id", Collections.emptyMap()),
            xpath("/order["),
            xml("<order><id>1</id><item sku=\"A\"/><item sku=\"B\"/></order>"),
            jsonPath("$.id"),
            // negated matchers match most probes, so they come last to let the scan reach the rest
            not(new JsonBody("{\"id\":2}")),
            not(jsonPath("$.missing")),
            not(xpath("/order/missing"))
        );
        List<Expectation> expectations = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            // descending priority fixes the scan order to the list order
            expectations.add(new Expectation(request().withBody(bodies.get(i)), Times.unlimited(), TimeToLive.unlimited(), 1000 - i)
                .withId("e" + i)
                .thenRespond(response().withBody("e" + i)));
        }
        return expectations;
    }

    static List<HttpRequest> corpusProbes() {
        return Arrays.asList(
            probe("json-object").withBody(new JsonBody(JSON_OBJECT)),
            probe("json-object-string").withHeader("Content-Type", "application/json").withBody("{\"id\":2,\"items\":[]}"),
            probe("json-array").withBody("[1,2,3]"),
            probe("json-array-of-items").withBody("{\"items\":[\"x\"]}"),
            probe("json-invalid").withBody("{\"id\": 1,"),
            probe("json-scalar").withBody("42"),
            probe("json-non-ascii").withBody("{\"name\":\"café ☃ 😀\"}"),
            probe("text").withBody("hello world"),
            probe("empty").withBody(""),
            probe("whitespace").withBody("   "),
            probe("absent"),
            probe("xml").withHeader("Content-Type", "application/xml").withBody(XML_PLAIN),
            probe("xml-no-content-type").withBody(XML_PLAIN),
            probe("xml-namespaced").withHeader("Content-Type", "application/xml").withBody(XML_NAMESPACED),
            probe("xml-invalid").withHeader("Content-Type", "text/xml").withBody("<order><id>1</order>"),
            probe("xml-doctype").withHeader("Content-Type", "application/xml").withBody("<?xml version=\"1.0\"?><!DOCTYPE order [<!ENTITY x \"y\">]><order>&x;</order>"),
            probe("form").withHeader("Content-Type", "application/x-www-form-urlencoded").withBody("id=1&name=a")
        );
    }

    private static HttpRequest probe(String name) {
        return request().withMethod("POST").withPath("/" + name);
    }

    static String transcript() {
        StringBuilder out = new StringBuilder();
        for (String logLevel : new String[]{"INFO", "WARN"}) {
            for (boolean detailed : new boolean[]{false, true}) {
                Configuration configuration = configuration().logLevel(logLevel).detailedMatchFailures(detailed);
                CapturingLogger logger = new CapturingLogger(configuration);
                RequestMatchers requestMatchers = new RequestMatchers(configuration, logger, mock(Scheduler.class), mock(WebSocketClientRegistry.class));
                List<Expectation> expectations = corpusExpectations();
                for (Expectation expectation : expectations) {
                    requestMatchers.add(expectation, API);
                }
                MatcherBuilder matcherBuilder = new MatcherBuilder(configuration, logger);
                for (HttpRequest probe : corpusProbes()) {
                    String label = "### " + logLevel + " detailed=" + detailed + " " + probe.getPath().getValue();
                    logger.rendered.clear();
                    Expectation matched = requestMatchers.firstMatchingExpectation(probe.clone());
                    out.append(label).append(" scan matched=").append(matched != null ? matched.getId() : null).append('\n');
                    appendLines(out, logger.rendered);
                    logger.rendered.clear();
                    RequestMatchers.ClosestMatchHint hint = requestMatchers.findClosestMatchHint(probe.clone());
                    out.append(label).append(" hint=").append(hint != null ? hint.getExpectationId() + " " + hint.getDifferences() : null).append('\n');
                    appendLines(out, logger.rendered);
                    for (Expectation expectation : expectations) {
                        logger.rendered.clear();
                        HttpRequestMatcher matcher = matcherBuilder.transformsToMatcher(expectation);
                        HttpRequest request = probe.clone();
                        MatchDifference difference = new MatchDifference(true, request);
                        boolean result = matcher.matches(difference, request);
                        out.append(label).append(' ').append(expectation.getId()).append(" single=").append(result)
                            .append(' ').append(difference.getAllDifferences()).append('\n');
                        appendLines(out, logger.rendered);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void appendLines(StringBuilder out, List<String> lines) {
        for (String line : lines) {
            out.append("  | ").append(line.replace("\n", "\n  | ")).append('\n');
        }
    }

    /**
     * One line per (log level, detailedMatchFailures, probe) section: its label and the SHA-256 of the
     * section's full text. The golden file stays small while still pinning every byte; on a mismatch the
     * full transcript is written to {@code target/} to diff against one regenerated from the previous commit.
     */
    static List<String> sectionDigests(String transcript) throws NoSuchAlgorithmException {
        List<String> digests = new ArrayList<>();
        String label = null;
        StringBuilder section = new StringBuilder();
        for (String line : normaliseExceptionMessages(transcript).split("\n", -1)) {
            if (line.startsWith("### ") && line.contains(" scan matched=")) {
                if (label != null) {
                    digests.add(label + " " + sha256(section.toString()));
                }
                label = line.substring(0, line.indexOf(" scan matched="));
                section.setLength(0);
            }
            section.append(line).append('\n');
        }
        if (label != null) {
            digests.add(label + " " + sha256(section.toString()));
        }
        return digests;
    }

    /**
     * Reduces "fully.qualified.SomeException: message" to the exception class, and a bare JDK "helpful"
     * NullPointerException message to {@code null}: that text belongs to the JDK or a library, not to
     * MockServer, and varies between versions (JDK 25 gives no helpful message where JDK 17 and 21 do).
     */
    static String normaliseExceptionMessages(String transcript) {
        String withoutMessages = EXCEPTION_MESSAGE.matcher(transcript).replaceAll("$1");
        return HELPFUL_NULL_POINTER_MESSAGE.matcher(withoutMessages).replaceAll("null");
    }

    private static final Pattern EXCEPTION_MESSAGE = Pattern.compile("((?:[a-z][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_$]*(?:Exception|Error)): [^\\n]*");
    private static final Pattern HELPFUL_NULL_POINTER_MESSAGE = Pattern.compile("Cannot [^\\n]*? because [^\\n]*? is null");

    private static String sha256(String text) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void writeTranscript(String transcript) throws IOException {
        Files.createDirectories(Paths.get("target"));
        Files.write(Paths.get("target", TRANSCRIPT), transcript.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void matchVerdictsDifferencesAndLogEntriesAreUnchanged() throws IOException, NoSuchAlgorithmException {
        String actual = transcript();
        List<String> actualDigests = sectionDigests(actual);
        if (Boolean.getBoolean("mockserver.bodyParseDifferential.regenerate")) {
            Path golden = Paths.get("src/test/resources", GOLDEN);
            Files.createDirectories(golden.getParent());
            Files.write(golden, (String.join("\n", actualDigests) + "\n").getBytes(StandardCharsets.UTF_8));
            writeTranscript(actual);
            return;
        }
        List<String> expectedDigests;
        try (InputStream in = BodyParseDifferentialTest.class.getClassLoader().getResourceAsStream(GOLDEN)) {
            assertThat("golden digests " + GOLDEN, in, notNullValue());
            expectedDigests = Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim().split("\n"));
        }
        if (!actualDigests.equals(expectedDigests)) {
            writeTranscript(actual);
        }
        assertThat("section count", actualDigests.size(), is(expectedDigests.size()));
        for (int i = 0; i < expectedDigests.size(); i++) {
            assertThat("section " + (i + 1) + " (full transcript in target/" + TRANSCRIPT + ")", actualDigests.get(i), is(expectedDigests.get(i)));
        }
    }
}
