package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.Charset;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @author jamesdbloom
 */
public abstract class Body<T> extends Not {
    private int hashCode;
    private final Type type;
    private Boolean optional;

    public Body(Type type) {
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    public Boolean getOptional() {
        return optional;
    }

    public Body<T> withOptional(Boolean optional) {
        this.optional = optional;
        return this;
    }

    public abstract T getValue();

    @JsonIgnore
    public byte[] getRawBytes() {
        return toString().getBytes(UTF_8);
    }

    /**
     * Drop any lazily-derived, re-computable representation this body caches (the decoded String view and,
     * for {@link JsonBody}, the parsed tree) once the entry that holds it has been retained in the event
     * log. The canonical raw bytes are kept, so the string re-derives identically on the next read. No-op
     * here; only the text bodies that hold a String copy override it.
     */
    @JsonIgnore
    public void releaseDerivedForms() {
    }

    /**
     * Approximate heap, in bytes, currently held by this body's derived String view (0 when it has been
     * released or was never materialised). Counted by the event-log weigher so an entry whose String is
     * still cached is charged for it. No-op here; the text bodies override it.
     */
    @JsonIgnore
    public long retainedDerivedFormBytes() {
        return 0L;
    }

    @JsonIgnore
    public Charset getCharset(Charset defaultIfNotSet) {
        if (this instanceof BodyWithContentType) {
            return this.getCharset(defaultIfNotSet);
        }
        return defaultIfNotSet;
    }

    public String getContentType() {
        if (this instanceof BodyWithContentType) {
            return this.getContentType();
        }
        return null;
    }

    public enum Type {
        BINARY,
        JSON,
        JSON_SCHEMA,
        JSON_PATH,
        PARAMETERS,
        REGEX,
        FUZZY,
        STRING,
        XML,
        XML_SCHEMA,
        XPATH,
        JSON_RPC,
        GRAPHQL,
        LOG_EVENT,
        FILE,
        WASM,
        MULTIPART,
        ALL_OF,
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
        Body<?> body = (Body<?>) o;
        return type == body.type &&
            Objects.equals(optional, body.optional);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), type, optional);
        }
        return hashCode;
    }
}
