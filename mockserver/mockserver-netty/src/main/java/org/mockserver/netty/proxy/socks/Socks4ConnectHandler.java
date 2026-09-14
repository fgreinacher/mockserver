package org.mockserver.netty.proxy.socks;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.util.NetUtil;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;

@ChannelHandler.Sharable
public final class Socks4ConnectHandler extends SocksConnectHandler<Socks4CommandRequest> {

    public Socks4ConnectHandler(Configuration configuration, MockServerLogger mockServerLogger, LifeCycle server, String host, int port) {
        super(configuration, mockServerLogger, server, host, port);
    }

    protected void removeCodecSupport(ChannelHandlerContext ctx) {
        super.removeCodecSupport(ctx);
        removeHandler(ctx.pipeline(), Socks4ServerEncoder.class);
    }

    protected Object successResponse(Object request) {
        return socks4Response(Socks4CommandStatus.SUCCESS);
    }

    protected Object failureResponse(Object request) {
        return socks4Response(Socks4CommandStatus.REJECTED_OR_FAILED);
    }

    /**
     * Build a SOCKS4 command response without letting a SOCKS4a hostname reach a field that must be an IPv4
     * literal. A SOCKS4a client (curl's {@code socks4a://}) sends a domain name as the destination, which
     * arrives here as {@link #host}. Netty's {@link DefaultSocks4CommandResponse} rejects a non-IPv4
     * {@code dstAddr} with an {@link IllegalArgumentException}, and because that was thrown while writing the
     * reply the client never received one and hung forever (curl exit 28). The reply's DSTIP/DSTPORT fields
     * are ignored by clients, so for a hostname we omit them (the encoder emits {@code 0.0.0.0:0}); for a
     * classic SOCKS4 request the destination is already an IPv4 literal, which we still echo back unchanged.
     */
    private DefaultSocks4CommandResponse socks4Response(Socks4CommandStatus status) {
        if (host != null && NetUtil.isValidIpV4Address(host)) {
            return new DefaultSocks4CommandResponse(status, host, port);
        }
        return new DefaultSocks4CommandResponse(status);
    }
}
