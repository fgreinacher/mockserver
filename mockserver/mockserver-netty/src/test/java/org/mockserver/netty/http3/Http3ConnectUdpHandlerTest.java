package org.mockserver.netty.http3;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.configuration.Configuration.configuration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link Http3ConnectUdpHandler}.
 * <p>
 * These tests use an {@link EmbeddedChannel} so they do not require the native
 * QUIC transport and will run on all platforms.
 */
public class Http3ConnectUdpHandlerTest {

    @Test
    public void shouldPassThroughNonConnectRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // Send a normal GET request
        DefaultHttp3HeadersFrame getHeaders = new DefaultHttp3HeadersFrame();
        getHeaders.headers().method("GET");
        getHeaders.headers().path("/hello");
        getHeaders.headers().scheme("https");
        getHeaders.headers().authority("example.com");

        channel.writeInbound(getHeaders);

        // The handler should NOT have written any outbound response
        assertNull("should not write outbound for non-CONNECT", channel.readOutbound());

        // The frame should have been passed to the next handler (inbound)
        Http3HeadersFrame passedThrough = channel.readInbound();
        assertNotNull("GET request should pass through to the next handler", passedThrough);
        assertThat("method should be GET", passedThrough.headers().method().toString(), is("GET"));
        assertThat("path should be /hello", passedThrough.headers().path().toString(), is("/hello"));

