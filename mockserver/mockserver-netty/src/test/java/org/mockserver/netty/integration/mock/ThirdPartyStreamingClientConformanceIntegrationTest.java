package org.mockserver.netty.integration.mock;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.mock.Expectation;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.HttpWebSocketResponse;
import org.mockserver.model.SelectionSetMatchType;
import org.mockserver.model.SseEvent;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.netty.MockServer;

import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Drives MockServer's streaming responses with <b>third-party protocol clients</b> — Java-WebSocket
 * and okhttp-sse — rather than with Netty, the library MockServer itself is built on.
 *
 * <p>This exists because the defect family these tests cover (issue #2419 and its siblings) all
 * shipped while unit tests were green: conformance had only ever been asserted against MockServer's
 * own objects, so any assumption the server made was shared by the thing checking it. An
 * independent implementation of the same RFC does not share those assumptions.
 */
public class ThirdPartyStreamingClientConformanceIntegrationTest {

    private static int mockServerPort;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    // ------------------------------------------------------------------
    // WebSocket control frames, via Java-WebSocket
    // ------------------------------------------------------------------

    /**
     * A Java-WebSocket client that keeps the session open and records the control frames it sees.
     * Java-WebSocket's own connection-lost detector is what kills an unanswered-ping session in
     * the field, so this uses its real PING/PONG plumbing.
     */
    private static class RecordingWebSocketClient extends WebSocketClient {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger pongsReceived = new AtomicInteger();
        final AtomicReference<Integer> closeCode = new AtomicReference<>();
        final List<String> messages = new CopyOnWriteArrayList<>();

        RecordingWebSocketClient(String path) {
            super(URI.create("ws://localhost:" + mockServerPort + path));
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            opened.countDown();
        }

        @Override
        public void onMessage(String message) {
            messages.add(message);
        }

        @Override
        public void onWebsocketPong(org.java_websocket.WebSocket conn, org.java_websocket.framing.Framedata f) {
            pongsReceived.incrementAndGet();
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closeCode.compareAndSet(null, code);
            closed.countDown();
        }

        @Override
        public void onError(Exception ex) {
            // recorded via the close latch
        }
    }

    private void mockWebSocketAt(String path) {
        mockServerClient
            .when(request().withMethod("GET").withPath(path))
            .respondWithWebSocket(
                HttpWebSocketResponse.webSocketResponse()
                    .withMessage(WebSocketMessage.webSocketMessage("hello"))
                    // keep the socket open so control frames can be exercised
                    .withCloseConnection(false)
            );
    }

    /**
     * RFC 6455 §5.5.2 makes the Pong a MUST. MockServer performs the handshake by hand, which
     * installs only the frame codec, so before the control-frame handler existed every mocked
     * WebSocket ignored PING entirely and every long-lived session died on keepalive.
     */
    @Test
    public void shouldAnswerClientPingWithPong() throws Exception {
        mockWebSocketAt("/ws-ping");

        RecordingWebSocketClient client = new RecordingWebSocketClient("/ws-ping");
        try {
            assertTrue("client failed to connect", client.connectBlocking(10, TimeUnit.SECONDS));
            assertTrue(client.opened.await(10, TimeUnit.SECONDS));

            client.sendPing();

            long deadline = System.currentTimeMillis() + 10_000;
            while (client.pongsReceived.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertThat("a mocked WebSocket must answer PING with PONG (RFC 6455 §5.5.2)",
                client.pongsReceived.get(), greaterThanOrEqualTo(1));
        } finally {
            client.closeBlocking();
        }
    }

    @Test
    public void shouldAnswerRepeatedKeepalivePings() throws Exception {
        mockWebSocketAt("/ws-keepalive");

        RecordingWebSocketClient client = new RecordingWebSocketClient("/ws-keepalive");
        try {
            assertTrue(client.connectBlocking(10, TimeUnit.SECONDS));

            for (int i = 0; i < 3; i++) {
                client.sendPing();
                Thread.sleep(50);
            }

            long deadline = System.currentTimeMillis() + 10_000;
            while (client.pongsReceived.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertThat("every keepalive PING must be answered, not just the first",
                client.pongsReceived.get(), greaterThanOrEqualTo(3));
            assertTrue("the session must still be open after keepalive", client.isOpen());
        } finally {
            client.closeBlocking();
        }
    }

    /**
     * The client's close must terminate the session rather than hang.
     *
     * <p><b>Deliberately does not assert that the close was echoed.</b> Java-WebSocket's
     * {@code onClose} reports the code <em>the client itself sent</em> when its close is not
     * answered, so this client cannot distinguish a proper echo from the server simply dropping
     * the TCP connection — replacing the echo with a bare {@code ctx.close()} leaves this test
     * green. The echo itself is asserted on the wire by
     * {@link #shouldEchoClientCloseStatusCodeOnTheWire()} and at unit level by
     * {@code WebSocketControlFrameHandlerTest}.
     */
    @Test
    public void shouldCloseTheConnectionWhenTheClientCloses() throws Exception {
        mockWebSocketAt("/ws-close");

        RecordingWebSocketClient client = new RecordingWebSocketClient("/ws-close");
        assertTrue(client.connectBlocking(10, TimeUnit.SECONDS));

        client.close(CloseFrame.GOING_AWAY, "client done");

        assertTrue("the server never closed the session", client.closed.await(10, TimeUnit.SECONDS));
    }

    /**
     * RFC 6455 §5.5.1 — the peer MUST echo a close frame back, carrying the status code.
     *
     * <p>Asserted on the raw wire rather than through a client library, because neither
     * Java-WebSocket nor OkHttp surfaces the received close frame's payload: both report the
     * locally-sent code when no echo arrives, so neither can tell an echo from a TCP drop.
     * Reading the bytes is the only observation that discriminates.
     */
    @Test
    public void shouldEchoClientCloseStatusCodeOnTheWire() throws Exception {
        mockWebSocketAt("/ws-close-wire");

        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10_000);
            handshakeWebSocket(socket, "/ws-close-wire");

            // client -> server frames must be masked (RFC 6455 §5.3)
            socket.getOutputStream().write(
                maskedCloseFrame(CloseFrame.GOING_AWAY, "client done"));
            socket.getOutputStream().flush();

            byte[] echoed = readCloseFrame(socket.getInputStream());
            assertThat("the server sent no close frame at all", echoed, notNullValue());
            int code = ((echoed[0] & 0xFF) << 8) | (echoed[1] & 0xFF);
            assertThat("the close echo must carry the client's status code",
                code, is(CloseFrame.GOING_AWAY));
            assertThat(new String(echoed, 2, echoed.length - 2, StandardCharsets.UTF_8),
                is("client done"));
        }
    }

    /** Minimal RFC 6455 opening handshake; the 101 response is consumed but not validated. */
    private void handshakeWebSocket(Socket socket, String path) throws Exception {
        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\n"
            + "Host: localhost:" + mockServerPort + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();

        // read exactly up to the end of the response head, leaving frame bytes in the stream
        java.io.InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b == -1) {
                throw new IllegalStateException("connection closed during handshake: " + head);
            }
            head.append((char) b);
        }
        assertThat("handshake did not succeed: " + head,
            head.toString(), containsString("101"));
    }

    private static byte[] maskedCloseFrame(int statusCode, String reason) {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (statusCode >> 8);
        payload[1] = (byte) statusCode;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);

        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        byte[] frame = new byte[2 + 4 + payload.length];
        frame[0] = (byte) 0x88;                              // FIN + opcode 8 (close)
        frame[1] = (byte) (0x80 | payload.length);           // MASK + 7-bit length
        System.arraycopy(mask, 0, frame, 2, 4);
        for (int i = 0; i < payload.length; i++) {
            frame[6 + i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        return frame;
    }

    /** Read frames until a close frame arrives; returns its (unmasked) payload, or null. */
    private static byte[] readCloseFrame(java.io.InputStream in) throws Exception {
        while (true) {
            int b0 = in.read();
            if (b0 == -1) {
                return null;
            }
            int length = in.read() & 0x7F;
            if (length == 126 || length == 127) {
                throw new IllegalStateException("unexpected extended length on a control frame");
            }
            byte[] payload = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(payload, read, length - read);
                if (n == -1) {
                    return null;
                }
                read += n;
            }
            if ((b0 & 0x0F) == 0x8) {
                return payload;
            }
            // a data frame (the scripted "hello") -- keep reading
        }
    }

    /**
     * A server-initiated close must deliver the scripted message and then close cleanly, rather
     * than dropping the connection.
     *
     * <p><b>Deliberately does not assert the distinction between a 1000 close frame and an empty
     * one.</b> {@code finishWebSocket} was changed to send 1000 NORMAL_CLOSURE instead of an empty
     * close frame, but Java-WebSocket normalises a missing status code to 1000, so this client
     * cannot tell the two apart — mutating the production code back to an empty frame leaves this
     * test green. The echo path's code handling is covered discriminatingly by
     * {@code WebSocketControlFrameHandlerTest.shouldEchoEmptyCloseAsNormalClosure}; the
     * server-initiated close code itself is currently unverified by any test.
     */
    @Test
    public void shouldDeliverMessageThenCloseWhenCloseConnectionIsSet() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/ws-server-close"))
            .respondWithWebSocket(
                HttpWebSocketResponse.webSocketResponse()
                    .withMessage(WebSocketMessage.webSocketMessage("bye"))
                    .withCloseConnection(true)
            );

        RecordingWebSocketClient client = new RecordingWebSocketClient("/ws-server-close");
        assertTrue(client.connectBlocking(10, TimeUnit.SECONDS));

        assertTrue("server never closed the connection", client.closed.await(10, TimeUnit.SECONDS));
        assertThat(client.messages, hasItem("bye"));
        assertThat("the close must be orderly, not an abnormal drop",
            client.closeCode.get(), is(not(CloseFrame.ABNORMAL_CLOSE)));
    }

    // ------------------------------------------------------------------
    // GraphQL subscriptions, via a real graphql-transport-ws exchange
    // ------------------------------------------------------------------
    //
    // These exist because `graphqlSubscriptionFilter` was, until this was fixed, unreachable on a
    // running server by ANY route -- so the delayed-subscription ordering fix (`complete` must never
    // precede the `next` frames) was latent: correct code that nothing could invoke. Two independent
    // blocks, each sufficient on its own:
    //
    //   * the Java client emits a `"type":"GRAPHQL"` discriminator (GraphQLBodyDTOSerializer always
    //     writes it) that model/schema/httpWebSocketResponse.json rejected under
    //     `additionalProperties: false`; and
    //   * behind that, `HttpWebSocketResponseDTO.graphqlSubscriptionFilter` is declared as the
    //     concrete `GraphQLBodyDTO`, so the polymorphic BodyDTODeserializer -- registered against
    //     `BodyDTO` and matched by exact class -- never ran for it, and the all-final DTO had no
    //     creator for Jackson to fall back on, so raw JSON failed too.
    //
    // The two are deliberately covered by different routes so a regression in either is
    // attributable: the Java-client tests below exercise the discriminator path, and
    // shouldAcceptATypelessGraphqlSubscriptionFilterAsRawJson exercises ONLY the DTO creator
    // (a typeless filter was always schema-legal, so it isolates the deserialisation fix).

    private static final String SUBSCRIPTION_QUERY =
        "subscription OnMessage { messageAdded { id body } }";

    /** A Java-WebSocket client that negotiates graphql-transport-ws and records protocol messages. */
    private static class GraphQLWebSocketClient extends WebSocketClient {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch terminal = new CountDownLatch(1);
        final List<String> messages = new CopyOnWriteArrayList<>();

        GraphQLWebSocketClient(String path) {
            super(URI.create("ws://localhost:" + mockServerPort + path),
                new Draft_6455(Collections.emptyList(),
                    Collections.singletonList(new Protocol(GRAPHQL_TRANSPORT_WS))));
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            opened.countDown();
        }

        @Override
        public void onMessage(String message) {
            messages.add(message);
            if (message.contains("\"type\":\"complete\"") || message.contains("\"type\":\"error\"")) {
                terminal.countDown();
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            terminal.countDown();
        }

        @Override
        public void onError(Exception ex) {
            // recorded via the terminal latch
        }

        /** Perform the connection_init/connection_ack handshake the protocol requires before subscribe. */
        void handshakeAndSubscribe(String query) throws Exception {
            assertTrue("websocket never opened", connectBlocking(10, TimeUnit.SECONDS));
            send("{\"type\":\"connection_init\"}");
            send("{\"id\":\"sub-1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"" + query + "\"}}");
            assertTrue("subscription never reached a terminal message",
                terminal.await(10, TimeUnit.SECONDS));
        }
    }

    private static final String GRAPHQL_TRANSPORT_WS = "graphql-transport-ws";

    private void mockGraphQLSubscriptionAt(String path, String filterQuery) {
        mockServerClient
            .when(request().withMethod("GET").withPath(path))
            .respondWithWebSocket(
                HttpWebSocketResponse.webSocketResponse()
                    .withSubprotocol(GRAPHQL_TRANSPORT_WS)
                    .withGraphqlSubscriptionFilter(
                        GraphQLBody.graphQL(filterQuery)
                            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET))
                    .withMessage(WebSocketMessage.webSocketMessage(
                        "{\"messageAdded\":{\"id\":\"1\",\"body\":\"hello\"}}"))
            );
    }

    /**
     * The control-plane round trip: an expectation carrying a {@code graphqlSubscriptionFilter} is
     * accepted by a <em>running</em> server and can be read back off it. Before the fix the
     * {@code .when(...)} call itself failed — the Java client's {@code "type":"GRAPHQL"} discriminator
     * was rejected by the schema — so this never got as far as the read-back.
     */
    @Test
    public void shouldRoundTripGraphqlSubscriptionFilterThroughTheControlPlane() {
        mockServerClient
            .when(request().withMethod("GET").withPath("/ws/graphql-roundtrip"))
            .respondWithWebSocket(
                HttpWebSocketResponse.webSocketResponse()
                    .withSubprotocol(GRAPHQL_TRANSPORT_WS)
                    .withGraphqlSubscriptionFilter(
                        GraphQLBody.graphQL(SUBSCRIPTION_QUERY, "OnMessage")
                            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
                            .withFields("messageAdded"))
            );

        Expectation[] active = mockServerClient
            .retrieveActiveExpectations(request().withPath("/ws/graphql-roundtrip"));

        assertThat("the expectation was not stored on the server", active.length, is(1));
        GraphQLBody filter = active[0].getHttpWebSocketResponse().getGraphqlSubscriptionFilter();
        assertThat("graphqlSubscriptionFilter was dropped on the round trip", filter, notNullValue());
        assertThat(filter.getQuery(), is(SUBSCRIPTION_QUERY));
        assertThat(filter.getOperationName(), is("OnMessage"));
        assertThat(filter.getSelectionSetMatchType(), is(SelectionSetMatchType.AST_SUBSET));
        assertThat(filter.getFields(), contains("messageAdded"));
    }

    /**
     * A raw-JSON expectation with a <em>typeless</em> filter. This form was always permitted by the
     * schema, so it isolates the {@code GraphQLBodyDTO} creator: it fails on an unfixed server with a
     * Jackson instantiation error even though the schema is satisfied.
     *
     * <p><b>Both the write and the read-back are done over raw HTTP on purpose.</b> Going through
     * {@code MockServerClient.retrieveActiveExpectations} would re-validate the server's response
     * against the expectation schema on the client side, which drags the discriminator fix back into
     * this test and destroys the isolation — the server re-serialises the stored filter <em>with</em>
     * {@code "type":"GRAPHQL"}, so its own output is schema-invalid without that fix. That coupling is
     * real and is covered by the round-trip test above; here it would only make this test
     * non-attributable.
     */
    @Test
    public void shouldAcceptATypelessGraphqlSubscriptionFilterAsRawJson() throws Exception {
        String expectationJson = "{"
            + "\"httpRequest\":{\"method\":\"GET\",\"path\":\"/ws/graphql-raw\"},"
            + "\"httpWebSocketResponse\":{"
            + "\"subprotocol\":\"" + GRAPHQL_TRANSPORT_WS + "\","
            + "\"graphqlSubscriptionFilter\":{"
            + "\"query\":\"" + SUBSCRIPTION_QUERY + "\","
            + "\"selectionSetMatchType\":\"AST_SUBSET\"},"
            + "\"messages\":[{\"text\":\"{}\"}]}}";

        OkHttpClient http = new OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build();
        try (Response response = http.newCall(new Request.Builder()
            .url("http://localhost:" + mockServerPort + "/mockserver/expectation")
            .put(RequestBody.create(expectationJson, MediaType.parse("application/json")))
            .build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            assertThat("control plane rejected a typeless graphqlSubscriptionFilter: " + body,
                response.code(), is(201));
        }

        try (Response retrieved = http.newCall(new Request.Builder()
            .url("http://localhost:" + mockServerPort
                + "/mockserver/retrieve?type=active_expectations&format=json")
            .put(RequestBody.create("", MediaType.parse("application/json")))
            .build()).execute()) {
            String stored = retrieved.body() != null ? retrieved.body().string() : "";
            assertThat(retrieved.code(), is(200));
            assertThat("the stored expectation did not survive the round trip",
                stored, containsString("/ws/graphql-raw"));
            assertThat("graphqlSubscriptionFilter was dropped on deserialisation",
                stored, containsString("graphqlSubscriptionFilter"));
            assertThat("the filter query was not retained",
                stored, containsString("messageAdded"));
        }
    }

    /**
     * The filter, once configured over the wire, actually drives the subscription: a matching query
     * is answered with the scripted {@code next} payload followed by {@code complete}.
     */
    @Test
    public void shouldPushScriptedPayloadsWhenTheSubscriptionQueryMatchesTheFilter() throws Exception {
        mockGraphQLSubscriptionAt("/ws/graphql-match", SUBSCRIPTION_QUERY);

        GraphQLWebSocketClient client = new GraphQLWebSocketClient("/ws/graphql-match");
        client.handshakeAndSubscribe(SUBSCRIPTION_QUERY);

        assertThat("no connection_ack", client.messages,
            hasItem(containsString("\"type\":\"connection_ack\"")));
        assertThat("scripted payload was not delivered", client.messages,
            hasItem(both(containsString("\"type\":\"next\"")).and(containsString("hello"))));
        assertThat("subscription never completed", client.messages,
            hasItem(containsString("\"type\":\"complete\"")));
    }

    /**
     * The discriminating half: a query that does <em>not</em> match the configured filter must be
     * rejected with {@code error}. Without this, a server that ignored the filter entirely — or
     * silently dropped it on the round trip — would still pass the matching test above.
     */
    @Test
    public void shouldErrorWhenTheSubscriptionQueryDoesNotMatchTheFilter() throws Exception {
        mockGraphQLSubscriptionAt("/ws/graphql-mismatch", SUBSCRIPTION_QUERY);

        GraphQLWebSocketClient client = new GraphQLWebSocketClient("/ws/graphql-mismatch");
        client.handshakeAndSubscribe("subscription OnOther { userJoined { id } }");

        assertThat("a non-matching subscription was not rejected", client.messages,
            hasItem(containsString("\"type\":\"error\"")));
        assertThat("a non-matching subscription was answered with scripted payloads", client.messages,
            not(hasItem(containsString("\"type\":\"next\""))));
    }

    // ------------------------------------------------------------------
    // SSE framing, via okhttp-sse (a real WHATWG event-stream parser)
    // ------------------------------------------------------------------

    private static class RecordingEventSourceListener extends EventSourceListener {
        final CountDownLatch finished = new CountDownLatch(1);
        final List<String> data = new CopyOnWriteArrayList<>();
        final List<String> types = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            this.data.add(data);
            this.types.add(String.valueOf(type));
        }

        @Override
        public void onClosed(EventSource eventSource) {
            finished.countDown();
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            finished.countDown();
        }
    }

    private RecordingEventSourceListener consumeSse(String path) throws Exception {
        OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
        RecordingEventSourceListener listener = new RecordingEventSourceListener();
        EventSource source = EventSources.createFactory(http).newEventSource(
            new Request.Builder().url("http://localhost:" + mockServerPort + path).build(),
            listener);
        try {
            assertTrue("SSE stream never terminated", listener.finished.await(15, TimeUnit.SECONDS));
        } finally {
            source.cancel();
        }
        return listener;
    }

    /**
     * The reported defect: {@code data} was split on LF only, so a lone CR went out raw inside a
     * {@code data:} line. A real parser treats CR as a line terminator, so everything after it was
     * dropped — this test fails against the unfixed server with data "before" instead of
     * "before\nafter".
     */
    @Test
    public void shouldNotTruncateSseDataContainingALoneCarriageReturn() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/sse-cr"))
            .respondWithSse(
                HttpSseResponse.sseResponse()
                    .withEvent(SseEvent.sseEvent().withEvent("message").withData("before\rafter"))
                    .withCloseConnection(true)
            );

        RecordingEventSourceListener listener = consumeSse("/sse-cr");

        assertThat(listener.data, hasSize(1));
        assertThat("everything after a lone CR was silently dropped by the client",
            listener.data.get(0), is("before\nafter"));
    }

    @Test
    public void shouldNotTruncateSseDataContainingMixedLineTerminators() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/sse-mixed"))
            .respondWithSse(
                HttpSseResponse.sseResponse()
                    .withEvent(SseEvent.sseEvent().withEvent("message").withData("a\rb\nc\r\nd"))
                    .withCloseConnection(true)
            );

        RecordingEventSourceListener listener = consumeSse("/sse-mixed");

        assertThat(listener.data, hasSize(1));
        assertThat(listener.data.get(0), is("a\nb\nc\nd"));
    }

    /**
     * A CR inside the data must not be able to forge an additional event, which is the more
     * serious reading of the same defect.
     */
    @Test
    public void shouldNotAllowCarriageReturnInDataToInjectAnExtraEvent() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/sse-inject"))
            .respondWithSse(
                HttpSseResponse.sseResponse()
                    .withEvent(SseEvent.sseEvent().withEvent("message")
                        .withData("safe\r\rdata: injected"))
                    .withCloseConnection(true)
            );

        RecordingEventSourceListener listener = consumeSse("/sse-inject");

        assertThat("a CR in data must not be able to frame a second event",
            listener.data, hasSize(1));
        assertThat(listener.data.get(0), containsString("safe"));
    }

    @Test
    public void shouldDeliverOrdinarySseEventsUnchanged() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/sse-plain"))
            .respondWithSse(
                HttpSseResponse.sseResponse()
                    .withEvent(SseEvent.sseEvent().withEvent("message").withData("one").withId("1"))
                    .withEvent(SseEvent.sseEvent().withEvent("message").withData("two").withId("2"))
                    .withCloseConnection(true)
            );

        RecordingEventSourceListener listener = consumeSse("/sse-plain");

        assertThat(listener.data, is(List.of("one", "two")));
    }

    /** Multi-line data (the ordinary LF case) must still be rejoined by the client. */
    @Test
    public void shouldDeliverMultiLineSseDataAsASinglEvent() throws Exception {
        mockServerClient
            .when(request().withMethod("GET").withPath("/sse-multiline"))
            .respondWithSse(
                HttpSseResponse.sseResponse()
                    .withEvent(SseEvent.sseEvent().withEvent("message").withData("line1\nline2"))
                    .withCloseConnection(true)
            );

        RecordingEventSourceListener listener = consumeSse("/sse-multiline");

        assertThat(listener.data, hasSize(1));
        assertThat(listener.data.get(0), is("line1\nline2"));
    }
}
