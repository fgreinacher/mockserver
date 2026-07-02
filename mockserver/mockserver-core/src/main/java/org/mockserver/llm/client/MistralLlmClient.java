package org.mockserver.llm.client;

import org.mockserver.model.Provider;

/**
 * Runtime client for Mistral AI. OpenAI-chat-compatible, so it inherits request
 * building and response parsing from {@link OpenAiLlmClient}; only the provider and
 * default base URL ({@code https://api.mistral.ai}) differ.
 */
public class MistralLlmClient extends OpenAiLlmClient {

    @Override
    public Provider provider() {
        return Provider.MISTRAL;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.mistral.ai";
    }
}