        channel.finish();
    }

    @Test
    public void shouldPassThroughPostRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        DefaultHttp3HeadersFrame postHeaders = new DefaultHttp3HeadersFrame();
        postHeaders.headers().method("POST");
        postHeaders.headers().path("/api/data");
        postHeaders.headers().scheme("https");
        postHeaders.headers().authority("example.com");

        channel.writeInbound(postHeaders);

        // POST should pass through
        assertNull("should not write outbound for POST", channel.readOutbound());
        Http3HeadersFrame passedThrough = channel.readInbound();
        assertNotNull("POST request should pass through", passedThrough);
        assertThat("method should be POST", passedThrough.headers().method().toString(), is("POST"));

        channel.finish();
    }

    @Test
    public void shouldPassThroughPlainConnectWithoutProtocol() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // Plain CONNECT (no :protocol) -- should pass through to mock handler
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().authority("target.example.com:443");

        channel.writeInbound(connectHeaders);

        // Plain CONNECT should pass through (not handled by MASQUE handler)
        assertNull("should not write outbound for plain CONNECT", channel.readOutbound());
        Http3HeadersFrame passedThrough = channel.readInbound();
        assertNotNull("plain CONNECT should pass through to the next handler", passedThrough);
        assertThat("method should be CONNECT", passedThrough.headers().method().toString(), is("CONNECT"));

        channel.finish();
    }

    @Test
    public void shouldPassThroughDataFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // Data frames before tunnel is established should pass through
        DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
            io.netty.buffer.Unpooled.wrappedBuffer("test data".getBytes(StandardCharsets.UTF_8))
        );

        channel.writeInbound(dataFrame);

        assertNull("should not write outbound for data frame", channel.readOutbound());
        Http3DataFrame passedThrough = channel.readInbound();
        assertNotNull("data frame should pass through", passedThrough);
        String content = passedThrough.content().toString(StandardCharsets.UTF_8);
        assertThat("content should match", content, is("test data"));
        passedThrough.release();

        channel.finish();
    }

    @Test
    public void shouldRejectConnectUdpWithMissingAuthority() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // Extended CONNECT with :protocol=connect-udp but no :authority
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        // no :authority set

        channel.writeInbound(connectHeaders);

        // Should get a 400 error response
        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 400",
            responseHeaders.headers().status().toString(), is("400"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        String body = responseBody.content().toString(StandardCharsets.UTF_8);
        assertThat("body should explain missing authority",
            body, containsString("Missing :authority"));
        responseBody.release();

        channel.finish();
    }

    @Test
    public void shouldRejectConnectUdpWithInvalidAuthority() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // Extended CONNECT with invalid authority (no port)
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        connectHeaders.headers().authority("no-port-here");

        channel.writeInbound(connectHeaders);

        // Should get a 400 error response
        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 400",
            responseHeaders.headers().status().toString(), is("400"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        String body = responseBody.content().toString(StandardCharsets.UTF_8);
        assertThat("body should explain invalid authority",
            body, containsString("Invalid :authority"));
        responseBody.release();

        channel.finish();
    }

    // ---- allowlist + SSRF rejection tests (EmbeddedChannel, no native QUIC needed) ----

    @Test
    public void shouldRejectConnectUdpTargetNotInAllowlist() {
        Configuration config = configuration()
            .http3ConnectUdpAllowedTargets("allowed.example.com:443");
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(config));

        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        connectHeaders.headers().authority("other.example.com:443");

        channel.writeInbound(connectHeaders);

        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 403 for a non-allowlisted target",
            responseHeaders.headers().status().toString(), is("403"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        String allowlistBody = responseBody.content().toString(StandardCharsets.UTF_8);
        assertThat("body should be the generic refusal (no internal detail leaked)",
            allowlistBody, containsString("CONNECT-UDP target not permitted"));
        assertThat("body must NOT leak the allowlist property name or target host",
            allowlistBody, not(anyOf(containsString("http3ConnectUdpAllowedTargets"), containsString("other.example.com"))));
        responseBody.release();

        channel.finish();
    }

    @Test
    public void shouldRejectUnresolvableConnectUdpTarget() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration()));

        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        // .invalid is a reserved TLD (RFC 6761) that never resolves
        connectHeaders.headers().authority("nonexistent.invalid:443");

        channel.writeInbound(connectHeaders);

        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 403 for an unresolvable target",
            responseHeaders.headers().status().toString(), is("403"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        assertThat("body should be the generic refusal",
            responseBody.content().toString(StandardCharsets.UTF_8),
            containsString("CONNECT-UDP target not permitted"));
        responseBody.release();

        channel.finish();
    }

    @Test
    public void shouldRejectConnectUdpToLoopbackWhenSsrfBlockingEnabled() {
        Configuration config = configuration()
            .forwardProxyBlockPrivateNetworks(true);
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(config));

        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        connectHeaders.headers().authority("127.0.0.1:53");

        channel.writeInbound(connectHeaders);

        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 403 for a blocked loopback target",
            responseHeaders.headers().status().toString(), is("403"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        String ssrfBody = responseBody.content().toString(StandardCharsets.UTF_8);
        assertThat("body should be the generic refusal (identical to the allowlist refusal)",
            ssrfBody, containsString("CONNECT-UDP target not permitted"));
        assertThat("body must NOT leak the blocked host or reason",
            ssrfBody, not(anyOf(containsString("127.0.0.1"), containsString("loopback"), containsString("blocked"))));
        responseBody.release();

        channel.finish();
    }

    // ---- matchesAllowlist tests ----

    @Test
    public void shouldMatchExactHostAndPort() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist("api.example.com:443", "api.example.com", 443));
    }

    @Test
    public void shouldMatchHostEntryOnAnyPort() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist("api.example.com", "api.example.com", 8080));
    }

    @Test
    public void shouldMatchCaseInsensitively() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist("API.Example.COM:443", "api.example.com", 443));
    }

    @Test
    public void shouldNotMatchWhenPortDiffers() {
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist("api.example.com:443", "api.example.com", 8443));
    }

    @Test
    public void shouldNotMatchWhenHostDiffers() {
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist("api.example.com:443", "evil.example.com", 443));
    }

    @Test
    public void shouldMatchOneOfSeveralCommaSeparatedEntries() {
        String allowlist = "a.example.com:53, b.example.com, [::1]:9090";
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist(allowlist, "b.example.com", 12345));
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist(allowlist, "a.example.com", 53));
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist(allowlist, "a.example.com", 54));
    }

    @Test
    public void shouldMatchBracketedIpv6EntryWithPort() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist("[::1]:9090", "::1", 9090));
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist("[::1]:9090", "::1", 9091));
    }

    @Test
    public void shouldIgnoreBlankEntries() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist(" , api.example.com:443 , ", "api.example.com", 443));
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist(" , , ", "api.example.com", 443));
    }

    // ---- parseAuthority tests ----

    @Test
    public void shouldParseIpv4Authority() {
        InetSocketAddress addr = Http3ConnectUdpHandler.parseAuthority("127.0.0.1:8080");
        assertNotNull("should parse IPv4 authority", addr);
        assertThat("host", addr.getHostString(), is("127.0.0.1"));
        assertThat("port", addr.getPort(), is(8080));
    }

    @Test
    public void shouldParseHostnameAuthority() {
        InetSocketAddress addr = Http3ConnectUdpHandler.parseAuthority("example.com:443");
        assertNotNull("should parse hostname authority", addr);
        assertThat("host", addr.getHostString(), is("example.com"));
        assertThat("port", addr.getPort(), is(443));
    }

    @Test
    public void shouldParseIpv6Authority() {
        InetSocketAddress addr = Http3ConnectUdpHandler.parseAuthority("[::1]:9090");
        assertNotNull("should parse IPv6 authority", addr);
        // InetSocketAddress normalizes "::1" to its expanded form
        assertThat("host should be an IPv6 loopback",
            addr.getHostString(), anyOf(is("::1"), is("0:0:0:0:0:0:0:1")));
        assertThat("port", addr.getPort(), is(9090));
    }

    @Test
    public void shouldReturnNullForMissingPort() {
        assertNull("no port", Http3ConnectUdpHandler.parseAuthority("example.com"));
    }

    @Test
    public void shouldReturnNullForEmptyAuthority() {
        assertNull("empty", Http3ConnectUdpHandler.parseAuthority(""));
        assertNull("null", Http3ConnectUdpHandler.parseAuthority(null));
    }

    @Test
    public void shouldReturnNullForInvalidPort() {
        assertNull("port 0", Http3ConnectUdpHandler.parseAuthority("host:0"));
        assertNull("port 99999", Http3ConnectUdpHandler.parseAuthority("host:99999"));
        assertNull("port abc", Http3ConnectUdpHandler.parseAuthority("host:abc"));
    }

    @Test
    public void shouldReturnNullForMalformedIpv6() {
        assertNull("no closing bracket", Http3ConnectUdpHandler.parseAuthority("[::1:8080"));
        assertNull("no port after bracket", Http3ConnectUdpHandler.parseAuthority("[::1]"));
    }

    // ---- escapeJsonString tests ----

    @Test
    public void shouldEscapeDoubleQuotes() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("say \"hello\""),
            is("say \\\"hello\\\""));
    }

    @Test
    public void shouldEscapeBackslash() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("path\\to\\file"),
            is("path\\\\to\\\\file"));
    }

    @Test
    public void shouldEscapeNewlineAndCarriageReturn() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("line1\nline2\rline3"),
            is("line1\\nline2\\rline3"));
    }

    @Test
    public void shouldEscapeTabBackspaceFormfeed() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("a\tb\bc\f"),
            is("a\\tb\\bc\\f"));
    }

    @Test
    public void shouldEscapeControlCharactersBelowU0020() {
        // NUL (0x00) and BEL (0x07) should be escaped as \\u0000 and \\u0007
        assertThat(Http3ConnectUdpHandler.escapeJsonString("\0\u0007"),
            is("\\u0000\\u0007"));
    }

    @Test
    public void shouldReturnEmptyStringForNull() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString(null), is(""));
    }

    @Test
    public void shouldNotEscapePlainText() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("plain text 123"),
            is("plain text 123"));
    }

    @Test
    public void shouldEscapeMixedContent() {
        // Simulates a real error message that might contain backslash and quotes
        String input = "Connection refused: \"target\" at C:\\path\\host";
        String expected = "Connection refused: \\\"target\\\" at C:\\\\path\\\\host";
        assertThat(Http3ConnectUdpHandler.escapeJsonString(input), is(expected));
    }
}
