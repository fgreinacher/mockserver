package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.test.Http2FlowControlBodies;
import org.mockserver.testing.integration.mock.AbstractBasicMockingSameJVMIntegrationTest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * @author jamesdbloom
 */
public class HTTP2MockingIntegrationTest extends AbstractBasicMockingSameJVMIntegrationTest {

    private static int mockServerPort;

    @BeforeClass
    public static void startServer() {
        mockServerClient = startClientAndServer();
        mockServerPort = mockServerClient.getPort();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Override
    public int getServerPort() {
        return mockServerPort;
    }

    public HttpRequest getRequestModifier(HttpRequest httpRequest) {
        // TODO(jamesdbloom) support http2 in plain text
        if (Boolean.TRUE.equals(httpRequest.isSecure())) {
            return httpRequest
                .clone()
                .withProtocol(Protocol.HTTP_2);
        } else {
            return httpRequest;
        }
    }

    @Test(timeout = 60000)
    public void shouldReturnLargeMockedResponseOverDirectHttp2() {
        // given - a mocked body larger than the 65,535-byte HTTP/2 initial flow-control window (both the
        // per-stream and the connection window), so the server must react to the client's WINDOW_UPDATE
        // and flush the queued DATA frames. On the DIRECT TLS+ALPN h2 path no mock-serving handler sits
        // ahead of the h2 codec swallowing channelReadComplete, so the flow-control flush already works:
        // this passes both before and after the CONNECT-tunnel fix. Its job is isolation - a future
        // regression that reds here points at the shared h2 layer, whereas one that reds only the proxy
        // test points at the relay pipeline.
        String largeBody = Http2FlowControlBodies.body(Http2FlowControlBodies.Size.OVER_WINDOW, "direct-tls-alpn");
        mockServerClient
            .when(request().withPath(calculatePath("large_direct_h2")))
            .respond(response().withStatusCode(201).withBody(largeBody));

        // when - fetched over direct TLS+ALPN HTTP/2 (getRequestModifier stamps HTTP_2 on the secure request)
        HttpResponse httpResponse = makeRequest(
            request().withSecure(true).withPath(calculatePath("large_direct_h2")),
            getHeadersToRemove()
        );

        // then - the entire body arrived, proving the direct h2 flow-control flush ran
        assertThat(httpResponse.getStatusCode(), is(201));
        assertThat(httpResponse.getBodyAsString().length(), is(largeBody.length()));
        assertThat(httpResponse.getBodyAsString(), is(largeBody));
    }

}
