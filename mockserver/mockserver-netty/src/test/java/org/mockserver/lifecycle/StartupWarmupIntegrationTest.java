package org.mockserver.lifecycle;

import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies the {@code startupWarmup} behaviour end-to-end:
 * <ul>
 *   <li>a warm-up-enabled server becomes ready, and the background warm-up request (a control-plane
 *       {@code PUT /mockserver/status}) does NOT create any recorded requests or expectations that
 *       would pollute {@code verify}/{@code retrieve};</li>
 *   <li>a warm-up-disabled server starts cleanly and the normal record/retrieve path still works.</li>
 * </ul>
 * The assertions are on behaviour, not timing — {@code /status} is deterministically non-recording, so
 * the "no pollution" assertion holds whether or not the background warm-up thread has run by the time
 * it is checked; the explicit status calls below additionally guarantee the endpoint has been exercised.
 *
 * @author jamesdbloom
 */
public class StartupWarmupIntegrationTest {

    @Test
    public void shouldBecomeReadyAndNotRecordAnyRequestsWhenWarmupEnabled() {
        MockServer mockServer = null;
        MockServerClient mockServerClient = null;
        try {
            mockServer = new MockServer(configuration().startupWarmup(true), 0);
            mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());

            // (a) the server becomes ready — the control-plane status endpoint the warm-up uses answers
            assertThat(mockServerClient.hasStarted(), is(true));
            // exercise the same control-plane status endpoint the background warm-up uses, so at least
            // one PUT /mockserver/status has definitely been handled by the time we assert below
            assertThat(mockServerClient.hasStarted(), is(true));

            // (b) neither the background warm-up nor the explicit status calls create user-visible
            // recorded requests, recorded expectations, or matched expectations
            assertThat(mockServerClient.retrieveRecordedRequests(null).length, is(0));
            assertThat(mockServerClient.retrieveRecordedExpectations(null).length, is(0));
        } finally {
            if (mockServer != null) {
                mockServer.stop();
            }
        }
    }

    @Test
    public void shouldStartCleanlyAndStillRecordDataPlaneRequestsWhenWarmupDisabled() throws Exception {
        MockServer mockServer = null;
        MockServerClient mockServerClient = null;
        try {
            mockServer = new MockServer(configuration().startupWarmup(false), 0);
            int port = mockServer.getLocalPort();
            mockServerClient = new MockServerClient("localhost", port);

            // server starts cleanly with warm-up disabled
            assertThat(mockServerClient.hasStarted(), is(true));
            // nothing recorded before any data-plane traffic
            assertThat(mockServerClient.retrieveRecordedRequests(null).length, is(0));

            // the normal record/retrieve path still works: set up a mock and drive one data-plane request
            mockServerClient
                .when(request().withMethod("GET").withPath("/warmup-disabled/probe"))
                .respond(response().withStatusCode(200).withBody("ok"));

            HttpURLConnection connection = (HttpURLConnection) new URL("http", "127.0.0.1", port, "/warmup-disabled/probe").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try {
                assertThat(connection.getResponseCode(), is(200));
                try (InputStream inputStream = connection.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    while (inputStream.read(buffer) != -1) {
                        // drain
                    }
                }
            } finally {
                connection.disconnect();
            }

            // the data-plane request IS recorded (recording is unaffected by disabling warm-up)
            assertThat(mockServerClient.retrieveRecordedRequests(request().withPath("/warmup-disabled/probe")).length, is(1));
        } finally {
            if (mockServer != null) {
                mockServer.stop();
            }
        }
    }
}
