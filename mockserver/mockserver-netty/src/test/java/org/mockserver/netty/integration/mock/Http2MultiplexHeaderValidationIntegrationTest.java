package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that a request carrying an unusual header VALUE — one the connection-adapter HTTP/2
 * path accepts and records — is likewise accepted (not reset) on the HTTP/2 <em>multiplex</em> pipeline
 * (gRPC bidi streaming on + a startup-loaded gRPC descriptor with services, the same enablement as
 * {@link Http2MultiplexConnectionScopeIntegrationTest}).
 * <p>
 * MockServer is a mock server: users deliberately drive malformed traffic through it to test how their
 * own clients behave. The connection-adapter path builds its inbound adapter with
 * {@code validateHttpHeaders(false)}, so a header value with a leading space, an embedded DEL (0x7F) or a
 * control character is recorded and matchable. The multiplex path constructed its stream-frame codec as
 * {@code new Http2StreamFrameToHttpObjectCodec(true)} — where the single boolean is {@code isServer},
 * leaving {@code validateHeaders} at its default {@code true}. That validation, applied in the HTTP/1
 * object conversion ({@code HttpConversionUtil.toHttpRequest}), throws on such a value and the stream is
 * reset with {@code RST_STREAM(PROTOCOL_ERROR)} — the request never reaches the matchers and nothing is
 * logged as received. The fix constructs the codec as {@code new Http2StreamFrameToHttpObjectCodec(true,
 * false)} so inbound validation is off, matching the connection-adapter path.
 * <p>
 * A raw in-JVM Netty h2c multiplex client is used so the malformed value is put on the wire verbatim
 * (a normal client library would reject or normalise it first). The value used is an embedded DEL
 * (0x7F), which is legal in an HTTP/2 field value but rejected by the HTTP/1 value rules.
 */
public class Http2MultiplexHeaderValidationIntegrationTest {

    private static final String GRPC_DESCRIPTOR_DIRECTORY = "../mockserver-core/src/test/resources/grpc";

    // Legal in an HTTP/2 field value (HTTP/2 forbids only NUL/CR/LF), rejected by the HTTP/1 value rules
    // (an octet == 0x7F is not permitted) — so it survives the HTTP/2 layer and only the HTTP/1-object
    // conversion validation trips on it.
    private static final String MALFORMED_HEADER_VALUE = "abc\u007Fdef";

    // A header NAME carrying the same DEL (0x7F). A control character is illegal in an HTTP field name, so
    // Netty's header-name validator rejects it when the frame reader has validateHeaders on (the default, and
    // what every OTHER MockServer path uses) -- the multiplex frame codec's validateHeaders(false) is the only
    // reason it is accepted here. The surrounding "x-...name" is an ordinary lowercase token, keeping this
    // clear of the separate, UNCONDITIONAL HTTP/2 checks (lowercase-name enforcement, connection-specific-name
    // ban) that validateHeaders(false) does NOT relax, so the test isolates the name-validation change.
    private static final String MALFORMED_HEADER_NAME = "x-malformedname";

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServer() {
        Configuration configuration = configuration()
            .grpcBidiStreamingEnabled(true)
            .grpcDescriptorDirectory(GRPC_DESCRIPTOR_DIRECTORY);
        mockServer = new MockServer(configuration);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        // match on path only: the point is the request is RECEIVED and matchable, not reset before it
        // ever reaches the matchers.
        mockServerClient.upsert(
            new Expectation(
                request()
                    .withMethod("GET")
                    .withPath("/http2_multiplex_malformed_header")
            ).thenRespond(
                response().withStatusCode(200).withBody("received_malformed_header")
            )
        );
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }


    /**
     * The whole point of {@link #MALFORMED_HEADER_VALUE} is the DEL (0x7F) it carries: Netty's
     * {@code HttpHeaderValidationUtil.validateValidHeaderValue} rejects 0x7F, so without it this
     * test would pass under BOTH strict and lenient inbound settings and prove nothing.
     * <p>
     * DEL is non-printing, so a raw byte here reads as "abcdef" in grep/diff/review output - that
     * exact misreading once produced a false review finding that the test was a no-op. This asserts
     * the character mechanically so the escape form cannot silently degrade to something inert.
     */
    @Test
    public void malformedHeaderValueMustActuallyCarryTheDelCharacter() {
        assertThat("MALFORMED_HEADER_VALUE must contain DEL (0x7F) or this test proves nothing",
            (int) MALFORMED_HEADER_VALUE.charAt(3), is(0x7F));
    }

    /**
     * Same mechanical guard as {@link #malformedHeaderValueMustActuallyCarryTheDelCharacter()} but for the
     * header NAME: the DEL (0x7F) is non-printing, so {@link #MALFORMED_HEADER_NAME} reads as
     * "x-malformedname" in grep/diff/review output. Assert the byte is there so the name-dimension test below
     * cannot silently degrade to a well-formed name that would pass under strict validation and prove nothing.
     */
    @Test
    public void malformedHeaderNameMustActuallyCarryTheDelCharacter() {
        assertThat("MALFORMED_HEADER_NAME must contain DEL (0x7F) or this test proves nothing",
            (int) MALFORMED_HEADER_NAME.charAt("x-malformed".length()), is(0x7F));
    }

