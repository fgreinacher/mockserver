package org.mockserver.llm.client;

import org.mockserver.model.Provider;

/**
 * Runtime client for xAI Grok. OpenAI-chat-compatible, so it inherits request
 * building and response parsing from {@link OpenAiLlmClient}; only the provider and
 * default base URL ({@code https://api.x.ai}) differ.
 */
public class XaiLlmClient extends OpenAiLlmClient {

    @Override
    public Provider provider() {
        return Provider.XAI;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.x.ai";
    }
}
