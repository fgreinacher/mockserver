package org.mockserver.llm.client;

import org.mockserver.model.Provider;

/**
 * Runtime client for Groq. OpenAI-chat-compatible, so it inherits request building
 * and response parsing from {@link OpenAiLlmClient}. The default base URL includes
 * Groq's {@code /openai} prefix ({@code https://api.groq.com/openai}), which combines
 * with the inherited {@code /v1/chat/completions} path.
 */
public class GroqLlmClient extends OpenAiLlmClient {

    @Override
    public Provider provider() {
        return Provider.GROQ;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.groq.com/openai";
    }
}
