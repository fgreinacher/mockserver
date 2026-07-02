package org.mockserver.cluster;

import java.util.List;

/**
 * Thrown when a cluster verify/retrieve fan-in cannot reach one or more peers.
 * <p>
 * Fan-in is <b>fail-closed</b>: rather than returning a partial (and therefore
 * potentially wrong) aggregate, the control plane surfaces the failure so the
 * caller knows the result could not be evaluated fleet-wide.
 */
public class ClusterFanInException extends RuntimeException {

    private final List<String> unreachablePeers;

    public ClusterFanInException(List<String> unreachablePeers) {
        super("cluster verify/retrieve fan-in could not reach peer(s): " + unreachablePeers);
        this.unreachablePeers = unreachablePeers;
    }

    public List<String> getUnreachablePeers() {
        return unreachablePeers;
    }
}
