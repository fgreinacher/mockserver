package org.mockserver.cluster;

import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.RequestDefinition;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.mockserver.log.model.LogEntry.LogMessageType.WARN;

/**
 * Opt-in cluster scatter-gather (fan-in) for {@code verify}/{@code retrieve}.
 * <p>
 * MockServer's event log is per-node: the shared {@code StateBackend} replicates
 * expectations, scenario state, CRUD entities, and blobs across the fleet, but NOT
 * request/response log entries. Behind a load balancer that means a {@code verify()}
 * or {@code retrieve(REQUESTS/REQUEST_RESPONSES)} on one node sees only the traffic
 * that reached THAT node — a silent correctness trap for the HA feature.
 * <p>
 * When enabled ({@code clusterVerifyFanIn=true} plus a non-empty
 * {@code clusterVerifyFanInPeers} list), this coordinator queries each configured
 * peer's LOCAL log via a {@link PeerAccessor} and concatenates the results, so the
 * caller can merge them with the local log and evaluate against the fleet-wide total.
 * <p>
 * <b>Non-recursion:</b> the {@link PeerAccessor} issues each peer request with a
 * "local-only" marker so the peer serves from its own log without fanning out again.
 * <p>
 * <b>Unreachable peers (fail-closed):</b> this coordinator does NOT drop unreachable
 * peers silently. It reports which peers failed via {@link FanInResult#unreachablePeers()};
 * callers treat any unreachable peer as a failure rather than returning a partial (and
 * therefore potentially wrong) verify/retrieve result. This is the safer default: for an
 * {@code atMost}/{@code exactly} verification, silently missing a peer's traffic could turn
 * a real violation into a false pass.
 */
public class ClusterFanIn {

    /**
     * Queries a single peer's LOCAL log. Implementations MUST request the peer in a
     * "local-only" mode so it does not fan out again (infinite recursion guard).
     * Any failure (unreachable, timeout, non-2xx, parse error) MUST throw.
     */
    public interface PeerAccessor {
        List<RequestDefinition> retrieveRequests(String peerBaseUrl, RequestDefinition filter) throws Exception;

        List<LogEventRequestAndResponse> retrieveRequestResponses(String peerBaseUrl, RequestDefinition filter) throws Exception;
    }

    /**
     * The merged remote results plus the list of peers that could not be reached.
     */
    public static final class FanInResult<T> {
        private final T merged;
        private final List<String> unreachablePeers;

        public FanInResult(T merged, List<String> unreachablePeers) {
            this.merged = merged;
            this.unreachablePeers = unreachablePeers;
        }

        public T merged() {
            return merged;
        }

        public List<String> unreachablePeers() {
            return unreachablePeers;
        }

        public boolean hasUnreachablePeers() {
            return !unreachablePeers.isEmpty();
        }
    }

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final PeerAccessor peerAccessor;

    public ClusterFanIn(Configuration configuration, MockServerLogger mockServerLogger, PeerAccessor peerAccessor) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.peerAccessor = peerAccessor;
    }

    /**
     * Fan-in is active only when explicitly enabled AND at least one peer is configured.
     * When enabled with no peers it is a safe no-op (single-node behaviour preserved).
     */
    public boolean enabled() {
        return configuration.clusterVerifyFanIn() && !peers().isEmpty();
    }

    /**
     * The configured peer control-plane base URLs (this node excluded), parsed from the
     * comma-separated {@code clusterVerifyFanInPeers} property. Blank entries are dropped.
     */
    public List<String> peers() {
        String raw = configuration.clusterVerifyFanInPeers();
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    /**
     * Query every peer's LOCAL matching requests and concatenate them. The returned
     * list is remote-only (the caller merges with its local log). Unreachable peers are
     * reported (never silently dropped).
     */
    public FanInResult<List<RequestDefinition>> fanInRequests(RequestDefinition filter) {
        List<RequestDefinition> merged = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        for (String peer : peers()) {
            try {
                merged.addAll(peerAccessor.retrieveRequests(peer, filter));
            } catch (Exception throwable) {
                unreachable.add(peer);
                logUnreachable(peer, throwable);
            }
        }
        return new FanInResult<>(merged, unreachable);
    }

    /**
     * Query every peer's LOCAL matching request-response pairs and concatenate them.
     */
    public FanInResult<List<LogEventRequestAndResponse>> fanInRequestResponses(RequestDefinition filter) {
        List<LogEventRequestAndResponse> merged = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        for (String peer : peers()) {
            try {
                merged.addAll(peerAccessor.retrieveRequestResponses(peer, filter));
            } catch (Exception throwable) {
                unreachable.add(peer);
                logUnreachable(peer, throwable);
            }
        }
        return new FanInResult<>(merged, unreachable);
    }

    private void logUnreachable(String peer, Throwable throwable) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(WARN)
                .setLogLevel(Level.WARN)
                .setMessageFormat("cluster verify/retrieve fan-in could not reach peer{}error:{}")
                .setArguments(peer, throwable.getMessage())
        );
    }
}
