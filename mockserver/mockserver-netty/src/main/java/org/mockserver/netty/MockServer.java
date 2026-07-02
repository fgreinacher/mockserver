package org.mockserver.netty;

import com.google.common.collect.ImmutableList;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.authentication.ChainedAuthenticationHandler;
import org.mockserver.authentication.jwt.JWTAuthenticationHandler;
import org.mockserver.authentication.mtls.MTLSAuthenticationHandler;
import org.mockserver.authentication.oidc.OidcAuthenticationHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.ExpectationsListener;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.dns.DnsRequestHandler;
import org.mockserver.netty.http3.Http3Server;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.socket.NettyTransport;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * @author jamesdbloom
 */
public class MockServer extends LifeCycle {

    private InetSocketAddress remoteSocket;
    private volatile org.mockserver.netty.mcp.McpSessionManager mcpSessionManager;
    private volatile io.netty.channel.Channel dnsChannel;
    private volatile Http3Server http3Server;

    /**
     * Start the instance using the ports provided
     *
     * @param localPorts the local port(s) to use, use 0 or no vararg values to specify any free port
     */
    public MockServer(final Integer... localPorts) {
        this(null, proxyConfiguration(configuration()), localPorts);
    }

    /**
     * Start the instance using the ports provided
     *
     * @param localPorts the local port(s) to use, use 0 or no vararg values to specify any free port
     */
    public MockServer(final Configuration configuration, final Integer... localPorts) {
        this(configuration, proxyConfiguration(configuration), localPorts);
    }

    /**
     * Start the instance using the ports provided configuring forwarded or proxied requests to go via an additional proxy
     *
     * @param proxyConfiguration the proxy configuration to send requests forwarded or proxied by MockServer via another proxy
     * @param localPorts         the local port(s) to use, use 0 or no vararg values to specify any free port
     */
    public MockServer(final ProxyConfiguration proxyConfiguration, final Integer... localPorts) {
        this(null, ImmutableList.of(proxyConfiguration), localPorts);
    }

    /**
     * Start the instance using the ports provided configuring forwarded or proxied requests to go via an additional proxy
     *
     * @param proxyConfigurations the proxy configuration to send requests forwarded or proxied by MockServer via another proxy
     * @param localPorts          the local port(s) to use, use 0 or no vararg values to specify any free port
     */
    public MockServer(final Configuration configuration, final List<ProxyConfiguration> proxyConfigurations, final Integer... localPorts) {
        super(configuration);
        createServerBootstrap(configuration, proxyConfigurations, localPorts);

        // wait to start
        getLocalPort();
    }

    /**
     * Start the instance using the ports provided
     *
     * @param remotePort the port of the remote server to connect to
     * @param remoteHost the hostname of the remote server to connect to (if null defaults to "localhost")
     * @param localPorts the local port(s) to use
     */
    public MockServer(final Integer remotePort, @Nullable final String remoteHost, final Integer... localPorts) {
        this(null, proxyConfiguration(configuration()), remoteHost, remotePort, localPorts);
    }

    /**
     * Start the instance using the ports provided
     *
     * @param remotePort the port of the remote server to connect to
     * @param remoteHost the hostname of the remote server to connect to (if null defaults to "localhost")
     * @param localPorts the local port(s) to use
     */
    public MockServer(final Configuration configuration, final Integer remotePort, @Nullable final String remoteHost, final Integer... localPorts) {
        this(configuration, proxyConfiguration(configuration), remoteHost, remotePort, localPorts);
    }

    /**
     * Start the instance using the ports provided configuring forwarded or proxied requests to go via an additional proxy
     *
     * @param localPorts the local port(s) to use
     * @param remoteHost the hostname of the remote server to connect to (if null defaults to "localhost")
     * @param remotePort the port of the remote server to connect to
     */
    public MockServer(final Configuration configuration, final ProxyConfiguration proxyConfiguration, @Nullable String remoteHost, final Integer remotePort, final Integer... localPorts) {
        this(configuration, ImmutableList.of(proxyConfiguration), remoteHost, remotePort, localPorts);
    }

