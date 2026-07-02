package org.mockserver.serialization.serializers.jwt;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.Jwt;

import java.io.IOException;

/**
 * Serialises the {@link Jwt} expectation criteria, emitting only the fields that are present. Claim
 * values, issuer, audience and algorithm are {@code NottableString}s so their regex / {@code !}
 * negation / optional forms are preserved. Default {@code header} / {@code scheme} values are
 * omitted.
 *
 * @author jamesdbloom
 */
public class JwtSerializer extends StdSerializer<Jwt> {

    private static final long serialVersionUID = 1L;

    public JwtSerializer() {
        super(Jwt.class);
    }

    @Override
    public void serialize(Jwt jwt, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (jwt.getHeader() != null && !Jwt.DEFAULT_HEADER.equals(jwt.getHeader())) {
            jgen.writeStringField("header", jwt.getHeader());
        }
        if (jwt.getScheme() != null && !Jwt.DEFAULT_SCHEME.equals(jwt.getScheme())) {
            jgen.writeStringField("scheme", jwt.getScheme());
        }
        if (jwt.getClaims() != null && !jwt.getClaims().isEmpty()) {
            jgen.writeObjectField("claims", jwt.getClaims());
        }
        if (jwt.getIssuer() != null) {
            jgen.writeObjectField("issuer", jwt.getIssuer());
        }
        if (jwt.getAudience() != null) {
            jgen.writeObjectField("audience", jwt.getAudience());
        }
        if (jwt.getAlgorithm() != null) {
            jgen.writeObjectField("algorithm", jwt.getAlgorithm());
        }
        jgen.writeEndObject();
    }
}
