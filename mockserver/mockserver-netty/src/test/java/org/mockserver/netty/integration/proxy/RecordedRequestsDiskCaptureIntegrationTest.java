package org.mockserver.netty.integration.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Disk capture ({@code persistRecordedRequestsToDisk}) end to end over real sockets: exchanges proxied
 * through MockServer are written one per line, and a {@code ?source=disk} import issued as soon as the
 * exchanges are recorded reads every one of them back, although lines are flushed in batches.
 */
public class RecordedRequestsDiskCaptureIntegrationTest {

    private static final int EXCHANGES = 40;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldCaptureProxiedExchangesToDiskAndImportThemStraightBack() throws Exception {
        File archive = new File(temporaryFolder.getRoot(), "recorded.ndjson");
        Configuration proxyConfiguration = configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(archive.getAbsolutePath());
        ClientAndServer upstream = ClientAndServer.startClientAndServer();
        ClientAndServer proxy = ClientAndServer.startClientAndServer(proxyConfiguration);
        try {
            // a scalar JSON body is serialised verbatim, so its newline exercises the one-line collapse
            upstream.when(request().withPath("/api/scalar/.*"))
                .respond(response().withBody(json("\n  42\n", MediaType.APPLICATION_JSON)));
            upstream.when(request().withPath("/api/object/.*"))
                .respond(response().withBody(json("{\"name\": \"cafe\",\n \"spaces\": \"  kept  \"}", MediaType.APPLICATION_JSON)));
            HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("localhost", proxy.getPort())))
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            // when — exchanges are proxied through the capturing server
            for (int i = 0; i < EXCHANGES; i++) {
                String path = (i % 2 == 0 ? "/api/scalar/" : "/api/object/") + i;
                HttpResponse<String> proxied = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + upstream.getPort() + path))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"request\": " + i + "}"))
                    .header("Content-Type", "application/json")
                    .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(proxied.statusCode(), is(200));
            }
            // the retrieve completes on the event-log consumer after every exchange before it is processed
            assertThat(proxy.retrieveRecordedRequests(request().withPath("/api/.*")).length, is(EXCHANGES));

            // then — a disk import straight away returns every exchange
            HttpResponse<String> imported = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + proxy.getPort() + "/mockserver/import?format=recording&source=disk&redactSensitiveData=false"))
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(imported.statusCode(), is(201));
            assertThat(imported.headers().firstValue("x-mockserver-recorded-requests-skipped").isPresent(), is(false));
            HttpRequestAndHttpResponse[] importedPairs = new HttpRequestAndHttpResponseSerializer(new MockServerLogger()).deserializeArray(imported.body());
            assertThat(importedPairs.length, is(EXCHANGES));
            for (int i = 0; i < EXCHANGES; i++) {
                String expectedPath = (i % 2 == 0 ? "/api/scalar/" : "/api/object/") + i;
                assertThat(importedPairs[i].getHttpRequest().getPath().getValue(), is(expectedPath));
            }
            assertThat(importedPairs[0].getHttpResponse().getBodyAsString().trim(), is("42"));
            JsonNode objectBody = new ObjectMapper().readTree(importedPairs[1].getHttpResponse().getBodyAsString());
            assertThat(objectBody.get("spaces").asText(), is("  kept  "));
            assertThat(objectBody.get("name").asText(), is("cafe"));

            // and — the archive holds exactly one line per exchange
            List<String> lines = Files.readAllLines(archive.toPath(), StandardCharsets.UTF_8);
            assertThat(lines.size(), is(EXCHANGES));
        } finally {
            stopQuietly(proxy);
            stopQuietly(upstream);
        }
    }
}
