package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end tests for the {@code GET/PUT/DELETE /mockserver/cassettes} control-plane endpoints,
 * driving a real MockServer over a real socket.
 * <p>
 * The endpoints expose the process-wide {@link org.mockserver.mock.CassetteRegistry} — lightweight
 * metadata about recorded/loaded cassette fixture files — so the dashboard can surface them across
 * page reloads and browsers. These tests pin the documented contract:
 * <ul>
 *     <li>PUT registers a cassette and echoes it back as {@code 201 Created},</li>
 *     <li>GET lists cassettes most-recently-used first with the documented body shape,</li>
 *     <li>re-registering (touching) a cassette moves it to the front of the MRU order,</li>
 *     <li>DELETE removes a cassette so a subsequent GET no longer lists it,</li>
 *     <li>server reset empties the registry,</li>
 *     <li>and every verb is refused when control-plane authentication is required.</li>
 * </ul>
 */
public class CassetteRegistryEndpointIntegrationTest {

    private static MockServer mockServer;
    private static NettyHttpClient httpClient;
    private static EventLoopGroup clientEventLoopGroup;
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    @BeforeClass
    public static void startServerAndClient() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(CassetteRegistryEndpointIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);
        mockServer = new MockServer();
    }

    @AfterClass
    public static void stopServerAndClient() {
        // belt-and-braces: never leak the global control-plane auth toggle to sibling suites
        ConfigurationProperties.controlPlaneJWTAuthenticationRequired(false);
        stopQuietly(mockServer);
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Before
    public void resetBefore() throws Exception {
        reset();
    }

    @After
    public void resetAfter() throws Exception {
        // ensure auth is off (a failed auth test could otherwise leave it on) before resetting
        ConfigurationProperties.controlPlaneJWTAuthenticationRequired(false);
        reset();
    }

    private void reset() throws Exception {
        send("PUT", "/mockserver/reset", null);
    }

    private HttpResponse send(String method, String path, String body) throws Exception {
        org.mockserver.model.HttpRequest httpRequest = request()
            .withMethod(method)
            .withHeader(HOST.toString(), "localhost:" + mockServer.getLocalPort())
            .withPath(path);
        if (body != null) {
            httpRequest = httpRequest.withBody(body);
        }
        return httpClient.sendRequest(httpRequest).get(15, TimeUnit.SECONDS);
    }

    private static JsonNode json(HttpResponse response) throws Exception {
        return OBJECT_MAPPER.readTree(response.getBodyAsString());
    }

    /** Distinct wall-clock milliseconds between two registrations so MRU ordering is deterministic. */
    private static void tick() throws InterruptedException {
        Thread.sleep(10);
    }

    // ------------------------------------------------------------------
    // PUT registration contract
    // ------------------------------------------------------------------

    @Test
    public void shouldRegisterCassetteAndEchoStoredEntry() throws Exception {
        // when
        HttpResponse response = send("PUT", "/mockserver/cassettes",
            "{\"path\":\"/tmp/fixtures/checkout.json\",\"filename\":\"checkout.json\",\"expectationCount\":3,\"origin\":\"recorded\"}");

        // then - a registration is reported as 201 Created with the stored entry echoed back
        assertThat(response.getStatusCode(), is(201));
        assertThat(response.getFirstHeader("content-type"), containsString("application/json"));
        JsonNode body = json(response);
        assertThat(body.get("path").asText(), is("/tmp/fixtures/checkout.json"));
        assertThat(body.get("filename").asText(), is("checkout.json"));
        assertThat(body.get("expectationCount").asInt(), is(3));
        assertThat(body.get("origin").asText(), is("recorded"));
        assertThat("a registered cassette must carry a lastUsed timestamp", body.get("lastUsed").asLong() > 0L, is(true));
    }

    @Test
    public void shouldDeriveFilenameFromPathAndDefaultOriginWhenOmitted() throws Exception {
        // when - only the required path is supplied
        HttpResponse response = send("PUT", "/mockserver/cassettes", "{\"path\":\"/data/recordings/login.json\"}");

        // then - filename is derived from the path and origin defaults to "loaded"
        assertThat(response.getStatusCode(), is(201));
        JsonNode body = json(response);
        assertThat(body.get("filename").asText(), is("login.json"));
        assertThat(body.get("origin").asText(), is("loaded"));
    }

    @Test
    public void shouldRejectPutWithoutPath() throws Exception {
        // when
        HttpResponse response = send("PUT", "/mockserver/cassettes", "{\"filename\":\"orphan.json\"}");

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(json(response).get("error").asText(), containsString("'path' field is required"));
    }

    // ------------------------------------------------------------------
    // GET list + MRU ordering
    // ------------------------------------------------------------------

    @Test
    public void shouldListRegisteredCassettesMostRecentlyUsedFirst() throws Exception {
        // given - three cassettes registered in a known order with distinct timestamps
        assertThat(send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/alpha.json\"}").getStatusCode(), is(201));
        tick();
        assertThat(send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/beta.json\"}").getStatusCode(), is(201));
        tick();
        assertThat(send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/gamma.json\"}").getStatusCode(), is(201));

        // when
        HttpResponse listResponse = send("GET", "/mockserver/cassettes", null);

        // then - the documented body shape { "cassettes": [ ... ] }, most-recently-used first
        assertThat(listResponse.getStatusCode(), is(200));
        assertThat(listResponse.getFirstHeader("content-type"), containsString("application/json"));
        JsonNode cassettes = json(listResponse).get("cassettes");
        assertThat(cassettes.isArray(), is(true));
        assertThat(cassettes.size(), is(3));
        assertThat(cassettes.get(0).get("path").asText(), is("/c/gamma.json"));
        assertThat(cassettes.get(1).get("path").asText(), is("/c/beta.json"));
        assertThat(cassettes.get(2).get("path").asText(), is("/c/alpha.json"));

        // when - the oldest cassette is re-registered (touched)
        tick();
        assertThat(send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/alpha.json\"}").getStatusCode(), is(201));

        // then - touching updates lastUsed, moving it to the front of the MRU order (no duplicate)
        JsonNode reordered = json(send("GET", "/mockserver/cassettes", null)).get("cassettes");
        assertThat("re-registering must update in place, not duplicate", reordered.size(), is(3));
        assertThat(reordered.get(0).get("path").asText(), is("/c/alpha.json"));
        assertThat(reordered.get(1).get("path").asText(), is("/c/gamma.json"));
        assertThat(reordered.get(2).get("path").asText(), is("/c/beta.json"));
    }

    @Test
    public void shouldReturnEmptyListWhenNoCassettesRegistered() throws Exception {
        // when
        HttpResponse response = send("GET", "/mockserver/cassettes", null);

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(json(response).get("cassettes").size(), is(0));
    }

    // ------------------------------------------------------------------
    // DELETE removal
    // ------------------------------------------------------------------

    @Test
    public void shouldRemoveCassetteViaBodyPathSoItIsNoLongerListed() throws Exception {
        // given
        send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/keep.json\"}");
        send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/drop.json\"}");
        assertThat(json(send("GET", "/mockserver/cassettes", null)).get("cassettes").size(), is(2));

        // when - deleting via the JSON body { "path": ... }
        HttpResponse deleteResponse = send("DELETE", "/mockserver/cassettes", "{\"path\":\"/c/drop.json\"}");

        // then - the endpoint reports the removal
        assertThat(deleteResponse.getStatusCode(), is(200));
        assertThat(json(deleteResponse).get("removed").asBoolean(), is(true));

        // then - a subsequent GET no longer lists the removed cassette but keeps the other
        JsonNode remaining = json(send("GET", "/mockserver/cassettes", null)).get("cassettes");
        assertThat(remaining.size(), is(1));
        assertThat(remaining.get(0).get("path").asText(), is("/c/keep.json"));
    }

    @Test
    public void shouldRemoveCassetteViaQueryParameter() throws Exception {
        // given
        send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/viaquery.json\"}");

        // when - deleting via the ?path= query parameter
        HttpResponse deleteResponse = send("DELETE", "/mockserver/cassettes?path=/c/viaquery.json", null);

        // then
        assertThat(deleteResponse.getStatusCode(), is(200));
        assertThat(json(deleteResponse).get("removed").asBoolean(), is(true));
        assertThat(json(send("GET", "/mockserver/cassettes", null)).get("cassettes").size(), is(0));
    }

    @Test
    public void shouldReportRemovedFalseForUnknownCassette() throws Exception {
        // when - deleting a cassette that was never registered
        HttpResponse deleteResponse = send("DELETE", "/mockserver/cassettes?path=/c/never-here.json", null);

        // then - the call succeeds but reports nothing was removed
        assertThat(deleteResponse.getStatusCode(), is(200));
        assertThat(json(deleteResponse).get("removed").asBoolean(), is(false));
    }

    // ------------------------------------------------------------------
    // registry lifecycle
    // ------------------------------------------------------------------

    @Test
    public void shouldEmptyRegistryOnServerReset() throws Exception {
        // given
        send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/one.json\"}");
        send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/two.json\"}");
        assertThat(json(send("GET", "/mockserver/cassettes", null)).get("cassettes").size(), is(2));

        // when
        reset();

        // then - the registry is empty
        assertThat(json(send("GET", "/mockserver/cassettes", null)).get("cassettes").size(), is(0));
    }

    // ------------------------------------------------------------------
    // control-plane authentication gate
    // ------------------------------------------------------------------

    @Test
    public void shouldRejectUnauthenticatedCassetteRequestsWhenControlPlaneAuthenticationRequired() throws Exception {
        try {
            // given - control-plane authentication is required and the caller presents no credentials
            ConfigurationProperties.controlPlaneJWTAuthenticationRequired(true);

            // then - every cassette verb is refused rather than served
            assertThat("GET /mockserver/cassettes must be refused when control-plane auth is required",
                send("GET", "/mockserver/cassettes", null).getStatusCode(), is(401));
            assertThat("PUT /mockserver/cassettes must be refused when control-plane auth is required",
                send("PUT", "/mockserver/cassettes", "{\"path\":\"/c/should-not-store.json\"}").getStatusCode(), is(401));
            assertThat("DELETE /mockserver/cassettes must be refused when control-plane auth is required",
                send("DELETE", "/mockserver/cassettes?path=/c/should-not-store.json", null).getStatusCode(), is(401));
        } finally {
            // restore the open control plane so sibling tests (and other suites in this fork) are unaffected
            ConfigurationProperties.controlPlaneJWTAuthenticationRequired(false);
        }

        // then - the refused PUT stored nothing: the registry is still empty once auth is off again
        assertThat(json(send("GET", "/mockserver/cassettes", null)).get("cassettes").size(), is(0));
    }
}
