package org.mockserver.log.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.fixture.SensitiveValueMatcher;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A copy of a logged throwable, shown in its place when {@code redactSecretsInLog} is enabled and its message (or a
 * cause's) quotes a credential: the messages have those values masked, the stack traces are the original ones, and
 * the message leads with the original class name, so every renderer (logback, {@code printStackTrace}, JSON) still
 * says what was thrown.
 */
// getters only: this is not a JDK class, so field detection would try to open Throwable's private fields
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.PUBLIC_ONLY, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public final class RedactedThrowable extends RuntimeException {

    private final String originalClassName;

    private RedactedThrowable(String originalClassName, String message, Throwable cause) {
        super(message, cause, true, true);
        this.originalClassName = originalClassName;
    }

    static RedactedThrowable of(Throwable original, SensitiveValueMatcher sensitiveValues) {
        return copy(original, sensitiveValues, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Each throwable is copied once: a throwable met again (a cyclic cause or suppressed graph) is left out, so the
     * copy is finite and serializable, where the original would recurse.
     */
    private static RedactedThrowable copy(Throwable original, SensitiveValueMatcher sensitiveValues, Set<Throwable> visited) {
        if (original == null || !visited.add(original)) {
            return null;
        }
        String className = original.getClass().getName();
        String message = sensitiveValues.scrub(original.getMessage());
        RedactedThrowable copy = new RedactedThrowable(className, message == null ? className : className + ": " + message, copy(original.getCause(), sensitiveValues, visited));
        copy.setStackTrace(original.getStackTrace());
        for (Throwable suppressed : original.getSuppressed()) {
            RedactedThrowable suppressedCopy = copy(suppressed, sensitiveValues, visited);
            if (suppressedCopy != null) {
                copy.addSuppressed(suppressedCopy);
            }
        }
        return copy;
    }

    /**
     * The original's stack trace is set on the copy; capturing the copy's own would only be discarded.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    @JsonIgnore
    public String getOriginalClassName() {
        return originalClassName;
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
