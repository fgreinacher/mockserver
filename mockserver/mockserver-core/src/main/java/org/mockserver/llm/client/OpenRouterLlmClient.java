package org.mockserver.llm.client;

import org.mockserver.model.Provider;

/**
 * Runtime client for OpenRouter. OpenAI-chat-compatible, so it inherits request
 * building and response parsing from {@link OpenAiLlmClient}. The default base URL
 * includes OpenRouter's {@code /api} prefix ({@code https://openrouter.ai/api}),
 * which combines with the inherited {@code /v1/chat/completions} path.
 */
public class OpenRouterLlmClient extends OpenAiLlmClient {

    @Override
    public Provider provider() {
        return Provider.OPENROUTER;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://openrouter.ai/api";
    }
}
