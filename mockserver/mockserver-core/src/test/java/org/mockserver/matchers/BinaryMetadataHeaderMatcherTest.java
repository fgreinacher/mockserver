package org.mockserver.matchers;

import com.google.common.io.BaseEncoding;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.KeyMatchStyle;
import org.mockserver.model.Parameter;
import org.mockserver.model.Parameters;

import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;

/**
 * gRPC binary metadata ({@code -bin}) header matching must be insensitive to base64 padding.
 * <p>
 * <strong>Why this is a defect and not a preference.</strong> MockServer's contract for a
 * {@code -bin} metadata value is that the user supplies the value <em>already base64-encoded</em>
 * and MockServer passes it through untouched. But grpc-java writes outbound binary metadata with
 * {@code BASE64_ENCODING_OMIT_PADDING} ({@code io.grpc.internal.TransportFrameUtil#toHttp2Headers}),
 * so what actually arrives on the wire is {@code AQIDBA} — never {@code AQIDBA==}. A user who writes
 * the padded form that {@code Base64.getEncoder()} produces would silently never match a real gRPC
 * client, with no error and no diagnostic. Both spellings decode to the same bytes, so the matcher
 * treats them as the same value.
 * <p>
 * The end-to-end proof against a real grpc-java client lives in
 * {@code org.mockserver.netty.grpc.GrpcUnaryClientIntegrationTest}; this class pins the matcher
 * semantics and, critically, the <em>blast radius</em> — that nothing outside {@code -bin} header
 * values changed.
 */
public class BinaryMetadataHeaderMatcherTest {

    private static final byte[] TRACE_BYTES = {1, 2, 3, 4};
    /** {@code AQIDBA==} — what {@code Base64.getEncoder()} and every "encode this" snippet produce. */
    private static final String PADDED = Base64.getEncoder().encodeToString(TRACE_BYTES);
    /** {@code AQIDBA} — what grpc-java actually puts on the wire. */
    private static final String UNPADDED = BaseEncoding.base64().omitPadding().encode(TRACE_BYTES);

    private static boolean matches(Headers matcher, Headers matched) {
        return new MultiValueMapMatcher(new MockServerLogger(), matcher, false).matches(null, matched);
    }

    // ---- the framing assumptions this whole change rests on, asserted rather than assumed ----

    /**
     * The padded and unpadded spellings really are different strings that carry the same bytes —
     * i.e. there is a genuine matching defect to fix, not a naming difference.
     */
    @Test
    public void shouldConfirmPaddedAndUnpaddedBase64AreDifferentStringsForTheSameBytes() {
        assertThat("grpc-java's unpadded wire form must differ from the padded form", PADDED.equals(UNPADDED), is(false));
        assertThat(PADDED, is("AQIDBA=="));
        assertThat(UNPADDED, is("AQIDBA"));
    }