    /**
     * Start the instance using the ports provided configuring forwarded or proxied requests to go via an additional proxy
     *
     * @param localPorts the local port(s) to use
     * @param remoteHost the hostname of the remote server to connect to (if null defaults to "localhost")
     * @param remotePort the port of the remote server to connect to
     */
    public MockServer(final Configuration configuration, final List<ProxyConfiguration> proxyConfigurations, @Nullable String remoteHost, final Integer remotePort, final Integer... localPorts) {
        super(configuration);
        if (remotePort == null) {
            throw new IllegalArgumentException("You must specify a remote hostname");
        }
        if (isBlank(remoteHost)) {
            remoteHost = "localhost";
        }

        remoteSocket = new InetSocketAddress(remoteHost, remotePort);
        if (proxyConfigurations != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("using proxy configuration for forwarded requests:{}")
                    .setArguments(proxyConfigurations)
            );
        }
        createServerBootstrap(configuration, proxyConfigurations, localPorts);

        // wait to start
        getLocalPort();
    }

    private void createServerBootstrap(Configuration configuration, final List<ProxyConfiguration> proxyConfigurations, final Integer... localPorts) {
        if (configuration == null) {
            configuration = configuration();
        }

        List<Integer> portBindings = singletonList(0);
        if (localPorts != null && localPorts.length > 0) {
            portBindings = Arrays.asList(localPorts);
        }

        final NettySslContextFactory nettyServerSslContextFactory = new NettySslContextFactory(configuration, mockServerLogger, true);
        final NettySslContextFactory nettyClientSslContextFactory = new NettySslContextFactory(configuration, mockServerLogger, false);
        List<AuthenticationHandler> controlPlaneAuthenticationHandlers = new ArrayList<>();
        if (configuration.controlPlaneTLSMutualAuthenticationRequired()) {
            controlPlaneAuthenticationHandlers.add(
                new MTLSAuthenticationHandler(mockServerLogger, nettyServerSslContextFactory.trustCertificateChain(configuration.controlPlaneTLSMutualAuthenticationCAChain()))
            );
        }
        if (configuration.controlPlaneJWTAuthenticationRequired()) {
            controlPlaneAuthenticationHandlers.add(
                new JWTAuthenticationHandler(mockServerLogger, configuration.controlPlaneJWTAuthenticationJWKSource())
                    .withExpectedAudience(configuration.controlPlaneJWTAuthenticationExpectedAudience())
                    .withMatchingClaims(configuration.controlPlaneJWTAuthenticationMatchingClaims())
                    .withRequiredClaims(configuration.controlPlaneJWTAuthenticationRequiredClaims())
            );
        }
        if (configuration.controlPlaneOidcAuthenticationRequired()) {
            controlPlaneAuthenticationHandlers.add(
                new OidcAuthenticationHandler(
                    mockServerLogger,
                    configuration.controlPlaneOidcJwksUri(),
                    configuration.controlPlaneOidcIssuer(),
                    configuration.controlPlaneOidcAudience(),
                    configuration.controlPlaneOidcScopeClaim(),
                    configuration.controlPlaneOidcRequiredScopes()
                )
            );
        }
        if (controlPlaneAuthenticationHandlers.size() == 1) {
            httpState.setControlPlaneAuthenticationHandler(controlPlaneAuthenticationHandlers.get(0));
        } else if (controlPlaneAuthenticationHandlers.size() > 1) {
            httpState.setControlPlaneAuthenticationHandler(
                new ChainedAuthenticationHandler(controlPlaneAuthenticationHandlers.toArray(new AuthenticationHandler[0]))
            );
        }
        MockServerUnificationInitializer initializer = new MockServerUnificationInitializer(configuration, MockServer.this, httpState, new HttpActionHandler(configuration, this::getForwardClientEventLoopGroup, httpState, proxyConfigurations, nettyClientSslContextFactory), nettyServerSslContextFactory);
        this.mcpSessionManager = initializer.getMcpSessionManager();
        serverServerBootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .channel(NettyTransport.serverSocketChannelClassFor(bossGroup))
            .childOption(ChannelOption.AUTO_READ, true)
            .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(8 * 1024, 32 * 1024))
            .childHandler(initializer)
            .childAttr(REMOTE_SOCKET, remoteSocket)
            .childAttr(PROXYING, remoteSocket != null);

        // Apply IP_TRANSPARENT socket option when TPROXY mode is enabled
        org.mockserver.netty.proxy.MockServerIpTransparentHelper.applyIfEnabled(serverServerBootstrap, configuration);

        try {
            bindServerPorts(portBindings);
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception binding to port(s) " + portBindings)
                    .setThrowable(throwable)
            );
            stop();
            throw throwable;
        }

        if (Boolean.TRUE.equals(configuration.dnsEnabled())) {
            try {
                bindDnsPort(configuration);
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("exception binding DNS port - DNS mocking disabled")
                        .setThrowable(throwable)
                );
            }
        }

        // start HTTP/3 (QUIC) server when configured (http3Port > 0)
        Integer http3Port = configuration.http3Port();
        if (http3Port != null && http3Port > 0) {
            startHttp3Server(configuration, initializer.getActionHandler(), http3Port, this.mcpSessionManager);
        }

        // Register the AsyncAPI control-plane if mockserver-async is on the classpath.
        // Uses reflection to avoid a hard compile-time dependency — when the module is
        // absent the endpoint gracefully responds 501 (Not Implemented).
        try {
            Class<?> asyncCp = Class.forName("org.mockserver.async.controlplane.AsyncApiControlPlaneImpl");
            java.lang.reflect.Method register = asyncCp.getMethod("registerIfAvailable");
            register.invoke(null);
        } catch (ClassNotFoundException ignored) {
            // mockserver-async not on classpath — AsyncAPI endpoints will return 501
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("failed to register AsyncAPI control-plane: " + e.getMessage())
            );
        }

        startedServer(getLocalPorts());
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteSocket;
    }

    public MockServer registerListener(ExpectationsListener expectationsListener) {
        super.registerListener(expectationsListener);
        return this;
    }

    private void bindDnsPort(Configuration configuration) {
        int dnsPort = configuration.dnsPort() != null ? configuration.dnsPort() : 0;
        DnsRequestHandler dnsHandler = new DnsRequestHandler(mockServerLogger, httpState);
        Bootstrap dnsBootstrap = new Bootstrap()
            .group(workerGroup)
            .channel(NettyTransport.datagramChannelClassFor(workerGroup))
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(new ChannelInitializer<DatagramChannel>() {
                @Override
                protected void initChannel(DatagramChannel ch) {
                    ch.pipeline()
                        .addLast(new DatagramDnsQueryDecoder())
                        .addLast(new DatagramDnsResponseEncoder())
                        .addLast(dnsHandler);
                }
            });
        dnsChannel = dnsBootstrap.bind(dnsPort).syncUninterruptibly().channel();
        int boundPort = ((InetSocketAddress) dnsChannel.localAddress()).getPort();
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("DNS mock server started on port: {}")
                    .setArguments(boundPort)
            );
        }
    }

    public int getDnsPort() {
        if (dnsChannel != null && dnsChannel.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) dnsChannel.localAddress()).getPort();
        }
        return -1;
    }

    /**
     * Returns the bound HTTP/3 (QUIC) UDP port, or -1 if the HTTP/3 server is
     * not running.
     */
    public int getHttp3Port() {
        Http3Server server = http3Server;
        return server != null ? server.getPort() : -1;
    }

    /**
     * Returns the current number of active HTTP/3 (QUIC) connections,
     * or 0 if the HTTP/3 server is not running.
     */
    public int getHttp3ActiveConnectionCount() {
        Http3Server server = http3Server;
        return server != null ? server.getActiveConnectionCount() : 0;
    }

    private void startHttp3Server(Configuration configuration, HttpActionHandler actionHandler, int http3Port, org.mockserver.netty.mcp.McpSessionManager mcpSessionMgr) {
        if (!Http3Server.isQuicAvailable()) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("native QUIC transport not available on this platform - HTTP/3 server disabled (http3Port was set to {})")
                    .setArguments(http3Port)
            );
            return;
        }
        try {
            Http3Server server = new Http3Server(configuration, mockServerLogger, httpState, actionHandler, MockServer.this, mcpSessionMgr);
            int boundPort = server.start(http3Port);
            this.http3Server = server;
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("HTTP/3 (QUIC) server started on UDP port: {}")
                    .setArguments(boundPort)
            );
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("exception starting HTTP/3 server on port {} - HTTP/3 disabled")
                    .setArguments(http3Port)
                    .setThrowable(throwable)
            );
        }
    }

    @Override
    public CompletableFuture<String> stopAsync() {
        if (http3Server != null) {
            http3Server.stop();
            http3Server = null;
        }
        if (dnsChannel != null) {
            dnsChannel.close();
        }
        if (mcpSessionManager != null) {
            mcpSessionManager.shutdown();
        }
        return super.stopAsync();
    }

}
