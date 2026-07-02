package org.mockserver.async.serde;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Converts message payloads between JSON text and Avro binary using Apache Avro's
 * {@code GenericDatum} reader/writer — no code generation and no Confluent serde
 * stack required.
 *
 * <p>MockServer speaks JSON everywhere (example generation, verification,
 * recorded-message payloads). This codec bridges that to the Avro binary carried in
 * the Confluent wire format:
 * <ul>
 *   <li><b>publish</b>: {@link #jsonToAvro(Schema, String)} turns a JSON example
 *       into Avro binary before framing.</li>
 *   <li><b>subscribe/verify</b>: {@link #avroToJson(Schema, byte[])} turns consumed
 *       Avro binary back into JSON so the existing substring / JSON-path verification
 *       works unchanged.</li>
 * </ul>
 *
 * <p><b>JSON encoding note:</b> Avro's JSON representation is stricter than plain
 * JSON for union-typed (nullable/optional) fields — those require the
 * {@code {"fieldType": value}} form. Flat records of primitive fields (the common
 * case for AsyncAPI example payloads) map straight across.
 */
public final class AvroPayloadCodec {

    private AvroPayloadCodec() {
        // utility class
    }

    /**
     * Parse an Avro schema from its JSON text.
     */
    public static Schema parseSchema(String schemaJson) {
        return new Schema.Parser().parse(schemaJson);
    }

    /**
     * Encode a JSON payload as Avro binary for the given schema.
     *
     * @param schema   the Avro schema
     * @param jsonPayload the payload as JSON text (Avro JSON encoding for unions)
     * @return the Avro binary encoding
     * @throws IOException if the JSON does not conform to the schema
     */
    public static byte[] jsonToAvro(Schema schema, String jsonPayload) throws IOException {
        GenericDatumReader<Object> reader = new GenericDatumReader<>(schema);
        Decoder jsonDecoder = DecoderFactory.get().jsonDecoder(schema, jsonPayload);
        Object datum = reader.read(null, jsonDecoder);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder binaryEncoder = EncoderFactory.get().binaryEncoder(out, null);
        GenericDatumWriter<Object> writer = new GenericDatumWriter<>(schema);
        writer.write(datum, binaryEncoder);
        binaryEncoder.flush();
        return out.toByteArray();
    }

    /**
     * Decode Avro binary back into JSON text for the given schema.
     *
     * @param schema      the Avro schema
     * @param avroBinary  the Avro binary encoding
     * @return the payload as JSON text
     * @throws IOException if the bytes do not conform to the schema
     */
    public static String avroToJson(Schema schema, byte[] avroBinary) throws IOException {
        GenericDatumReader<Object> reader = new GenericDatumReader<>(schema);
        Decoder binaryDecoder = DecoderFactory.get().binaryDecoder(avroBinary, null);
        Object datum = reader.read(null, binaryDecoder);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonEncoder jsonEncoder = EncoderFactory.get().jsonEncoder(schema, out);
        GenericDatumWriter<Object> writer = new GenericDatumWriter<>(schema);
        writer.write(datum, jsonEncoder);
        jsonEncoder.flush();
        return out.toString(StandardCharsets.UTF_8);
    }
}
