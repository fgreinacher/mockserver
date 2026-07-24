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
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.never;

/**
 * End-to-end proof, over a real Netty server and a real client, of the eviction false-green guard on the
 * <strong>response-aware</strong> verification path ({@code maxLogEntries} +
 * {@code failVerificationOnEvictedLog}).
 *
 * <p>{@code MockServerEventLog} has two independent arms of the same guard. The request-only arm
 * ({@code verifyRequest}) is covered across the wire by {@link EvictedLogVerificationIntegrationTest};
 * the response-aware arm ({@code verifyResponse}, reached whenever the {@code Verification} carries an
 * {@code httpResponse}) counts recorded request-response <em>pairs</em> through an entirely separate code
 * path and, until this test, had no {@code *IntegrationTest} at all — only an engine-level test against an
 * in-process event log.
 *
 * <h2>Why this verification form, and not another</h2>
 *
 * <p>Derived from the production code rather than copied from the request-side test, the response arm
 * reaches the guard only when <em>all</em> of the following hold:
 *
 * <ol>
 *   <li><b>The verification carries a response.</b> {@code verify(...)} dispatches to
 *       {@code verifyResponse} only when {@code verification.getHttpResponse() != null}; a request-only
 *       verification would exercise the already-covered request arm instead. Hence the
 *       {@code verify(request, response, times)} pair form here.</li>
 *   <li><b>The verification must already PASS.</b> The guard sits in the {@code else} branch, after
 *       {@code verification.getTimes().matches(matchedCount)} succeeds. An {@code atLeast(1)} or
 *       {@code once()} verification of an evicted pair fails earlier with an ordinary
 *       "Response not found ..." message and never reaches the guard at all.</li>
 *   <li><b>An UPPER bound must be asserted.</b> {@code upperBoundUnprovableAfterEviction} returns null
 *       when {@code getTimes().getAtMost() == -1}. Eviction can only ever push the observed count too
 *       LOW, so a lower-bound pass stays sound and is deliberately left alone.</li>
 * </ol>
 *
 * <p>{@code never()} is {@code VerificationTimes(0, 0)}: {@code getAtMost() == 0 != -1}, and an evicted
 * pair yields {@code matchedCount == 0}, which {@code never()} <em>satisfies</em>. That combination is
 * precisely the false green the guard exists to prevent — a server without the guard reports "the response
 * never happened" when in truth the evidence was simply discarded. It is also why {@code never()} is the
 * only honest choice here: {@code atLeast(1)} would go red for an unrelated reason and prove nothing.
 *
 * <p>One difference from the request arm, also derived rather than assumed, shapes the traffic below: the
 * response arm looks only at {@code EXPECTATION_RESPONSE} / {@code FORWARDED_REQUEST} entries
 * ({@code responseVerificationLogPredicate}) — unmatched 404s ({@code NO_MATCH_RESPONSE}) are invisible to
 * it. So the entry that must be evicted has to come from a real matched expectation, and the filler
 * traffic that evicts it can safely be unmatched requests, which cannot themselves be miscounted as
 * matching responses.
 *
 * <p>This is a Netty {@code *IntegrationTest}, so it runs in the single-fork Surefire phase and is exempt
 * from the global-state mutation guard; the whole configuration is per-server (constructor argument), so
 * nothing JVM-global is mutated and the {@code @After} simply stops the client and the server.
 */
public class EvictedResponseVerificationIntegrationTest {

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test
    public void shouldFailNeverResponseVerificationLoudlyWhenMatchingPairWasEvictedFromTheLog() throws IOException {
        // given - a server whose event log holds only the two most-recent entries and which has been told
        // to fail (rather than silently pass) any verification it can no longer prove after eviction
        mockServer = new MockServer(
            configuration()
                .maxLogEntries(2)
                .failVerificationOnEvictedLog(true),
            0
        );
        int port = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("127.0.0.1", port);

        // an expectation, so the exchange below is recorded as an EXPECTATION_RESPONSE pair - the only
        // entry type the response-aware verification path looks at
        mockServerClient
            .when(request().withPath("/was-responded"))
            .respond(response().withStatusCode(418).withBody("teapot"));

        // when - the expectation really does respond, recording a request-response pair. Assert the 418
        // here so the fixture is self-verifying: the guard only needs evictedCount > 0, so without this
        // check the test would stay green even if expectation registration silently failed and this
        // exchange was recorded as an ordinary 404 NO_MATCH_RESPONSE the response arm never sees.
        assertThat("the expectation must actually respond 418 so a real EXPECTATION_RESPONSE pair is "
                + "recorded — otherwise the response-side guard is never exercised",
            send(port, "/was-responded"), is(418));

        // ... and later unmatched traffic floods the bounded log so that pair is evicted (more than
        // maxLogEntries further requests guarantees it falls out of the ring)
        send(port, "/filler-1");
        send(port, "/filler-2");
        send(port, "/filler-3");
        send(port, "/filler-4");

        // then - never() must NOT silently pass: the 418 response WAS sent, the proof was simply
        // discarded. The client turns the server's refusal into an AssertionError naming the cause.
        try {
            mockServerClient.verify(request().withPath("/was-responded"), response().withStatusCode(418), never());
            fail("verify(request, response, never()) should have thrown AssertionError: the 418 response was "
                + "really sent but its log entry was evicted, so absence cannot be certified and the guard "
                + "must fail loudly rather than report a silent false green");
        } catch (AssertionError assertionError) {
            String message = assertionError.getMessage();
            // "Response could not be verified" (not "Request could not be verified") pins this to the
            // response-aware arm, and distinguishes it from the ordinary "Response not found" failure
            // that would be raised had the pair still been in the log
            assertThat("the failure must come from the response-side guard and state the response could not "
                    + "be verified, message was: " + message,
                message, containsString("Response could not be verified"));
            assertThat("the failure must attribute the eviction to the maxLogEntries bound, message was: "
                    + message,
                message, containsString("maxLogEntries"));
        }
    }

    /**
     * Sends a plain GET to the given path so the exchange is recorded in the event log, returning the
     * response status code. The matched path returns 418 from the registered expectation; filler paths
     * with no expectation return 404 - irrelevant for the filler traffic, whose only job is to consume
     * room in the bounded log.
     */
    private static int send(int port, String path) throws IOException {
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
            return statusCode;
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
