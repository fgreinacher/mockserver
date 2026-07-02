package org.mockserver.llm.codec;

import org.mockserver.model.Provider;

/**
 * Codec for Mistral AI ({@code api.mistral.ai}). Mistral's chat API is
 * OpenAI-chat-compatible, so all encoding/decoding delegates to
 * {@link OpenAiChatCompletionsCodec} via {@link OpenAiCompatibleChatCodec}.
 */
public class MistralCodec extends OpenAiCompatibleChatCodec {

    @Override
    public Provider provider() {
        return Provider.MISTRAL;
    }
}