    /**
     * The return leg. grpc-java decodes an inbound {@code -bin} value with
     * {@code BaseEncoding.base64().decode(..)} (see {@code TransportFrameUtil#toRawSerializedHeaders}),
     * so whatever MockServer passes through as a response header must survive that decode.
     * <p>
     * That Guava's strict {@code base64()} decoder tolerates <em>missing</em> padding is the single
     * assumption that makes pass-through safe in the response direction, and it is the one thing the
     * original investigation inferred rather than executed. It is executed here: if a future Guava
     * upgrade tightened this, pass-through would start failing real clients and this test goes red
     * first.
     */
    @Test
    public void shouldConfirmGuavaBase64DecoderAcceptsBothPaddedAndUnpaddedInput() {
        assertThat(BaseEncoding.base64().decode(PADDED), is(TRACE_BYTES));
        assertThat(BaseEncoding.base64().decode(UNPADDED), is(TRACE_BYTES));

        // every remainder class, because only lengths 1 and 2 mod 3 are padded at all
        for (int length = 1; length <= 5; length++) {
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) (i + 1);
            }
            assertThat(BaseEncoding.base64().decode(BaseEncoding.base64().encode(bytes)), is(bytes));
            assertThat(BaseEncoding.base64().decode(BaseEncoding.base64().omitPadding().encode(bytes)), is(bytes));
        }
    }

    // ---- the defect ----

    /**
     * The headline case: a padded expectation against the unpadded value a real gRPC client sends.
     * This failed before the fix.
     */
    @Test
    public void shouldMatchPaddedExpectationAgainstUnpaddedWireValue() {
        assertThat(matches(
            new Headers().withEntries(new Header("x-trace-bin", PADDED)),
            new Headers().withEntries(new Header("x-trace-bin", UNPADDED))
        ), is(true));
    }

    /**
     * The mirror case: an unpadded expectation against a padded actual value, as sent by a client
     * that does pad (gRPC-Web implementations, hand-rolled clients).
     */
    @Test
    public void shouldMatchUnpaddedExpectationAgainstPaddedWireValue() {
        assertThat(matches(
            new Headers().withEntries(new Header("x-trace-bin", UNPADDED)),
            new Headers().withEntries(new Header("x-trace-bin", PADDED))
        ), is(true));
    }

    /**
     * Both spelt the same way must keep matching — the normalisation is symmetric, so it cannot
     * break the case that already worked.
     */
    @Test
    public void shouldStillMatchWhenBothSidesUseTheSameSpelling() {
        assertThat(matches(
            new Headers().withEntries(new Header("x-trace-bin", PADDED)),
            new Headers().withEntries(new Header("x-trace-bin", PADDED))
        ), is(true));
        assertThat(matches(
            new Headers().withEntries(new Header("x-trace-bin", UNPADDED)),
            new Headers().withEntries(new Header("x-trace-bin", UNPADDED))
        ), is(true));
    }

    /**
     * HTTP/2 lower-cases header names on the wire, but an expectation may be written in any case,
     * so the {@code -bin} suffix test is case-insensitive.
     */
    @Test
    public void shouldMatchRegardlessOfHeaderNameCase() {
        assertThat(matches(
            new Headers().withEntries(new Header("X-Trace-BIN", PADDED)),
            new Headers().withEntries(new Header("x-trace-bin", UNPADDED))
        ), is(true));
    }

    /**
     * Padding-insensitivity must not become value-insensitivity: a different payload still fails.
     */
    @Test
    public void shouldNotMatchADifferentBinaryValue() {
        assertThat(matches(
            new Headers().withEntries(new Header("x-trace-bin", PADDED)),
            new Headers().withEntries(new Header("x-trace-bin", BaseEncoding.base64().omitPadding().encode(new byte[]{9, 9, 9, 9})))
        ), is(false));
    }

    /**
     * A regex matcher key such as {@code x-.*} never ends {@code -bin}, so normalisation is decided
     * from <em>either</em> key. Deciding it from the matcher key alone would leave the two sides
     * normalised asymmetrically and would have <em>broken</em> a padded-vs-padded match that works
     * today.
     */
    @Test
    public void shouldNormalizeWhenOnlyTheRequestSideKeyIdentifiesAsBinary() {
        assertThat(matches(
            new Headers().withEntries(new Header("x-.*", PADDED)),
            new Headers().withEntries(new Header("x-trace-bin", UNPADDED))
        ), is(true));
        // the regression guard: this matched before the change and must still match
        assertThat(matches(
            new Headers().withEntries(new Header("x-.*", PADDED)),
            new Headers().withEntries(new Header("x-trace-bin", PADDED))
        ), is(true));
    }

    // ---- blast radius ----

    /**
     * A header whose name does not end {@code -bin} keeps byte-exact semantics, so an ordinary HTTP
     * header that happens to end in {@code =} is unaffected.
     */
    @Test
    public void shouldNotNormalizePaddingForANonBinaryHeader() {
        assertThat(matches(
            new Headers().withEntries(new Header("x-trace", PADDED)),
            new Headers().withEntries(new Header("x-trace", UNPADDED))
        ), is(false));
    }

    /**
     * Query-string parameters are not gRPC metadata, so a parameter named {@code *-bin} is not
     * normalised — the change is scoped to header maps.
     */
    @Test
    public void shouldNotNormalizePaddingForQueryStringParameters() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("x-trace-bin", PADDED)
        ), false).matches(
            null,
            new Parameters().withEntries(new Parameter("x-trace-bin", UNPADDED))
        ), is(false));
    }

    /**
     * A value that merely contains or ends with {@code =} but is not structurally valid base64 is
     * left exactly as written.
     */
    @Test
    public void shouldOnlyStripPaddingFromWellFormedBase64() {
        assertThat("a value with three padding chars is not valid base64",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "AQIDBA===")),
                new Headers().withEntries(new Header("x-trace-bin", "AQIDBA"))
            ), is(false));
        assertThat("a value with an interior '=' is not base64",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "a=b=")),
                new Headers().withEntries(new Header("x-trace-bin", "a=b"))
            ), is(false));
        assertThat("the URL-safe alphabet is not the alphabet grpc-java decodes, so it is not stripped",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "some-value=")),
                new Headers().withEntries(new Header("x-trace-bin", "some-value"))
            ), is(false));
        assertThat("a value that is only padding is not base64",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "==")),
                new Headers().withEntries(new Header("x-trace-bin", ""))
            ), is(false));
    }

    /**
     * <strong>The length invariant.</strong> Every character of {@code AQID==} is in the base64
     * alphabet and it ends in exactly two {@code =}, yet its total length is 6 — real base64 is
     * always a multiple of 4, so it is not a padded encoding of anything and must not be rewritten.
     * An alphabet-and-padding-count check alone accepts it.
     */
    @Test
    public void shouldNotStripPaddingFromAlphabetValidValueOfInvalidLength() {
        assertThat("'AQID==' is 6 characters — not a valid padded base64 length",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "AQID==")),
                new Headers().withEntries(new Header("x-trace-bin", "AQID"))
            ), is(false));
        assertThat("'a=' is 2 characters — not a valid padded base64 length",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "a=")),
                new Headers().withEntries(new Header("x-trace-bin", "a"))
            ), is(false));
        assertThat("every legitimate padded value is still stripped",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "AQIDBAU=")),
                new Headers().withEntries(new Header("x-trace-bin", "AQIDBAU"))
            ), is(true));
    }

    /**
     * <strong>The regex-broadening case the length invariant exists to prevent.</strong> {@code +}
     * is both a base64 alphabet character and a regex metacharacter. Stripping the padding from
     * {@code A+=} yields the regex {@code A+}, which matches {@code A}, {@code AAAA},
     * {@code AAAAAAAA} — an expectation silently widened from one literal value to a whole family.
     * That is a correctness defect in the header matching path, so it gets its own test.
     */
    @Test
    public void shouldNotBroadenARegexMatcherByStrippingItsTrailingPadding() {
        assertThat("'A+=' must not be rewritten to the regex 'A+' and start matching 'AAAA'",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "A+=")),
                new Headers().withEntries(new Header("x-trace-bin", "AAAA"))
            ), is(false));
        assertThat("nor 'A'",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "A+=")),
                new Headers().withEntries(new Header("x-trace-bin", "A"))
            ), is(false));
        assertThat("'A+=' still matches itself",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "A+=")),
                new Headers().withEntries(new Header("x-trace-bin", "A+="))
            ), is(true));
        assertThat("a deliberate regex on a -bin header keeps working",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "AQ.*")),
                new Headers().withEntries(new Header("x-trace-bin", UNPADDED))
            ), is(true));
    }

    /**
     * <strong>A structurally valid padded value containing {@code +} still behaves as a regex once
     * unpadded — deliberately, and not a regression.</strong> {@code AA+=} is length 4 and all
     * alphabet, so it is real base64 and does normalise to {@code AA+}, which then regex-matches
     * {@code AAAA}.
     * <p>
     * That is not something this change introduced: MockServer regex-interprets <em>every</em>
     * header value, so the unpadded spelling {@code AQIDB+} already matches {@code AQIDBB} on
     * untouched code. The contract here is precisely "the padded spelling behaves like the unpadded
     * spelling of the same bytes", and this is that contract holding rather than leaking. Pinned so
     * the behaviour is visible and intentional rather than latent, and so that anyone later making
     * header matching literal has a test telling them what changes.
     */
    @Test
    public void shouldLeaveAValidPaddedValueBehavingAsARegexOnceUnpadded() {
        assertThat("'AA+=' is valid base64, normalises to 'AA+', and regex-matches 'AAAA'",
            matches(
                new Headers().withEntries(new Header("x-trace-bin", "AA+=")),
                new Headers().withEntries(new Header("x-trace-bin", "AAAA"))
            ), is(true));
        assertThat("the same is already true of the unpadded spelling on untouched behaviour",
            matches(
                new Headers().withEntries(new Header("x-other", "AA+")),
                new Headers().withEntries(new Header("x-other", "AAAA"))
            ), is(true));
    }

    /**
     * A notted value keeps asserting inequality after normalisation, rather than becoming a
     * vacuously-true "these differ in padding" match.
     */
    @Test
    public void shouldHonourNottedValuesAfterNormalization() {
        assertThat("!AQIDBA== must not match the same bytes spelt unpadded",
            new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
                new Header(string("x-trace-bin"), not(PADDED))
            ), false).matches(
                null,
                new Headers().withEntries(new Header("x-trace-bin", UNPADDED))
            ), is(false));

        assertThat("!AQIDBA== must still match genuinely different bytes",
            new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
                new Header(string("x-trace-bin"), not(PADDED))
            ), false).matches(
                null,
                new Headers().withEntries(new Header("x-trace-bin", "CQkJCQ"))
            ), is(true));
    }

    /**
     * MATCHING_KEY resolves values per key rather than as a flat entry set, so it needs its own
     * normalisation; without it the two key-match styles would disagree about the same metadata.
     */
    @Test
    public void shouldNormalizeUnderMatchingKeyKeyMatchStyle() {
        Headers matcher = new Headers().withEntries(new Header("x-trace-bin", PADDED));
        matcher.withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
        Headers matched = new Headers().withEntries(new Header("x-trace-bin", UNPADDED));
        matched.withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
        assertThat(matches(matcher, matched), is(true));
    }

    /**
     * The two key-match styles must agree, including for the regex-matcher-key case — which is the
     * one that needs the <em>actual</em> matched key, not the pattern that selected it. Resolving
     * the binary-ness from the matcher key alone silently degrades MATCHING_KEY to a non-match here
     * while SUB_SET matches, so the same expectation would behave differently depending only on the
     * key-match style.
     */
    @Test
    public void shouldNormalizeUnderMatchingKeyEvenWhenOnlyTheRequestSideKeyIdentifiesAsBinary() {
        Headers matcher = new Headers().withEntries(new Header("x-.*", PADDED));
        matcher.withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
        Headers matched = new Headers().withEntries(new Header("x-trace-bin", UNPADDED));
        matched.withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);

        assertThat("MATCHING_KEY must agree with SUB_SET for a regex matcher key",
            matches(matcher, matched), is(true));
        assertThat("SUB_SET, for comparison",
            matches(
                new Headers().withEntries(new Header("x-.*", PADDED)),
                new Headers().withEntries(new Header("x-trace-bin", UNPADDED))
            ), is(true));
    }

    /**
     * A header named exactly {@code -bin} is a binary key to {@code io.grpc.Metadata.Key}, which
     * tests the suffix without requiring a prefix. A name that merely ends in those letters without
     * the hyphen ({@code x-cabin}) is not, and must keep exact matching.
     */
    @Test
    public void shouldTreatExactlyBinAsBinaryButNotCabin() {
        assertThat("a header named exactly '-bin' is a binary key",
            matches(
                new Headers().withEntries(new Header("-bin", PADDED)),
                new Headers().withEntries(new Header("-bin", UNPADDED))
            ), is(true));
        assertThat("'x-cabin' is not a binary key",
            matches(
                new Headers().withEntries(new Header("x-cabin", PADDED)),
                new Headers().withEntries(new Header("x-cabin", UNPADDED))
            ), is(false));
    }
}
