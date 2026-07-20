package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for {@link Http3RequestBridge} conversion helpers.
 * These tests do NOT require the native QUIC transport — they exercise
 * pure data conversion between HTTP/3 frame objects and MockServer model objects.
 */
public class Http3RequestBridgeTest {

    // ---- toHttpRequest tests ----

    @Test
    public void shouldConvertBasicGetRequest() {
        HttpRequest request = Http3RequestBridge.toHttpRequest(
            "GET", "/hello", "https", "localhost:8443",
            new ArrayList<>(), new byte[0]
        );

        assertThat(request.getMethod(""), is("GET"));
        assertThat(request.getPath().getValue(), is("/hello"));
        assertThat(request.isSecure(), is(true));
        assertThat(request.getFirstHeader("host"), is("localhost:8443"));
        // HTTP/3 requests are tagged with the negotiated protocol so they can be
        // matched on / verified by protocol (the h3 ALPN is server-trusted)
        assertThat(request.getProtocol(), is(org.mockserver.model.Protocol.HTTP_3));
    }

    @Test
    public void shouldTagProtocolAsHttp3() {
        HttpRequest request = Http3RequestBridge.toHttpRequest(
            "POST", "/api", "https", "example.com",
            new ArrayList<>(), new byte[0]
        );

        assertThat(request.getProtocol(), is(org.mockserver.model.Protocol.HTTP_3));
    }

    @Test
    public void shouldParseQueryStringFromPath() {
        HttpRequest request = Http3RequestBridge.toHttpRequest(
            "GET", "/search?q=hello&page=1", "https", "example.com",
            new ArrayList<>(), new byte[0]
        );

        assertThat(request.getPath().getValue(), is("/search"));
        assertThat(request.getFirstQueryStringParameter("q"), is("hello"));
        assertThat(request.getFirstQueryStringParameter("page"), is("1"));
    }

    @Test
    public void shouldConvertPostRequestWithBody() {
        byte[] body = "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8);
        List<Map.Entry<String, String>> headers = Arrays.asList(
            new AbstractMap.SimpleImmutableEntry<>("content-type", "application/json"),
            new AbstractMap.SimpleImmutableEntry<>("content-length", String.valueOf(body.length))
        );

        HttpRequest request = Http3RequestBridge.toHttpRequest(
            "POST", "/api/items", "https", "localhost:8443",
            headers, body
        );

        assertThat(request.getMethod(""), is("POST"));
        assertThat(request.getPath().getValue(), is("/api/items"));
        assertThat(request.getBodyAsString(), is("{\"name\":\"test\"}"));
        assertThat(request.getFirstHeader("content-type"), is("application/json"));
    }

    @Test
    public void shouldHandleNullMethodAndPath() {
        HttpRequest request = Http3RequestBridge.toHttpRequest(
            null, null, null, null,
            new ArrayList<>(), new byte[0]
        );

        assertThat(request.getMethod(""), is("GET"));
        assertThat(request.getPath().getValue(), is("/"));
    }

    @Test
    public void shouldHandleEmptyPath() {
        HttpRequest request = Http3RequestBridge.toHttpRequest(
            "GET", "", "https", null,
            new ArrayList<>(), new byte[0]
        );

        assertThat(request.getPath().getValue(), is("/"));
    }

    @Test
    public void shouldPreserveMultipleHeaders() {
        List<Map.Entry<String, String>> headers = Arrays.asList(
            new AbstractMap.SimpleImmutableEntry<>("accept", "text/html"),
            new AbstractMap.SimpleImmutableEntry<>("accept-language", "en-US"),
            new AbstractMap.SimpleImmutableEntry<>("x-custom", "value1")
        );

        HttpRequest request = Http3RequestBridge.toHttpRequest(
            "GET", "/page", "https", "example.com",
            headers, new byte[0]
        );

        assertThat(request.getFirstHeader("accept"), is("text/html"));
        assertThat(request.getFirstHeader("accept-language"), is("en-US"));
        assertThat(request.getFirstHeader("x-custom"), is("value1"));
    }

