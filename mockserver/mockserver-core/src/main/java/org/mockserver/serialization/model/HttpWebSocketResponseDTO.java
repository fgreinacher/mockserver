package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.HttpWebSocketResponse;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.List;

public class HttpWebSocketResponseDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpWebSocketResponse> {
    private DelayDTO delay;
    private String subprotocol;
    private List<WebSocketMessageModelDTO> messages;
    private List<WebSocketMessageMatcherDTO> matchers;
    private Boolean closeConnection;
    private GraphQLBodyDTO graphqlSubscriptionFilter;
    private HttpTemplate.TemplateType templateType;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean primary;

    public HttpWebSocketResponseDTO(HttpWebSocketResponse httpWebSocketResponse) {
        if (httpWebSocketResponse != null) {
            if (httpWebSocketResponse.getDelay() != null) {
                delay = new DelayDTO(httpWebSocketResponse.getDelay());
            }
            subprotocol = httpWebSocketResponse.getSubprotocol();
            closeConnection = httpWebSocketResponse.getCloseConnection();
            if (httpWebSocketResponse.getMessages() != null) {
                messages = new ArrayList<>();
                httpWebSocketResponse.getMessages().forEach(message -> messages.add(new WebSocketMessageModelDTO(message)));
            }
            if (httpWebSocketResponse.getMatchers() != null) {
                matchers = new ArrayList<>();
                httpWebSocketResponse.getMatchers().forEach(matcher -> matchers.add(new WebSocketMessageMatcherDTO(matcher)));
            }
            if (httpWebSocketResponse.getGraphqlSubscriptionFilter() != null) {
                graphqlSubscriptionFilter = new GraphQLBodyDTO(httpWebSocketResponse.getGraphqlSubscriptionFilter());
            }
            templateType = httpWebSocketResponse.getTemplateType();
            primary = httpWebSocketResponse.isPrimary();
        }
    }

    public HttpWebSocketResponseDTO() {
    }

    public HttpWebSocketResponse buildObject() {
        HttpWebSocketResponse httpWebSocketResponse = new HttpWebSocketResponse()
            .withDelay(delay != null ? delay.buildObject() : null)
            .withSubprotocol(subprotocol)
            .withCloseConnection(closeConnection)
            .withTemplateType(templateType)
            .withPrimary(primary);
        if (messages != null) {
            messages.forEach(messageDTO -> httpWebSocketResponse.withMessage(messageDTO.buildObject()));
        }
        if (matchers != null) {
            matchers.forEach(matcherDTO -> httpWebSocketResponse.withMatcher(matcherDTO.buildObject()));
        }
        if (graphqlSubscriptionFilter != null) {
            httpWebSocketResponse.withGraphqlSubscriptionFilter(graphqlSubscriptionFilter.buildObject());
        }
        return httpWebSocketResponse;
    }

    public DelayDTO getDelay() {
        return delay;
    }

    public HttpWebSocketResponseDTO setDelay(DelayDTO delay) {
        this.delay = delay;
        return this;
    }

    public String getSubprotocol() {
        return subprotocol;
    }

    public HttpWebSocketResponseDTO setSubprotocol(String subprotocol) {
        this.subprotocol = subprotocol;
        return this;
    }

    public List<WebSocketMessageModelDTO> getMessages() {
        return messages;
    }

    public HttpWebSocketResponseDTO setMessages(List<WebSocketMessageModelDTO> messages) {
        this.messages = messages;
        return this;
    }

    public List<WebSocketMessageMatcherDTO> getMatchers() {
        return matchers;
    }

    public HttpWebSocketResponseDTO setMatchers(List<WebSocketMessageMatcherDTO> matchers) {
        this.matchers = matchers;
        return this;
    }

    public Boolean getCloseConnection() {
        return closeConnection;
    }

    public HttpWebSocketResponseDTO setCloseConnection(Boolean closeConnection) {
        this.closeConnection = closeConnection;
        return this;
    }

    public GraphQLBodyDTO getGraphqlSubscriptionFilter() {
        return graphqlSubscriptionFilter;
    }

    public HttpWebSocketResponseDTO setGraphqlSubscriptionFilter(GraphQLBodyDTO graphqlSubscriptionFilter) {
        this.graphqlSubscriptionFilter = graphqlSubscriptionFilter;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    public HttpWebSocketResponseDTO setTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        return this;
    }

    public boolean isPrimary() {
        return primary;
    }

    public HttpWebSocketResponseDTO setPrimary(boolean primary) {
        this.primary = primary;
        return this;
    }
}
