package org.mockserver.grpc;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GrpcHealthRegistry {

    private static final GrpcHealthRegistry INSTANCE = new GrpcHealthRegistry();

    private final ConcurrentHashMap<String, ServingStatus> statusByService = new ConcurrentHashMap<>();
    private volatile ServingStatus defaultStatus = ServingStatus.SERVING;

    GrpcHealthRegistry() {
    }

    public static GrpcHealthRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Set the status for a specific service name (empty string = default for all).
     */
    public void setStatus(String serviceName, ServingStatus status) {
        if (serviceName == null || status == null) {
            return;
        }
        if (serviceName.isEmpty()) {
            defaultStatus = status;
        } else {
            statusByService.put(serviceName, status);
        }
    }

    /**
     * Get the status for a service name, falling back to the default status when the name has no
     * registered override.
     *
     * <p><strong>Callers must consult {@link #isRegistered} first.</strong> The fallback here is
     * NOT the health-check contract for a named service: {@code Check} on an unregistered service
     * must fail with {@code NOT_FOUND} rather than inherit the default, so calling this method
     * alone would report an unknown service as healthy. The fallback remains because it is correct
     * for the empty (overall-server) name, and because the {@code GET} control-plane endpoint
     * reports effective status.</p>
     */
    public ServingStatus getStatus(String serviceName) {
        if (serviceName != null && !serviceName.isEmpty()) {
            ServingStatus s = statusByService.get(serviceName);
            if (s != null) {
                return s;
            }
        }
        return defaultStatus;
    }

    /**
     * Returns whether a health status has been registered for the given service name.
     *
     * <p>The empty service name is the overall-server health target defined by
     * {@code grpc.health.v1.Health}; it always has a status (the default) and so is always
     * registered. A named service is registered only once {@link #setStatus} has been called
     * for it — the default status is deliberately NOT treated as covering arbitrary names,
     * because {@code Check} on an unregistered service must fail with {@code NOT_FOUND} rather
     * than inherit a status. Without this distinction a typo'd service name reports healthy.</p>
     */
    public boolean isRegistered(String serviceName) {
        return serviceName == null || serviceName.isEmpty() || statusByService.containsKey(serviceName);
    }

    /**
     * Returns all non-default service to status entries plus the default status.
     */
    public Map<String, ServingStatus> entries() {
        Map<String, ServingStatus> result = new HashMap<>(statusByService);
        result.put("", defaultStatus);
        return result;
    }

    /**
     * Remove the override for a specific service so it reverts to the default. An empty service
     * name resets the default status itself back to SERVING.
     */
    public void removeStatus(String serviceName) {
        if (serviceName == null) {
            return;
        }
        if (serviceName.isEmpty()) {
            defaultStatus = ServingStatus.SERVING;
        } else {
            statusByService.remove(serviceName);
        }
    }

    public void reset() {
        statusByService.clear();
        defaultStatus = ServingStatus.SERVING;
    }
}
