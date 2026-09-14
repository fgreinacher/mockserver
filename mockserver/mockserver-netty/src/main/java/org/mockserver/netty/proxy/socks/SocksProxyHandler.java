package org.mockserver.netty.proxy.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.netty.HttpRequestHandler.setProxyingRequest;
import static org.mockserver.netty.unification.PortUnificationHandler.disableSslDownstream;
import static org.mockserver.netty.unification.PortUnificationHandler.enableSslUpstreamAndDownstream;

@ChannelHandler.Sharable
public abstract class SocksProxyHandler<T> extends SimpleChannelInboundHandler<T> {

    protected final Configuration configuration;
    protected final LifeCycle server;
    protected final MockServerLogger mockServerLogger;

    public SocksProxyHandler(Configuration configuration, MockServerLogger mockServerLogger, LifeCycle server) {
        super(false);
        this.configuration = configuration;
        this.server = server;
        this.mockServerLogger = mockServerLogger;
    }

    protected void forwardConnection(final ChannelHandlerContext ctx, ChannelHandler forwarder, final String addr, int port) {
        Channel channel = ctx.channel();
        setProxyingRequest(ctx, Boolean.TRUE);
        if (String.valueOf(port).endsWith("80")) {
            disableSslDownstream(channel);
        } else if (String.valueOf(port).endsWith("443")) {
            // A 443 target means the client will negotiate TLS inside the SOCKS tunnel. Enable BOTH the
            // upstream and downstream TLS flags (not just downstream) so the relay terminates that TLS
            // itself and waits for its ALPN result before provisioning the loopback - the same path the
            // CONNECT proxy takes. Setting only the downstream flag left the relay provisioning HTTP/1.1
            // before ALPN was known, so an h2 request through the tunnel was mis-provisioned (issue #2685).
            enableSslUpstreamAndDownstream(channel);
        }

        // add Subject Alternative Name for SSL certificate
        if (isNotBlank(addr)) {
            server.getScheduler().submit(() -> configuration.addSubjectAlternativeName(addr));
        }

        ctx.pipeline().replace(this, null, forwarder);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
        // a mid-pipeline handler that swallows channelReadComplete starves Netty's HTTP/2
        // flow-control flush (Http2ConnectionHandler.channelReadComplete -> writePendingBytes),
        // stalling any h2 response larger than the peer's initial window - so propagate the event
        ctx.fireChannelReadComplete();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by SOCKS proxy handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("SSL or decoder fault caught by SOCKS proxy handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel()))
                    .setThrowable(cause)
            );
        }
        ctx.close();
    }
}
