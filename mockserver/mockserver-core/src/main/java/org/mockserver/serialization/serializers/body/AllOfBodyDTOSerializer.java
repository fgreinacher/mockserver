package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.serialization.model.AllOfBodyDTO;
import org.mockserver.serialization.model.BodyDTO;

import java.io.IOException;

/**
 * @author jamesdbloom
 */
public class AllOfBodyDTOSerializer extends StdSerializer<AllOfBodyDTO> {

    private static final long serialVersionUID = 1L;

    public AllOfBodyDTOSerializer() {
        super(AllOfBodyDTO.class);
    }

    @Override
    public void serialize(AllOfBodyDTO allOfBodyDTO, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (allOfBodyDTO.getNot() != null && allOfBodyDTO.getNot()) {
            jgen.writeBooleanField("not", allOfBodyDTO.getNot());
        }
        if (allOfBodyDTO.getOptional() != null && allOfBodyDTO.getOptional()) {
            jgen.writeBooleanField("optional", allOfBodyDTO.getOptional());
        }
        jgen.writeStringField("type", allOfBodyDTO.getType().name());
        jgen.writeArrayFieldStart("bodyAllOf");
        if (allOfBodyDTO.getBodies() != null) {
            for (BodyDTO body : allOfBodyDTO.getBodies()) {
                jgen.writeObject(body);
            }
        }
        jgen.writeEndArray();
        jgen.writeEndObject();
    }
}
