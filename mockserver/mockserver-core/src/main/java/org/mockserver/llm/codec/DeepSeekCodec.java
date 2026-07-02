package org.mockserver.llm.codec;

import org.mockserver.model.Provider;

/**
 * Codec for DeepSeek ({@code api.deepseek.com}). DeepSeek's chat API is
 * OpenAI-chat-compatible, so all encoding/decoding delegates to
 * {@link OpenAiChatCompletionsCodec} via {@link OpenAiCompatibleChatCodec}.
 */
public class DeepSeekCodec extends OpenAiCompatibleChatCodec {

    @Override
    public Provider provider() {
        return Provider.DEEPSEEK;
    }
}
