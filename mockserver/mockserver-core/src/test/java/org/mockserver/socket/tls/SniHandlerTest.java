package org.mockserver.socket.tls;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Protocol;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Tests for {@link SniHandler} — lookup behaviour, static certificate retrieval,
 * and ALPN protocol resolution using EmbeddedChannel (no real network).
 */
public class SniHandlerTest {

    private static final AttributeKey<SSLEngine> UPSTREAM_SSL_ENGINE = AttributeKey.valueOf("UPSTREAM_SSL_ENGINE");
    private static final AttributeKey<SslHandler> UPSTREAM_SSL_HANDLER = AttributeKey.valueOf("UPSTREAM_SSL_HANDLER");
    private static final AttributeKey<Certificate[]> UPSTREAM_CLIENT_CERTIFICATES = AttributeKey.valueOf("UPSTREAM_CLIENT_CERTIFICATES");
    private static final AttributeKey<Protocol> NEGOTIATED_APPLICATION_PROTOCOL = AttributeKey.valueOf("NEGOTIATED_APPLICATION_PROTOCOL");

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    /**
     * Creates an EmbeddedChannel with a dummy handler so that pipeline().firstContext() is non-null.
     */
    private EmbeddedChannel channelWithDummyHandler() {
        return new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    }

    /**
     * Returns a valid ChannelHandlerContext from the channel's pipeline.
     */
    private ChannelHandlerContext contextOf(EmbeddedChannel channel) {
        return channel.pipeline().firstContext();
    }

    // ---- lookup ----

    @Test
    public void lookupShouldAddNonBlankHostnameToSubjectAlternativeNames() {
        // given
        Configuration configuration = configuration();
        NettySslContextFactory nettySslContextFactory = new NettySslContextFactory(configuration, new MockServerLogger(), true);
        SniHandler sniHandler = new SniHandler(configuration, nettySslContextFactory);
        EmbeddedChannel channel = new EmbeddedChannel(sniHandler);
        ChannelHandlerContext ctx = channel.pipeline().context(sniHandler);

        // when
        sniHandler.lookup(ctx, "example.com");

        // then — hostname should have been added as a SAN domain
        assertThat(configuration.sslSubjectAlternativeNameDomains(), hasItem("example.com"));
        channel.close();
    }

    @Test
    public void lookupShouldNotAddBlankHostname() {
        // given
        Configuration configuration = configuration();
        NettySslContextFactory nettySslContextFactory = new NettySslContextFactory(configuration, new MockServerLogger(), true);
        SniHandler sniHandler = new SniHandler(configuration, nettySslContextFactory);
        EmbeddedChannel channel = new EmbeddedChannel(sniHandler);
        ChannelHandlerContext ctx = channel.pipeline().context(sniHandler);

        int domainCountBefore = configuration.sslSubjectAlternativeNameDomains().size();

        // when
        sniHandler.lookup(ctx, "");

        // then — no new domain added
        assertThat(configuration.sslSubjectAlternativeNameDomains().size(), is(domainCountBefore));
        channel.close();
    }

    @Test
    public void lookupShouldNotAddNullHostname() {
        // given
        Configuration configuration = configuration();
        NettySslContextFactory nettySslContextFactory = new NettySslContextFactory(configuration, new MockServerLogger(), true);
        SniHandler sniHandler = new SniHandler(configuration, nettySslContextFactory);
        EmbeddedChannel channel = new EmbeddedChannel(sniHandler);
        ChannelHandlerContext ctx = channel.pipeline().context(sniHandler);

        int domainCountBefore = configuration.sslSubjectAlternativeNameDomains().size();

        // when
        sniHandler.lookup(ctx, null);

        // then
        assertThat(configuration.sslSubjectAlternativeNameDomains().size(), is(domainCountBefore));
        channel.close();
    }

