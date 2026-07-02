package org.mockserver.serialization.deserializers.jwt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.mockserver.model.Jwt;
import org.mockserver.model.NottableString;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deserialises the {@link Jwt} expectation criteria. {@code header} and {@code scheme} are plain
 * strings; {@code issuer}, {@code audience} and {@code algorithm} and every value in the {@code
 * claims} map are read as {@code NottableString}s so a plain string, a regex, or a {@code
 * {"not":true,"value":"..."}} form is accepted.
 *
 * @author jamesdbloom
 */
public class JwtDeserializer extends StdDeserializer<Jwt> {

    private static final long serialVersionUID = 1L;

    public JwtDeserializer() {
        super(Jwt.class);
    }

    @Override
    public Jwt deserialize(JsonParser jsonParser, DeserializationContext ctxt) throws IOException {
        if (jsonParser.getCurrentToken() == JsonToken.START_OBJECT) {
            Jwt jwt = new Jwt();
            while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = jsonParser.currentName();
                if (fieldName != null) {
                    switch (fieldName) {
                        case "header": {
                            jsonParser.nextToken();
                            jwt.withHeader(ctxt.readValue(jsonParser, String.class));
                            break;
                        }
                        case "scheme": {
                            jsonParser.nextToken();
                            jwt.withScheme(ctxt.readValue(jsonParser, String.class));
                            break;
                        }
                        case "issuer": {
                            jsonParser.nextToken();
                            jwt.withIssuer(ctxt.readValue(jsonParser, NottableString.class));
                            break;
                        }
                        case "audience": {
                            jsonParser.nextToken();
                            jwt.withAudience(ctxt.readValue(jsonParser, NottableString.class));
                            break;
                        }
                        case "algorithm": {
                            jsonParser.nextToken();
                            jwt.withAlgorithm(ctxt.readValue(jsonParser, NottableString.class));
                            break;
                        }
                        case "claims": {
                            if (jsonParser.nextToken() == JsonToken.START_OBJECT) {
                                Map<String, NottableString> claims = new LinkedHashMap<>();
                                while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
                                    String claimName = jsonParser.currentName();
                                    jsonParser.nextToken();
                                    claims.put(claimName, ctxt.readValue(jsonParser, NottableString.class));
                                }
                                jwt.withClaims(claims);
                            }
                            break;
                        }
                        default: {
                            jsonParser.nextToken();
                            jsonParser.skipChildren();
                        }
                    }
                }
            }
            return jwt;
        }
        return null;
    }
}
