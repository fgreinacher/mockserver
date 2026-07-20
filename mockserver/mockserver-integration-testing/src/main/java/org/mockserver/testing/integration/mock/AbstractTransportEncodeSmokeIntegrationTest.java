package org.mockserver.testing.integration.mock;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.mockserver.matchers.Times.exactly;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.OK_200;

/**
 * Explicit per-transport <em>response-encode</em> smoke tests — the counterpart to
 * {@link AbstractTransportDecodeSmokeIntegrationTest}, which covers only request decode.
 * <p>
 * That asymmetry is the structural cause of a recurring defect class. Every bug in the family was a
 * response-<em>encode</em> bug, invisible to a decode test and invisible to any test that asserts
 * against MockServer's own model objects:
 * <ul>
 *     <li>GitHub issue #2419 — server-streaming gRPC delivered zero messages to a real client over
 *         HTTP/2, because the handler wrote raw Netty objects that nothing stamped with the HTTP/2
 *         stream id, so the whole stream went out on a phantom server-initiated stream;</li>
 *     <li>the same omission in the SSE handler, the streaming-body writer, the metrics endpoint and
 *         the MCP handler — all found only by auditing, because no test drove them over HTTP/2.</li>
 * </ul>
 * The common signature is that the server believes it responded and the client receives nothing.
 * Only a test that asserts <strong>the client actually received the body</strong>, run over the
 * transport in question, can see it.
 * <p>
 * Each method therefore sends {@code withSecure(true)}: in the HTTP/2 subclasses
 * {@code getRequestModifier} upgrades a secure request to {@code Protocol.HTTP_2}, so these run over
 * real HTTP/2 there and over HTTPS elsewhere — one set of methods covering both. (The plaintext leg
 * cannot exercise h2c: {@code NettyHttpClient} downgrades HTTP_2 to HTTP/1.1 when the request is not
 * secure, since h2c needs prior knowledge rather than ALPN. h2c encode coverage is a separate gap,
 * noted in the audit.)
 * <p>
 * Because this class sits between {@link AbstractTransportDecodeSmokeIntegrationTest} and
 * {@link AbstractBasicMockingIntegrationTest} in the inheritance chain, every concrete transport
 * subclass inherits and runs these methods, forming an explicit per-transport encode contract that a
 * future change cannot silently drop.
 */
public abstract class AbstractTransportEncodeSmokeIntegrationTest extends AbstractTransportDecodeSmokeIntegrationTest {

    /**
     * Whether this transport can serve responses chunked by {@code ConnectionOptions.chunkSize}.
     * Override to {@code false} on transports that do not honour it.
     */
    protected boolean supportsChunkedResponses() {
        return true;
    }

    // ========================================================================
    // (a) static response body actually reaches the client
    // ========================================================================

    @Test
    public void encodeSmoke_staticResponseBody() {
        // when
        mockServerClient
            .when(
                request().withPath(calculatePath("encode_smoke_static")),
                exactly(1)
            )
            .respond(
                response().withBody("static_encoded")
            );

        // then — the client receives the body, on this transport
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("static_encoded"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("encode_smoke_static")),
                getHeadersToRemove()
            )
        );
    }

    // ========================================================================
    // (b) SSE stream — covered by a dedicated test, not here
    // ========================================================================
    //
    // SSE deliberately has no method in this class. The shared harness cannot assert an SSE body:
    // NettyHttpClient installs StreamingAwareHttpObjectAggregator, which detects
    // Content-Type: text/event-stream and RELAYS the stream instead of aggregating it, so
    // makeRequest returns a 200 with a null body on every transport. A test written here would
    // therefore assert nothing about SSE on any transport, which is precisely the kind of vacuous
    // coverage that let this defect class survive.
    //
    // SSE-over-HTTP/2 encode is instead covered end-to-end by
    // org.mockserver.netty.integration.mock.Http2SseStreamingIntegrationTest, which drives a real
    // Netty HTTP/2 (h2c) multiplex client and asserts the event frames actually arrive on the
    // client's own stream.

    // ========================================================================
    // (c) chunked response body actually reaches the client, in full
    // ========================================================================

    @Test
    public void encodeSmoke_chunkedResponseBody() {
        assumeTrue("transport does not support chunked responses", supportsChunkedResponses());

        // given — a body large enough to span several chunks, so a transport that drops or
        // mis-routes continuation frames (rather than the head) is also caught
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            builder.append("chunk_body_line_").append(i).append('\n');
        }
        String chunkedBody = builder.toString();

        mockServerClient
            .when(
                request().withPath(calculatePath("encode_smoke_chunked")),
                exactly(1)
            )
            .respond(
                response()
                    .withBody(chunkedBody)
                    .withConnectionOptions(connectionOptions().withChunkSize(128))
            );

        // then — the client receives every byte, not just the first chunk
        org.mockserver.model.HttpResponse response = makeRequest(
            request()
                .withSecure(true)
                .withPath(calculatePath("encode_smoke_chunked")),
            getHeadersToRemove()
        );

        assertThat(response.getStatusCode(), is(OK_200.code()));
        assertThat(response.getBodyAsString(), is(chunkedBody));
    }

    // ========================================================================
    // (d) large single-shot response body actually reaches the client, in full
    //     — spans multiple HTTP/2 DATA frames and exceeds the default frame size
    // ========================================================================

    @Test
    public void encodeSmoke_largeResponseBody() {
        // given — comfortably larger than the 16KB default HTTP/2 max frame size
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 64 * 1024) {
            builder.append("0123456789abcdef");
        }
        String largeBody = builder.toString();

        mockServerClient
            .when(
                request().withPath(calculatePath("encode_smoke_large")),
                exactly(1)
            )
            .respond(
                response().withBody(largeBody)
            );

        // then — the whole body arrives, not a truncated prefix
        org.mockserver.model.HttpResponse response = makeRequest(
            request()
                .withSecure(true)
                .withPath(calculatePath("encode_smoke_large")),
            getHeadersToRemove()
        );

        assertThat(response.getStatusCode(), is(OK_200.code()));
        assertThat(response.getBodyAsString().length(), is(largeBody.length()));
        assertThat(response.getBodyAsString(), is(largeBody));
    }
}
