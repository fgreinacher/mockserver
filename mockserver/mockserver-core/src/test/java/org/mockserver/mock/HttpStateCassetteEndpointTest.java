package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Pins the settled decision that a cassette which enters the process-wide {@link CassetteRegistry}
 * via the <em>load</em> or <em>record</em> path (i.e. without a manual {@code PUT /mockserver/cassettes})
 * is retrievable through the {@code GET /mockserver/cassettes} control-plane endpoint.
 *
 * <p>The {@code load_expectations_from_file} and {@code record_llm_fixtures} MCP tools live in
 * {@code mockserver-netty} and auto-register the fixture in {@link CassetteRegistry} keyed by file path
 * (origin {@code "loaded"} / {@code "recorded"}) — see {@code McpToolRegistryTest}, which proves the
 * tools populate the registry. This test complements those by pinning the <em>other half</em> of the
 * seam at the core layer that owns the registry and the endpoint: that {@code HttpState}'s
 * {@code GET /mockserver/cassettes} handler serialises such an auto-registered entry with the documented
 * body shape, so a loaded/recorded cassette really does surface (and therefore appears in the dashboard's
 * Cassettes tab) without a separate registration call. Registering the entry the way the load/record
 * handlers do — {@code CassetteRegistry.getInstance().register(path, null, count, origin)} — keeps this
 * test in {@code mockserver-core} while still driving the real registration contract.
 *
 * <p>Mutates the process-wide {@link CassetteRegistry} singleton, so this class is registered in the
 * sequential Surefire phase of {@code mockserver-core/pom.xml} (both the parallel {@code <excludes>} and
 * the sequential {@code <includes>}); {@code GlobalStateMutationGuardTest} enforces that.
 */
public class HttpStateCassetteEndpointTest {

    private HttpState httpState;
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private static class FakeResponseWriter extends ResponseWriter {
        private HttpResponse response;

        protected FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }

    @Before
    public void setUp() {
        Configuration configuration = configuration();
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateCassetteEndpointTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateCassetteEndpointTest.class), scheduler);
        // the registry is a process-wide singleton — start each test from empty
        CassetteRegistry.getInstance().reset();
    }

    @After
    public void tearDown() {
        CassetteRegistry.getInstance().reset();
    }

    /** Drive {@code GET /mockserver/cassettes} and return the {@code cassettes} array from the JSON body. */
    private JsonNode getCassettes() throws Exception {
        FakeResponseWriter writer = new FakeResponseWriter();
        boolean handled = httpState.handle(request("/mockserver/cassettes").withMethod("GET"), writer, false);
        assertThat(handled, is(true));
        assertThat(writer.response.getStatusCode(), is(200));
        return objectMapper.readTree(writer.response.getBodyAsString()).get("cassettes");
    }

    @Test
    public void shouldSurfaceLoadedCassetteThroughGetEndpoint() throws Exception {
        // given - a fixture registered exactly as the load_expectations_from_file MCP tool registers it:
        // by file path, with the loaded expectation count and origin "loaded", and no explicit PUT
        CassetteRegistry.getInstance().register("/tmp/fixtures/anthropic-chat.json", null, 3, "loaded");

        // when - the dashboard (or any client) lists cassettes over the control plane
        JsonNode cassettes = getCassettes();

        // then - the auto-registered cassette is retrievable with the documented body shape
        assertThat("a loaded cassette must be retrievable without a manual PUT", cassettes.size(), is(1));
        JsonNode entry = cassettes.get(0);
        assertThat(entry.get("path").asText(), is("/tmp/fixtures/anthropic-chat.json"));
        assertThat("filename is derived from the path when the load path passes none",
            entry.get("filename").asText(), is("anthropic-chat.json"));
        assertThat(entry.get("expectationCount").asInt(), is(3));
        assertThat(entry.get("origin").asText(), is("loaded"));
        assertThat("a registered cassette carries a lastUsed timestamp",
            entry.get("lastUsed").asLong(), is(greaterThan(0L)));
    }

    @Test
    public void shouldSurfaceRecordedCassetteWithRecordedOrigin() throws Exception {
        // given - a fixture registered as record_llm_fixtures registers it
        CassetteRegistry.getInstance().register("/tmp/fixtures/openai-chat.json", null, 5, "recorded");

        // when
        JsonNode cassettes = getCassettes();

        // then - it surfaces with origin "recorded"
        assertThat(cassettes.size(), is(1));
        assertThat(cassettes.get(0).get("origin").asText(), is("recorded"));
        assertThat(cassettes.get(0).get("expectationCount").asInt(), is(5));
    }

    @Test
    public void shouldReturnSingleEntryWhenSameCassetteRecordedThenLoaded() throws Exception {
        // given - the same file is first recorded, then loaded (the natural VCR workflow)
        CassetteRegistry.getInstance().register("/tmp/fixtures/session.json", null, 2, "recorded");
        CassetteRegistry.getInstance().register("/tmp/fixtures/session.json", null, 2, "loaded");

        // when
        JsonNode cassettes = getCassettes();

        // then - record-then-load on one path yields ONE entry, not two, reflecting the latest (loaded) origin
        assertThat("re-registering the same path must upsert, not duplicate", cassettes.size(), is(1));
        assertThat(cassettes.get(0).get("path").asText(), is("/tmp/fixtures/session.json"));
        assertThat(cassettes.get(0).get("origin").asText(), is("loaded"));
    }

    @Test
    public void shouldNoLongerSurfaceCassetteAfterServerReset() throws Exception {
        // given - a loaded cassette is visible
        CassetteRegistry.getInstance().register("/tmp/fixtures/ephemeral.json", null, 1, "loaded");
        assertThat(getCassettes().size(), is(1));

        // when - the server is reset (HttpState.reset() clears the registry)
        httpState.reset();

        // then - the endpoint no longer lists it
        assertThat(getCassettes().size(), is(0));
    }

    @Test
    public void shouldSurfaceLoadedCassetteThroughBareAlias() throws Exception {
        // given
        CassetteRegistry.getInstance().register("/tmp/fixtures/bare.json", null, 4, "loaded");

        // when - the bare /cassettes alias (no /mockserver prefix) is used
        FakeResponseWriter writer = new FakeResponseWriter();
        boolean handled = httpState.handle(request("/cassettes").withMethod("GET"), writer, false);

        // then - it serves from the same registry the prefixed path does
        assertThat(handled, is(true));
        assertThat(writer.response.getStatusCode(), is(200));
        JsonNode cassettes = objectMapper.readTree(writer.response.getBodyAsString()).get("cassettes");
        assertThat(cassettes.size(), is(1));
        assertThat(cassettes.get(0).get("path").asText(), is("/tmp/fixtures/bare.json"));
    }
}
