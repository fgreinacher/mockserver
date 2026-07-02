package org.mockserver.llm.codec;

import org.mockserver.model.Provider;

/**
 * Codec for OpenRouter ({@code openrouter.ai}). OpenRouter exposes an
 * OpenAI-compatible chat API ({@code /api/v1/chat/completions}) that fronts many
 * upstream models, so all encoding/decoding delegates to
 * {@link OpenAiChatCompletionsCodec} via {@link OpenAiCompatibleChatCodec}.
 */
public class OpenRouterCodec extends OpenAiCompatibleChatCodec {

    @Override
    public Provider provider() {
        return Provider.OPENROUTER;
    }
}