    @Test
    public void lookupShouldReturnSucceededFutureWithSslContext() throws Exception {
        // given
        Configuration configuration = configuration();
        NettySslContextFactory nettySslContextFactory = new NettySslContextFactory(configuration, new MockServerLogger(), true);
        SniHandler sniHandler = new SniHandler(configuration, nettySslContextFactory);
        EmbeddedChannel channel = new EmbeddedChannel(sniHandler);
        ChannelHandlerContext ctx = channel.pipeline().context(sniHandler);

        // when — provisioning now runs off the event loop and completes the future asynchronously (C5).
        // await() cannot be called on the EmbeddedChannel's own executor thread, so poll isDone().
        io.netty.util.concurrent.Future<SslContext> result = sniHandler.lookup(ctx, "example.com");

        // then
        long deadline = System.currentTimeMillis() + java.util.concurrent.TimeUnit.SECONDS.toMillis(30);
        while (!result.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(result.isSuccess(), is(true));
        assertThat(result.getNow(), is(notNullValue()));
        channel.close();
    }

    @Test
    public void lookupShouldAddIpAddressAsSubjectAlternativeNameIp() {
        // given
        Configuration configuration = configuration();
        NettySslContextFactory nettySslContextFactory = new NettySslContextFactory(configuration, new MockServerLogger(), true);
        SniHandler sniHandler = new SniHandler(configuration, nettySslContextFactory);
        EmbeddedChannel channel = new EmbeddedChannel(sniHandler);
        ChannelHandlerContext ctx = channel.pipeline().context(sniHandler);

        // when
        sniHandler.lookup(ctx, "192.168.1.100");

        // then — IP address should be added as SAN IP, not domain
        assertThat(configuration.sslSubjectAlternativeNameIps(), hasItem("192.168.1.100"));
        channel.close();
    }

    // ---- retrieveClientCertificates ----

    @Test
    public void retrieveClientCertificatesShouldReturnNullForChannelWithNoAttributes() {
        // given — channel with a dummy handler but no TLS attributes set
        EmbeddedChannel channel = channelWithDummyHandler();
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Certificate[] result = SniHandler.retrieveClientCertificates(mockServerLogger, ctx);

        // then
        assertThat(result, is(nullValue()));
        channel.close();
    }

    @Test
    public void retrieveClientCertificatesShouldReturnPrePopulatedCertificates() {
        // given
        EmbeddedChannel channel = channelWithDummyHandler();
        Certificate[] expectedCerts = new Certificate[]{new StubCertificate()};
        channel.attr(UPSTREAM_CLIENT_CERTIFICATES).set(expectedCerts);
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Certificate[] result = SniHandler.retrieveClientCertificates(mockServerLogger, ctx);

        // then
        assertThat(result, is(sameInstance(expectedCerts)));
        channel.close();
    }

    @Test
    public void retrieveClientCertificatesShouldReturnNullWhenSslEngineThrowsPeerUnverified() {
        // given
        EmbeddedChannel channel = channelWithDummyHandler();
        SSLEngine sslEngine = new StubSSLEngine(true);
        channel.attr(UPSTREAM_SSL_ENGINE).set(sslEngine);
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Certificate[] result = SniHandler.retrieveClientCertificates(mockServerLogger, ctx);

        // then — gracefully returns null when peer is not verified
        assertThat(result, is(nullValue()));
        channel.close();
    }

    @Test
    public void retrieveClientCertificatesShouldReturnCertsFromSslEngine() {
        // given
        EmbeddedChannel channel = channelWithDummyHandler();
        Certificate[] expectedCerts = new Certificate[]{new StubCertificate()};
        SSLEngine sslEngine = new StubSSLEngine(false, expectedCerts);
        channel.attr(UPSTREAM_SSL_ENGINE).set(sslEngine);
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Certificate[] result = SniHandler.retrieveClientCertificates(mockServerLogger, ctx);

        // then
        assertThat(result, is(expectedCerts));
        // also verify caching in channel attribute
        assertThat(channel.attr(UPSTREAM_CLIENT_CERTIFICATES).get(), is(expectedCerts));
        channel.close();
    }

    // ---- getALPNProtocol ----

    @Test
    public void getALPNProtocolShouldReturnNullForNullContext() {
        // when
        Protocol result = SniHandler.getALPNProtocol(mockServerLogger, null);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void getALPNProtocolShouldReturnCachedProtocol() {
        // given
        EmbeddedChannel channel = channelWithDummyHandler();
        channel.attr(NEGOTIATED_APPLICATION_PROTOCOL).set(Protocol.HTTP_2);
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Protocol result = SniHandler.getALPNProtocol(mockServerLogger, ctx);

        // then
        assertThat(result, is(Protocol.HTTP_2));
        channel.close();
    }

    @Test
    public void getALPNProtocolShouldReturnHttp2WhenSslHandlerReportsH2() {
        // given
        EmbeddedChannel channel = channelWithDummyHandler();
        SslHandler sslHandler = createSslHandlerWithApplicationProtocol("h2");
        channel.attr(UPSTREAM_SSL_HANDLER).set(sslHandler);
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Protocol result = SniHandler.getALPNProtocol(mockServerLogger, ctx);

        // then
        assertThat(result, is(Protocol.HTTP_2));
        // verify caching
        assertThat(channel.attr(NEGOTIATED_APPLICATION_PROTOCOL).get(), is(Protocol.HTTP_2));
        channel.close();
    }

    @Test
    public void getALPNProtocolShouldReturnHttp11WhenSslHandlerReportsHttp11() {
        // given
        EmbeddedChannel channel = channelWithDummyHandler();
        SslHandler sslHandler = createSslHandlerWithApplicationProtocol("http/1.1");
        channel.attr(UPSTREAM_SSL_HANDLER).set(sslHandler);
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Protocol result = SniHandler.getALPNProtocol(mockServerLogger, ctx);

        // then
        assertThat(result, is(Protocol.HTTP_1_1));
        channel.close();
    }

    @Test
    public void getALPNProtocolShouldReturnNullWhenNoSslHandlerAndNoCache() {
        // given
        EmbeddedChannel channel = channelWithDummyHandler();
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Protocol result = SniHandler.getALPNProtocol(mockServerLogger, ctx);

        // then
        assertThat(result, is(nullValue()));
        channel.close();
    }

    @Test
    public void getALPNProtocolShouldReturnNullWhenSslHandlerReportsBlankProtocol() {
        // given
        EmbeddedChannel channel = channelWithDummyHandler();
        SslHandler sslHandler = createSslHandlerWithApplicationProtocol("");
        channel.attr(UPSTREAM_SSL_HANDLER).set(sslHandler);
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Protocol result = SniHandler.getALPNProtocol(mockServerLogger, ctx);

        // then
        assertThat(result, is(nullValue()));
        channel.close();
    }

    @Test
    public void getALPNProtocolShouldReturnNullWhenSslHandlerReportsNullProtocol() {
        // given
        EmbeddedChannel channel = channelWithDummyHandler();
        SslHandler sslHandler = createSslHandlerWithApplicationProtocol(null);
        channel.attr(UPSTREAM_SSL_HANDLER).set(sslHandler);
        ChannelHandlerContext ctx = contextOf(channel);

        // when
        Protocol result = SniHandler.getALPNProtocol(mockServerLogger, ctx);

        // then
        assertThat(result, is(nullValue()));
        channel.close();
    }

    // ---- helpers ----

    /**
     * Creates a StubSslHandler that reports the given ALPN protocol without a TLS handshake.
     */
    private SslHandler createSslHandlerWithApplicationProtocol(String protocol) {
        NettySslContextFactory factory = new NettySslContextFactory(configuration(), new MockServerLogger(), true);
        SslContext sslContext = factory.createServerSslContext();
        SslHandler realHandler = sslContext.newHandler(io.netty.buffer.UnpooledByteBufAllocator.DEFAULT);
        return new StubSslHandler(realHandler.engine(), protocol);
    }

    /**
     * Stub SslHandler that overrides applicationProtocol() without needing a TLS handshake.
     */
    private static class StubSslHandler extends SslHandler {
        private final String protocol;

        StubSslHandler(SSLEngine engine, String protocol) {
            super(engine);
            this.protocol = protocol;
        }

        @Override
        public String applicationProtocol() {
            return protocol;
        }
    }

    /**
     * Minimal Certificate stub for testing attribute population.
     */
    @SuppressWarnings("serial")
    private static class StubCertificate extends Certificate {
        StubCertificate() {
            super("X.509");
        }

        @Override
        public byte[] getEncoded() {
            return new byte[0];
        }

        @Override
        public void verify(java.security.PublicKey key) {
        }

        @Override
        public void verify(java.security.PublicKey key, String sigProvider) {
        }

        @Override
        public String toString() {
            return "StubCertificate";
        }

        @Override
        public java.security.PublicKey getPublicKey() {
            return null;
        }
    }

    /**
     * Minimal SSLEngine stub that simulates peer certificate retrieval.
     * When throwPeerUnverified is true, getSession().getPeerCertificates() throws SSLPeerUnverifiedException.
     */
    private static class StubSSLEngine extends SSLEngine {
        private final boolean throwPeerUnverified;
        private final Certificate[] peerCertificates;

        StubSSLEngine(boolean throwPeerUnverified) {
            this(throwPeerUnverified, null);
        }

        StubSSLEngine(boolean throwPeerUnverified, Certificate[] peerCertificates) {
            this.throwPeerUnverified = throwPeerUnverified;
            this.peerCertificates = peerCertificates;
        }

        @Override
        public SSLSession getSession() {
            return new StubSSLSession(throwPeerUnverified, peerCertificates);
        }

        @Override public SSLSession getHandshakeSession() { return null; }
        @Override public void beginHandshake() { }
        @Override public javax.net.ssl.SSLEngineResult wrap(java.nio.ByteBuffer[] srcs, int off, int len, java.nio.ByteBuffer dst) { return null; }
        @Override public javax.net.ssl.SSLEngineResult unwrap(java.nio.ByteBuffer src, java.nio.ByteBuffer[] dsts, int off, int len) { return null; }
        @Override public Runnable getDelegatedTask() { return null; }
        @Override public void closeInbound() { }
        @Override public boolean isInboundDone() { return false; }
        @Override public void closeOutbound() { }
        @Override public boolean isOutboundDone() { return false; }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] suites) { }
        @Override public String[] getSupportedProtocols() { return new String[0]; }
        @Override public String[] getEnabledProtocols() { return new String[0]; }
        @Override public void setEnabledProtocols(String[] protocols) { }
        @Override public void setUseClientMode(boolean mode) { }
        @Override public boolean getUseClientMode() { return false; }
        @Override public void setNeedClientAuth(boolean need) { }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) { }
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean flag) { }
        @Override public boolean getEnableSessionCreation() { return false; }
        @Override public javax.net.ssl.SSLEngineResult.HandshakeStatus getHandshakeStatus() { return javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING; }
    }

    /**
     * Minimal SSLSession stub. SuppressWarnings: SSLSession.getPeerCertificateChain() returns
     * javax.security.cert.X509Certificate[] which is deprecated-for-removal since JDK 9; this
     * stub must match the interface signature exactly until the method itself is removed.
     */
    @SuppressWarnings({"deprecation", "removal"})
    private static class StubSSLSession implements SSLSession {
        private final boolean throwPeerUnverified;
        private final Certificate[] peerCertificates;

        StubSSLSession(boolean throwPeerUnverified, Certificate[] peerCertificates) {
            this.throwPeerUnverified = throwPeerUnverified;
            this.peerCertificates = peerCertificates;
        }

        @Override
        public Certificate[] getPeerCertificates() throws javax.net.ssl.SSLPeerUnverifiedException {
            if (throwPeerUnverified) {
                throw new javax.net.ssl.SSLPeerUnverifiedException("peer not verified");
            }
            return peerCertificates;
        }

        @Override public byte[] getId() { return new byte[0]; }
        @Override public javax.net.ssl.SSLSessionContext getSessionContext() { return null; }
        @Override public long getCreationTime() { return 0; }
        @Override public long getLastAccessedTime() { return 0; }
        @Override public void invalidate() { }
        @Override public boolean isValid() { return true; }
        @Override public void putValue(String name, Object value) { }
        @Override public Object getValue(String name) { return null; }
        @Override public void removeValue(String name) { }
        @Override public String[] getValueNames() { return new String[0]; }
        @Override public javax.security.cert.X509Certificate[] getPeerCertificateChain() { return null; }
        @Override public java.security.Principal getPeerPrincipal() { return null; }
        @Override public java.security.Principal getLocalPrincipal() { return null; }
        @Override public String getCipherSuite() { return "TLS_NULL_WITH_NULL_NULL"; }
        @Override public String getProtocol() { return "TLSv1.2"; }
        @Override public String getPeerHost() { return null; }
        @Override public int getPeerPort() { return 0; }
        @Override public int getPacketBufferSize() { return 0; }
        @Override public int getApplicationBufferSize() { return 0; }
        @Override public Certificate[] getLocalCertificates() { return null; }
    }
}
