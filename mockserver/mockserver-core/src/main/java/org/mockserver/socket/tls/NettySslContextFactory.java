package org.mockserver.socket.tls;

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileReader;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory;
import static org.mockserver.socket.tls.PEMToFile.x509ChainFromPEM;
import static org.mockserver.socket.tls.PEMToFile.x509ChainFromPEMFile;

/**
 * @author jamesdbloom
 */
public class NettySslContextFactory {

    public static Function<SslContextBuilder, SslContext> clientSslContextBuilderFunction =
        sslContextBuilder -> {
            try {
                return sslContextBuilder.build();
            } catch (SSLException e) {
                throw new RuntimeException(e);
            }
        };
    public static Consumer<NettySslContextFactory> nettySslContextFactoryCustomizer = factory -> {
    };
    public static UnaryOperator<SslContextBuilder> sslServerContextBuilderCustomizer = UnaryOperator.identity();
    public static UnaryOperator<SslContextBuilder> sslClientContextBuilderCustomizer = UnaryOperator.identity();

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final KeyAndCertificateFactory keyAndCertificateFactory;
    private final Map<String, SslContext> clientSslContexts = new ConcurrentHashMap<>();
    // Parsed forward-proxy PEM material cached by its configuration value (file path or inline PEM)
    // so an unchanged forward-proxy key/chain is not re-parsed on every TLS context (re)build.
    private final Map<String, PrivateKey> forwardProxyPrivateKeyCache = new ConcurrentHashMap<>();
    private final Map<String, X509Certificate[]> forwardProxyCertificateChainCache = new ConcurrentHashMap<>();
    private volatile SslContext serverSslContext;
    private final Object sslContextLock = new Object();
    private Function<SslContextBuilder, SslContext> instanceClientSslContextBuilderFunction = clientSslContextBuilderFunction;
    private final boolean forServer;

    /**
     * @deprecated use constructor that specifies configuration explicitly
     */
    @Deprecated
    public NettySslContextFactory(MockServerLogger mockServerLogger) {
        this.configuration = configuration();
        this.mockServerLogger = mockServerLogger;
        this.forServer = true;
        keyAndCertificateFactory = createKeyAndCertificateFactory(configuration, mockServerLogger);
        warnIfInsecureTlsProfileConfigured();
        nettySslContextFactoryCustomizer.accept(this);
        if (configuration.proactivelyInitialiseTLS()) {
            createServerSslContext();
        }
    }

