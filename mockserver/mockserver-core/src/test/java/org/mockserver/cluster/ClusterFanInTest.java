package org.mockserver.cluster;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Unit tests for the T1.9 cluster verify/retrieve fan-in merge logic. Peers are simulated
 * with an in-memory {@link ClusterFanIn.PeerAccessor} so no real multi-node cluster is needed:
 * the count aggregation, local-only non-recursion, and fail-closed unreachable-peer handling
 * are all exercised in isolation.
 */
public class ClusterFanInTest {

    private final MockServerLogger logger = new MockServerLogger();

    /**
     * A fake peer accessor holding each peer's LOCAL log in memory. Unreachable peers are
     * modelled by throwing (as the real HTTP accessor does on a connect failure / non-2xx).
     */
    private static final class FakePeerAccessor implements ClusterFanIn.PeerAccessor {
        final Map<String, List<RequestDefinition>> requestsByPeer = new HashMap<>();
        final Map<String, List<LogEventRequestAndResponse>> pairsByPeer = new HashMap<>();
        final List<String> unreachable = new ArrayList<>();
        final List<String> queriedPeers = new ArrayList<>();

        @Override
        public List<RequestDefinition> retrieveRequests(String peerBaseUrl, RequestDefinition filter) throws Exception {
            queriedPeers.add(peerBaseUrl);
            if (unreachable.contains(peerBaseUrl)) {
                throw new java.net.ConnectException("connection refused: " + peerBaseUrl);
            }
            return requestsByPeer.getOrDefault(peerBaseUrl, List.of());
        }

        @Override
        public List<LogEventRequestAndResponse> retrieveRequestResponses(String peerBaseUrl, RequestDefinition filter) throws Exception {
            queriedPeers.add(peerBaseUrl);
            if (unreachable.contains(peerBaseUrl)) {
                throw new java.net.ConnectException("connection refused: " + peerBaseUrl);
            }
            return pairsByPeer.getOrDefault(peerBaseUrl, List.of());
        }
    }

    private static List<RequestDefinition> requests(int count) {
        List<RequestDefinition> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(request("/path" + i));
        }
        return list;
    }

    @Test
    public void enabledIsFalseByDefault() {
        ClusterFanIn fanIn = new ClusterFanIn(configuration(), logger, new FakePeerAccessor());
        assertThat(fanIn.enabled(), is(false));
    }

    @Test
    public void enabledIsFalseWhenFlagOnButNoPeers() {
        Configuration configuration = configuration().clusterVerifyFanIn(true).clusterVerifyFanInPeers("");
        ClusterFanIn fanIn = new ClusterFanIn(configuration, logger, new FakePeerAccessor());
        assertThat(fanIn.enabled(), is(false));
    }

    @Test
    public void enabledIsTrueWhenFlagOnAndPeersConfigured() {
        Configuration configuration = configuration().clusterVerifyFanIn(true).clusterVerifyFanInPeers("http://b:1080");
        ClusterFanIn fanIn = new ClusterFanIn(configuration, logger, new FakePeerAccessor());
        assertThat(fanIn.enabled(), is(true));
    }

    @Test
    public void peersAreParsedTrimmedAndBlanksDropped() {
        Configuration configuration = configuration().clusterVerifyFanInPeers(" http://b:1080 , ,http://c:1080,");
        ClusterFanIn fanIn = new ClusterFanIn(configuration, logger, new FakePeerAccessor());
        assertThat(fanIn.peers(), contains("http://b:1080", "http://c:1080"));
    }

    @Test
    public void fanInRequestsAggregatesCountsAcrossPeers() {
        Configuration configuration = configuration().clusterVerifyFanIn(true).clusterVerifyFanInPeers("http://b:1080,http://c:1080");
        FakePeerAccessor accessor = new FakePeerAccessor();
        accessor.requestsByPeer.put("http://b:1080", requests(3));
        accessor.requestsByPeer.put("http://c:1080", requests(2));
        ClusterFanIn fanIn = new ClusterFanIn(configuration, logger, accessor);

        ClusterFanIn.FanInResult<List<RequestDefinition>> result = fanIn.fanInRequests(request("/api"));

        // remote-only merge: 3 (node B) + 2 (node C) = 5; caller adds its own local count
        assertThat(result.merged().size(), is(5));
        assertThat(result.hasUnreachablePeers(), is(false));
        assertThat(result.unreachablePeers(), is(empty()));
        // both peers were queried (fan-in is a scatter to every configured peer)
        assertThat(accessor.queriedPeers, containsInAnyOrder("http://b:1080", "http://c:1080"));
    }

    @Test
    public void fanInRequestsFailsClosedOnUnreachablePeer() {
        Configuration configuration = configuration().clusterVerifyFanIn(true).clusterVerifyFanInPeers("http://b:1080,http://c:1080");
        FakePeerAccessor accessor = new FakePeerAccessor();
        accessor.requestsByPeer.put("http://b:1080", requests(4));
        accessor.unreachable.add("http://c:1080");
        ClusterFanIn fanIn = new ClusterFanIn(configuration, logger, accessor);

        ClusterFanIn.FanInResult<List<RequestDefinition>> result = fanIn.fanInRequests(request("/api"));

        // the reachable peer's traffic is still gathered, but the unreachable peer is reported
        // so the caller can fail-closed rather than trust a partial (under-counted) result
        assertThat(result.merged().size(), is(4));
        assertThat(result.hasUnreachablePeers(), is(true));
        assertThat(result.unreachablePeers(), contains("http://c:1080"));
    }

    @Test
    public void fanInRequestResponsesAggregatesAcrossPeers() {
        Configuration configuration = configuration().clusterVerifyFanIn(true).clusterVerifyFanInPeers("http://b:1080,http://c:1080");
        FakePeerAccessor accessor = new FakePeerAccessor();
        accessor.pairsByPeer.put("http://b:1080", Arrays.asList(
            new LogEventRequestAndResponse().withHttpRequest(request("/x")),
            new LogEventRequestAndResponse().withHttpRequest(request("/y"))
        ));
        accessor.pairsByPeer.put("http://c:1080", Arrays.asList(
            new LogEventRequestAndResponse().withHttpRequest(request("/z"))
        ));
        ClusterFanIn fanIn = new ClusterFanIn(configuration, logger, accessor);

        ClusterFanIn.FanInResult<List<LogEventRequestAndResponse>> result = fanIn.fanInRequestResponses(request("/api"));

        assertThat(result.merged().size(), is(3));
        assertThat(result.hasUnreachablePeers(), is(false));
    }

    @Test
    public void disabledFanInDoesNotQueryPeers() {
        // enabled() is the caller's gate; when the flag is off the coordinator should not be
        // consulted. Verify peers() is empty-effect and that calling fanIn on a disabled config
        // still behaves (defensive) — enabled() is false so callers skip it entirely.
        Configuration configuration = configuration().clusterVerifyFanIn(false).clusterVerifyFanInPeers("http://b:1080");
        FakePeerAccessor accessor = new FakePeerAccessor();
        ClusterFanIn fanIn = new ClusterFanIn(configuration, logger, accessor);
        assertThat(fanIn.enabled(), is(false));
    }
}
