package org.mockserver.async.serde;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

/**
 * Unit tests for {@link ConfluentWireFormat} — the magic-byte + schema-id framing.
 */
public class ConfluentWireFormatTest {

    @Test
    public void shouldEncodeMagicByteAndSchemaId() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] framed = ConfluentWireFormat.encode(42, payload);

        assertThat(framed.length, is(ConfluentWireFormat.HEADER_SIZE + payload.length));
        assertThat(framed[0], is(ConfluentWireFormat.MAGIC_BYTE));
        // big-endian schema id 42 = 0x0000002A
        assertThat(framed[1], is((byte) 0x00));
        assertThat(framed[2], is((byte) 0x00));
        assertThat(framed[3], is((byte) 0x00));
        assertThat(framed[4], is((byte) 0x2A));
    }

    @Test
    public void shouldRoundTripEncodeDecode() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] framed = ConfluentWireFormat.encode(100001, payload);

        ConfluentWireFormat.Decoded decoded = ConfluentWireFormat.decode(framed);
        assertThat(decoded.getSchemaId(), is(100001));
        assertThat(decoded.getPayload(), is(payload));
    }

    @Test
    public void shouldDetectWireFormat() {
        byte[] framed = ConfluentWireFormat.encode(1, new byte[]{0x09});
        assertThat(ConfluentWireFormat.isWireFormat(framed), is(true));
    }

    @Test
    public void shouldRejectNonWireFormatBytes() {
        assertThat(ConfluentWireFormat.isWireFormat(null), is(false));
        assertThat(ConfluentWireFormat.isWireFormat(new byte[0]), is(false));
        // too short (< 5 bytes)
        assertThat(ConfluentWireFormat.isWireFormat(new byte[]{0x00, 0x00}), is(false));
        // wrong leading byte
        assertThat(ConfluentWireFormat.isWireFormat(new byte[]{0x01, 0x00, 0x00, 0x00, 0x01}), is(false));
    }

    @Test
    public void shouldDecodeEmptyPayload() {
        byte[] framed = ConfluentWireFormat.encode(7, new byte[0]);
        ConfluentWireFormat.Decoded decoded = ConfluentWireFormat.decode(framed);
        assertThat(decoded.getSchemaId(), is(7));
        assertThat(decoded.getPayload().length, is(0));
    }

    @Test
    public void shouldThrowDecodingNonWireFormat() {
        assertThrows(IllegalArgumentException.class,
            () -> ConfluentWireFormat.decode(new byte[]{0x01, 0x02, 0x03}));
    }

    @Test
    public void shouldThrowEncodingNullPayload() {
        assertThrows(IllegalArgumentException.class, () -> ConfluentWireFormat.encode(1, null));
    }
}
