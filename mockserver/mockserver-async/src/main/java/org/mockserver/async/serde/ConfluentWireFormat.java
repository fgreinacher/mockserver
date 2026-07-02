package org.mockserver.async.serde;

import java.nio.ByteBuffer;

/**
 * Encoder/decoder for the <b>Confluent Schema Registry wire format</b> used by
 * Kafka Avro/Protobuf messages.
 *
 * <p>The wire format frames a serialized payload with a 5-byte header:
 * <pre>
 *   byte 0       : magic byte (always 0x00)
 *   bytes 1..4   : 4-byte big-endian schema id (as registered in the Schema Registry)
 *   bytes 5..n   : the serialized payload (Avro binary, Protobuf, …)
 * </pre>
 *
 * <p>This class handles only the framing — it is codec-agnostic and broker-free, so
 * it is trivially unit-testable. It lets MockServer interoperate with real
 * Confluent Avro producers/consumers without pulling in the Confluent Community
 * License serde stack (see {@code docs/code/async-messaging.md}).
 */
public final class ConfluentWireFormat {

    /** The Confluent magic byte that prefixes every schema-registry-framed message. */
    public static final byte MAGIC_BYTE = 0x0;

    /** Size in bytes of the wire-format header (magic byte + 4-byte schema id). */
    public static final int HEADER_SIZE = 5;

    private ConfluentWireFormat() {
        // utility class
    }

    /**
     * Frame a serialized payload with the Confluent wire-format header.
     *
     * @param schemaId the schema id to embed (as registered in the Schema Registry)
     * @param payload  the already-serialized payload (e.g. Avro binary)
     * @return the framed bytes: {@code [0x00][schemaId:4][payload]}
     */
    public static byte[] encode(int schemaId, byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.put(MAGIC_BYTE);
        buffer.putInt(schemaId);
        buffer.put(payload);
        return buffer.array();
    }

    /**
     * @return true if the given bytes carry the Confluent wire-format header
     *         (at least 5 bytes, leading with the magic byte).
     */
    public static boolean isWireFormat(byte[] data) {
        return data != null && data.length >= HEADER_SIZE && data[0] == MAGIC_BYTE;
    }

    /**
     * Decode the wire-format header, returning the embedded schema id and the
     * remaining serialized payload.
     *
     * @param data the framed bytes
     * @return the decoded schema id + payload
     * @throws IllegalArgumentException if the bytes do not carry the wire-format header
     */
    public static Decoded decode(byte[] data) {
        if (!isWireFormat(data)) {
            throw new IllegalArgumentException(
                "not a Confluent wire-format message (missing magic byte or too short)");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.get(); // skip magic byte
        int schemaId = buffer.getInt();
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        return new Decoded(schemaId, payload);
    }

    /**
     * The result of decoding a wire-format message: the embedded schema id and the
     * remaining serialized payload bytes.
     */
    public static final class Decoded {
        private final int schemaId;
        private final byte[] payload;

        Decoded(int schemaId, byte[] payload) {
            this.schemaId = schemaId;
            this.payload = payload;
        }

        public int getSchemaId() {
            return schemaId;
        }

        public byte[] getPayload() {
            return payload;
        }
    }
}
