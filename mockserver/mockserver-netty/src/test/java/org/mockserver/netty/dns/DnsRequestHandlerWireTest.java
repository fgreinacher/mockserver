package org.mockserver.netty.dns;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.buffer.Unpooled;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.dns.DnsIntentRegistry;
import org.mockserver.model.DnsRecord;
import org.mockserver.model.DnsRequestDefinition;
import org.mockserver.model.DnsResponse;
import org.mockserver.scheduler.Scheduler;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Wire-level conformance tests for the DNS mock.
 * <p>
 * These deliberately drive the handler through the same {@code DatagramDnsResponseEncoder} the
 * production pipeline installs (see {@code MockServer#bindDnsPort}) and then parse the resulting
 * octets with <b>dnsjava</b> — an independent resolver implementation. Asserting against
 * MockServer's own model objects, or against Netty's decoder, cannot detect an encoder that emits
 * well-formed-looking but non-conformant bytes; that blind spot is precisely what let the defects
 * these tests cover survive. Every assertion here is on bytes a real resolver would receive.
 */
public class DnsRequestHandlerWireTest {

    private HttpState httpState;
    private EmbeddedChannel channel;
    private int queryId = 1;

    @Before
    public void setUp() {
        DnsIntentRegistry.getInstance().clear();
        Configuration configuration = configuration();
        MockServerLogger logger = new MockServerLogger(configuration, DnsRequestHandlerWireTest.class);
        Scheduler scheduler = new Scheduler(configuration, logger);
        httpState = new HttpState(configuration, logger, scheduler);
        DnsRequestHandler handler = new DnsRequestHandler(logger, httpState);
        // decoder -> encoder -> handler, matching MockServer#bindDnsPort. The decoder is omitted
        // because queries are written inbound as already-decoded DatagramDnsQuery objects.
        channel = new EmbeddedChannel(new DatagramDnsResponseEncoder(), handler);
    }

    @After
    public void tearDown() {
        DnsIntentRegistry.getInstance().clear();
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void expectDnsResponse(String name, DnsResponse dnsResponse) {
        httpState.add(new Expectation(
            DnsRequestDefinition.dnsRequest().withDnsName(name)
        ).thenRespondWithDns(dnsResponse));
    }

    private Message query(String name, int type) throws IOException {
        return query(name, type, true, 0);
    }

    /**
     * Writes a query through the handler and parses the emitted datagram with dnsjava.
     *
     * @param ednsPayloadSize when &gt; 0, an EDNS(0) OPT record advertising this payload size
     */
    private Message query(String name, int type, boolean recursionDesired, int ednsPayloadSize) throws IOException {
        DatagramDnsQuery dnsQuery = new DatagramDnsQuery(
            new InetSocketAddress("127.0.0.1", 12345),
            new InetSocketAddress("127.0.0.1", 53),
            queryId++
        );
        dnsQuery.setRecursionDesired(recursionDesired);
        dnsQuery.addRecord(DnsSection.QUESTION,
            new DefaultDnsQuestion(name, io.netty.handler.codec.dns.DnsRecordType.valueOf(type)));
        if (ednsPayloadSize > 0) {
            dnsQuery.addRecord(DnsSection.ADDITIONAL, new DefaultDnsRawRecord(
                ".", io.netty.handler.codec.dns.DnsRecordType.OPT, ednsPayloadSize, 0, Unpooled.EMPTY_BUFFER));
        }

        channel.writeInbound(dnsQuery);

        DatagramPacket packet = channel.readOutbound();
        assertNotNull("handler emitted no response datagram", packet);
        try {
            byte[] bytes = new byte[packet.content().readableBytes()];
            packet.content().getBytes(0, bytes);
            // dnsjava parses the octets exactly as a real resolver would.
            return new Message(bytes);
        } finally {
            packet.release();
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    // ------------------------------------------------- baseline: valid records

    @Test
    public void shouldEmitAnARecordARealResolverCanParse() throws Exception {
        expectDnsResponse("api.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aRecord("api.example.com.", "10.0.0.1")));

        Message message = query("api.example.com.", 1);

        assertThat(message.getHeader().getRcode(), is(Rcode.NOERROR));
        List<org.xbill.DNS.Record> answers = message.getSection(Section.ANSWER);
        assertThat(answers.size(), is(1));
        ARecord answer = (ARecord) answers.get(0);
        assertThat(answer.getName().toString(), is("api.example.com."));
        assertThat(answer.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(answer.getTTL(), is(300L));
    }

    @Test
    public void shouldEmitACnameRecordARealResolverCanParse() throws Exception {
        expectDnsResponse("alias.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.cnameRecord("alias.example.com.", "real.example.com.")));

        Message message = query("alias.example.com.", 5);

        CNAMERecord answer = (CNAMERecord) message.getSection(Section.ANSWER).get(0);
        assertThat(answer.getTarget().toString(), is("real.example.com."));
    }

    // ------------------------------------- defect 1: compression-pointer corruption

    /**
     * A label of 192+ octets writes a length octet of 0xC0+, which RFC 1035 §4.1.4 defines as a
     * compression pointer — the resolver then reads the next 14 bits as a message offset and
     * misparses everything after it. Before the bounds check, the handler emitted such a packet
     * with rcode NOERROR and dnsjava misparsed or rejected it.
     */
    @Test
    public void shouldReturnServfailRatherThanEmitALabelLongEnoughToLookLikeACompressionPointer() throws Exception {
        expectDnsResponse("alias.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.cnameRecord("alias.example.com.", repeat('a', 200) + ".example.com.")));

        Message message = query("alias.example.com.", 5);

        assertThat(message.getHeader().getRcode(), is(Rcode.SERVFAIL));
        assertThat(message.getSection(Section.ANSWER).size(), is(0));
    }

    /** 64 octets is the first length that violates RFC 1035 §2.3.4, well below the 0xC0 boundary. */
    @Test
    public void shouldReturnServfailForALabelJustOverTheSixtyThreeOctetLimit() throws Exception {
        expectDnsResponse("alias.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.cnameRecord("alias.example.com.", repeat('a', 64) + ".example.com.")));

        assertThat(query("alias.example.com.", 5).getHeader().getRcode(), is(Rcode.SERVFAIL));
    }

    /** The boundary case immediately below the limit must still succeed. */
    @Test
    public void shouldAcceptALabelOfExactlySixtyThreeOctets() throws Exception {
        String label = repeat('a', 63);
        expectDnsResponse("alias.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.cnameRecord("alias.example.com.", label + ".example.com.")));

        Message message = query("alias.example.com.", 5);

        assertThat(message.getHeader().getRcode(), is(Rcode.NOERROR));
        CNAMERecord answer = (CNAMERecord) message.getSection(Section.ANSWER).get(0);
        assertThat(answer.getTarget().toString(), is(label + ".example.com."));
    }

    /** RFC 1035 §2.3.4 — the whole name is capped at 255 octets even when every label is legal. */
    @Test
    public void shouldReturnServfailForANameExceedingTwoHundredAndFiftyFiveOctets() throws Exception {
        String longName = repeat('a', 60) + "." + repeat('b', 60) + "." + repeat('c', 60)
            + "." + repeat('d', 60) + "." + repeat('e', 60) + ".example.com.";
        expectDnsResponse("alias.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.cnameRecord("alias.example.com.", longName)));

        assertThat(query("alias.example.com.", 5).getHeader().getRcode(), is(Rcode.SERVFAIL));
    }

    /**
     * Netty's {@code DefaultDnsRawRecord} constructor validates individual <em>label</em> lengths,
     * so this case reaches the SERVFAIL path via the library's own check rather than MockServer's.
     * Kept as a regression guard, but note it does <em>not</em> cover the total-name-length half —
     * see the next test, which is the case Netty does not validate.
     */
    @Test
    public void shouldReturnServfailForAnOwnerNameWithAnOverLongLabel() throws Exception {
        expectDnsResponse("api.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aRecord(repeat('a', 64) + ".example.com.", "10.0.0.1")));

        assertThat(query("api.example.com.", 1).getHeader().getRcode(), is(Rcode.SERVFAIL));
    }

    /**
     * Every label here is individually legal (60 octets), but the name totals well over the RFC 1035
     * §2.3.4 limit of 255 octets. Netty's constructor does <b>not</b> check total name length — only
     * label length — so this must be validated by MockServer, inside the encode try/catch. Without
     * that, the length check fires later from the response-size accounting, outside the catch: the
     * exception escapes {@code channelRead0}, no response is written at all, and the client hangs
     * until it times out.
     */
    @Test
    public void shouldReturnServfailForAnOwnerNameOverTwoHundredAndFiftyFiveOctetsWithLegalLabels() throws Exception {
        StringBuilder ownerName = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            ownerName.append(repeat('b', 60)).append('.');
        }
        ownerName.append("example.com.");
        // These preconditions are load-bearing, not decorative: if a future edit made any single
        // label exceed 63 octets, this test would still pass — but via Netty's own label check
        // rather than MockServer's total-length check, silently covering the wrong half.
        for (String label : ownerName.toString().split("\\.")) {
            assertThat("precondition: label \"" + label + "\" must be individually legal, so that "
                + "this test can only pass via the total-length check", label.length() <= 63, is(true));
        }
        assertThat("precondition: the whole name must exceed the 255 octet limit",
            ownerName.length() > 255, is(true));

        expectDnsResponse("api.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aRecord(ownerName.toString(), "10.0.0.1")));

        // The assertNotNull inside query() is the real guard here: before the fix the handler
        // emitted no datagram at all.
        assertThat(query("api.example.com.", 1).getHeader().getRcode(), is(Rcode.SERVFAIL));
    }

    // ---------------------------------------------- defect 2: TXT truncation

    /**
     * RFC 1035 §3.3.14 — TXT RDATA is a sequence of character-strings, each at most 255 octets.
     * Values longer than that must be split, not truncated. Truncation silently corrupted DKIM
     * keys and long SPF records, the two commonest real TXT payloads.
     */
    @Test
    public void shouldSplitATxtValueLongerThan255OctetsAcrossCharacterStrings() throws Exception {
        String value = repeat('x', 300);
        expectDnsResponse("txt.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.txtRecord("txt.example.com.", value)));

        Message message = query("txt.example.com.", 16);

        TXTRecord answer = (TXTRecord) message.getSection(Section.ANSWER).get(0);
        List<String> strings = answer.getStrings();
        assertThat("value must be split, not truncated", strings.size(), is(2));
        assertThat(strings.get(0).length(), is(255));
        assertThat(strings.get(1).length(), is(45));
        assertThat("concatenation must round-trip the configured value",
            String.join("", strings), is(value));
    }

    /** A realistic DKIM public key is ~400 octets — the payload truncation used to corrupt. */
    @Test
    public void shouldRoundTripADkimSizedTxtValue() throws Exception {
        String value = "v=DKIM1; k=rsa; p=" + repeat('A', 392);
        expectDnsResponse("selector._domainkey.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.txtRecord("selector._domainkey.example.com.", value)));

        Message message = query("selector._domainkey.example.com.", 16);

        TXTRecord answer = (TXTRecord) message.getSection(Section.ANSWER).get(0);
        assertThat(String.join("", answer.getStrings()), is(value));
    }

    @Test
    public void shouldEmitATxtValueOfExactly255OctetsAsASingleCharacterString() throws Exception {
        String value = repeat('x', 255);
        expectDnsResponse("txt.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.txtRecord("txt.example.com.", value)));

        TXTRecord answer = (TXTRecord) query("txt.example.com.", 16).getSection(Section.ANSWER).get(0);

        assertThat(answer.getStrings().size(), is(1));
        assertThat(answer.getStrings().get(0), is(value));
    }

    /**
     * Truncation at a fixed 255-octet offset could also cut through the middle of a multi-byte
     * UTF-8 sequence. Splitting preserves the octets, so concatenation restores the value.
     */
    @Test
    public void shouldNotCorruptMultiByteUtf8InALongTxtValue() throws Exception {
        // 254 ASCII octets + a two-octet 'é' puts the 255-octet split boundary exactly in the
        // middle of the multi-byte sequence, so the two character-strings are only lossless if
        // the resolver's concatenation restores the original octets.
        String value = repeat('e', 254) + "é";
        expectDnsResponse("txt.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.txtRecord("txt.example.com.", value)));

        TXTRecord answer = (TXTRecord) query("txt.example.com.", 16).getSection(Section.ANSWER).get(0);

        List<byte[]> chunks = answer.getStringsAsByteArrays();
        assertThat(chunks.size(), is(2));
        assertThat("split falls mid-UTF-8-sequence", chunks.get(0).length, is(255));
        assertThat(chunks.get(1).length, is(1));

        ByteArrayOutputStream rejoined = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            rejoined.write(chunk);
        }
        assertThat(new String(rejoined.toByteArray(), StandardCharsets.UTF_8), is(value));
    }

    // --------------------------------------- defect 3: address width vs record type

    /**
     * RFC 1035 §3.4.1 — A RDATA is exactly 4 octets. NetUtil returns 16 for an IPv6 literal
     * regardless of the declared type, so this used to emit TYPE=A with RDLENGTH=16.
     */
    @Test
    public void shouldReturnServfailForAnARecordConfiguredWithAnIpv6Address() throws Exception {
        expectDnsResponse("bad.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aRecord("bad.example.com.", "2001:db8::1")));

        assertThat(query("bad.example.com.", 1).getHeader().getRcode(), is(Rcode.SERVFAIL));
    }

    /** RFC 3596 §2.2 — AAAA RDATA is exactly 16 octets. */
    @Test
    public void shouldReturnServfailForAnAaaaRecordConfiguredWithAnIpv4Address() throws Exception {
        expectDnsResponse("bad.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aaaaRecord("bad.example.com.", "10.0.0.1")));

        assertThat(query("bad.example.com.", 28).getHeader().getRcode(), is(Rcode.SERVFAIL));
    }

    @Test
    public void shouldReturnServfailForAnUnparseableIpAddress() throws Exception {
        expectDnsResponse("bad.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aRecord("bad.example.com.", "not-an-ip")));

        assertThat(query("bad.example.com.", 1).getHeader().getRcode(), is(Rcode.SERVFAIL));
    }

    // --------------------------------------------- defect 4: root-zone owner name

    /**
     * An absent owner name used to encode as the root zone, producing a NOERROR/ANCOUNT=1
     * response that carries no answer for the name the client asked about — the "looks
     * successful, is useless" signature. It now defaults to the queried name.
     */
    @Test
    public void shouldDefaultAnAbsentOwnerNameToTheQueriedName() throws Exception {
        expectDnsResponse("api.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.dnsRecord()
                .withType(org.mockserver.model.DnsRecordType.A)
                .withValue("10.0.0.1")));

        Message message = query("api.example.com.", 1);

        ARecord answer = (ARecord) message.getSection(Section.ANSWER).get(0);
        assertThat("owner name must not be the root zone", answer.getName().toString(), is("api.example.com."));
    }

    @Test
    public void shouldPreferAnExplicitOwnerNameOverTheQueriedName() throws Exception {
        expectDnsResponse("api.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aRecord("other.example.com.", "10.0.0.1")));

        ARecord answer = (ARecord) query("api.example.com.", 1).getSection(Section.ANSWER).get(0);

        assertThat(answer.getName().toString(), is("other.example.com."));
    }

    // ------------------------------------------------- defect 6: header flags

    /**
     * systemd-resolved treats RA=0 as "this server offers no recursion" and moves to the next
     * configured server, so a mock that never sets RA is skipped entirely.
     */
    @Test
    public void shouldSetRecursionAvailableAndAuthoritativeAnswer() throws Exception {
        expectDnsResponse("api.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aRecord("api.example.com.", "10.0.0.1")));

        Message message = query("api.example.com.", 1);

        assertThat("RA must be set", message.getHeader().getFlag(Flags.RA), is(true));
        assertThat("AA must be set", message.getHeader().getFlag(Flags.AA), is(true));
        assertThat("QR must be set", message.getHeader().getFlag(Flags.QR), is(true));
    }

    @Test
    public void shouldEchoRecursionDesiredFromTheQuery() throws Exception {
        expectDnsResponse("api.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aRecord("api.example.com.", "10.0.0.1")));

        assertThat(query("api.example.com.", 1, true, 0).getHeader().getFlag(Flags.RD), is(true));
        assertThat(query("api.example.com.", 1, false, 0).getHeader().getFlag(Flags.RD), is(false));
    }

    @Test
    public void shouldSetFlagsOnNxdomainResponsesToo() throws Exception {
        Message message = query("unknown.example.com.", 1);

        assertThat(message.getHeader().getRcode(), is(Rcode.NXDOMAIN));
        assertThat(message.getHeader().getFlag(Flags.RA), is(true));
    }

    // ------------------------------------- defect 5 (partial): TC bit / 512-octet limit

    /**
     * RFC 1035 §4.2.1 — a UDP response over 512 octets must be sent truncated with TC set so the
     * resolver retries over TCP. Oversized responses previously went out with TC=0, leaving the
     * client to parse a silently short answer set.
     */
    @Test
    public void shouldSetTheTruncatedBitWhenTheResponseExceedsTheUdpPayloadLimit() throws Exception {
        DnsRecord[] many = new DnsRecord[40];
        for (int i = 0; i < many.length; i++) {
            many[i] = DnsRecord.aRecord("api.example.com.", "10.0.0." + (i + 1));
        }
        expectDnsResponse("api.example.com.", new DnsResponse().withAnswerRecords(many));

        Message message = query("api.example.com.", 1);

        assertThat("TC must be set", message.getHeader().getFlag(Flags.TC), is(true));
        assertThat("response must fit the 512 octet limit", message.toWire().length <= 512, is(true));
        assertThat("records that fit are still returned",
            message.getSection(Section.ANSWER).size() > 0, is(true));
    }

    @Test
    public void shouldNotSetTheTruncatedBitWhenTheResponseFits() throws Exception {
        expectDnsResponse("api.example.com.", new DnsResponse()
            .withAnswerRecords(DnsRecord.aRecord("api.example.com.", "10.0.0.1")));

        assertThat(query("api.example.com.", 1).getHeader().getFlag(Flags.TC), is(false));
    }

    /** RFC 6891 §6.1.2 — an EDNS(0) client advertising a larger buffer should not be truncated. */
    @Test
    public void shouldHonourAnEdnsAdvertisedPayloadSizeBeforeTruncating() throws Exception {
        DnsRecord[] many = new DnsRecord[40];
        for (int i = 0; i < many.length; i++) {
            many[i] = DnsRecord.aRecord("api.example.com.", "10.0.0." + (i + 1));
        }
        expectDnsResponse("api.example.com.", new DnsResponse().withAnswerRecords(many));

        Message message = query("api.example.com.", 1, true, 4096);

        assertThat("EDNS client can take the full response", message.getHeader().getFlag(Flags.TC), is(false));
        assertThat(message.getSection(Section.ANSWER).size(), is(40));
    }
}
