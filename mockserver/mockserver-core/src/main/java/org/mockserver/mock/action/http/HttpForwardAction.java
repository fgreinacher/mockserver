package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcForwardTranslator;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.filters.HopByHopHeaderFilter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.SocketAddress;
import org.slf4j.event.Level;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.HttpResponse.badGatewayResponse;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public abstract class HttpForwardAction {

    protected final MockServerLogger mockServerLogger;
    protected final Configuration configuration;
    private final NettyHttpClient httpClient;
    private HopByHopHeaderFilter hopByHopHeaderFilter = new HopByHopHeaderFilter();
    // gRPC forward-proxy: when set (threaded from HttpActionHandler), a matched FORWARD-class
    // action whose request is a decoded gRPC exchange is re-encoded to protobuf frames before the
    // upstream call and the upstream response is decoded back to JSON. Null (the default) leaves
    // ordinary HTTP forwarding byte-for-byte unchanged.
    private GrpcProtoDescriptorStore grpcDescriptorStore;

    HttpForwardAction(MockServerLogger mockServerLogger, Configuration configuration, NettyHttpClient httpClient) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
        this.httpClient = httpClient;
    }

    public void setGrpcDescriptorStore(GrpcProtoDescriptorStore grpcDescriptorStore) {
        this.grpcDescriptorStore = grpcDescriptorStore;
    }

    protected HttpForwardActionResult sendRequest(HttpRequest request, @Nullable InetSocketAddress remoteAddress, Function<HttpResponse, HttpResponse> overrideHttpResponse) {
        return sendRequest(request, remoteAddress, overrideHttpResponse, false);
    }

    protected HttpForwardActionResult sendRequest(HttpRequest request, @Nullable InetSocketAddress remoteAddress, Function<HttpResponse, HttpResponse> overrideHttpResponse, boolean disableStreaming) {
        // Resolved once outside the try so the catch block can feed a synchronous failure back into
        // the circuit breaker (otherwise a half-open trial that throws synchronously would never
        // release its trial slot and the breaker would be stranded open). Null when the breaker is
        // disabled, so the default forward path is byte-for-byte unchanged.
        String circuitKey = null;
        boolean circuitBreakerEnabled = configuration != null
            && Boolean.TRUE.equals(configuration.forwardProxyCircuitBreakerEnabled());
        try {
            // By default force the forwarded request into HTTP/1.1 (protocol nulled => NettyHttpClient
            // defaults to HTTP/1.1). When forwardProxyHttp2Enabled is set the inbound request's protocol
            // is preserved instead, so an HTTP/2 inbound is forwarded upstream as HTTP/2. HTTP/2 only
            // actually flows over TLS+ALPN: NettyHttpClient downgrades a non-secure HTTP/2 request to
            // HTTP/1.1, so cleartext (h2c) is never attempted. HTTP/2 forwards are not pooled.
            boolean forwardProxyHttp2Enabled = configuration != null
                && Boolean.TRUE.equals(configuration.forwardProxyHttp2Enabled());
            // forwardProxyHttp2Upgrade forces a SECURE (TLS) forward upstream over HTTP/2 via ALPN even
            // when the inbound client is HTTP/1.1 (ALPN falls back to HTTP/1.1 if the upstream does not
            // negotiate h2). This takes precedence over the preserve-inbound case so an h1 inbound is
            // upgraded; a non-secure request is left unchanged (HTTP/2 needs TLS+ALPN — NettyHttpClient
            // downgrades a non-secure HTTP/2 request back to HTTP/1.1 anyway).
            boolean forwardProxyHttp2Upgrade = configuration != null
                && Boolean.TRUE.equals(configuration.forwardProxyHttp2Upgrade())
                && Boolean.TRUE.equals(request.isSecure());
            HttpRequest filtered = hopByHopHeaderFilter.onRequest(request);
            HttpRequest toSend = forwardProxyHttp2Upgrade
                ? filtered.withProtocol(org.mockserver.model.Protocol.HTTP_2)
                : forwardProxyHttp2Enabled
                    ? filtered.withProtocol(request.getProtocol())
                    : filtered.withProtocol(null);

            // gRPC forward-proxy: re-encode a decoded gRPC request (JSON body + x-grpc-* headers,
            // produced by GrpcToHttpRequestHandler) back into gRPC-framed protobuf and force HTTP/2 so
            // the upstream gRPC server receives a valid call. The upstream framed response is decoded
            // back to JSON via the composed override below, making both the client response and the
            // recorded FORWARDED_REQUEST replayable. No-op when the request is not a gRPC exchange.
            final boolean grpcForward = grpcDescriptorStore != null
                && grpcDescriptorStore.hasServices()
                && GrpcForwardTranslator.isGrpcForwardRequest(request);
            Function<HttpResponse, HttpResponse> effectiveOverride = overrideHttpResponse;
            if (grpcForward) {
                final String grpcService = request.getFirstHeader(GrpcForwardTranslator.SERVICE_HEADER);
                final String grpcMethod = request.getFirstHeader(GrpcForwardTranslator.METHOD_HEADER);
                toSend = GrpcForwardTranslator.encodeRequestForUpstream(toSend, grpcDescriptorStore);
                final Function<HttpResponse, HttpResponse> decode =
                    resp -> GrpcForwardTranslator.decodeResponseFromUpstream(resp, grpcService, grpcMethod, grpcDescriptorStore);
                effectiveOverride = overrideHttpResponse == null ? decode : overrideHttpResponse.andThen(decode);
            }

            // Per-upstream circuit breaker (default off): when the breaker for this upstream is
            // open, fail fast with a 503 instead of attempting the forward. Resolve the key from the
            // explicit remote address when present, otherwise from the request's Host header.
            if (circuitBreakerEnabled) {
                circuitKey = ForwardCircuitBreaker.keyFor(remoteAddress != null ? remoteAddress : safeSocketAddressFromHostHeader(toSend));
            }
            if (circuitBreakerEnabled && !ForwardCircuitBreaker.getInstance().allowRequest(configuration, circuitKey)) {
                CompletableFuture<HttpResponse> openFuture = new CompletableFuture<>();
                openFuture.complete(
                    response()
                        .withStatusCode(503)
                        .withReasonPhrase("Service Unavailable")
                        .withBody("upstream circuit breaker open for " + circuitKey)
                );
                return new HttpForwardActionResult(request, openFuture, overrideHttpResponse, remoteAddress);
            }

            // Retry policy (default off, maxRetries=0): re-issues the upstream call for idempotent
            // methods on a transient failure (connection error or 502/503/504) with linear back-off.
            final int maxRetries = configuration != null ? configuration.forwardProxyRetryCount() : 0;
            final long backoffMillis = configuration != null ? configuration.forwardProxyRetryBackoffMillis() : 0L;
            final HttpRequest finalToSend = toSend;
            final Supplier<CompletableFuture<HttpResponse>> attempt = () -> disableStreaming
                ? httpClient.sendRequest(finalToSend, remoteAddress, configuration.socketConnectionTimeoutInMillis(), true)
                : httpClient.sendRequest(finalToSend, remoteAddress);

            CompletableFuture<HttpResponse> responseFuture =
                ForwardRetryPolicy.execute(toSend.getMethod(""), maxRetries, backoffMillis, attempt);

            // Feed the final outcome back into the circuit breaker. Only chained when the breaker is
            // enabled and a key resolved, so when disabled the upstream future flows through unchanged.
            if (circuitBreakerEnabled && circuitKey != null) {
                final String key = circuitKey;
                responseFuture = responseFuture.whenComplete((res, throwable) -> {
                    if (ForwardRetryPolicy.isTransientFailure(res, throwable)) {
                        ForwardCircuitBreaker.getInstance().recordFailure(configuration, key);
                    } else {
                        ForwardCircuitBreaker.getInstance().recordSuccess(configuration, key);
                    }
                });
            }

            return new HttpForwardActionResult(request, responseFuture, effectiveOverride, remoteAddress);
        } catch (Exception e) {
            // A synchronous failure (e.g. the upstream connection throws before a future is returned)
            // must still count against the breaker so a stuck half-open trial slot is released and the
            // breaker can re-probe recovery rather than stranding open.
            if (circuitBreakerEnabled && circuitKey != null) {
                ForwardCircuitBreaker.getInstance().recordFailure(configuration, circuitKey);
            }
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequest(request)
                    .setMessageFormat("exception forwarding request " + request)
                    .setThrowable(e)
            );
        }
        return badGatewayFuture(request);
    }

    private static InetSocketAddress safeSocketAddressFromHostHeader(HttpRequest request) {
        try {
            return request.socketAddressFromHostHeader();
        } catch (Exception ignore) {
            // No usable Host header — circuit breaker simply does not key on this request.
            return null;
        }
    }

    protected void adjustHostHeader(HttpRequest request) {
        if (configuration != null) {
            String defaultHostHeader = configuration.forwardDefaultHostHeader();
            if (isNotBlank(defaultHostHeader)) {
                request.replaceHeader(new Header("Host", defaultHostHeader));
            } else if (configuration.forwardAdjustHostHeader()) {
                SocketAddress sa = request.getSocketAddress();
                if (sa != null && isNotBlank(sa.getHost())) {
                    boolean defaultPort = (SocketAddress.Scheme.HTTPS.equals(sa.getScheme()) && sa.getPort() != null && sa.getPort() == 443)
                        || (SocketAddress.Scheme.HTTP.equals(sa.getScheme()) && sa.getPort() != null && sa.getPort() == 80)
                        || (sa.getPort() == null);
                    String hostHeader = defaultPort ? sa.getHost() : sa.getHost() + ":" + sa.getPort();
                    request.replaceHeader(new Header("Host", hostHeader));
                }
            }
        }
    }

    HttpForwardActionResult badGatewayFuture(HttpRequest httpRequest) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(badGatewayResponse());
        return new HttpForwardActionResult(httpRequest, future, null);
    }
}
