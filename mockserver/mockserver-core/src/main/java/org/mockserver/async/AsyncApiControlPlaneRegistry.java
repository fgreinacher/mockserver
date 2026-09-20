package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Process-wide holder for the optional {@link AsyncApiControlPlane} implementation.
 * <p>
 * Lives in mockserver-core so that {@link org.mockserver.mock.HttpState} can call it
 * without a compile-time dependency on mockserver-async. When no implementation is
 * registered (the module is not on the classpath), the {@code load()} and {@code status()}
 * methods return a "not available" JSON response — the endpoint is still routable but
 * responds with 501/503 semantics, same as other optional features.
 * <p>
 * Thread safety: {@link #register(AsyncApiControlPlane)} is called once at startup;
 * reads are safe after that. The volatile field ensures visibility across threads.
 */
public class AsyncApiControlPlaneRegistry {

    private static final AsyncApiControlPlaneRegistry INSTANCE = new AsyncApiControlPlaneRegistry();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile AsyncApiControlPlane delegate;

    AsyncApiControlPlaneRegistry() {
    }

    public static AsyncApiControlPlaneRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register the control-plane implementation. Called once at server startup by
     * the mockserver-async module's bootstrap code.
     */
    public void register(AsyncApiControlPlane controlPlane) {
        this.delegate = controlPlane;
    }

    /**
     * @return true if an implementation is registered (mockserver-async is on the classpath)
     */
    public boolean isAvailable() {
        return delegate != null;
    }

    /**
     * Delegate to the registered implementation, or return a "not available" response.
     */
    public JsonNode load(String requestBody) {
        if (delegate == null) {
            return notAvailableResponse();
        }
        return delegate.load(requestBody);
    }

    /**
     * Delegate to the registered implementation, or return a "not available" response.
     */
    public JsonNode status() {
        if (delegate == null) {
            return notAvailableResponse();
        }
        return delegate.status();
    }

    /**
     * Reset async mocking state. Safe to call even when no implementation is registered.
     */
    public void reset() {
        if (delegate != null) {
            delegate.reset();
        }
    }

    /**
     * What to tell a caller who asked for AsyncAPI mocking without the module present.
     * <p>
     * Naming the missing module is not enough on its own: this is the entire user experience of an
     * opt-in feature, so the message has to say how to obtain it. The reachable case is a build that
     * depends on mockserver-netty or mockserver-core directly, where mockserver-async is an optional
     * dependency and so is not pulled in; the shaded jar and the Docker images bundle it already,
     * which is why the classpath-mount advice is phrased as the exception rather than the remedy.
     * <p>
     * Public because the same absence is reported over HTTP as a 501 by the control plane, which
     * must say the same thing as the in-process API rather than keep its own copy.
     */
    public static final String NOT_AVAILABLE =
        "AsyncAPI messaging module is not available. MockServer mocks HTTP out of the box; "
            + "message-broker mocking (Kafka, RabbitMQ/AMQP, MQTT) lives in a separate artifact. "
            + "Add org.mock-server:mockserver-async to the classpath at the same version as "
            + "mockserver-core - the mockserver-bom manages that version for you. The standalone "
            + "jar and the Docker images bundle it already; where a build does not, mounting the "
            + "jar into /libs puts it on the server's classpath.";

    /**
     * Delegate verify to the registered implementation, or return a not-available message.
     *
     * @return {@code null} if verification passes; a failure description if it fails;
     *         a not-available message if no implementation is registered
     */
    public String verify(String verificationJson) {
        if (delegate == null) {
            return NOT_AVAILABLE;
        }
        return delegate.verify(verificationJson);
    }

    /**
     * Delegate HTTP-expectation generation to the registered implementation.
     *
     * @return a JSON array string of MockServer expectations
     * @throws IllegalStateException if no implementation is registered
     */
    public String generateHttpExpectations(String requestBody) {
        if (delegate == null) {
            throw new IllegalStateException(NOT_AVAILABLE);
        }
        return delegate.generateHttpExpectations(requestBody);
    }

    private JsonNode notAvailableResponse() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", NOT_AVAILABLE);
        return node;
    }
}