    /**
     * The name-dimension counterpart to {@link #shouldAcceptUnusualHeaderValueOnTheMultiplexPath()}. Disabling
     * frame-reader validation to keep unusual header VALUES working (see PortUnificationHandler) also disables
     * inbound header-NAME validation, because Netty folds both under one flag with no names-only control. That
     * is the half that actually CHANGES behaviour on the multiplex path, so it gets equal coverage: a header
     * whose NAME carries a DEL (0x7F) — rejected at frame-decode with strict validation — must be accepted,
     * matched (on path), and recorded with the name intact through to the event log.
     */
    @Test(timeout = 30000)
    public void shouldAcceptUnusualHeaderNameOnTheMultiplexPath() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel parent = connectMultiplexParent(group);

            CompletableFuture<Response> responseFuture = new CompletableFuture<>();
            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(new ResponseCollector(responseFuture))
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers(false)
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + mockServer.getLocalPort())
                .path("/http2_multiplex_malformed_header");
            headers.set(MALFORMED_HEADER_NAME, "present");
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            Response response = responseFuture.get(15, TimeUnit.SECONDS);

            assertThat("status", response.status, is("200"));
            assertThat(response.body, is("received_malformed_header"));

            HttpRequest[] recorded = mockServerClient.retrieveRecordedRequests(
                request().withPath("/http2_multiplex_malformed_header"));
            assertThat("recorded request count", recorded.length, is(1));
            assertThat("recorded header NAME preserved intact (embedded DEL 0x7F)",
                recorded[0].getFirstHeader(MALFORMED_HEADER_NAME), is("present"));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
    @Test(timeout = 30000)
    public void shouldAcceptUnusualHeaderValueOnTheMultiplexPath() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel parent = connectMultiplexParent(group);

            CompletableFuture<Response> responseFuture = new CompletableFuture<>();
            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(new ResponseCollector(responseFuture))
                .open()
                .sync()
                .getNow();

            // Build the inbound headers with validation disabled (new DefaultHttp2Headers(false)) so the
            // DEL (0x7F) value can be injected verbatim, exactly as the real lenient inbound path delivers
            // it: the shared-connection decoder sets validateHttpHeaders(false). The no-arg constructor
            // enables validation, and as of netty-codec-http2 4.2.18 that validation now rejects 0x7F
            // header values (earlier versions validated only names) — which would throw here at setup,
            // before the server-side conversion under test ever runs.
            Http2Headers headers = new DefaultHttp2Headers(false)
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + mockServer.getLocalPort())
                .path("/http2_multiplex_malformed_header");
            headers.set("x-malformed-value", MALFORMED_HEADER_VALUE);
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            // Before the fix the server rejects the value in the HTTP/1 conversion and replies
            // RST_STREAM(PROTOCOL_ERROR); the collector completes exceptionally (via Http2ResetFrame or
            // stream close) so this get() fails fast rather than hanging to the @Test timeout. After the
            // fix the request is received, matched on path, and answered 200.
            Response response = responseFuture.get(15, TimeUnit.SECONDS);

            assertThat("status", response.status, is("200"));
            assertThat(response.body, is("received_malformed_header"));

            // Accepted-and-matched is necessary but not sufficient: prove the value is RECORDED INTACT,
            // with the DEL (0x7F) byte preserved end to end, which is the point of the lenient inbound
            // path (users record malformed traffic to test their own clients). If frame-decode validation
            // had merely been relaxed enough to avoid the reset but the value were normalised/stripped en
            // route to the event log, this assertion would catch it.
            HttpRequest[] recorded = mockServerClient.retrieveRecordedRequests(
                request().withPath("/http2_multiplex_malformed_header"));
            assertThat("recorded request count", recorded.length, is(1));
            assertThat("recorded header value preserved intact (embedded DEL 0x7F)",
                recorded[0].getFirstHeader("x-malformed-value"), is(MALFORMED_HEADER_VALUE));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private Channel connectMultiplexParent(NioEventLoopGroup group) throws Exception {
        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                    ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                        }
                    }));
                }
            });
        return bootstrap.connect("localhost", mockServer.getLocalPort()).sync().channel();
    }

    private static final class Response {
        final String status;
        final String body;

        Response(String status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class ResponseCollector extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<Response> done;
        private final StringBuilder body = new StringBuilder();
        private volatile String status;

        ResponseCollector(CompletableFuture<Response> done) {
            this.done = done;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                    CharSequence s = headersFrame.headers().status();
                    if (s != null) {
                        status = s.toString();
                    }
                    if (headersFrame.isEndStream()) {
                        done.complete(new Response(status, body.toString()));
                    }
                } else if (msg instanceof Http2DataFrame) {
                    Http2DataFrame dataFrame = (Http2DataFrame) msg;
                    body.append(dataFrame.content().toString(StandardCharsets.UTF_8));
                    if (dataFrame.isEndStream()) {
                        done.complete(new Response(status, body.toString()));
                    }
                } else if (msg instanceof Http2ResetFrame) {
                    // the server rejected the request and reset the stream (the pre-fix behaviour)
                    done.completeExceptionally(new IllegalStateException(
                        "stream was reset with RST_STREAM errorCode=" + ((Http2ResetFrame) msg).errorCode()
                            + " - the malformed header value was rejected instead of being received"));
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            done.completeExceptionally(new IllegalStateException(
                "stream channel closed before a response was received (request was likely reset)"));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            done.completeExceptionally(cause);
        }
    }
}
