package org.mockserver.grpc;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.grpc.GrpcStatusMapper.percentDecodeMessage;
import static org.mockserver.grpc.GrpcStatusMapper.percentEncodeMessage;

/**
 * The {@code grpc-message} percent-encoding required by the gRPC wire specification.
 * <p>
 * MockServer previously wrote the raw string at every emission site, which corrupts ordinary input
 * — not just exotic characters — because clients percent-DECODE on receipt.
 */
public class GrpcPercentEncodingTest {

    /**
     * The case that breaks with entirely ordinary input: a literal {@code %} in the message.
     * Unencoded, the client decodes {@code "50% e"} → {@code %20} → a space, silently corrupting
     * the text.
     */
    @Test
    public void shouldEscapePercentSoPlainAsciiSurvivesTheClientsDecode() {
        String message = "quota 50% exceeded";
        assertThat(percentEncodeMessage(message), is("quota 50%25 exceeded"));
        assertThat("must survive the client's decode unchanged",
            percentDecodeMessage(percentEncodeMessage(message)), is(message));
    }

    /**
     * Non-ASCII is escaped over its UTF-8 bytes. Unencoded it is lost twice over: byte-cast to
     * ISO-8859-1 on HTTP/1.1 and HTTP/2, and replaced by a literal '?' in the US_ASCII gRPC-Web
     * trailer frame.
     */
    @Test
    public void shouldEscapeNonAsciiOverUtf8Bytes() {
        assertThat(percentEncodeMessage("paiement refusé"), is("paiement refus%C3%A9"));
        assertThat(percentDecodeMessage("paiement refus%C3%A9"), is("paiement refusé"));
    }

    @Test
    public void shouldRoundTripMultiByteAndEmoji() {
        for (String message : new String[]{"日本語のメッセージ", "quota ⚠ exceeded", "emoji 🚀 here"}) {
            assertThat(percentDecodeMessage(percentEncodeMessage(message)), is(message));
        }
    }

    /**
     * CR and LF are escaped, which is what closes the gRPC-Web trailer-frame injection: the frame
     * is a CRLF-delimited block, so an unescaped CRLF would inject a second grpc-status line.
     */
    @Test
    public void shouldEscapeCarriageReturnAndLineFeed() {
        String injection = "denied\r\ngrpc-status: 0";
        String encoded = percentEncodeMessage(injection);
        assertThat(encoded, is("denied%0D%0Agrpc-status: 0"));
        assertThat("no raw CR may survive", encoded.indexOf('\r'), is(-1));
        assertThat("no raw LF may survive", encoded.indexOf('\n'), is(-1));
        assertThat(percentDecodeMessage(encoded), is(injection));
    }

    @Test
    public void shouldLeaveCleanAsciiUntouched() {
        String message = "no such greeting";
        assertThat(percentEncodeMessage(message), is(message));
    }

    @Test
    public void shouldHandleNullAndEmpty() {
        assertThat(percentEncodeMessage(null), is((String) null));
        assertThat(percentDecodeMessage(null), is((String) null));
        assertThat(percentEncodeMessage(""), is(""));
    }

    /**
     * Lenient decoding, matching grpc-java: a stray {@code %} that is not a valid escape is passed
     * through rather than treated as an error, so a server that emits an unencoded message (as
     * MockServer itself did) is not made worse.
     */
    @Test
    public void shouldDecodeLenientlyWhenEscapeIsInvalid() {
        assertThat(percentDecodeMessage("100% done"), is("100% done"));
        assertThat(percentDecodeMessage("trailing %"), is("trailing %"));
        assertThat(percentDecodeMessage("bad %ZZ escape"), is("bad %ZZ escape"));
    }
}
