package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.MockMode;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Data-plane integration test for the operating mode (SPY / CAPTURE).
 *
 * <p>{@link OperatingModeIntegrationTest} only asserts the control-plane echo (PUT mode -> GET
 * reflects it) plus the SIMULATE negative (unmatched -> 404). This test closes the gap by driving
 * an UNMATCHED request all the way through the SPY and CAPTURE modes and asserting the documented
 * data-plane behaviour: the request is actually proxied to the real upstream (so the client gets the
 * upstream's body back) AND the proxied exchange is recorded so it can be retrieved as an
 * expectation.
 *
 * <p>The mode is the DECISIVE factor here. Proxy-on-no-match is driven purely by the
 * {@code attemptToProxyIfNoMatchingExpectation} flag that SPY/CAPTURE toggle (via
 * {@link MockMode#proxyUnmatchedRequests()}), with the upstream selected by the request's
 * {@code Host} header. Crucially this test does NOT configure a {@code proxyRemoteHost}: a
 * configured remote proxy would proxy unmatched requests regardless of the mode
 * ({@code HttpActionHandler} treats a configured remote proxy as an independent trigger), which
 * would make the mode incidental and mask a regression. To prove the mode is what flips the
 * behaviour, each test first confirms the same request returns 404 in SIMULATE mode, then switches
 * to the proxying mode and confirms it is proxied and recorded.
 *
 * <p>Both SPY and CAPTURE share the same underlying proxy-on-no-match flag (see {@link MockMode}),
 * so each mode is asserted independently against a real echo upstream (a second MockServer).
 */
public class OperatingModeDataPlaneIntegrationTest {

    private static MockServer upstreamServer;
    private static MockServerClient upstreamClient;
    private static int upstreamPort;

    private static MockServer modeServer;
    private static MockServerClient modeClient;
    private static int modePort;

    @BeforeClass
    public static void startServers() {
        // Real echo upstream: a second MockServer that returns a known body.
        upstreamServer = new MockServer();
        upstreamPort = upstreamServer.getLocalPort();
        upstreamClient = new MockServerClient("localhost", upstreamPort);

        // MockServer under test. No proxyRemoteHost is configured: proxy-on-no-match is driven
        // solely by the operating mode, and the upstream is selected by the request Host header.
        Configuration configuration = configuration();
        modeServer = new MockServer(configuration);
        modePort = modeServer.getLocalPort();
        modeClient = new MockServerClient("localhost", modePort);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(modeClient);
        stopQuietly(modeServer);
        stopQuietly(upstreamClient);
        stopQuietly(upstreamServer);
    }

    @Before
    public void resetServers() {
        modeClient.reset();
        upstreamClient.reset();
        // reset() does not reset the operating mode, so tests start from a known SIMULATE baseline.
        modeClient.setMode(MockMode.SIMULATE);
    }

    @Test
    public void shouldProxyAndRecordUnmatchedRequestInSpyMode() throws Exception {
        assertModeDrivesProxyAndRecord(MockMode.SPY, "/spy-unmatched", "spy-upstream-body");
    }

    @Test
    public void shouldProxyAndRecordUnmatchedRequestInCaptureMode() throws Exception {
        assertModeDrivesProxyAndRecord(MockMode.CAPTURE, "/capture-unmatched", "capture-upstream-body");
    }

    /**
     * Proves the operating mode drives the data plane: the same unmatched request returns 404 in
     * SIMULATE mode, but once the given proxy-on-no-match mode is set it is proxied to the real
     * upstream (client receives the upstream body) AND recorded as exactly one expectation. All
     * assertions are on the DATA-PLANE outcome, not the control-plane mode echo.
     */
    private void assertModeDrivesProxyAndRecord(MockMode mode, String path, String upstreamBody) throws Exception {
        // given - the upstream returns a known body for the path
        upstreamClient
            .when(request().withPath(path))
            .respond(response().withBody(upstreamBody));

        // baseline - in SIMULATE mode the unmatched request is NOT proxied (404), proving the mode
        // (not a configured remote proxy) is the decisive factor for the proxying below
        String simulateResponse = sendUnmatchedRequestToUpstream(path);
        assertThat("unmatched request in SIMULATE mode should return 404 (not proxied)",
            simulateResponse, containsString("404"));
        assertThat("unmatched request in SIMULATE mode should NOT return the upstream body",
            simulateResponse, not(containsString(upstreamBody)));
        assertThat("no exchange should be recorded in SIMULATE mode",
            modeClient.retrieveRecordedExpectations(request().withPath(path)).length, is(0));

        // when - the server is switched to the proxying mode and the SAME unmatched request is sent
        modeClient.setMode(mode);
        String rawResponse = sendUnmatchedRequestToUpstream(path);

        // then - the response is the upstream's, proving the request was proxied through (not 404)
        assertThat("unmatched request in " + mode + " mode should be proxied (HTTP 200)",
            rawResponse, containsString("200"));
        assertThat("unmatched request in " + mode + " mode should return the upstream body",
            rawResponse, containsString(upstreamBody));
        assertThat("unmatched request in " + mode + " mode must NOT return a 404",
            rawResponse, not(containsString("404")));

        // and - the proxied exchange is recorded and retrievable as exactly one expectation
        pollUntilTrue(() ->
            modeClient.retrieveRecordedExpectations(request().withPath(path)).length >= 1);
        Expectation[] recorded = modeClient.retrieveRecordedExpectations(request().withPath(path));
        assertThat("exactly one recorded expectation should exist for " + path + " in " + mode + " mode",
            recorded.length, is(1));
        assertThat("recorded expectation should be for the proxied path",
            ((HttpRequest) recorded[0].getHttpRequest()).getPath().getValue(), is(path));
    }

    // ---- helpers ----

    /**
     * Sends an unmatched request over the wire to the server under test with a {@code Host} header
     * pointing at the upstream. When proxy-on-no-match is enabled the server forwards it there.
     */
    private String sendUnmatchedRequestToUpstream(String path) throws Exception {
        try (Socket socket = new Socket("localhost", modePort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return org.apache.commons.io.IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
        }
    }

    private void pollUntilTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for condition to become true");
            }
            Thread.sleep(50);
        }
    }
}
