package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.ExpectationSerializer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertNotNull;

/**
 * Pins the server-side consequence that a client library must avoid: an expectation carrying a real
 * action AND an empty {@code httpResponse} is counted as two actions with no primary and rejected.
 * <p>
 * This is the failure the Node client produced for {@code grpcBidiResponse},
 * {@code httpForwardValidateAction} and {@code httpForwardWithFallback} — its hand-maintained action
 * list had fallen behind the server, so it injected an empty {@code httpResponse} alongside those
 * actions and made them uncreatable from Node. The client-side half is covered by
 * {@code mockserver-client-node/test/no_proxy/action_key_coverage_test.js}, which asserts on the
 * bytes the client actually puts on the wire; this covers the other half, against the real parser,
 * so neither test has to assume the other's behaviour.
 */
public class ExpectationActionCountTest {

    private static final String REQUEST = "\"httpRequest\": {\"path\": \"/some/path\"}";
    private static final String GRPC_BIDI_ACTION =
        "\"grpcBidiResponse\": {\"statusName\": \"OK\", \"messages\": [{\"json\": \"{}\"}]}";

    private Expectation deserialize(String json) {
        return new ExpectationSerializer(new MockServerLogger()).deserialize(json);
    }

    @Test
    public void shouldAcceptAnExpectationWithExactlyOneAction() {
        Expectation expectation = deserialize("{" + REQUEST + ", " + GRPC_BIDI_ACTION + "}");

        assertNotNull("a single-action expectation must resolve an action", expectation.getAction());
        assertThat(expectation.getAction(), instanceOf(org.mockserver.model.GrpcBidiResponse.class));
    }

    /**
     * The exact body the Node client used to send: the real action plus the empty httpResponse its
     * default-header handling injected when it did not recognise the action key.
     */
    @Test
    public void shouldRejectARealActionAccompaniedByAnEmptyHttpResponse() {
        Expectation expectation =
            deserialize("{" + REQUEST + ", " + GRPC_BIDI_ACTION + ", \"httpResponse\": {}}");

        IllegalArgumentException exception =
            assertThrows(IllegalArgumentException.class, expectation::getAction);

        assertThat(exception.getMessage(),
            is("when multiple action types are configured, exactly one must be marked as primary"));
    }

    /**
     * ...and marking one primary is the documented escape hatch, so the rejection above is about the
     * missing primary flag rather than about two actions being present at all.
     */
    @Test
    public void shouldAcceptTwoActionsWhenOneIsMarkedPrimary() {
        Expectation expectation = deserialize(
            "{" + REQUEST + ", "
                + "\"grpcBidiResponse\": {\"statusName\": \"OK\", \"primary\": true}, "
                + "\"httpResponse\": {\"statusCode\": 200}}");

        assertThat(expectation.getAction(), instanceOf(org.mockserver.model.GrpcBidiResponse.class));
    }
}
