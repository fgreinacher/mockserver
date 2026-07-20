package org.mockserver.serialization.serializers.collections;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.KeyMatchStyle;
import org.mockserver.model.KeyToMultiValue;
import org.mockserver.model.KeysToMultiValues;
import org.mockserver.model.NottableString;
import org.mockserver.serialization.serializers.string.NottableStringSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import static org.mockserver.model.NottableString.serialiseNottableString;

/**
 * @author jamesdbloom
 */
public abstract class KeysToMultiValuesSerializer<T extends KeysToMultiValues<? extends KeyToMultiValue, T>> extends StdSerializer<T> {

    private static final long serialVersionUID = 1L;

    KeysToMultiValuesSerializer(Class<T> valueClass) {
        super(valueClass);
    }

    @Override
    public void serialize(T collection, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        ArrayList<NottableString> sortedKeys = new ArrayList<>(collection.keySet());
        Collections.sort(sortedKeys);

        // A key goes on the wire as a JSON FIELD NAME, which has no object form — so a name whose
        // own first character is a marker ('!' negation, '?' optional) cannot be written as a bare
        // string: the reader strips the marker unconditionally and a header literally named "!foo"
        // comes back as "NOT foo", inverting the match. Serialising such a collection in the array
        // form instead lets each name be written as an object whose `value` is read verbatim.
        //
        // Only collections that would otherwise be corrupted take this branch, so output stays
        // byte-identical for every collection that was already round-tripping correctly.
        if (sortedKeys.stream().anyMatch(NottableStringSerializer::isAmbiguousAsPlainString)) {
            serialiseAsArray(collection, jgen, sortedKeys);
            return;
        }

        jgen.writeStartObject();
        if (collection.getKeyMatchStyle() != null && collection.getKeyMatchStyle() != KeyMatchStyle.SUB_SET) {
            jgen.writeObjectField("keyMatchStyle", collection.getKeyMatchStyle());
        }
        for (NottableString key : sortedKeys) {
            jgen.writeFieldName(serialiseNottableString(key));
            if (key.getParameterStyle() != null) {
                jgen.writeStartObject();
                jgen.writeObjectField("parameterStyle", key.getParameterStyle());
                jgen.writeFieldName("values");
                writeValuesArray(collection, jgen, key);
                jgen.writeEndObject();
            } else {
                writeValuesArray(collection, jgen, key);
            }
        }
        jgen.writeEndObject();
    }

    /**
     * The {@code [{ "name": ..., "values": [...] }]} form, whose `name` the deserialiser reads with
     * the full NottableString reader rather than as a marker-prefixed field name.
     */
    private void serialiseAsArray(T collection, JsonGenerator jgen, ArrayList<NottableString> sortedKeys) throws IOException {
        jgen.writeStartArray();
        for (NottableString key : sortedKeys) {
            jgen.writeStartObject();
            jgen.writeFieldName("name");
            // writeObject routes through NottableStringSerializer, which emits the object form for
            // exactly the names that need it and a plain string for the rest
            jgen.writeObject(key);
            jgen.writeFieldName("values");
            writeValuesArray(collection, jgen, key);
            jgen.writeEndObject();
        }
        jgen.writeEndArray();
    }

    private void writeValuesArray(T collection, JsonGenerator jgen, NottableString key) throws IOException {
        Collection<NottableString> values = collection.getValues(key);
        jgen.writeStartArray(values, values.size());
        for (NottableString nottableString : values) {
            jgen.writeObject(nottableString);
        }
        jgen.writeEndArray();
    }

}