    public NettySslContextFactory(Configuration configuration, MockServerLogger mockServerLogger, boolean forServer) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.forServer = forServer;
        keyAndCertificateFactory = createKeyAndCertificateFactory(configuration, mockServerLogger, forServer);
        warnIfInsecureTlsProfileConfigured();
        nettySslContextFactoryCustomizer.accept(this);
        if (configuration.proactivelyInitialiseTLS()) {
            createServerSslContext();
        }
    }

    /**
     * Resolve the effective TLS protocols, filtering out TLSv1 and TLSv1.1 when
     * {@code tlsAllowInsecureProtocols=false}. If filtering leaves the list empty,
     * the original list is returned so SSL context creation does not fail.
     */
    private String[] effectiveTlsProtocols() {
        String[] requested = java.util.Arrays.stream(configuration.tlsProtocols().split(","))
            .map(String::trim)
            .filter(p -> !p.isEmpty())
            .toArray(String[]::new);
        if (Boolean.TRUE.equals(configuration.tlsAllowInsecureProtocols())) {
            return requested;
        }
        String[] filtered = java.util.Arrays.stream(requested)
            .filter(p -> !p.equalsIgnoreCase("TLSv1") && !p.equalsIgnoreCase("TLSv1.1"))
            .toArray(String[]::new);
        return filtered.length > 0 ? filtered : requested;
    }

    private void warnIfInsecureTlsProfileConfigured() {
        if (mockServerLogger == null) {
            return;
        }
        boolean configuredInsecure = java.util.Arrays.stream(configuration.tlsProtocols().split(","))
            .map(String::trim)
            .anyMatch(p -> p.equalsIgnoreCase("TLSv1") || p.equalsIgnoreCase("TLSv1.1"));
        if (configuredInsecure && Boolean.TRUE.equals(configuration.tlsAllowInsecureProtocols())) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("TLS protocol list includes deprecated TLSv1 / TLSv1.1 (RFC 8996; vulnerable to BEAST and POODLE). Set mockserver.tlsAllowInsecureProtocols=false to drop them, or remove the entries from mockserver.tlsProtocols.")
            );
        }
        if (forwardProxyTrustsEverything()) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("Forward proxy is configured to trust ALL X.509 certificates (mockserver.forwardProxyTLSX509CertificatesTrustManagerType=ANY). Certificate validation is disabled — this should be used only in development; prefer JVM or CUSTOM in production.")
            );
        }
    }

    private boolean forwardProxyTrustsEverything() {
        try {
            return configuration.forwardProxyTLSX509CertificatesTrustManagerType() == ForwardProxyTLSX509CertificatesTrustManager.ANY;
        } catch (RuntimeException ignore) {
            return false;
        }
    }

    public NettySslContextFactory withClientSslContextBuilderFunction(Function<SslContextBuilder, SslContext> clientSslContextBuilderFunction) {
        this.instanceClientSslContextBuilderFunction = clientSslContextBuilderFunction;
        return this;
    }

    public SslContext createClientSslContext(boolean forwardProxyClient, boolean enableHttp2) {
        return createClientSslContext(forwardProxyClient, enableHttp2, null);
    }

    /**
     * Build (or return the cached) outbound client {@link SslContext} for connecting to an upstream.
     * <p>
     * When {@code host} has a per-host client certificate/key mapping configured via
     * {@code forwardProxyClientCertificatesByHost}, that host's cert/key pair is presented for outbound
     * mTLS; otherwise the global {@code forwardProxyPrivateKey} / {@code forwardProxyCertificateChain}
     * pair (or MockServer's own generated key/cert) is used, exactly as before. Contexts are cached:
     * every host that resolves to the global pair shares one cached context (so the cache cannot grow
     * unbounded across many upstream hosts), while each host with its own mapping gets its own context.
     *
     * @param host the upstream target host (may be null/blank — then only the global pair is ever used)
     */
    public SslContext createClientSslContext(boolean forwardProxyClient, boolean enableHttp2, String host) {
        // Only hosts with an explicit per-host cert/key mapping are keyed by host; all other hosts share
        // the empty host-key so a forward proxy seeing many upstream hosts cannot grow the cache without bound.
        String hostKeyPart = perHostForwardProxyCertAndKey(host) != null ? host.toLowerCase(Locale.ROOT) : "";
        // Fold the mutable client-TLS inputs into the cache KEY so that rotating any of them at runtime
        // self-invalidates the cache (defect C8). Previously the client SslContext was cached for the
        // JVM lifetime and rotating forwardProxyPrivateKey / forwardProxyCertificateChain / trust manager
        // type / tlsMutualAuthenticationCertificateChain / tlsProtocols was silently ignored.
        String key = "forwardProxyClient=" + forwardProxyClient + ",enableHttp2=" + enableHttp2 + ",host=" + hostKeyPart
            + ",sig=" + clientContextSignature();
        SslContext clientSslContext = clientSslContexts.get(key);
        if (clientSslContext != null && !configuration.rebuildTLSContext()) {
            return clientSslContext;
        }
        synchronized (sslContextLock) {
            clientSslContext = clientSslContexts.get(key);
            if (clientSslContext != null && !configuration.rebuildTLSContext()) {
                return clientSslContext;
            }
            try {
                if (keyAndCertificateFactory.certificateNotYetCreated()) {
                    keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();
                }
                SslContextBuilder sslContextBuilder =
                    SslContextBuilder
                        .forClient()
                        .protocols(effectiveTlsProtocols())
                        .keyManager(
                            forwardProxyPrivateKey(host),
                            forwardProxyCertificateChain(host)
                        );
                if (enableHttp2) {
                    configureALPN(sslContextBuilder);
                }
                if (forwardProxyClient) {
                    switch (configuration.forwardProxyTLSX509CertificatesTrustManagerType()) {
                        case ANY:
                            sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                            break;
                        case JVM:
                            List<X509Certificate> mockServerX509Certificates = new ArrayList<>();
                            mockServerX509Certificates.add(keyAndCertificateFactory.x509Certificate());
                            mockServerX509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
                            sslContextBuilder.trustManager(jvmCAX509TrustCertificates(mockServerX509Certificates));
                            break;
                        case CUSTOM:
                            sslContextBuilder.trustManager(customCAX509TrustCertificates());
                            break;
                    }
                } else {
                    List<X509Certificate> mockServerX509Certificates = new ArrayList<>();
                    if (isNotBlank(configuration.tlsMutualAuthenticationCertificateChain())) {
                        mockServerX509Certificates.addAll(x509ChainFromPEMFile(configuration.tlsMutualAuthenticationCertificateChain()));
                        mockServerX509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
                    } else {
                        mockServerX509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
                    }
                    sslContextBuilder.trustManager(jvmCAX509TrustCertificates(mockServerX509Certificates));
                }
                clientSslContext = instanceClientSslContextBuilderFunction.apply(sslClientContextBuilderCustomizer.apply(sslContextBuilder));
                clientSslContexts.put(key, clientSslContext);
                configuration.rebuildTLSContext(false);
            } catch (Throwable throwable) {
                throw new RuntimeException("Exception creating SSL context for client", throwable);
            }
        }
        return clientSslContext;
    }

    private PrivateKey forwardProxyPrivateKey(String host) {
        String[] perHost = perHostForwardProxyCertAndKey(host);
        String forwardProxyPrivateKey = perHost != null ? perHost[1] : configuration.forwardProxyPrivateKey();
        if (isNotBlank(forwardProxyPrivateKey)) {
            // Cache the parsed key by the PEM *contents* (not the file path) so an unchanged PEM is
            // parsed only once while a rotated key file (same path, new contents) is re-parsed.
            String pem = FileReader.readFileFromClassPathOrPath(forwardProxyPrivateKey);
            return forwardProxyPrivateKeyCache.computeIfAbsent(pem, PEMToFile::privateKeyFromPEM);
        } else {
            return keyAndCertificateFactory.privateKey();
        }
    }

    private X509Certificate[] forwardProxyCertificateChain(String host) {
        String[] perHost = perHostForwardProxyCertAndKey(host);
        String forwardProxyCertificateChain = perHost != null ? perHost[0] : configuration.forwardProxyCertificateChain();
        if (isNotBlank(forwardProxyCertificateChain)) {
            // Cache the parsed chain by the PEM *contents* (not the file path) so an unchanged PEM
            // chain is parsed only once while a rotated file is re-parsed. A clone is returned so
            // callers cannot mutate the cached array.
            String pem = FileReader.readFileFromClassPathOrPath(forwardProxyCertificateChain);
            return forwardProxyCertificateChainCache
                .computeIfAbsent(pem, chain -> x509ChainFromPEM(chain).toArray(new X509Certificate[0]))
                .clone();
        } else {
            return keyAndCertificateFactory.certificateChain().toArray(new X509Certificate[0]);
        }
    }

    /**
     * Resolve the per-host outbound mTLS certificate chain and private key for {@code host} from the
     * {@code forwardProxyClientCertificatesByHost} property.
     * <p>
     * The property is a comma-separated list of {@code host=certificateChainPath;privateKeyPath} entries;
     * host matching is case-insensitive. Returns {@code [certificateChainPath, privateKeyPath]} for the
     * first matching host, or {@code null} when {@code host} is blank, no mapping is configured, or no
     * entry matches — in which case the caller falls back to the global forward-proxy cert/key pair.
     * Malformed entries (no {@code =}, or no {@code ;} separating the two paths) are skipped.
     */
    private String[] perHostForwardProxyCertAndKey(String host) {
        return resolveForwardProxyClientCertificate(configuration.forwardProxyClientCertificatesByHost(), host);
    }

    /**
     * Pure resolver for {@link #perHostForwardProxyCertAndKey(String)} — package-private for unit testing.
     * See that method's Javadoc for the format and semantics.
     */
    static String[] resolveForwardProxyClientCertificate(String mapping, String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (!isNotBlank(mapping)) {
            return null;
        }
        for (String entry : mapping.split(",")) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String mappedHost = entry.substring(0, equals).trim();
            if (!mappedHost.equalsIgnoreCase(host)) {
                continue;
            }
            String pair = entry.substring(equals + 1).trim();
            int semicolon = pair.indexOf(';');
            if (semicolon <= 0 || semicolon >= pair.length() - 1) {
                continue;
            }
            String certificateChainPath = pair.substring(0, semicolon).trim();
            String privateKeyPath = pair.substring(semicolon + 1).trim();
            if (isNotBlank(certificateChainPath) && isNotBlank(privateKeyPath)) {
                return new String[]{certificateChainPath, privateKeyPath};
            }
        }
        return null;
    }

    private X509Certificate[] jvmCAX509TrustCertificates(List<X509Certificate> additionalX509Certificates) throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore) null);
        return Arrays
            .stream(trustManagerFactory.getTrustManagers())
            .filter(trustManager -> trustManager instanceof X509TrustManager)
            .flatMap(trustManager -> Arrays.stream(((X509TrustManager) trustManager).getAcceptedIssuers()))
            .collect(() -> additionalX509Certificates, List::add, List::addAll)
            .toArray(new X509Certificate[0]);
    }

    private X509Certificate[] customCAX509TrustCertificates() {
        ArrayList<X509Certificate> x509Certificates = new ArrayList<>();
        x509Certificates.add(keyAndCertificateFactory.x509Certificate());
        x509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
        x509Certificates.addAll(x509ChainFromPEMFile(configuration.forwardProxyTLSCustomTrustX509Certificates()));
        return x509Certificates.toArray(new X509Certificate[0]);
    }

    /**
     * The complete set of inputs baked into the cached {@link #serverSslContext}, compared per cached
     * entry so the cache self-invalidates when ANY of them change — replacing the single consumable
     * {@code rebuildServerTLSContext} boolean, which suffered a lost-update race: one thread could add a
     * SAN and set the flag while another thread, already inside the lock, cleared it, so the second
     * thread then returned a context that was missing the first thread's hostname (defect C4).
     *
     * <p>Covers: the Subject-Alternative-Name domain and IP sets (unless {@code preventCertificateDynamicUpdate}
     * is set, which pins the domain list), the certificate-authority identity and paths, the fixed
     * leaf key/cert paths, the mTLS client-authentication inputs, and the TLS protocol/ALPN inputs.
     * Leaf/CA EXPIRY is handled separately via {@link KeyAndCertificateFactory#certificateNeedsRenewal()}
     * (defect C1) because it changes with the clock rather than with configuration.
     *
     * <p>SAN membership is deliberately NOT gated by {@code preventCertificateDynamicUpdate} for the
     * client-authentication inputs — that option suppresses certificate REGENERATION when the domain
     * list changes, and must not be able to suppress a tightening of client-authentication policy.
     */
    private volatile String serverSslContextSignature;

    private String serverContextSignature() {
        StringBuilder signature = new StringBuilder()
            .append("mtls=").append(configuration.tlsMutualAuthenticationRequired())
            .append("|mtlsChain=").append(configuration.tlsMutualAuthenticationCertificateChain())
            .append("|protocols=").append(configuration.tlsProtocols())
            .append("|insecureProtocols=").append(configuration.tlsAllowInsecureProtocols())
            .append("|http2=").append(configuration.http2Enabled())
            .append("|caCert=").append(configuration.certificateAuthorityCertificate())
            .append("|caKey=").append(configuration.certificateAuthorityPrivateKey())
            .append("|dynamicCA=").append(configuration.dynamicallyCreateCertificateAuthorityCertificate())
            .append("|dir=").append(configuration.directoryToSaveDynamicSSLCertificate())
            .append("|keyPath=").append(configuration.privateKeyPath())
            .append("|certPath=").append(configuration.x509CertificatePath());
        if (!Boolean.TRUE.equals(configuration.preventCertificateDynamicUpdate())) {
            signature
                .append("|sanDomains=").append(new TreeSet<>(nullSafeSet(configuration.sslSubjectAlternativeNameDomains())))
                .append("|sanIps=").append(new TreeSet<>(nullSafeSet(configuration.sslSubjectAlternativeNameIps())));
        }
        return signature.toString();
    }

    /**
     * The mutable client-TLS inputs baked into a cached client {@link SslContext}; folded into the cache
     * key so runtime rotation self-invalidates (defect C8).
     */
    private String clientContextSignature() {
        return "fwdKey=" + configuration.forwardProxyPrivateKey()
            + "|fwdChain=" + configuration.forwardProxyCertificateChain()
            + "|fwdByHost=" + configuration.forwardProxyClientCertificatesByHost()
            + "|trustType=" + forwardProxyTrustManagerTypeSafe()
            + "|customTrust=" + configuration.forwardProxyTLSCustomTrustX509Certificates()
            + "|mtlsChain=" + configuration.tlsMutualAuthenticationCertificateChain()
            + "|protocols=" + configuration.tlsProtocols()
            + "|insecureProtocols=" + configuration.tlsAllowInsecureProtocols()
            // CA identity so a runtime CA rotation invalidates the client trust anchor too (defect C9)
            + "|caCert=" + configuration.certificateAuthorityCertificate()
            + "|caKey=" + configuration.certificateAuthorityPrivateKey()
            + "|dynamicCA=" + configuration.dynamicallyCreateCertificateAuthorityCertificate()
            + "|dir=" + configuration.directoryToSaveDynamicSSLCertificate();
    }

    private String forwardProxyTrustManagerTypeSafe() {
        try {
            return String.valueOf(configuration.forwardProxyTLSX509CertificatesTrustManagerType());
        } catch (RuntimeException ignore) {
            return "";
        }
    }

    private static <T> Set<T> nullSafeSet(Set<T> set) {
        return set == null ? Collections.emptySet() : set;
    }

    public SslContext createServerSslContext() {
        if (serverSslContext != null
            && !keyAndCertificateFactory.certificateNotYetCreated()
            && !keyAndCertificateFactory.certificateNeedsRenewal()
            && serverContextSignature().equals(serverSslContextSignature)) {
            return serverSslContext;
        }
        synchronized (sslContextLock) {
            if (serverSslContext != null
                && !keyAndCertificateFactory.certificateNotYetCreated()
                && !keyAndCertificateFactory.certificateNeedsRenewal()
                && serverContextSignature().equals(serverSslContextSignature)) {
                return serverSslContext;
            }
            try {
                new CertificateConfigurationValidator(configuration, mockServerLogger).validate();
                keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();
                logUsedCertificateData();
                final SslContextBuilder sslContextBuilder = SslContextBuilder
                    .forServer(
                        keyAndCertificateFactory.privateKey(),
                        keyAndCertificateFactory.certificateChain()
                    )
                    .protocols(effectiveTlsProtocols())
                    .clientAuth(configuration.tlsMutualAuthenticationRequired() ? ClientAuth.REQUIRE : ClientAuth.OPTIONAL);
                configureALPN(sslContextBuilder);
                if (isNotBlank(configuration.tlsMutualAuthenticationCertificateChain()) || configuration.tlsMutualAuthenticationRequired()) {
                    sslContextBuilder.trustManager(trustCertificateChain());
                } else {
                    sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                }
                serverSslContext = sslServerContextBuilderCustomizer
                    .apply(sslContextBuilder)
                    .build();
                // record the FULL set of inputs this context was built from (read AFTER the build, so any
                // paths the factory derived during the build are captured), so a later change to any of
                // them forces a rebuild without relying on a consumable flag
                serverSslContextSignature = serverContextSignature();
                configuration.rebuildServerTLSContext(false);
            } catch (Error error) {
                throw error;
            } catch (Exception exception) {
                throw new RuntimeException(
                    "Exception creating SSL context for server"
                        + " with privateKeyPath=\"" + configuration.privateKeyPath() + "\""
                        + " and x509CertificatePath=\"" + configuration.x509CertificatePath() + "\""
                        + " and certificateAuthorityCertificate=\"" + configuration.certificateAuthorityCertificate() + "\""
                        + (exception.getMessage() != null ? " - " + exception.getMessage() : ""),
                    exception
                );
            }
        }
        return serverSslContext;
    }

    private void logUsedCertificateData() {
        final X509Certificate caCertificate = keyAndCertificateFactory.certificateAuthorityX509Certificate();
        final X509Certificate eeCertificate = keyAndCertificateFactory.x509Certificate();
        if (caCertificate != null && eeCertificate != null) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("using certificate authority serial:{}issuer:{}subject:{}and certificate serial:{}issuer:{}subject:{}")
                    .setArguments(
                        caCertificate.getSerialNumber(),
                        caCertificate.getIssuerX500Principal(),
                        caCertificate.getSubjectX500Principal(),
                        eeCertificate.getSerialNumber(),
                        eeCertificate.getIssuerX500Principal(),
                        eeCertificate.getSubjectX500Principal()
                    )
            );
        }
    }

    private void configureALPN(SslContextBuilder sslContextBuilder) {
        // when HTTP/2 is disabled only advertise http/1.1 via ALPN so h2 capable clients fall back to HTTP/1.1
        String[] applicationProtocols = configuration.http2Enabled()
            ? new String[]{ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1}
            : new String[]{ApplicationProtocolNames.HTTP_1_1};
        Consumer<SslContextBuilder> configureALPN = contextBuilder -> contextBuilder
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                applicationProtocols
            ));
        if (SslProvider.isAlpnSupported(SslContext.defaultServerProvider())) {
            configureALPN.accept(sslContextBuilder.sslProvider(SslContext.defaultServerProvider()));
        } else if (SslProvider.isAlpnSupported(SslProvider.JDK)) {
            configureALPN.accept(sslContextBuilder.sslProvider(SslProvider.JDK));
        } else if (SslProvider.isAlpnSupported(SslProvider.OPENSSL)) {
            configureALPN.accept(sslContextBuilder.sslProvider(SslProvider.OPENSSL));
        }
    }

    private X509Certificate[] trustCertificateChain() {
        return trustCertificateChain(configuration.tlsMutualAuthenticationCertificateChain());
    }

    public X509Certificate[] trustCertificateChain(String tlsMutualAuthenticationCertificateChain) {
        if (isNotBlank(tlsMutualAuthenticationCertificateChain)) {
            List<X509Certificate> x509Certificates = x509ChainFromPEMFile(tlsMutualAuthenticationCertificateChain);
            x509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
            return x509Certificates.toArray(new X509Certificate[0]);
        } else {
            return Collections
                .singletonList(keyAndCertificateFactory.certificateAuthorityX509Certificate())
                .toArray(new X509Certificate[0]);
        }
    }

    public boolean isForServer() {
        return forServer;
    }
}
