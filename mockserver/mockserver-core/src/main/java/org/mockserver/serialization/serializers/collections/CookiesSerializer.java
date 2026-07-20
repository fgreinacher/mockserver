package org.mockserver.serialization.serializers.collections;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.Cookie;
import org.mockserver.model.Cookies;
import org.mockserver.serialization.serializers.string.NottableStringSerializer;

import java.io.IOException;

import static org.mockserver.model.NottableString.serialiseNottableString;

/**
 * @author jamesdbloom
 */
public class CookiesSerializer extends StdSerializer<Cookies> {

    private static final long serialVersionUID = 1L;

    public CookiesSerializer() {
        super(Cookies.class);
    }

    @Override
    public void serialize(Cookies collection, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        // A cookie name goes on the wire as a JSON field name, which has no object form. A name
        // whose own first character is a marker ('!' negation, '?' optional) would be stripped on
        // read, so "cookie named !c" comes back as "cookie NOT named c" — the inverse. Fall back to
        // the array form, whose `name` the deserialiser reads verbatim. Only collections that would
        // otherwise be corrupted take this branch, so everything else stays byte-identical.
        boolean anyAmbiguousName = collection.getEntries().stream()
            .anyMatch(cookie -> NottableStringSerializer.isAmbiguousAsPlainString(cookie.getName()));

        if (anyAmbiguousName) {
            jgen.writeStartArray();
            for (Cookie cookie : collection.getEntries()) {
                jgen.writeStartObject();
                jgen.writeFieldName("name");
                jgen.writeObject(cookie.getName());
                jgen.writeFieldName("value");
                jgen.writeObject(cookie.getValue());
                jgen.writeEndObject();
            }
            jgen.writeEndArray();
            return;
        }

        jgen.writeStartObject();
        for (Cookie cookie : collection.getEntries()) {
            jgen.writeObjectField(serialiseNottableString(cookie.getName()), cookie.getValue());
        }
        jgen.writeEndObject();
    }

}
