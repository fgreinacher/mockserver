package org.mockserver.configuration;

import org.junit.After;
import org.junit.Test;
import org.mockserver.matchers.LlmConversationMatcher;
import org.mockserver.mock.action.http.LlmCostBudgetMonitor;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;
import org.mockserver.model.RateLimit;
import org.mockserver.ratelimit.RateLimitRegistry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Proves that enforcement sites honour a value set on a {@link Configuration} <em>instance</em>.
 * <p>
 * These properties were previously enforced by reading only the static
 * {@link ConfigurationProperties} store. Because {@code PUT /mockserver/configuration} writes only
 * the {@code Configuration} instance (and {@code Configuration.<prop>()} falls back <em>to</em> the
 * static store when unset), the instance/DTO/REST route could never reach enforcement: the property
 * round-tripped perfectly and silently did nothing.
 * <p>
 * Each test sets the value on an instance ONLY — never on the static store — so it fails if the
 * enforcement site regresses to a static read. The paired "static fallback" assertions confirm the
 * fix did not break existing system-property/env/file users.
 */
public class ConfigurationInstanceEnforcementTest {

    @After
    public void resetSingletons() {
        LlmCostBudgetMonitor.getInstance().reset();
        RateLimitRegistry.getInstance().reset();
        // LlmProviderSniffer holds a pushed-in Configuration (installed by HttpState in production);
        // clear it so an instance installed here cannot leak into unrelated tests in this JVM.
        org.mockserver.llm.client.LlmProviderSniffer.resetConfiguration();
        org.mockserver.matchers.CustomJsonUnitMatcherLoader.reset();
    }

    // ----- llmCostBudgetUsd -------------------------------------------------

    @Test
    public void shouldEnforceLlmCostBudgetFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().llmCostBudgetUsd(1.0);
        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();

        monitor.recordCost(2.0);

