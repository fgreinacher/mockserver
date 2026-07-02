package org.mockserver.llm.client;

import org.mockserver.model.Provider;

/**
 * Runtime client for DeepSeek. OpenAI-chat-compatible, so it inherits request
 * building and response parsing from {@link OpenAiLlmClient}; only the provider and
 * default base URL ({@code https://api.deepseek.com}) differ.
 */
public class DeepSeekLlmClient extends OpenAiLlmClient {

    @Override
    public Provider provider() {
        return Provider.DEEPSEEK;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.deepseek.com";
    }
}
