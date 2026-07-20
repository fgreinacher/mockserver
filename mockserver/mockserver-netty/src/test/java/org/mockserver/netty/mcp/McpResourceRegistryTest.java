package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

public class McpResourceRegistryTest {

    private McpResourceRegistry resourceRegistry;
    private HttpState httpState;

    @Before
    public void setUp() {
        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        resourceRegistry = new McpResourceRegistry(httpState);
    }

    @Test
    public void shouldRegisterAllResources() {
        Map<String, McpResourceRegistry.ResourceDefinition> resources = resourceRegistry.getResources();
        assertThat(resources.size(), is(5));
        assertThat(resources.containsKey("mockserver://expectations"), is(true));
        assertThat(resources.containsKey("mockserver://requests"), is(true));
        assertThat(resources.containsKey("mockserver://logs"), is(true));
        assertThat(resources.containsKey("mockserver://configuration"), is(true));
        assertThat(resources.containsKey("mockserver://unmatched"), is(true));
    }

    @Test
    public void shouldHaveResourceDefinitionsWithMetadata() {
        for (McpResourceRegistry.ResourceDefinition resource : resourceRegistry.getResources().values()) {
            assertThat(resource.getUri(), notNullValue());
            assertThat(resource.getName(), notNullValue());
            assertThat(resource.getDescription(), notNullValue());
            assertThat(resource.getMimeType(), notNullValue());
        }
    }

    @Test
    public void shouldReadExpectationsResource() {
        JsonNode result = resourceRegistry.readResource("mockserver://expectations");
        assertThat(result, notNullValue());
        assertThat(result.isArray(), is(true));
    }

    @Test
    public void shouldReadRequestsResource() {
        JsonNode result = resourceRegistry.readResource("mockserver://requests");
        assertThat(result, notNullValue());
        assertThat(result.isArray(), is(true));
    }

    @Test
    public void shouldReadLogsResource() {
        JsonNode result = resourceRegistry.readResource("mockserver://logs");
        assertThat(result, notNullValue());
        assertThat(result.has("logs"), is(true));
    }

    @Test
    public void shouldReadConfigurationResource() {
        JsonNode result = resourceRegistry.readResource("mockserver://configuration");
        assertThat(result, notNullValue());
        assertThat(result.has("maxExpectations"), is(true));
        assertThat(result.has("maxLogEntries"), is(true));
    }

    /**
     * The configuration resource must report the values held on the {@link HttpState}'s
     * {@code Configuration} instance, not the static {@code ConfigurationProperties} store.
     * {@code PUT /mockserver/configuration} writes only the instance, so reading the static store made
     * this resource silently disagree with {@code GET /mockserver/configuration}.
     */
    @Test
    public void shouldReadConfigurationResourceFromConfigurationInstanceNotStaticStore() {
        Configuration configuration = configuration();
        HttpState state = new HttpState(configuration, new MockServerLogger(), mock(Scheduler.class));
        McpResourceRegistry registry = new McpResourceRegistry(state);

        // set values on the instance only — deliberately different from the static-store values, so the
        // assertions below fail if the resource reads ConfigurationProperties instead
        int instanceMaxExpectations = ConfigurationProperties.maxExpectations() + 4321;
        int instanceMaxLogEntries = ConfigurationProperties.maxLogEntries() + 1234;
        configuration.maxExpectations(instanceMaxExpectations);
        configuration.maxLogEntries(instanceMaxLogEntries);

        JsonNode result = registry.readResource("mockserver://configuration");

        assertThat(result, notNullValue());
        assertThat(result.get("maxExpectations").asInt(), is(instanceMaxExpectations));
        assertThat(result.get("maxLogEntries").asInt(), is(instanceMaxLogEntries));
        // guard the guard: the static store must NOT have been mutated, otherwise the assertions above
        // would pass even when reading the static store
        assertThat(ConfigurationProperties.maxExpectations(), is(not(instanceMaxExpectations)));
        assertThat(ConfigurationProperties.maxLogEntries(), is(not(instanceMaxLogEntries)));
    }

    @Test
    public void shouldReturnNullForUnknownResource() {
        JsonNode result = resourceRegistry.readResource("mockserver://nonexistent");
        assertThat(result, nullValue());
    }

    @Test
    public void shouldHaveCorrectMimeTypes() {
        Map<String, McpResourceRegistry.ResourceDefinition> resources = resourceRegistry.getResources();
        assertThat(resources.get("mockserver://expectations").getMimeType(), is("application/json"));
        assertThat(resources.get("mockserver://requests").getMimeType(), is("application/json"));
        assertThat(resources.get("mockserver://logs").getMimeType(), is("text/plain"));
        assertThat(resources.get("mockserver://configuration").getMimeType(), is("application/json"));
        assertThat(resources.get("mockserver://unmatched").getMimeType(), is("application/json"));
    }

    @Test
    public void shouldReadUnmatchedResource() {
        // given / when
        JsonNode result = resourceRegistry.readResource("mockserver://unmatched");

        // then
        assertThat(result, notNullValue());
        assertThat(result.has("unmatchedRequestCount"), is(true));
        assertThat(result.path("unmatchedRequestCount").asInt(), is(0));
        assertThat(result.has("unmatchedRequests"), is(true));
        assertThat(result.path("unmatchedRequests").isArray(), is(true));
    }
}
