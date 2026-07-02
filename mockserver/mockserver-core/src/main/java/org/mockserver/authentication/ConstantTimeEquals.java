package org.mockserver.authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared constant-time equality checks for authentication credentials.
 *
 * <p>Comparisons delegate to {@link MessageDigest#isEqual(byte[], byte[])}, which is documented to
 * be constant-time and to not short-circuit on content, so an attacker cannot recover a secret one
 * byte at a time by measuring how long a comparison takes. {@code isEqual} does return {@code false}
 * fast when the two arrays differ in length, which leaks only the length of the <em>supplied</em>
 * credential — never any byte of the configured secret.
 *
 * <p>Both the control-plane {@code CONNECT}/SOCKS5 proxy authentication and the data-plane
 * authenticator route their credential comparisons through here so there is a single, audited
 * constant-time implementation rather than duplicated {@code String.equals} / {@code isEqual} calls.
 */
public final class ConstantTimeEquals {

    private ConstantTimeEquals() {
    }

    /**
     * Constant-time comparison of two strings by their UTF-8 bytes. A {@code null} is only ever equal
     * to another {@code null}; a {@code null} and a non-{@code null} are never equal.
     */
    public static boolean equals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return equals(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Constant-time comparison of two byte arrays via {@link MessageDigest#isEqual(byte[], byte[])}.
     */
    public static boolean equals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
