package org.mockserver.httpclient;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AttributeKey;
import org.mockserver.codec.MockServerHttpClientCodec;
import org.mockserver.codec.StreamingAwareHttpObjectAggregator;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.proxyconfiguration.ProxyConfiguration;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockserver.httpclient.NettyHttpClient.DISABLE_RESPONSE_STREAMING;
import static org.mockserver.httpclient.NettyHttpClient.ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE;
import static org.mockserver.httpclient.NettyHttpClient.EXPECT_STREAMING_RESPONSE;
import static org.mockserver.httpclient.NettyHttpClient.FIRST_BYTE_MILLIS;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * Per-stream child initializer used with {@link io.netty.handler.codec.http2.Http2MultiplexHandler}
 * on the {@link NettyHttpClient} forward path. It mirrors the HTTP/1.1 forward pipeline built by
 * {@link HttpClientInitializer#configureHttp1Pipeline} but on an HTTP/2 stream channel, so a streamed
 * upstream response (Server-Sent Events) is relayed incrementally instead of being aggregated to
 * completion before its head reaches the proxy client.
 * <p>
 * An HTTP/2 stream channel is decoded into the same unaggregated {@code HttpObject}s that the
 * HTTP/1.1 path produces ({@link Http2StreamFrameToHttpObjectCodec} in CLIENT, non-aggregating mode),
 * then the identical shared streaming machinery is reused: {@link HttpContentDecompressor},
 * {@link TimeToFirstByteHandler}, {@link StreamingAwareHttpObjectAggregator} (which relays
 * {@code text/event-stream} / client-requested streams via {@link StreamingResponseRelayHandler} and
 * aggregates everything else to a {@code FullHttpResponse}), {@link MockServerHttpClientCodec}, and the
 * shared {@link HttpClientHandler}.
 * <p>
 * HTTP/2 child channels do NOT inherit parent-channel attributes, so the per-request attributes the
 * forward machinery reads off the channel are copied from the parent to the child in
 * {@link #initChannel}. A close listener closes the parent connection when the single stream ends,
 * preserving the non-pooled "close after one response" behaviour of HTTP/2 forwards.
 */
public class Http2ForwardStreamChildInitializer extends ChannelInitializer<Http2StreamChannel> {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations;
    private final ChannelHandler httpClientHandler;
    private final ChannelHandler httpClientConnectionHandler;

    Http2ForwardStreamChildInitializer(Configuration configuration, MockServerLogger mockServerLogger, Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations, ChannelHandler httpClientHandler, ChannelHandler httpClientConnectionHandler) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.proxyConfigurations = proxyConfigurations;
        this.httpClientHandler = httpClientHandler;
        this.httpClientConnectionHandler = httpClientConnectionHandler;
    }

    @Override
    protected void initChannel(Http2StreamChannel ch) {
        Channel parent = ch.parent();

        // HTTP/2 child channels do NOT inherit parent-channel attributes, so propagate the
        // per-request attributes the forward machinery reads off the channel (the response future to
        // complete, the closed-without-response guard, the TTFB stamp, and the two streaming-mode
        // hints). Without these the streaming relay / aggregator and HttpClientHandler would see an
        // empty channel and never complete the response future.
        copyAttribute(parent, ch, RESPONSE_FUTURE);
        copyAttribute(parent, ch, ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE);
        copyAttribute(parent, ch, FIRST_BYTE_MILLIS);
        copyAttribute(parent, ch, DISABLE_RESPONSE_STREAMING);
        copyAttribute(parent, ch, EXPECT_STREAMING_RESPONSE);

        ChannelPipeline pipeline = ch.pipeline();

        // Completes the response future exceptionally if the stream closes before a valid response
        // (mirrors the HTTP/1.1 pipeline's HttpClientConnectionErrorHandler). @Sharable, so the same
        // instance is safely added to every child stream.
        if (httpClientConnectionHandler != null) {
            pipeline.addLast(httpClientConnectionHandler);
        }

        // Per-stream read timeout, the streaming-aware analogue of the HTTP/1.1 pipeline's read
        // timeout: it bounds an in-flight upstream stream that connects but never sends its head. When
        // a streaming response is detected, StreamingAwareHttpObjectAggregator swaps it for the
        // longer, stream-appropriate IdleStateHandler so a slow-but-healthy stream is not killed.
        long readTimeoutMillis = configuration != null ? configuration.maxSocketTimeoutInMillis() : 0;
        if (readTimeoutMillis > 0) {
            pipeline.addLast(new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS));
        }

        // CLIENT, non-aggregating mode: emit the same unaggregated HttpObjects the HTTP/1.1 path does
        // (HttpResponse head, HttpContent chunks, LastHttpContent) and encode the outbound
        // FullHttpRequest to HEADERS + DATA frames.
        pipeline.addLast(new Http2StreamFrameToHttpObjectCodec(false));
        pipeline.addLast(new HttpContentDecompressor());
        pipeline.addLast(new TimeToFirstByteHandler());
        if (configuration != null) {
            pipeline.addLast(new StreamingAwareHttpObjectAggregator(configuration.maxResponseBodySize(), configuration, mockServerLogger));
        } else {
            pipeline.addLast(new StreamingAwareHttpObjectAggregator(ConfigurationProperties.maxResponseBodySize()));
        }
        pipeline.addLast(new MockServerHttpClientCodec(mockServerLogger, proxyConfigurations));
        pipeline.addLast(httpClientHandler);

        // HTTP/2 forwards are not pooled — close the parent connection when this single stream ends so
        // the "close after one response" lifecycle of a non-pooled forward is preserved.
        ch.closeFuture().addListener(future -> {
            if (parent.isActive()) {
                parent.close();
            }
        });
    }

    private static <T> void copyAttribute(Channel from, Channel to, AttributeKey<T> key) {
        T value = from.attr(key).get();
        if (value != null) {
            to.attr(key).set(value);
        }
    }
}
