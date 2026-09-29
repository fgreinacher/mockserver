package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.HttpObject;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.SensitiveLogValue;
import org.mockserver.mappers.NettyMessageForLog;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;

import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;

public class DownstreamProxyRelayHandler extends SimpleChannelInboundHandler<HttpObject> {

    private final MockServerLogger mockServerLogger;
    private final Channel upstreamChannel;

    public DownstreamProxyRelayHandler(MockServerLogger mockServerLogger, Channel upstreamChannel) {
        super(false);
        this.upstreamChannel = upstreamChannel;
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
        ctx.write(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) {
        upstreamChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                ctx.read();
            } else {
                if (isNotSocketClosedException(future.cause())) {
                    mockServerLogger.logEvent(writeFailure(msg, future.cause()));
                }
                future.channel().close();
            }
        });
    }

    /**
     * The relayed message's text lists every header, so it is logged with its headers attached for redactSecretsInLog
     * to mask; a message with no headers of its own is masked whole. The text is taken now: the message may already
     * be released, and the entry must not keep it.
     */
    static LogEntry writeFailure(HttpObject msg, Throwable cause) {
        LogEntry logEntry = new LogEntry()
            .setLogLevel(Level.ERROR)
            .setMessageFormat("exception while returning writing:{}")
            .setThrowable(cause);
        String text = String.valueOf(msg);
        org.mockserver.model.HttpResponse headersOfResponse = msg instanceof io.netty.handler.codec.http.HttpResponse ? NettyMessageForLog.response((io.netty.handler.codec.http.HttpResponse) msg) : null;
        org.mockserver.model.HttpRequest headersOfRequest = msg instanceof io.netty.handler.codec.http.HttpRequest ? NettyMessageForLog.request((io.netty.handler.codec.http.HttpRequest) msg) : null;
        if (headersOfResponse != null) {
            logEntry.setHttpResponse(headersOfResponse).setArguments(text);
        } else if (headersOfRequest != null) {
            logEntry.setHttpRequest(headersOfRequest).setArguments(text);
        } else {
            // no headers to redact against: masked whole
            logEntry.setArguments(SensitiveLogValue.of(text));
        }
        return logEntry;
    }

    private boolean isNotSocketClosedException(Throwable cause) {
        return !(cause instanceof ClosedChannelException || cause instanceof ClosedSelectorException);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeOnFlush(upstreamChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by downstream relay handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault caught by downstream relay handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel(), upstreamChannel))
                        .setThrowable(cause)
                );
            }
        }
        closeOnFlush(ctx.channel());
    }

}
