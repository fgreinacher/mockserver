package org.mockserver.log;

import org.junit.After;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.VerificationTimes.never;

/**
 * End-to-end proof, over a real Netty server and a real client, of the eviction false-green guard
 * ({@code maxLogEntries} + {@code failVerificationOnEvictedLog}).
 *
 * <p>The engine-level {@code MockServerEventLogEvictionTest} already proves the guard against an
 * in-process {@link org.mockserver.log.MockServerEventLog}; this test closes the gap that NO
 * {@code *IntegrationTest} exercised the same guard across the wire — through the REST verification
 * endpoint and the {@link MockServerClient#verify} path that turns a server-side failure message into
 * an {@link AssertionError}.
 *
 * <p>The headline regression is the {@code never()} case: a request that genuinely happened is evicted
 * from the bounded request-log ring, and a naive verification would then report "found 0 times" and
 * PASS — the single worst failure mode for a verification tool (a silent false green). With
 * {@code failVerificationOnEvictedLog=true} the server must instead refuse to certify absence it can no
 * longer see, and the client must surface that refusal as a loud {@link AssertionError}.
 *
 * <p>This is a Netty {@code *IntegrationTest}, so it runs in the single-fork Surefire phase and is
 * exempt from the global-state mutation guard; the whole configuration is per-server (constructor
 * argument), so nothing JVM-global is mutated and the {@code @After} simply stops the server.
 */
public class EvictedLogVerificationIntegrationTest {

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @After
    public void stopServer() {
        if (mockServerClient != null) {
            mockServerClient.stop();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    public void shouldFailNeverVerificationLoudlyWhenMatchingRequestWasEvictedFromTheLog() throws IOException {
        // given - a server whose request log holds only the two most-recent entries and which has been
        // told to fail (rather than silently pass) any verification it can no longer prove after eviction
        mockServer = new MockServer(
            configuration()
                .maxLogEntries(2)
                .failVerificationOnEvictedLog(true),
            0
        );
        int port = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("127.0.0.1", port);

        // when - a request that really was made is recorded ...
        send(port, "/was-called");

        // ... and later traffic floods the bounded log so the entry above is evicted (more than
        // maxLogEntries further requests guarantees the /was-called entry falls out of the ring)
        send(port, "/filler-1");
        send(port, "/filler-2");
        send(port, "/filler-3");
        send(port, "/filler-4");

        // then - never() must NOT silently pass: the request DID happen, the proof was simply discarded.
        // The client turns the server's refusal into an AssertionError whose message names the cause.
        try {
            mockServerClient.verify(request("/was-called"), never());
            fail("verify(never()) should have thrown AssertionError: the /was-called request was made but "
                + "its log entry was evicted, so absence cannot be certified and the guard must fail loudly "
                + "rather than report a silent false green");
        } catch (AssertionError assertionError) {
            String message = assertionError.getMessage();
            assertThat("the failure must state the log could not be verified because entries were evicted, "
                    + "message was: " + message,
                message, containsString("could not be verified"));
            assertThat("the failure must attribute the eviction to the maxLogEntries bound, message was: "
                    + message,
                message, containsString("maxLogEntries"));
        }
    }

    /**
     * Sends a plain GET to the given path so it is recorded in the request log. No expectation is set,
     * so the server returns 404 — irrelevant here, the point is only that the request is logged.
     */
    private static void send(int port, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                drain(stream);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[4096];
        while (in.read(buffer) != -1) {
            // discard - we only care that the request reached the server and was logged
        }
    }
}
