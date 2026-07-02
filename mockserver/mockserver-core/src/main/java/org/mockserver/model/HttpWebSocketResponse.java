package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;

public class HttpWebSocketResponse extends Action<HttpWebSocketResponse> {
    private int hashCode;
    private String subprotocol;
    private List<WebSocketMessage> messages;
    private List<WebSocketMessageMatcher> matchers;
    private Boolean closeConnection;
    private GraphQLBody graphqlSubscriptionFilter;
    private HttpTemplate.TemplateType templateType;

    public static HttpWebSocketResponse webSocketResponse() {
        return new HttpWebSocketResponse();
    }

    public HttpWebSocketResponse withSubprotocol(String subprotocol) {
        this.subprotocol = subprotocol;
        this.hashCode = 0;
        return this;
    }

    public String getSubprotocol() {
        return subprotocol;
    }

    public HttpWebSocketResponse withMessages(List<WebSocketMessage> messages) {
        this.messages = messages;
        this.hashCode = 0;
        return this;
    }

    public HttpWebSocketResponse withMessages(WebSocketMessage... messages) {
        this.messages = Arrays.asList(messages);
        this.hashCode = 0;
        return this;
    }

    public HttpWebSocketResponse withMessage(WebSocketMessage message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
        this.hashCode = 0;
        return this;
    }

    public List<WebSocketMessage> getMessages() {
        return messages;
    }

    public HttpWebSocketResponse withMatchers(List<WebSocketMessageMatcher> matchers) {
        this.matchers = matchers;
        this.hashCode = 0;
        return this;
    }

    public HttpWebSocketResponse withMatchers(WebSocketMessageMatcher... matchers) {
        this.matchers = new ArrayList<>(Arrays.asList(matchers));
        this.hashCode = 0;
        return this;
    }

    public HttpWebSocketResponse withMatcher(WebSocketMessageMatcher matcher) {
        if (this.matchers == null) {
            this.matchers = new ArrayList<>();
        }
        this.matchers.add(matcher);
        this.hashCode = 0;
        return this;
    }

    public List<WebSocketMessageMatcher> getMatchers() {
        return matchers;
    }

    public HttpWebSocketResponse withCloseConnection(Boolean closeConnection) {
        this.closeConnection = closeConnection;
        this.hashCode = 0;
        return this;
    }

    public Boolean getCloseConnection() {
        return closeConnection;
    }

    /**
     * Set a GraphQL subscription filter for the graphql-transport-ws protocol.
     * When the negotiated subprotocol is {@code graphql-transport-ws} or {@code graphql-ws},
     * incoming {@code subscribe} messages will have their query matched against this filter.
     * On match, the configured {@link #messages} are pushed as {@code next} payloads.
     *
     * @param filter the GraphQL body to match subscription queries against
     * @return this instance for fluent chaining
     */
    public HttpWebSocketResponse withGraphqlSubscriptionFilter(GraphQLBody filter) {
        this.graphqlSubscriptionFilter = filter;
        this.hashCode = 0;
        return this;
    }

    public GraphQLBody getGraphqlSubscriptionFilter() {
        return graphqlSubscriptionFilter;
    }

    /**
     * Opt-in response templating: when set (to {@link HttpTemplate.TemplateType#VELOCITY},
     * {@link HttpTemplate.TemplateType#MUSTACHE} or {@link HttpTemplate.TemplateType#JAVASCRIPT}),
     * each configured text {@link WebSocketMessage} is rendered as a response template against the
     * triggering request (so {@code $!request.body}, {@code $jsonPath(...)}, the built-in helpers,
     * the {@code faker} helper and the {@code scenario} helper are all available) rather than sent
     * verbatim. The template is rendered once per message, immediately before the frame is written.
     * Binary messages are never templated.
     * <p>
     * When {@code null} (the default) every message is sent byte-for-byte unchanged, exactly as
     * before this field existed.
     */
    public HttpWebSocketResponse withTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        this.hashCode = 0;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    @Override
    @JsonIgnore
    public Type getType() {
        return Type.WEBSOCKET_RESPONSE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        HttpWebSocketResponse that = (HttpWebSocketResponse) o;
        return Objects.equals(subprotocol, that.subprotocol) &&
            Objects.equals(messages, that.messages) &&
            Objects.equals(matchers, that.matchers) &&
            Objects.equals(closeConnection, that.closeConnection) &&
            Objects.equals(graphqlSubscriptionFilter, that.graphqlSubscriptionFilter) &&
            templateType == that.templateType;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), subprotocol, messages, matchers, closeConnection, graphqlSubscriptionFilter, templateType);
        }
        return hashCode;
    }
}
