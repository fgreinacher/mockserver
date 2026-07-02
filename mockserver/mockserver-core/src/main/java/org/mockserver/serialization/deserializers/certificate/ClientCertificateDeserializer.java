package org.mockserver.serialization.deserializers.certificate;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.mockserver.model.ClientCertificate;
import org.mockserver.model.NottableString;

import java.io.IOException;

/**
 * Deserialises the {@link ClientCertificate} expectation criteria. Each field is read as a
 * {@code NottableString} so a plain string, a regex, or a {@code {"not":true,"value":"..."}} form is
 * accepted for {@code subject}, {@code issuer} and {@code fingerprintSha256}.
 *
 * @author jamesdbloom
 */
public class ClientCertificateDeserializer extends StdDeserializer<ClientCertificate> {

    private static final long serialVersionUID = 1L;

    public ClientCertificateDeserializer() {
        super(ClientCertificate.class);
    }

    @Override
    public ClientCertificate deserialize(JsonParser jsonParser, DeserializationContext ctxt) throws IOException {
        if (jsonParser.getCurrentToken() == JsonToken.START_OBJECT) {
            ClientCertificate clientCertificate = new ClientCertificate();
            while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = jsonParser.currentName();
                if (fieldName != null) {
                    switch (fieldName) {
                        case "subject": {
                            jsonParser.nextToken();
                            clientCertificate.withSubject(ctxt.readValue(jsonParser, NottableString.class));
                            break;
                        }
                        case "issuer": {
                            jsonParser.nextToken();
                            clientCertificate.withIssuer(ctxt.readValue(jsonParser, NottableString.class));
                            break;
                        }
                        case "fingerprintSha256": {
                            jsonParser.nextToken();
                            clientCertificate.withFingerprintSha256(ctxt.readValue(jsonParser, NottableString.class));
                            break;
                        }
                        default: {
                            jsonParser.nextToken();
                            jsonParser.skipChildren();
                        }
                    }
                }
            }
            return clientCertificate;
        }
        return null;
    }
}
