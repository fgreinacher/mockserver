package org.mockserver.serialization.model;

import org.mockserver.model.*;

import java.util.ArrayList;
import java.util.List;

public class WebSocketMessageMatcherDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<WebSocketMessageMatcher> {
    private WebSocketFrameType frameType;
    // held as a NottableString, not a String: collapsing to String drops the negation flag. Re-parsing
    // a leading '!' in buildObject() recovers it for most values but silently inverts any literal
    // value that itself begins with '!'.
    private NottableString textMatcher;
    private List<WebSocketMessageModelDTO> responses;

    public WebSocketMessageMatcherDTO(WebSocketMessageMatcher matcher) {
        if (matcher != null) {
            frameType = matcher.getFrameType();
            textMatcher = matcher.getTextMatcher();
            if (matcher.getResponses() != null) {
                responses = new ArrayList<>();
                matcher.getResponses().forEach(response -> responses.add(new WebSocketMessageModelDTO(response)));
            }
        }
    }

    public WebSocketMessageMatcherDTO() {
    }

    public WebSocketMessageMatcher buildObject() {
        WebSocketMessageMatcher matcher = new WebSocketMessageMatcher();
        if (frameType != null) {
            matcher.withFrameType(frameType);
        } else {
            matcher.withFrameType(WebSocketFrameType.ANY);
        }
        if (textMatcher != null) {
            matcher.withTextMatcher(textMatcher);
        }
        if (responses != null) {
            List<WebSocketMessage> messages = new ArrayList<>();
            responses.forEach(dto -> messages.add(dto.buildObject()));
            matcher.withResponses(messages);
        }
        return matcher;
    }

    public WebSocketFrameType getFrameType() {
        return frameType;
    }

    public WebSocketMessageMatcherDTO setFrameType(WebSocketFrameType frameType) {
        this.frameType = frameType;
        return this;
    }

    public NottableString getTextMatcher() {
        return textMatcher;
    }

    public WebSocketMessageMatcherDTO setTextMatcher(NottableString textMatcher) {
        this.textMatcher = textMatcher;
        return this;
    }

    public List<WebSocketMessageModelDTO> getResponses() {
        return responses;
    }

    public WebSocketMessageMatcherDTO setResponses(List<WebSocketMessageModelDTO> responses) {
        this.responses = responses;
        return this;
    }
}
