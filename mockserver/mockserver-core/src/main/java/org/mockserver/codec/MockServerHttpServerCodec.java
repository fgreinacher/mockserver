package org.mockserver.codec;

import io.netty.channel.CombinedChannelDuplexHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.FullHttpRequestToMockServerHttpRequest;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;

/**
 * @author jamesdbloom
 */
public class MockServerHttpServerCodec extends CombinedChannelDuplexHandler<NettyHttpToMockServerHttpRequestDecoder, MockServerHttpToNettyHttpResponseEncoder> {

    public MockServerHttpServerCodec(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, SocketAddress socketAddress) {
        this(configuration, mockServerLogger, isSecure, clientCertificates, socketAddress instanceof InetSocketAddress ? ((InetSocketAddress) socketAddress).getPort() : null);
    }

    public MockServerHttpServerCodec(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, Integer port) {
        init(new NettyHttpToMockServerHttpRequestDecoder(configuration, mockServerLogger, isSecure, clientCertificates, port), new MockServerHttpToNettyHttpResponseEncoder(mockServerLogger));
    }

    /**
     * Builds the codec around mappers the caller already holds, so pipelines that serve the same connection
     * (the stream child channels of one HTTP/2 connection) can share them. The mappers must only be used from
     * one event loop: the request mapper memoises the connection's address strings, the fields extracted from
     * its client certificate chain, and the previous request's header wrappers, without synchronisation.
     */
    public MockServerHttpServerCodec(MockServerLogger mockServerLogger, FullHttpRequestToMockServerHttpRequest requestMapper, MockServerHttpResponseToFullHttpResponse responseMapper) {
        init(new NettyHttpToMockServerHttpRequestDecoder(mockServerLogger, requestMapper), new MockServerHttpToNettyHttpResponseEncoder(responseMapper));
    }

}
