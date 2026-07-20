package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Joiner;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.NottableOptionalString.OPTIONAL_CHAR;
import static org.mockserver.model.NottableOptionalString.optional;

/**
 * @author jamesdbloom
 */
public class NottableString extends ObjectWithJsonToString implements Comparable<NottableString> {

    public static final char NOT_CHAR = '!';
    private static final String EMPTY_STRING = "";
    private final String value;
    private final boolean isBlank;
    private final Boolean not;
    private final int hashCode;
    private final String json;
    // volatile so the lazily-compiled patterns are safely published across threads. The matcher is
    // called concurrently from request-matching worker threads on a single shared NottableString;
    // without volatile the non-final writes here can be seen as a half-constructed/never-visible
    // Pattern by another thread (unsafe publication) and every racing thread recompiles on the first
    // burst (thundering herd). Pattern is itself immutable and safely publishable, so a rare
    // duplicate Pattern.compile under a race is harmless — the last writer wins and all readers then
    // see a fully-built Pattern.
    private volatile Pattern pattern;
    private volatile Pattern caseSensitivePattern;
    private ParameterStyle parameterStyle;
    private String schemaType;

    NottableString(String value, Boolean not) {
        this.value = value;
        this.isBlank = StringUtils.isBlank(value);
        if (not != null) {
            this.not = not;
        } else {
            this.not = Boolean.FALSE;
        }
        this.hashCode = Objects.hash(this.value, this.not);
        this.json = serialise();
    }

    NottableString(String value) {
        this.isBlank = StringUtils.isBlank(value);
        if (!this.isBlank && value.charAt(0) == NOT_CHAR) {
            this.value = value.substring(1);
            this.not = Boolean.TRUE;
        } else {
            this.value = value;
            this.not = Boolean.FALSE;
        }
        this.hashCode = Objects.hash(this.value, this.not);
        this.json = serialise();
    }

    private String serialise() {
        if (this.isOptional() || this.not) {
            return (this.isOptional() ? "" + OPTIONAL_CHAR : "") + (this.not ? "" + NOT_CHAR : "") + (!this.isBlank ? this.value : EMPTY_STRING);
        } else if (this.isBlank) {
            return EMPTY_STRING;
        } else {
            return this.value;
        }
    }

    public static List<NottableString> deserializeNottableStrings(String... strings) {
        List<NottableString> nottableStrings = new LinkedList<>();
        for (String string : strings) {
            nottableStrings.add(string(string));
        }
        return nottableStrings;
    }

    public static List<NottableString> deserializeNottableStrings(List<String> strings) {
        List<NottableString> nottableStrings = new LinkedList<>();
        for (String string : strings) {
            nottableStrings.add(string(string));
        }
        return nottableStrings;
    }

    public static String serialiseNottableString(NottableString nottableString) {
        return nottableString.toString();
    }

    public static List<String> serialiseNottableStrings(Collection<NottableString> nottableStrings) {
        List<String> strings = new LinkedList<>();
        for (NottableString nottableString : nottableStrings) {
            strings.add(nottableString.toString());
        }
        return strings;
    }

    public static NottableString string(String value, Boolean not) {
        return new NottableString(value, not);
    }

    public static NottableString string(String value) {
        Boolean not = null;
        boolean optional = false;
        if (isNotBlank(value)) {
            if (!value.isEmpty() && value.charAt(0) == OPTIONAL_CHAR) {
                optional = true;
                value = value.substring(1);
            }
            if (!value.isEmpty() && value.charAt(0) == NOT_CHAR) {
                not = true;
                value = value.substring(1);
            }
            if (!value.isEmpty() && value.charAt(0) == OPTIONAL_CHAR) {
                optional = true;
                value = value.substring(1);
            }
        }
        if (optional) {
            return optional(value, not);
        } else {
            return new NottableString(value, not);
        }
    }

    public static NottableString not(String value) {
        return new NottableString(value, Boolean.TRUE);
    }

