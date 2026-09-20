package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link AsyncApiControlPlaneRegistry} — the SPI holder in core
 * that delegates to the optional mockserver-async implementation.
 */
public class AsyncApiControlPlaneRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @After
    public void tearDown() {
        // Restore singleton to unregistered state for test isolation.
        // In production, register() is called once at startup and never cleared.
        AsyncApiControlPlaneRegistry.getInstance().register(null);
    }

    @Test
    public void shouldReturnNotAvailableWhenNoImplRegistered() {
        AsyncApiControlPlaneRegistry registry = new AsyncApiControlPlaneRegistry();
        assertThat(registry.isAvailable(), is(false));

        JsonNode loadResult = registry.load("{\"test\":true}");
        assertThat(loadResult.get("error").asText(), containsString("not available"));

        JsonNode statusResult = registry.status();
        assertThat(statusResult.get("error").asText(), containsString("not available"));

        String verifyResult = registry.verify("{\"channel\":\"test\"}");
        assertThat(verifyResult, is(notNullValue()));
        assertThat(verifyResult, containsString("not available"));
    }

    @Test
    public void shouldDelegateWhenImplRegistered() {
        AsyncApiControlPlaneRegistry registry = new AsyncApiControlPlaneRegistry();

        // Register a simple stub implementation
        registry.register(new AsyncApiControlPlane() {
            @Override
            public JsonNode load(String requestBody) {
                return MAPPER.createObjectNode().put("loaded", true);
            }

            @Override
            public JsonNode status() {
                return MAPPER.createObjectNode().put("status", "ok");
            }

            @Override
            public void reset() {
                // no-op
            }

            @Override
            public String verify(String verificationJson) {
                // Simulate: pass when channel is "ok", fail otherwise
                return verificationJson.contains("\"ok\"") ? null : "verification failed";
            }

            @Override
            public String generateHttpExpectations(String requestBody) {
                return "[{\"httpRequest\":{\"path\":\"/" + requestBody + "\"}}]";
            }
        });

        assertThat(registry.isAvailable(), is(true));

        JsonNode loadResult = registry.load("{}");
        assertThat(loadResult.get("loaded").asBoolean(), is(true));

        JsonNode statusResult = registry.status();
        assertThat(statusResult.get("status").asText(), is("ok"));

        // Verify delegates correctly
        assertThat(registry.verify("{\"channel\":\"ok\"}"), is(nullValue()));
        assertThat(registry.verify("{\"channel\":\"fail\"}"), is("verification failed"));

        // generateHttpExpectations delegates correctly
        assertThat(registry.generateHttpExpectations("channel"), is("[{\"httpRequest\":{\"path\":\"/channel\"}}]"));
    }

    /**
     * The not-available message IS the user experience of an opt-in feature: a caller who asked for
     * AsyncAPI mocking without the module needs to learn how to get it, not merely that they lack it.
     * Naming the missing module alone leaves them to guess the coordinates and, in a container, where
     * to put the jar. Pinned here because nothing else would fail if the actionable half were edited
     * away, leaving a message that is still technically true and no longer useful.
     */
    @Test
    public void notAvailableMessageTellsTheUserHowToEnableIt() {
        AsyncApiControlPlaneRegistry registry = new AsyncApiControlPlaneRegistry();

        String message = registry.verify("{}");

        assertThat("must name the artifact to add, not just the module that is missing",
            message, containsString("mockserver-async"));
        assertThat("must name the group so the coordinates are complete",
            message, containsString("org.mock-server"));
        assertThat("must say where the version comes from, or the user has to guess it",
            message, containsString("mockserver-bom"));
        assertThat("must tell a container user where to put the jar - /libs is already on the "
                + "classpath in every image variant",
            message, containsString("/libs"));
    }

    /**
     * Four entry points report the same absence, and a caller who hits any one of them needs the
     * same instructions. They are only as consistent as the single constant behind them, so pin
     * that they stay identical rather than trusting that a later edit touches all four.
     */
    @Test
    public void everyEntryPointReportsTheSameNotAvailableMessage() {
        AsyncApiControlPlaneRegistry registry = new AsyncApiControlPlaneRegistry();
        String expected = registry.verify("{}");

        assertThat(registry.load("{}").get("error").asText(), is(expected));
        assertThat(registry.status().get("error").asText(), is(expected));
        try {
            registry.generateHttpExpectations("{}");
            throw new AssertionError("expected IllegalStateException when no implementation registered");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), is(expected));
        }
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowGeneratingHttpExpectationsWhenNoImpl() {
        AsyncApiControlPlaneRegistry registry = new AsyncApiControlPlaneRegistry();
        registry.generateHttpExpectations("{}");
    }

    @Test
    public void shouldResetSafelyWhenNoImpl() {
        AsyncApiControlPlaneRegistry registry = new AsyncApiControlPlaneRegistry();
        // Should not throw
        registry.reset();
    }
}
