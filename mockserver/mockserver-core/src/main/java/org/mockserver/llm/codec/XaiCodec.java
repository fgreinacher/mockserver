package org.mockserver.llm.codec;

import org.mockserver.model.Provider;

/**
 * Codec for xAI Grok ({@code api.x.ai}). xAI's chat API is OpenAI-chat-compatible,
 * so all encoding/decoding delegates to {@link OpenAiChatCompletionsCodec} via
 * {@link OpenAiCompatibleChatCodec}.
 */
public class XaiCodec extends OpenAiCompatibleChatCodec {

    @Override
    public Provider provider() {
        return Provider.XAI;
    }
}
