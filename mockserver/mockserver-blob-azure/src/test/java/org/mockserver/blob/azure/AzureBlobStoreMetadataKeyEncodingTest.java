package org.mockserver.blob.azure;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Direct round-trip tests for the Azure metadata-key escape, which previously had none: the only
 * coverage was the shared {@code BlobStoreContract} fixture, and that used ASCII keys exclusively.
 * <p>
 * These need no Azure and no Docker, so they run on every build.
 */
public class AzureBlobStoreMetadataKeyEncodingTest {

    private void assertRoundTrips(String key) {
        Map<String, String> original = new HashMap<>();
        original.put(key, "value");

        Map<String, String> encoded = AzureBlobStore.encodeMetadataKeys(original);
        Map<String, String> decoded = AzureBlobStore.decodeMetadataKeys(encoded);

        assertThat("key '" + key + "' encoded to '" + encoded.keySet() + "' did not round-trip",
            decoded.keySet(), contains(key));
        assertThat(decoded.get(key), is("value"));
    }

    @Test
    public void shouldRoundTripAsciiKeys() {
        assertRoundTrips("simple");
        assertRoundTrips("x-custom-type");
        assertRoundTrips("x_custom_type");
        assertRoundTrips("with.dots");
        assertRoundTrips("with spaces");
        assertRoundTrips("with=equals");
    }

    /**
     * The regression case. {@code %02x} widens to four hex digits above {@code 0xFF} while the
     * decoder consumed exactly two, so {@code 中文} encoded as {@code _4e2d_6587} and decoded to
     * {@code N2de87} -- a silently corrupted key.
     */
    @Test
    public void shouldRoundTripKeysAboveLatin1() {
        assertRoundTrips("中文");
        assertRoundTrips("emoji-✓");
        assertRoundTrips("mixed-中-ascii");
        assertRoundTrips("Ω");
    }

    /**
     * Latin-1 characters sit exactly on the old two-digit boundary.
     */
    @Test
    public void shouldRoundTripLatin1Keys() {
        assertRoundTrips("café");
        assertRoundTrips("naïve");
        assertRoundTrips("ÿ");
    }

    /**
     * Characters outside the BMP are two UTF-16 code units; each is escaped separately and the
     * pair must recombine on decode.
     */
    @Test
    public void shouldRoundTripNonBmpKeys() {
        assertRoundTrips("emoji-😀");
        assertRoundTrips("😀");
    }

    /**
     * An Azure metadata key must begin with a letter or an underscore, never a digit.
     */
    @Test
    public void shouldNotProduceKeysStartingWithADigit() {
        for (String key : new String[]{"1abc", "9", "0-leading", "中文", "-dash"}) {
            Map<String, String> original = new HashMap<>();
            original.put(key, "value");

            String encoded = AzureBlobStore.encodeMetadataKeys(original).keySet().iterator().next();

            assertThat("encoded key '" + encoded + "' for '" + key + "' must not start with a digit",
                Character.isDigit(encoded.charAt(0)), is(false));
            assertRoundTrips(key);
        }
    }

    /**
     * Distinct keys must never collide once encoded -- a collision silently drops one entry.
     */
    @Test
    public void shouldNotCollideDistinctKeys() {
        Map<String, String> original = new HashMap<>();
        original.put("x-custom-type", "hyphen");
        original.put("x_custom_type", "underscore");
        original.put("x.custom.type", "dot");
        original.put("中文", "chinese");
        original.put("1abc", "leading-digit");

        Map<String, String> encoded = AzureBlobStore.encodeMetadataKeys(original);
        assertThat("distinct keys must encode to distinct Azure keys",
            encoded.size(), is(original.size()));

        Map<String, String> decoded = AzureBlobStore.decodeMetadataKeys(encoded);
        assertThat(decoded, is(original));
    }

    /**
     * A stored key that is not a well-formed escape must pass through rather than being mangled
     * or throwing -- {@code parseInt} accepts a leading sign, so this is checked explicitly.
     */
    @Test
    public void shouldPassThroughMalformedEscapesLiterally() {
        Map<String, String> stored = new HashMap<>();
        stored.put("_zz", "short-non-hex");
        stored.put("_", "bare-underscore");
        stored.put("trailing_", "trailing-underscore");

        Map<String, String> decoded = AzureBlobStore.decodeMetadataKeys(stored);

        assertThat(decoded.get("_zz"), is("short-non-hex"));
        assertThat(decoded.get("_"), is("bare-underscore"));
        assertThat(decoded.get("trailing_"), is("trailing-underscore"));
    }

    /**
     * Pins the exact wire format, so a future width change is a deliberate, visible edit rather
     * than a silent one.
     */
    @Test
    public void shouldEncodeUsingFixedFourDigitHexEscapes() {
        Map<String, String> original = new HashMap<>();
        original.put("a-b", "value");

        assertThat(AzureBlobStore.encodeMetadataKeys(original).keySet(), contains("a_002db"));
    }
}
