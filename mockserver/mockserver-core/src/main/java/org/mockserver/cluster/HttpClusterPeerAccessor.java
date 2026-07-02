package org.mockserver.cluster;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.serialization.LogEventRequestAndResponseSerializer;
import org.mockserver.serialization.RequestDefinitionSerializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Default {@link ClusterFanIn.PeerAccessor} that queries a peer's control-plane
 * {@code PUT /mockserver/retrieve} endpoint over HTTP using the JDK
 * {@link java.net.http.HttpClient}.
 * <p>
 * Every request carries {@code fanInLocalOnly=true} so the peer serves ONLY from its
 * own log and does not fan out again (infinite-recursion guard). Responses are parsed
 * with the same serializers the control plane uses, so the merged objects are identical
 * in shape to the local ones.
 * <p>
 * <b>Authenticated clusters:</b> when {@code clusterFanInPeerAuthToken} is set, every
 * peer query carries it verbatim as the control-plane {@code Authorization} header
 * (e.g. {@code Bearer <jwt>}), so a cluster with control-plane authentication
 * (bearer/JWT/OIDC) accepts the fan-in query instead of rejecting it with 401/403.
 * All nodes must share the same token / trust. With no token configured (the default)
 * no credential is sent — unchanged behaviour. mTLS client-certificate presentation
 * for peer queries remains a documented boundary (see {@code docs/code/clustered-state.md}).
 */
public class HttpClusterPeerAccessor implements ClusterFanIn.PeerAccessor {

    private final RequestDefinitionSerializer requestDefinitionSerializer;
    private final LogEventRequestAndResponseSerializer logEventRequestAndResponseSerializer;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final String peerAuthToken;

    public HttpClusterPeerAccessor(Configuration configuration, MockServerLogger mockServerLogger) {
        this.requestDefinitionSerializer = new RequestDefinitionSerializer(mockServerLogger);
        this.logEventRequestAndResponseSerializer = new LogEventRequestAndResponseSerializer(mockServerLogger);
        Long timeoutMillis = configuration.maxSocketTimeoutInMillis();
        this.requestTimeout = Duration.ofMillis(timeoutMillis != null && timeoutMillis > 0 ? timeoutMillis : 20_000L);
        String token = configuration.clusterFanInPeerAuthToken();
        this.peerAuthToken = token != null ? token.trim() : "";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(this.requestTimeout)
            .build();
    }

    @Override
    public List<RequestDefinition> retrieveRequests(String peerBaseUrl, RequestDefinition filter) throws Exception {
        String body = peerRetrieve(peerBaseUrl, "REQUESTS", filter);
        if (isEmptyJsonArray(body)) {
            return List.of();
        }
        RequestDefinition[] parsed = requestDefinitionSerializer.deserializeArray(body);
        return parsed == null ? List.of() : Arrays.asList(parsed);
    }

    @Override
    public List<LogEventRequestAndResponse> retrieveRequestResponses(String peerBaseUrl, RequestDefinition filter) throws Exception {
        String body = peerRetrieve(peerBaseUrl, "REQUEST_RESPONSES", filter);
        if (isEmptyJsonArray(body)) {
            return List.of();
        }
        LogEventRequestAndResponse[] parsed = logEventRequestAndResponseSerializer.deserializeArray(body);
        return parsed == null ? List.of() : Arrays.asList(parsed);
    }

    /**
     * A peer with no matching traffic returns an empty JSON array (or blank). The
     * serializers reject blank / empty-array input, so treat that as "no remote matches"
     * rather than a peer failure.
     */
    private static boolean isEmptyJsonArray(String body) {
        if (body == null) {
            return true;
        }
        String trimmed = body.trim();
        return trimmed.isEmpty() || trimmed.equals("[]") || trimmed.replaceAll("\\s", "").equals("[]");
    }

    private String peerRetrieve(String peerBaseUrl, String type, RequestDefinition filter) throws Exception {
        String base = peerBaseUrl.endsWith("/") ? peerBaseUrl.substring(0, peerBaseUrl.length() - 1) : peerBaseUrl;
        URI uri = URI.create(base + "/mockserver/retrieve?type=" + type + "&format=JSON&fanInLocalOnly=true");
        String requestBody = filter != null ? requestDefinitionSerializer.serialize(filter) : "";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(requestTimeout)
            .header("content-type", "application/json");
        // Present the shared control-plane credential so an authenticated cluster accepts the
        // cross-node fan-in query (sent verbatim, so the operator includes the scheme e.g. "Bearer <jwt>").
        if (!peerAuthToken.isEmpty()) {
            builder.header("authorization", peerAuthToken);
        }
        HttpRequest request = builder
            .method("PUT", HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("peer " + peerBaseUrl + " returned status " + status);
        }
        return response.body();
    }
}