    // ---- parseHeaders tests ----

    @Test
    public void shouldParsePseudoHeadersAndRegularHeaders() {
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/api/data?foo=bar");
        headersFrame.headers().scheme("https");
        headersFrame.headers().authority("example.com:443");
        headersFrame.headers().add("content-type", "application/json");
        headersFrame.headers().add("x-request-id", "abc123");

        Http3RequestBridge.ParsedHeaders parsed = Http3RequestBridge.parseHeaders(headersFrame);

        assertThat(parsed.method(), is("POST"));
        assertThat(parsed.path(), is("/api/data?foo=bar"));
        assertThat(parsed.scheme(), is("https"));
        assertThat(parsed.authority(), is("example.com:443"));
        assertThat(parsed.headers(), hasSize(2));
        assertThat(parsed.headers().get(0).getKey(), is("content-type"));
        assertThat(parsed.headers().get(0).getValue(), is("application/json"));
        assertThat(parsed.headers().get(1).getKey(), is("x-request-id"));
        assertThat(parsed.headers().get(1).getValue(), is("abc123"));
    }

    @Test
    public void shouldHandleHeadersFrameWithNoPseudoHeaders() {
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        // HTTP/3 doesn't require all pseudo-headers in every frame
        headersFrame.headers().add("x-custom", "value");

        Http3RequestBridge.ParsedHeaders parsed = Http3RequestBridge.parseHeaders(headersFrame);

        assertThat(parsed.method(), is(nullValue()));
        assertThat(parsed.path(), is(nullValue()));
        assertThat(parsed.headers(), hasSize(1));
    }

    // ---- toHttp3HeadersFrame tests ----

    @Test
    public void shouldConvertResponseToHeadersFrame() {
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", "text/plain")
            .withHeader("x-custom", "value");

        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);