        assertThat("budget set on the instance must be enforced",
            monitor.isBudgetExceeded(configuration), is(true));
        assertThat("exceeding the instance budget must produce a blocking response",
            monitor.checkBudgetOrNull(configuration), is(notNullValue()));
    }

    @Test
    public void shouldNotBlockWhenUnderLlmCostBudgetFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().llmCostBudgetUsd(10.0);
        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();

        monitor.recordCost(2.0);

        assertThat(monitor.isBudgetExceeded(configuration), is(false));
        assertThat(monitor.checkBudgetOrNull(configuration), is(nullValue()));
    }

    @Test
    public void shouldFallBackToStaticStoreForLlmCostBudgetWhenNoConfiguration() {
        LlmCostBudgetMonitor monitor = LlmCostBudgetMonitor.getInstance();
        monitor.recordCost(2.0);

        // no budget configured anywhere -> fail-open, never blocks
        assertThat(monitor.isBudgetExceeded(null), is(false));
    }

    // ----- rateLimitMaxNamedQuotas -----------------------------------------

    @Test
    public void shouldEnforceRateLimitMaxNamedQuotasFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().rateLimitMaxNamedQuotas(1);
        RateLimitRegistry registry = RateLimitRegistry.getInstance();

        RateLimit first = new RateLimit().withName("first").withLimit(1).withWindowMillis(60_000L);
        RateLimit second = new RateLimit().withName("second").withLimit(1).withWindowMillis(60_000L);

        // first distinct counter is created and rate-limits normally
        assertThat(registry.tryAcquire(first, "first", configuration).allowed, is(true));
        assertThat("second request on the same counter is over its limit",
            registry.tryAcquire(first, "first", configuration).allowed, is(false));

        // the cap of 1 distinct counter is now reached, so a NEW key must fail open (be allowed)
        // rather than allocating an unbounded number of counters
        assertThat("a new counter beyond the instance cap must fail open",
            registry.tryAcquire(second, "second", configuration).allowed, is(true));
        assertThat("and must still fail open on repeat, proving no counter was allocated",
            registry.tryAcquire(second, "second", configuration).allowed, is(true));
    }

    // ----- maxLlmConversationBodySize --------------------------------------

    @Test
    public void shouldEnforceMaxLlmConversationBodySizeFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().maxLlmConversationBodySize(10);

        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.OPENAI)
            .withLatestMessageContains("hello");

        HttpRequest oversized = HttpRequest.request()
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"hello there this body is well over ten bytes\"}]}");

        assertThat("a body over the instance cap must be treated as no-match (fail-closed)",
            matcher.matches(oversized, configuration), is(false));
    }

    @Test
    public void shouldMatchWhenUnderMaxLlmConversationBodySizeFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().maxLlmConversationBodySize(1_000_000);

        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.OPENAI)
            .withLatestMessageContains("hello");

        HttpRequest request = HttpRequest.request()
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        assertThat("a body under the instance cap must be matched normally",
            matcher.matches(request, configuration), is(true));
    }

    // ----- otelMetricsEnabled / otelEndpoint (OtelMetricsExporter#startIfEnabled) -------------

    @Test
    public void shouldStartOtelMetricsExporterFromConfigurationInstance() {
        // static default is false, so a non-null exporter can ONLY come from the instance value
        Configuration configuration = Configuration.configuration()
            .otelMetricsEnabled(true)
            .otelEndpoint("http://localhost:14318");

        org.mockserver.metrics.OtelMetricsExporter exporter =
            org.mockserver.metrics.OtelMetricsExporter.startIfEnabled(configuration);

        assertThat("otelMetricsEnabled set on the instance must start the exporter",
            exporter, is(notNullValue()));
        exporter.stop();
    }

    @Test
    public void shouldNotStartOtelMetricsExporterWhenNoConfigurationAndStaticDefaultIsOff() {
        assertThat(org.mockserver.metrics.OtelMetricsExporter.startIfEnabled(null), is(nullValue()));
    }

    // ----- otelTracesEnabled (GenAiSpanExporter#startIfEnabled) -------------------------------

    @Test
    public void shouldStartGenAiSpanExporterFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration()
            .otelTracesEnabled(true)
            .otelEndpoint("http://localhost:14318");

        org.mockserver.telemetry.GenAiSpanExporter exporter =
            org.mockserver.telemetry.GenAiSpanExporter.startIfEnabled(configuration);

        assertThat("otelTracesEnabled set on the instance must start the exporter",
            exporter, is(notNullValue()));
        exporter.stop();
    }

    @Test
    public void shouldNotStartGenAiSpanExporterWhenNoConfigurationAndStaticDefaultIsOff() {
        assertThat(org.mockserver.telemetry.GenAiSpanExporter.startIfEnabled(null), is(nullValue()));
    }

    // ----- prometheusRemoteWrite* (PrometheusRemoteWriteExporter#startIfEnabled) --------------

    @Test
    public void shouldStartPrometheusRemoteWriteExporterFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration()
            .prometheusRemoteWriteEnabled(true)
            .prometheusRemoteWriteUrl("http://localhost:19090/api/v1/write");

        org.mockserver.metrics.PrometheusRemoteWriteExporter exporter =
            org.mockserver.metrics.PrometheusRemoteWriteExporter.startIfEnabled(configuration);

        assertThat("prometheusRemoteWriteEnabled set on the instance must start the exporter",
            exporter, is(notNullValue()));
        exporter.stop();
    }

    @Test
    public void shouldNotStartPrometheusRemoteWriteExporterWithoutAnInstanceUrl() {
        // enabled on the instance but no URL anywhere -> still skipped (fail-soft), proving the
        // URL is read from the instance too rather than only the static store
        Configuration configuration = Configuration.configuration().prometheusRemoteWriteEnabled(true);

        assertThat(org.mockserver.metrics.PrometheusRemoteWriteExporter.startIfEnabled(configuration),
            is(nullValue()));
    }

    // ----- llmProvider / llmBaseUrl / llmModel (LlmBackendResolver#fromProperties) ------------

    @Test
    public void shouldResolveLlmBackendFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration()
            .llmProvider("ANTHROPIC")
            .llmBaseUrl("http://llm.example:1234")
            .llmModel("claude-instance-model")
            .llmApiKey("instance-key");

        // env lookup returns nothing, so a resolved backend can only come from the instance
        java.util.Optional<org.mockserver.llm.client.LlmBackend> backend =
            new org.mockserver.llm.client.LlmBackendResolver(name -> null, configuration).resolveDefault();

        assertThat("llmProvider set on the instance must resolve a backend", backend.isPresent(), is(true));
        assertThat(backend.get().provider(), is(Provider.ANTHROPIC));
        assertThat(backend.get().baseUrl(), is("http://llm.example:1234"));
        assertThat(backend.get().model(), is("claude-instance-model"));
        assertThat(backend.get().apiKey(), is("instance-key"));
    }

    @Test
    public void shouldResolveNoLlmBackendWhenNeitherInstanceNorStaticStoreConfiguresOne() {
        assertThat(new org.mockserver.llm.client.LlmBackendResolver(name -> null, null)
            .resolveDefault().isPresent(), is(false));
    }

    // ----- llmBackendsConfig (LlmBackendResolver#namedBackends) -------------------------------

    @Test
    public void shouldResolveNamedLlmBackendFromConfigurationInstance() throws Exception {
        java.io.File backends = java.io.File.createTempFile("mockserver-llm-backends", ".json");
        backends.deleteOnExit();
        java.nio.file.Files.write(backends.toPath(),
            ("[{\"name\":\"instance-backend\",\"provider\":\"OPENAI\",\"model\":\"gpt-instance\"}]")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Configuration configuration = Configuration.configuration()
            .llmBackendsConfig(backends.getAbsolutePath());

        java.util.Optional<org.mockserver.llm.client.LlmBackend> named =
            new org.mockserver.llm.client.LlmBackendResolver(name -> null, configuration)
                .resolveByName("instance-backend");

        assertThat("llmBackendsConfig set on the instance must be read", named.isPresent(), is(true));
        assertThat(named.get().model(), is("gpt-instance"));
    }

    // ----- llmRequestTimeoutMillis (LlmCompletionService#complete) ----------------------------

    @Test
    public void shouldApplyLlmRequestTimeoutFromConfigurationInstance() {
        long instanceTimeout = 1234L;
        Configuration configuration = Configuration.configuration().llmRequestTimeoutMillis(instanceTimeout);

        java.util.concurrent.atomic.AtomicLong observedTimeout = new java.util.concurrent.atomic.AtomicLong(-1);
        org.mockserver.llm.client.LlmClientRegistry registry = new org.mockserver.llm.client.LlmClientRegistry();
        registry.register(new RecordingLlmClient());

        org.mockserver.llm.client.LlmCompletionService service =
            new org.mockserver.llm.client.LlmCompletionService(
                (request, timeoutMillis) -> {
                    observedTimeout.set(timeoutMillis);
                    return org.mockserver.model.HttpResponse.response().withStatusCode(200).withBody("{}");
                },
                registry,
                configuration);

        // backend carries no per-backend timeout, so the configuration value is what must be used
        service.complete(org.mockserver.llm.client.LlmBackend.of(Provider.OPENAI, "key"),
            org.mockserver.llm.ParsedConversation.empty());

        assertThat("llmRequestTimeoutMillis set on the instance must be passed to the transport",
            observedTimeout.get(), is(instanceTimeout));
    }

    /** Minimal client so {@link org.mockserver.llm.client.LlmCompletionService} reaches the transport. */
    private static final class RecordingLlmClient implements org.mockserver.llm.client.LlmClient {
        @Override
        public Provider provider() {
            return Provider.OPENAI;
        }

        @Override
        public HttpRequest buildCompletionRequest(org.mockserver.llm.client.LlmBackend backend,
                                                  org.mockserver.llm.ParsedConversation prompt) {
            return HttpRequest.request().withPath("/v1/chat/completions");
        }

        @Override
        public org.mockserver.model.Completion parseCompletionResponse(org.mockserver.model.HttpResponse response) {
            return new org.mockserver.model.Completion();
        }
    }

    // ----- llmProvider / llmBaseUrl (LlmProviderSniffer, pushed-in Configuration) --------------

    @Test
    public void shouldFallBackToConfiguredProviderFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().llmProvider("COHERE");
        org.mockserver.llm.client.LlmProviderSniffer.setConfiguration(configuration);

        // unknown host + an LLM-looking path -> only the configured-provider fallback can classify it
        assertThat("llmProvider set on the instance must drive the configured-provider fallback",
            org.mockserver.llm.client.LlmProviderSniffer
                .sniffByHostAndPath("unknown.example.com", "/v1/chat/completions")
                .orElse(null),
            is(Provider.COHERE));
    }

    @Test
    public void shouldMatchConfiguredOllamaBaseUrlHostFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration().llmBaseUrl("http://my-ollama.example:11434");
        org.mockserver.llm.client.LlmProviderSniffer.setConfiguration(configuration);

        assertThat("llmBaseUrl set on the instance must be matched against the forwarded host",
            org.mockserver.llm.client.LlmProviderSniffer
                .sniffByHostAndPath("my-ollama.example", "/api/chat")
                .orElse(null),
            is(Provider.OLLAMA));
    }

    @Test
    public void shouldNotClassifyUnknownHostWhenNoProviderConfiguredAnywhere() {
        assertThat(org.mockserver.llm.client.LlmProviderSniffer
            .sniffByHostAndPath("unknown.example.com", "/v1/chat/completions").isPresent(), is(false));
    }

    // ----- fixtureBodyRedactFields (LlmOptimisationReportService#bodyFields) -------------------

    @Test
    public void shouldReadRedactedBodyFieldsFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration()
            .fixtureBodyRedactFields("instanceSecret,otherInstanceField");

        java.util.List<String> fields =
            new org.mockserver.llm.analysis.LlmOptimisationReportService(configuration).redactedBodyFieldNames();

        assertThat("fixtureBodyRedactFields set on the instance must drive report redaction",
            fields, org.hamcrest.Matchers.contains("instanceSecret", "otherInstanceField"));
    }

    // ----- fixtureBodyRedactFields (LogEntry#logRedactor) -------------------------------------

    @Test
    public void shouldRedactLogBodyFieldsNamedOnConfigurationInstance() {
        Configuration configuration = Configuration.configuration()
            .redactSecretsInLog(true)
            .fixtureBodyRedactFields("instanceOnlySecret");

        org.mockserver.log.model.LogEntry logEntry = new org.mockserver.log.model.LogEntry()
            .setHttpRequest(HttpRequest.request()
                .withPath("/some/path")
                .withBody("{\"instanceOnlySecret\":\"leaked-value\"}"));

        String redacted = String.valueOf(logEntry.getRedactedHttpRequest(configuration));

        assertThat("a body field named only on the instance must still be masked",
            redacted.contains("leaked-value"), is(false));
    }

    // ----- customJsonUnitMatchersClass (CustomJsonUnitMatcherLoader#load) ----------------------

    @Test
    public void shouldLoadCustomJsonUnitMatchersFromConfigurationInstance() {
        Configuration configuration = Configuration.configuration()
            .customJsonUnitMatchersClass(InstanceMatcherProvider.class.getName());

        java.util.Map<String, org.hamcrest.Matcher<?>> matchers =
            org.mockserver.matchers.CustomJsonUnitMatcherLoader.load(configuration);

        assertThat("customJsonUnitMatchersClass set on the instance must be loaded",
            matchers.containsKey("instanceMatcher"), is(true));
    }

    @Test
    public void shouldLoadNoCustomJsonUnitMatchersWhenConfiguredNowhere() {
        assertThat(org.mockserver.matchers.CustomJsonUnitMatcherLoader.load((Configuration) null).isEmpty(), is(true));
    }

    /** Provider named only via a {@link Configuration} instance in the test above. */
    public static final class InstanceMatcherProvider
        implements org.mockserver.matchers.CustomJsonUnitMatcherProvider {
        @Override
        public java.util.Map<String, org.hamcrest.Matcher<?>> jsonUnitMatchers() {
            return java.util.Collections.singletonMap("instanceMatcher",
                org.hamcrest.Matchers.notNullValue());
        }
    }

}
