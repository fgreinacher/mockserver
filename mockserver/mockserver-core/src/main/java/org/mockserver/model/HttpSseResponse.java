package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;

public class HttpSseResponse extends Action<HttpSseResponse> {
    private int hashCode;
    private Integer statusCode;
    private Headers headers;
    private List<SseEvent> events;
    private Boolean closeConnection;
    private HttpTemplate.TemplateType templateType;

    public static HttpSseResponse sseResponse() {
        return new HttpSseResponse();
    }

    public HttpSseResponse withStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
        this.hashCode = 0;
        return this;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public HttpSseResponse withHeaders(Headers headers) {
        this.headers = headers;
        this.hashCode = 0;
        return this;
    }

    public HttpSseResponse withHeader(Header header) {
        if (this.headers == null) {
            this.headers = new Headers();
        }
        this.headers.withEntry(header);
        this.hashCode = 0;
        return this;
    }

    public HttpSseResponse withHeader(String name, String... values) {
        if (this.headers == null) {
            this.headers = new Headers();
        }
        this.headers.withEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    public Headers getHeaders() {
        return headers;
    }

    public HttpSseResponse withEvents(List<SseEvent> events) {
        this.events = events;
        this.hashCode = 0;
        return this;
    }

    public HttpSseResponse withEvents(SseEvent... events) {
        this.events = Arrays.asList(events);
        this.hashCode = 0;
        return this;
    }

    public HttpSseResponse withEvent(SseEvent event) {
        if (this.events == null) {
            this.events = new ArrayList<>();
        }
        this.events.add(event);
        this.hashCode = 0;
        return this;
    }

    public List<SseEvent> getEvents() {
        return events;
    }

    public HttpSseResponse withCloseConnection(Boolean closeConnection) {
        this.closeConnection = closeConnection;
        this.hashCode = 0;
        return this;
    }

    public Boolean getCloseConnection() {
        return closeConnection;
    }

    /**
     * Opt-in response templating: when set (to {@link HttpTemplate.TemplateType#VELOCITY},
     * {@link HttpTemplate.TemplateType#MUSTACHE} or {@link HttpTemplate.TemplateType#JAVASCRIPT}),
     * each event's {@code data} payload is rendered as a response template against the triggering
     * request (so {@code $!request.body}, {@code $jsonPath(...)}, the built-in helpers, the
     * {@code faker} helper and the {@code scenario} helper are all available) rather than emitted
     * verbatim. The template is rendered once per event, immediately before the event is written.
     * <p>
     * When {@code null} (the default) every event is emitted byte-for-byte unchanged, exactly as
     * before this field existed.
     */
    public HttpSseResponse withTemplateType(HttpTemplate.TemplateType templateType) {
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
        return Type.SSE_RESPONSE;
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
        HttpSseResponse that = (HttpSseResponse) o;
        return Objects.equals(statusCode, that.statusCode) &&
            Objects.equals(headers, that.headers) &&
            Objects.equals(events, that.events) &&
            Objects.equals(closeConnection, that.closeConnection) &&
            templateType == that.templateType;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), statusCode, headers, events, closeConnection, templateType);
        }
        return hashCode;
    }
}
