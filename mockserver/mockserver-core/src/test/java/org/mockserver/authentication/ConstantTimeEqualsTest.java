package org.mockserver.authentication;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConstantTimeEqualsTest {

    @Test
    public void shouldMatchEqualStrings() {
        assertTrue(ConstantTimeEquals.equals("correct-secret", "correct-secret"));
        assertTrue(ConstantTimeEquals.equals("", ""));
    }

    @Test
    public void shouldNotMatchDifferentStrings() {
        assertFalse(ConstantTimeEquals.equals("correct-secret", "wrong-secret"));
        // same length, single byte difference
        assertFalse(ConstantTimeEquals.equals("secret", "secreT"));
        // prefix of the other (different length)
        assertFalse(ConstantTimeEquals.equals("secret", "secret-longer"));
        assertFalse(ConstantTimeEquals.equals("secret-longer", "secret"));
    }

    @Test
    public void shouldMatchUtf8Bytes() {
        assertTrue(ConstantTimeEquals.equals("naïve-Ω".getBytes(StandardCharsets.UTF_8), "naïve-Ω".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ConstantTimeEquals.equals("naïve-Ω".getBytes(StandardCharsets.UTF_8), "naive-Ω".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void shouldHandleNullStrings() {
        assertTrue(ConstantTimeEquals.equals((String) null, (String) null));
        assertFalse(ConstantTimeEquals.equals(null, "secret"));
        assertFalse(ConstantTimeEquals.equals("secret", null));
    }
}
