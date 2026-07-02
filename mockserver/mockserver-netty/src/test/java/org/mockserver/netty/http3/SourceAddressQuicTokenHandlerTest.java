package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertArrayEquals;

/**
 * Unit tests for {@link SourceAddressQuicTokenHandler}.
 * <p>
 * The handler's constructor calls {@code Quic.ensureAvailability()}, so these tests
 * are gated on the native QUIC transport being loadable and skip gracefully otherwise.
 */
public class SourceAddressQuicTokenHandlerTest {

    // a fixed secret so writeToken/validateToken are deterministic across the test
    private static final byte[] SECRET = new byte[]{
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    };

    private SourceAddressQuicTokenHandler handler;

    @Before
    public void setUp() {
        Assume.assumeTrue(
            "native QUIC transport not available on this platform -- skipping",
            Http3Server.isQuicAvailable());
        handler = new SourceAddressQuicTokenHandler(SECRET);
    }

    private static ByteBuf dcid(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        return Unpooled.wrappedBuffer(b);
    }

    @Test
    public void shouldWriteAndValidateTokenForSameAddress() {
        InetSocketAddress address = new InetSocketAddress("192.0.2.10", 443);
        ByteBuf originalDcid = dcid(10, 20, 30, 40, 50);
        ByteBuf token = Unpooled.buffer();
        try {
            boolean wrote = handler.writeToken(token, originalDcid, address);
            assertThat("stateless retry must be enabled (writeToken returns true)", wrote, is(true));

            int offset = handler.validateToken(token, address);
            assertThat("token should validate for the same source address", offset, greaterThan(0));

            // the bytes after the returned offset must be the original destination connection id
            byte[] recovered = new byte[token.readableBytes() - offset];
            token.getBytes(token.readerIndex() + offset, recovered);
            byte[] expectedDcid = {10, 20, 30, 40, 50};
            assertArrayEquals("recovered dcid should equal the original", expectedDcid, recovered);
        } finally {
            token.release();
            originalDcid.release();
        }
    }

    @Test
    public void shouldRejectTokenForDifferentSourceAddress() {
        InetSocketAddress mintAddress = new InetSocketAddress("192.0.2.10", 443);
        InetSocketAddress spoofedAddress = new InetSocketAddress("198.51.100.7", 443);
        ByteBuf originalDcid = dcid(1, 2, 3, 4);
        ByteBuf token = Unpooled.buffer();
        try {
            handler.writeToken(token, originalDcid, mintAddress);
            int offset = handler.validateToken(token, spoofedAddress);
            assertThat("token minted for one address must NOT validate for another", offset, is(-1));
        } finally {
            token.release();
            originalDcid.release();
        }
    }

    @Test
    public void shouldRejectForgedToken() {
        // an attacker who does not know the server secret cannot forge a valid token:
        // craft a token with a plausible layout (version + zero mac + dcid) and confirm rejection
        InetSocketAddress address = new InetSocketAddress("192.0.2.10", 443);
        ByteBuf forged = Unpooled.buffer();
        try {
            forged.writeByte(0x01);            // version
            forged.writeBytes(new byte[32]);   // all-zero HMAC (attacker guess)
            forged.writeBytes(new byte[]{9, 9, 9}); // arbitrary dcid
            int offset = handler.validateToken(forged, address);
            assertThat("a forged (unauthenticated) token must be rejected", offset, is(-1));
        } finally {
            forged.release();
        }
    }

    @Test
    public void shouldRejectTokenWithWrongVersionByte() {
        InetSocketAddress address = new InetSocketAddress("192.0.2.10", 443);
        ByteBuf originalDcid = dcid(1, 2, 3, 4);
        ByteBuf token = Unpooled.buffer();
        try {
            handler.writeToken(token, originalDcid, address);
            // corrupt the version byte
            token.setByte(token.readerIndex(), 0x7F);
            int offset = handler.validateToken(token, address);
            assertThat("a token with an unknown version must be rejected", offset, is(-1));
        } finally {
            token.release();
            originalDcid.release();
        }
    }

    @Test
    public void shouldRejectTooShortToken() {
        InetSocketAddress address = new InetSocketAddress("192.0.2.10", 443);
        ByteBuf token = Unpooled.wrappedBuffer(new byte[]{0x01, 0x02, 0x03});
        try {
            assertThat("a token shorter than version+mac must be rejected",
                handler.validateToken(token, address), is(-1));
        } finally {
            token.release();
        }
    }

    @Test
    public void shouldWriteAndValidateTokenForIpv6Address() {
        InetSocketAddress address = new InetSocketAddress("2001:db8::1", 443);
        ByteBuf originalDcid = dcid(7, 6, 5, 4, 3, 2, 1);
        ByteBuf token = Unpooled.buffer();
        try {
            handler.writeToken(token, originalDcid, address);
            int offset = handler.validateToken(token, address);
            assertThat("IPv6 token should validate for the same address", offset, greaterThan(0));

            InetSocketAddress otherV6 = new InetSocketAddress("2001:db8::2", 443);
            assertThat("IPv6 token must not validate for a different IPv6 address",
                handler.validateToken(token, otherV6), is(-1));
        } finally {
            token.release();
            originalDcid.release();
        }
    }

    @Test
    public void shouldNotThrowAndIssueNoTokenForUnresolvedAddress() {
        // an unresolved InetSocketAddress has a null InetAddress; the handler must not NPE
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("nonexistent.invalid", 443);
        ByteBuf originalDcid = dcid(1, 2, 3, 4);
        ByteBuf token = Unpooled.buffer();
        try {
            boolean wrote = handler.writeToken(token, originalDcid, unresolved);
            assertThat("no token should be written for an unresolved address", wrote, is(false));
            assertThat("nothing should have been written to the token buffer",
                token.readableBytes(), is(0));
        } finally {
            token.release();
            originalDcid.release();
        }
    }

    @Test
    public void shouldFailValidationForUnresolvedAddressWithoutThrowing() {
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("nonexistent.invalid", 443);
        ByteBuf token = Unpooled.buffer();
        try {
            token.writeByte(0x01);
            token.writeBytes(new byte[32]);
            token.writeBytes(new byte[]{9, 9, 9});
            assertThat("validation must fail (not throw) for an unresolved address",
                handler.validateToken(token, unresolved), is(-1));
        } finally {
            token.release();
        }
    }

    @Test
    public void maxTokenLengthShouldCoverVersionMacAndConnectionId() {
        // version(1) + HMAC-SHA256(32) + MAX_CONN_ID_LEN(20)
        assertThat(handler.maxTokenLength(), is(1 + 32 + 20));
    }

    @Test
    public void differentSecretsShouldProduceIncompatibleTokens() {
        byte[] otherSecret = SECRET.clone();
        otherSecret[0] = (byte) (otherSecret[0] ^ 0xFF);
        SourceAddressQuicTokenHandler otherHandler = new SourceAddressQuicTokenHandler(otherSecret);

        InetSocketAddress address = new InetSocketAddress("192.0.2.10", 443);
        ByteBuf originalDcid = dcid(1, 2, 3, 4);
        ByteBuf token = Unpooled.buffer();
        try {
            handler.writeToken(token, originalDcid, address);
            assertThat("a token minted under one secret must not validate under another",
                otherHandler.validateToken(token, address), is(-1));
        } finally {
            token.release();
            originalDcid.release();
        }
        // sanity: the two secrets really are different
        assertThat(Arrays.equals(SECRET, otherSecret), is(false));
    }
}
