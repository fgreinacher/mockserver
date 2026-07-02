package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.AllOfBody;
import org.mockserver.model.Body;

import java.io.IOException;

/**
 * @author jamesdbloom
 */
public class AllOfBodySerializer extends StdSerializer<AllOfBody> {

    private static final long serialVersionUID = 1L;

    public AllOfBodySerializer() {
        super(AllOfBody.class);
    }

    @Override
    public void serialize(AllOfBody allOfBody, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (allOfBody.getNot() != null && allOfBody.getNot()) {
            jgen.writeBooleanField("not", allOfBody.getNot());
        }
        if (allOfBody.getOptional() != null && allOfBody.getOptional()) {
            jgen.writeBooleanField("optional", allOfBody.getOptional());
        }
        jgen.writeStringField("type", allOfBody.getType().name());
        jgen.writeArrayFieldStart("bodyAllOf");
        if (allOfBody.getValue() != null) {
            for (Body body : allOfBody.getValue()) {
                jgen.writeObject(body);
            }
        }
        jgen.writeEndArray();
        jgen.writeEndObject();
    }
}
