package org.mockserver.serialization.serializers.certificate;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.ClientCertificate;

import java.io.IOException;

/**
 * Serialises the {@link ClientCertificate} expectation criteria, emitting only the criteria that are
 * present (each as a {@code NottableString}, so regex / {@code !} negation / optional forms are
 * preserved).
 *
 * @author jamesdbloom
 */
public class ClientCertificateSerializer extends StdSerializer<ClientCertificate> {

    private static final long serialVersionUID = 1L;

    public ClientCertificateSerializer() {
        super(ClientCertificate.class);
    }

    @Override
    public void serialize(ClientCertificate clientCertificate, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (clientCertificate.getSubject() != null) {
            jgen.writeObjectField("subject", clientCertificate.getSubject());
        }
        if (clientCertificate.getIssuer() != null) {
            jgen.writeObjectField("issuer", clientCertificate.getIssuer());
        }
        if (clientCertificate.getFingerprintSha256() != null) {
            jgen.writeObjectField("fingerprintSha256", clientCertificate.getFingerprintSha256());
        }
        jgen.writeEndObject();
    }
}