    public static List<NottableString> strings(String... values) {
        List<NottableString> nottableValues = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                nottableValues.add(string(value));
            }
        }
        return nottableValues;
    }

    /**
     * Build a list of {@link NottableString}s <b>without</b> parsing leading {@code !}/{@code ?}
     * markers, by passing {@code not} through to {@link #string(String, Boolean)} for every value.
     * <p>
     * For recording an actual HTTP message (a parsed request or response), whose names and values
     * are literal text and never matchers — so a header value that happens to begin with {@code !}
     * must stay that literal value rather than being read as a negation. Pass {@code not = false}.
     */
    public static List<NottableString> strings(Collection<String> values, Boolean not) {
        List<NottableString> nottableValues = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                nottableValues.add(string(value, not));
            }
        }
        return nottableValues;
    }

    public static List<NottableString> strings(Collection<String> values) {
        List<NottableString> nottableValues = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                nottableValues.add(string(value));
            }
        }
        return nottableValues;
    }

    public String getValue() {
        return value;
    }

    @JsonIgnore
    public boolean isNot() {
        return not;
    }

    public boolean isOptional() {
        return false;
    }

    public ParameterStyle getParameterStyle() {
        return parameterStyle;
    }

    public NottableString withStyle(ParameterStyle style) {
        this.parameterStyle = style;
        return this;
    }

    public String getSchemaType() {
        return schemaType;
    }

    public NottableString withSchemaType(String schemaType) {
        this.schemaType = schemaType;
        return this;
    }

    NottableString capitalize() {
        final String[] split = (value + "_").split("-");
        for (int i = 0; i < split.length; i++) {
            split[i] = StringUtils.capitalize(split[i]);
        }
        return new NottableString(StringUtils.substringBeforeLast(Joiner.on("-").join(split), "_"), not);
    }

    public NottableString lowercase() {
        return new NottableString(value.toLowerCase(), not);
    }

    public boolean equalsIgnoreCase(Object other) {
        return equals(other, true);
    }

    private boolean equals(Object other, boolean ignoreCase) {
        if (other instanceof String) {
            if (ignoreCase) {
                return not != ((String) other).equalsIgnoreCase(value);
            } else {
                return not != other.equals(value);
            }
        } else if (other instanceof NottableString) {
            NottableString that = (NottableString) other;
            if (that.getValue() == null) {
                return value == null;
            }
            boolean reverse = (that.not != this.not) && (that.not || this.not);
            if (ignoreCase) {
                return reverse != that.getValue().equalsIgnoreCase(value);
            } else {
                return reverse != that.getValue().equals(value);
            }
        }
        return false;
    }

    public boolean isBlank() {
        return isBlank;
    }

    private static final int MAX_REGEX_LENGTH = 8192;

    public boolean matches(String input) {
        return matches(input, false);
    }

    /**
     * Compile this value as a regex and test it against {@code input}.
     * <p>
     * When {@code caseSensitive} is {@code false} (the default — every pre-existing caller) the
     * pattern is compiled with {@code DOTALL | CASE_INSENSITIVE | UNICODE_CASE}, exactly as before,
     * so behaviour is byte-for-byte unchanged. When {@code caseSensitive} is {@code true} (only the
     * method/path/string-body matchers, and only when the opt-in {@code matchExactCase} flag is on)
     * the pattern is compiled with {@code DOTALL} alone, so matching is case-sensitive. The two
     * compiled patterns are cached independently so a value can be used both ways without recompiling.
     */
    public boolean matches(String input, boolean caseSensitive) {
        if (caseSensitive) {
            if (caseSensitivePattern == null) {
                String regex = getValue();
                // a null value can never be compiled as a regex; treat it as a non-match rather than
                // letting Pattern.compile(null, ...) throw an NPE that the catch below would not handle
                if (regex == null || regex.length() > MAX_REGEX_LENGTH) {
                    return false;
                }
                try {
                    caseSensitivePattern = Pattern.compile(regex, Pattern.DOTALL);
                } catch (PatternSyntaxException e) {
                    return false;
                }
            }
            return caseSensitivePattern.matcher(input).matches();
        }
        if (pattern == null) {
            String regex = getValue();
            if (regex != null && regex.length() > MAX_REGEX_LENGTH) {
                return false;
            }
            try {
                pattern = Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
        return pattern.matcher(input).matches();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof String) {
            return not != other.equals(value);
        } else if (this instanceof NottableSchemaString && other instanceof NottableSchemaString) {
            return equalsValue((NottableString) other);
        } else if (this instanceof NottableSchemaString) {
            return equalsSchema((NottableSchemaString) this, (NottableString) other);
        } else if (other instanceof NottableSchemaString) {
            return equalsSchema((NottableSchemaString) other, this);
        } else if (other instanceof NottableString) {
            return equalsValue((NottableString) other);
        }
        return false;
    }

    private boolean equalsSchema(NottableSchemaString schema, NottableString string) {
        if (schema.getValue() == null && string.value == null) {
            return true;
        } else if (schema.getValue() == null || string.value == null) {
            return false;
        } else {
            return schema.matches(string.value);
        }
    }

    private boolean equalsString(NottableString one, NottableString two) {
        if (one.value == null && two.value == null) {
            return true;
        } else if (one.value == null || two.value == null) {
            return false;
        } else {
            boolean reverse = (two.not != one.not) && (two.not || one.not);
            return reverse != two.value.equals(one.value);
        }
    }

    private boolean equalsValue(NottableString other) {
        return equalsString(this, other);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return json;
    }

    @Override
    public int compareTo(NottableString other) {
        return other.getValue().compareTo(this.getValue());
    }
}