        assertThat(headersFrame.headers().status().toString(), is("200"));
        assertThat(headersFrame.headers().get("server").toString(), is("mockserver-http3"));
        assertThat(headersFrame.headers().get("content-type").toString(), is("text/plain"));
        assertThat(headersFrame.headers().get("x-custom").toString(), is("value"));
    }

    @Test
    public void shouldConvertNon200StatusCode() {
        HttpResponse response = HttpResponse.response()
            .withStatusCode(404);

        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);

        assertThat(headersFrame.headers().status().toString(), is("404"));
    }

    @Test
    public void shouldFilterConnectionHeaders() {
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("connection", "close")
            .withHeader("transfer-encoding", "chunked")
            .withHeader("x-custom", "kept");

        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);

        // connection and transfer-encoding should be filtered out in HTTP/3
        assertThat(headersFrame.headers().get("connection"), is(nullValue()));
        assertThat(headersFrame.headers().get("transfer-encoding"), is(nullValue()));
        assertThat(headersFrame.headers().get("x-custom").toString(), is("kept"));
    }

    /**
     * RFC 9114 section 4.2 forbids five connection-specific fields on an HTTP/3 message and a
     * receiver MUST treat a message carrying one as malformed. Only {@code connection} and
     * {@code transfer-encoding} used to be filtered, so {@code keep-alive}, {@code upgrade} and
     * {@code proxy-connection} leaked onto the wire. {@code keep-alive} is the reachable one: in
     * proxy/forward mode the upstream response's headers are copied onto the model response
     * wholesale (only HTTP/2 extension headers are stripped), so an HTTP/1.1 origin answering with
     * {@code Keep-Alive: timeout=5, max=100} had it relayed verbatim onto an HTTP/3 response.
     */
    @Test
    public void shouldFilterEveryConnectionSpecificHeaderForbiddenByRfc9114() {
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("connection", "keep-alive")
            .withHeader("keep-alive", "timeout=5")
            .withHeader("proxy-connection", "keep-alive")
            .withHeader("transfer-encoding", "chunked")
            .withHeader("upgrade", "websocket")
            .withHeader("x-custom", "kept");

        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);

        assertThat(headersFrame.headers().get("connection"), is(nullValue()));
        assertThat(headersFrame.headers().get("keep-alive"), is(nullValue()));
        assertThat(headersFrame.headers().get("proxy-connection"), is(nullValue()));
        assertThat(headersFrame.headers().get("transfer-encoding"), is(nullValue()));
        assertThat(headersFrame.headers().get("upgrade"), is(nullValue()));
        assertThat(headersFrame.headers().get("x-custom").toString(), is("kept"));
    }

    @Test
    public void shouldAllowTeOnlyWithTrailersValue() {
        // RFC 9114 section 4.2 permits TE, but with the single value "trailers"
        HttpResponse allowed = HttpResponse.response().withStatusCode(200).withHeader("te", "trailers");
        assertThat(Http3RequestBridge.toHttp3HeadersFrame(allowed).headers().get("te").toString(), is("trailers"));

        HttpResponse forbidden = HttpResponse.response().withStatusCode(200).withHeader("te", "gzip");
        assertThat(Http3RequestBridge.toHttp3HeadersFrame(forbidden).headers().get("te"), is(nullValue()));
    }

    @Test
    public void shouldDropContentLengthOnStreamingResponsesOnly() {
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-length", "1234")
            .withHeader("x-custom", "kept");

        // a streamed body's length is unknown at header time, and a stale one (e.g. copied from a
        // relayed upstream response) describes a different body than the one actually streamed
        DefaultHttp3HeadersFrame streaming = Http3RequestBridge.toHttp3HeadersFrame(response, true);
        assertThat(streaming.headers().get("content-length"), is(nullValue()));
        assertThat(streaming.headers().get("x-custom").toString(), is("kept"));

        // a non-streaming response keeps its content-length
        DefaultHttp3HeadersFrame nonStreaming = Http3RequestBridge.toHttp3HeadersFrame(response, false);
        assertThat(nonStreaming.headers().get("content-length").toString(), is("1234"));
    }

    // ---- locale-independent header case folding ----
    //
    // These run under a Turkish default locale, where 'I'.toLowerCase() is the DOTLESS 'ı'
    // (U+0131) rather than 'i'. A locale-sensitive fold therefore turns "CONNECTION" into
    // "connectıon", which fails the equals check that is supposed to drop it, and emits both a
    // forbidden Connection header (malformed per RFC 9114 section 4.2) and a non-ASCII field
    // name. The header names below are deliberately upper-case: the existing
    // shouldFilterConnectionHeaders test uses already-lower-case names and so cannot detect this.

    /**
     * Covers the interaction between the RFC 9114 forbidden-header filter and locale-independent
     * folding — something neither change could test on its own.
     * {@link #shouldFilterEveryConnectionSpecificHeaderForbiddenByRfc9114()} names its headers in
     * lower case, so it is immune to the fold and passes under any locale; the fold tests named
     * only {@code CONNECTION} and {@code TRANSFER-ENCODING}. But <strong>four</strong> of the six
     * forbidden field names contain an {@code I} and so are locale-exposed — {@code CONNECTION},
     * {@code KEEP-ALIVE}, {@code PROXY-CONNECTION} and {@code TRANSFER-ENCODING} fold to
     * {@code connectıon}, {@code keep-alıve}, {@code proxy-connectıon} and {@code transfer-encodıng}
     * under a Turkish locale, bypassing the filter entirely. ({@code UPGRADE} and {@code TE} have
     * no {@code I} and are safe either way.) All six are asserted here in upper case so the filter
     * is exercised against the fold rather than around it.
     */
    @Test
    public void shouldFilterUpperCaseConnectionHeadersUnderATurkishLocale() {
        withDefaultLocale(new Locale("tr", "TR"), () -> {
            HttpResponse response = HttpResponse.response()
                .withStatusCode(200)
                .withHeader("CONNECTION", "close")
                .withHeader("KEEP-ALIVE", "timeout=5")
                .withHeader("PROXY-CONNECTION", "keep-alive")
                .withHeader("TRANSFER-ENCODING", "chunked")
                .withHeader("UPGRADE", "websocket")
                .withHeader("TE", "gzip")
                .withHeader("X-CUSTOM", "kept");

            DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);

            // Two weaker assertions were tried here first and BOTH passed vacuously under the
            // mutation, so the exact emitted set is asserted instead:
            //   get("connection") == null       -- the header leaks as "connectıon", so a lookup
            //                                      for the correct spelling misses either way;
            //   no name ROOT-folds to "connection" -- "connectıon" does not fold back to
            //                                      "connection", so the mangled name slips through.
            // Only enumerating what was actually emitted catches a leak under ANY spelling.
            assertThat(emittedHeaderNames(headersFrame), containsInAnyOrder("server", "x-custom"));
            assertThat(headersFrame.headers().get("x-custom").toString(), is("kept"));
        });
    }

    /** Non-pseudo header field names actually emitted on the frame, in wire order. */
    private static List<String> emittedHeaderNames(DefaultHttp3HeadersFrame frame) {
        List<String> names = new ArrayList<>();
        frame.headers().forEach(entry -> {
            String name = entry.getKey().toString();
            if (!name.startsWith(":")) {
                names.add(name);
            }
        });
        return names;
    }

    @Test
    public void shouldNotEmitANonAsciiHeaderNameUnderATurkishLocale() {
        // Stated separately from the filter assertion: a fold that mangles the name of a header
        // which is NOT filtered corrupts the field name without tripping any equals check.
        withDefaultLocale(new Locale("tr", "TR"), () -> {
            HttpResponse response = HttpResponse.response()
                .withStatusCode(200)
                .withHeader("X-REQUEST-ID", "abc");

            DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);

            assertThat(headersFrame.headers().get("x-request-id").toString(), is("abc"));
            assertThat(headersFrame.headers().get("x-request-ıd"), is(nullValue()));
        });
    }

    @Test
    public void shouldNotEmitANonAsciiTrailerNameUnderATurkishLocale() {
        withDefaultLocale(new Locale("tr", "TR"), () -> {
            HttpResponse response = HttpResponse.response()
                .withStatusCode(200)
                .withTrailer("X-CHECKSUM-ID", "9f8e");

            DefaultHttp3HeadersFrame trailersFrame = Http3RequestBridge.toHttp3TrailersFrame(response);

            assertThat(trailersFrame, is(notNullValue()));
            assertThat(trailersFrame.headers().get("x-checksum-id").toString(), is("9f8e"));
            assertThat(trailersFrame.headers().get("x-checksum-ıd"), is(nullValue()));
        });
    }

    /**
     * Runs {@code body} with {@code locale} as the JVM default, always restoring the previous one.
     *
     * <p><strong>Depends on mockserver-netty running tests sequentially.</strong>
     * {@link Locale#setDefault} is process-wide, so although this method restores the previous
     * value, a concurrently-running test would observe the Turkish locale while {@code body} runs.
     * This is safe today only because the mockserver-netty surefire configuration declares no
     * {@code <parallel>} (unlike mockserver-core, which runs {@code parallel=classes}). If netty
     * ever gains a parallel phase, move these tests to the sequential phase — they would otherwise
     * start corrupting unrelated tests silently rather than failing.</p>
     *
     * <p>The surefire fork also pins {@code -Duser.language=en -Duser.country=GB}, so the ambient
     * default can never exercise this path; overriding it here is the only way to reach it.</p>
     */
    private static void withDefaultLocale(Locale locale, Runnable body) {
        Locale previous = Locale.getDefault();
        Locale.setDefault(locale);
        try {
            body.run();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void shouldDefaultToStatus200WhenNull() {
        HttpResponse response = HttpResponse.response();
        // statusCode defaults to 200

        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);

        assertThat(headersFrame.headers().status().toString(), is("200"));
    }

    // ---- toHttp3DataFrame tests ----

    @Test
    public void shouldConvertResponseBodyToDataFrame() {
        HttpResponse response = HttpResponse.response()
            .withBody("Hello, HTTP/3!");

        DefaultHttp3DataFrame dataFrame = Http3RequestBridge.toHttp3DataFrame(response);

        assertThat(dataFrame, is(notNullValue()));
        ByteBuf content = dataFrame.content();
        assertThat(content.toString(StandardCharsets.UTF_8), is("Hello, HTTP/3!"));
        content.release();
    }

    @Test
    public void shouldReturnNullDataFrameForEmptyBody() {
        HttpResponse response = HttpResponse.response()
            .withStatusCode(204);

        DefaultHttp3DataFrame dataFrame = Http3RequestBridge.toHttp3DataFrame(response);

        assertThat(dataFrame, is(nullValue()));
    }

    // ---- body accumulation tests ----

    @Test
    public void shouldAccumulateMultipleDataFrames() {
        CompositeByteBuf composite = Unpooled.compositeBuffer();

        DefaultHttp3DataFrame frame1 = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer("Hello, ".getBytes(StandardCharsets.UTF_8))
        );
        DefaultHttp3DataFrame frame2 = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer("World!".getBytes(StandardCharsets.UTF_8))
        );

        Http3RequestBridge.accumulateBody(composite, frame1);
        Http3RequestBridge.accumulateBody(composite, frame2);

        byte[] body = Http3RequestBridge.readAccumulatedBody(composite);
        assertThat(new String(body, StandardCharsets.UTF_8), is("Hello, World!"));

        frame1.release();
        frame2.release();
        composite.release();
    }

    @Test
    public void shouldHandleEmptyAccumulation() {
        CompositeByteBuf composite = Unpooled.compositeBuffer();

        byte[] body = Http3RequestBridge.readAccumulatedBody(composite);
        assertThat(body, is(notNullValue()));
        assertThat(body.length, is(0));

        composite.release();
    }

    // ---- round-trip test ----

    @Test
    public void shouldRoundTripRequestAndResponse() {
        // simulate an HTTP/3 request arriving as headers + body
        byte[] requestBody = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        List<Map.Entry<String, String>> requestHeaders = Arrays.asList(
            new AbstractMap.SimpleImmutableEntry<>("content-type", "application/json"),
            new AbstractMap.SimpleImmutableEntry<>("x-trace-id", "trace-001")
        );

        HttpRequest request = Http3RequestBridge.toHttpRequest(
            "PUT", "/api/resource/42?version=2", "https", "api.example.com:443",
            requestHeaders, requestBody
        );

        // verify the request was correctly built
        assertThat(request.getMethod(""), is("PUT"));
        assertThat(request.getPath().getValue(), is("/api/resource/42"));
        assertThat(request.getFirstQueryStringParameter("version"), is("2"));
        assertThat(request.getFirstHeader("host"), is("api.example.com:443"));
        assertThat(request.getFirstHeader("content-type"), is("application/json"));
        assertThat(request.getFirstHeader("x-trace-id"), is("trace-001"));
        assertThat(request.getBodyAsString(), is("{\"key\":\"value\"}"));

        // create a response
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"status\":\"ok\"}");

        // convert back to HTTP/3 frames
        DefaultHttp3HeadersFrame responseHeaders = Http3RequestBridge.toHttp3HeadersFrame(response);
        DefaultHttp3DataFrame responseData = Http3RequestBridge.toHttp3DataFrame(response);

        assertThat(responseHeaders.headers().status().toString(), is("200"));
        assertThat(responseHeaders.headers().get("content-type").toString(), is("application/json"));
        assertThat(responseData, is(notNullValue()));
        assertThat(responseData.content().toString(StandardCharsets.UTF_8), is("{\"status\":\"ok\"}"));

        responseData.content().release();
    }
}
