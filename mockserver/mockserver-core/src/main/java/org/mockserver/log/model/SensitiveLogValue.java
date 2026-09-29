package org.mockserver.log.model;

import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.model.NottableString;

import java.util.Objects;

/**
 * A log argument holding a value taken from a request or response when the entry carries no request to redact it
 * against, such as the value a matcher compared. {@link LogEntry} renders the value unchanged when
 * {@code redactSecretsInLog} is off and {@value FixtureRedactor#REDACTED_PLACEHOLDER} when it is on, and scrubs the
 * value from the rest of the entry's text (for example an exception message that quotes it).
 */
public final class SensitiveLogValue {

    private final Object value;

    private SensitiveLogValue(Object value) {
        this.value = value;
    }

    public static SensitiveLogValue of(Object value) {
        return new SensitiveLogValue(value);
    }

    public Object getValue() {
        return value;
    }

    /**
     * The value as it appears in text: a {@link NottableString}'s own value, else its string form.
     */
    String text() {
        return value instanceof NottableString ? ((NottableString) value).getValue() : String.valueOf(value);
    }

    /**
     * Always masked: a caller reaching the value through {@code toString()} cannot see the effective setting.
     */
    @Override
    public String toString() {
        return FixtureRedactor.REDACTED_PLACEHOLDER;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SensitiveLogValue && Objects.equals(value, ((SensitiveLogValue) other).value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
