package org.mockserver.netty.integration.proxy;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileReader;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.VerificationTimes;

import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end coverage for the OpenAPI validation-proxy ENFORCE path.
 *
 * <p>A validation proxy is configured with {@code validateProxyOpenAPISpec} + {@code validateProxyEnforce}
 * and forwards unmatched traffic to a second (upstream) MockServer. Requests/responses are driven over a
 * real socket so that production {@code HttpActionHandler.validateProxyRequest} /
 * {@code validateProxyResponse} — the code that actually short-circuits non-conformant forwarded
 * traffic — is exercised, not a re-implemented enforce branch. Assertions are made on the response the
 * client receives from the running proxy and on what the upstream did (or did not) receive.</p>
 */
public class ValidationProxyEnforceIntegrationTest {

    private static final String SPEC = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");

    private static ClientAndServer upstreamClientAndServer;
    private static ClientAndServer validationProxyClientAndServer;

    private static EventLoopGroup clientEventLoopGroup;
    private static NettyHttpClient httpClient;

    @BeforeClass
    public static void startServers() {
        upstreamClientAndServer = startClientAndServer();
        // validation proxy forwards unmatched requests to the upstream, enforcing the OpenAPI contract
        Configuration proxyConfiguration = configuration()
            .validateProxyOpenAPISpec(SPEC)
            .validateProxyEnforce(true);
        validationProxyClientAndServer = startClientAndServer(proxyConfiguration, "127.0.0.1", upstreamClientAndServer.getPort());
    }

    @BeforeClass
    public static void startEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(ValidationProxyEnforceIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(upstreamClientAndServer);
        stopQuietly(validationProxyClientAndServer);
    }

    @Before
    public void reset() {
        upstreamClientAndServer.reset();
        validationProxyClientAndServer.reset();
    }

    private HttpResponse sendThroughProxy(org.mockserver.model.HttpRequest request) throws Exception {
        Future<HttpResponse> responseFuture = httpClient.sendRequest(
            request.withHeader(HOST.toString(), "localhost:" + validationProxyClientAndServer.getPort()),
            new InetSocketAddress(validationProxyClientAndServer.getPort())
        );
        return responseFuture.get(60, TimeUnit.SECONDS);
    }

    @Test
    public void shouldReject400AndNotForwardWhenRequestViolatesSpec() throws Exception {
        // given - the upstream would happily match POST /pets if the request ever reached it
        upstreamClientAndServer
            .when(request().withMethod("POST").withPath("/pets"))
            .respond(response().withStatusCode(201).withBody("reached-upstream"));

        // when - a schema-INVALID POST /pets (id must be an integer, sent as a string) is forwarded through the proxy
        HttpResponse response = sendThroughProxy(
            request()
                .withMethod("POST")
                .withPath("/pets")
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": \"not_a_number\", \"name\": \"Fido\"}")
        );

        // then - the client receives the enforce 400 from the running proxy
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("OpenAPI request validation failed"));

        // and - the upstream NEVER received the request (short-circuited before forwarding)
        upstreamClientAndServer.verify(request().withMethod("POST").withPath("/pets"), VerificationTimes.exactly(0));
    }

    @Test
    public void shouldForwardWhenRequestConformsToSpec() throws Exception {
        // given - the upstream returns a spec-conformant listPets array
        upstreamClientAndServer
            .when(request().withMethod("GET").withPath("/pets"))
            .respond(response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[{\"id\": 1, \"name\": \"Fido\"}]"));

        // when - a conformant GET /pets is forwarded through the proxy
        HttpResponse response = sendThroughProxy(request().withMethod("GET").withPath("/pets"));

        // then - enforce mode does NOT block conformant traffic: the client gets the upstream response
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("Fido"));

        // and - the upstream actually received the forwarded request
        upstreamClientAndServer.verify(request().withMethod("GET").withPath("/pets"), VerificationTimes.exactly(1));
    }

    @Test
    public void shouldReject502WhenUpstreamResponseViolatesSpec() throws Exception {
        // given - a conformant GET /pets request, but the upstream returns a non-conformant body
        // (listPets must return an array; the upstream returns an object)
        upstreamClientAndServer
            .when(request().withMethod("GET").withPath("/pets"))
            .respond(response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"not\": \"an array\"}"));

        // when - the request is forwarded through the proxy
        HttpResponse response = sendThroughProxy(request().withMethod("GET").withPath("/pets"));

        // then - the client receives the enforce 502 produced by the proxy's response validation
        assertThat(response.getStatusCode(), is(502));
        assertThat(response.getBodyAsString(), containsString("OpenAPI response validation failed"));

        // and - the request did reach the upstream (response enforcement happens after the upstream call)
        upstreamClientAndServer.verify(request().withMethod("GET").withPath("/pets"), VerificationTimes.exactly(1));
    }
}
