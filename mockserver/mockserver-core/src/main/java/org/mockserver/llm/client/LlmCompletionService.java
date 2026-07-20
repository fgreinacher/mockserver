package org.mockserver.llm.client;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.PromptNormalizer;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NormalizationOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates an outbound completion against a real LLM backend: looks up the
 * per-provider {@link LlmClient}, builds the request, sends it via the
 * {@link LlmTransport}, and parses the response into a {@link Completion}.
 * <p>
 * This is the single entry point used by runtime-LLM features (drift detection,
 * semantic matching). It enforces the roadmap's safety rules:
 * <ul>
 *   <li><b>Off unless configured</b> — callers resolve a backend via
 *       {@link LlmBackendResolver}; with none, the feature is simply unavailable.</li>
 *   <li><b>Fail closed</b> — any timeout, transport error, non-2xx status, or
 *       parse failure returns {@link Optional#empty()} and logs one line. A flaky
 *       network must never flip an assertion.</li>
 *   <li><b>Reproducible</b> — clients pin {@code temperature=0}/seed; responses
 *       are cached per (provider, model, base URL, normalised prompt) so a given
 *       input is stable within a run.</li>
 * </ul>
 * Never sits on the deterministic assertion/matching path itself.
 */
public class LlmCompletionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmCompletionService.class);

    // Bound the within-run reproducibility cache so a long-lived instance with
    // many distinct prompts cannot grow without limit. An access-ordered
    // LinkedHashMap with a removeEldestEntry cap gives bounded LRU eviction of a
    // single oldest entry (reproducibility is best-effort), wrapped in a
    // synchronized map so concurrent get/put are atomic — the previous
    // size()-then-clear() was a non-atomic check-then-act that could drop entries
    // under concurrency.
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final LlmTransport transport;
    private final LlmClientRegistry registry;
    private final Map<String, Completion> cache = Collections.synchronizedMap(
        new LinkedHashMap<String, Completion>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Completion> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        });

    /**
     * The live server {@link org.mockserver.configuration.Configuration}, or {@code null}. Preferred
     * over the static {@link ConfigurationProperties} store when resolving the per-request timeout,
     * so a value set over {@code PUT /mockserver/configuration} actually takes effect.
     */
    private final org.mockserver.configuration.Configuration configuration;

    public LlmCompletionService(LlmTransport transport) {
        this(transport, LlmClientRegistry.getInstance(), null);
    }

    public LlmCompletionService(LlmTransport transport, org.mockserver.configuration.Configuration configuration) {
        this(transport, LlmClientRegistry.getInstance(), configuration);
    }

    public LlmCompletionService(LlmTransport transport, LlmClientRegistry registry) {
        this(transport, registry, null);
    }

    public LlmCompletionService(LlmTransport transport, LlmClientRegistry registry, org.mockserver.configuration.Configuration configuration) {
        this.transport = transport;
        this.registry = registry;
        this.configuration = configuration;
    }

    /**
     * Complete the given prompt against the backend, or {@link Optional#empty()}
     * if no client is registered for the provider or the call fails for any
     * reason (fail-closed). Never throws.
     */
    public Optional<Completion> complete(LlmBackend backend, ParsedConversation prompt) {
        if (backend == null) {
            return Optional.empty();
        }
        Optional<LlmClient> clientOpt = registry.lookup(backend.provider());
        if (!clientOpt.isPresent()) {
            LOGGER.info("no runtime LLM client registered for provider {}, completion unavailable", backend.provider());
            return Optional.empty();
        }
        LlmClient client = clientOpt.get();

        String cacheKey = cacheKey(backend, prompt);
        Completion cached = cache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        try {
            HttpRequest request = client.buildCompletionRequest(backend, prompt);
            // The timeout covers the full request lifecycle (DNS + connect + send +
            // response read), as NettyHttpClient blocks on a single future.get(timeout).
            long timeout = backend.timeoutMillis() != null
                ? backend.timeoutMillis()
                : (configuration != null
                    ? configuration.llmRequestTimeoutMillis()
                    : ConfigurationProperties.llmRequestTimeoutMillis());
            HttpResponse response = transport.send(request, timeout);
            // Only a 2xx is a usable completion; 1xx/3xx/4xx/5xx all fail closed so a
            // redirect or error body never gets parsed into an empty completion and cached.
            Integer status = response == null ? null : response.getStatusCode();
            if (status == null || status < 200 || status >= 300) {
                LOGGER.warn("runtime LLM call to {} returned {}, treating as no completion",
                    backend.provider(), status == null ? "no response" : status);
                return Optional.empty();
            }
            Completion completion = client.parseCompletionResponse(response);
            cacheCompletion(cacheKey, completion);
            return Optional.of(completion);
        } catch (Exception e) {
            LOGGER.warn("runtime LLM call to {} failed ({}), treating as no completion",
                backend.provider(), e.getMessage());
            return Optional.empty();
        }
    }

    private void cacheCompletion(String cacheKey, Completion completion) {
        // Bounded LRU eviction is handled atomically by the synchronized
        // access-ordered LinkedHashMap (removeEldestEntry above).
        cache.put(cacheKey, completion);
    }

    private String cacheKey(LlmBackend backend, ParsedConversation prompt) {
        StringBuilder conversation = new StringBuilder();
        for (ParsedMessage message : prompt.getMessages()) {
            conversation.append(message.getRole()).append(':')
                .append(message.getTextContent() == null ? "" : message.getTextContent()).append('\n');
        }
        String normalised = PromptNormalizer.normalize(conversation.toString(), NormalizationOptions.normalizationOptions());
        return backend.provider() + "|" + backend.model() + "|" + backend.baseUrl() + "|" + normalised;
    }
}
