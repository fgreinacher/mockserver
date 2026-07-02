package org.mockserver.cluster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.RequestDefinition;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Wire-level tests for {@link HttpClusterPeerAccessor} using a tiny in-JVM HTTP server as the
 * "peer". Proves the infinite-recursion guard is applied on the wire ({@code fanInLocalOnly=true})
 * and that fail-closed semantics hold (non-2xx throws).
 */
public class HttpClusterPeerAccessorTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastUri = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "[]";
    /** When set, the "peer" rejects any query whose Authorization header does not match (simulates an authed cluster). */
    private volatile String requiredAuthorization = null;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mockserver/retrieve", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastUri.set(exchange.getRequestURI().toString());
        lastMethod.set(exchange.getRequestMethod());
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getRequestBody().readAllBytes();
        // Simulate an authenticated cluster: reject queries lacking the required control-plane credential.
        if (requiredAuthorization != null && !requiredAuthorization.equals(lastAuthorization.get())) {
            byte[] denied = "unauthorized".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, denied.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(denied);
            }
            return;
        }
        byte[] payload = bodyToReturn.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusToReturn, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    private HttpClusterPeerAccessor accessor() {
        return new HttpClusterPeerAccessor(configuration(), new MockServerLogger());
    }

    private HttpClusterPeerAccessor accessorWithToken(String token) {
        return new HttpClusterPeerAccessor(configuration().clusterFanInPeerAuthToken(token), new MockServerLogger());
    }

    @Test
    public void sendsLocalOnlyMarkerToPreventRecursion() throws Exception {
        accessor().retrieveRequests(baseUrl, request("/api"));

        assertThat(lastMethod.get(), is("PUT"));
        assertThat("peer query MUST carry the local-only recursion guard", lastUri.get(), containsString("fanInLocalOnly=true"));
        assertThat(lastUri.get(), containsString("type=REQUESTS"));
        assertThat(lastUri.get(), containsString("format=JSON"));
    }

    @Test
    public void emptyArrayResponseYieldsEmptyListNotAnError() throws Exception {
        bodyToReturn = "[]";
        List<RequestDefinition> result = accessor().retrieveRequests(baseUrl, request("/api"));
        assertThat(result.isEmpty(), is(true));
    }

    @Test
    public void requestResponsesUsesRequestResponsesType() throws Exception {
        bodyToReturn = "[]";
        List<LogEventRequestAndResponse> result = accessor().retrieveRequestResponses(baseUrl, request("/api"));
        assertThat(lastUri.get(), containsString("type=REQUEST_RESPONSES"));
        assertThat(result.isEmpty(), is(true));
    }

    @Test
    public void nonSuccessStatusThrowsSoCallerFailsClosed() {
        statusToReturn = 500;
        bodyToReturn = "error";
        try {
            accessor().retrieveRequests(baseUrl, request("/api"));
            fail("expected an exception on a non-2xx peer response (fail-closed)");
        } catch (Exception expected) {
            assertThat(expected.getMessage(), containsString("500"));
        }
    }

    @Test
    public void noAuthTokenSendsNoAuthorizationHeaderPreservingLegacyBehaviour() throws Exception {
        accessor().retrieveRequests(baseUrl, request("/api"));
        assertThat("default accessor must not present a credential", lastAuthorization.get(), is((String) null));
    }

    @Test
    public void authTokenIsPresentedVerbatimAsControlPlaneAuthorizationHeader() throws Exception {
        accessorWithToken("Bearer eyJraff").retrieveRequests(baseUrl, request("/api"));
        assertThat("peer query MUST carry the configured control-plane credential verbatim",
            lastAuthorization.get(), is("Bearer eyJraff"));
    }

    @Test
    public void authenticatedPeerAcceptsQueryWhenTokenPresented() throws Exception {
        // the "peer" requires a bearer credential (an authed cluster)
        requiredAuthorization = "Bearer shared-cluster-jwt";
        bodyToReturn = "[]";
        // accessor presenting the matching token succeeds (no fail-closed)
        List<RequestDefinition> result = accessorWithToken("Bearer shared-cluster-jwt").retrieveRequests(baseUrl, request("/api"));
        assertThat(result.isEmpty(), is(true));
        assertThat(lastAuthorization.get(), is("Bearer shared-cluster-jwt"));
    }

    @Test
    public void authenticatedPeerRejectsUnauthenticatedQueryFailingClosed() {
        // the "peer" requires a bearer credential, but the accessor sends none (no token configured)
        requiredAuthorization = "Bearer shared-cluster-jwt";
        try {
            accessor().retrieveRequests(baseUrl, request("/api"));
            fail("expected a fail-closed exception when the authed peer returns 401");
        } catch (Exception expected) {
            assertThat("401 from an authed peer must propagate (fail-closed)", expected.getMessage(), containsString("401"));
        }
    }

    @Test
    public void unreachablePeerThrows() {
        // a port with nothing listening — connection failure must propagate (fail-closed)
        try {
            accessor().retrieveRequests("http://127.0.0.1:1", request("/api"));
            fail("expected an exception when the peer is unreachable");
        } catch (Exception expected) {
            // pass
        }
    }
}
