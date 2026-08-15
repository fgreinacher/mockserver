package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3SettingsFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.http3.Http3SettingsFrame;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.mcp.McpRequestProcessor;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.socket.tls.KeyAndCertificateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory;
import static org.mockserver.socket.tls.PEMToFile.x509ChainFromPEMFile;

/**
 * HTTP/3 (QUIC) server for MockServer, integrated with the full request pipeline.
 * <p>
 * When started, HTTP/3 requests are routed through the same expectation matching,
 * action handling, recording, and proxy forwarding pipeline as HTTP/1.1 and HTTP/2.
 * <p>
 * The server uses MockServer's configured TLS certificate material. If no custom
 * certificate is configured, MockServer's auto-generated BouncyCastle certificate
 * is used. The QUIC transport requires a native BoringSSL library; if unavailable
 * at startup the server logs a warning and does not start (fail-soft).
 * <p>
 * HTTP/3 is OFF by default ({@code http3Port=0}) and is built on the Netty 4.2
 * {@code netty-codec-http3} module (graduated from the incubator). HTTP/3 support
 * is labelled <strong>experimental</strong> because the API may evolve.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3Server {

    private static final Logger LOG = LoggerFactory.getLogger(Http3Server.class);

    private final AtomicInteger activeHttp3Connections = new AtomicInteger(0);

    private volatile Channel channel;
    private volatile NioEventLoopGroup group;

    // pipeline components (null when using the legacy echo-only constructor)
    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final HttpState httpState;
    private final HttpActionHandler httpActionHandler;
    /** Null when MCP is not wired (legacy/test constructors). */
    private final McpRequestProcessor mcpRequestProcessor;

    /**
     * Create an HTTP/3 server wired into MockServer's request pipeline, with MCP support.
     *
     * @param configuration      the server configuration
     * @param mockServerLogger   the logger
     * @param httpState          the shared HTTP state (expectations, matchers, etc.)
     * @param httpActionHandler  the action handler for processing matched expectations
     * @param server             the MockServer lifecycle instance (for MCP tool registry)
     * @param mcpSessionManager  the shared MCP session manager (may be null to disable MCP)
     */
    public Http3Server(Configuration configuration, MockServerLogger mockServerLogger,
                       HttpState httpState, HttpActionHandler httpActionHandler,
                       LifeCycle server, McpSessionManager mcpSessionManager) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.httpState = httpState;
        this.httpActionHandler = httpActionHandler;
        this.mcpRequestProcessor = mcpSessionManager != null
            ? new McpRequestProcessor(httpState, server, mcpSessionManager)
            : null;
    }

    /**
     * Create an HTTP/3 server wired into MockServer's request pipeline (no MCP).
     *
     * @param configuration    the server configuration
     * @param mockServerLogger the logger
     * @param httpState        the shared HTTP state (expectations, matchers, etc.)
     * @param httpActionHandler the action handler for processing matched expectations
     */
    public Http3Server(Configuration configuration, MockServerLogger mockServerLogger,
                       HttpState httpState, HttpActionHandler httpActionHandler) {
        this(configuration, mockServerLogger, httpState, httpActionHandler, null, null);
    }

    /**
     * Legacy constructor for backwards compatibility (echo-only mode).
     * Used by tests that do not need the full pipeline.
     */
    public Http3Server() {
        this.configuration = null;
        this.mockServerLogger = null;
        this.httpState = null;
        this.httpActionHandler = null;
        this.mcpRequestProcessor = null;
    }

    /**
     * Start the HTTP/3 server on the given UDP port.
     *
     * @param port UDP port to bind; use 0 for an ephemeral port
     * @return the actual bound port
     * @throws Exception if the server cannot start
     */
    public int start(int port) throws Exception {
        NioEventLoopGroup localGroup = new NioEventLoopGroup(1);
        boolean success = false;
        try {
            // create a shared Metrics instance for all QUIC streams (avoids per-stream allocation)
            Metrics sharedMetrics = configuration != null ? new Metrics(configuration) : null;

            QuicSslContext sslContext = buildQuicSslContext();

            // resolve transport parameters from configuration (or use defaults
            // that match the original hardcoded values for backward compat)
            long maxIdleTimeout = configuration != null ? configuration.http3MaxIdleTimeout() : 5000L;
            long initialMaxData = configuration != null ? configuration.http3InitialMaxData() : 10000000L;
            long initialMaxStreamDataBidi = configuration != null ? configuration.http3InitialMaxStreamDataBidirectional() : 1000000L;
            long initialMaxStreamsBidi = configuration != null ? configuration.http3InitialMaxStreamsBidirectional() : 100L;
            long qpackMaxTableCapacity = configuration != null ? configuration.http3QpackMaxTableCapacity() : 0L;

            // build settings frame: QPACK dynamic table + extended CONNECT
            DefaultHttp3SettingsFrame settingsFrame = new DefaultHttp3SettingsFrame();
            settingsFrame.put(Http3SettingsFrame.HTTP3_SETTINGS_QPACK_MAX_TABLE_CAPACITY, qpackMaxTableCapacity);

            boolean connectUdpEnabled = configuration != null && Boolean.TRUE.equals(configuration.http3ConnectUdpEnabled());
            if (connectUdpEnabled) {
                // Advertise extended CONNECT support (RFC 9220) so clients can
                // send CONNECT requests with the :protocol pseudo-header
                settingsFrame.put(Http3SettingsFrame.HTTP3_SETTINGS_ENABLE_CONNECT_PROTOCOL, 1L);
            }

            AtomicInteger connectionCounter = this.activeHttp3Connections;

            ChannelHandler codec = Http3.newQuicServerCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(maxIdleTimeout, TimeUnit.MILLISECONDS)
                .initialMaxData(initialMaxData)
                .initialMaxStreamDataBidirectionalLocal(initialMaxStreamDataBidi)
                .initialMaxStreamDataBidirectionalRemote(initialMaxStreamDataBidi)
                .initialMaxStreamsBidirectional(initialMaxStreamsBidi)
                // source-address-validating (stateless-retry) token handler: binds the
                // client address into a keyed HMAC so a forged-source Initial packet cannot
                // obtain a valid retry token, mitigating QUIC address-spoofing / amplification.
                // Replaces Netty's InsecureQuicTokenHandler (plaintext, forgeable token).
                .tokenHandler(new SourceAddressQuicTokenHandler())
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        // track QUIC connection open/close for dashboard visibility
                        connectionCounter.incrementAndGet();
                        ch.closeFuture().addListener(f -> connectionCounter.decrementAndGet());

                        // disable the QPACK dynamic table when capacity is 0 (the default),
                        // matching the old 1-arg constructor behaviour; enable it only when
                        // the user has configured a non-zero qpackMaxTableCapacity
                        boolean disableQpackDynamicTable = qpackMaxTableCapacity == 0;
                        ch.pipeline().addLast(new Http3ServerConnectionHandler(
                            new ChannelInitializer<QuicStreamChannel>() {
                                @Override
                                protected void initChannel(QuicStreamChannel streamCh) {
                                    if (httpState != null && httpActionHandler != null && configuration != null) {
                                        if (connectUdpEnabled) {
                                            streamCh.pipeline().addLast(new Http3ConnectUdpHandler(configuration));
                                        }
                                        streamCh.pipeline().addLast(new Http3MockServerHandler(
                                            configuration, mockServerLogger, httpState, httpActionHandler, sharedMetrics,
                                            mcpRequestProcessor
                                        ));
                                    } else {
                                        streamCh.pipeline().addLast(new Http3EchoRequestHandler());
                                    }
                                }
                            },
                            null, null, settingsFrame, disableQpackDynamicTable
                        ));
                    }
                })
                .build();

            channel = new Bootstrap()
                .group(localGroup)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(new InetSocketAddress(port))
                .sync()
                .channel();

            int boundPort = ((InetSocketAddress) channel.localAddress()).getPort();
            LOG.info("HTTP/3 (QUIC) server started on UDP port: {}", boundPort);
            group = localGroup;
            success = true;
            return boundPort;
        } finally {
            if (!success) {
                localGroup.shutdownGracefully();
            }
        }
    }

    /**
     * Returns the current number of active QUIC (HTTP/3) connections.
     */
    public int getActiveConnectionCount() {
        return activeHttp3Connections.get();
    }

    /**
     * Returns the bound UDP port, or -1 if not started.
     */
    public int getPort() {
        if (channel != null && channel.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) channel.localAddress()).getPort();
        }
        return -1;
    }

    /**
     * Stop the HTTP/3 server and release resources.
     */
    public void stop() {
        if (channel != null) {
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
        LOG.info("HTTP/3 (QUIC) server stopped");
    }

    /**
     * Build the QUIC SSL context using MockServer's configured TLS material when
     * available, falling back to a self-signed certificate when no configuration
     * is provided (legacy/echo mode).
     */
    private QuicSslContext buildQuicSslContext() throws Exception {
        PrivateKey privateKey;
        X509Certificate[] certChain;
        KeyAndCertificateFactory keyAndCertFactory = null;

        if (configuration != null && mockServerLogger != null) {
            // use MockServer's TLS certificate infrastructure
            keyAndCertFactory = createKeyAndCertificateFactory(configuration, mockServerLogger);
            if (keyAndCertFactory.certificateNotYetCreated()) {
                keyAndCertFactory.buildAndSavePrivateKeyAndX509Certificate();
            }
            privateKey = keyAndCertFactory.privateKey();
            List<X509Certificate> chain = keyAndCertFactory.certificateChain();
            certChain = chain.toArray(new X509Certificate[0]);
            LOG.info("HTTP/3 server using MockServer's configured TLS certificate");
        } else {
            // legacy self-signed fallback
            java.security.KeyPair keyPair = generateKeyPair();
            privateKey = keyPair.getPrivate();
            certChain = new X509Certificate[]{generateSelfSignedCert(keyPair)};
            LOG.info("HTTP/3 server using self-signed certificate (no configuration provided)");
        }

        QuicSslContextBuilder quicSslBuilder = QuicSslContextBuilder
            .forServer(privateKey, null, certChain)
            .applicationProtocols(Http3.supportedApplicationProtocols());

        // mTLS (client authentication) for QUIC -- mirrors the TCP path's
        // NettySslContextFactory logic:
        // - clientAuth is REQUIRE when tlsMutualAuthenticationRequired is true,
        //   OPTIONAL otherwise (OPTIONAL is the safe default: it will request
        //   a client cert but not reject connections that don't present one)
        // - trustManager is set to the configured mTLS trust chain when present,
        //   or an insecure trust-all factory when no explicit chain is provided
        //   (same as the TCP path's InsecureTrustManagerFactory fallback)
        if (configuration != null && keyAndCertFactory != null) {
            quicSslBuilder.clientAuth(
                configuration.tlsMutualAuthenticationRequired()
                    ? ClientAuth.REQUIRE : ClientAuth.OPTIONAL
            );
            if (isNotBlank(configuration.tlsMutualAuthenticationCertificateChain())
                || configuration.tlsMutualAuthenticationRequired()) {
                quicSslBuilder.trustManager(buildTrustCertificateChain(keyAndCertFactory));
            } else {
                quicSslBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
        }

        return quicSslBuilder.build();
    }

    /**
     * Build the trust certificate chain for mTLS client verification, mirroring
     * the TCP path's {@code NettySslContextFactory.trustCertificateChain()}.
     * When a custom mTLS trust chain is configured, it is loaded from PEM and
     * combined with the CA certificate; otherwise, only the CA certificate is used.
     */
    private X509Certificate[] buildTrustCertificateChain(KeyAndCertificateFactory keyAndCertFactory) {
        String mtlsCertChainPath = configuration.tlsMutualAuthenticationCertificateChain();
        if (isNotBlank(mtlsCertChainPath)) {
            List<X509Certificate> x509Certificates = x509ChainFromPEMFile(mtlsCertChainPath);
            x509Certificates.add(keyAndCertFactory.certificateAuthorityX509Certificate());
            return x509Certificates.toArray(new X509Certificate[0]);
        } else {
            return Collections
                .singletonList(keyAndCertFactory.certificateAuthorityX509Certificate())
                .toArray(new X509Certificate[0]);
        }
    }

    /**
     * Check whether the native QUIC transport is available on this platform.
     *
     * @return true if the native BoringSSL QUIC library is loadable
     */
    public static boolean isQuicAvailable() {
        try {
            return io.netty.handler.codec.quic.Quic.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // -- self-signed cert generation for legacy mode --

    private static java.security.KeyPair generateKeyPair() throws Exception {
        java.security.KeyPairGenerator keyPairGen = java.security.KeyPairGenerator.getInstance("EC");
        keyPairGen.initialize(256, new java.security.SecureRandom());
        return keyPairGen.generateKeyPair();
    }

    /**
     * Generate the legacy echo-mode self-signed certificate. Reached only when {@code configuration == null}
     * (transport-level echo testing); the configured HTTP/3 path goes through the real
     * {@link KeyAndCertificateFactory} and gets the hardened short-lived leaf.
     * <p>
     * This certificate is simultaneously trust anchor AND server certificate and has no renewal loop
     * behind it, so it deliberately keeps the long CA-style validity ({@link KeyAndCertificateFactory#CERTIFICATE_VALIDITY_YEARS})
     * rather than the short 397-day leaf validity — a short-lived self-signed anchor with nothing to renew
     * it would simply expire the echo endpoint. It is otherwise brought up to the same standard as the real
     * leaf: 5-day back-dated notBefore (clock-skew tolerance), a positive serial (RFC 5280 §4.1.2.2), a SAN
     * covering localhost / 127.0.0.1 / ::1, serverAuth (+ clientAuth) EKU (Apple requires serverAuth) and a
     * TLS-server keyUsage.
     */
    private static X509Certificate generateSelfSignedCert(java.security.KeyPair keyPair) throws Exception {
        org.bouncycastle.asn1.x500.X500Name issuer = new org.bouncycastle.asn1.x500.X500Name("CN=MockServer HTTP/3, O=MockServer");
        java.math.BigInteger serial = KeyAndCertificateFactory.positiveSerialNumber();
        java.util.Date notBefore = KeyAndCertificateFactory.notBefore();
        java.util.Date notAfter = KeyAndCertificateFactory.notAfter();

        org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic()
        );

        org.bouncycastle.asn1.x509.GeneralName[] sanEntries = new org.bouncycastle.asn1.x509.GeneralName[]{
            new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName, "localhost"),
            new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.iPAddress, "127.0.0.1"),
            new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.iPAddress, "::1")
        };
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false, new org.bouncycastle.asn1.x509.GeneralNames(sanEntries));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, false, new org.bouncycastle.asn1.x509.ExtendedKeyUsage(new org.bouncycastle.asn1.x509.KeyPurposeId[]{
            org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth,
            org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth
        }));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage, true, new org.bouncycastle.asn1.x509.KeyUsage(
            org.bouncycastle.asn1.x509.KeyUsage.digitalSignature | org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment
        ));

        org.bouncycastle.operator.ContentSigner signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
            .build(keyPair.getPrivate());
        org.bouncycastle.cert.X509CertificateHolder holder = builder.build(signer);

        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder);
    }

    /**
     * Legacy echo request handler, kept for backward compatibility and basic
     * transport-level testing when the full pipeline is not wired.
     */
    static class Http3EchoRequestHandler extends io.netty.handler.codec.http3.Http3RequestStreamInboundHandler {

        @Override
        protected void channelRead(
            io.netty.channel.ChannelHandlerContext ctx,
            io.netty.handler.codec.http3.Http3HeadersFrame headersFrame
        ) {
            CharSequence methodSeq = headersFrame.headers().method();
            CharSequence pathSeq = headersFrame.headers().path();
            String method = methodSeq != null ? methodSeq.toString() : "UNKNOWN";
            String path = pathSeq != null ? pathSeq.toString() : "/";

            String responseBody = "MockServer HTTP/3 echo - method: " + method + ", path: " + path;
            byte[] bodyBytes = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            io.netty.handler.codec.http3.DefaultHttp3HeadersFrame responseHeaders = new io.netty.handler.codec.http3.DefaultHttp3HeadersFrame();
            responseHeaders.headers().status("200");
            responseHeaders.headers().add("content-type", "text/plain; charset=utf-8");
            responseHeaders.headers().addInt("content-length", bodyBytes.length);
            responseHeaders.headers().add("server", "mockserver-http3-experimental");

            ctx.write(responseHeaders);
            ctx.writeAndFlush(new io.netty.handler.codec.http3.DefaultHttp3DataFrame(
                io.netty.buffer.Unpooled.wrappedBuffer(bodyBytes)
            )).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }

        @Override
        protected void channelRead(
            io.netty.channel.ChannelHandlerContext ctx,
            io.netty.handler.codec.http3.Http3DataFrame dataFrame
        ) {
            // echo handler: ignore request body data frames
            io.netty.util.ReferenceCountUtil.release(dataFrame);
        }

        @Override
        protected void channelInputClosed(io.netty.channel.ChannelHandlerContext ctx) {
            // stream input closed by peer - nothing to do for the echo handler
        }
    }
}
