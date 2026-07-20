package org.mockserver.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.Provider;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves which {@link LlmBackend} a runtime-LLM feature should use, across the
 * three config layers (simplest first):
 * <ol>
 *   <li><b>Provider env conventions</b> — auto-detect from {@code OPENAI_API_KEY}
 *       / {@code ANTHROPIC_API_KEY} / {@code GEMINI_API_KEY} / {@code OLLAMA_HOST}
 *       (the same vars each provider SDK reads).</li>
 *   <li><b>MockServer properties</b> — a single default backend via
 *       {@code mockserver.llmProvider} / {@code llmApiKey} / {@code llmModel} /
 *       {@code llmBaseUrl}.</li>
 *   <li><b>Named backends JSON</b> — {@code mockserver.llmBackendsConfig} points
 *       at a JSON array of backends, selectable by name.</li>
 * </ol>
 * The default backend prefers layer 2 (explicit properties) over layer 1 (env);
 * named backends come from layer 3. If nothing resolves, runtime-LLM features
 * are simply unavailable — they fall back to deterministic behaviour.
 * <p>
 * The environment lookup is injected so the resolver is unit-testable without
 * mutating the real process environment.
 */
public class LlmBackendResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmBackendResolver.class);
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    private final Function<String, String> env;

    /**
     * The live server {@link org.mockserver.configuration.Configuration}, or {@code null} when the
     * resolver is constructed without one. Every property read below prefers this instance and falls
     * back to the static {@link ConfigurationProperties} store, so a backend configured over
     * {@code PUT /mockserver/configuration} is actually used rather than silently ignored.
     */
    private final org.mockserver.configuration.Configuration configuration;

    public LlmBackendResolver() {
        this(System::getenv, null);
    }

    public LlmBackendResolver(org.mockserver.configuration.Configuration configuration) {
        this(System::getenv, configuration);
    }

    public LlmBackendResolver(Function<String, String> env) {
        this(env, null);
    }

    public LlmBackendResolver(Function<String, String> env, org.mockserver.configuration.Configuration configuration) {
        this.env = env;
        this.configuration = configuration;
    }

    /**
     * Resolve the default backend: explicit MockServer properties (layer 2) if a
     * provider is configured, otherwise an env-detected backend (layer 1),
     * otherwise empty.
     */
    public Optional<LlmBackend> resolveDefault() {
        Optional<LlmBackend> fromProperties = fromProperties();
        if (fromProperties.isPresent()) {
            return fromProperties;
        }
        return fromEnvironment();
    }

    /**
     * Resolve a named backend from the layer-3 JSON file. Empty if no file is
     * configured, the file cannot be read, or no entry matches the name.
     */
    public Optional<LlmBackend> resolveByName(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(namedBackends().get(name));
    }

    /**
     * All named backends from the layer-3 JSON file, keyed by name. Empty if not
     * configured or unreadable (a parse error is logged, not thrown).
     */
    public Map<String, LlmBackend> namedBackends() {
        String path = configuration != null
            ? configuration.llmBackendsConfig()
            : ConfigurationProperties.llmBackendsConfig();
        if (path == null || path.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            String json = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            return parseBackends(json);
        } catch (Exception e) {
            LOGGER.warn("could not read LLM backends config {}: {}", path, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * Parse a JSON array of backend objects into a name-keyed map. Visible for
     * testing.
     */
    Map<String, LlmBackend> parseBackends(String json) {
        Map<String, LlmBackend> backends = new LinkedHashMap<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    LlmBackend backend = toBackend(node);
                    if (backend != null && backend.name() != null) {
                        backends.put(backend.name(), backend);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("could not parse LLM backends config: {}", e.getMessage());
        }
        return backends;
    }

    private LlmBackend toBackend(JsonNode node) {
        String providerStr = node.path("provider").asText(null);
        Provider provider = parseProvider(providerStr);
        if (provider == null) {
            LOGGER.warn("skipping LLM backend with missing/unknown provider: {}", providerStr);
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        JsonNode headersNode = node.path("headers");
        if (headersNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = headersNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                headers.put(e.getKey(), e.getValue().asText());
            }
        }
        Long timeout = node.has("timeoutMillis") && node.path("timeoutMillis").isNumber()
            ? node.path("timeoutMillis").asLong() : null;
        return new LlmBackend(
            node.path("name").asText(null),
            provider,
            emptyToNull(node.path("baseUrl").asText(null)),
            emptyToNull(node.path("apiKey").asText(null)),
            emptyToNull(node.path("model").asText(null)),
            headers,
            timeout
        );
    }

    private Optional<LlmBackend> fromProperties() {
        String providerStr = configuration != null
            ? configuration.llmProvider()
            : ConfigurationProperties.llmProvider();
        Provider provider = parseProvider(providerStr);
        if (provider == null) {
            return Optional.empty();
        }
        return Optional.of(new LlmBackend(
            null,
            provider,
            emptyToNull(configuration != null ? configuration.llmBaseUrl() : ConfigurationProperties.llmBaseUrl()),
            emptyToNull(configuration != null ? configuration.llmApiKey() : ConfigurationProperties.llmApiKey()),
            emptyToNull(configuration != null ? configuration.llmModel() : ConfigurationProperties.llmModel()),
            null,
            configuration != null ? configuration.llmRequestTimeoutMillis() : ConfigurationProperties.llmRequestTimeoutMillis()
        ));
    }

    private Optional<LlmBackend> fromEnvironment() {
        // Fixed priority so detection is deterministic.
        String ollamaHost = env.apply("OLLAMA_HOST");
        if (notEmpty(ollamaHost)) {
            String baseUrl = ollamaHost.startsWith("http") ? ollamaHost : "http://" + ollamaHost;
            return Optional.of(new LlmBackend(null, Provider.OLLAMA, baseUrl, null, null, null, null));
        }
        String openAiKey = env.apply("OPENAI_API_KEY");
        if (notEmpty(openAiKey)) {
            return Optional.of(LlmBackend.of(Provider.OPENAI, openAiKey));
        }
        String anthropicKey = env.apply("ANTHROPIC_API_KEY");
        if (notEmpty(anthropicKey)) {
            return Optional.of(LlmBackend.of(Provider.ANTHROPIC, anthropicKey));
        }
        String geminiKey = env.apply("GEMINI_API_KEY");
        if (notEmpty(geminiKey)) {
            return Optional.of(LlmBackend.of(Provider.GEMINI, geminiKey));
        }
        return Optional.empty();
    }

    private static Provider parseProvider(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Provider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }
}
