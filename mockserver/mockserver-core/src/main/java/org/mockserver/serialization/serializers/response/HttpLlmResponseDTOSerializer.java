package org.mockserver.serialization.serializers.response;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.serialization.model.HttpLlmResponseDTO;

import java.io.IOException;

public class HttpLlmResponseDTOSerializer extends StdSerializer<HttpLlmResponseDTO> {
    private static final long serialVersionUID = 1L;

    public HttpLlmResponseDTOSerializer() {
        super(HttpLlmResponseDTO.class);
    }

    @Override
    public void serialize(HttpLlmResponseDTO httpLlmResponseDTO, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (httpLlmResponseDTO.getProvider() != null) {
            jgen.writeObjectField("provider", httpLlmResponseDTO.getProvider());
        }
        if (httpLlmResponseDTO.getModel() != null) {
            jgen.writeObjectField("model", httpLlmResponseDTO.getModel());
        }
        if (httpLlmResponseDTO.getCompletion() != null) {
            jgen.writeObjectField("completion", httpLlmResponseDTO.getCompletion());
        }
        if (httpLlmResponseDTO.getEmbedding() != null) {
            jgen.writeObjectField("embedding", httpLlmResponseDTO.getEmbedding());
        }
        if (httpLlmResponseDTO.getRerank() != null) {
            jgen.writeObjectField("rerank", httpLlmResponseDTO.getRerank());
        }
        if (httpLlmResponseDTO.getModeration() != null) {
            jgen.writeObjectField("moderation", httpLlmResponseDTO.getModeration());
        }
        if (httpLlmResponseDTO.getContentFilter() != null) {
            jgen.writeObjectField("contentFilter", httpLlmResponseDTO.getContentFilter());
        }
        if (httpLlmResponseDTO.getConversationPredicates() != null) {
            jgen.writeObjectField("conversationPredicates", httpLlmResponseDTO.getConversationPredicates());
        }
        if (httpLlmResponseDTO.getChaos() != null) {
            jgen.writeObjectField("chaos", httpLlmResponseDTO.getChaos());
        }
        if (httpLlmResponseDTO.getDelay() != null) {
            jgen.writeObjectField("delay", httpLlmResponseDTO.getDelay());
        }
        if (httpLlmResponseDTO.isPrimary()) {
            jgen.writeObjectField("primary", true);
        }
        jgen.writeEndObject();
    }
}
