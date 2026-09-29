package org.mockserver.lifecycle;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.log.model.DeferredLogArgument;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.RequestDefinition;
import org.mockserver.netty.MockServer;
import org.slf4j.event.Level;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpTemplate.template;

/**
 * Over real proxy, forward and echo exchanges, a retained event-log entry must hold only what the byte
 * budget counts: no rendered curl String in its arguments, and no decoded body String re-attached by the
 * response write that follows the log (the response is logged before it is written).
 */
public class EventLogRetainedCopiesIntegrationTest {

    private static final int EXCHANGES = 25;
    private static MockServer backend;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServers() {
        backend = new MockServer(configuration().logLevel(Level.WARN));
        new MockServerClient("localhost", backend.getLocalPort())
            .when(request().withPath("/backend.*"))
            .respond(template(HttpTemplate.TemplateType.MUSTACHE,
                "{\"statusCode\": 200, \"headers\": {\"content-type\": [\"text/plain\"]}, \"body\": \"{{ request.body }}\"}"));
        mockServer = new MockServer(configuration().logLevel(Level.WARN));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient
            .when(request().withPath("/echo"))
            .respond(template(HttpTemplate.TemplateType.MUSTACHE,
                "{\"statusCode\": 200, \"headers\": {\"content-type\": [\"text/plain\"]}, \"body\": \"{{ request.body }}\"}"));
        mockServerClient
            .when(request().withPath("/backend-forward"))
            .forward(forward().withHost("localhost").withPort(backend.getLocalPort()));
    }

    @AfterClass
    public static void stopServers() {
        if (mockServer != null) {
            mockServer.stop();
        }
        if (backend != null) {
            backend.stop();
        }
    }

    @Test
    public void shouldRetainNoUncountedCopiesAfterProxyForwardAndEchoExchanges() throws Exception {
        HttpClient direct = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
        HttpClient viaProxy = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.of(new InetSocketAddress("localhost", mockServer.getLocalPort()))).build();
        for (int i = 0; i < EXCHANGES; i++) {
            String body = ("exchange-" + i + "_").repeat(200);
            assertThat(post(viaProxy, "http://localhost:" + backend.getLocalPort() + "/backend-proxy?n=" + i, body), is(body));
            assertThat(post(direct, "http://localhost:" + mockServer.getLocalPort() + "/backend-forward?n=" + i, body), is(body));
            assertThat(post(direct, "http://localhost:" + mockServer.getLocalPort() + "/echo?n=" + i, body), is(body));
        }

        // queued behind every entry already published, so all of them have been retained and released
        CompletableFuture<List<LogEntry>> retained = new CompletableFuture<>();
        mockServer.httpState.getMockServerLog().retrieveRequestResponseMessageLogEntries(null, retained::complete);
        List<LogEntry> entries = retained.get(30, TimeUnit.SECONDS);

        int forwarded = 0;
        int withResponse = 0;
        for (LogEntry entry : entries) {
            for (RequestDefinition requestDefinition : entry.getHttpRequests()) {
                if (requestDefinition instanceof HttpRequest) {
                    assertThat(entry.getType() + " request body", derivedBytes(((HttpRequest) requestDefinition).getBody()), is(0L));
                }
            }
            HttpResponse response = entry.getHttpResponse();
            if (response != null && response.getBody() != null) {
                withResponse++;
                assertThat(entry.getType() + " response body", derivedBytes(response.getBody()), is(0L));
            }
            if (entry.getType() == FORWARDED_REQUEST) {
                forwarded++;
                Object[] arguments = rawArguments(entry);
                assertThat(arguments[2], instanceOf(DeferredLogArgument.class));
                // rendered afresh on every read, so no rendered String is held behind the argument either
                DeferredLogArgument curl = (DeferredLogArgument) arguments[2];
                String rendered = curl.render(null);
                assertThat(rendered, containsString("curl -v"));
                assertThat(curl.render(null), is(rendered));
                assertThat(curl.render(null), not(sameInstance(rendered)));
                for (Object argument : arguments) {
                    // an expectation id may be a String argument; a copy of the body must not be
                    if (argument instanceof String) {
                        assertThat((String) argument, not(containsString("exchange-")));
                    }
                }
            }
        }
        assertThat(forwarded, is(2 * EXCHANGES));
        assertThat(withResponse, greaterThanOrEqualTo(3 * EXCHANGES));
    }

    private static String post(HttpClient client, String uri, String body) throws Exception {
        java.net.http.HttpResponse<String> response = client.send(
            java.net.http.HttpRequest.newBuilder(URI.create(uri))
                .header("content-type", "text/plain")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(uri, response.statusCode(), is(200));
        return response.body();
    }

    private static long derivedBytes(Body<?> body) {
        return body == null ? 0L : body.retainedDerivedFormBytes();
    }

    private static Object[] rawArguments(LogEntry entry) throws ReflectiveOperationException {
        Field field = LogEntry.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return (Object[]) field.get(entry);
    }
}
