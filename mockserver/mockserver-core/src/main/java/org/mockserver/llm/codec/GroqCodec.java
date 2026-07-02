package org.mockserver.llm.codec;

import org.mockserver.model.Provider;

/**
 * Codec for Groq ({@code api.groq.com}). Groq's OpenAI-compatible chat API
 * ({@code /openai/v1/chat/completions}) uses the OpenAI Chat Completions wire
 * format, so all encoding/decoding delegates to {@link OpenAiChatCompletionsCodec}
 * via {@link OpenAiCompatibleChatCodec}.
 */
public class GroqCodec extends OpenAiCompatibleChatCodec {

    @Override
    public Provider provider() {
        return Provider.GROQ;
    }
}
