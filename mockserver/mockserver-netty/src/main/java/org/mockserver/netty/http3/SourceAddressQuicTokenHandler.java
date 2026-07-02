package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicTokenHandler;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Source-address-validating QUIC retry token handler (stateless retry, RFC 9000 §8.1).
 * <p>
 * This replaces Netty's {@link io.netty.handler.codec.quic.InsecureQuicTokenHandler}.
 * The insecure handler writes the client address into the token in <em>plaintext</em>
 * with no cryptographic protection, so an attacker can trivially forge a token that
 * embeds a spoofed source address and pass validation — defeating the retry mechanism
 * and re-enabling QUIC address-spoofing / traffic-amplification attacks (a forged-source
 * Initial packet can elicit a large response up to {@code initialMaxData}).
 * <p>
 * This handler instead binds the client's {@link InetSocketAddress} into a keyed HMAC
 * (HMAC-SHA256 under a per-server random secret). A retry token is therefore:
 * <ul>
 *   <li><strong>source-address bound</strong>: the address bytes are part of the HMAC
 *       input, so a token minted for address A does not validate for address B;</li>
 *   <li><strong>unforgeable</strong>: without the server secret an attacker cannot
 *       produce a token that validates for any address, so the only way to obtain a
 *       valid token is to complete a Retry round-trip — which requires actually
 *       receiving the Retry packet at the claimed source address.</li>
 * </ul>
 * The secret is generated once per {@link Http3Server#start(int)} and lives for the
 * lifetime of the server, so tokens issued in a Retry remain valid for the immediately
 * following handshake. Because only IP address bytes are hashed (not the ephemeral
 * secret's persistence across restarts), the handler is correct for both IPv4 (4-byte)
 * and IPv6 (16-byte) peers.
 * <p>
 * Token layout: {@code [VERSION(1)] [HMAC-SHA256(32)] [original-dcid(0..MAX_CONN_ID_LEN)]}.
 * {@link #validateToken(ByteBuf, InetSocketAddress)} returns the offset at which the
 * original destination connection id begins, exactly as Netty's built-in handlers do.
 */
public final class SourceAddressQuicTokenHandler implements QuicTokenHandler {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAC_LENGTH = 32; // HMAC-SHA256 output length
    private static final byte VERSION = 0x01;
    private static final int DCID_OFFSET = 1 + MAC_LENGTH; // version byte + mac
    private static final int SECRET_LENGTH = 32;

    private final byte[] secret;
    private final ThreadLocal<Mac> macThreadLocal;

    /**
     * Create a handler with a fresh random secret.
     */
    public SourceAddressQuicTokenHandler() {
        this(newSecret());
    }

    /**
     * Package-private constructor allowing a fixed secret for deterministic tests.
     */
    SourceAddressQuicTokenHandler(byte[] secret) {
        Quic.ensureAvailability();
        this.secret = secret.clone();
        this.macThreadLocal = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(new SecretKeySpec(this.secret, HMAC_ALGORITHM));
                return mac;
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException("Unable to initialise HMAC for QUIC source-address token handler", e);
            }
        });
    }

    private static byte[] newSecret() {
        byte[] s = new byte[SECRET_LENGTH];
        new SecureRandom().nextBytes(s);
        return s;
    }

    @Override
    public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        byte[] addressBytes = addressBytes(address);
        if (addressBytes == null) {
            // unresolved / malformed peer address -- issue no token rather than NPE.
            // Returning false disables retry-token validation for this packet only.
            return false;
        }
        byte[] mac = computeMac(addressBytes, dcid);
        out.writeByte(VERSION);
        out.writeBytes(mac);
        // append the original destination connection id so validateToken can recover it
        out.writeBytes(dcid, dcid.readerIndex(), dcid.readableBytes());
        return true;
    }

    @Override
    public int validateToken(ByteBuf token, InetSocketAddress address) {
        byte[] addressBytes = addressBytes(address);
        if (addressBytes == null) {
            // unresolved / malformed peer address -- fail validation rather than NPE
            return -1;
        }
        final int readable = token.readableBytes();
        if (readable < DCID_OFFSET) {
            return -1;
        }
        if (token.getByte(token.readerIndex()) != VERSION) {
            return -1;
        }
        final int dcidLen = readable - DCID_OFFSET;
        if (dcidLen < 0 || dcidLen > Quic.MAX_CONN_ID_LEN) {
            return -1;
        }
        // recompute the HMAC over (client address, original-dcid-from-token) and
        // compare, in constant time, against the HMAC presented in the token
        ByteBuf dcid = token.slice(token.readerIndex() + DCID_OFFSET, dcidLen);
        byte[] expected = computeMac(addressBytes, dcid);
        byte[] presented = new byte[MAC_LENGTH];
        token.getBytes(token.readerIndex() + 1, presented);
        if (!MessageDigest.isEqual(expected, presented)) {
            return -1;
        }
        return DCID_OFFSET;
    }

    @Override
    public int maxTokenLength() {
        return DCID_OFFSET + Quic.MAX_CONN_ID_LEN;
    }

    /**
     * Extract the raw IP address bytes (4 for IPv4, 16 for IPv6) of the peer, or
     * {@code null} if the address is missing or unresolved -- so a malformed peer
     * address cannot throw a {@link NullPointerException} out of Netty's token path.
     */
    private static byte[] addressBytes(InetSocketAddress address) {
        if (address == null || address.isUnresolved()) {
            return null;
        }
        InetAddress inetAddress = address.getAddress();
        return inetAddress == null ? null : inetAddress.getAddress();
    }

    private byte[] computeMac(byte[] addressBytes, ByteBuf dcid) {
        Mac mac = macThreadLocal.get();
        mac.reset();
        // bind the source IP address bytes (4 for IPv4, 16 for IPv6)
        mac.update(addressBytes);
        // bind the destination connection id without disturbing reader/writer indices
        int idx = dcid.readerIndex();
        int len = dcid.readableBytes();
        if (dcid.hasArray()) {
            mac.update(dcid.array(), dcid.arrayOffset() + idx, len);
        } else {
            byte[] tmp = new byte[len];
            dcid.getBytes(idx, tmp);
            mac.update(tmp);
        }
        return mac.doFinal();
    }
}
