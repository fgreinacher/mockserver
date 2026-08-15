package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.ssl.SslHandler;
import org.mockserver.codec.StreamingAwareHttpObjectAggregator;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.model.Protocol.HTTP_2;
import static org.mockserver.netty.unification.PortUnificationHandler.isSslEnabledDownstream;
import static org.mockserver.netty.unification.PortUnificationHandler.nettySslContextFactory;
import static org.mockserver.socket.tls.SniHandler.getALPNProtocol;

public class UpstreamProxyRelayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final MockServerLogger mockServerLogger;
    private final Channel upstreamChannel;
    private final Channel downstreamChannel;
    private final String host;
    private final int port;

    public UpstreamProxyRelayHandler(MockServerLogger mockServerLogger, Channel upstreamChannel, Channel downstreamChannel, String host, int port) {
        super(false);
        this.upstreamChannel = upstreamChannel;
        this.downstreamChannel = downstreamChannel;
        this.host = host;
        this.port = port;
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
        ctx.write(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest request) {
        if (isSslEnabledDownstream(upstreamChannel) && downstreamChannel.pipeline().get(SslHandler.class) == null) {
            io.netty.handler.ssl.SslContext clientSslContext = nettySslContextFactory(ctx.channel())
                .createClientSslContext(true, HTTP_2.equals(getALPNProtocol(mockServerLogger, ctx)));
            // Give HTTPS endpoint identification (host name verification for JVM / CUSTOM trust managers) a
            // reference identity: the CONNECT target host/port, mirroring the sibling loopback TLS in
            // RelayConnectHandler#configurePipelines. The connected downstream SOCKET address is the wrong
            // thing to verify against — its getHostString() is the MockServer loopback ("0.0.0.0") on the
            // common forward-proxy path and only coincidentally the target on a reverse-proxy path — so the
            // identity is keyed off the CONNECT host, never the socket. Falls back to the no-host handler
            // (verification skipped, as before) only when the CONNECT host is unknown.
            SslHandler sslHandler = isNotBlank(host)
                ? clientSslContext.newHandler(ctx.alloc(), host, port)
                : clientSslContext.newHandler(ctx.alloc());
            downstreamChannel.pipeline().addFirst(sslHandler);
        }
        // Propagate the request's streaming intent onto the loopback channel so the relay-only
        // StreamingAwareHttpObjectAggregator streams the matching response incrementally even when the
        // upstream omits Content-Type: text/event-stream (e.g. the OpenAI Codex backend used by the
        // opencode CLI). Set per-request (overwritten on every request, true OR cleared to null) so a
        // keep-alive tunnel carrying many requests applies the intent only to the response it belongs to.
        downstreamChannel.attr(StreamingAwareHttpObjectAggregator.EXPECT_STREAMING_RESPONSE)
            .set(StreamingAwareHttpObjectAggregator.requestExpectsStreamingResponse(request) ? Boolean.TRUE : null);
        // Diagnostic only — record the forward time and request line so the relay-only
        // StreamingAwareHttpObjectAggregator can report a time-to-first-byte in its DEBUG
        // streaming-decision log when the matching response head arrives. Behaviour-preserving.
        downstreamChannel.attr(StreamingAwareHttpObjectAggregator.REQUEST_FORWARDED_NANOS).set(System.nanoTime());
        downstreamChannel.attr(StreamingAwareHttpObjectAggregator.REQUEST_LINE).set(request.method() + " " + request.uri());
        downstreamChannel.writeAndFlush(request).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                ctx.channel().read();
            } else {
                if (isNotSocketClosedException(future.cause())) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception while returning response for request \"" + request.method() + " " + request.uri() + "\"")
                            .setThrowable(future.cause())
                    );
                }
                future.channel().close();
            }
        });
    }

    private boolean isNotSocketClosedException(Throwable cause) {
        return !(cause instanceof ClosedChannelException || cause instanceof ClosedSelectorException);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeOnFlush(downstreamChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by upstream relay handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("SSL or decoder fault caught by upstream relay handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel(), upstreamChannel, downstreamChannel))
                    .setThrowable(cause)
            );
        }
        closeOnFlush(ctx.channel());
    }

}
