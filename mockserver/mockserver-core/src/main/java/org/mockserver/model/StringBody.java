package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.MediaType.DEFAULT_TEXT_HTTP_CHARACTER_SET;

/**
 * @author jamesdbloom
 */
public class StringBody extends BodyWithContentType<String> {
    private int hashCode;
    public static final MediaType DEFAULT_CONTENT_TYPE = MediaType.create("text", "plain");
    private final boolean subString;
    // The raw bytes are canonical; value is the derived String view - cached on first read and released
    // once the entry is retained (see releaseDerivedForms). The read/write race with the release is
    // benign: a reader either sees the cached String or null, and null re-derives the identical String.
    private transient String value;
    private final byte[] rawBytes;

    public StringBody(String value) {
        this(value, null, false, null);
    }

    public StringBody(String value, Charset charset) {
        this(value, null, false, (charset != null ? DEFAULT_CONTENT_TYPE.withCharset(charset) : null));
    }

    public StringBody(String value, MediaType contentType) {
        this(value, null, false, contentType);
    }

    public StringBody(String value, byte[] rawBytes, boolean subString, MediaType contentType) {
        super(Type.STRING, contentType);
        this.value = isNotBlank(value) ? value : "";
        this.subString = subString;

        if (rawBytes == null && value != null) {
            this.rawBytes = value.getBytes(determineCharacterSet(contentType, DEFAULT_TEXT_HTTP_CHARACTER_SET));
        } else {
            this.rawBytes = rawBytes;
        }
    }

    public static StringBody exact(String body) {
        return new StringBody(body);
    }

    public static StringBody exact(String body, Charset charset) {
        return new StringBody(body, charset);
    }

    public static StringBody exact(String body, MediaType contentType) {
        return new StringBody(body, contentType);
    }

    public static StringBody subString(String body) {
        return new StringBody(body, null, true, null);
    }

    public static StringBody subString(String body, Charset charset) {
        return new StringBody(body, null, true, (charset != null ? DEFAULT_CONTENT_TYPE.withCharset(charset) : null));
    }

    public static StringBody subString(String body, MediaType contentType) {
        return new StringBody(body, null, true, contentType);
    }

    public String getValue() {
        String v = value;
        if (v == null && rawBytes != null) {
            v = decodeRawBytes(rawBytes);
            value = v;
        }
        return v;
    }

    @Override
    public void releaseDerivedForms() {
        String v = value;
        if (v != null && rawBytes != null && v.equals(decodeRawBytes(rawBytes))) {
            value = null;
        }
    }

    @Override
    public long retainedDerivedFormBytes() {
        String v = value;
        return v == null ? 0L : (long) v.length() * 2;
    }

    @JsonIgnore
    public byte[] getRawBytes() {
        return rawBytes;
    }

    public boolean isSubString() {
        return subString;
    }

    @Override
    public String toString() {
        return getValue();
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
        StringBody that = (StringBody) o;
        return subString == that.subString &&
            Objects.equals(getValue(), that.getValue()) &&
            Arrays.equals(rawBytes, that.rawBytes);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            // keyed on the canonical rawBytes, not the releasable value view, so the hash is stable
            // across a release and never re-materialises the String
            int result = Objects.hash(super.hashCode(), subString);
            hashCode = 31 * result + Arrays.hashCode(rawBytes);
        }
        return hashCode;
    }
}
