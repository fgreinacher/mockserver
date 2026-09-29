package org.mockserver.mappers;

import org.apache.commons.lang3.StringUtils;
import org.mockserver.codec.ExpandedParameterDecoder;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.Map;

/**
 * A Netty request or response as a MockServer one, for a log entry about a message that could not be mapped, so
 * {@code redactSecretsInLog} can mask the credentials its text quotes (the Netty message's {@code toString()} lists
 * every header). Nothing is decoded — decoding may be what failed — and nothing here throws.
 */
public final class NettyMessageForLog {

    private NettyMessageForLog() {
    }

    public static HttpRequest request(io.netty.handler.codec.http.HttpRequest nettyRequest) {
        if (nettyRequest == null) {
            return null;
        }
        try {
            String uri = nettyRequest.uri() == null ? "" : nettyRequest.uri();
            HttpRequest request = HttpRequest.request(StringUtils.substringBefore(uri, "?"))
                .withQueryStringParameters(ExpandedParameterDecoder.undecodedParameters(uri, true));
            if (nettyRequest.method() != null) {
                request.withMethod(nettyRequest.method().name());
            }
            for (Map.Entry<String, String> header : nettyRequest.headers()) {
                request.withHeader(header.getKey(), header.getValue());
            }
            return request;
        } catch (RuntimeException unmappable) {
            return null;
        }
    }

    public static HttpResponse response(io.netty.handler.codec.http.HttpResponse nettyResponse) {
        if (nettyResponse == null) {
            return null;
        }
        try {
            HttpResponse response = HttpResponse.response();
            for (Map.Entry<String, String> header : nettyResponse.headers()) {
                response.withHeader(header.getKey(), header.getValue());
            }
            return response;
        } catch (RuntimeException unmappable) {
            return null;
        }
    }
}
