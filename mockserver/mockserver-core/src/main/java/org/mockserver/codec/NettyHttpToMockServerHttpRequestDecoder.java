package org.mockserver.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.FullHttpRequest;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.FullHttpRequestToMockServerHttpRequest;
import org.mockserver.model.Header;

import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.List;

import static org.mockserver.socket.tls.SniHandler.getALPNProtocol;

/**
 * @author jamesdbloom
 */
public class NettyHttpToMockServerHttpRequestDecoder extends MessageToMessageDecoder<FullHttpRequest> {

    private final FullHttpRequestToMockServerHttpRequest fullHttpRequestToMockServerRequest;
    private final MockServerLogger mockServerLogger;

    public NettyHttpToMockServerHttpRequestDecoder(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, Integer port) {
        this(mockServerLogger, new FullHttpRequestToMockServerHttpRequest(configuration, mockServerLogger, isSecure, clientCertificates, port));
    }

    public NettyHttpToMockServerHttpRequestDecoder(MockServerLogger mockServerLogger, FullHttpRequestToMockServerHttpRequest fullHttpRequestToMockServerRequest) {
        this.mockServerLogger = mockServerLogger;
        this.fullHttpRequestToMockServerRequest = fullHttpRequestToMockServerRequest;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, FullHttpRequest fullHttpRequest, List<Object> out) {
        List<Header> preservedHeaders = null;
        byte[] originalRawBody = null;
        SocketAddress localAddress = null;
        SocketAddress remoteAddress = null;
        if (ctx != null && ctx.channel() != null) {
            PreserveHeadersNettyRemoves.PreservedRequest preserved = PreserveHeadersNettyRemoves.preservedRequest(ctx.channel());
            preservedHeaders = preserved.headers();
            originalRawBody = preserved.originalRawBody();
            localAddress = ctx.channel().localAddress();
            remoteAddress = ctx.channel().remoteAddress();
        }
        out.add(fullHttpRequestToMockServerRequest.mapFullHttpRequestToMockServerRequest(fullHttpRequest, preservedHeaders, originalRawBody, localAddress, remoteAddress, getALPNProtocol(mockServerLogger, ctx)));
    }

}
