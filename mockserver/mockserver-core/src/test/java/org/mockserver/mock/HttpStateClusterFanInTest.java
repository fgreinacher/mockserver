package org.mockserver.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.cluster.ClusterFanIn;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.RequestDefinitionSerializer;
import org.mockserver.verify.Verification;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * HttpState-level tests for the T1.9 cluster verify/retrieve fan-in wiring: the recursion guard
 * (a {@code fanInLocalOnly=true} retrieve serves ONLY the local log), count-based verify merging
 * remote peer counts before evaluating {@code VerificationTimes}, retrieve concatenating remote
 * requests, and fail-closed behaviour when a peer is unreachable. Peers are simulated with an
 * in-memory {@link ClusterFanIn.PeerAccessor} — no real cluster is required.
 */
public class HttpStateClusterFanInTest {

    private final RequestDefinitionSerializer requestDefinitionSerializer = new RequestDefinitionSerializer(new MockServerLogger());
    private HttpState httpState;
    private ScheduledExecutorService schedulerExecutor;

    /** Simulated peer log: returns a fixed number of matching requests, or throws if "unreachable". */
    private static final class FixedPeerAccessor implements ClusterFanIn.PeerAccessor {
        final int remoteMatches;
        final boolean unreachable;
        final AtomicInteger queries = new AtomicInteger(0);

        FixedPeerAccessor(int remoteMatches, boolean unreachable) {
            this.remoteMatches = remoteMatches;
            this.unreachable = unreachable;
        }

        @Override
        public List<RequestDefinition> retrieveRequests(String peerBaseUrl, RequestDefinition filter) throws Exception {
            queries.incrementAndGet();
            if (unreachable) {
                throw new java.net.ConnectException("refused");
            }
            List<RequestDefinition> list = new ArrayList<>();
            for (int i = 0; i < remoteMatches; i++) {
                list.add(request("/api"));
            }
            return list;
        }

        @Override
        public List<LogEventRequestAndResponse> retrieveRequestResponses(String peerBaseUrl, RequestDefinition filter) throws Exception {
            queries.incrementAndGet();
            if (unreachable) {
                throw new java.net.ConnectException("refused");
            }
            List<LogEventRequestAndResponse> list = new ArrayList<>();
            for (int i = 0; i < remoteMatches; i++) {
                list.add(new LogEventRequestAndResponse().withHttpRequest(request("/api")));
            }
            return list;
        }
    }

    @Before
    public void setUp() {
        Configuration configuration = configuration();
        Scheduler scheduler = mock(Scheduler.class);
        schedulerExecutor = java.util.concurrent.Executors.newScheduledThreadPool(2);
        org.mockito.Mockito.when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
    }

    @After
    public void tearDown() {
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
        }
    }

    private void logLocalRequest(String path) {
        httpState.log(new LogEntry().setHttpRequest(request(path)).setType(RECEIVED_REQUEST));
    }

    private FixedPeerAccessor enableFanIn(int remoteMatches, boolean unreachable) {
        FixedPeerAccessor accessor = new FixedPeerAccessor(remoteMatches, unreachable);
        Configuration fanInConfig = configuration().clusterVerifyFanIn(true).clusterVerifyFanInPeers("http://peer-b:1080");
        httpState.setClusterFanIn(new ClusterFanIn(fanInConfig, new MockServerLogger(), accessor));
        return accessor;
    }

    @Test
    public void verifyCountMergesRemoteMatchesAcrossCluster() throws Exception {
        logLocalRequest("/api"); // 1 local match
        enableFanIn(2, false);   // + 2 remote matches = 3 fleet-wide

        // exactly(3) must PASS fleet-wide even though only 1 request hit this node
        String pass = httpState.verify(new Verification().withRequest(request("/api")).withTimes(exactly(3))).get();
        assertThat(pass, is(""));

        // exactly(1) — the old per-node answer — must now FAIL, because the fleet served 3
        String fail = httpState.verify(new Verification().withRequest(request("/api")).withTimes(exactly(1))).get();
        assertThat(fail.isEmpty(), is(false));
    }

    @Test
    public void retrieveRequestsConcatenatesRemote() {
        logLocalRequest("/api"); // 1 local
        enableFanIn(2, false);   // + 2 remote

        FakeResponseWriter writer = new FakeResponseWriter();
        httpState.handle(request("/mockserver/retrieve").withMethod("PUT")
            .withBody(requestDefinitionSerializer.serialize(request("/api"))), writer, false);

        assertThat(writer.statusCode, is(200));
        // 1 local + 2 remote = 3 request definitions returned
        RequestDefinition[] returned = requestDefinitionSerializer.deserializeArray(writer.body);
        assertThat(returned.length, is(3));
    }

    @Test
    public void fanInLocalOnlyRetrieveDoesNotRecurse() {
        logLocalRequest("/api");
        FixedPeerAccessor accessor = enableFanIn(5, false);

        FakeResponseWriter writer = new FakeResponseWriter();
        // a peer fan-in query carries fanInLocalOnly=true — it MUST serve only the local log
        httpState.handle(request("/mockserver/retrieve").withMethod("PUT")
            .withQueryStringParameter("fanInLocalOnly", "true")
            .withBody(requestDefinitionSerializer.serialize(request("/api"))), writer, false);

        assertThat(writer.statusCode, is(200));
        RequestDefinition[] returned = requestDefinitionSerializer.deserializeArray(writer.body);
        assertThat("local-only retrieve must not include remote traffic", returned.length, is(1));
        assertThat("peers must not be queried for a local-only retrieve (recursion guard)", accessor.queries.get(), is(0));
    }

    @Test
    public void verifyFailsClosedWhenPeerUnreachable() throws Exception {
        logLocalRequest("/api");
        enableFanIn(0, true); // peer unreachable

        String result = httpState.verify(new Verification().withRequest(request("/api")).withTimes(exactly(1))).get();
        // even though the local count alone (1) would satisfy exactly(1), an unreachable peer
        // must fail the verification rather than return a partial (possibly wrong) result
        assertThat(result.isEmpty(), is(false));
        assertThat(result, containsString("peer"));
    }

    @Test
    public void retrieveFailsClosedWhenPeerUnreachable() {
        logLocalRequest("/api");
        enableFanIn(0, true);

        FakeResponseWriter writer = new FakeResponseWriter();
        httpState.handle(request("/mockserver/retrieve").withMethod("PUT")
            .withBody(requestDefinitionSerializer.serialize(request("/api"))), writer, false);

        assertThat("retrieve must fail-closed (502) when a peer is unreachable", writer.statusCode, is(502));
    }

    /** Minimal ResponseWriter capturing the control-plane response (via sendResponse, as the real writers do). */
    private static final class FakeResponseWriter extends org.mockserver.responsewriter.ResponseWriter {
        volatile int statusCode;
        volatile String body;

        FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, org.mockserver.model.HttpResponse response) {
            this.statusCode = response.getStatusCode();
            this.body = response.getBodyAsString();
        }
    }
}
