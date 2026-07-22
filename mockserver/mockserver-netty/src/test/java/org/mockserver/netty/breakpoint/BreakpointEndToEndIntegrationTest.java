package org.mockserver.netty.breakpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.breakpoint.BreakpointCallbackDispatcher;
import org.mockserver.mock.breakpoint.BreakpointMatcherRegistry;
import org.mockserver.mock.breakpoint.BreakpointPhase;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end test that drives the <strong>real</strong> interactive-breakpoint round trip over a
 * live HTTP/1.1 transport: a running {@link MockServer}, the real breakpoint callback WebSocket
 * client established by {@link MockServerClient#addBreakpoint}, and a real JDK {@link HttpClient}
 * as the originating caller.
 * <p>
 * Every other breakpoint test in the tree asserts below the wire — {@code BreakpointWebSocketClientTest}
 * and {@code BreakpointMatcherClientTest} mock {@code NettyHttpClient}, and the registry / handler /
 * HTTP/3 tests drive {@code EmbeddedChannel} or the handlers directly. None of them prove that a live
 * request actually <em>pauses</em> server-side, is dispatched to the connected client over the callback
 * WebSocket, and that the client's decision changes what the originating caller receives. That full
 * loop is what this test exercises, asserting only on the response the JDK client reads back from the
 * running server — so a green result can only mean the pause/resume/modify happened server-side.
 * <p>
 * Two documented decisions are covered:
 * <ul>
 *   <li><b>MODIFY</b> at the RESPONSE phase — the client rewrites the matched mock response body and the
 *       caller receives the modified body (not the original).</li>
 *   <li><b>ABORT</b> at the REQUEST phase — the client returns an {@link HttpResponse} before the mock
 *       response is generated, and the caller receives that abort response instead of the mock.</li>
 * </ul>
 * <p>
 * The breakpoint pause is a real blocking hold on the server event loop's continuation (completed only
 * when the WebSocket client replies), not a fixed sleep; the generous {@code breakpointTimeoutMillis}
 * exists purely so a slow CI machine does not spuriously auto-continue before the client's reply lands.
 */
public class BreakpointEndToEndIntegrationTest {

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private HttpClient httpClient;

    @Before
    public void setUp() {
        // reset the process-wide breakpoint singletons so no matcher / in-flight hold leaks in
        BreakpointMatcherRegistry.getInstance().clear();
        BreakpointCallbackDispatcher.getInstance().reset();

        // generous timeout: the pause must be resolved by the client reply, not by auto-continue
        mockServer = new MockServer(Configuration.configuration().breakpointTimeoutMillis(30_000L));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @After
    public void tearDown() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        BreakpointMatcherRegistry.getInstance().clear();
        BreakpointCallbackDispatcher.getInstance().reset();
    }

    @Test(timeout = 60_000)
    public void shouldModifyMatchedMockResponseAtResponseBreakpointOverLiveTransport() throws Exception {
        // given - a matched mock response returning the ORIGINAL body
        mockServerClient
            .when(request().withMethod("GET").withPath("/breakpoint-modify"))
            .respond(response().withStatusCode(200).withBody("original-body"));

        // and - a RESPONSE-phase breakpoint whose client handler rewrites the body server-side
        final CountDownLatch paused = new CountDownLatch(1);
        mockServerClient.addBreakpoint(
            request().withMethod("GET").withPath("/breakpoint-modify"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            null,
            (httpRequest, httpResponse) -> {
                paused.countDown();
                return response()
                    .withStatusCode(299)
                    .withBody("modified-by-breakpoint-client");
            },
            null
        );

        // when - a REAL http client sends a matching request; the response is held until the
        // breakpoint client replies (proving a genuine server-side pause, not a sleep)
        java.net.http.HttpResponse<String> callerResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + mockServer.getLocalPort() + "/breakpoint-modify"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()
        );

        // then - the breakpoint fired and the caller received the MODIFIED response, not the original
        assertThat("breakpoint handler must have been invoked server-side",
            paused.await(5, TimeUnit.SECONDS), is(true));
        assertThat(callerResponse.statusCode(), is(299));
        assertThat(callerResponse.body(), is("modified-by-breakpoint-client"));
    }

    @Test(timeout = 60_000)
    public void shouldAbortMatchedMockResponseAtRequestBreakpointOverLiveTransport() throws Exception {
        // given - a matched mock response that must NOT be seen because the request is aborted first
        mockServerClient
            .when(request().withMethod("GET").withPath("/breakpoint-abort"))
            .respond(response().withStatusCode(200).withBody("should-not-be-returned"));

        // and - a REQUEST-phase breakpoint whose client handler ABORTS by returning a response
        final AtomicReference<String> abortedPath = new AtomicReference<>();
        mockServerClient.addBreakpoint(
            request().withMethod("GET").withPath("/breakpoint-abort"),
            EnumSet.of(BreakpointPhase.REQUEST),
            httpRequest -> {
                abortedPath.set(httpRequest.getPath().getValue());
                return response()
                    .withStatusCode(418)
                    .withBody("aborted-by-breakpoint-client");
            },
            null,
            null
        );

        // when
        java.net.http.HttpResponse<String> callerResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + mockServer.getLocalPort() + "/breakpoint-abort"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()
        );

        // then - the caller received the ABORT response, and the mock response was never generated
        assertThat("request breakpoint handler must have been invoked server-side",
            abortedPath.get(), is("/breakpoint-abort"));
        assertThat(callerResponse.statusCode(), is(418));
        assertThat(callerResponse.body(), is("aborted-by-breakpoint-client"));
    }
}
